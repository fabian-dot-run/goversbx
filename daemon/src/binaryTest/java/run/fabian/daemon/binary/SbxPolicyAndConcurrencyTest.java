package run.fabian.daemon.binary;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SbxPolicyAndConcurrencyTest {

    @Test
    void failedPolicyAllowIsReportedOnTheRequest() {
        FakeSbx sbx = FakeSbx.create().respond("policy", 1, "Blocked by local rule for denied.example");
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            var call = daemon.mcp("policy-fail").initialize().requestAccessAsync("denied.example", 30);
            JsonNode decided = daemon.decide(daemon.awaitPending("denied.example").path("id").asText(), "approve");
            assertEquals("APPROVED", decided.path("status").asText());
            assertFalse(decided.path("sbxApplied").asBoolean(), decided.toString());
            assertEquals("Blocked by local rule for denied.example", decided.path("sbxOutput").asText());
            call.join();
            assertTrue(daemon.log().contains("sbx policy allow failed (exit 1)"), daemon.log());
        }
    }

    @Test
    void manySandboxesBlockAndAreApprovedConcurrently() throws Exception {
        int n = 25;
        FakeSbx sbx = FakeSbx.create();
        try (DaemonProcess daemon = DaemonProcess.start(sbx)) {
            List<CompletableFuture<JsonNode>> calls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                calls.add(daemon.mcp("conc-" + i).initialize().requestAccessAsync("conc-" + i + ".example", 90));
            }
            List<String> ids = IntStream.range(0, n)
                    .mapToObj(i -> daemon.awaitPending("conc-" + i + ".example").path("id").asText()).toList();
            ids.parallelStream().forEach(id -> daemon.decide(id, "approve"));

            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            for (int i = 0; i < n; i++) {
                String text = Mcp.text(calls.get(i).join());
                assertTrue(text.startsWith("Approved - network access to 'conc-" + i + ".example'"), text);
                assertTrue(sbx.calls().contains("policy allow network conc-" + i + ".example --sandbox conc-" + i));
            }
            assertEquals(200, daemon.get(daemon.admin("/api/health")).statusCode());
        }
    }
}
