package run.fabian.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

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

    @JsonCreator
    public static NetworkAccessRequest fromJson(
            @JsonProperty("id") UUID id,
            @JsonProperty("sandboxName") String sandboxName,
            @JsonProperty("host") String host,
            @JsonProperty("reason") String reason,
            @JsonProperty("requestedAt") Instant requestedAt,
            @JsonProperty("status") RequestStatus status,
            @JsonProperty("decidedAt") Instant decidedAt,
            @JsonProperty("decidedBy") String decidedBy,
            @JsonProperty("sbxApplied") Boolean sbxApplied,
            @JsonProperty("sbxOutput") String sbxOutput) {
        NetworkAccessRequest request = new NetworkAccessRequest(id, sandboxName, host, reason, requestedAt);
        request.status = status;
        request.decidedAt = decidedAt;
        request.decidedBy = decidedBy;
        request.sbxApplied = sbxApplied;
        request.sbxOutput = sbxOutput;
        return request;
    }

    public synchronized void approve(String decidedBy, boolean sbxApplied, String sbxOutput) {
        this.status = RequestStatus.APPROVED;
        this.decidedAt = Instant.now();
        this.decidedBy = decidedBy;
        this.sbxApplied = sbxApplied;
        this.sbxOutput = sbxOutput;
    }

    public synchronized void deny(String decidedBy) {
        this.status = RequestStatus.DENIED;
        this.decidedAt = Instant.now();
        this.decidedBy = decidedBy;
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
