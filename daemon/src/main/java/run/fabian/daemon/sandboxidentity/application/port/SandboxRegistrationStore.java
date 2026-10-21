package run.fabian.daemon.sandboxidentity.application.port;

import java.util.Map;
import java.util.Set;

/**
 * Driven port that keeps the daemon's sandbox registrations across restarts: which sandbox
 * incarnations ({@code name@id} from {@code sbx ls}) already carry a substitution secret, and which
 * minted identity token belongs to which sandbox.
 *
 * <p>Without this, every daemon restart forgets every token while the secrets in sbx live on, so each
 * sandbox's first call after a restart is answered with "please retry" and costs one {@code sbx ls}
 * plus one {@code sbx secret set-custom} - and an agent that has already used up its retries gives
 * up. Registrations are meant to be as durable as the secrets they describe.
 *
 * <p>The tokens are secrets (a sandbox never sees its own); an implementation must keep the stored
 * state as private as the admin token.
 */
public interface SandboxRegistrationStore {

    /** The last saved state, or an empty one when nothing was saved yet (or it can't be read). Never throws. */
    RegistrationState load();

    /** Replaces the saved state with {@code state}. Best-effort; never throws - a failure is logged. */
    void save(RegistrationState state);

    /**
     * A snapshot of the registrations: {@code registeredIncarnations} holds {@code name@id} keys,
     * {@code tokenToName} maps each minted token to the sandbox it was registered for.
     */
    record RegistrationState(Set<String> registeredIncarnations, Map<String, String> tokenToName) {

        public RegistrationState {
            registeredIncarnations = Set.copyOf(registeredIncarnations);
            tokenToName = Map.copyOf(tokenToName);
        }

        public static RegistrationState empty() {
            return new RegistrationState(Set.of(), Map.of());
        }
    }
}
