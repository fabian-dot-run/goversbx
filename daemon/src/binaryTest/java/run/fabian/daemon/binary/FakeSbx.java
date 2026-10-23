package run.fabian.daemon.binary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A scriptable stand-in for the sbx CLI: a POSIX sh script that records every invocation's argv and
 * answers from files in its directory. Lets tests assert the exact commands the daemon runs and
 * inject failures, which the bash stub in scripts/testing/fake-sbx can't.
 */
final class FakeSbx {

    private static final String SCRIPT = """
            #!/bin/sh
            d="$(cd "$(dirname "$0")" && pwd)"
            printf '%s\\n' "$*" >> "$d/calls.log"
            case "$1" in ls|secret|policy) cmd="$1" ;; *) cmd=other ;; esac
            if [ -f "$d/$cmd.failfor" ] && printf '%s' " $* " | tr ',' ' ' | grep -q -F " $(cat "$d/$cmd.failfor") "; then
              echo "fake sbx: refused $(cat "$d/$cmd.failfor")"; exit 1
            fi
            [ -f "$d/$cmd.out" ] && cat "$d/$cmd.out"
            exit "$(cat "$d/$cmd.exit" 2>/dev/null || echo 0)"
            """;

    private final Path dir;

    private FakeSbx(Path dir) {
        this.dir = dir;
    }

    static FakeSbx create() {
        try {
            Path dir = Files.createTempDirectory("fake-sbx");
            Path script = dir.resolve("sbx");
            Files.writeString(script, SCRIPT);
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            FakeSbx sbx = new FakeSbx(dir);
            sbx.respond("ls", 0, "[]");
            sbx.respond("secret", 0, "secret set");
            sbx.respond("policy", 0, "Added rule (fake sbx)");
            return sbx;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path binary() {
        return dir.resolve("sbx");
    }

    /** `sbx ls --format json` answers with these sandboxes, all running. */
    FakeSbx running(String... names) {
        return lsJson(Arrays.stream(names)
                .map(n -> "{\"name\":\"%s\",\"id\":\"id-%s\",\"status\":\"running\",\"agent\":\"claude\"}".formatted(n, n))
                .collect(Collectors.joining(",", "[", "]")));
    }

    FakeSbx lsJson(String json) {
        return respond("ls", 0, json);
    }

    /** `sbx policy ...` fails (exit 1) whenever its argv names this host, alone or inside a comma-separated list. */
    FakeSbx failPolicyFor(String host) {
        try {
            Files.writeString(dir.resolve("policy.failfor"), host);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** `command` is the first sbx argument: ls, secret or policy. */
    FakeSbx respond(String command, int exitCode, String output) {
        try {
            Files.writeString(dir.resolve(command + ".out"), output);
            Files.writeString(dir.resolve(command + ".exit"), Integer.toString(exitCode));
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The identity token (the {@code --value}) from the most recent
     * {@code secret set-custom --sandbox <name> ...} call, or null if there was none. Lets a test
     * learn the token the daemon minted for a sandbox - the same value the sbx proxy would then
     * substitute into that sandbox's identity header.
     */
    String tokenFor(String sandboxName) {
        String marker = "secret set-custom --sandbox " + sandboxName + " ";
        String token = null;
        for (String call : calls()) {
            if (call.startsWith(marker)) {
                int i = call.indexOf("--value ");
                if (i >= 0) {
                    token = call.substring(i + "--value ".length()).trim();
                }
            }
        }
        return token;
    }

    /** Every invocation so far, as its space-joined argv (without the binary itself). */
    List<String> calls() {
        try {
            Path log = dir.resolve("calls.log");
            return Files.exists(log) ? Files.readAllLines(log) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
