package run.fabian.daemon.http;

import run.fabian.common.JsonSupport;
import run.fabian.common.NetworkAccessRequest;
import run.fabian.common.RequestStatus;
import run.fabian.daemon.sbx.SbxPolicyClient;
import run.fabian.daemon.store.RequestStore;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

import java.util.Map;
import java.util.UUID;

/**
 * Operator-facing: listing + approve/deny + the web UI. Must be bound to loopback only -
 * a sandbox that could reach this could self-approve its own requests (no authn in the MVP).
 */
public final class AdminServer {

    private final RequestStore store;
    private final SbxPolicyClient sbxPolicyClient;
    private Javalin app;

    public AdminServer(RequestStore store, SbxPolicyClient sbxPolicyClient) {
        this.store = store;
        this.sbxPolicyClient = sbxPolicyClient;
    }

    public void start(String bindHost, int port) {
        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(JsonSupport.MAPPER, false));
            config.staticFiles.add("/public");
        });

        app.get("/api/requests", ctx -> {
            String statusParam = ctx.queryParam("status");
            RequestStatus filter = statusParam == null ? null : RequestStatus.valueOf(statusParam.toUpperCase());
            ctx.json(store.list(filter));
        });

        app.post("/api/requests/{id}/approve", ctx -> {
            UUID id = parseId(ctx.pathParam("id"));
            NetworkAccessRequest request = id == null ? null : store.get(id).orElse(null);
            if (request == null) {
                ctx.status(404).json(Map.of("error", "not found"));
                return;
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                ctx.status(409).json(Map.of("error", "already decided"));
                return;
            }

            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String decidedBy = body.get("decidedBy") == null ? null : body.get("decidedBy").toString();

            SbxPolicyClient.Result result = sbxPolicyClient.allowNetwork(request.getSandboxName(), request.getHost());
            request.approve(decidedBy, result.applied(), result.output());
            store.complete(id);

            ctx.json(request);
        });

        app.post("/api/requests/{id}/deny", ctx -> {
            UUID id = parseId(ctx.pathParam("id"));
            NetworkAccessRequest request = id == null ? null : store.get(id).orElse(null);
            if (request == null) {
                ctx.status(404).json(Map.of("error", "not found"));
                return;
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                ctx.status(409).json(Map.of("error", "already decided"));
                return;
            }

            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String decidedBy = body.get("decidedBy") == null ? null : body.get("decidedBy").toString();

            request.deny(decidedBy);
            store.complete(id);

            ctx.json(request);
        });

        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

        app.start(bindHost, port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
