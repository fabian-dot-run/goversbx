package run.fabian.daemon.http;

import run.fabian.common.JsonSupport;
import run.fabian.common.NetworkAccessRequest;
import run.fabian.daemon.store.RequestStore;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Reachable from inside sandboxes over the always-allow-listed daemon hostname.
 * Only exposes request create/await/get - never approve/deny (see AdminServer).
 */
public final class SandboxApiServer {

    private final RequestStore store;
    private Javalin app;

    public SandboxApiServer(RequestStore store) {
        this.store = store;
    }

    public void start(String bindHost, int port) {
        app = Javalin.create(config -> config.jsonMapper(new JavalinJackson(JsonSupport.MAPPER, false)));

        app.post("/api/requests", ctx -> {
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String sandboxName = asString(body.get("sandboxName"));
            String host = asString(body.get("host"));
            String reason = asString(body.get("reason"));

            if (sandboxName == null || sandboxName.isBlank() || host == null || host.isBlank()) {
                ctx.status(400).json(Map.of("error", "sandboxName and host are required"));
                return;
            }

            NetworkAccessRequest request = store.create(sandboxName, host, reason == null ? "" : reason);
            ctx.status(201).json(request);
        });

        app.get("/api/requests/{id}", ctx -> {
            UUID id = parseId(ctx.pathParam("id"));
            if (id == null) {
                ctx.status(400).json(Map.of("error", "invalid id"));
                return;
            }
            store.get(id)
                    .ifPresentOrElse(ctx::json, () -> ctx.status(404).json(Map.of("error", "not found")));
        });

        app.get("/api/requests/{id}/await", ctx -> {
            UUID id = parseId(ctx.pathParam("id"));
            if (id == null) {
                ctx.status(400).json(Map.of("error", "invalid id"));
                return;
            }
            int timeoutSeconds = Integer.parseInt(ctx.queryParamAsClass("timeoutSeconds", String.class).getOrDefault("45"));
            timeoutSeconds = Math.max(1, Math.min(timeoutSeconds, 120));

            NetworkAccessRequest result = store.await(id, Duration.ofSeconds(timeoutSeconds));
            if (result == null) {
                ctx.status(404).json(Map.of("error", "not found"));
                return;
            }
            ctx.json(result);
        });

        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

        app.start(bindHost, port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
