package run.fabian.daemon.networkaccess.domain;

import java.util.Locale;

/**
 * Why a requested host points at the daemon's own admin API and must be rejected (threat-model F3).
 * A sandbox could otherwise ask a human to approve the daemon's own admin port and - since the
 * loopback admin IS reachable from a sandbox via the proxy (confirmed,
 * {@code scripts/diagnose-admin-reachability.sh}) - then self-approve everything. A bare host-local
 * alias with no port is also refused, since that can mean "all ports" and would include the admin
 * port. Legitimate host-service access (e.g. {@code host.docker.internal:3000}) is unaffected: it
 * names a specific, non-admin port. Wildcards, URLs and IP literals are {@link HostValidation}'s
 * job (F2), not this guard's. Pure, like {@link HostValidation}; the admin port is passed in by
 * whoever knows it.
 */
public final class LocalTargetGuard {

    private LocalTargetGuard() {
    }

    /** A human-readable reason the host must be refused, or {@code null} if it's acceptable. */
    public static String rejectionReason(String host, int selfAdminPort) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        String hostPart = h;
        int port = -1;
        if (h.startsWith("[")) {                                  // [ipv6] or [ipv6]:port
            int end = h.indexOf(']');
            if (end > 0) {
                hostPart = h.substring(1, end);
                if (end + 1 < h.length() && h.charAt(end + 1) == ':') {
                    port = parsePort(h.substring(end + 2));
                }
            }
        } else {
            int colon = h.indexOf(':');
            if (colon >= 0 && h.indexOf(':', colon + 1) < 0) {    // exactly one colon -> host:port
                hostPart = h.substring(0, colon);
                port = parsePort(h.substring(colon + 1));
            }
        }

        boolean hostLocalAlias = switch (hostPart) {
            case "localhost", "127.0.0.1", "::1",
                 "host.docker.internal", "gateway.docker.internal" -> true;
            default -> false;
        };
        if (hostLocalAlias && (port == selfAdminPort || port == -1)) {
            return "Requesting '" + host + "' is not allowed: it points at the goversbx daemon's own "
                    + "host-loopback, where the admin API lives. Request the specific external host and "
                    + "port your command needs instead.";
        }
        return null;
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
