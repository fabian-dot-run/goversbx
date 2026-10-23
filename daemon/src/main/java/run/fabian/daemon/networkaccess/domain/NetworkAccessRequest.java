package run.fabian.daemon.networkaccess.domain;

import run.fabian.daemon.networkaccess.api.HostGrant;
import run.fabian.daemon.networkaccess.api.RequestOrigin;
import run.fabian.daemon.networkaccess.api.RequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for one network-access request: one or more hosts for one sandbox, decided as a
 * whole by a human, applied to sbx host by host. An agent request has exactly one host; a project
 * host set has the hosts of the project file, plus where it came from ({@code source},
 * {@code profiles}) for the approver. Mutable by design: the repository holds this exact instance,
 * and approve()/deny() mutate it in place rather than producing a copy.
 */
public final class NetworkAccessRequest {

    private final UUID id;
    private final String sandboxName;
    private final List<String> hosts;
    private final String reason;
    private final Instant requestedAt;
    private final RequestOrigin origin;
    private final String source;
    private final List<String> profiles;

    private volatile RequestStatus status;
    private volatile Instant decidedAt;
    private volatile String decidedBy;
    private volatile List<HostGrant> grants;

    /** A single-host request from the agent. */
    public NetworkAccessRequest(UUID id, String sandboxName, String host, String reason, Instant requestedAt) {
        this(id, sandboxName, List.of(host), reason, requestedAt, RequestOrigin.AGENT, null, List.of());
    }

    private NetworkAccessRequest(UUID id, String sandboxName, List<String> hosts, String reason, Instant requestedAt,
                                 RequestOrigin origin, String source, List<String> profiles) {
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("A request needs at least one host");
        }
        if (hosts.stream().distinct().count() != hosts.size()) {
            throw new IllegalArgumentException("A request must not repeat a host: " + hosts);
        }
        this.id = id;
        this.sandboxName = sandboxName;
        this.hosts = List.copyOf(hosts);
        this.reason = reason;
        this.requestedAt = requestedAt;
        this.origin = origin;
        this.source = source;
        this.profiles = List.copyOf(profiles);
        this.status = RequestStatus.PENDING;
    }

    /** A host set declared by the project file at {@code source}, which named {@code profiles}. */
    public static NetworkAccessRequest projectHostSet(UUID id, String sandboxName, List<String> hosts,
                                                      String source, List<String> profiles, Instant requestedAt) {
        String reason = "Standard hosts of the project, declared in " + source
                + (profiles.isEmpty() ? "" : " (profiles: " + String.join(", ", profiles) + ")");
        return new NetworkAccessRequest(id, sandboxName, hosts, reason, requestedAt, RequestOrigin.PROJECT_FILE, source, profiles);
    }

    /** Approves a single-host request with the outcome of applying that host. */
    public synchronized void approve(String decidedBy, boolean sbxApplied, String sbxOutput) {
        if (!isSingleHost()) {
            throw new IllegalStateException("Request " + id + " has " + hosts.size() + " hosts - approve it with one grant per host");
        }
        approve(decidedBy, List.of(new HostGrant(hosts.get(0), sbxApplied, sbxOutput)));
    }

    /** Approves the request with one grant per host, in the request's host order. */
    public synchronized void approve(String decidedBy, List<HostGrant> grants) {
        requirePending();
        List<String> granted = grants.stream().map(HostGrant::host).toList();
        if (!granted.equals(hosts)) {
            throw new IllegalArgumentException("Grants " + granted + " do not match the request's hosts " + hosts);
        }
        this.status = RequestStatus.APPROVED;
        this.decidedAt = Instant.now();
        this.decidedBy = decidedBy;
        this.grants = List.copyOf(grants);
    }

    public synchronized void deny(String decidedBy) {
        requirePending();
        this.status = RequestStatus.DENIED;
        this.decidedAt = Instant.now();
        this.decidedBy = decidedBy;
    }

    private void requirePending() {
        if (status != RequestStatus.PENDING) {
            throw new AlreadyDecidedException("Request " + id + " was already " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getSandboxName() {
        return sandboxName;
    }

    public List<String> getHosts() {
        return hosts;
    }

    public boolean isSingleHost() {
        return hosts.size() == 1;
    }

    public String getReason() {
        return reason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public RequestOrigin getOrigin() {
        return origin;
    }

    /** The project file a host set came from, or {@code null} for an agent request. */
    public String getSource() {
        return source;
    }

    public List<String> getProfiles() {
        return profiles;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    /** Per-host outcomes once approved, in host order; {@code null} before that. */
    public List<HostGrant> getGrants() {
        return grants;
    }

    /** Whether every host was applied to sbx; {@code null} until approved. */
    public Boolean getSbxApplied() {
        List<HostGrant> g = grants;
        return g == null ? null : g.stream().allMatch(HostGrant::applied);
    }

    /** sbx's output: verbatim for a single host, one {@code host: output} line per host otherwise. */
    public String getSbxOutput() {
        List<HostGrant> g = grants;
        if (g == null) {
            return null;
        }
        if (g.size() == 1) {
            return g.get(0).output();
        }
        return String.join("\n", g.stream().map(x -> x.host() + ": " + x.output()).toList());
    }
}
