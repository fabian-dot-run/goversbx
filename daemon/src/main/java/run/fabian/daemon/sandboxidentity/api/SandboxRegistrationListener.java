package run.fabian.daemon.sandboxidentity.api;

/**
 * Told whenever the daemon registers a sandbox incarnation it had not registered before - a brand
 * new sandbox, or one created again under a known name. Called synchronously on the registering
 * thread; implementations must be quick and must not throw.
 */
public interface SandboxRegistrationListener {

    void sandboxRegistered(RegisteredSandbox sandbox);
}
