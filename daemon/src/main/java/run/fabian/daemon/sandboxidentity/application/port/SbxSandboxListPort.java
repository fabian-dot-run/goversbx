package run.fabian.daemon.sandboxidentity.application.port;

import java.util.List;

/** Driven port for discovering currently-running sandboxes via {@code sbx ls}. */
public interface SbxSandboxListPort {

    List<String> listRunningSandboxNames();
}
