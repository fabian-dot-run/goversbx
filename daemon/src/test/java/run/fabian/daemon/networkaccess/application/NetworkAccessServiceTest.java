package run.fabian.daemon.networkaccess.application;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.adapter.persistence.InMemoryRequestRepository;
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

        @Override
        public Result allowNetwork(String sandboxName, String host) {
            calls.add(new Call(sandboxName, host));
            return new Result(true, "applied for " + sandboxName);
        }
    }

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
}
