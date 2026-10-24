package run.fabian.daemon.projecthosts.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.Response;
import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.adapter.persistence.InMemoryRequestRepository;
import run.fabian.daemon.networkaccess.adapter.web.JsonSupport;
import run.fabian.daemon.networkaccess.application.NetworkAccessService;
import run.fabian.daemon.networkaccess.application.port.SbxPolicyPort;
import run.fabian.daemon.projecthosts.application.ProjectHostSetService;
import run.fabian.daemon.projecthosts.domain.InvalidHostSetException;
import run.fabian.daemon.projecthosts.domain.ResolvedHostSet;
import run.fabian.daemon.sandboxidentity.api.RegisteredSandbox;
import run.fabian.daemon.sandboxidentity.api.SandboxIdentityApi;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectHostSetControllerTest {

    private static SandboxIdentityApi identity(RegisteredSandbox... known) {
        return new SandboxIdentityApi() {
            @Override
            public Optional<String> resolveSandboxName(String identityHeaderValue) {
                return Optional.empty();
            }

            @Override
            public void registerAnyUnregisteredSandboxes() {
            }

            @Override
            public Optional<RegisteredSandbox> findRegistered(String sandboxName) {
                return List.of(known).stream().filter(s -> s.name().equals(sandboxName)).findFirst();
            }
        };
    }

    private static Javalin app(ProjectHostSetService service, SandboxIdentityApi identity) {
        return Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(JsonSupport.MAPPER, false));
            new ProjectHostSetController(service, identity).registerRoutes(config.routes);
        });
    }

    private static Response post(io.javalin.testtools.HttpClient client, String name) {
        return client.request("/api/sandboxes/" + name + "/host-set-requests", b -> b.post(HttpRequest.BodyPublishers.ofString("")));
    }

    @Test
    void createdThenAlreadyPendingThenNothingAfterApproval() throws Exception {
        NetworkAccessService networkAccess = new NetworkAccessService(new InMemoryRequestRepository(),
                (sandbox, host) -> new SbxPolicyPort.Result(true, "ok"));
        ProjectHostSetService service = new ProjectHostSetService(
                ws -> Optional.of(new ResolvedHostSet(List.of("a.example", "b.example"), ws + "/.goversbx/network.yaml", List.of())), networkAccess);

        JavalinTest.test(app(service, identity(new RegisteredSandbox("sb", "id", List.of("/w")))), (server, client) -> {
            Response created = post(client, "sb");
            assertEquals(201, created.code());
            JsonNode body = JsonSupport.MAPPER.readTree(created.body().string());
            assertEquals("sb", body.path("sandboxName").asText());
            assertEquals("/w", body.path("workspaces").get(0).asText());
            JsonNode evaluation = body.path("evaluations").get(0);
            assertEquals("CREATED", evaluation.path("kind").asText());
            assertEquals("a.example", evaluation.path("request").path("hosts").get(0).asText());
            String id = evaluation.path("request").path("id").asText();

            Response again = post(client, "sb");
            assertEquals(200, again.code());
            JsonNode secondEvaluation = JsonSupport.MAPPER.readTree(again.body().string()).path("evaluations").get(0);
            assertEquals("ALREADY_PENDING", secondEvaluation.path("kind").asText());
            assertEquals(id, secondEvaluation.path("request").path("id").asText());

            networkAccess.approve(java.util.UUID.fromString(id), "fabian");
            Response afterwards = post(client, "sb");
            assertEquals(200, afterwards.code());
            assertEquals("ALL_GRANTED", JsonSupport.MAPPER.readTree(afterwards.body().string()).path("evaluations").get(0).path("kind").asText());
        });
    }

    @Test
    void anInvalidFileIs400WithTheProblems() throws Exception {
        ProjectHostSetService service = new ProjectHostSetService(ws -> {
            throw new InvalidHostSetException(ws + "/.goversbx/network.yaml", List.of("unknown key 'host'", "version 2 is not supported"));
        }, new NetworkAccessService(new InMemoryRequestRepository(), (sandbox, host) -> new SbxPolicyPort.Result(true, "ok")));

        JavalinTest.test(app(service, identity(new RegisteredSandbox("sb", "id", List.of("/w")))), (server, client) -> {
            Response response = post(client, "sb");
            assertEquals(400, response.code());
            JsonNode evaluation = JsonSupport.MAPPER.readTree(response.body().string()).path("evaluations").get(0);
            assertEquals("INVALID", evaluation.path("kind").asText());
            assertEquals(2, evaluation.path("problems").size());
        });
    }

    @Test
    void noFileIs200AndAnUnknownSandboxIs404() throws Exception {
        ProjectHostSetService service = new ProjectHostSetService(ws -> Optional.empty(),
                new NetworkAccessService(new InMemoryRequestRepository(), (sandbox, host) -> new SbxPolicyPort.Result(true, "ok")));

        JavalinTest.test(app(service, identity(new RegisteredSandbox("sb", "id", List.of("/w")))), (server, client) -> {
            Response none = post(client, "sb");
            assertEquals(200, none.code());
            assertEquals("NO_FILE", JsonSupport.MAPPER.readTree(none.body().string()).path("evaluations").get(0).path("kind").asText());

            Response unknown = post(client, "nope");
            assertEquals(404, unknown.code());
            assertTrue(unknown.body().string().contains("not registered"));
        });
    }

    @Test
    void textFormatPrintsReadyLinesForTheLauncher() throws Exception {
        NetworkAccessService networkAccess = new NetworkAccessService(new InMemoryRequestRepository(),
                (sandbox, host) -> new SbxPolicyPort.Result(true, "ok"));
        ProjectHostSetService service = new ProjectHostSetService(ws -> switch (ws.toString()) {
            case "/ok" -> Optional.of(new ResolvedHostSet(List.of("a.example", "b.example"), "/ok/.goversbx/network.yaml", List.of()));
            case "/bad" -> throw new InvalidHostSetException("/bad/.goversbx/network.yaml", List.of("unknown key 'host'"));
            default -> Optional.empty();
        }, networkAccess);

        JavalinTest.test(app(service, identity(new RegisteredSandbox("sb", "id", List.of("/ok", "/none", "/bad")))), (server, client) -> {
            Response created = client.request("/api/sandboxes/sb/host-set-requests?format=text",
                    b -> b.post(HttpRequest.BodyPublishers.ofString("")));
            assertEquals(201, created.code());
            assertEquals("""
                    project host set (2 hosts from /ok/.goversbx/network.yaml) is waiting for approval in the dashboard
                    /bad/.goversbx/network.yaml is invalid - no hosts requested:
                      - unknown key 'host'
                    """, created.body().string());

            Response unknown = client.request("/api/sandboxes/nope/host-set-requests?format=text",
                    b -> b.post(HttpRequest.BodyPublishers.ofString("")));
            assertEquals(404, unknown.code());
            assertTrue(unknown.body().string().contains("not registered"));
        });
    }
}
