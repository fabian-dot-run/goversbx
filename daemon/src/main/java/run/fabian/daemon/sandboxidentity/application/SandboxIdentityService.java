package run.fabian.daemon.sandboxidentity.application;

import run.fabian.daemon.sandboxidentity.api.SandboxIdentityApi;
import run.fabian.daemon.sandboxidentity.application.port.SbxCustomSecretPort;
import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort;
import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort.RunningSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Registers the placeholder-substitution secret that lets a sandbox's MCP requests carry a
 * proxy-verified identity header (see {@code SbxCustomSecretPort}). Registration can happen at
 * any time relative to a sandbox's lifecycle - sbx applies custom secrets to already-running
 * sandboxes without a restart - so this is driven reactively by {@link #registerAnyUnregisteredSandboxes()}
 * (called by the {@code mcp} context when it sees an unsubstituted placeholder) as well as
 * proactively via {@link #register(String)} (the admin HTTP endpoint).
 */
public final class SandboxIdentityService implements SandboxIdentityApi {

    private static final Logger log = LoggerFactory.getLogger(SandboxIdentityService.class);

    private final SbxCustomSecretPort secretPort;
    private final SbxSandboxListPort listPort;
    private final String targetHost;
    private final String envVarName;
    private final String placeholder;
    private final Pattern sandboxNamePattern;
    /**
     * Sandbox incarnations ({@code name + id} from {@code sbx ls}) registered by this process. Keyed
     * by id, not just name: removing a sandbox drops its sandbox-scoped secret, and a sandbox created
     * again under the same name must be registered again - keyed by name alone, the daemon would
     * answer "please retry" to it forever (seen on a real host).
     */
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    public SandboxIdentityService(
            SbxCustomSecretPort secretPort, SbxSandboxListPort listPort,
            String targetHost, String envVarName, String placeholder) {
        this(secretPort, listPort, targetHost, envVarName, placeholder, Pattern.compile(".*"));
    }

    /**
     * @param sandboxNamePattern only running sandboxes whose full name matches are registered by
     *     {@link #registerAnyUnregisteredSandboxes()} - lets a test run (or a shared host) avoid
     *     touching unrelated sandboxes. Explicit {@link #register(String)} calls are not filtered.
     */
    public SandboxIdentityService(
            SbxCustomSecretPort secretPort, SbxSandboxListPort listPort,
            String targetHost, String envVarName, String placeholder, Pattern sandboxNamePattern) {
        this.secretPort = secretPort;
        this.listPort = listPort;
        this.targetHost = targetHost;
        this.envVarName = envVarName;
        this.placeholder = placeholder;
        this.sandboxNamePattern = sandboxNamePattern;
    }

    @Override
    public boolean isUnsubstitutedPlaceholder(String sandboxNameHeaderValue) {
        return placeholder.equals(sandboxNameHeaderValue);
    }

    @Override
    public void registerAnyUnregisteredSandboxes() {
        for (RunningSandbox sandbox : listPort.listRunningSandboxes()) {
            String key = sandbox.name() + "@" + sandbox.id();
            if (registered.contains(key) || !sandboxNamePattern.matcher(sandbox.name()).matches()) {
                continue;
            }
            SandboxRegistrationResult result = register(sandbox.name());
            if (result.applied()) {
                registered.add(key);
            } else {
                log.warn("Failed to register sandbox identity for {}: {}", sandbox.name(), result.output());
            }
        }
    }

    /**
     * Registers by name only (admin endpoint). Doesn't mark the sandbox as registered, because the
     * incarnation (id) isn't known here; the next reactive pass may register it once more, which is
     * harmless.
     */
    public SandboxRegistrationResult register(String sandboxName) {
        SbxCustomSecretPort.Result result =
                secretPort.registerPlaceholderSecret(sandboxName, targetHost, envVarName, placeholder, sandboxName);
        return new SandboxRegistrationResult(sandboxName, result.applied(), result.output());
    }
}
