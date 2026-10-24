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
            boolean text = "text".equals(ctx.queryParam("format"));
            if (sandbox.isEmpty()) {
                String error = "sandbox '" + name + "' is not registered with the daemon, or sbx does not list it";
                if (text) {
                    ctx.status(404).contentType("text/plain; charset=utf-8").result(error + "\n");
                } else {
                    ctx.status(404).json(Map.of("error", error));
                }
                return;
            }
            List<HostSetEvaluation> evaluations = service.evaluate(sandbox.get());
            ctx.status(statusFor(evaluations));
            if (text) {
                ctx.contentType("text/plain; charset=utf-8").result(summary(evaluations));
            } else {
                ctx.json(new Response(sandbox.get().name(), sandbox.get().workspaces(), evaluations));
            }
        });
    }

    /**
     * The {@code ?format=text} body: ready-to-print lines for the launcher (spec 0001 slice 2,
     * decision 2), so a bash 3.2 script without jq never parses JSON. Nothing for a workspace
     * without a project file; empty body if no workspace has one.
     */
    static String summary(List<HostSetEvaluation> evaluations) {
        StringBuilder out = new StringBuilder();
        for (HostSetEvaluation e : evaluations) {
            String file = e.request() != null && e.request().source() != null
                    ? e.request().source() : e.workspace() + "/.goversbx/network.yaml";
            switch (e.kind()) {
                case CREATED, ALREADY_PENDING -> out.append("project host set (").append(e.request().hosts().size())
                        .append(e.request().hosts().size() == 1 ? " host" : " hosts").append(" from ").append(file)
                        .append(") is waiting for approval in the dashboard\n");
                case ALL_GRANTED -> out.append("project host set from ").append(file)
                        .append(": every host is already granted to this sandbox\n");
                case INVALID -> {
                    out.append(file).append(" is invalid - no hosts requested:\n");
                    e.problems().forEach(p -> out.append("  - ").append(p).append('\n'));
                }
                case NO_FILE -> { }
            }
        }
        return out.toString();
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
