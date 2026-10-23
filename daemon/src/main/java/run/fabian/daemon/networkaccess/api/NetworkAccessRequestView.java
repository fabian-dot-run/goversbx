package run.fabian.daemon.networkaccess.api;

import run.fabian.daemon.networkaccess.domain.NetworkAccessRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable, published view of a {@link NetworkAccessRequest}. This - not the mutable domain
 * entity - is what the {@code mcp} bounded context and the admin HTTP API are allowed to see.
 *
 * <p>A request carries one or more {@code hosts}. {@code host} is the one host of a single-host
 * request (every agent request) and {@code null} for a host set, so consumers that only know
 * single requests keep working and a set can't be mistaken for one. {@code hostResults} is filled
 * on approval, one entry per host in order; {@code sbxApplied} is true only if every host applied.
 */
public record NetworkAccessRequestView(
        UUID id,
        String sandboxName,
        String host,
        List<String> hosts,
        String reason,
        RequestOrigin origin,
        String source,
        List<String> profiles,
        Instant requestedAt,
        RequestStatus status,
        Instant decidedAt,
        String decidedBy,
        Boolean sbxApplied,
        String sbxOutput,
        List<HostGrant> hostResults) {

    public static NetworkAccessRequestView from(NetworkAccessRequest request) {
        return new NetworkAccessRequestView(
                request.getId(),
                request.getSandboxName(),
                request.isSingleHost() ? request.getHosts().get(0) : null,
                request.getHosts(),
                request.getReason(),
                request.getOrigin(),
                request.getSource(),
                request.getProfiles(),
                request.getRequestedAt(),
                request.getStatus(),
                request.getDecidedAt(),
                request.getDecidedBy(),
                request.getSbxApplied(),
                request.getSbxOutput(),
                request.getGrants());
    }
}
