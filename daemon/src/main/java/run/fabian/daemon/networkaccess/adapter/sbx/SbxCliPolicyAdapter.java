package run.fabian.daemon.networkaccess.adapter.sbx;

import run.fabian.daemon.networkaccess.application.port.SbxPolicyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code sbx policy allow network <host>[,<host>...] --sandbox <name>}: one call per request,
 * however many hosts it has. Verified on sbx 0.49.1 (e2e, 2026-10-23): the comma-separated list
 * creates one rule per host, and allowing an already allowed host answers "Already covered" with
 * exit 0, so a re-request after a daemon restart counts as applied. sbx prints one line per host,
 * which becomes that host's output. If the batch call fails, every host is applied on its own so
 * the per-host outcome the approver sees is exact (which host sbx refused), not a guess.
 */
public final class SbxCliPolicyAdapter implements SbxPolicyPort {

    private static final Logger log = LoggerFactory.getLogger(SbxCliPolicyAdapter.class);

    private final String sbxBinary;
    private final boolean dryRun;

    public SbxCliPolicyAdapter(String sbxBinary, boolean dryRun) {
        this.sbxBinary = sbxBinary;
        this.dryRun = dryRun;
    }

    @Override
    public Result allowNetwork(String sandboxName, String host) {
        return allowNetwork(sandboxName, List.of(host)).get(0);
    }

    @Override
    public List<Result> allowNetwork(String sandboxName, List<String> hosts) {
        Invocation batch = run(sandboxName, hosts);
        if (batch.applied()) {
            List<Result> results = new ArrayList<>(hosts.size());
            for (String host : hosts) {
                results.add(new Result(true, hosts.size() == 1 ? batch.output() : lineFor(host, batch.output())));
            }
            return results;
        }
        if (hosts.size() == 1) {
            return List.of(new Result(false, batch.output()));
        }
        log.warn("Batch sbx policy allow for {} hosts failed for sandbox={}; applying each host on its own", hosts.size(), sandboxName);
        List<Result> results = new ArrayList<>(hosts.size());
        for (String host : hosts) {
            Invocation single = run(sandboxName, List.of(host));
            results.add(new Result(single.applied(), single.output()));
        }
        return results;
    }

    private record Invocation(boolean applied, String output) {
    }

    private Invocation run(String sandboxName, List<String> hosts) {
        String resources = String.join(",", hosts);
        List<String> command = List.of(sbxBinary, "policy", "allow", "network", resources, "--sandbox", sandboxName);

        if (dryRun) {
            String fakeOutput = "dry-run: would execute: " + String.join(" ", command);
            log.info(fakeOutput);
            return new Invocation(true, fakeOutput);
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
                log.info("sbx policy allow network {} --sandbox {} succeeded", resources, sandboxName);
            } else {
                log.warn("sbx policy allow failed (exit {}) for sandbox={} host={}: {}", exitCode, sandboxName, resources, output);
            }
            return new Invocation(applied, output);
        } catch (Exception e) {
            log.error("Failed to invoke sbx for sandbox={} host={}", sandboxName, resources, e);
            return new Invocation(false, "exception: " + e.getMessage());
        }
    }

    /** sbx's line about this host ("...: host [tcp]"), or the whole output if there is no such line. */
    static String lineFor(String host, String output) {
        for (String line : output.split("\n")) {
            if (line.contains(": " + host + " [") || line.endsWith(": " + host)) {
                return line.strip();
            }
        }
        return output;
    }
}
