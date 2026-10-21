package run.fabian.daemon.sandboxidentity.adapter.persistence;

import run.fabian.daemon.sandboxidentity.application.port.SandboxRegistrationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@link SandboxRegistrationStore} backed by one JSON file in the daemon's state directory
 * (next to {@code admin-token}):
 *
 * <pre>{"version":1,"registered":["name@id",...],"tokens":{"gvsbx-...":"name",...}}</pre>
 *
 * <p>Writes go to a temp file in the same directory that is then moved over the real one, so a
 * crash mid-write leaves the previous state intact rather than a truncated file. The file holds
 * identity tokens, so it is created owner-read/write only, like the admin token. The JSON is read
 * and written through Jackson's tree model on purpose: no reflection on our own types, so nothing
 * here needs native-image metadata beyond what the rest of the daemon already uses.
 *
 * <p>An unreadable or malformed file is logged and treated as empty: the daemon then falls back to
 * the reactive "please retry" re-registration it did before persistence existed, and the next save
 * replaces the broken file.
 */
public final class FileSandboxRegistrationStore implements SandboxRegistrationStore {

    private static final Logger log = LoggerFactory.getLogger(FileSandboxRegistrationStore.class);
    private static final int VERSION = 1;

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileSandboxRegistrationStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    @Override
    public RegistrationState load() {
        if (!Files.exists(file)) {
            return RegistrationState.empty();
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                log.warn("Sandbox registration file {} is not a JSON object - starting without saved registrations", file);
                return RegistrationState.empty();
            }
            int version = root.path("version").asInt(-1);
            if (version != VERSION) {
                log.warn("Sandbox registration file {} has unsupported version {} - starting without saved registrations",
                        file, version);
                return RegistrationState.empty();
            }
            Set<String> registered = new HashSet<>();
            for (JsonNode key : root.path("registered")) {
                if (key.isTextual() && !key.asText().isBlank()) {
                    registered.add(key.asText());
                }
            }
            Map<String, String> tokens = new HashMap<>();
            for (Map.Entry<String, JsonNode> entry : root.path("tokens").properties()) {
                if (entry.getValue().isTextual() && !entry.getKey().isBlank() && !entry.getValue().asText().isBlank()) {
                    tokens.put(entry.getKey(), entry.getValue().asText());
                }
            }
            log.info("Loaded {} sandbox registration(s) ({} identity token(s)) from {}", registered.size(), tokens.size(), file);
            return new RegistrationState(registered, tokens);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the sandbox registration file {} - starting without saved registrations", file, e);
            return RegistrationState.empty();
        }
    }

    @Override
    public void save(RegistrationState state) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", VERSION);
        ArrayNode registered = root.putArray("registered");
        new TreeSet<>(state.registeredIncarnations()).forEach(registered::add);
        ObjectNode tokens = root.putObject("tokens");
        new TreeMap<>(state.tokenToName()).forEach(tokens::put);

        Path temp = null;
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, file.getFileName() + ".", ".tmp");
            trySetOwnerOnly(temp);
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            trySetOwnerOnly(file);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not save sandbox registrations to {} - they will be redone after a restart", file, e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static void trySetOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem; the state dir is already user-scoped, so this is best-effort.
        }
    }
}
