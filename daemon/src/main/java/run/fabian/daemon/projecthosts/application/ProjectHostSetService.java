package run.fabian.daemon.projecthosts.application;

import run.fabian.daemon.networkaccess.api.HostSetRequestOutcome;
import run.fabian.daemon.networkaccess.api.NetworkAccessApi;
import run.fabian.daemon.projecthosts.application.port.HostSetSource;
import run.fabian.daemon.projecthosts.domain.HostSetRules;
import run.fabian.daemon.projecthosts.domain.InvalidHostSetException;
import run.fabian.daemon.projecthosts.domain.ResolvedHostSet;
import run.fabian.daemon.sandboxidentity.api.RegisteredSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code projecthosts} bounded context: turns a sandbox's project file(s) into one host-set
 * request per workspace through {@link NetworkAccessApi}. Triggered when a sandbox incarnation is
 * registered (the daemon then knows its workspaces from {@code sbx ls}) and by the admin endpoint.
 * Reads host-side files only at paths sbx reported, never one the agent named.
 *
 * <p>Host syntax (threat-model F2/F3) is judged by {@code networkaccess} through its published API,
 * so this context never imports that domain; one bad host makes the whole file invalid.
 */
public final class ProjectHostSetService {

    private static final Logger log = LoggerFactory.getLogger(ProjectHostSetService.class);

    private final HostSetSource source;
    private final NetworkAccessApi networkAccess;

    public ProjectHostSetService(HostSetSource source, NetworkAccessApi networkAccess) {
        this.source = source;
        this.networkAccess = networkAccess;
    }

    /** Registration trigger: evaluates every workspace, logs, never throws. */
    public void onSandboxRegistered(RegisteredSandbox sandbox) {
        try {
            evaluate(sandbox);
        } catch (RuntimeException e) {
            log.warn("Could not evaluate the project host set for {}", sandbox.name(), e);
        }
    }

    /** One evaluation per workspace, in {@code sbx ls} order. */
    public List<HostSetEvaluation> evaluate(RegisteredSandbox sandbox) {
        List<HostSetEvaluation> evaluations = new ArrayList<>();
        for (String workspace : sandbox.workspaces()) {
            evaluations.add(evaluate(sandbox.name(), workspace));
        }
        return evaluations;
    }

    private HostSetEvaluation evaluate(String sandboxName, String workspace) {
        ResolvedHostSet set;
        try {
            Optional<ResolvedHostSet> resolved = source.resolve(Path.of(workspace));
            if (resolved.isEmpty()) {
                log.debug("No {} in workspace {} of {}", HostSetRules.PROJECT_FILE, workspace, sandboxName);
                return HostSetEvaluation.of(workspace, HostSetEvaluation.Kind.NO_FILE);
            }
            set = resolved.get();
        } catch (InvalidHostSetException e) {
            log.warn("Ignoring the project host set for {}: {} is invalid: {}", sandboxName, e.source(), String.join("; ", e.problems()));
            return HostSetEvaluation.invalid(workspace, e.problems());
        }
        List<String> problems = new ArrayList<>();
        for (String host : set.hosts()) {
            networkAccess.hostRejectionReason(host).ifPresent(problems::add);
        }
        if (!problems.isEmpty()) {
            log.warn("Ignoring the project host set for {}: {} names unacceptable hosts: {}", sandboxName, set.source(), String.join("; ", problems));
            return HostSetEvaluation.invalid(workspace, problems);
        }
        HostSetRequestOutcome outcome = networkAccess.createHostSetRequest(sandboxName, set.hosts(), set.source(), set.profiles());
        return switch (outcome.kind()) {
            case CREATED -> HostSetEvaluation.withRequest(workspace, HostSetEvaluation.Kind.CREATED, outcome.request());
            case ALREADY_PENDING -> HostSetEvaluation.withRequest(workspace, HostSetEvaluation.Kind.ALREADY_PENDING, outcome.request());
            case ALL_GRANTED -> HostSetEvaluation.of(workspace, HostSetEvaluation.Kind.ALL_GRANTED);
        };
    }
}
