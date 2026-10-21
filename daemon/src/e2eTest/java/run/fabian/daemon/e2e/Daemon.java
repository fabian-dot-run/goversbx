package run.fabian.daemon.e2e;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The real daemon jar, started for real ({@code GOVERSBX_SBX_DRY_RUN=false}) against the real sbx,
 * with its sandbox-facing listener on all interfaces so sandboxes reach it via
 * {@code host.docker.internal}. Registration is limited to this run's sandboxes
 * ({@code GOVERSBX_IDENTITY_SANDBOX_PATTERN}) so the machine's other sandboxes are left alone.
 * The admin API is driven over loopback with a fixed token, like {@code goversbx dashboard} would.
 */
final class Daemon implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    final E2eConfig config;
    final Path stateDir;
    final Path log;
    private final Process process;

    private Daemon(E2eConfig config, Path stateDir, Path log, Process process) {
        this.config = config;
        this.stateDir = stateDir;
        this.log = log;
        this.process = process;
    }

    /** Starts (or, with an existing state dir, restarts) the daemon; the log is appended to. */
    static Daemon start(E2eConfig config, Path stateDir, Path log) {
        try {
            Files.createDirectories(stateDir);
            Path configHome = Files.createDirectories(config.runDir.resolve("xdg-config"));
            List<String> command = List.of(javaBinary(), "-jar", config.daemonJar.toString());
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            Map<String, String> env = builder.environment();
            env.put("GOVERSBX_SBX_DRY_RUN", "false");
            env.put("GOVERSBX_SBX_BINARY", config.sbxBinary.toString());
            env.put("GOVERSBX_IDENTITY_TARGET_HOST", config.identityTargetHost);
            env.put("GOVERSBX_IDENTITY_SANDBOX_PATTERN", config.identityPattern());
            env.put("GOVERSBX_SANDBOX_API_PORT", Integer.toString(config.sandboxApiPort));
            env.put("GOVERSBX_SANDBOX_API_BIND", "0.0.0.0");
            env.put("GOVERSBX_ADMIN_PORT", Integer.toString(config.adminPort));
            env.put("GOVERSBX_ADMIN_TOKEN", E2eConfig.ADMIN_TOKEN);
            env.put("GOVERSBX_AUTO_OPEN_DASHBOARD", "false");
            env.put("GOVERSBX_STATE_DIR", stateDir.toString());
            env.put("XDG_CONFIG_HOME", configHome.toString());
            // The daemon itself must talk to loopback/sbx only; a proxied JVM inside a dev sandbox
            // must not send its own admin calls through the sandbox proxy.
            env.remove("JAVA_TOOL_OPTIONS");
            System.out.println("$ " + String.join(" ", command) + "  (state " + stateDir + ", log " + log + ")");
            Daemon daemon = new Daemon(config, stateDir, log, builder.start());
            daemon.awaitHealthy(Duration.ofSeconds(60));
            return daemon;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private void awaitHealthy(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail("daemon exited during startup (exit " + process.exitValue() + "):\n" + log());
            }
            if (healthy(admin("/api/health")) && healthy(URI.create("http://127.0.0.1:" + config.sandboxApiPort + "/api/health"))) {
                return;
            }
            sleep(200);
        }
        process.destroyForcibly();
        fail("daemon not healthy within " + timeout + ":\n" + log());
    }

    private static boolean healthy(URI uri) {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"ok\"");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    URI admin(String path) {
        return URI.create("http://127.0.0.1:" + config.adminPort + path);
    }

    HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(admin(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + E2eConfig.ADMIN_TOKEN).build());
    }

    HttpResponse<String> postJson(String path, String body) {
        return send(HttpRequest.newBuilder(admin(path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + E2eConfig.ADMIN_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    JsonNode requests() {
        HttpResponse<String> response = get("/api/requests");
        if (response.statusCode() != 200) {
            fail("GET /api/requests -> " + response.statusCode() + ": " + response.body());
        }
        return parse(response.body());
    }

    List<JsonNode> requestsFor(String host) {
        List<JsonNode> matches = new ArrayList<>();
        requests().forEach(r -> {
            if (host.equals(r.path("host").asText())) {
                matches.add(r);
            }
        });
        return matches;
    }

    Optional<JsonNode> pendingFor(String host) {
        return requestsFor(host).stream().filter(r -> "PENDING".equals(r.path("status").asText())).findFirst();
    }

    JsonNode awaitPending(String host, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<JsonNode> pending = pendingFor(host);
            if (pending.isPresent()) {
                System.out.println("    pending request: " + pending.get());
                return pending.get();
            }
            sleep(500);
        }
        return fail("no pending request for " + host + " within " + timeout + " - most likely the placeholder header "
                + "is not being substituted (check `sbx secret ls --sandbox <name>`). Requests seen: " + requests()
                + "\nDaemon log tail:\n" + logTail(40));
    }

    /** POST approve/deny; returns the decided request. */
    JsonNode decide(String requestId, String decision) {
        HttpResponse<String> response = postJson("/api/requests/" + requestId + "/" + decision, "{\"decidedBy\":\"e2eTest\"}");
        if (response.statusCode() != 200) {
            fail(decision + " " + requestId + " -> " + response.statusCode() + ": " + response.body());
        }
        JsonNode decided = parse(response.body());
        System.out.println("    " + decision + " -> " + decided);
        return decided;
    }

    JsonNode approve(String requestId) {
        return decide(requestId, "approve");
    }

    JsonNode deny(String requestId) {
        return decide(requestId, "deny");
    }

    String log() {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            return "<log unreadable: " + e + ">";
        }
    }

    String logTail(int lines) {
        String[] all = log().split("\n");
        int from = Math.max(0, all.length - lines);
        return String.join("\n", List.of(all).subList(from, all.length));
    }

    boolean logContains(String needle) {
        return log().contains(needle);
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /** SIGTERM, like `goversbx stop`. */
    void stop() {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("daemon did not exit within 15s of SIGTERM");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    public void close() {
        stop();
    }

    static JsonNode parse(String json) {
        try {
            return Sbx.JSON.readTree(json);
        } catch (IOException e) {
            throw new AssertionError("not JSON: " + json, e);
        }
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(request.method() + " " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
