package run.fabian.daemon.networkaccess.application;

import run.fabian.daemon.networkaccess.api.HostGrant;
import run.fabian.daemon.networkaccess.api.HostSetRequestOutcome;
import run.fabian.daemon.networkaccess.api.NetworkAccessApi;
import run.fabian.daemon.networkaccess.api.NetworkAccessListener;
import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;
import run.fabian.daemon.networkaccess.api.RequestOrigin;
import run.fabian.daemon.networkaccess.api.RequestStatus;
import run.fabian.daemon.networkaccess.application.port.RequestRepository;
import run.fabian.daemon.networkaccess.application.port.SbxPolicyPort;
import run.fabian.daemon.networkaccess.domain.AlreadyDecidedException;
import run.fabian.daemon.networkaccess.domain.HostValidation;
import run.fabian.daemon.networkaccess.domain.InvalidHostException;
import run.fabian.daemon.networkaccess.domain.LocalTargetGuard;
import run.fabian.daemon.networkaccess.domain.NetworkAccessRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Application service for the {@code networkaccess} bounded context. Implements
 * {@link NetworkAccessApi} (the cross-context published surface) and additionally exposes
 * admin-only use cases (list/approve/deny) for the in-context web adapter.
 *
 * <p>Besides the requests themselves it keeps, per sandbox name, the hosts applied to sbx by
 * approvals in this daemon process. That is what lets a host set leave out what the sandbox already
 * has, and what answers an agent's request for an already granted host without a human. It is
 * in-memory like the request repository (sbx holds the durable rules), and it is wiped for a name
 * when a sandbox is created anew under it ({@link #forgetGrants}).
 */
public final class NetworkAccessService implements NetworkAccessApi {

    private static final Logger log = LoggerFactory.getLogger(NetworkAccessService.class);
    /** {@code decidedBy} of a request the daemon answered itself because the host was already granted. */
    public static final String ALREADY_GRANTED_DECIDER = "goversbx";

    private final RequestRepository repository;
    private final SbxPolicyPort sbxPolicyPort;
    private final int selfAdminPort;
    private final List<NetworkAccessListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Set<String>> grantedHosts = new ConcurrentHashMap<>();

    /**
     * @param selfAdminPort the daemon's own admin port, which a sandbox must never be able to get
     *     opened to itself through goversbx (threat-model F3); {@code -1} for "no admin port".
     */
    public NetworkAccessService(RequestRepository repository, SbxPolicyPort sbxPolicyPort, int selfAdminPort) {
        this.repository = repository;
        this.sbxPolicyPort = sbxPolicyPort;
        this.selfAdminPort = selfAdminPort;
    }

    /** Without an admin port to guard (tests of the request flow itself). */
    public NetworkAccessService(RequestRepository repository, SbxPolicyPort sbxPolicyPort) {
        this(repository, sbxPolicyPort, -1);
    }

    @Override
    public Optional<String> hostRejectionReason(String host) {
        String reason = HostValidation.rejectionReason(host);
        if (reason == null && selfAdminPort >= 0) {
            reason = LocalTargetGuard.rejectionReason(host, selfAdminPort);
        }
        return Optional.ofNullable(reason);
    }

    @Override
    public NetworkAccessRequestView createRequest(String sandboxName, String host, String reason) {
        requireAcceptable(host);
        NetworkAccessRequest request = new NetworkAccessRequest(UUID.randomUUID(), sandboxName, host, reason, Instant.now());
        if (granted(sandboxName).contains(host)) {
            // Already applied to sbx for this sandbox: answer without a human, and without a second
            // sbx call. Reported as decided only - it was never an open question.
            request.approve(ALREADY_GRANTED_DECIDER, true, "already granted to this sandbox");
            repository.add(request);
            log.info("Request for {} from {} answered as approved: already granted", host, sandboxName);
            return decided(request);
        }
        repository.add(request);
        NetworkAccessRequestView view = NetworkAccessRequestView.from(request);
        publish(listener -> listener.requestCreated(view));
        return view;
    }

    @Override
    public HostSetRequestOutcome createHostSetRequest(String sandboxName, List<String> hosts, String source, List<String> profiles) {
        for (String host : hosts) {
            requireAcceptable(host);
        }
        Set<String> already = granted(sandboxName);
        List<String> wanted = new ArrayList<>(new LinkedHashSet<>(hosts));
        wanted.removeIf(already::contains);
        if (wanted.isEmpty()) {
            return HostSetRequestOutcome.allGranted();
        }
        Optional<NetworkAccessRequest> pending = repository.findAll().stream()
                .filter(r -> r.getStatus() == RequestStatus.PENDING && r.getOrigin() == RequestOrigin.PROJECT_FILE)
                .filter(r -> r.getSandboxName().equals(sandboxName) && source.equals(r.getSource()))
                .findFirst();
        if (pending.isPresent()) {
            return HostSetRequestOutcome.alreadyPending(NetworkAccessRequestView.from(pending.get()));
        }
        NetworkAccessRequest request = NetworkAccessRequest.projectHostSet(
                UUID.randomUUID(), sandboxName, wanted, source, profiles, Instant.now());
        repository.add(request);
        NetworkAccessRequestView view = NetworkAccessRequestView.from(request);
        log.info("Host set of {} host(s) for {} from {} is pending", wanted.size(), sandboxName, source);
        publish(listener -> listener.requestCreated(view));
        return HostSetRequestOutcome.created(view);
    }

    @Override
    public void forgetGrants(String sandboxName) {
        Set<String> removed = grantedHosts.remove(sandboxName);
        if (removed != null && !removed.isEmpty()) {
            log.info("Forgot {} grant(s) recorded for {}: new sandbox incarnation", removed.size(), sandboxName);
        }
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

    /** The hosts applied to sbx for this sandbox by approvals in this daemon process. */
    public Set<String> grantedHosts(String sandboxName) {
        return Set.copyOf(granted(sandboxName));
    }

    public NetworkAccessRequestView approve(UUID requestId, String decidedBy) {
        NetworkAccessRequest request = requireRequest(requestId);
        if (request.getStatus() != RequestStatus.PENDING) {
            throw new AlreadyDecidedException("Request " + requestId + " was already " + request.getStatus());
        }
        List<SbxPolicyPort.Result> results = sbxPolicyPort.allowNetwork(request.getSandboxName(), request.getHosts());
        List<HostGrant> grants = new ArrayList<>(results.size());
        for (int i = 0; i < request.getHosts().size(); i++) {
            grants.add(new HostGrant(request.getHosts().get(i), results.get(i).applied(), results.get(i).output()));
        }
        request.approve(decidedBy, grants);
        recordGrants(request.getSandboxName(), grants);
        NetworkAccessRequestView view = decided(request);
        if (!request.isSingleHost()) {
            resolvePendingSinglesCoveredBy(request, decidedBy);
        }
        return view;
    }

    public NetworkAccessRequestView deny(UUID requestId, String decidedBy) {
        NetworkAccessRequest request = requireRequest(requestId);
        request.deny(decidedBy);
        return decided(request);
    }

    /** Registers a listener for request lifecycle events; the returned handle removes it again. */
    public Runnable subscribe(NetworkAccessListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * An approved host set also answers the agent's own pending requests for hosts it contains, so
     * a sandbox that ran into one of them before the set was decided isn't left waiting for a second
     * click. Each gets the host's outcome from the set.
     */
    private void resolvePendingSinglesCoveredBy(NetworkAccessRequest set, String decidedBy) {
        Map<String, HostGrant> byHost = new ConcurrentHashMap<>();
        set.getGrants().forEach(g -> byHost.put(g.host(), g));
        for (NetworkAccessRequest other : repository.findAll()) {
            if (other.getStatus() != RequestStatus.PENDING || !other.isSingleHost()
                    || !other.getSandboxName().equals(set.getSandboxName())) {
                continue;
            }
            HostGrant grant = byHost.get(other.getHosts().get(0));
            if (grant == null) {
                continue;
            }
            try {
                other.approve(decidedBy, grant.applied(), grant.output());
            } catch (AlreadyDecidedException raced) {
                continue;
            }
            log.info("Pending request {} for {} from {} resolved by the approved host set {}",
                    other.getId(), grant.host(), other.getSandboxName(), set.getId());
            decided(other);
        }
    }

    private void recordGrants(String sandboxName, List<HostGrant> grants) {
        Set<String> hosts = grantedHosts.computeIfAbsent(sandboxName, k -> ConcurrentHashMap.newKeySet());
        grants.stream().filter(HostGrant::applied).forEach(g -> hosts.add(g.host()));
    }

    private Set<String> granted(String sandboxName) {
        return grantedHosts.getOrDefault(sandboxName, Set.of());
    }

    private void requireAcceptable(String host) {
        Optional<String> rejection = hostRejectionReason(host);
        if (rejection.isPresent()) {
            throw new InvalidHostException(rejection.get());
        }
    }

    private NetworkAccessRequestView decided(NetworkAccessRequest request) {
        repository.notifyDecided(request.getId());
        NetworkAccessRequestView view = NetworkAccessRequestView.from(request);
        publish(listener -> listener.requestDecided(view));
        return view;
    }

    private void publish(Consumer<NetworkAccessListener> event) {
        for (NetworkAccessListener listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                log.warn("Network-access listener failed", e);
            }
        }
    }

    private NetworkAccessRequest requireRequest(UUID requestId) {
        return repository.findById(requestId)
                .orElseThrow(() -> new NoSuchElementException("Request " + requestId + " not found"));
    }
}
