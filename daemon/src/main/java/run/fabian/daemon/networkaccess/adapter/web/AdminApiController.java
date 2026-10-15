package run.fabian.daemon.networkaccess.adapter.web;

import run.fabian.daemon.networkaccess.api.RequestStatus;
import run.fabian.daemon.networkaccess.application.NetworkAccessService;
import run.fabian.daemon.networkaccess.domain.AlreadyDecidedException;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Operator-facing: listing + approve/deny. Must only ever be mounted on a loopback-bound Javalin
 * instance - a sandbox that could reach this could self-approve its own requests (no authn in
 * this MVP).
 */
public final class AdminApiController {

    private final NetworkAccessService service;

    public AdminApiController(NetworkAccessService service) {
        this.service = service;
    }

    public void registerRoutes(JavalinDefaultRoutingApi routes) {
        routes.exception(NoSuchElementException.class, (e, ctx) -> ctx.status(404).json(Map.of("error", "not found")));
        routes.exception(AlreadyDecidedException.class, (e, ctx) -> ctx.status(409).json(Map.of("error", "already decided")));

        routes.get("/api/requests", ctx -> {
            String statusParam = ctx.queryParam("status");
            RequestStatus filter = statusParam == null ? null : RequestStatus.valueOf(statusParam.toUpperCase());
            ctx.json(service.list(filter));
        });

        routes.post("/api/requests/{id}/approve", ctx -> {
            UUID id = parseId(ctx.pathParam("id"));
            if (id == null) {
                ctx.status(400).json(Map.of("error", "invalid id"));
                return;
            }
            ctx.json(service.approve(id, decidedBy(ctx)));
        });

        routes.post("/api/requests/{id}/deny", ctx -> {
            UUID id = parseId(ctx.pathParam("id"));
            if (id == null) {
                ctx.status(400).json(Map.of("error", "invalid id"));
                return;
            }
            ctx.json(service.deny(id, decidedBy(ctx)));
        });

        routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
    }

    private static String decidedBy(io.javalin.http.Context ctx) {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        Object decidedBy = body.get("decidedBy");
        return decidedBy == null ? null : decidedBy.toString();
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
