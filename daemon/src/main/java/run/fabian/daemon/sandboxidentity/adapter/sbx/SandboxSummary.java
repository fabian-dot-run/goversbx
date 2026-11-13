package run.fabian.daemon.sandboxidentity.adapter.sbx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal shape of one entry in {@code sbx ls --format json}'s output array. Only the fields we
 * actually use are declared; the rest (agent, last_used_at, workspaces, created_at, ...) are
 * ignored. Confirmed against real output shared by the user on 2026-10-14 - snake_case fields,
 * array returned directly (not wrapped in {"sandboxes": [...]}), per sbx's own v0.48.0 changelog.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SandboxSummary(String name, String id, String status) {
}
