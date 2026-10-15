package run.fabian.daemon.binary;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** GET /api/events, what the admin UI's EventSource and browser notifications run on. */
class AdminEventStreamTest {

    @Test
    void streamsReadyCreatedAndDecided() {
        try (DaemonProcess daemon = DaemonProcess.start(FakeSbx.create())) {
            List<String> lines = new CopyOnWriteArrayList<>();
            // EventSource can't set headers, so the daemon also accepts the token as ?token= - use it here.
            var stream = DaemonProcess.HTTP.sendAsync(
                    HttpRequest.newBuilder(daemon.admin("/api/events?token=" + DaemonProcess.ADMIN_TOKEN))
                            .header("Accept", "text/event-stream").build(),
                    HttpResponse.BodyHandlers.ofLines());
            stream.thenAccept(response -> {
                assertEquals(200, response.statusCode());
                try (Stream<String> body = response.body()) {
                    body.forEach(lines::add);
                }
            });
            awaitEvent(lines, "ready", "");

            var call = daemon.asSandbox("sse-sb").requestAccessAsync("sse.example", 30);
            JsonNode pending = daemon.awaitPending("sse.example");
            JsonNode created = awaitEvent(lines, "created", "sse.example");
            assertEquals(pending.path("id").asText(), created.path("id").asText());
            assertEquals("PENDING", created.path("status").asText());

            daemon.decide(pending.path("id").asText(), "approve");
            JsonNode decided = awaitEvent(lines, "decided", "sse.example");
            assertEquals("APPROVED", decided.path("status").asText());
            assertTrue(decided.path("sbxApplied").asBoolean());
            call.join();
            stream.cancel(true);
        }
    }

    /** The data of the first `event` whose data mentions `contains`, parsed as JSON. */
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
