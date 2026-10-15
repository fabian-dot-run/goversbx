package run.fabian.daemon.networkaccess.adapter.web;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.adapter.persistence.InMemoryRequestRepository;
import run.fabian.daemon.networkaccess.adapter.sbx.SbxCliPolicyAdapter;
import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;
import run.fabian.daemon.networkaccess.application.NetworkAccessService;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminEventStreamTest {

    @Test
    void streamsCreatedAndDecidedEvents() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new SbxCliPolicyAdapter("sbx", true));
        Javalin app = Javalin.create(config -> new AdminEventStream(service).registerRoutes(config.routes));

        JavalinTest.test(app, (server, client) -> {
            // Javalin only upgrades to SSE for this Accept header (EventSource sends it). javalin-testtools
            // 7 has no raw-client accessor, so drive the stream with java.net.http directly.
            HttpRequest request = HttpRequest.newBuilder(URI.create(client.getOrigin() + "/api/events"))
                    .header("Accept", "text/event-stream").GET().build();
            HttpResponse<InputStream> response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));

            try (BufferedReader stream = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertEquals("ready", nextEvent(stream).get(0));

                NetworkAccessRequestView created = service.createRequest("sandbox-1", "api.example.com", "fetch pricing");
                List<String> createdEvent = nextEvent(stream);
                assertEquals("created", createdEvent.get(0));
                assertTrue(createdEvent.get(1).contains("\"host\":\"api.example.com\""), createdEvent.get(1));
                assertTrue(createdEvent.get(1).contains("\"status\":\"PENDING\""), createdEvent.get(1));

                service.deny(created.id(), "fabian");
                List<String> decidedEvent = nextEvent(stream);
                assertEquals("decided", decidedEvent.get(0));
                assertTrue(decidedEvent.get(1).contains("\"status\":\"DENIED\""), decidedEvent.get(1));
            }
        });
    }

    /** Reads one SSE event; returns [event name, data]. */
    private static List<String> nextEvent(BufferedReader stream) throws Exception {
        String event = null;
        List<String> data = new ArrayList<>();
        String line;
        while ((line = stream.readLine()) != null) {
            if (line.isEmpty()) {
                if (event != null) {
                    return List.of(event, String.join("\n", data));
                }
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data.add(line.substring("data:".length()).trim());
            }
        }
        throw new IllegalStateException("stream ended before an event arrived");
    }
}
