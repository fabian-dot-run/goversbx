package run.fabian.daemon.networkaccess.adapter.web;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.adapter.persistence.InMemoryRequestRepository;
import run.fabian.daemon.networkaccess.adapter.sbx.SbxCliPolicyAdapter;
import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;
import run.fabian.daemon.networkaccess.application.NetworkAccessService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminEventStreamTest {

    @Test
    void streamsCreatedAndDecidedEvents() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new SbxCliPolicyAdapter("sbx", true));
        Javalin app = Javalin.create();
        new AdminEventStream(service).registerRoutes(app);

        JavalinTest.test(app, (server, client) -> {
            // Javalin only upgrades to SSE for this Accept header (EventSource sends it).
            Request request = new Request.Builder().url(client.getOrigin() + "/api/events")
                    .header("Accept", "text/event-stream").build();
            try (Response response = client.getOkHttp().newCall(request).execute()) {
                assertEquals(200, response.code());
                assertTrue(response.header("Content-Type", "").startsWith("text/event-stream"));
                BufferedSource stream = response.body().source();

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
    private static List<String> nextEvent(BufferedSource stream) throws Exception {
        String event = null;
        List<String> data = new ArrayList<>();
        String line;
        while ((line = stream.readUtf8Line()) != null) {
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
