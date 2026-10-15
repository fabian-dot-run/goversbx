package run.fabian.daemon.binary;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static run.fabian.daemon.binary.DaemonProcess.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * initialize with every client capability and clientInfo field the MCP SDK knows, generated from
 * the SDK's own records - so a field a newer SDK adds is covered without touching this test. A
 * native binary missing reflection metadata for one of these types fails exactly here (it did:
 * {@code ClientCapabilities$RootCapabilities}, with Claude Code, in v0.2.0).
 */
class ClientCapabilityMatrixTest {

    private static DaemonProcess daemon;
    private static String matrixToken;

    @BeforeAll
    static void start() {
        daemon = DaemonProcess.start(FakeSbx.create());
        matrixToken = daemon.tokenFor("matrix");
    }

    @AfterAll
    static void stop() {
        daemon.close();
    }

    @TestFactory
    Collection<DynamicTest> everyCapabilityAloneAndAllTogether() {
        ObjectNode all = generate(McpSchema.ClientCapabilities.class, 0);
        List<DynamicTest> tests = new ArrayList<>();
        all.fieldNames().forEachRemaining(name -> {
            ObjectNode single = JSON.createObjectNode().set(name, all.get(name));
            tests.add(DynamicTest.dynamicTest("capability " + name + " = " + single, () -> initializeWith(single, minimalInfo())));
        });
        tests.add(DynamicTest.dynamicTest("all capabilities + full clientInfo: " + all,
                () -> initializeWith(all, generate(McpSchema.Implementation.class, 0))));
        tests.add(DynamicTest.dynamicTest("capabilities with unknown extra fields",
                () -> initializeWith((ObjectNode) DaemonProcess.parse("""
                        {"roots":{"listChanged":true,"futureFlag":1},"futureCapability":{"x":[1,2]}}"""), minimalInfo())));
        return tests;
    }

    private static void initializeWith(ObjectNode capabilities, JsonNode clientInfo) {
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", "2025-11-25");
        params.set("capabilities", capabilities);
        params.set("clientInfo", clientInfo);
        Mcp mcp = daemon.mcp(matrixToken).initialize(params.toString());
        assertEquals(2, mcp.request("tools/list", "{}").path("result").path("tools").size());
        assertEquals("Still pending", Mcp.text(mcp.callTool("request_network_access",
                Mcp.args("host", "matrix.example", "reason", "r", "timeoutSeconds", 1))).substring(0, 13));
    }

    private static ObjectNode minimalInfo() {
        return (ObjectNode) DaemonProcess.parse("{\"name\":\"matrix\",\"version\":\"0\"}");
    }

    /** A JSON object with every component of a record set to a plausible value, recursively. */
    private static ObjectNode generate(Class<?> record, int depth) {
        ObjectNode node = JSON.createObjectNode();
        for (RecordComponent component : record.getRecordComponents()) {
            JsonProperty property = component.getAccessor().getAnnotation(JsonProperty.class);
            String name = property != null && !property.value().isEmpty() ? property.value() : component.getName();
            node.set(name, value(component.getGenericType(), depth));
        }
        return node;
    }

    private static JsonNode value(Type type, int depth) {
        Class<?> raw = type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
        if (raw == String.class) return JSON.getNodeFactory().textNode("x");
        if (raw == Boolean.class || raw == boolean.class) return JSON.getNodeFactory().booleanNode(true);
        if (Number.class.isAssignableFrom(raw) || raw.isPrimitive()) return JSON.getNodeFactory().numberNode(1);
        if (raw.isEnum()) return JSON.getNodeFactory().textNode(raw.getEnumConstants()[0].toString().toLowerCase());
        if (raw.isRecord() && depth < 4) return generate(raw, depth + 1);
        if (List.class.isAssignableFrom(raw) && type instanceof ParameterizedType p && depth < 4) {
            ArrayNode array = JSON.createArrayNode();
            array.add(value(p.getActualTypeArguments()[0], depth + 1));
            return array;
        }
        if (Map.class.isAssignableFrom(raw)) return JSON.createObjectNode().set("x", JSON.createObjectNode());
        return JSON.createObjectNode();
    }
}
