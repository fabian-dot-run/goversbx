package run.fabian.daemon.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The one shared fixture of the suite: the real sbx, one daemon (restartable, keeps its state dir)
 * and two throwaway sandboxes created with the Claude kit - {@code <prefix>-a} with a workspace,
 * {@code <prefix>-b} without, so that both exist before the daemon's first reactive registration
 * (the only situation that can reveal two sandboxes' identities colliding). Built on first use,
 * torn down once when the test run ends ({@link E2eSuite}).
 */
final class E2eEnvironment implements AutoCloseable {

    private static E2eEnvironment instance;
    private static Throwable setupFailure;

    final E2eConfig config;
    final Sbx sbx;
    final Path daemonLog;
    final Path daemonState;
    final Sandbox a;
    final Sandbox b;
    private Daemon daemon;
    private final List<Sandbox> created = new ArrayList<>();

    static synchronized E2eEnvironment get() {
        if (setupFailure != null) {
            throw new IllegalStateException("e2e environment setup failed earlier: " + setupFailure.getMessage(), setupFailure);
        }
        if (instance == null) {
            try {
                instance = new E2eEnvironment();
            } catch (Throwable t) {
                setupFailure = t;
                throw t;
            }
        }
        return instance;
    }

    private E2eEnvironment() {
        config = E2eConfig.load();
        System.out.println("== goversbx e2e: " + config);
        sbx = new Sbx(config.sbxBinary);
        String version = sbx.version();
        if (version.isBlank()) {
            fail("sbx version printed nothing");
        }
        // Fails fast, with sbx's own message, if nobody is signed in or the daemon is unavailable.
        List<String> leftovers = new ArrayList<>();
        sbx.lsJson().forEach(row -> {
            String name = row.path("name").asText();
            if (name.startsWith("goversbx-e2e-")) {
                leftovers.add(name);
            }
        });
        if (!leftovers.isEmpty()) {
            System.out.println("    WARNING: sandboxes left over from an earlier run (not touched): " + leftovers
                    + " - remove with: sbx rm --force " + String.join(" ", leftovers));
        }
        daemonLog = config.runDir.resolve("daemon.log");
        daemonState = config.runDir.resolve("state");
        daemon = Daemon.start(config, daemonState, daemonLog);
        System.out.println("== daemon up: sandboxes reach it at " + config.daemonUrlFromSandbox()
                + ", admin at " + daemon.admin("/") + " (registration limited to " + config.identityPattern() + ")");

        a = createSandbox(config.sandboxPrefix + "-a", "claude", config.kit("claude"), config.workspace);
        b = createSandbox(config.sandboxPrefix + "-b", "claude", config.kit("claude"), null);
        System.out.println("== sandboxes ready: " + a + (a.workspace == null ? " (no workspace)" : " (workspace " + a.workspace + ")")
                + ", " + b + " (no workspace). If this JVM dies, remove them with: sbx rm --force " + a + " " + b);
    }

    private Sandbox createSandbox(String name, String agent, Path kit, Path workspace) {
        Sandbox sandbox = Sandbox.create(config, sbx, name, agent, kit, workspace);
        created.add(sandbox);
        return sandbox;
    }

    Daemon daemon() {
        return daemon;
    }

    /** Decides a request as denied through the admin API (used to tidy up check requests). */
    void deny(String requestId) {
        daemon.deny(requestId);
    }

    /** SIGTERM + start again with the same state dir, the way `goversbx stop && goversbx start` does. */
    Daemon restartDaemon() {
        daemon.stop();
        daemon = Daemon.start(config, daemonState, daemonLog);
        return daemon;
    }

    /** Stops the daemon, drops its saved registrations (as if the file were lost), starts it again. */
    Daemon restartDaemonWithoutSavedRegistrations() {
        daemon.stop();
        try {
            Files.deleteIfExists(daemonState.resolve("sandbox-registrations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        daemon = Daemon.start(config, daemonState, daemonLog);
        return daemon;
    }

    @Override
    public void close() {
        System.out.println("== e2e teardown");
        if (daemon != null) {
            daemon.stop();
            System.out.println("    daemon stopped; log kept at " + daemonLog);
        }
        if (config.keep) {
            System.out.println("    GOVERSBX_E2E_KEEP set - keeping " + created + "; remove with: sbx rm --force "
                    + String.join(" ", created.stream().map(Sandbox::toString).toList()));
            return;
        }
        for (Sandbox sandbox : created) {
            sandbox.remove();
        }
    }
}
