package run.fabian.daemon.binary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reactive registration: spawning sbx, parsing `sbx ls` JSON, surviving a misbehaving sbx, surviving a restart. */
class SandboxIdentityTest {

    private static String placeholderCall(DaemonProcess daemon) {
        return Mcp.text(daemon.mcp(DaemonProcess.PLACEHOLDER).initialize()
                .callTool("request_network_access", Mcp.args("host", "id.example", "reason", "r", "timeoutSeconds", 1)));
    }

    @Test
    void registersRunningSandboxesMatchingThePatternWithExactArguments() {
        FakeSbx sbx = FakeSbx.create().lsJson("""
                [{"name":"match-1","id":"i1","status":"running","agent":"claude","workspaces":["/w"],"created_at":"2026-10-14T00:00:00Z"},
                 {"name":"match-2","id":"i2","status":"stopped"},
                 {"name":"other","id":"i3","status":"running"}]""");
        try (DaemonProcess daemon = DaemonProcess.start(sbx, DaemonProcess.env("GOVERSBX_IDENTITY_SANDBOX_PATTERN", "match-.*"))) {
            assertTrue(placeholderCall(daemon).contains("registering it now"));
        }
        List<String> secrets = sbx.calls().stream().filter(c -> c.startsWith("secret")).toList();
        assertEquals(1, secrets.size(), secrets.toString());
        // The substituted value is a per-sandbox secret token (gvsbx-<random>), never the name - that
        // is the F1 fix: a value a sandbox could type in by hand (its own or a victim's name) must not
        // resolve to an identity.
        assertTrue(secrets.get(0).matches("secret set-custom --sandbox match-1 --host localhost"
                        + " --env GOVERSBX_SANDBOX_TOKEN --placeholder " + Pattern.quote(DaemonProcess.PLACEHOLDER)
                        + " --value gvsbx-[A-Za-z0-9_-]+"),
                secrets.get(0));
    }

    @Test
    void registersEachSandboxOnlyOnce() {
        FakeSbx sbx = FakeSbx.create().running("once-1", "once-2");
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            placeholderCall(daemon);
            placeholderCall(daemon);
        }
        assertEquals(2, sbx.calls().stream().filter(c -> c.startsWith("secret")).count(), sbx.calls().toString());
        assertEquals(2, sbx.calls().stream().filter(c -> c.startsWith("ls")).count(), sbx.calls().toString());
    }

    @Test
    void lsFormatArgumentsAreConfigurable() {
        FakeSbx sbx = FakeSbx.create().running("fmt-1");
        try (DaemonProcess daemon = DaemonProcess.start(sbx, DaemonProcess.env("GOVERSBX_SBX_LS_FORMAT_ARGS", "--json"))) {
            placeholderCall(daemon);
        }
        assertTrue(sbx.calls().contains("ls --json"), sbx.calls().toString());
    }

    @Test
    void survivesAFailingLs() {
        FakeSbx sbx = FakeSbx.create().respond("ls", 1, "error: not signed in to Docker");
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            assertTrue(placeholderCall(daemon).contains("registering it now"));
            assertTrue(daemon.isAlive());
            assertTrue(daemon.log().contains("sbx ls failed (exit 1)"), daemon.log());
        }
    }

    @Test
    void survivesMalformedLsOutput() {
        FakeSbx sbx = FakeSbx.create().lsJson("this is not json");
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            assertTrue(placeholderCall(daemon).contains("registering it now"));
            assertEquals(200, daemon.get(daemon.admin("/api/health")).statusCode());
        }
    }

    @Test
    void survivesAFailingSetCustom() {
        FakeSbx sbx = FakeSbx.create().running("fail-1").respond("secret", 1, "error: unknown flag: --target");
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            assertTrue(placeholderCall(daemon).contains("registering it now"));
            assertTrue(daemon.log().contains("sbx secret set-custom failed (exit 1)"), daemon.log());
        }
    }

    @Test
    void missingSbxBinaryIsLoggedNotFatal() {
        FakeSbx sbx = FakeSbx.create();
        try (DaemonProcess daemon = DaemonProcess.start(sbx, DaemonProcess.env("GOVERSBX_SBX_BINARY", "/nonexistent/sbx"))) {
            assertTrue(placeholderCall(daemon).contains("registering it now"));
            assertTrue(daemon.isAlive());
        }
    }

    private static long secretCalls(FakeSbx sbx) {
        return sbx.calls().stream().filter(c -> c.startsWith("secret")).count();
    }

    @Test
    void registrationSurvivesADaemonRestart() throws IOException {
        FakeSbx sbx = FakeSbx.create().running("durable-1");
        Path stateDir = Files.createTempDirectory("goversbx-state-shared");
        Map<String, String> sameStateDir = DaemonProcess.env("GOVERSBX_STATE_DIR", stateDir.toString());

        String token;
        try (DaemonProcess daemon = DaemonProcess.start(sbx, sameStateDir)) {
            assertTrue(placeholderCall(daemon).contains("registering it now"));
            token = sbx.tokenFor("durable-1");
            assertNotNull(token, sbx.calls().toString());
            assertTrue(Files.exists(stateDir.resolve("sandbox-registrations.json")), "state file written");
        }
        assertEquals(1, secretCalls(sbx), sbx.calls().toString());

        try (DaemonProcess restarted = DaemonProcess.start(sbx, sameStateDir)) {
            assertTrue(restarted.log().contains("Restored 1 sandbox registration(s)"), restarted.log());
            // The sbx proxy still substitutes the token minted before the restart; the restarted
            // daemon must resolve it on the first call - no "please retry", no sbx call at all.
            String answer = Mcp.text(restarted.mcp(token).initialize()
                    .callTool("request_network_access", Mcp.args("host", "durable.example", "reason", "r", "timeoutSeconds", 1)));
            assertTrue(answer.startsWith("Still pending"), answer);
            assertEquals("durable-1", restarted.awaitPending("durable.example").path("sandboxName").asText());
            // And the incarnation is still known: another unregistered caller triggers a pass that
            // registers nothing for durable-1 again.
            assertTrue(placeholderCall(restarted).contains("registering it now"));
        }
        assertEquals(1, secretCalls(sbx), sbx.calls().toString());
    }

    @Test
    void aMissingStateFileFallsBackToReRegistration() throws IOException {
        FakeSbx sbx = FakeSbx.create().running("heal-1");
        Path stateDir = Files.createTempDirectory("goversbx-state-lost");
        Map<String, String> sameStateDir = DaemonProcess.env("GOVERSBX_STATE_DIR", stateDir.toString());

        String staleToken;
        try (DaemonProcess daemon = DaemonProcess.start(sbx, sameStateDir)) {
            placeholderCall(daemon);
            staleToken = sbx.tokenFor("heal-1");
        }
        Files.delete(stateDir.resolve("sandbox-registrations.json"));

        try (DaemonProcess restarted = DaemonProcess.start(sbx, sameStateDir)) {
            Mcp stale = restarted.mcp(staleToken).initialize();
            assertTrue(Mcp.text(stale.callTool("request_network_access",
                    Mcp.args("host", "heal.example", "reason", "r", "timeoutSeconds", 1))).contains("registering it now"));
            assertEquals(2, secretCalls(sbx), sbx.calls().toString());
            // What the proxy substitutes from now on is the fresh token, and that one works.
            assertTrue(Mcp.text(restarted.mcp(sbx.tokenFor("heal-1")).initialize().callTool("request_network_access",
                    Mcp.args("host", "heal.example", "reason", "r", "timeoutSeconds", 1))).startsWith("Still pending"));
        }
    }
}
