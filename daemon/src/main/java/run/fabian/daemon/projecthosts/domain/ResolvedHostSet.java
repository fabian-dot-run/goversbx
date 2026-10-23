package run.fabian.daemon.projecthosts.domain;

import java.util.List;

/**
 * The hosts a project file asks for, after its profiles were pulled in and duplicates dropped:
 * profile hosts first (in profile order), then the file's own hosts. {@code source} is the project
 * file's path, which the approver sees; {@code profiles} the names it referenced.
 */
public record ResolvedHostSet(List<String> hosts, String source, List<String> profiles) {

    public ResolvedHostSet {
        hosts = List.copyOf(hosts);
        profiles = List.copyOf(profiles);
    }
}
