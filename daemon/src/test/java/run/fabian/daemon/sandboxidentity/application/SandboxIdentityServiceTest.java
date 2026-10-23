package run.fabian.daemon.sandboxidentity.application;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.sandboxidentity.application.port.SandboxRegistrationStore;
import run.fabian.daemon.sandboxidentity.application.port.SandboxRegistrationStore.RegistrationState;
import run.fabian.daemon.sandboxidentity.application.port.SbxCustomSecretPort;
import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort;
import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort.ListedSandbox;
import run.fabian.daemon.sandboxidentity.api.RegisteredSandbox;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxIdentityServiceTest {

    private record Call(String sandboxName, String targetHost, String envVarName, String placeholder, String value) {
    }

    /** The simplest durable store: what the last save said. Stands in for the file in the state dir. */
    private static final class MemoryStore implements SandboxRegistrationStore {
        RegistrationState saved = RegistrationState.empty();
        int saves;

        @Override
        public RegistrationState load() {
            return saved;
        }

        @Override
        public void save(RegistrationState state) {
            saved = state;
            saves++;
        }
    }

    private static SandboxIdentityService withStore(SbxCustomSecretPort port, SbxSandboxListPort listing, MemoryStore store) {
        return new SandboxIdentityService(port, listing, store, "localhost", "VAR", "placeholder", java.util.regex.Pattern.compile(".*"));
    }

    private static SbxSandboxListPort listing(String... names) {
        List<ListedSandbox> sandboxes = java.util.Arrays.stream(names)
                .map(name -> new ListedSandbox(name, "id-of-" + name))
                .toList();
        return () -> sandboxes;
    }

    private static SbxCustomSecretPort acceptingPort() {
        return (n, t, e, p, value) -> new SbxCustomSecretPort.Result(true, "ok");
    }

    @Test
    void registersWithASecretTokenAsTheSubstitutedValue() {
        AtomicReference<Call> lastCall = new AtomicReference<>();
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) -> {
            lastCall.set(new Call(sandboxName, targetHost, envVarName, placeholder, value));
            return new SbxCustomSecretPort.Result(true, "ok");
        };

        SandboxIdentityService service = new SandboxIdentityService(
                port, listing(), "localhost", "GOVERSBX_SANDBOX_TOKEN", "the-placeholder");

        SandboxRegistrationResult result = service.register("sandbox-1");

        assertEquals("sandbox-1", result.sandboxName());
        assertTrue(result.applied());

        Call call = lastCall.get();
        assertEquals("sandbox-1", call.sandboxName());
        assertEquals("localhost", call.targetHost());
        assertEquals("GOVERSBX_SANDBOX_TOKEN", call.envVarName());
        assertEquals("the-placeholder", call.placeholder());
        // The substituted value is a secret token, not the sandbox name (F1): a value a sandbox could
        // supply itself must never resolve to an identity. The token resolves back to the name.
        assertNotEquals("sandbox-1", call.value());
        assertTrue(call.value().startsWith("gvsbx-"), call.value());
        assertEquals(Optional.of("sandbox-1"), service.resolveSandboxName(call.value()));
    }

    @Test
    void surfacesFailureFromThePort() {
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) ->
                new SbxCustomSecretPort.Result(false, "sbx: command not found");

        SandboxIdentityService service = new SandboxIdentityService(port, listing(), "localhost", "VAR", "placeholder");

        SandboxRegistrationResult result = service.register("sandbox-2");

        assertEquals(false, result.applied());
        assertEquals("sbx: command not found", result.output());
    }

    @Test
    void resolvesOnlyMintedTokensNotNamesOrThePlaceholder() {
        AtomicReference<String> mintedToken = new AtomicReference<>();
        SbxCustomSecretPort port = (n, t, e, p, value) -> {
            mintedToken.set(value);
            return new SbxCustomSecretPort.Result(true, "ok");
        };
        SandboxIdentityService service = new SandboxIdentityService(
                port, listing(), "localhost", "VAR", "the-placeholder");

        service.register("real-sandbox");

        // Only the minted token resolves to the sandbox.
        assertEquals(Optional.of("real-sandbox"), service.resolveSandboxName(mintedToken.get()));
        // The raw placeholder does not - the sandbox isn't substituted yet.
        assertEquals(Optional.empty(), service.resolveSandboxName("the-placeholder"));
        // The sandbox's own name, supplied by hand, does not - this is the forgery F1 closes.
        assertEquals(Optional.empty(), service.resolveSandboxName("real-sandbox"));
        // Nor any other guessed value, nor a missing header.
        assertEquals(Optional.empty(), service.resolveSandboxName("some-other-sandbox"));
        assertEquals(Optional.empty(), service.resolveSandboxName(null));
    }

    @Test
    void registersEveryRunningSandboxNotYetRegistered() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) -> {
            calls.add(new Call(sandboxName, targetHost, envVarName, placeholder, value));
            return new SbxCustomSecretPort.Result(true, "ok");
        };

        SandboxIdentityService service = new SandboxIdentityService(
                port, listing("sandbox-a", "sandbox-b"), "localhost", "VAR", "placeholder");

        service.registerAnyUnregisteredSandboxes();

        assertEquals(2, calls.size());
        assertEquals("sandbox-a", calls.get(0).sandboxName());
        assertEquals("sandbox-b", calls.get(1).sandboxName());
    }

    @Test
    void onlyRegistersSandboxesMatchingThePattern() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) -> {
            calls.add(new Call(sandboxName, targetHost, envVarName, placeholder, value));
            return new SbxCustomSecretPort.Result(true, "ok");
        };

        SandboxIdentityService service = new SandboxIdentityService(
                port, listing("goversbx-e2e-1", "claude-goversbx", "goversbx-e2e-1-b"),
                "localhost", "VAR", "placeholder", java.util.regex.Pattern.compile("goversbx-e2e-1(-b)?"));

        service.registerAnyUnregisteredSandboxes();

        assertEquals(List.of("goversbx-e2e-1", "goversbx-e2e-1-b"),
                calls.stream().map(Call::sandboxName).toList());
    }

    @Test
    void doesNotReRegisterAnAlreadyRegisteredSandbox() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) -> {
            calls.add(new Call(sandboxName, targetHost, envVarName, placeholder, value));
            return new SbxCustomSecretPort.Result(true, "ok");
        };

        SandboxIdentityService service = new SandboxIdentityService(
                port, listing("sandbox-a"), "localhost", "VAR", "placeholder");

        service.registerAnyUnregisteredSandboxes();
        service.registerAnyUnregisteredSandboxes();

        assertEquals(1, calls.size());
    }

    @Test
    void reRegistersASandboxRecreatedUnderTheSameName() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) -> {
            calls.add(new Call(sandboxName, targetHost, envVarName, placeholder, value));
            return new SbxCustomSecretPort.Result(true, "ok");
        };
        AtomicReference<List<ListedSandbox>> running =
                new AtomicReference<>(List.of(new ListedSandbox("test-goversbx", "first-id")));

        SandboxIdentityService service = new SandboxIdentityService(
                port, running::get, "localhost", "VAR", "placeholder");

        service.registerAnyUnregisteredSandboxes();
        running.set(List.of(new ListedSandbox("test-goversbx", "second-id")));
        service.registerAnyUnregisteredSandboxes();
        service.registerAnyUnregisteredSandboxes();

        assertEquals(List.of("test-goversbx", "test-goversbx"), calls.stream().map(Call::sandboxName).toList());
    }

    @Test
    void retriesRegistrationNextTimeIfItFailed() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) -> {
            calls.add(new Call(sandboxName, targetHost, envVarName, placeholder, value));
            return new SbxCustomSecretPort.Result(false, "sbx temporarily unavailable");
        };

        SandboxIdentityService service = new SandboxIdentityService(
                port, listing("sandbox-a"), "localhost", "VAR", "placeholder");

        service.registerAnyUnregisteredSandboxes();
        service.registerAnyUnregisteredSandboxes();

        assertEquals(2, calls.size());
    }

    @Test
    void registrationsSurviveARestartThroughTheStore() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) -> {
            calls.add(new Call(sandboxName, targetHost, envVarName, placeholder, value));
            return new SbxCustomSecretPort.Result(true, "ok");
        };
        MemoryStore store = new MemoryStore();

        SandboxIdentityService firstRun = withStore(port, listing("durable"), store);
        firstRun.registerAnyUnregisteredSandboxes();
        String token = calls.get(0).value();
        assertEquals(Set.of("durable@id-of-durable"), store.saved.registeredIncarnations());
        assertEquals(Map.of(token, "durable"), store.saved.tokenToName());

        // "Daemon restart": a new service instance over the same store, nothing else carried over.
        SandboxIdentityService secondRun = withStore(port, listing("durable"), store);

        // The token the proxy still substitutes resolves right away - no "please retry" ...
        assertEquals(Optional.of("durable"), secondRun.resolveSandboxName(token));
        // ... and the incarnation counts as registered, so no second set-custom is issued.
        secondRun.registerAnyUnregisteredSandboxes();
        assertEquals(1, calls.size(), calls.toString());
    }

    @Test
    void aRecreatedSandboxIsRegisteredAgainAfterARestartAndTheOldTokenStillOnlyMapsToItsName() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) -> {
            calls.add(new Call(sandboxName, targetHost, envVarName, placeholder, value));
            return new SbxCustomSecretPort.Result(true, "ok");
        };
        MemoryStore store = new MemoryStore();
        withStore(port, () -> List.of(new ListedSandbox("sb", "first-id")), store).registerAnyUnregisteredSandboxes();

        SandboxIdentityService restarted = withStore(port, () -> List.of(new ListedSandbox("sb", "second-id")), store);
        restarted.registerAnyUnregisteredSandboxes();

        assertEquals(List.of("sb", "sb"), calls.stream().map(Call::sandboxName).toList());
        assertEquals(Set.of("sb@first-id", "sb@second-id"), store.saved.registeredIncarnations());
        assertEquals(Optional.of("sb"), restarted.resolveSandboxName(calls.get(0).value()));
        assertEquals(Optional.of("sb"), restarted.resolveSandboxName(calls.get(1).value()));
    }

    @Test
    void explicitRegistrationIsPersistedToo() {
        SbxCustomSecretPort port = (n, t, e, p, value) -> new SbxCustomSecretPort.Result(true, "ok");
        MemoryStore store = new MemoryStore();

        withStore(port, listing(), store).register("pre-warmed");

        assertEquals(1, store.saves);
        assertEquals(List.of("pre-warmed"), List.copyOf(store.saved.tokenToName().values()));
        assertTrue(store.saved.registeredIncarnations().isEmpty());   // the admin path knows no incarnation id
    }

    @Test
    void aFailedRegistrationIsNotPersisted() {
        SbxCustomSecretPort port = (n, t, e, p, value) -> new SbxCustomSecretPort.Result(false, "sbx down");
        MemoryStore store = new MemoryStore();

        SandboxIdentityService service = withStore(port, listing("sandbox-a"), store);
        service.registerAnyUnregisteredSandboxes();
        service.register("by-hand");

        assertEquals(0, store.saves);
        assertEquals(RegistrationState.empty(), store.saved);
    }

    @Test
    void aLostStoreFallsBackToReRegistration() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) -> {
            calls.add(new Call(sandboxName, targetHost, envVarName, placeholder, value));
            return new SbxCustomSecretPort.Result(true, "ok");
        };
        withStore(port, listing("sb"), new MemoryStore()).registerAnyUnregisteredSandboxes();
        String staleToken = calls.get(0).value();

        // The state file is gone: the stale token doesn't resolve, one re-registration self-heals it.
        SandboxIdentityService restarted = withStore(port, listing("sb"), new MemoryStore());
        assertFalse(restarted.resolveSandboxName(staleToken).isPresent());
        restarted.registerAnyUnregisteredSandboxes();
        assertEquals(2, calls.size());
        assertEquals(Optional.of("sb"), restarted.resolveSandboxName(calls.get(1).value()));
    }

    @Test
    void announcesEachNewIncarnationWithItsWorkspacesOnce() {
        List<RegisteredSandbox> events = new CopyOnWriteArrayList<>();
        ListedSandbox withWorkspace = new ListedSandbox("ws", "id-1", true, List.of("/home/me/project"));
        SandboxIdentityService service = new SandboxIdentityService(acceptingPort(), () -> List.of(withWorkspace),
                "localhost", "VAR", "placeholder");
        service.subscribe(events::add);

        service.registerAnyUnregisteredSandboxes();
        service.registerAnyUnregisteredSandboxes();

        assertEquals(List.of(new RegisteredSandbox("ws", "id-1", List.of("/home/me/project"))), events);
    }

    @Test
    void doesNotAnnounceAFailedRegistration() {
        List<RegisteredSandbox> events = new CopyOnWriteArrayList<>();
        SbxCustomSecretPort failing = (n, t, e, p, value) -> new SbxCustomSecretPort.Result(false, "sbx down");
        SandboxIdentityService service = new SandboxIdentityService(failing, listing("sb"), "localhost", "VAR", "placeholder");
        service.subscribe(events::add);

        service.registerAnyUnregisteredSandboxes();

        assertTrue(events.isEmpty());
    }

    @Test
    void aRecreatedSandboxIsAnnouncedAgainAsANewIncarnation() {
        List<RegisteredSandbox> events = new CopyOnWriteArrayList<>();
        AtomicReference<List<ListedSandbox>> running = new AtomicReference<>(List.of(new ListedSandbox("sb", "first")));
        SandboxIdentityService service = new SandboxIdentityService(acceptingPort(), running::get, "localhost", "VAR", "placeholder");
        service.subscribe(events::add);

        service.registerAnyUnregisteredSandboxes();
        running.set(List.of(new ListedSandbox("sb", "second")));
        service.registerAnyUnregisteredSandboxes();

        assertEquals(List.of("first", "second"), events.stream().map(RegisteredSandbox::id).toList());
    }

    @Test
    void explicitRegistrationAnnouncesTheIncarnationWhenSbxListsIt() {
        List<RegisteredSandbox> events = new CopyOnWriteArrayList<>();
        ListedSandbox listed = new ListedSandbox("launched", "id-9", false, List.of("/ws"));   // idle sandboxes stop; still listed
        SandboxIdentityService service = new SandboxIdentityService(acceptingPort(), () -> List.of(listed), "localhost", "VAR", "placeholder");
        service.subscribe(events::add);

        service.register("launched");
        service.register("launched");                     // same incarnation: a fresh token, no second announcement
        service.registerAnyUnregisteredSandboxes();       // already recorded: nothing to do

        assertEquals(List.of(new RegisteredSandbox("launched", "id-9", List.of("/ws"))), events);
    }

    @Test
    void explicitRegistrationOfAnUnlistedSandboxAnnouncesNothing() {
        List<RegisteredSandbox> events = new CopyOnWriteArrayList<>();
        SandboxIdentityService service = new SandboxIdentityService(acceptingPort(), listing(), "localhost", "VAR", "placeholder");
        service.subscribe(events::add);

        assertTrue(service.register("pre-warmed").applied());

        assertTrue(events.isEmpty());
    }

    @Test
    void aFailingListenerDoesNotBreakRegistration() {
        SandboxIdentityService service = new SandboxIdentityService(acceptingPort(), listing("sb"), "localhost", "VAR", "placeholder");
        service.subscribe(sandbox -> {
            throw new IllegalStateException("broken listener");
        });

        service.registerAnyUnregisteredSandboxes();

        assertTrue(service.findRegistered("sb").isPresent());
    }

    @Test
    void findRegisteredNeedsBothAnIdentityAndAListing() {
        ListedSandbox listed = new ListedSandbox("known", "id-k", true, List.of("/w1", "/w2"));
        AtomicReference<List<ListedSandbox>> ls = new AtomicReference<>(List.of(listed));
        SandboxIdentityService service = new SandboxIdentityService(acceptingPort(), ls::get, "localhost", "VAR", "placeholder");

        assertEquals(Optional.empty(), service.findRegistered("known"));          // listed but never registered
        service.registerAnyUnregisteredSandboxes();
        assertEquals(Optional.of(new RegisteredSandbox("known", "id-k", List.of("/w1", "/w2"))), service.findRegistered("known"));
        ls.set(List.of());
        assertEquals(Optional.empty(), service.findRegistered("known"));          // registered but removed since
        assertEquals(Optional.empty(), service.findRegistered("never"));
    }
}
