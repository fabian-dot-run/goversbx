package com.goversbx.daemon.store;

import com.goversbx.common.NetworkAccessRequest;
import com.goversbx.common.RequestStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class RequestStore {

    private final Map<UUID, NetworkAccessRequest> requests = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<NetworkAccessRequest>> waiters = new ConcurrentHashMap<>();

    public NetworkAccessRequest create(String sandboxName, String host, String reason) {
        UUID id = UUID.randomUUID();
        NetworkAccessRequest request = new NetworkAccessRequest(id, sandboxName, host, reason, Instant.now());
        requests.put(id, request);
        waiters.put(id, new CompletableFuture<>());
        return request;
    }

    public Optional<NetworkAccessRequest> get(UUID id) {
        return Optional.ofNullable(requests.get(id));
    }

    public List<NetworkAccessRequest> list(RequestStatus statusFilter) {
        return requests.values().stream()
                .filter(r -> statusFilter == null || r.getStatus() == statusFilter)
                .sorted(Comparator.comparing(NetworkAccessRequest::getRequestedAt).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Blocks the calling thread until the request is decided or the timeout elapses.
     * Returns the (possibly still-pending) current state either way.
     */
    public NetworkAccessRequest await(UUID id, Duration timeout) {
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
            return future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return requests.get(id);
        }
    }

    public void complete(UUID id) {
        NetworkAccessRequest request = requests.get(id);
        CompletableFuture<NetworkAccessRequest> future = waiters.get(id);
        if (request != null && future != null) {
            future.complete(request);
        }
    }
}
