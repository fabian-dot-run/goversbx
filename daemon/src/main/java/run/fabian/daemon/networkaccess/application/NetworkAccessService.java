package run.fabian.daemon.networkaccess.application;

import run.fabian.daemon.networkaccess.api.NetworkAccessApi;
import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;
import run.fabian.daemon.networkaccess.api.RequestStatus;
import run.fabian.daemon.networkaccess.application.port.RequestRepository;
import run.fabian.daemon.networkaccess.application.port.SbxPolicyPort;
import run.fabian.daemon.networkaccess.domain.NetworkAccessRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for the {@code networkaccess} bounded context. Implements
 * {@link NetworkAccessApi} (the cross-context published surface) and additionally exposes
 * admin-only use cases (list/approve/deny) for the in-context web adapter.
 */
public final class NetworkAccessService implements NetworkAccessApi {

    private final RequestRepository repository;
    private final SbxPolicyPort sbxPolicyPort;

    public NetworkAccessService(RequestRepository repository, SbxPolicyPort sbxPolicyPort) {
        this.repository = repository;
        this.sbxPolicyPort = sbxPolicyPort;
    }

    @Override
    public NetworkAccessRequestView createRequest(String sandboxName, String host, String reason) {
        NetworkAccessRequest request = new NetworkAccessRequest(UUID.randomUUID(), sandboxName, host, reason, Instant.now());
        repository.add(request);
        return NetworkAccessRequestView.from(request);
    }

    @Override
    public Optional<NetworkAccessRequestView> awaitDecision(UUID requestId, Duration timeout) {
        NetworkAccessRequest request = repository.awaitDecision(requestId, timeout);
        return Optional.ofNullable(request).map(NetworkAccessRequestView::from);
    }

    @Override
    public Optional<NetworkAccessRequestView> getStatus(UUID requestId) {
        return repository.findById(requestId).map(NetworkAccessRequestView::from);
    }

    public List<NetworkAccessRequestView> list(RequestStatus statusFilter) {
        return repository.findAll().stream()
                .filter(r -> statusFilter == null || r.getStatus() == statusFilter)
                .sorted(Comparator.comparing(NetworkAccessRequest::getRequestedAt).reversed())
                .map(NetworkAccessRequestView::from)
                .toList();
    }

    public NetworkAccessRequestView approve(UUID requestId, String decidedBy) {
        NetworkAccessRequest request = requireRequest(requestId);
        SbxPolicyPort.Result result = sbxPolicyPort.allowNetwork(request.getSandboxName(), request.getHost());
        request.approve(decidedBy, result.applied(), result.output());
        repository.notifyDecided(requestId);
        return NetworkAccessRequestView.from(request);
    }

    public NetworkAccessRequestView deny(UUID requestId, String decidedBy) {
        NetworkAccessRequest request = requireRequest(requestId);
        request.deny(decidedBy);
        repository.notifyDecided(requestId);
        return NetworkAccessRequestView.from(request);
    }

    private NetworkAccessRequest requireRequest(UUID requestId) {
        return repository.findById(requestId)
                .orElseThrow(() -> new NoSuchElementException("Request " + requestId + " not found"));
    }
}
