package run.fabian.daemon.sandboxidentity.application;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.sandboxidentity.application.port.SbxCustomSecretPort;
import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxIdentityServiceTest {

    private record Call(String sandboxName, String targetHost, String envVarName, String placeholder, String value) {
    }

    private static SbxSandboxListPort listing(String... names) {
        return () -> List.of(names);
    }

    @Test
    void registersWithSandboxNameAsTheSubstitutedValue() {
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
        assertEquals("sandbox-1", call.value());
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
    void recognizesTheUnsubstitutedPlaceholder() {
        SandboxIdentityService service = new SandboxIdentityService(
                (n, t, e, p, v) -> new SbxCustomSecretPort.Result(true, "ok"),
                listing(), "localhost", "VAR", "the-placeholder");

        assertTrue(service.isUnsubstitutedPlaceholder("the-placeholder"));
        assertFalse(service.isUnsubstitutedPlaceholder("some-real-sandbox-name"));
        assertFalse(service.isUnsubstitutedPlaceholder("unknown-sandbox"));
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
