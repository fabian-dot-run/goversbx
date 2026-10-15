package run.fabian.daemon.sandboxidentity.application;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.sandboxidentity.application.port.SbxCustomSecretPort;
import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort;
import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort.RunningSandbox;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxIdentityServiceTest {

    private record Call(String sandboxName, String targetHost, String envVarName, String placeholder, String value) {
    }

    private static SbxSandboxListPort listing(String... names) {
        List<RunningSandbox> sandboxes = java.util.Arrays.stream(names)
                .map(name -> new RunningSandbox(name, "id-of-" + name))
                .toList();
        return () -> sandboxes;
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
        AtomicReference<List<RunningSandbox>> running =
                new AtomicReference<>(List.of(new RunningSandbox("test-goversbx", "first-id")));

        SandboxIdentityService service = new SandboxIdentityService(
                port, running::get, "localhost", "VAR", "placeholder");

        service.registerAnyUnregisteredSandboxes();
        running.set(List.of(new RunningSandbox("test-goversbx", "second-id")));
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
}
