package run.fabian.daemon.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The real sbx CLI. Every invocation is echoed to stdout (visible in the Gradle report) so a failed
 * run shows exactly what was asked of sbx and what it answered.
 */
final class Sbx {

    /** {@code output} is stdout and stderr interleaved (for messages); {@code stdout} alone is what the daemon parses. */
    record Result(int exit, String output, String stdout) {
        boolean ok() {
            return exit == 0;
        }

        /** Last line, trimmed - `sbx exec` output after the sandbox's own progress noise. */
        String lastLine() {
            String[] lines = output.strip().split("\n");
            return lines[lines.length - 1].strip();
        }
    }

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    final Path binary;

    Sbx(Path binary) {
        this.binary = binary;
    }

    Result run(String... args) {
        return run(DEFAULT_TIMEOUT, List.of(args));
    }

    Result run(Duration timeout, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(args);
        System.out.println("$ sbx " + String.join(" ", args));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectInput(new File("/dev/null"))
                    .start();
            // stderr is drained on its own thread: sbx prints warnings there (e.g. "docker hub refresh
            // lock held by another process") and they must not end up in what we parse as JSON.
            var stderr = new java.util.concurrent.CompletableFuture<byte[]>();
            Thread.ofVirtual().start(() -> {
                try (var err = process.getErrorStream()) {
                    stderr.complete(err.readAllBytes());
                } catch (IOException e) {
                    stderr.complete(new byte[0]);
                }
            });
            byte[] bytes;
            try (var in = process.getInputStream()) {
                bytes = in.readAllBytes();            // sbx closes stdout when it exits
            }
            String out = new String(bytes, StandardCharsets.UTF_8);
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(124, out + "\n<timed out after " + timeout + ">", out);
            }
            String err = new String(stderr.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            String output = err.isBlank() ? out : out + (out.endsWith("\n") || out.isEmpty() ? "" : "\n") + err;
            System.out.println(indent(output.isBlank() ? "<no output> (exit " + process.exitValue() + ")" : output.strip()
                    + (process.exitValue() == 0 ? "" : "\n(exit " + process.exitValue() + ")")));
            return new Result(process.exitValue(), output, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("reading sbx stderr", e);
        }
    }

    String version() {
        return run("version").output().strip();
    }

    /** `sbx ls --format json`, or a clear failure (typically "not signed in to Docker"). */
    JsonNode lsJson() {
        Result result = run("ls", "--format", "json");
        if (!result.ok()) {
            throw new IllegalStateException("sbx ls failed: " + result.output().strip());
        }
        try {
            // stdout only, like SbxCliSandboxListAdapter: a warning on stderr is fine, one on stdout is not.
            return JSON.readTree(result.stdout());
        } catch (IOException e) {
            throw new IllegalStateException("sbx ls --format json did not print JSON on stdout (the daemon would fail to parse it): "
                    + result.stdout(), e);
        }
    }

    Optional<JsonNode> sandbox(String name) {
        for (JsonNode row : lsJson()) {
            if (name.equals(row.path("name").asText())) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /** `sbx exec -e K=V ... NAME COMMAND...` (stdin closed, like a script would). */
    Result exec(String sandbox, Map<String, String> env, Duration timeout, String... command) {
        List<String> args = new ArrayList<>(List.of("exec"));
        env.forEach((k, v) -> {
            args.add("-e");
            args.add(k + "=" + v);
        });
        args.add(sandbox);
        args.addAll(List.of(command));
        return run(timeout, args);
    }

    Result rmForce(String sandbox) {
        return run("rm", "--force", sandbox);
    }

    Result policyAllowNetwork(String host, String sandbox) {
        return run("policy", "allow", "network", host, "--sandbox", sandbox);
    }

    Result policyLsJson(String sandbox) {
        return run("policy", "ls", sandbox, "--json");
    }

    Result kitValidate(Path kit) {
        return run("kit", "validate", kit.toString());
    }

    Result help(String... command) {
        List<String> args = new ArrayList<>(List.of(command));
        args.add("--help");
        return run(Duration.ofSeconds(30), args);
    }

    private static String indent(String text) {
        return "    " + text.replace("\n", "\n    ");
    }
}
