package run.fabian.daemon.binary;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Spec 0001 slice 1, the contract-level scenarios: a sandbox's project file becomes one host-set
 * request when the sandbox registers, the human decides it as a whole, every host is applied,
 * singles and sets know about each other, and the explicit admin trigger behaves.
 */
class ProjectHostSetTest {

    private static final String THREE_HOSTS = """
            version: 1
            hosts:
              - a.example
              - b.example
              - c.example
            """;

    /** A workspace directory with (or, for null, without) a project file. */
    private static Path workspace(String yaml) {
        try {
            Path dir = Files.createTempDirectory("goversbx-ws");
            if (yaml != null) {
                Path file = dir.resolve(".goversbx").resolve("network.yaml");
                Files.createDirectories(file.getParent());
                Files.writeString(file, yaml);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String lsRow(String name, String id, Path workspace) {
        String workspaces = workspace == null ? "[]" : "[\"" + workspace.toString().replace("\\", "\\\\") + "\"]";
        return "{\"name\":\"%s\",\"id\":\"%s\",\"status\":\"running\",\"agent\":\"claude\",\"workspaces\":%s}".formatted(name, id, workspaces);
    }

    private static String ls(String... rows) {
        return "[" + String.join(",", rows) + "]";
    }

    /** The reactive registration trigger: a call with the raw placeholder. */
    private static String placeholderCall(DaemonProcess daemon) {
        return Mcp.text(daemon.mcp(DaemonProcess.PLACEHOLDER).initialize()
                .callTool("request_network_access", Mcp.args("host", "trigger.example", "reason", "r", "timeoutSeconds", 1)));
    }

    private static List<String> policyCalls(FakeSbx sbx) {
        return sbx.calls().stream().filter(c -> c.startsWith("policy")).toList();
    }

    private static List<String> hosts(JsonNode request) {
        List<String> hosts = new java.util.ArrayList<>();
        request.path("hosts").forEach(h -> hosts.add(h.asText()));
        return hosts;
    }

    @Test
    void theProjectFileBecomesOnePendingSetWhenTheSandboxRegisters() {
        Path ws = workspace(THREE_HOSTS);
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-1", "i1", ws)));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            assertTrue(placeholderCall(daemon).contains("registering it now"));
            JsonNode set = daemon.awaitPendingHostSet("proj-1");
            assertEquals(List.of("a.example", "b.example", "c.example"), hosts(set));
            assertEquals("PROJECT_FILE", set.path("origin").asText());
            assertTrue(set.path("host").isNull(), "a set has no single host: " + set);
            assertEquals(ws.resolve(".goversbx").resolve("network.yaml").toString(), set.path("source").asText());
            assertEquals(0, set.path("profiles").size());
            assertTrue(set.path("reason").asText().contains("network.yaml"), set.toString());
            assertEquals(1, daemon.hostSetsOf("proj-1").size(), "exactly one set");
            // The trigger call itself created no request, and the set is the only pending one.
            assertEquals(1, daemon.adminJson("/api/requests?status=pending").size());
            assertTrue(daemon.log().contains("Host set of 3 host(s) for proj-1"), daemon.log());
        }
    }

    @Test
    void profilesFromTheUsersConfigDirResolveFirst() throws IOException {
        Path configHome = Files.createTempDirectory("goversbx-xdg");
        Path profiles = Files.createDirectories(configHome.resolve("goversbx").resolve("profiles"));
        Files.writeString(profiles.resolve("jvm-build.yaml"), "version: 1\nhosts: [services.gradle.org, plugins.gradle.org]\n");
        Path ws = workspace("version: 1\nprofiles: [jvm-build]\nhosts: [plugins.gradle.org, api.example.com]\n");
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-p", "ip", ws)));
        try (DaemonProcess daemon = DaemonProcess.start(sbx, DaemonProcess.env("XDG_CONFIG_HOME", configHome.toString()))) {
            placeholderCall(daemon);
            JsonNode set = daemon.awaitPendingHostSet("proj-p");
            assertEquals(List.of("services.gradle.org", "plugins.gradle.org", "api.example.com"), hosts(set));
            assertEquals("jvm-build", set.path("profiles").get(0).asText());
            assertTrue(daemon.log().contains(profiles.toString()), "the daemon logs where profiles live:\n" + daemon.log());
        }
    }

    @Test
    void anInvalidFileProducesNoRequestAndIsLoggedWithItsPath() {
        Path ws = workspace("version: 1\nhosts: [a.example, '*.example']\n");
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-bad", "ib", ws)));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            placeholderCall(daemon);
            DaemonProcess.sleep(500);
            assertTrue(daemon.hostSetsOf("proj-bad").isEmpty());
            String log = daemon.log();
            assertTrue(log.contains("Ignoring the project host set for proj-bad"), log);
            assertTrue(log.contains(ws.resolve(".goversbx").resolve("network.yaml").toString()), log);
            assertTrue(log.contains("Wildcard"), log);
        }
    }

    @Test
    void noFileNoRequest() {
        Path ws = workspace(null);
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-none", "in", ws)));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            placeholderCall(daemon);
            DaemonProcess.sleep(500);
            assertTrue(daemon.hostSetsOf("proj-none").isEmpty());
            assertFalse(daemon.log().contains("Host set of"), daemon.log());
            assertFalse(daemon.log().contains("Ignoring the project host set"), daemon.log());
        }
    }

