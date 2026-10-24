package run.fabian.daemon.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * One throwaway sandbox created with a goversbx kit. Everything the agent side does in the suite
 * goes through here and runs INSIDE the sandbox via {@code sbx exec} - so through the sbx proxy,
 * carrying the hardcoded placeholder header, exactly the path a real agent's MCP client takes.
 */
final class Sandbox {

    /** POSIX sh: one MCP session, one tools/call; prints the raw tools/call response body. */
    private static final String MCP_CALL = """
            set -e
            hdr=$(mktemp)
            h1="Content-Type: application/json"; h2="Accept: application/json, text/event-stream"; h3="X-Goversbx-Sandbox-Name: $PLACEHOLDER"
            init='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"goversbx-e2e","version":"0"}}}'
            curl -sS -D "$hdr" -o /dev/null --max-time 30 -X POST "$DAEMON_URL/mcp" -H "$h1" -H "$h2" -H "$h3" -d "$init"
            sid=$(grep -i "^mcp-session-id:" "$hdr" | tr -d "\\r" | cut -d" " -f2)
            [ -n "$sid" ] || { echo "NO-SESSION"; exit 0; }
            curl -sS -o /dev/null --max-time 30 -X POST "$DAEMON_URL/mcp" -H "$h1" -H "$h2" -H "$h3" -H "mcp-session-id: $sid" \\
              -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
            curl -sS --max-time "$MAX_TIME" -X POST "$DAEMON_URL/mcp" -H "$h1" -H "$h2" -H "$h3" -H "mcp-session-id: $sid" \\
              -d "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":2,\\"method\\":\\"tools/call\\",\\"params\\":{\\"name\\":\\"$TOOL\\",\\"arguments\\":$ARGS}}"
            """;

    final String name;
    final String agent;
    final Path kit;
    /** Null if created without a workspace bind mount. */
    final Path workspace;
    /** Set when a workspace was asked for but sbx refused the mount and the sandbox was created without one. */
    final String workspaceNote;
    private final E2eConfig config;
    private final Sbx sbx;

    private Sandbox(E2eConfig config, Sbx sbx, String name, String agent, Path kit, Path workspace, String workspaceNote) {
        this.config = config;
        this.sbx = sbx;
        this.name = name;
        this.agent = agent;
        this.kit = kit;
        this.workspace = workspace;
        this.workspaceNote = workspaceNote;
    }

