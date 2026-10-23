package run.fabian.daemon.networkaccess.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only thing other bounded contexts ({@code mcp}, {@code projecthosts}) are allowed to depend
 * on from {@code networkaccess}. Admin-only operations (listing, approve, deny) are deliberately not
 * part of this published surface - they stay on the concrete application service, used directly
 * by the (same-context) admin web adapter.
 */
public interface NetworkAccessApi {

    /**
     * A reason the host is not acceptable to request a grant for, or empty if it's fine: threat-model
     * F2 (wildcards, URLs, IP literals, malformed names) and F3 (the daemon's own admin port on a
     * host-local alias). Lets a caller refuse a bad host with a clean message without reaching into
     * {@code networkaccess.domain}; {@link #createRequest} and {@link #createHostSetRequest} enforce
     * the same rule as an invariant regardless.
     */
    Optional<String> hostRejectionReason(String host);

    /**
     * A single-host request from the agent. If the host is already granted to the sandbox (by an
     * earlier approval in this daemon process) the request comes back approved right away, with no
     * human involved and no sbx call; otherwise it is pending.
     */
    NetworkAccessRequestView createRequest(String sandboxName, String host, String reason);

    /**
     * A host-set request on behalf of the project file {@code source} (its path, shown to the
     * approver) that named {@code profiles}. Hosts already granted to the sandbox are left out;
     * duplicates are collapsed. One pending set per sandbox and source: a second call while the
     * first is undecided returns that one.
     */
    HostSetRequestOutcome createHostSetRequest(String sandboxName, List<String> hosts, String source, List<String> profiles);

    /**
     * Forgets every grant recorded for {@code sandboxName}: the sandbox was created anew under
     * that name (new incarnation), so nothing granted to its predecessor holds for it.
     */
    void forgetGrants(String sandboxName);

    Optional<NetworkAccessRequestView> awaitDecision(UUID requestId, Duration timeout);

    Optional<NetworkAccessRequestView> getStatus(UUID requestId);
}
