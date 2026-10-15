package run.fabian.daemon.networkaccess.domain;

/**
 * What counts as a well-formed, safe host to request a grant for (threat-model F2). Pure, no
 * framework or config dependencies - a domain invariant, enforced at
 * {@code NetworkAccessService.createRequest} so it holds on every path, and also called by the MCP
 * tool to give the agent a clean error.
 *
 * <p>The requested host flows verbatim into {@code sbx policy allow network <host>}, and it is the
 * only factual field an approver sees besides the (now verified) sandbox name. So it must be a single
 * plain hostname, not a value sbx would read more broadly than the UI shows: no wildcards (a {@code **}
 * catch-all grants everything), no scheme/path (a URL, not a host), no whitespace, and no IP literal -
 * an IP bypasses the human's sense of what is being allowed and is the usual way to reach cloud
 * metadata (169.254.169.254) or internal ranges. An optional numeric port is allowed for host-service
 * access; the daemon's own admin port on a host-local alias is rejected separately by the MCP tool
 * (F3), which is the layer that knows that port.
 */
public final class HostValidation {

    private HostValidation() {
    }

    /** A human-readable reason the host must be refused, or {@code null} if it's acceptable. */
    public static String rejectionReason(String host) {
        if (host == null || host.isBlank()) {
            return "A host is required.";
        }
        String h = host.trim();
        for (int i = 0; i < h.length(); i++) {
            if (h.charAt(i) <= ' ') {
                return "Host '" + host + "' contains whitespace or control characters - request a single hostname.";
            }
        }
        if (h.contains("*")) {
            return "Wildcard hosts like '" + host + "' are not allowed - request one specific hostname, not a pattern.";
        }
        if (h.contains("://")) {
            return "Request a bare hostname, not a URL: '" + host + "' includes a scheme (drop the http(s)://).";
        }
        if (h.indexOf('/') >= 0) {
            return "Request a bare hostname, not a URL or path: '" + host + "' contains '/'.";
        }

        String hostPart;
        String portPart = null;
        if (h.startsWith("[")) {                                   // bracketed IPv6 is always an IP literal
            return ipReason(host);
        }
        int firstColon = h.indexOf(':');
        if (firstColon < 0) {
            hostPart = h;
        } else if (h.indexOf(':', firstColon + 1) >= 0) {         // more than one colon -> bare IPv6
            return ipReason(host);
        } else {                                                   // exactly one colon -> host:port
            hostPart = h.substring(0, firstColon);
            portPart = h.substring(firstColon + 1);
        }

        if (portPart != null) {
            if (!portPart.matches("\\d{1,5}")) {
                return "Host '" + host + "' has a non-numeric port.";
            }
            int port = Integer.parseInt(portPart);
            if (port < 1 || port > 65535) {
                return "Host '" + host + "' has a port outside 1-65535.";
            }
        }

        if (looksLikeIpv4(hostPart)) {
            return ipReason(host);
        }
        if (!isValidHostname(hostPart)) {
            return "Host '" + host + "' is not a valid hostname.";
        }
        return null;
    }

    private static String ipReason(String host) {
        return "Request a hostname, not an IP address ('" + host + "'): an IP hides from the approver "
                + "what is being allowed and can point at internal or cloud-metadata ranges.";
    }

    private static boolean looksLikeIpv4(String s) {
        return s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static boolean isValidHostname(String s) {
        if (s.isEmpty() || s.length() > 253) {
            return false;
        }
        for (String label : s.split("\\.", -1)) {
            if (!label.matches("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) {
                return false;
            }
        }
        return true;
    }
}
