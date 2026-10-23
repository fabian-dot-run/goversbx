package run.fabian.daemon.sandboxidentity.api;

import java.util.Optional;

/**
 * The only thing the {@code mcp} and {@code projecthosts} bounded contexts are allowed to depend on
 * from {@code sandboxidentity}.
 */
public interface SandboxIdentityApi {

    /**
     * Resolves the proxy-substituted identity header value to the sandbox it was minted for.
     *
     * <p>The substituted value is a per-sandbox <em>secret token</em> the daemon minted and handed
     * to the sbx proxy; a sandbox never sees it and cannot forge it. Returns empty when the value
     * is not a token this daemon currently knows - the raw placeholder (sandbox not registered
     * yet), a stale token from before a daemon restart, or a value a sandbox supplied itself
     * instead of letting the proxy substitute the placeholder. In every empty case the caller must
     * trigger registration and ask the sandbox to retry; it must <em>not</em> treat the header
     * value as a sandbox name.
     */
    Optional<String> resolveSandboxName(String identityHeaderValue);

    /** Registers every currently-running sandbox this service doesn't yet know about. Best-effort; never throws. */
    void registerAnyUnregisteredSandboxes();

    /**
     * The sandbox of that name as {@code sbx ls} reports it now (any status), provided the daemon
     * has registered an identity for the name; empty if it never did or sbx no longer lists it.
     * Costs one {@code sbx ls}.
     */
    Optional<RegisteredSandbox> findRegistered(String sandboxName);
}
