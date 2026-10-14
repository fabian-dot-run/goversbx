package run.fabian.daemon.binary;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reactive registration: spawning sbx, parsing `sbx ls` JSON, and surviving a misbehaving sbx. */
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
        assertEquals(List.of("secret set-custom --sandbox match-1 --host localhost --env GOVERSBX_SANDBOX_TOKEN"
                + " --placeholder " + DaemonProcess.PLACEHOLDER + " --value match-1"), secrets);
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
}
