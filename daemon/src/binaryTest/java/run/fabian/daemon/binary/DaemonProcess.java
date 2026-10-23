package run.fabian.daemon.binary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * One daemon process started from {@code -Dgoversbx.daemon.command} (newline-separated argv: the
 * native binary, {@code java -jar ...}, or the jar under the tracing agent), on free loopback
 * ports, wired to a {@link FakeSbx}, for real (not dry-run) unless a test overrides the env.
 */
final class DaemonProcess implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String PLACEHOLDER = "goversbx-sandbox-identity-placeholder-v1";
    /** Fixed admin token for tests (the daemon takes it from GOVERSBX_ADMIN_TOKEN); admin calls carry it. */
    static final String ADMIN_TOKEN = "test-admin-token";

    final FakeSbx sbx;
    final int sandboxPort;
    final int adminPort;
    final Path log;
    final long startupMillis;
    private final Process process;

    private DaemonProcess(FakeSbx sbx, int sandboxPort, int adminPort, Path log, Process process, long startupMillis) {
        this.sbx = sbx;
        this.sandboxPort = sandboxPort;
        this.adminPort = adminPort;
        this.log = log;
        this.process = process;
        this.startupMillis = startupMillis;
    }

    static DaemonProcess start(FakeSbx sbx) {
        return start(sbx, Map.of());
    }

    static DaemonProcess start(FakeSbx sbx, Map<String, String> envOverrides) {
        String raw = System.getProperty("goversbx.daemon.command");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("-Dgoversbx.daemon.command is not set - run via ./gradlew :daemon:binaryTestJar or :daemon:binaryTestNative");
        }
        List<String> command = List.of(raw.split("\n"));
        int sandboxPort = freePort();
        int adminPort = freePort();
        try {
            Path log = Files.createTempFile("goversbx-daemon", ".log");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            Map<String, String> env = builder.environment();
            env.put("GOVERSBX_SANDBOX_API_PORT", Integer.toString(sandboxPort));
            env.put("GOVERSBX_ADMIN_PORT", Integer.toString(adminPort));
            env.put("GOVERSBX_SANDBOX_API_BIND", "127.0.0.1");
            env.put("GOVERSBX_SBX_BINARY", sbx.binary().toString());
            env.put("GOVERSBX_SBX_DRY_RUN", "false");
            env.put("GOVERSBX_ADMIN_TOKEN", ADMIN_TOKEN);
            env.put("GOVERSBX_AUTO_OPEN_DASHBOARD", "false");            // tests opt in explicitly
            env.put("XDG_CONFIG_HOME", Files.createTempDirectory("goversbx-config").toString());
            // Fresh state dir per daemon (sandbox-registrations.json lives there), unless a test shares one
            // across two daemons on purpose to check what survives a restart.
            env.put("GOVERSBX_STATE_DIR", Files.createTempDirectory("goversbx-state").toString());
            env.putAll(envOverrides);
            long started = System.nanoTime();
            Process process = builder.start();
            DaemonProcess daemon = new DaemonProcess(sbx, sandboxPort, adminPort, log, process, 0);
            daemon.awaitHealthy(Duration.ofSeconds(60));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.printf("daemon %s ready in %d ms%n", command.get(command.size() - 1), millis);
            return new DaemonProcess(sbx, sandboxPort, adminPort, log, process, millis);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void awaitHealthy(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail("daemon exited during startup (exit " + process.exitValue() + "):\n" + log());
            }
            if (isHealthy(admin("/api/health")) && isHealthy(sandboxApi("/api/health"))) {
                return;
            }
            sleep(100);
        }
        process.destroyForcibly();
        fail("daemon not healthy within " + timeout + ":\n" + log());
    }

    private static boolean isHealthy(URI uri) {
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
        return URI.create("http://127.0.0.1:" + adminPort + path);
    }

    URI sandboxApi(String path) {
        return URI.create("http://127.0.0.1:" + sandboxPort + path);
    }

    Mcp mcp(String sandboxHeader) {
        return new Mcp(sandboxApi("/mcp"), sandboxHeader);
    }

    /**
     * An initialized MCP session that acts as a registered sandbox: it carries the real identity
     * token the daemon minted for {@code name} (via {@link #tokenFor}), the way the sbx proxy would
     * substitute it into the sandbox's header. Use this instead of {@code mcp(name)} wherever a
     * request_network_access call must actually create a request - a bare name is no longer trusted.
     */
    Mcp asSandbox(String name) {
        return mcp(tokenFor(name)).initialize();
    }

    /**
     * Registers {@code name} through the loopback admin endpoint (what an operator pre-warming a
     * sandbox does) and returns the identity token the daemon injected for it - read from the
     * daemon's dry-run output, or, in real mode, from the {@link FakeSbx} recording of the
     * {@code secret set-custom} argv. This is the trusted, host-side channel standing in for the
     * sbx proxy; a sandbox can never obtain this value.
     */
    String tokenFor(String name) {
        HttpResponse<String> response = postJson(admin("/api/sandboxes/" + name + "/register"), "");
        if (response.statusCode() != 200) {
            fail("register " + name + " -> " + response.statusCode() + ": " + response.body());
        }
        String output = parse(response.body()).path("output").asText("");
        String token = valueArg(output);              // dry run: command (with --value) is in the output
        if (token == null) {
            token = sbx.tokenFor(name);               // real mode: read it from the recorded argv
        }
        if (token == null || token.isBlank()) {
            fail("no identity token minted for " + name + "; register output: " + output + "; sbx calls: " + sbx.calls());
        }
        return token;
    }

    private static String valueArg(String command) {
        int i = command.indexOf("--value ");
        return i < 0 ? null : command.substring(i + "--value ".length()).trim();
    }

    HttpResponse<String> get(URI uri) {
        return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + ADMIN_TOKEN).build());
    }

    HttpResponse<String> postJson(URI uri, String body) {
        return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    JsonNode adminJson(String path) {
        HttpResponse<String> response = get(admin(path));
        if (response.statusCode() != 200) {
            fail("GET " + path + " -> " + response.statusCode() + ": " + response.body());
        }
        return parse(response.body());
    }

    /** All requests (any status) for this host, newest data. */
    List<JsonNode> requestsFor(String host) {
        List<JsonNode> matches = new java.util.ArrayList<>();
        adminJson("/api/requests").forEach(r -> {
            if (host.equals(r.path("host").asText())) {
                matches.add(r);
            }
        });
        return matches;
    }

    /** All requests (any status) matching the predicate, newest data. */
    List<JsonNode> requestsWhere(java.util.function.Predicate<JsonNode> predicate) {
        List<JsonNode> matches = new java.util.ArrayList<>();
        adminJson("/api/requests").forEach(r -> {
            if (predicate.test(r)) {
                matches.add(r);
            }
        });
        return matches;
    }

    /** Project host sets (any status) of this sandbox. */
    List<JsonNode> hostSetsOf(String sandbox) {
        return requestsWhere(r -> "PROJECT_FILE".equals(r.path("origin").asText()) && sandbox.equals(r.path("sandboxName").asText()));
    }

    /** Waits until a pending project host set for this sandbox exists and returns it. */
    JsonNode awaitPendingHostSet(String sandbox) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            for (JsonNode r : hostSetsOf(sandbox)) {
                if ("PENDING".equals(r.path("status").asText())) {
                    return r;
                }
            }
            sleep(50);
        }
        return fail("no pending host set for " + sandbox + " within 20s; log:\n" + log());
    }

    /** Waits until exactly one pending request for this host exists and returns it. */
    JsonNode awaitPending(String host) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            for (JsonNode r : requestsFor(host)) {
                if ("PENDING".equals(r.path("status").asText())) {
                    return r;
                }
            }
            sleep(50);
        }
        return fail("no pending request for " + host + " within 20s; log:\n" + log());
    }

    JsonNode decide(String requestId, String decision) {
        HttpResponse<String> response = postJson(admin("/api/requests/" + requestId + "/" + decision), "{\"decidedBy\":\"binary-test\"}");
        if (response.statusCode() != 200) {
            fail(decision + " -> " + response.statusCode() + ": " + response.body());
        }
        return parse(response.body());
    }

    String log() {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            return "<log unreadable: " + e + ">";
        }
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /** SIGTERM, like launchd and `goversbx stop`; returns the exit code. */
    int stop() {
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("daemon did not exit within 15s of SIGTERM:\n" + log());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        return process.exitValue();
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            stop();
        }
    }

    static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new AssertionError("not JSON: " + json, e);
        }
    }

    static HttpResponse<String> send(HttpRequest request) {
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

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, String> env(String... keyValues) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            env.put(keyValues[i], keyValues[i + 1]);
        }
        return env;
    }
}
