package run.fabian.daemon.networkaccess.api;

/**
 * Lifecycle events of network-access requests, for anything that has to tell a human about them
 * (the admin UI's live stream today, other notification channels later). Called synchronously on
 * the thread that changed the request - implementations must be quick and must not throw.
 *
 * <p>{@link #requestCreated} means "a pending request needs a decision". A request that is answered
 * without a human (a host already granted to the sandbox) is only ever reported as decided, so
 * nobody is notified about a question that was never open.
 */
public interface NetworkAccessListener {

    void requestCreated(NetworkAccessRequestView request);

    void requestDecided(NetworkAccessRequestView request);
}
