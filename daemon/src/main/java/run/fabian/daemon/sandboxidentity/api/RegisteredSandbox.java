package run.fabian.daemon.sandboxidentity.api;

import java.util.List;

/**
 * A sandbox incarnation the daemon registered an identity for: its name, the id {@code sbx ls}
 * reports for this incarnation ({@code null} if sbx reported none), and the host directories
 * bind-mounted into it (where a project's host-set file may live).
 */
public record RegisteredSandbox(String name, String id, List<String> workspaces) {

    public RegisteredSandbox {
        workspaces = workspaces == null ? List.of() : List.copyOf(workspaces);
    }
}
