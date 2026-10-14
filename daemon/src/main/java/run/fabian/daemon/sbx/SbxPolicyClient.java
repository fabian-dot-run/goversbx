package run.fabian.daemon.sbx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public final class SbxPolicyClient {

    private static final Logger log = LoggerFactory.getLogger(SbxPolicyClient.class);

    public record Result(boolean applied, String output) {
    }

    private final String sbxBinary;
    private final boolean dryRun;

    public SbxPolicyClient(String sbxBinary, boolean dryRun) {
        this.sbxBinary = sbxBinary;
        this.dryRun = dryRun;
    }

    public Result allowNetwork(String sandboxName, String host) {
        var command = new java.util.ArrayList<>(
                java.util.List.of(sbxBinary, "policy", "allow", "network", host, "--sandbox", sandboxName));

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
            if (!applied) {
                log.warn("sbx policy allow failed (exit {}) for sandbox={} host={}: {}", exitCode, sandboxName, host, output);
            }
            return new Result(applied, output);
        } catch (Exception e) {
            log.error("Failed to invoke sbx for sandbox={} host={}", sandboxName, host, e);
            return new Result(false, "exception: " + e.getMessage());
        }
    }
}
