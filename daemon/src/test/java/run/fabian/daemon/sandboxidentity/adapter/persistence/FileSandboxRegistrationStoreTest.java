package run.fabian.daemon.sandboxidentity.adapter.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import run.fabian.daemon.sandboxidentity.application.port.SandboxRegistrationStore.RegistrationState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSandboxRegistrationStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsRegistrationsThroughTheFile() throws IOException {
        Path file = dir.resolve("state").resolve("sandbox-registrations.json");   // parent doesn't exist yet
        FileSandboxRegistrationStore store = new FileSandboxRegistrationStore(file);
        RegistrationState state = new RegistrationState(
                Set.of("sb-a@id-1", "sb-b@id-2"),
                Map.of("gvsbx-token-a", "sb-a", "gvsbx-token-b", "sb-b"));

        store.save(state);

        assertTrue(Files.exists(file));
        assertEquals(state, new FileSandboxRegistrationStore(file).load());
        // The file is a plain, versioned JSON document (hand-inspectable, forward-compatible).
        String json = Files.readString(file);
        assertTrue(json.contains("\"version\" : 1"), json);
        assertTrue(json.contains("\"sb-a@id-1\""), json);
        assertTrue(json.contains("\"gvsbx-token-a\" : \"sb-a\""), json);
        // No leftover temp file from the atomic write.
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void savedFileIsOwnerOnly() throws IOException {
        Path file = dir.resolve("sandbox-registrations.json");
        new FileSandboxRegistrationStore(file).save(new RegistrationState(Set.of("x@1"), Map.of("gvsbx-t", "x")));

        try {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException e) {
            // non-POSIX filesystem: permissions are best-effort there
        }
    }

    @Test
    void missingFileLoadsAsEmpty() {
        assertEquals(RegistrationState.empty(), new FileSandboxRegistrationStore(dir.resolve("none.json")).load());
    }

    @Test
    void malformedOrForeignFileLoadsAsEmptyAndIsReplacedByTheNextSave() throws IOException {
        Path file = dir.resolve("sandbox-registrations.json");
        FileSandboxRegistrationStore store = new FileSandboxRegistrationStore(file);

        Files.writeString(file, "{not json");
        assertEquals(RegistrationState.empty(), store.load());

        Files.writeString(file, "[1,2,3]");
        assertEquals(RegistrationState.empty(), store.load());

        Files.writeString(file, "{\"version\":99,\"registered\":[\"a@1\"],\"tokens\":{\"t\":\"a\"}}");
        assertEquals(RegistrationState.empty(), store.load());

        store.save(new RegistrationState(Set.of("b@2"), Map.of("gvsbx-u", "b")));
        assertEquals(new RegistrationState(Set.of("b@2"), Map.of("gvsbx-u", "b")), store.load());
    }

    @Test
    void ignoresEntriesOfTheWrongShapeInsteadOfFailing() throws IOException {
        Path file = dir.resolve("sandbox-registrations.json");
        Files.writeString(file, """
                {"version":1,
                 "registered":["ok@1", 42, "", null],
                 "tokens":{"gvsbx-ok":"sb", "gvsbx-num":7, "gvsbx-blank":"", "":"x"},
                 "future-field":true}""");

        RegistrationState loaded = new FileSandboxRegistrationStore(file).load();

        assertEquals(Set.of("ok@1"), loaded.registeredIncarnations());
        assertEquals(Map.of("gvsbx-ok", "sb"), loaded.tokenToName());
    }

    @Test
    void saveFailureIsLoggedNotThrown() throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "I am a file, not a directory");
        FileSandboxRegistrationStore store = new FileSandboxRegistrationStore(blocker.resolve("state.json"));

        store.save(new RegistrationState(Set.of("a@1"), Map.of("gvsbx-t", "a")));   // must not throw

        assertFalse(Files.exists(blocker.resolve("state.json")));
        assertEquals(RegistrationState.empty(), store.load());
    }
}
