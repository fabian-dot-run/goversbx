package run.fabian.daemon.projecthosts.application;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.adapter.persistence.InMemoryRequestRepository;
import run.fabian.daemon.networkaccess.api.RequestStatus;
import run.fabian.daemon.networkaccess.application.NetworkAccessService;
import run.fabian.daemon.projecthosts.application.port.HostSetSource;
import run.fabian.daemon.projecthosts.domain.InvalidHostSetException;
import run.fabian.daemon.projecthosts.domain.ResolvedHostSet;
import run.fabian.daemon.sandboxidentity.api.RegisteredSandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectHostSetServiceTest {

    /** Workspaces -> what the file there resolves to (a set, nothing, or problems). */
    private static HostSetSource sourceOf(Map<String, Object> byWorkspace) {
        return workspace -> {
            Object value = byWorkspace.get(workspace.toString());
            if (value == null) {
                return Optional.empty();
            }
            if (value instanceof List<?> problems) {
                throw new InvalidHostSetException(workspace + "/.goversbx/network.yaml", problems.stream().map(Object::toString).toList());
            }
            return Optional.of((ResolvedHostSet) value);
        };
    }

    private static ResolvedHostSet set(String workspace, String... hosts) {
        return new ResolvedHostSet(List.of(hosts), workspace + "/.goversbx/network.yaml", List.of("p"));
    }

    private static NetworkAccessService networkAccess() {
        return new NetworkAccessService(new InMemoryRequestRepository(), (sandbox, host) -> new run.fabian.daemon.networkaccess.application.port.SbxPolicyPort.Result(true, "ok"), 8788);
    }

    @Test
    void aRegisteredSandboxWithAProjectFileGetsOnePendingSetPerWorkspace() {
        NetworkAccessService networkAccess = networkAccess();
        ProjectHostSetService service = new ProjectHostSetService(
                sourceOf(Map.of("/w1", set("/w1", "a.example", "b.example"), "/w3", set("/w3", "c.example"))), networkAccess);

        service.onSandboxRegistered(new RegisteredSandbox("sb", "id", List.of("/w1", "/w2", "/w3")));

        var pending = networkAccess.list(RequestStatus.PENDING);
        assertEquals(2, pending.size());
        assertEquals(List.of(List.of("c.example"), List.of("a.example", "b.example")),
                pending.stream().map(r -> r.hosts()).toList());    // newest first
        assertTrue(pending.stream().allMatch(r -> r.sandboxName().equals("sb") && r.profiles().equals(List.of("p"))));
    }

    @Test
    void evaluationReportsEachWorkspace() {
        NetworkAccessService networkAccess = networkAccess();
        ProjectHostSetService service = new ProjectHostSetService(
                sourceOf(Map.of("/w1", set("/w1", "a.example"), "/bad", List.of("version 2 is not supported"))), networkAccess);
        RegisteredSandbox sandbox = new RegisteredSandbox("sb", "id", List.of("/w1", "/none", "/bad"));

        List<HostSetEvaluation> first = service.evaluate(sandbox);
        List<HostSetEvaluation> second = service.evaluate(sandbox);

        assertEquals(List.of(HostSetEvaluation.Kind.CREATED, HostSetEvaluation.Kind.NO_FILE, HostSetEvaluation.Kind.INVALID),
                first.stream().map(HostSetEvaluation::kind).toList());
        assertEquals(List.of("version 2 is not supported"), first.get(2).problems());
        assertEquals("/w1", first.get(0).workspace());
        assertEquals(HostSetEvaluation.Kind.ALREADY_PENDING, second.get(0).kind());
        assertEquals(first.get(0).request().id(), second.get(0).request().id());
        networkAccess.approve(first.get(0).request().id(), "fabian");
        assertEquals(HostSetEvaluation.Kind.ALL_GRANTED, service.evaluate(sandbox).get(0).kind());
    }

    @Test
    void aFileNamingAnUnacceptableHostProducesNoRequest() {
        NetworkAccessService networkAccess = networkAccess();
        ProjectHostSetService service = new ProjectHostSetService(
                sourceOf(Map.of("/w", set("/w", "a.example", "*.example", "localhost:8788"))), networkAccess);

        List<HostSetEvaluation> evaluations = service.evaluate(new RegisteredSandbox("sb", "id", List.of("/w")));

        assertEquals(HostSetEvaluation.Kind.INVALID, evaluations.get(0).kind());
        assertEquals(2, evaluations.get(0).problems().size(), evaluations.get(0).problems().toString());
        assertTrue(networkAccess.list(null).isEmpty());
    }

    @Test
    void theRegistrationTriggerNeverThrows() {
        ProjectHostSetService service = new ProjectHostSetService(workspace -> {
            throw new IllegalStateException("disk on fire");
        }, networkAccess());

        service.onSandboxRegistered(new RegisteredSandbox("sb", "id", List.of("/w")));
    }

    @Test
    void aSandboxWithoutWorkspacesEvaluatesToNothing() {
        assertTrue(new ProjectHostSetService(sourceOf(Map.of()), networkAccess())
                .evaluate(new RegisteredSandbox("sb", "id", List.of())).isEmpty());
    }
}
