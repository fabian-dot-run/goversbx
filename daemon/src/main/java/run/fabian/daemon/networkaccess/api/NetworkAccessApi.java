package run.fabian.daemon.networkaccess.api;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The only thing the {@code mcp} bounded context is allowed to depend on from
 * {@code networkaccess}. Admin-only operations (listing, approve, deny) are deliberately not
 * part of this published surface - they stay on the concrete application service, used directly
 * by the (same-context) admin web adapter.
 */
public interface NetworkAccessApi {

    /**
     * A reason the host is not acceptable to request a grant for (threat-model F2: wildcards, URLs, IP
     * literals, malformed names), or empty if it's fine. Lets the {@code mcp} tool refuse a bad host
     * with a clean message without reaching into {@code networkaccess.domain}; {@link #createRequest}
     * enforces the same rule as an invariant regardless.
     */
    Optional<String> hostRejectionReason(String host);

    NetworkAccessRequestView createRequest(String sandboxName, String host, String reason);

    Optional<NetworkAccessRequestView> awaitDecision(UUID requestId, Duration timeout);

    Optional<NetworkAccessRequestView> getStatus(UUID requestId);
}
