package run.fabian.daemon.sandboxidentity.adapter.sbx;

import run.fabian.daemon.sandboxidentity.application.port.SbxCustomSecretPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public final class SbxCliCustomSecretAdapter implements SbxCustomSecretPort {

    private static final Logger log = LoggerFactory.getLogger(SbxCliCustomSecretAdapter.class);

    private final String sbxBinary;
    private final boolean dryRun;

    public SbxCliCustomSecretAdapter(String sbxBinary, boolean dryRun) {
        this.sbxBinary = sbxBinary;
        this.dryRun = dryRun;
    }

    @Override
    public Result registerPlaceholderSecret(
            String sandboxName, String targetHost, String envVarName, String placeholder, String value) {
        List<String> command = List.of(
                sbxBinary, "secret", "set-custom",
                "--sandbox", sandboxName,
                "--host", targetHost,
                "--env", envVarName,
                "--placeholder", placeholder,
                "--value", value);

        if (dryRun) {
            String fakeOutput = "dry-run: would execute: " + String.join(" ", command);
            log.info(fakeOutput);
            return new Result(true, fakeOutput);
        }

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            int exitCode = process.waitFor();
            boolean applied = exitCode == 0;
            if (applied) {
                log.info("sbx secret set-custom registered sandbox={} target={}", sandboxName, targetHost);
            } else {
                log.warn("sbx secret set-custom failed (exit {}) for sandbox={}: {}", exitCode, sandboxName, output);
            }
            return new Result(applied, output);
        } catch (Exception e) {
            log.error("Failed to invoke sbx secret set-custom for sandbox={}", sandboxName, e);
            return new Result(false, "exception: " + e.getMessage());
        }
    }
}
