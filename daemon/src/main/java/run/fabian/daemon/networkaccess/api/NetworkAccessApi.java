package run.fabian.daemon.networkaccess.api;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The only thing another bounded context ({@code mcp}) is allowed to depend on from
 * {@code networkaccess}. Admin-only operations (listing, approve, deny) are deliberately not
 * part of this published surface - they stay on the concrete application service, used directly
 * by the (same-context) admin web adapter.
 */
public interface NetworkAccessApi {

    /**
     * A reason the host is not acceptable to request a grant for, or empty if it's fine: threat-model
     * F2 (wildcards, URLs, IP literals, malformed names) and F3 (the daemon's own admin port on a
     * host-local alias). Lets a caller refuse a bad host with a clean message without reaching into
     * {@code networkaccess.domain}; {@link #createRequest} enforces the same rule
     * as an invariant regardless.
     */
    Optional<String> hostRejectionReason(String host);

    /** A request for one host; always pending until a human decides it. */
    NetworkAccessRequestView createRequest(String sandboxName, String host, String reason);

    Optional<NetworkAccessRequestView> awaitDecision(UUID requestId, Duration timeout);

    Optional<NetworkAccessRequestView> getStatus(UUID requestId);
}
