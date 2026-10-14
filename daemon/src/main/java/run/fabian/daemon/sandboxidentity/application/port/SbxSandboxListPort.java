package run.fabian.daemon.sandboxidentity.application.port;

import java.util.List;

/** Driven port for discovering currently-running sandboxes via {@code sbx ls}. */
public interface SbxSandboxListPort {

    List<RunningSandbox> listRunningSandboxes();

    /**
     * One running sandbox. {@code id} changes when a sandbox is removed and created again under the
     * same name - which also drops its sandbox-scoped secrets - so it is what tells two
     * incarnations of a name apart. May be {@code null} if {@code sbx ls} doesn't report one.
     */
    record RunningSandbox(String name, String id) {
    }
}
