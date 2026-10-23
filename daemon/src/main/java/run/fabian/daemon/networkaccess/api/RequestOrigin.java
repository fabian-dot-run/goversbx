package run.fabian.daemon.networkaccess.api;

/** Who asked for a request: the agent in the sandbox (one host), or the project's host-set file. */
public enum RequestOrigin {
    AGENT,
    PROJECT_FILE
}
