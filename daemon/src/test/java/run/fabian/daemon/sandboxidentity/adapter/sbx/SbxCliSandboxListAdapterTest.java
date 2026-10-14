package run.fabian.daemon.sandboxidentity.adapter.sbx;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fixture below mirrors the real {@code sbx ls --format json} output shape the user shared
 * directly (snake_case fields, array returned directly, "workspaces" as an array) - see
 * docs/plan.md's third 2026-10-14 revision. Only fields we actually consume are asserted on, but
 * the fixture keeps the full real shape (including fields we ignore) so a future schema change
 * that silently drops/renames "name" or "status" would still be caught.
 */
class SbxCliSandboxListAdapterTest {

    private static final String SAMPLE_JSON = """
            [
              {
                "name": "claude-goversbx",
                "id": "defaed17-e79e-4a11-bb65-5c1c527ee557",
                "agent": "claude",
                "status": "running",
                "last_used_at": "2026-10-14T16:00:00.000000Z",
                "workspaces": ["/Users/fabian/IdeaProjects/goversbx"],
                "created_at": "2026-10-14T12:54:00Z"
              },
              {
                "name": "omp-secret-manager",
                "id": "4bc520bc-c650-4304-b892-3ec9e1819d9f",
                "agent": "omp",
                "status": "running",
                "last_used_at": "2026-10-14T15:56:27.953665Z",
                "workspaces": [
                  "/Users/fabian/secret_manager",
                  "/Users/fabian/.omp",
                  "/Users/fabian/.config/gh"
                ],
                "created_at": "2026-10-11T07:28:46Z"
              },
              {
                "name": "old-stopped-sandbox",
                "id": "11111111-1111-1111-1111-111111111111",
                "agent": "claude",
                "status": "stopped",
                "last_used_at": "2026-10-01T00:00:00Z",
                "workspaces": [],
                "created_at": "2026-09-30T00:00:00Z"
              }
            ]
            """;

    @Test
    void parsesOnlyRunningSandboxNames() throws Exception {
        List<String> names = SbxCliSandboxListAdapter.parseRunningSandboxNames(SAMPLE_JSON, new ObjectMapper());

        assertEquals(List.of("claude-goversbx", "omp-secret-manager"), names);
    }
}
