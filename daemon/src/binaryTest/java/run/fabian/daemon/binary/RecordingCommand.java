package run.fabian.daemon.binary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * A scriptable stand-in for an external command (e.g. the browser opener): a POSIX sh script that
 * appends each invocation's argv to a log file, so a test can assert exactly what the daemon ran.
 */
final class RecordingCommand {

    private final Path script;
    private final Path log;

    private RecordingCommand(Path script, Path log) {
        this.script = script;
        this.log = log;
    }

    static RecordingCommand create() {
        try {
            Path dir = Files.createTempDirectory("recording-cmd");
            Path log = dir.resolve("invocations.log");
            Path script = dir.resolve("cmd");
            Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"" + log + "\"\n");
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
            return new RecordingCommand(script, log);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String path() {
        return script.toString();
    }

    List<String> invocations() {
        try {
            return Files.exists(log) ? Files.readAllLines(log) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
