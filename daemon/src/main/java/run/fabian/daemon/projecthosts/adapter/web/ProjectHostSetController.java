package run.fabian.daemon.projecthosts.adapter.web;

import run.fabian.daemon.projecthosts.application.HostSetEvaluation;
import run.fabian.daemon.projecthosts.application.ProjectHostSetService;
import run.fabian.daemon.sandboxidentity.api.RegisteredSandbox;
import run.fabian.daemon.sandboxidentity.api.SandboxIdentityApi;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Operator-facing explicit trigger (spec 0001 slice 1, T2): evaluate a registered sandbox's project
 * file(s) now - what the launcher calls right after creating a sandbox. Loopback-only and
 * token-gated like every admin route; a sandbox must never reach it, since it raises requests.
 */
public final class ProjectHostSetController {

    private final ProjectHostSetService service;
    private final SandboxIdentityApi identity;

    public ProjectHostSetController(ProjectHostSetService service, SandboxIdentityApi identity) {
        this.service = service;
        this.identity = identity;
    }

    public void registerRoutes(JavalinDefaultRoutingApi routes) {
        routes.post("/api/sandboxes/{name}/host-set-requests", ctx -> {
            String name = ctx.pathParam("name");
            Optional<RegisteredSandbox> sandbox = identity.findRegistered(name);
            if (sandbox.isEmpty()) {
                ctx.status(404).json(Map.of("error",
                        "sandbox '" + name + "' is not registered with the daemon, or sbx does not list it"));
                return;
            }
            List<HostSetEvaluation> evaluations = service.evaluate(sandbox.get());
            ctx.status(statusFor(evaluations)).json(new Response(sandbox.get().name(), sandbox.get().workspaces(), evaluations));
        });
    }

    /** 201 if a request was created; else 400 if a file is invalid; else 200 (nothing to do or already pending). */
    static int statusFor(List<HostSetEvaluation> evaluations) {
        if (evaluations.stream().anyMatch(e -> e.kind() == HostSetEvaluation.Kind.CREATED)) {
            return 201;
        }
        if (evaluations.stream().anyMatch(e -> e.kind() == HostSetEvaluation.Kind.INVALID)) {
            return 400;
        }
        return 200;
    }

    public record Response(String sandboxName, List<String> workspaces, List<HostSetEvaluation> evaluations) {
    }
}
