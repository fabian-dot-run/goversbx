package run.fabian.daemon.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Host-side daemon settings that live outside the code: the admin API token and the user-editable
 * config file in {@code $XDG_CONFIG_HOME/goversbx/config.properties} (default
 * {@code ~/.config/goversbx/}). The file is created with commented defaults on first start so the
 * user has something to edit. Environment variables (the {@code GOVERSBX_*} family) override the
 * file, so tests and CI can pin values.
 */
public final class DaemonConfig {

    private static final Logger log = LoggerFactory.getLogger(DaemonConfig.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String adminToken;
    private final boolean autoOpenDashboard;
    private final List<String> browserOpenerCommand;
    private final long autoOpenCooldownMillis;

    private DaemonConfig(String adminToken, boolean autoOpenDashboard,
                         List<String> browserOpenerCommand, long autoOpenCooldownMillis) {
        this.adminToken = adminToken;
        this.autoOpenDashboard = autoOpenDashboard;
        this.browserOpenerCommand = browserOpenerCommand;
        this.autoOpenCooldownMillis = autoOpenCooldownMillis;
    }

    /** The bearer token every admin API/SSE request must carry; a sandbox never has it. */
    public String adminToken() {
        return adminToken;
    }

    /** Open the dashboard in the browser when the first request arrives and none is connected. */
    public boolean autoOpenDashboard() {
        return autoOpenDashboard;
    }

    /** The browser-opener argv (without the URL), or empty to auto-detect {@code open}/{@code xdg-open}. */
    public List<String> browserOpenerCommand() {
        return browserOpenerCommand;
    }

    public long autoOpenCooldownMillis() {
        return autoOpenCooldownMillis;
    }

    public static DaemonConfig load() {
        Properties file = readOrCreateConfigFile();

        boolean autoOpen = boolSetting("GOVERSBX_AUTO_OPEN_DASHBOARD", file, "autoOpenDashboard", true);
        long cooldown = longSetting("GOVERSBX_AUTO_OPEN_COOLDOWN_MS", file, "autoOpenCooldownMillis", 10_000);

        String openerEnv = env("GOVERSBX_BROWSER_OPENER");
        List<String> opener = openerEnv == null ? List.of() : List.of(openerEnv.split(" "));

        return new DaemonConfig(resolveAdminToken(), autoOpen, opener, cooldown);
    }

    // --- admin token ----------------------------------------------------------------------------

    private static String resolveAdminToken() {
        String explicit = env("GOVERSBX_ADMIN_TOKEN");
        if (explicit != null) {
            return explicit;
        }
        Path tokenFile = adminTokenFile();
        try {
            if (Files.isReadable(tokenFile)) {
                String existing = Files.readString(tokenFile).trim();
                if (!existing.isBlank()) {
                    return existing;
                }
            }
            String token = "gvadm-" + randomToken();
            Files.createDirectories(tokenFile.getParent());
            Files.writeString(tokenFile, token + "\n");
            trySetOwnerOnly(tokenFile);
            log.info("Generated a new admin API token at {}", tokenFile);
            return token;
        } catch (IOException e) {
            // Never block startup on the token file; fall back to an in-memory token (the admin UI
            // then needs GOVERSBX_ADMIN_TOKEN or `goversbx dashboard` can't read it, which is logged).
            log.warn("Could not read/write the admin token file {} - using an ephemeral token", tokenFile, e);
            return "gvadm-" + randomToken();
        }
    }

    /** Where the admin token is stored; the launcher reads the same path for `goversbx dashboard`. */
    public static Path adminTokenFile() {
        String explicit = env("GOVERSBX_ADMIN_TOKEN_FILE");
        if (explicit != null) {
            return Path.of(explicit);
        }
        return stateDir().resolve("admin-token");
    }

    /**
     * Where sandbox registrations (incarnations + identity tokens) are kept across restarts; holds
     * secrets, so it sits next to the admin token in the state dir and gets the same permissions.
     */
    public static Path sandboxRegistrationsFile() {
        return stateDir().resolve("sandbox-registrations.json");
    }

    private static Path stateDir() {
        String stateDir = env("GOVERSBX_STATE_DIR");
        if (stateDir != null) {
            return Path.of(stateDir);
        }
        return Path.of(System.getProperty("user.home"), ".local", "state", "goversbx");
    }

    private static void trySetOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem; the state dir is already user-scoped, so this is best-effort.
        }
    }

    // --- config file ----------------------------------------------------------------------------

    private static Path configFile() {
        return configDir().resolve("config.properties");
    }

    /** {@code $XDG_CONFIG_HOME/goversbx} (default {@code ~/.config/goversbx}). */
    public static Path configDir() {
        String xdg = env("XDG_CONFIG_HOME");
        Path base = xdg != null ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("goversbx");
    }

    /**
     * User-supplied host-set profiles ({@code <name>.yaml}) a project file may reference. Deliberately
     * only here, next to the config file - no override, no shipped profiles (spec 0001).
     */
    public static Path profilesDir() {
        return configDir().resolve("profiles");
    }

    private static Properties readOrCreateConfigFile() {
        Path file = configFile();
        Properties props = new Properties();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_CONFIG);
                log.info("Wrote a default config file at {}", file);
            }
            if (Files.isReadable(file)) {
                try (var in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
        } catch (IOException e) {
            log.warn("Could not read/create the config file {} - using defaults", file, e);
        }
        return props;
    }

    private static final String DEFAULT_CONFIG = """
            # goversbx daemon configuration. Environment variables (GOVERSBX_*) override these.

            # Open the dashboard in your default browser when the first network-access request arrives
            # and no dashboard tab is already connected to the daemon. It never opens a second window
            # or steals focus while a dashboard is open (the daemon tracks its own live connections).
            # true (default) or false.
            autoOpenDashboard=true

            # How long (ms) after an auto-open before the daemon may auto-open again, to absorb a burst
            # of requests while the just-opened tab is still connecting.
            autoOpenCooldownMillis=10000
            """;

    // --- helpers --------------------------------------------------------------------------------

    private static boolean boolSetting(String envName, Properties file, String key, boolean fallback) {
        String value = env(envName);
        if (value == null) {
            value = file.getProperty(key);
        }
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static long longSetting(String envName, Properties file, String key, long fallback) {
        String value = env(envName);
        if (value == null) {
            value = file.getProperty(key);
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
}
