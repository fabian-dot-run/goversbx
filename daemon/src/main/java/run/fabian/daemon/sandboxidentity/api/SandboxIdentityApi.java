package run.fabian.daemon.sandboxidentity.api;

/**
 * The only thing the {@code mcp} bounded context is allowed to depend on from
 * {@code sandboxidentity}.
 */
public interface SandboxIdentityApi {

    /** True if this header value is the raw, unsubstituted placeholder - i.e. this sandbox isn't registered yet. */
    boolean isUnsubstitutedPlaceholder(String sandboxNameHeaderValue);

    /** Registers every currently-running sandbox this service doesn't yet know about. Best-effort; never throws. */
    void registerAnyUnregisteredSandboxes();
}
