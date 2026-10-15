package run.fabian.daemon.networkaccess.domain;

/**
 * Thrown by the domain when a requested host is not a well-formed, safe hostname (see
 * {@link HostValidation}). A domain invariant: no {@code NetworkAccessRequest} is ever created for an
 * invalid host, whatever the caller.
 */
public final class InvalidHostException extends RuntimeException {

    public InvalidHostException(String message) {
        super(message);
    }
}
