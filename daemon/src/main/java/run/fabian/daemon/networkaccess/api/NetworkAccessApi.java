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

    NetworkAccessRequestView createRequest(String sandboxName, String host, String reason);

    Optional<NetworkAccessRequestView> awaitDecision(UUID requestId, Duration timeout);

    Optional<NetworkAccessRequestView> getStatus(UUID requestId);
}
