package run.fabian.daemon.projecthosts.domain;

import java.util.List;

/**
 * The project file (or a profile it names) cannot be turned into a host set. Carries every problem
 * found, not just the first, so the user can fix the file in one go. The file is the agent's to
 * write, so these never reach the agent - they go to the log and to the admin endpoint.
 */
public final class InvalidHostSetException extends RuntimeException {

    private final String source;
    private final List<String> problems;

    public InvalidHostSetException(String source, List<String> problems) {
        super(source + ": " + String.join("; ", problems));
        this.source = source;
        this.problems = List.copyOf(problems);
    }

    public String source() {
        return source;
    }

    public List<String> problems() {
        return problems;
    }
}
