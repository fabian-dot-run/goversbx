package run.fabian.daemon.projecthosts.application;

import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;

import java.util.List;

/**
 * What evaluating one workspace of a sandbox came to. {@code request} is set for {@code CREATED}
 * and {@code ALREADY_PENDING}; {@code problems} for {@code INVALID}.
 */
public record HostSetEvaluation(String workspace, Kind kind, NetworkAccessRequestView request, List<String> problems) {

    public enum Kind {
        CREATED,
        ALREADY_PENDING,
        ALL_GRANTED,
        NO_FILE,
        INVALID
    }

    public HostSetEvaluation {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    static HostSetEvaluation of(String workspace, Kind kind) {
        return new HostSetEvaluation(workspace, kind, null, List.of());
    }

    static HostSetEvaluation withRequest(String workspace, Kind kind, NetworkAccessRequestView request) {
        return new HostSetEvaluation(workspace, kind, request, List.of());
    }

    static HostSetEvaluation invalid(String workspace, List<String> problems) {
        return new HostSetEvaluation(workspace, Kind.INVALID, null, problems);
    }
}
