package run.fabian.daemon.networkaccess.api;

/** The outcome of applying one host of an approved request to sbx. */
public record HostGrant(String host, boolean applied, String output) {
}
