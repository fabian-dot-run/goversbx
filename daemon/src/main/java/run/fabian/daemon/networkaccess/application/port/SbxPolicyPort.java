package run.fabian.daemon.networkaccess.application.port;

/** Driven port for actually granting network access to a sandbox via {@code sbx}. */
public interface SbxPolicyPort {

    Result allowNetwork(String sandboxName, String host);

    record Result(boolean applied, String output) {
    }
}
