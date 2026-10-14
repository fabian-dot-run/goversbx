package run.fabian.daemon.networkaccess.domain;

import run.fabian.daemon.networkaccess.api.RequestStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root for a single network-access request. Mutable by design: the repository holds
 * this exact instance, and approve()/deny() mutate it in place rather than producing a copy.
 */
public final class NetworkAccessRequest {

    private final UUID id;
    private final String sandboxName;
    private final String host;
    private final String reason;
    private final Instant requestedAt;

    private volatile RequestStatus status;
    private volatile Instant decidedAt;
    private volatile String decidedBy;
    private volatile Boolean sbxApplied;
    private volatile String sbxOutput;

    public NetworkAccessRequest(UUID id, String sandboxName, String host, String reason, Instant requestedAt) {
        this.id = id;
        this.sandboxName = sandboxName;
        this.host = host;
        this.reason = reason;
        this.requestedAt = requestedAt;
        this.status = RequestStatus.PENDING;
    }

    public synchronized void approve(String decidedBy, boolean sbxApplied, String sbxOutput) {
        requirePending();
        this.status = RequestStatus.APPROVED;
        this.decidedAt = Instant.now();
        this.decidedBy = decidedBy;
        this.sbxApplied = sbxApplied;
        this.sbxOutput = sbxOutput;
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

    public String getHost() {
        return host;
    }

    public String getReason() {
        return reason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
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

    public Boolean getSbxApplied() {
        return sbxApplied;
    }

    public String getSbxOutput() {
        return sbxOutput;
    }
}