    /**
     * {@code sbx create --kit <kit> [--kit-arg goversbx-<kit>.daemonPort=N] --name <name> <agent> [<workspace>]}.
     * A refused workspace mount (seen on a headless Linux sbx without the admin-settings backend:
     * "mount policy denied") falls back to a workspace-less sandbox and records that.
     */
    static Sandbox create(E2eConfig config, Sbx sbx, String name, String agent, Path kit, Path workspace) {
        if (workspace != null) {
            try {
                Files.createDirectories(workspace);
                Path readme = workspace.resolve("README.md");
                if (!Files.exists(readme)) {
                    Files.writeString(readme, "# goversbx e2e workspace\n\nThrowaway workspace of the goversbx e2e suite.\n");
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        Sbx.Result result = sbx.run(Duration.ofMinutes(15), createArgs(config, name, agent, kit, workspace));
        String note = null;
        if (!result.ok() && workspace != null && result.output().contains("mount policy denied")) {
            note = "sbx refused the workspace bind mount (" + firstLine(result.output(), "mount policy denied")
                    + ") - created without a workspace instead";
            System.out.println("    NOTE: " + note);
            workspace = null;
            result = sbx.run(Duration.ofMinutes(15), createArgs(config, name, agent, kit, workspace));
        }
        if (!result.ok()) {
            fail("sbx create " + name + " failed:\n" + result.output());
        }
        Sandbox sandbox = new Sandbox(config, sbx, name, agent, kit, workspace, note);
        Sbx.Result probe = sandbox.sh("echo e2e-ok");
        if (!probe.ok() || !probe.lastLine().equals("e2e-ok")) {
            fail("sbx exec " + name + " does not work right after create:\n" + probe.output());
        }
        return sandbox;
    }

    /** A sandbox someone else created (the launcher, in LauncherSequenceTest). */
    static Sandbox existing(E2eConfig config, Sbx sbx, String name, String agent, Path kit, Path workspace) {
        return new Sandbox(config, sbx, name, agent, kit, workspace, null);
    }

    /**
     * {@code sbx create} only - no {@code sbx exec} afterwards, so the sandbox has never run a command
     * when this returns (what `goversbx run` sees between create and attach). Returns the sandbox and
     * sbx's create output.
     */
    static Sandbox createWithoutStart(E2eConfig config, Sbx sbx, String name, String agent, Path kit, Path workspace,
                                      StringBuilder createOutput) {
        Sbx.Result result = sbx.run(Duration.ofMinutes(15), createArgs(config, name, agent, kit, workspace));
        if (!result.ok()) {
            fail("sbx create " + name + " failed:\n" + result.output());
        }
        createOutput.append(result.output());
        return new Sandbox(config, sbx, name, agent, kit, workspace, null);
    }

    private static List<String> createArgs(E2eConfig config, String name, String agent, Path kit, Path workspace) {
        List<String> args = new ArrayList<>(List.of("create", "--no-progress", "--kit", kit.toString()));
        if (config.sandboxApiPort != 8787) {              // the kits default to 8787
            args.add("--kit-arg");
            args.add(kit.getFileName() + ".daemonPort=" + config.sandboxApiPort);
        }
        args.addAll(List.of("--name", name, agent));
        if (workspace != null) {
            args.add(workspace.toString());
        }
        return args;
    }

    private Map<String, String> baseEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DAEMON_URL", config.daemonUrlFromSandbox());
        env.put("PLACEHOLDER", E2eConfig.PLACEHOLDER);
        return env;
    }

    /** Runs a POSIX sh snippet inside the sandbox with DAEMON_URL and PLACEHOLDER set. */
    Sbx.Result sh(String snippet) {
        return sh(Map.of(), Duration.ofMinutes(3), snippet);
    }

    Sbx.Result sh(Map<String, String> extraEnv, Duration timeout, String snippet) {
        Map<String, String> env = baseEnv();
        env.putAll(extraEnv);
        return sbx.exec(name, env, timeout, "sh", "-c", snippet);
    }

    /** `bash -lc` - the agent's login environment (PATH with claude etc.). */
    Sbx.Result bash(Map<String, String> extraEnv, Duration timeout, String snippet) {
        Map<String, String> env = baseEnv();
        env.putAll(extraEnv);
        return sbx.exec(name, env, timeout, "bash", "-lc", snippet);
    }

    /** HTTP status of GET https://host from inside the sandbox ("000" if the connection failed). */
    String httpStatus(String host) {
        Sbx.Result r = sh("curl -sS -o /dev/null -w '%{http_code}' --max-time 20 https://" + host + " 2>/dev/null || true");
        String code = r.lastLine();
        return code.matches("\\d{3}") ? code : "000";
    }

    /** First line of the body the proxy answers with for a blocked host (the 403 explanation). */
    String blockedMessage(String host) {
        return sh("curl -sS --max-time 20 https://" + host + " 2>&1 | head -1 || true").lastLine();
    }

    /** Polls until https://host answers 200 (policy changes take a moment to reach the proxy). */
    boolean becomesReachable(String host, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String code = httpStatus(host);
        while (!code.equals("200") && System.nanoTime() < deadline) {
            Daemon.sleep(2000);
            code = httpStatus(host);
        }
        return code.equals("200");
    }

    String daemonHealth() {
        return sh("curl -sS --max-time 10 \"$DAEMON_URL/api/health\" 2>&1 || true").output().strip();
    }

    boolean fileExists(String path) {
        return sh("test -e '" + path + "' && echo yes || echo no").lastLine().equals("yes");
    }

    String readFile(String path) {
        Sbx.Result r = sh("cat '" + path + "' 2>&1 || true");
        return r.output();
    }

    /** request_network_access(host, reason, timeoutSeconds) through the proxy, blocking up to timeoutSeconds. */
    ToolResult requestAccess(String host, int timeoutSeconds) {
        return callTool("request_network_access",
                "{\"host\":\"" + host + "\",\"reason\":\"goversbx e2e suite\",\"timeoutSeconds\":" + timeoutSeconds + "}",
                Duration.ofSeconds(timeoutSeconds + 60));
    }

    CompletableFuture<ToolResult> requestAccessAsync(String host, int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> requestAccess(host, timeoutSeconds));
    }

    ToolResult checkStatus(String requestId) {
        return callTool("check_network_access_status", "{\"requestId\":\"" + requestId + "\"}", Duration.ofSeconds(60));
    }

    ToolResult callTool(String tool, String argumentsJson, Duration timeout) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TOOL", tool);
        env.put("ARGS", argumentsJson);
        env.put("MAX_TIME", Long.toString(Math.max(30, timeout.toSeconds() - 30)));
        Sbx.Result r = sh(env, timeout, MCP_CALL);
        if (!r.ok()) {
            fail("MCP call from " + name + " failed (exit " + r.exit() + "):\n" + r.output());
        }
        ToolResult result = ToolResult.parse(r.output(), 2);
        System.out.println("    tool result: " + result);
        return result;
    }

    /** `sbx rm --force`; the sandbox-scoped identity secret goes with the sandbox (best effort either way). */
    void remove() {
        sbx.run("secret", "rm", "--sandbox", name, "--placeholder", E2eConfig.PLACEHOLDER, "-f");
        Sbx.Result r = sbx.rmForce(name);
        if (!r.ok()) {
            System.out.println("    WARNING: could not remove sandbox " + name + " - remove it by hand: sbx rm --force " + name);
        }
    }

    private static String firstLine(String text, String containing) {
        for (String line : text.split("\n")) {
            if (line.contains(containing)) {
                return line.strip();
            }
        }
        return text.strip();
    }

    @Override
    public String toString() {
        return name;
    }
}
