package run.fabian.daemon.sandboxidentity.application;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.sandboxidentity.application.port.SbxCustomSecretPort;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxIdentityServiceTest {

    private record Call(String sandboxName, String targetHost, String envVarName, String placeholder, String value) {
    }

    @Test
    void registersWithSandboxNameAsTheSubstitutedValue() {
        AtomicReference<Call> lastCall = new AtomicReference<>();
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) -> {
            lastCall.set(new Call(sandboxName, targetHost, envVarName, placeholder, value));
            return new SbxCustomSecretPort.Result(true, "ok");
        };

        SandboxIdentityService service = new SandboxIdentityService(
                port, "daemon.internal", "GOVERSBX_SANDBOX_TOKEN", "the-placeholder");

        SandboxRegistrationResult result = service.register("sandbox-1");

        assertEquals("sandbox-1", result.sandboxName());
        assertTrue(result.applied());

        Call call = lastCall.get();
        assertEquals("sandbox-1", call.sandboxName());
        assertEquals("daemon.internal", call.targetHost());
        assertEquals("GOVERSBX_SANDBOX_TOKEN", call.envVarName());
        assertEquals("the-placeholder", call.placeholder());
        assertEquals("sandbox-1", call.value());
    }

    @Test
    void surfacesFailureFromThePort() {
        SbxCustomSecretPort port = (sandboxName, targetHost, envVarName, placeholder, value) ->
                new SbxCustomSecretPort.Result(false, "sbx: command not found");

        SandboxIdentityService service = new SandboxIdentityService(port, "daemon.internal", "VAR", "placeholder");

        SandboxRegistrationResult result = service.register("sandbox-2");

        assertEquals(false, result.applied());
        assertEquals("sbx: command not found", result.output());
    }
}
