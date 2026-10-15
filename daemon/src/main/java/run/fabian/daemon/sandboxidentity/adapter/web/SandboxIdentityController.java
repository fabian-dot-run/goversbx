package run.fabian.daemon.sandboxidentity.adapter.web;

import run.fabian.daemon.sandboxidentity.application.SandboxIdentityService;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.Map;

/**
 * Operator-facing: registers a sandbox's proxy-verified identity secret before that sandbox's
 * kit-install step runs. Loopback-only, like {@code AdminApiController} - this mints trust, so it
 * must never be reachable from inside a sandbox.
 */
public final class SandboxIdentityController {

    private final SandboxIdentityService service;

    public SandboxIdentityController(SandboxIdentityService service) {
        this.service = service;
    }

    public void registerRoutes(JavalinDefaultRoutingApi routes) {
        routes.post("/api/sandboxes/{name}/register", ctx -> {
            String name = ctx.pathParam("name");
            if (name == null || name.isBlank()) {
                ctx.status(400).json(Map.of("error", "sandbox name is required"));
                return;
            }
            ctx.json(service.register(name));
        });
    }
}
