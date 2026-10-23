package run.fabian.daemon.projecthosts.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules of the host-set files, independent of their syntax (spec 0001, slice 1): one schema
 * version, profile names that are safe file names, a cap that keeps a set reviewable, and the
 * order in which profile and file hosts combine.
 */
public final class HostSetRules {

    /** Relative to a workspace: the project file. */
    public static final String PROJECT_FILE = ".goversbx/network.yaml";
    public static final int SCHEMA_VERSION = 1;
    /** More than this is not a set a human reviews, it's a set they skim. */
    public static final int MAX_HOSTS = 100;
    public static final long MAX_FILE_BYTES = 64 * 1024;
    public static final Pattern PROFILE_NAME = Pattern.compile("[a-z0-9-]{1,64}");

    private HostSetRules() {
    }

    /** Profile hosts first, in profile order, then the file's own hosts; each host once. */
    public static List<String> combine(List<List<String>> profileHosts, List<String> fileHosts) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        profileHosts.forEach(all::addAll);
        all.addAll(fileHosts);
        return new ArrayList<>(all);
    }

    public static boolean isValidProfileName(String name) {
        return name != null && PROFILE_NAME.matcher(name).matches();
    }
}
