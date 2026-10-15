package run.fabian.daemon.sandboxidentity.adapter.web;

import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.Response;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import run.fabian.daemon.networkaccess.adapter.web.JsonSupport;
import run.fabian.daemon.sandboxidentity.adapter.sbx.SbxCliCustomSecretAdapter;
import run.fabian.daemon.sandboxidentity.application.SandboxIdentityService;
import run.fabian.daemon.sandboxidentity.application.SandboxRegistrationResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxIdentityControllerTest {

    private static Javalin newApp(SandboxIdentityService service) {
        return Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(JsonSupport.MAPPER, false));
            new SandboxIdentityController(service).registerRoutes(config.routes);
        });
    }

    @Test
    void registeringASandboxReturnsTheDryRunOutput() throws Exception {
        SandboxIdentityService service = new SandboxIdentityService(
                new SbxCliCustomSecretAdapter("sbx", true), List::of,
                "localhost", "GOVERSBX_SANDBOX_TOKEN", "the-placeholder");

        JavalinTest.test(newApp(service), (server, client) -> {
            Response response = client.request("/api/sandboxes/sandbox-1/register",
                    b -> b.post(HttpRequest.BodyPublishers.ofString("")));

            assertEquals(200, response.code());
            SandboxRegistrationResult result =
                    JsonSupport.MAPPER.readValue(response.body().string(), SandboxRegistrationResult.class);
            assertEquals("sandbox-1", result.sandboxName());
            assertTrue(result.applied());
            assertTrue(result.output().contains("secret set-custom"));
            assertTrue(result.output().contains("sandbox-1"));
        });
    }
}
