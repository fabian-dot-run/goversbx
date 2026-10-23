package run.fabian.daemon.sandboxidentity.application.port;

import java.util.List;

/** Driven port for discovering sandboxes via {@code sbx ls}. */
public interface SbxSandboxListPort {

    List<ListedSandbox> listSandboxes();

    /**
     * One sandbox as {@code sbx ls} reports it. {@code id} changes when a sandbox is removed and
     * created again under the same name - which also drops its sandbox-scoped secrets - so it is
     * what tells two incarnations of a name apart. May be {@code null} if {@code sbx ls} doesn't
     * report one. {@code workspaces} are the host directories bind-mounted into the sandbox, where
     * a project file may live; empty if none or not reported.
     */
    record ListedSandbox(String name, String id, boolean running, List<String> workspaces) {

        public ListedSandbox {
            workspaces = workspaces == null ? List.of() : List.copyOf(workspaces);
        }

        /** A running sandbox without workspaces (tests). */
        public ListedSandbox(String name, String id) {
            this(name, id, true, List.of());
        }
    }
}
