package run.fabian.daemon.projecthosts.adapter.file;

import run.fabian.daemon.projecthosts.application.port.HostSetSource;
import run.fabian.daemon.projecthosts.domain.HostSetRules;
import run.fabian.daemon.projecthosts.domain.InvalidHostSetException;
import run.fabian.daemon.projecthosts.domain.ResolvedHostSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code <workspace>/.goversbx/network.yaml} and the profiles it names from
 * {@code <profilesDir>/<name>.yaml}. Strict on purpose - the project file is agent-writable, so a
 * typo must fail loudly rather than silently drop a host, and the file must be a plain regular file
 * of modest size (no symlinks: the workspace path comes from sbx, the file must not redirect the
 * daemon elsewhere). Jackson's tree model, like the registration store: no reflection on our own
 * types, so nothing new for native-image metadata.
 */
public final class YamlHostSetSource implements HostSetSource {

    private static final Set<String> PROJECT_KEYS = Set.of("version", "profiles", "hosts");
    private static final Set<String> PROFILE_KEYS = Set.of("version", "hosts");

    private final Path profilesDir;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public YamlHostSetSource(Path profilesDir) {
        this.profilesDir = profilesDir;
    }

    public Path profilesDir() {
        return profilesDir;
    }

    @Override
    public Optional<ResolvedHostSet> resolve(Path workspace) {
        Path dir = workspace.resolve(".goversbx");
        Path file = dir.resolve("network.yaml");
        String source = file.toString();
        List<String> problems = new ArrayList<>();
        if (Files.isSymbolicLink(dir)) {
            problems.add(dir + " is a symbolic link - the .goversbx directory must be a plain directory");
            throw new InvalidHostSetException(source, problems);
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        JsonNode root = readDocument(file, source, problems);
        if (root == null) {
            throw new InvalidHostSetException(source, problems);
        }
        checkKeys(root, PROJECT_KEYS, source, problems);
        checkVersion(root, source, problems);
        List<String> profiles = stringList(root, "profiles", source, problems);
        List<String> hosts = stringList(root, "hosts", source, problems);
        if (profiles.isEmpty() && hosts.isEmpty() && problems.isEmpty()) {
            problems.add(source + ": neither 'profiles' nor 'hosts' names anything");
        }
        List<List<String>> profileHosts = new ArrayList<>();
        for (String profile : profiles) {
            if (!HostSetRules.isValidProfileName(profile)) {
                problems.add(source + ": profile name '" + profile + "' is not allowed (letters, digits and '-' only, "
                        + HostSetRules.PROFILE_NAME.pattern() + ")");
                continue;
            }
            profileHosts.add(readProfile(profile, problems));
        }
        if (!problems.isEmpty()) {
            throw new InvalidHostSetException(source, problems);
        }
        List<String> combined = HostSetRules.combine(profileHosts, hosts);
        if (combined.size() > HostSetRules.MAX_HOSTS) {
            throw new InvalidHostSetException(source, List.of(source + ": " + combined.size()
                    + " distinct hosts, more than the " + HostSetRules.MAX_HOSTS + " a human can review"));
        }
        return Optional.of(new ResolvedHostSet(combined, source, profiles));
    }

    private List<String> readProfile(String name, List<String> problems) {
        Path file = profilesDir.resolve(name + ".yaml");
        String source = file.toString();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            problems.add("profile '" + name + "' not found (expected " + source + ")");
            return List.of();
        }
        JsonNode root = readDocument(file, source, problems);
        if (root == null) {
            return List.of();
        }
        checkKeys(root, PROFILE_KEYS, source, problems);
        checkVersion(root, source, problems);
        List<String> hosts = stringList(root, "hosts", source, problems);
        if (hosts.isEmpty()) {
            problems.add(source + ": 'hosts' names nothing");
        }
        return hosts;
    }

    /** The document's root object, or null after recording why it can't be read. */
    private JsonNode readDocument(Path file, String source, List<String> problems) {
        try {
            if (Files.isSymbolicLink(file)) {
                problems.add(source + " is a symbolic link - must be a plain file");
                return null;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                problems.add(source + " is not a regular file");
                return null;
            }
            long size = Files.size(file);
            if (size > HostSetRules.MAX_FILE_BYTES) {
                problems.add(source + " is " + size + " bytes, larger than the " + HostSetRules.MAX_FILE_BYTES + " allowed");
                return null;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = yaml.readTree(text);
            if (root == null || root.isMissingNode() || root.isNull()) {
                problems.add(source + " is empty");
                return null;
            }
            if (!root.isObject()) {
                problems.add(source + ": expected a mapping with 'version', 'profiles' and/or 'hosts' at the top level");
                return null;
            }
            return root;
        } catch (IOException e) {
            problems.add(source + ": cannot be parsed: " + firstLine(e.getMessage()));
            return null;
        }
    }

    private static void checkKeys(JsonNode root, Set<String> allowed, String source, List<String> problems) {
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!allowed.contains(key)) {
                problems.add(source + ": unknown key '" + key + "' (allowed: " + String.join(", ", allowed.stream().sorted().toList()) + ")");
            }
        }
    }

    private static void checkVersion(JsonNode root, String source, List<String> problems) {
        JsonNode version = root.get("version");
        if (version == null) {
            problems.add(source + ": 'version: " + HostSetRules.SCHEMA_VERSION + "' is required");
        } else if (!version.isInt() || version.intValue() != HostSetRules.SCHEMA_VERSION) {
            problems.add(source + ": version " + version + " is not supported (only " + HostSetRules.SCHEMA_VERSION + ")");
        }
    }

    private static List<String> stringList(JsonNode root, String key, String source, List<String> problems) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            problems.add(source + ": '" + key + "' must be a list");
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                problems.add(source + ": '" + key + "' entry " + item + " is not a plain string");
                continue;
            }
            values.add(item.asText().trim());
        }
        return values;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
