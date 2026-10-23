package run.fabian.daemon.networkaccess.application.port;

import java.util.ArrayList;
import java.util.List;

/** Driven port for actually granting network access to a sandbox via {@code sbx}. */
public interface SbxPolicyPort {

    Result allowNetwork(String sandboxName, String host);

    /**
     * Grants several hosts, one result per host in the same order. The default applies them one by
     * one; an adapter may override it with a batch form of the sbx command once that is verified.
     */
    default List<Result> allowNetwork(String sandboxName, List<String> hosts) {
        List<Result> results = new ArrayList<>(hosts.size());
        for (String host : hosts) {
            results.add(allowNetwork(sandboxName, host));
        }
        return results;
    }

    record Result(boolean applied, String output) {
    }
}
