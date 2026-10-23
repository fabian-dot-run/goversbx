package run.fabian.daemon.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything the suite needs to know about the machine it runs on, from {@code GOVERSBX_E2E_*}
 * environment variables (plus the Gradle-supplied {@code goversbx.*} system properties).
 *
 * <pre>
 * GOVERSBX_E2E_SBX                   sbx binary (default: `sbx` on PATH)
 * GOVERSBX_E2E_SANDBOX_PREFIX        name prefix of the throwaway sandboxes (default goversbx-e2e-&lt;timestamp&gt;);
 *                                    the daemon only registers sandboxes under this prefix
 * GOVERSBX_E2E_DAEMON_HOST           how a sandbox reaches the daemon (default host.docker.internal)
 * GOVERSBX_E2E_IDENTITY_TARGET_HOST  `sbx secret set-custom --host` - how the sbx proxy sees that traffic (default localhost)
 * GOVERSBX_E2E_SANDBOX_API_PORT      daemon sandbox-facing port (default: a free port; the kit gets --kit-arg daemonPort)
 * GOVERSBX_E2E_ADMIN_PORT            daemon admin port (default: a free loopback port)
 * GOVERSBX_E2E_TARGET_HOST           host the approval flow asks for; must be blocked beforehand (default example.org)
 * GOVERSBX_E2E_DENY_HOST             host the deny flow / the real agent asks for; must be blocked beforehand (default example.net)
 * GOVERSBX_E2E_SET_HOST              host the project host-set flow declares; must be blocked beforehand (default example.com)
 * GOVERSBX_E2E_WORKSPACE             workspace for the first sandbox: a path, or `none` for a workspace-less sandbox
 *                                    (default: a fresh dir under the run dir; falls back to `none` if sbx denies the mount)
 * GOVERSBX_E2E_KEEP                  1 = keep sandboxes, secrets and the daemon's state dir at the end (debugging)
 * GOVERSBX_E2E_AGENTS                comma-separated real agents to drive (`claude`); default none - those tests are skipped
 * GOVERSBX_E2E_AGENT_TIMEOUT         seconds to wait for a real agent run (default 300)
 * </pre>
 *
 * Nothing here reads {@code SANDBOX_NAME}: inside a goversbx dev sandbox that is the OUTER
 * sandbox's own name, and the 2026-10-14 nested run collided with it.
 */
final class E2eConfig {

    static final String PLACEHOLDER = "goversbx-sandbox-identity-placeholder-v1";
    static final String ADMIN_TOKEN = "goversbx-e2e-admin-token";

    final Path sbxBinary;
    final Path daemonJar;
    final Path kitsDir;
    final Path runDir;
    final String runId;
    final String sandboxPrefix;
    final String daemonHost;
    final String identityTargetHost;
    final int sandboxApiPort;
    final int adminPort;
    final String targetHost;
    final String denyHost;
    final String setHost;
    /** Null = workspace-less. */
    final Path workspace;
    final boolean keep;
    final java.util.Set<String> agents;
    final Duration agentTimeout;

    private E2eConfig() {
        sbxBinary = resolveSbx(env("GOVERSBX_E2E_SBX").orElse("sbx"));
        daemonJar = Path.of(property("goversbx.daemon.jar"));
        kitsDir = Path.of(property("goversbx.kitsDir"));
        runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT));
        runDir = Path.of(property("goversbx.e2e.runDir")).resolve(runId);
        sandboxPrefix = env("GOVERSBX_E2E_SANDBOX_PREFIX").orElse("goversbx-e2e-" + runId);
        daemonHost = env("GOVERSBX_E2E_DAEMON_HOST").orElse("host.docker.internal");
        identityTargetHost = env("GOVERSBX_E2E_IDENTITY_TARGET_HOST").orElse("localhost");
        sandboxApiPort = env("GOVERSBX_E2E_SANDBOX_API_PORT").map(Integer::parseInt).orElseGet(E2eConfig::freePort);
        adminPort = env("GOVERSBX_E2E_ADMIN_PORT").map(Integer::parseInt).orElseGet(E2eConfig::freePort);
        targetHost = env("GOVERSBX_E2E_TARGET_HOST").orElse("example.org");
        denyHost = env("GOVERSBX_E2E_DENY_HOST").orElse("example.net");
        setHost = env("GOVERSBX_E2E_SET_HOST").orElse("example.com");
        String ws = env("GOVERSBX_E2E_WORKSPACE").orElse("");
        workspace = ws.equalsIgnoreCase("none") ? null : ws.isBlank() ? runDir.resolve("workspace") : Path.of(ws);
        keep = env("GOVERSBX_E2E_KEEP").map(v -> v.equals("1") || v.equalsIgnoreCase("true")).orElse(false);
        agents = env("GOVERSBX_E2E_AGENTS").map(v -> java.util.Set.of(v.toLowerCase(Locale.ROOT).split("\\s*,\\s*")))
                .orElse(java.util.Set.of());
        agentTimeout = Duration.ofSeconds(env("GOVERSBX_E2E_AGENT_TIMEOUT").map(Long::parseLong).orElse(300L));
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static E2eConfig load() {
        return new E2eConfig();
    }

    Path kit(String name) {
        return kitsDir.resolve("goversbx-" + name);
    }

    String daemonUrlFromSandbox() {
        return "http://" + daemonHost + ":" + sandboxApiPort;
    }

    /** Java regex for GOVERSBX_IDENTITY_SANDBOX_PATTERN: only this run's sandboxes get registered. */
    String identityPattern() {
        return java.util.regex.Pattern.quote(sandboxPrefix) + "-.*";
    }

    boolean agentEnabled(String agent) {
        return agents.contains(agent);
    }

    private static Optional<String> env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("-D" + name + " is not set - run the suite via ./gradlew :daemon:e2eTest");
        }
        return value;
    }

    private static Path resolveSbx(String binary) {
        Path given = Path.of(binary);
        if (given.isAbsolute() || binary.contains("/")) {
            return given.toAbsolutePath();
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(dir).resolve(binary);
                if (Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath();
                }
            }
        }
        throw new IllegalStateException("'" + binary + "' not found on PATH - install docker-sbx or set GOVERSBX_E2E_SBX");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "sbx=" + sbxBinary + " jar=" + daemonJar + " kits=" + kitsDir + " runDir=" + runDir
                + " prefix=" + sandboxPrefix + " daemon=" + daemonUrlFromSandbox() + " admin=127.0.0.1:" + adminPort
                + " identityTargetHost=" + identityTargetHost + " target=" + targetHost + " deny=" + denyHost + " set=" + setHost
                + " workspace=" + (workspace == null ? "none" : workspace) + " keep=" + keep + " agents=" + agents;
    }
}
