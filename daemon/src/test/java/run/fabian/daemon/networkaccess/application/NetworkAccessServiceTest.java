package run.fabian.daemon.networkaccess.application;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.adapter.persistence.InMemoryRequestRepository;
import run.fabian.daemon.networkaccess.api.HostGrant;
import run.fabian.daemon.networkaccess.api.HostSetRequestOutcome;
import run.fabian.daemon.networkaccess.api.RequestOrigin;
import run.fabian.daemon.networkaccess.api.NetworkAccessListener;
import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;
import run.fabian.daemon.networkaccess.api.RequestStatus;
import run.fabian.daemon.networkaccess.application.port.SbxPolicyPort;
import run.fabian.daemon.networkaccess.domain.AlreadyDecidedException;
import run.fabian.daemon.networkaccess.domain.InvalidHostException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAccessServiceTest {

    private record Call(String sandboxName, String host) {
    }

    private static final class RecordingSbxPolicyPort implements SbxPolicyPort {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<String> failFor = new CopyOnWriteArrayList<>();

        @Override
        public Result allowNetwork(String sandboxName, String host) {
            calls.add(new Call(sandboxName, host));
            if (failFor.contains(host)) {
                return new Result(false, "refused " + host);
            }
            return new Result(true, "applied for " + sandboxName);
        }
    }

    private static final String SOURCE = "/ws/.goversbx/network.yaml";

    @Test
    void createRequestStartsPending() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());

        NetworkAccessRequestView view = service.createRequest("sandbox-1", "api.example.com", "fetch pricing");

        assertEquals(RequestStatus.PENDING, view.status());
        assertEquals("sandbox-1", view.sandboxName());
        assertEquals("api.example.com", view.host());
    }

    @Test
    void createRequestRejectsAnInvalidHostAsAnInvariant() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());

        // No request is ever created for a wildcard / IP / malformed host, whatever the caller (F2).
        assertThrows(InvalidHostException.class, () -> service.createRequest("sandbox-1", "**", "reason"));
        assertThrows(InvalidHostException.class, () -> service.createRequest("sandbox-1", "169.254.169.254", "reason"));
        assertTrue(service.hostRejectionReason("**").isPresent());
        assertFalse(service.hostRejectionReason("api.example.com").isPresent());
        assertEquals(0, service.list(RequestStatus.PENDING).size());
    }

    @Test
    void awaitDecisionTimesOutWhileStillPending() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());
        NetworkAccessRequestView created = service.createRequest("sandbox-1", "api.example.com", "fetch pricing");

        NetworkAccessRequestView result = service.awaitDecision(created.id(), Duration.ofMillis(200)).orElseThrow();

        assertEquals(RequestStatus.PENDING, result.status());
    }

    @Test
    void awaitDecisionUnblocksAsSoonAsApproved() throws Exception {
        InMemoryRequestRepository repository = new InMemoryRequestRepository();
        RecordingSbxPolicyPort sbxPolicyPort = new RecordingSbxPolicyPort();
        NetworkAccessService service = new NetworkAccessService(repository, sbxPolicyPort);
        NetworkAccessRequestView created = service.createRequest("sandbox-1", "api.example.com", "fetch pricing");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                service.approve(created.id(), "fabian");
            });

            long start = System.nanoTime();
            NetworkAccessRequestView result = service.awaitDecision(created.id(), Duration.ofSeconds(10)).orElseThrow();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(RequestStatus.APPROVED, result.status());
            assertTrue(elapsedMs < 5_000, "should unblock well before the 10s timeout, took " + elapsedMs + "ms");
            assertEquals(1, sbxPolicyPort.calls.size());
            assertEquals(new Call("sandbox-1", "api.example.com"), sbxPolicyPort.calls.get(0));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void denyDoesNotCallSbxPolicyPort() {
        RecordingSbxPolicyPort sbxPolicyPort = new RecordingSbxPolicyPort();
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), sbxPolicyPort);
        NetworkAccessRequestView created = service.createRequest("sandbox-1", "api.example.com", "fetch pricing");

        NetworkAccessRequestView result = service.deny(created.id(), "fabian");

        assertEquals(RequestStatus.DENIED, result.status());
        assertTrue(sbxPolicyPort.calls.isEmpty());
    }

    @Test
    void approvingAlreadyDecidedRequestThrows() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());
        NetworkAccessRequestView created = service.createRequest("sandbox-1", "api.example.com", "fetch pricing");
        service.approve(created.id(), "fabian");

        assertThrows(AlreadyDecidedException.class, () -> service.approve(created.id(), "someone-else"));
    }

    @Test
    void getStatusForUnknownIdIsEmpty() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());

        assertFalse(service.getStatus(UUID.randomUUID()).isPresent());
    }

    @Test
    void publishesCreatedAndDecidedEventsUntilUnsubscribed() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());
        List<String> events = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = service.subscribe(new NetworkAccessListener() {
            @Override
            public void requestCreated(NetworkAccessRequestView request) {
                events.add("created " + request.host() + " " + request.status());
            }

            @Override
            public void requestDecided(NetworkAccessRequestView request) {
                events.add("decided " + request.host() + " " + request.status());
            }
        });

        NetworkAccessRequestView first = service.createRequest("sandbox-1", "a.example.com", "reason");
        service.approve(first.id(), "fabian");
        unsubscribe.run();
        service.createRequest("sandbox-1", "b.example.com", "reason");

        assertEquals(List.of("created a.example.com PENDING", "decided a.example.com APPROVED"), events);
    }

    @Test
    void aFailingListenerDoesNotBreakTheUseCase() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());
        service.subscribe(new NetworkAccessListener() {
            @Override
            public void requestCreated(NetworkAccessRequestView request) {
                throw new IllegalStateException("broken listener");
            }

            @Override
            public void requestDecided(NetworkAccessRequestView request) {
                throw new IllegalStateException("broken listener");
            }
        });

        NetworkAccessRequestView created = service.createRequest("sandbox-1", "a.example.com", "reason");
        NetworkAccessRequestView denied = service.deny(created.id(), "fabian");

        assertEquals(RequestStatus.DENIED, denied.status());
    }

    // --- host sets (spec 0001, slice 1) --------------------------------------------------------

    @Test
    void aHostSetIsOnePendingRequestWithEveryHostAndItsOrigin() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());

        HostSetRequestOutcome outcome = service.createHostSetRequest("sb", List.of("a.example", "b.example", "c.example"), SOURCE, List.of("jvm"));

        assertEquals(HostSetRequestOutcome.Kind.CREATED, outcome.kind());
        NetworkAccessRequestView view = outcome.request();
        assertEquals(RequestStatus.PENDING, view.status());
        assertEquals(RequestOrigin.PROJECT_FILE, view.origin());
        assertEquals(List.of("a.example", "b.example", "c.example"), view.hosts());
        assertEquals(null, view.host());
        assertEquals(SOURCE, view.source());
        assertEquals(List.of("jvm"), view.profiles());
        assertTrue(view.reason().contains(SOURCE), view.reason());
        assertEquals(1, service.list(RequestStatus.PENDING).size());
    }

    @Test
    void aHostSetWithAnInvalidHostIsRejectedAsAWhole() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort(), 8788);

        assertThrows(InvalidHostException.class, () -> service.createHostSetRequest("sb", List.of("a.example", "*.example"), SOURCE, List.of()));
        assertThrows(InvalidHostException.class, () -> service.createHostSetRequest("sb", List.of("a.example", "localhost:8788"), SOURCE, List.of()));
        assertTrue(service.list(null).isEmpty());
    }

    @Test
    void approvingAHostSetAppliesEveryHostInOrderAndRecordsEachOutcome() {
        RecordingSbxPolicyPort port = new RecordingSbxPolicyPort();
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), port);
        NetworkAccessRequestView set = service.createHostSetRequest("sb", List.of("a.example", "b.example", "c.example"), SOURCE, List.of()).request();

        NetworkAccessRequestView approved = service.approve(set.id(), "fabian");

        assertEquals(List.of(new Call("sb", "a.example"), new Call("sb", "b.example"), new Call("sb", "c.example")), port.calls);
        assertEquals(RequestStatus.APPROVED, approved.status());
        assertEquals(Boolean.TRUE, approved.sbxApplied());
        assertEquals(List.of(
                new HostGrant("a.example", true, "applied for sb"),
                new HostGrant("b.example", true, "applied for sb"),
                new HostGrant("c.example", true, "applied for sb")), approved.hostResults());
        assertEquals(java.util.Set.of("a.example", "b.example", "c.example"), service.grantedHosts("sb"));
    }

    @Test
    void aPartialSbxFailureIsVisiblePerHost() {
        RecordingSbxPolicyPort port = new RecordingSbxPolicyPort();
        port.failFor.add("b.example");
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), port);
        NetworkAccessRequestView set = service.createHostSetRequest("sb", List.of("a.example", "b.example", "c.example"), SOURCE, List.of()).request();

        NetworkAccessRequestView approved = service.approve(set.id(), "fabian");

        assertEquals(RequestStatus.APPROVED, approved.status());
        assertEquals(Boolean.FALSE, approved.sbxApplied());
        assertEquals(new HostGrant("b.example", false, "refused b.example"), approved.hostResults().get(1));
        assertTrue(approved.sbxOutput().contains("b.example: refused b.example"), approved.sbxOutput());
        assertEquals(java.util.Set.of("a.example", "c.example"), service.grantedHosts("sb"));
        // The failed host stays requestable: a single request for it is pending, not auto-granted.
        assertEquals(RequestStatus.PENDING, service.createRequest("sb", "b.example", "retry").status());
    }

    @Test
    void denyingAHostSetAppliesNothingAndLeavesTheSourceFreeForANewSet() {
        RecordingSbxPolicyPort port = new RecordingSbxPolicyPort();
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), port);
        NetworkAccessRequestView set = service.createHostSetRequest("sb", List.of("a.example"), SOURCE, List.of()).request();

        NetworkAccessRequestView denied = service.deny(set.id(), "fabian");

        assertEquals(RequestStatus.DENIED, denied.status());
        assertTrue(port.calls.isEmpty());
        assertTrue(service.grantedHosts("sb").isEmpty());
        assertEquals(HostSetRequestOutcome.Kind.CREATED, service.createHostSetRequest("sb", List.of("a.example"), SOURCE, List.of()).kind());
    }

    @Test
    void anApprovedSetResolvesPendingSinglesOfTheSameSandboxForItsHosts() {
        RecordingSbxPolicyPort port = new RecordingSbxPolicyPort();
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), port);
        NetworkAccessRequestView single = service.createRequest("sb", "a.example", "the agent ran into it first");
        NetworkAccessRequestView otherSandbox = service.createRequest("other", "a.example", "not covered");
        NetworkAccessRequestView otherHost = service.createRequest("sb", "z.example", "not in the set");
        NetworkAccessRequestView set = service.createHostSetRequest("sb", List.of("a.example", "b.example"), SOURCE, List.of()).request();

        service.approve(set.id(), "fabian");

        NetworkAccessRequestView resolved = service.getStatus(single.id()).orElseThrow();
        assertEquals(RequestStatus.APPROVED, resolved.status());
        assertEquals("fabian", resolved.decidedBy());
        assertEquals(Boolean.TRUE, resolved.sbxApplied());
        assertEquals(RequestStatus.PENDING, service.getStatus(otherSandbox.id()).orElseThrow().status());
        assertEquals(RequestStatus.PENDING, service.getStatus(otherHost.id()).orElseThrow().status());
        // The set applied each host once; the resolved single did not trigger a second sbx call.
        assertEquals(2, port.calls.size(), port.calls.toString());
        // And a waiter on the single request is released.
        assertEquals(RequestStatus.APPROVED, service.awaitDecision(single.id(), Duration.ofMillis(10)).orElseThrow().status());
    }

    @Test
    void aRequestForAnAlreadyGrantedHostIsApprovedWithoutAHumanOrSbx() {
        RecordingSbxPolicyPort port = new RecordingSbxPolicyPort();
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), port);
        List<String> events = new CopyOnWriteArrayList<>();
        service.subscribe(new NetworkAccessListener() {
            @Override
            public void requestCreated(NetworkAccessRequestView request) {
                events.add("created " + request.host());
            }

            @Override
            public void requestDecided(NetworkAccessRequestView request) {
                events.add("decided " + request.host() + " " + request.status());
            }
        });
        service.approve(service.createRequest("sb", "a.example", "first time").id(), "fabian");

        NetworkAccessRequestView again = service.createRequest("sb", "a.example", "second time");

        assertEquals(RequestStatus.APPROVED, again.status());
        assertEquals(NetworkAccessService.ALREADY_GRANTED_DECIDER, again.decidedBy());
        assertEquals(Boolean.TRUE, again.sbxApplied());
        assertEquals(1, port.calls.size());
        assertEquals(RequestStatus.APPROVED, service.awaitDecision(again.id(), Duration.ofSeconds(1)).orElseThrow().status());
        // Never announced as a question: only the first request was "created".
        assertEquals(List.of("created a.example", "decided a.example APPROVED", "decided a.example APPROVED"), events);
        // Another sandbox gets no such shortcut.
        assertEquals(RequestStatus.PENDING, service.createRequest("other", "a.example", "mine").status());
    }

    @Test
    void aHostSetLeavesOutGrantedHostsAndCollapsesDuplicates() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());
        service.approve(service.createRequest("sb", "a.example", "earlier").id(), "fabian");

        HostSetRequestOutcome outcome = service.createHostSetRequest("sb", List.of("a.example", "b.example", "b.example", "c.example"), SOURCE, List.of());

        assertEquals(List.of("b.example", "c.example"), outcome.request().hosts());
        assertEquals(HostSetRequestOutcome.Kind.ALL_GRANTED,
                service.createHostSetRequest("sb", List.of("a.example"), "/other/.goversbx/network.yaml", List.of()).kind());
    }

    @Test
    void onePendingSetPerSandboxAndSource() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());
        HostSetRequestOutcome first = service.createHostSetRequest("sb", List.of("a.example"), SOURCE, List.of());

        HostSetRequestOutcome again = service.createHostSetRequest("sb", List.of("a.example", "b.example"), SOURCE, List.of());
        HostSetRequestOutcome otherSource = service.createHostSetRequest("sb", List.of("a.example"), "/w2/.goversbx/network.yaml", List.of());
        HostSetRequestOutcome otherSandbox = service.createHostSetRequest("sb2", List.of("a.example"), SOURCE, List.of());

        assertEquals(HostSetRequestOutcome.Kind.ALREADY_PENDING, again.kind());
        assertEquals(first.request().id(), again.request().id());
        assertEquals(HostSetRequestOutcome.Kind.CREATED, otherSource.kind());
        assertEquals(HostSetRequestOutcome.Kind.CREATED, otherSandbox.kind());
        // Once decided, the next evaluation of the same source asks again for what is not granted.
        service.approve(first.request().id(), "fabian");
        HostSetRequestOutcome afterApproval = service.createHostSetRequest("sb", List.of("a.example", "b.example"), SOURCE, List.of());
        assertEquals(HostSetRequestOutcome.Kind.CREATED, afterApproval.kind());
        assertEquals(List.of("b.example"), afterApproval.request().hosts());
    }

    @Test
    void forgettingGrantsMakesANewIncarnationStartFromScratch() {
        NetworkAccessService service = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());
        service.approve(service.createHostSetRequest("sb", List.of("a.example", "b.example"), SOURCE, List.of()).request().id(), "fabian");
        assertEquals(HostSetRequestOutcome.Kind.ALL_GRANTED, service.createHostSetRequest("sb", List.of("a.example", "b.example"), SOURCE, List.of()).kind());

        service.forgetGrants("sb");

        HostSetRequestOutcome fresh = service.createHostSetRequest("sb", List.of("a.example", "b.example"), SOURCE, List.of());
        assertEquals(HostSetRequestOutcome.Kind.CREATED, fresh.kind());
        assertEquals(List.of("a.example", "b.example"), fresh.request().hosts());
        assertEquals(RequestStatus.PENDING, service.createRequest("sb", "a.example", "again").status());
    }

    @Test
    void theAdminPortGuardIsPartOfTheHostRule() {
        NetworkAccessService guarded = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort(), 8788);
        NetworkAccessService unguarded = new NetworkAccessService(new InMemoryRequestRepository(), new RecordingSbxPolicyPort());

        assertTrue(guarded.hostRejectionReason("host.docker.internal:8788").isPresent());
        assertTrue(guarded.hostRejectionReason("localhost").isPresent());
        assertFalse(guarded.hostRejectionReason("host.docker.internal:3000").isPresent());
        assertThrows(InvalidHostException.class, () -> guarded.createRequest("sb", "localhost:8788", "self-approve"));
        assertFalse(unguarded.hostRejectionReason("localhost:8788").isPresent());
    }
}