    @Test
    void approvalAppliesEveryHostInFileOrderAndRecordsEachOutcome() {
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-ok", "io", workspace(THREE_HOSTS))));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            placeholderCall(daemon);
            JsonNode set = daemon.awaitPendingHostSet("proj-ok");

            JsonNode decided = daemon.decide(set.path("id").asText(), "approve");

            assertEquals("APPROVED", decided.path("status").asText());
            assertTrue(decided.path("sbxApplied").asBoolean());
            assertEquals(List.of(
                    "policy allow network a.example --sandbox proj-ok",
                    "policy allow network b.example --sandbox proj-ok",
                    "policy allow network c.example --sandbox proj-ok"), policyCalls(sbx));
            assertEquals(3, decided.path("hostResults").size());
            decided.path("hostResults").forEach(r -> assertTrue(r.path("applied").asBoolean(), r.toString()));
            assertEquals("Added rule (fake sbx)", decided.path("hostResults").get(2).path("output").asText());
            assertTrue(daemon.log().contains("sbx policy allow network c.example --sandbox proj-ok succeeded"), daemon.log());
        }
    }

    @Test
    void aPartialSbxFailureIsVisiblePerHost() {
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-part", "ipt", workspace(THREE_HOSTS)))).failPolicyFor("b.example");
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            placeholderCall(daemon);
            JsonNode decided = daemon.decide(daemon.awaitPendingHostSet("proj-part").path("id").asText(), "approve");

            assertEquals("APPROVED", decided.path("status").asText());
            assertFalse(decided.path("sbxApplied").asBoolean());
            JsonNode results = decided.path("hostResults");
            assertTrue(results.get(0).path("applied").asBoolean());
            assertFalse(results.get(1).path("applied").asBoolean());
            assertEquals("b.example", results.get(1).path("host").asText());
            assertEquals("fake sbx: refused b.example", results.get(1).path("output").asText());
            assertTrue(results.get(2).path("applied").asBoolean());
            assertTrue(decided.path("sbxOutput").asText().contains("b.example: fake sbx: refused b.example"), decided.toString());
            assertEquals(3, policyCalls(sbx).size());
            // The failed host stays requestable by the agent: a single request for it is pending, not shortcut.
            var call = daemon.asSandbox("proj-part").requestAccessAsync("b.example", 30);
            JsonNode single = daemon.awaitPending("b.example");
            daemon.decide(single.path("id").asText(), "deny");
            call.join();
        }
    }

    @Test
    void denialAppliesNothing() {
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-deny", "id", workspace(THREE_HOSTS))));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            placeholderCall(daemon);
            JsonNode decided = daemon.decide(daemon.awaitPendingHostSet("proj-deny").path("id").asText(), "deny");
            assertEquals("DENIED", decided.path("status").asText());
            assertTrue(policyCalls(sbx).isEmpty(), sbx.calls().toString());
            assertTrue(decided.path("hostResults").isNull());
        }
    }

    @Test
    void anApprovedSetResolvesThePendingSingleRequestOfTheSameSandbox() {
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-single", "is", workspace(THREE_HOSTS))));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            placeholderCall(daemon);
            JsonNode set = daemon.awaitPendingHostSet("proj-single");
            // The agent ran into one of the set's hosts before the human decided the set.
            Mcp agent = daemon.asSandbox("proj-single");
            var waiting = agent.requestAccessAsync("b.example", 60);
            JsonNode single = daemon.awaitPending("b.example");

            daemon.decide(set.path("id").asText(), "approve");

            String text = Mcp.text(waiting.join());
            assertTrue(text.startsWith("Approved - network access to 'b.example'"), text);
            JsonNode resolved = daemon.requestsFor("b.example").get(0);
            assertEquals(single.path("id").asText(), resolved.path("id").asText());
            assertEquals("APPROVED", resolved.path("status").asText());
            assertEquals("binary-test", resolved.path("decidedBy").asText());
            assertTrue(resolved.path("sbxApplied").asBoolean());
            // Applied once, by the set - not again for the single.
            assertEquals(3, policyCalls(sbx).size(), sbx.calls().toString());
        }
    }

    @Test
    void aRequestForAnAlreadyGrantedHostIsAnsweredAtOnce() {
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-granted", "ig", workspace(THREE_HOSTS))));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            placeholderCall(daemon);
            daemon.decide(daemon.awaitPendingHostSet("proj-granted").path("id").asText(), "approve");
            Mcp agent = daemon.asSandbox("proj-granted");

            long started = System.nanoTime();
            String text = Mcp.text(agent.callTool("request_network_access",
                    Mcp.args("host", "c.example", "reason", "proactive", "timeoutSeconds", 60)));
            long millis = (System.nanoTime() - started) / 1_000_000;

            assertTrue(text.startsWith("Approved - network access to 'c.example'"), text);
            assertTrue(millis < 5_000, "answered without waiting for a human, took " + millis + "ms");
            JsonNode answered = daemon.requestsFor("c.example").get(0);
            assertEquals("APPROVED", answered.path("status").asText());
            assertEquals("goversbx", answered.path("decidedBy").asText());
            assertTrue(daemon.adminJson("/api/requests?status=pending").isEmpty());
            assertEquals(3, policyCalls(sbx).size(), "no sbx call for the already granted host: " + sbx.calls());
        }
    }

    @Test
    void grantsAreScopedToTheSandbox() {
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-a", "ia", workspace(THREE_HOSTS)), lsRow("proj-b", "ib", null)));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            placeholderCall(daemon);
            daemon.decide(daemon.awaitPendingHostSet("proj-a").path("id").asText(), "approve");
            DaemonProcess.sleep(300);
            assertTrue(daemon.hostSetsOf("proj-b").isEmpty(), "a workspace-less sandbox gets no set");

            var call = daemon.asSandbox("proj-b").requestAccessAsync("a.example", 30);
            JsonNode pending = daemon.awaitPending("a.example");
            assertEquals("proj-b", pending.path("sandboxName").asText());
            daemon.decide(pending.path("id").asText(), "deny");
            call.join();
            assertTrue(policyCalls(sbx).stream().allMatch(c -> c.endsWith("--sandbox proj-a")), sbx.calls().toString());
        }
    }

    @Test
    void theExplicitTriggerReportsCreatedPendingGrantedInvalidAndUnknown() throws IOException {
        Path ws = workspace(null);
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-trig", "it", ws)));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            daemon.tokenFor("proj-trig");                       // registers (admin endpoint); no file yet -> no set
            Path file = ws.resolve(".goversbx").resolve("network.yaml");
            Files.createDirectories(file.getParent());
            Files.writeString(file, THREE_HOSTS);

            HttpResponse<String> created = trigger(daemon, "proj-trig");
            assertEquals(201, created.statusCode(), created.body());
            JsonNode body = DaemonProcess.parse(created.body());
            assertEquals("proj-trig", body.path("sandboxName").asText());
            assertEquals(ws.toString(), body.path("workspaces").get(0).asText());
            JsonNode evaluation = body.path("evaluations").get(0);
            assertEquals("CREATED", evaluation.path("kind").asText());
            assertEquals(ws.toString(), evaluation.path("workspace").asText());
            String id = evaluation.path("request").path("id").asText();
            assertEquals(List.of("a.example", "b.example", "c.example"), hosts(evaluation.path("request")));

            HttpResponse<String> again = trigger(daemon, "proj-trig");
            assertEquals(200, again.statusCode(), again.body());
            JsonNode pendingEvaluation = DaemonProcess.parse(again.body()).path("evaluations").get(0);
            assertEquals("ALREADY_PENDING", pendingEvaluation.path("kind").asText());
            assertEquals(id, pendingEvaluation.path("request").path("id").asText());

            daemon.decide(id, "approve");
            HttpResponse<String> granted = trigger(daemon, "proj-trig");
            assertEquals(200, granted.statusCode(), granted.body());
            assertEquals("ALL_GRANTED", DaemonProcess.parse(granted.body()).path("evaluations").get(0).path("kind").asText());

            Files.writeString(file, "version: 2\nhosts: [a.example]\n");
            HttpResponse<String> invalid = trigger(daemon, "proj-trig");
            assertEquals(400, invalid.statusCode(), invalid.body());
            JsonNode invalidEvaluation = DaemonProcess.parse(invalid.body()).path("evaluations").get(0);
            assertEquals("INVALID", invalidEvaluation.path("kind").asText());
            assertTrue(invalidEvaluation.path("problems").get(0).asText().contains("version 2 is not supported"), invalid.body());

            HttpResponse<String> unknown = trigger(daemon, "never-registered");
            assertEquals(404, unknown.statusCode(), unknown.body());
            assertTrue(DaemonProcess.parse(unknown.body()).path("error").asText().contains("not registered"));

            // Token-gated like every admin route.
            HttpResponse<String> noToken = DaemonProcess.send(HttpRequest.newBuilder(daemon.admin("/api/sandboxes/proj-trig/host-set-requests"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build());
            assertEquals(401, noToken.statusCode());
        }
    }

    @Test
    void aRecreatedSandboxGetsAFreshSetEvenThoughTheOldOneWasApproved() {
        Path ws = workspace(THREE_HOSTS);
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-re", "first-id", ws)));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            placeholderCall(daemon);
            daemon.decide(daemon.awaitPendingHostSet("proj-re").path("id").asText(), "approve");
            assertEquals(200, trigger(daemon, "proj-re").statusCode(), "everything granted to the first incarnation");

            sbx.lsJson(ls(lsRow("proj-re", "second-id", ws)));   // removed and created again under the same name
            assertTrue(placeholderCall(daemon).contains("registering it now"));

            JsonNode fresh = daemon.awaitPendingHostSet("proj-re");
            assertEquals(List.of("a.example", "b.example", "c.example"), hosts(fresh));
            assertEquals(2, daemon.hostSetsOf("proj-re").size());
            assertTrue(daemon.log().contains("Forgot 3 grant(s) recorded for proj-re"), daemon.log());
        }
    }

    @Test
    void theEventStreamCarriesASetAsOneCreatedEventWithEveryHost() {
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-sse", "ie", workspace(THREE_HOSTS))));
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            List<String> lines = new CopyOnWriteArrayList<>();
            var stream = DaemonProcess.HTTP.sendAsync(
                    HttpRequest.newBuilder(daemon.admin("/api/events?token=" + DaemonProcess.ADMIN_TOKEN))
                            .header("Accept", "text/event-stream").build(),
                    HttpResponse.BodyHandlers.ofLines());
            stream.thenAccept(response -> {
                try (Stream<String> body = response.body()) {
                    body.forEach(lines::add);
                }
            });
            awaitEvent(lines, "ready", "");

            placeholderCall(daemon);
            JsonNode created = awaitEvent(lines, "created", "PROJECT_FILE");
            assertEquals(List.of("a.example", "b.example", "c.example"), hosts(created));
            assertEquals("proj-sse", created.path("sandboxName").asText());
            assertEquals(1, lines.stream().filter(l -> l.replace(" ", "").equals("event:created")).count(), "one event for the set: " + lines);
            stream.cancel(true);
        }
    }

    @Test
    void afterARestartTheFullSetIsRequestedAgain() throws IOException {
        // Grants live in memory (known limitation until slice 3): a restarted daemon asks again, which is
        // harmless because sbx still holds the rules - pinned so a change of this behavior is deliberate.
        Path ws = workspace(THREE_HOSTS);
        FakeSbx sbx = FakeSbx.create().lsJson(ls(lsRow("proj-restart", "ir", ws)));
        Map<String, String> sameStateDir = DaemonProcess.env("GOVERSBX_STATE_DIR", Files.createTempDirectory("goversbx-state").toString());
        try (DaemonProcess daemon = DaemonProcess.start(sbx, sameStateDir)) {
            placeholderCall(daemon);
            daemon.decide(daemon.awaitPendingHostSet("proj-restart").path("id").asText(), "approve");
            assertEquals(200, trigger(daemon, "proj-restart").statusCode());
        }
        try (DaemonProcess restarted = DaemonProcess.start(sbx, sameStateDir)) {
            HttpResponse<String> response = trigger(restarted, "proj-restart");
            assertEquals(201, response.statusCode(), response.body());
            assertEquals(List.of("a.example", "b.example", "c.example"),
                    hosts(DaemonProcess.parse(response.body()).path("evaluations").get(0).path("request")));
        }
    }

    private static HttpResponse<String> trigger(DaemonProcess daemon, String sandbox) {
        return daemon.postJson(daemon.admin("/api/sandboxes/" + sandbox + "/host-set-requests"), "");
    }

    private static JsonNode awaitEvent(List<String> lines, String event, String contains) {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i + 1 < lines.size(); i++) {
                if (lines.get(i).replace(" ", "").equals("event:" + event) && lines.get(i + 1).startsWith("data:")
                        && lines.get(i + 1).contains(contains)) {
                    return DaemonProcess.parse(lines.get(i + 1).substring(5).trim());
                }
            }
            DaemonProcess.sleep(50);
        }
        return fail("no '" + event + "' event mentioning '" + contains + "' within 15s, got: " + lines);
    }
}
