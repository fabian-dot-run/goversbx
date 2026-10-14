package run.fabian.daemon.networkaccess.adapter.persistence;

import run.fabian.daemon.networkaccess.application.port.RequestRepository;
import run.fabian.daemon.networkaccess.api.RequestStatus;
import run.fabian.daemon.networkaccess.domain.NetworkAccessRequest;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * In-memory {@link RequestRepository}. Acceptable for the MVP because the durable state that
 * actually matters (the sandbox's network policy) lives in {@code sbx} itself, not here - losing
 * request history on restart is fine. Holds the same mutable entity instance the application
 * layer mutates in place, rather than defensive copies.
 */
public final class InMemoryRequestRepository implements RequestRepository {

    private final ConcurrentHashMap<UUID, NetworkAccessRequest> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<NetworkAccessRequest>> waiters = new ConcurrentHashMap<>();

    @Override
    public void add(NetworkAccessRequest request) {
        requests.put(request.getId(), request);
        waiters.put(request.getId(), new CompletableFuture<>());
    }

    @Override
    public Optional<NetworkAccessRequest> findById(UUID id) {
        return Optional.ofNullable(requests.get(id));
    }

    @Override
    public List<NetworkAccessRequest> findAll() {
        return List.copyOf(requests.values());
    }

    @Override
    public NetworkAccessRequest awaitDecision(UUID id, Duration timeout) {
        NetworkAccessRequest current = requests.get(id);
        if (current == null) {
            return null;
        }
        if (current.getStatus() != RequestStatus.PENDING) {
            return current;
        }
        CompletableFuture<NetworkAccessRequest> future = waiters.get(id);
        if (future == null) {
            return current;
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return requests.get(id);
        }
    }

    @Override
    public void notifyDecided(UUID id) {
        NetworkAccessRequest request = requests.get(id);
        CompletableFuture<NetworkAccessRequest> future = waiters.get(id);
        if (request != null && future != null) {
            future.complete(request);
        }
    }
}
