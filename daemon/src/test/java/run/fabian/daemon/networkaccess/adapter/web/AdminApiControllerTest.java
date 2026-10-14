package run.fabian.daemon.networkaccess.adapter.web;

import com.fasterxml.jackson.core.type.TypeReference;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.testtools.JavalinTest;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.adapter.persistence.InMemoryRequestRepository;
import run.fabian.daemon.networkaccess.adapter.sbx.SbxCliPolicyAdapter;
import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;
import run.fabian.daemon.networkaccess.application.NetworkAccessService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminApiControllerTest {

    private static Javalin newApp(NetworkAccessService service) {
        Javalin app = Javalin.create(config -> config.jsonMapper(new JavalinJackson(JsonSupport.MAPPER, false)));
        new AdminApiController(service).registerRoutes(app);
        return app;
    }

    private static RequestBody jsonBody(String json) {
        return RequestBody.create(json, MediaType.get("application/json"));
    }

    @Test
    void healthEndpointIsOk() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new SbxCliPolicyAdapter("sbx", true));

        JavalinTest.test(newApp(service), (server, client) -> {
            Response response = client.get("/api/health");
            assertEquals(200, response.code());
        });
    }

    @Test
    void listPendingThenApproveThenRejectsSecondDecision() throws Exception {
        InMemoryRequestRepository repository = new InMemoryRequestRepository();
        NetworkAccessService service = new NetworkAccessService(repository, new SbxCliPolicyAdapter("sbx", true));
        NetworkAccessRequestView created = service.createRequest("sandbox-1", "api.example.com", "fetch pricing");

        JavalinTest.test(newApp(service), (server, client) -> {
            Response listResponse = client.get("/api/requests?status=pending");
            assertEquals(200, listResponse.code());
            List<NetworkAccessRequestView> pending =
                    JsonSupport.MAPPER.readValue(listResponse.body().string(), new TypeReference<>() {
                    });
            assertEquals(1, pending.size());
            assertEquals(created.id(), pending.get(0).id());

            Response approveResponse = client.request("/api/requests/" + created.id() + "/approve",
                    b -> b.post(jsonBody("{\"decidedBy\":\"fabian\"}")));
            assertEquals(200, approveResponse.code());
            NetworkAccessRequestView approved = JsonSupport.MAPPER.readValue(approveResponse.body().string(), NetworkAccessRequestView.class);
            assertEquals("APPROVED", approved.status().name());
            assertTrue(approved.sbxApplied());

            Response secondApprove = client.request("/api/requests/" + created.id() + "/approve",
                    b -> b.post(jsonBody("{\"decidedBy\":\"someone-else\"}")));
            assertEquals(409, secondApprove.code());
        });
    }

    @Test
    void denyFlow() throws Exception {
        InMemoryRequestRepository repository = new InMemoryRequestRepository();
        NetworkAccessService service = new NetworkAccessService(repository, new SbxCliPolicyAdapter("sbx", true));
        NetworkAccessRequestView created = service.createRequest("sandbox-2", "evil.example.com", "just because");

        JavalinTest.test(newApp(service), (server, client) -> {
            Response denyResponse = client.request("/api/requests/" + created.id() + "/deny",
                    b -> b.post(jsonBody("{\"decidedBy\":\"fabian\"}")));
            assertEquals(200, denyResponse.code());
            NetworkAccessRequestView denied = JsonSupport.MAPPER.readValue(denyResponse.body().string(), NetworkAccessRequestView.class);
            assertEquals("DENIED", denied.status().name());
        });
    }

    @Test
    void approvingUnknownIdIs404() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new SbxCliPolicyAdapter("sbx", true));

        JavalinTest.test(newApp(service), (server, client) -> {
            Response response = client.request("/api/requests/" + java.util.UUID.randomUUID() + "/approve",
                    b -> b.post(jsonBody("{}")));
            assertEquals(404, response.code());
        });
    }

    @Test
    void invalidIdIs400() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new SbxCliPolicyAdapter("sbx", true));

        JavalinTest.test(newApp(service), (server, client) -> {
            Response response = client.request("/api/requests/not-a-uuid/approve", b -> b.post(jsonBody("{}")));
            assertEquals(400, response.code());
        });
    }
}
