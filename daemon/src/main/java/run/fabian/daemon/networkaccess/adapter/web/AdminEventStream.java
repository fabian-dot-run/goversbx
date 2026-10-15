package run.fabian.daemon.networkaccess.adapter.web;

import io.javalin.http.sse.SseClient;
import io.javalin.router.JavalinDefaultRoutingApi;
import run.fabian.daemon.networkaccess.api.NetworkAccessListener;
import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;
import run.fabian.daemon.networkaccess.application.NetworkAccessService;

/**
 * Server-sent events for the admin UI: {@code created} and {@code decided}, each carrying the
 * request as JSON. Lets the UI update live and raise browser notifications for new requests.
 * Same loopback-only rule as {@link AdminApiController}.
 */
public final class AdminEventStream {

    private final NetworkAccessService service;
    private final java.util.concurrent.atomic.AtomicInteger connectedClients =
            new java.util.concurrent.atomic.AtomicInteger();

    public AdminEventStream(NetworkAccessService service) {
        this.service = service;
    }

    public void registerRoutes(JavalinDefaultRoutingApi routes) {
        routes.sse("/api/events", client -> {
            client.keepAlive();
            Runnable unsubscribe = service.subscribe(new Forwarder(client));
            connectedClients.incrementAndGet();
            client.onClose(() -> {
                unsubscribe.run();
                connectedClients.decrementAndGet();
            });
            // Confirms the subscription, so a client knows it won't miss events from here on.
            send(client, "ready", "{}");
        });
    }

    /**
     * Whether at least one dashboard is currently connected to the event stream - i.e. a dashboard
     * tab is open and listening. Used by {@code DashboardAutoOpener} to decide whether to open one.
     */
    public boolean hasConnectedClients() {
        return connectedClients.get() > 0;
    }

    private record Forwarder(SseClient client) implements NetworkAccessListener {

        @Override
        public void requestCreated(NetworkAccessRequestView request) {
            send(client, "created", toJson(request));
        }

        @Override
        public void requestDecided(NetworkAccessRequestView request) {
            send(client, "decided", toJson(request));
        }
    }

    // Writes from different request threads must not interleave on one stream.
    private static void send(SseClient client, String event, String json) {
        synchronized (client) {
            if (!client.terminated()) {
                client.sendEvent(event, json, null);   // Javalin 7: sendEvent(event, data, id)
            }
        }
    }

    private static String toJson(NetworkAccessRequestView request) {
        try {
            return JsonSupport.MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize request " + request.id(), e);
        }
    }
}
