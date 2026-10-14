package run.fabian.daemon.sandboxidentity.application.port;

/**
 * Driven port for registering a per-sandbox placeholder-substitution secret via
 * {@code sbx secret set-custom}. The sandbox only ever sees the placeholder (via the declared
 * env var); the sbx proxy rewrites it to {@code value} in every outbound HTTP header on requests
 * to {@code targetHost} - the sandbox itself cannot forge or read the real value.
 */
public interface SbxCustomSecretPort {

    Result registerPlaceholderSecret(
            String sandboxName, String targetHost, String envVarName, String placeholder, String value);

    record Result(boolean applied, String output) {
    }
}
