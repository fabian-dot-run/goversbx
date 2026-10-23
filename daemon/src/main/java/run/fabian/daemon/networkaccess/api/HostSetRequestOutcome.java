package run.fabian.daemon.networkaccess.api;

/**
 * What {@link NetworkAccessApi#createHostSetRequest} did: created a new pending request for the
 * hosts not yet granted, found one still pending for the same sandbox and source, or had nothing
 * left to ask for because every host is already granted to the sandbox.
 */
public record HostSetRequestOutcome(Kind kind, NetworkAccessRequestView request) {

    public enum Kind {
        CREATED,
        ALREADY_PENDING,
        ALL_GRANTED
    }

    public static HostSetRequestOutcome created(NetworkAccessRequestView request) {
        return new HostSetRequestOutcome(Kind.CREATED, request);
    }

    public static HostSetRequestOutcome alreadyPending(NetworkAccessRequestView request) {
        return new HostSetRequestOutcome(Kind.ALREADY_PENDING, request);
    }

    public static HostSetRequestOutcome allGranted() {
        return new HostSetRequestOutcome(Kind.ALL_GRANTED, null);
    }
}
