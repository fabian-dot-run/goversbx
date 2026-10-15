package run.fabian.daemon.sandboxidentity.application;

import run.fabian.daemon.sandboxidentity.api.SandboxIdentityApi;
import run.fabian.daemon.sandboxidentity.application.port.SbxCustomSecretPort;
import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort;
import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort.RunningSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Registers the placeholder-substitution secret that lets a sandbox's MCP requests carry a
 * proxy-verified identity (see {@code SbxCustomSecretPort}).
 *
 * <p><strong>The substituted value is a per-sandbox secret token, not the sandbox name.</strong>
 * This is what makes the identity unforgeable: the daemon mints a random token per sandbox, hands
 * it to the sbx proxy as the {@code set-custom --value}, and keeps a private token &rarr; name map.
 * A sandbox only ever sees the placeholder; the proxy rewrites that placeholder to the sandbox's
 * own token on the way out. A rogue agent that supplies any other header value - a guessed name,
 * another sandbox's name, or the placeholder itself - can never produce a victim's token, so
 * {@link #resolveSandboxName(String)} either maps it to the caller's own sandbox or rejects it.
 * (Using the name as the value would not work: the daemon could not tell a proxy-substituted name
 * from one a sandbox typed in by hand, since names are public.)
 *
 * <p>Registration can happen at any time relative to a sandbox's lifecycle - sbx applies custom
 * secrets to already-running sandboxes without a restart - so this is driven reactively by
 * {@link #registerAnyUnregisteredSandboxes()} (called by the {@code mcp} context when it sees a
 * value it can't resolve) as well as proactively via {@link #register(String)} (the admin
 * endpoint). No persistence is needed across a daemon restart: the token in sbx outlives the
 * daemon, the in-memory map does not, so after a restart the sandbox's placeholder is substituted
 * to a token the daemon no longer knows - which resolves to empty, triggers one re-registration
 * (minting a fresh token that overwrites the stale one live), and self-heals on the next retry,
 * exactly like a brand-new sandbox's first call.
 */
public final class SandboxIdentityService implements SandboxIdentityApi {

    private static final Logger log = LoggerFactory.getLogger(SandboxIdentityService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

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
    /**
     * Minted identity tokens &rarr; the sandbox name each was registered for. The proxy substitutes a
     * sandbox's placeholder to its token, so an incoming token resolves back to its name here. Old
     * tokens (after a re-registration) are left in place - each still only ever maps to its own
     * sandbox, so they stay safe; the map grows slowly over a daemon's lifetime, acceptable for the
     * in-memory MVP.
     */
    private final Map<String, String> tokenToName = new ConcurrentHashMap<>();

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
    public Optional<String> resolveSandboxName(String identityHeaderValue) {
        if (identityHeaderValue == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokenToName.get(identityHeaderValue));
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
     * Mints a fresh token for {@code sandboxName}, registers it as the substitution secret, and - on
     * success - records the token so {@link #resolveSandboxName(String)} recognizes it. Called both
     * by the reactive pass and by the admin endpoint; doesn't touch the {@code registered} set
     * (the admin path doesn't know the incarnation id), so the next reactive pass may register once
     * more, which is harmless.
     */
    public SandboxRegistrationResult register(String sandboxName) {
        String token = newToken();
        SbxCustomSecretPort.Result result =
                secretPort.registerPlaceholderSecret(sandboxName, targetHost, envVarName, placeholder, token);
        if (result.applied()) {
            tokenToName.put(token, sandboxName);
        }
        return new SandboxRegistrationResult(sandboxName, result.applied(), result.output());
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "gvsbx-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
