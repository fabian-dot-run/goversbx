package run.fabian.daemon.binary;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonLifecycleTest {

    @Test
    void startsAndStopsCleanlyOnSigterm() {
        try (DaemonProcess daemon = DaemonProcess.start(FakeSbx.create())) {
            int exit = daemon.stop();
            // 143 = 128 + SIGTERM: the JVM and a native image both exit like this after running shutdown hooks.
            assertTrue(exit == 0 || exit == 143, "exit code " + exit);
            String log = daemon.log();
            assertEquals(2, log.split("Javalin has stopped", -1).length - 1, "both listeners stop:\n" + log);
            assertFalse(log.contains("Exception"), "no exceptions during a clean run:\n" + log);
        }
    }

    @Test
    void startsQuickly() {
        try (DaemonProcess daemon = DaemonProcess.start(FakeSbx.create())) {
            // Generous enough for the JVM and the tracing agent; the native binary takes ~50 ms.
            assertTrue(daemon.startupMillis < TimeUnit.SECONDS.toMillis(30), daemon.startupMillis + " ms");
        }
    }

    @Test
    void dryRunNeverRunsMutatingSbxCommands() {
        FakeSbx sbx = FakeSbx.create().running("dry-sb");
        try (DaemonProcess daemon = DaemonProcess.start(sbx, DaemonProcess.env("GOVERSBX_SBX_DRY_RUN", "true"))) {
            Mcp placeholder = daemon.mcp(DaemonProcess.PLACEHOLDER).initialize();
            Mcp.text(placeholder.callTool("request_network_access", Mcp.args("host", "dry.example", "reason", "r", "timeoutSeconds", 1)));

            Mcp mcp = daemon.mcp("dry-sb").initialize();
            var call = mcp.requestAccessAsync("dry.example", 30);
            var decided = daemon.decide(daemon.awaitPending("dry.example").path("id").asText(), "approve");
            assertTrue(decided.path("sbxApplied").asBoolean(), decided.toString());
            assertTrue(decided.path("sbxOutput").asText().startsWith("dry-run: would execute:"), decided.toString());
            assertTrue(Mcp.text(call.join()).startsWith("Approved"));
        }
        assertTrue(sbx.calls().stream().noneMatch(c -> c.startsWith("policy") || c.startsWith("secret")),
                "dry run ran: " + sbx.calls());
    }
}
