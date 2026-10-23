package run.fabian.daemon.projecthosts.application.port;

import run.fabian.daemon.projecthosts.domain.InvalidHostSetException;
import run.fabian.daemon.projecthosts.domain.ResolvedHostSet;

import java.nio.file.Path;
import java.util.Optional;

/** Driven port: reads a workspace's project file and the profiles it names. */
public interface HostSetSource {

    /**
     * The resolved host set of the project file in {@code workspace}, or empty if the workspace has
     * no project file (which is normal, not an error).
     *
     * @throws InvalidHostSetException if the file or a profile it names is malformed
     */
    Optional<ResolvedHostSet> resolve(Path workspace);
}
