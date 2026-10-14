package run.fabian.daemon.networkaccess.application.port;

import run.fabian.daemon.networkaccess.domain.NetworkAccessRequest;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven port for persisting and awaiting decisions on {@link NetworkAccessRequest}s. Exposes
 * {@link #awaitDecision} as a first-class capability (rather than leaving polling to the
 * application layer) since efficiently supporting a blocking wait is an implementation detail of
 * the storage mechanism - the in-memory adapter does it via a CompletableFuture per request;
 * a different adapter could use its own notification mechanism while satisfying the same contract.
 */
public interface RequestRepository {

    void add(NetworkAccessRequest request);

    Optional<NetworkAccessRequest> findById(UUID id);

    List<NetworkAccessRequest> findAll();

    /** Blocks until the request is decided or the timeout elapses; returns its current state either way. */
    NetworkAccessRequest awaitDecision(UUID id, Duration timeout);

    /** Wakes up anyone blocked in {@link #awaitDecision} for this request. */
    void notifyDecided(UUID id);
}
