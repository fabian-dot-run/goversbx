package run.fabian.daemon.networkaccess.api;

import run.fabian.daemon.networkaccess.domain.NetworkAccessRequest;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable, published view of a {@link NetworkAccessRequest}. This - not the mutable domain
 * entity - is what the {@code mcp} bounded context and the admin HTTP API are allowed to see.
 */
public record NetworkAccessRequestView(
        UUID id,
        String sandboxName,
        String host,
        String reason,
        Instant requestedAt,
        RequestStatus status,
        Instant decidedAt,
        String decidedBy,
        Boolean sbxApplied,
        String sbxOutput) {

    public static NetworkAccessRequestView from(NetworkAccessRequest request) {
        return new NetworkAccessRequestView(
                request.getId(),
                request.getSandboxName(),
                request.getHost(),
                request.getReason(),
                request.getRequestedAt(),
                request.getStatus(),
                request.getDecidedAt(),
                request.getDecidedBy(),
                request.getSbxApplied(),
                request.getSbxOutput());
    }
}
