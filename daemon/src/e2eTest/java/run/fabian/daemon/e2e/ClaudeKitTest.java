package run.fabian.daemon.e2e;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What `sbx create --kit goversbx-claude` must have done inside the sandbox - nothing here was
 * installed by hand. The sandbox's own environment is the oracle (files, `claude mcp list`).
 */
@Order(2)
@ExtendWith(E2eSuite.class)
class ClaudeKitTest {

    private final E2eEnvironment env = E2eEnvironment.get();

    @Test
    void kitAllowListLetsBothSandboxesReachTheDaemon() {
        for (Sandbox sandbox : new Sandbox[] {env.a, env.b}) {
            String health = sandbox.daemonHealth();
            assertTrue(health.contains("\"ok\""), sandbox + " cannot reach " + env.config.daemonUrlFromSandbox()
                    + "/api/health through the kit's permissions.network.allow: " + health);
        }
    }

    @Test
    void sandboxTrafficGoesThroughTheSbxProxy() {
        // Without the proxy there is no header substitution - and no policy at all.
        String proxy = env.a.sh("echo \"${HTTP_PROXY:-${http_proxy:-unset}}\"").lastLine();
        assertTrue(!proxy.equals("unset") && proxy.startsWith("http"), "no HTTP_PROXY inside " + env.a + ": " + proxy);
    }

    @Test
    void kitRegisteredTheMcpEndpointWithClaude() {
        Sbx.Result list = env.a.bash(Map.of(), Duration.ofMinutes(2), "claude mcp list 2>&1");
        assertTrue(list.output().contains("goversbx-network"), "claude mcp list does not show goversbx-network:\n" + list.output());
        // `claude mcp list` also probes the server; on a reachable daemon it reports it connected.
        assertTrue(list.output().contains("Connected") && !list.output().contains("Failed to connect"),
                "claude could not connect to the daemon:\n" + list.output());
    }

    @Test
    void mcpRegistrationCarriesTheHardcodedPlaceholderHeaderAndTheDaemonUrl() {
        String claudeJson = env.a.readFile("/home/agent/.claude.json");
        assertTrue(claudeJson.contains(E2eConfig.PLACEHOLDER),
                "~/.claude.json does not carry the placeholder header literal:\n" + claudeJson);
        assertTrue(claudeJson.contains(env.config.daemonUrlFromSandbox() + "/mcp"),
                "~/.claude.json does not point at " + env.config.daemonUrlFromSandbox() + "/mcp (kit arg daemonPort not applied?):\n" + claudeJson);
    }

    @Test
    void kitInstalledTheSkill() {
        String skill = "/home/agent/.claude/skills/goversbx-network-access/SKILL.md";
        assertTrue(env.a.fileExists(skill), "skill missing at " + skill);
        assertTrue(env.a.readFile(skill).contains("request_network_access"), "skill file does not mention the tool");
    }

    @Test
    void kitWroteTheUserMemoryRule() {
        String memory = env.a.readFile("/home/agent/.claude/CLAUDE.md");
        assertTrue(memory.contains("<!-- goversbx:start -->") && memory.contains("request_network_access"),
                "~/.claude/CLAUDE.md has no goversbx rule - Claude would follow the sandbox's own 'ask the user to approve' text:\n" + memory);
    }
}
