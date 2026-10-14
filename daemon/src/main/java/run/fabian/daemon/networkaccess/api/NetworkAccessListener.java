package run.fabian.daemon.networkaccess.api;

/**
 * Lifecycle events of network-access requests, for anything that has to tell a human about them
 * (the admin UI's live stream today, other notification channels later). Called synchronously on
 * the thread that changed the request - implementations must be quick and must not throw.
 */
public interface NetworkAccessListener {

    void requestCreated(NetworkAccessRequestView request);

    void requestDecided(NetworkAccessRequestView request);
}
