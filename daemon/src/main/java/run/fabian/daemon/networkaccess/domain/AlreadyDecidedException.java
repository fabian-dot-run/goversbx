package run.fabian.daemon.networkaccess.domain;

/**
 * Thrown when approve()/deny() is called on a {@link NetworkAccessRequest} that has already
 * been decided. The invariant lives on the entity itself rather than being re-checked by callers.
 */
public final class AlreadyDecidedException extends RuntimeException {

    public AlreadyDecidedException(String message) {
        super(message);
    }
}
