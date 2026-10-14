package run.fabian.daemon.sandboxidentity.application;

import run.fabian.daemon.sandboxidentity.application.port.SbxCustomSecretPort;

/**
 * Registers the placeholder-substitution secret that lets a sandbox's MCP requests carry a
 * proxy-verified identity header (see {@code SbxCustomSecretPort}). Must be called for a
 * sandbox's name *before* that sandbox's kit-install step runs - the env var it declares only
 * exists once this registration has happened.
 */
public final class SandboxIdentityService {

    private final SbxCustomSecretPort port;
    private final String targetHost;
    private final String envVarName;
    private final String placeholder;

    public SandboxIdentityService(SbxCustomSecretPort port, String targetHost, String envVarName, String placeholder) {
        this.port = port;
        this.targetHost = targetHost;
        this.envVarName = envVarName;
        this.placeholder = placeholder;
    }

    public SandboxRegistrationResult register(String sandboxName) {
        SbxCustomSecretPort.Result result =
                port.registerPlaceholderSecret(sandboxName, targetHost, envVarName, placeholder, sandboxName);
        return new SandboxRegistrationResult(sandboxName, result.applied(), result.output());
    }
}
