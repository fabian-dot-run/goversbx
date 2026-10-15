package run.fabian.daemon.mcp.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The host guard that closes threat-model F3's goversbx-mediated path (and wildcard grants). */
class RequestNetworkAccessToolTest {

    private static final int ADMIN_PORT = 8788;

    @Test
    void allowsOrdinaryExternalHosts() {
        assertNull(RequestNetworkAccessTool.targetRejectionReason("api.example.com", ADMIN_PORT));
        assertNull(RequestNetworkAccessTool.targetRejectionReason("www.example.com:443", ADMIN_PORT));
        // A legitimate host-service grant names a specific, non-admin port.
        assertNull(RequestNetworkAccessTool.targetRejectionReason("host.docker.internal:3000", ADMIN_PORT));
        assertNull(RequestNetworkAccessTool.targetRejectionReason("localhost:3000", ADMIN_PORT));
        // The admin port on an EXTERNAL host is fine - the admin API is only on the host's loopback.
        assertNull(RequestNetworkAccessTool.targetRejectionReason("example.com:8788", ADMIN_PORT));
    }

    @Test
    void rejectsTheDaemonsOwnAdminPortOnEveryHostLocalAlias() {
        assertNotNull(RequestNetworkAccessTool.targetRejectionReason("localhost:8788", ADMIN_PORT));
        assertNotNull(RequestNetworkAccessTool.targetRejectionReason("127.0.0.1:8788", ADMIN_PORT));
        assertNotNull(RequestNetworkAccessTool.targetRejectionReason("host.docker.internal:8788", ADMIN_PORT));
        assertNotNull(RequestNetworkAccessTool.targetRejectionReason("gateway.docker.internal:8788", ADMIN_PORT));
        assertNotNull(RequestNetworkAccessTool.targetRejectionReason("[::1]:8788", ADMIN_PORT));
        assertNotNull(RequestNetworkAccessTool.targetRejectionReason("HOST.DOCKER.INTERNAL:8788", ADMIN_PORT));
    }

    @Test
    void rejectsABareHostLocalAliasWithNoPortBecauseItCanMeanAllPorts() {
        assertNotNull(RequestNetworkAccessTool.targetRejectionReason("localhost", ADMIN_PORT));
        assertNotNull(RequestNetworkAccessTool.targetRejectionReason("127.0.0.1", ADMIN_PORT));
        assertNotNull(RequestNetworkAccessTool.targetRejectionReason("host.docker.internal", ADMIN_PORT));
    }

    // Wildcards, URLs and IP literals are HostValidation's job (F2), checked before this guard runs;
    // see HostValidationTest. This guard is only about the daemon's own admin port (F3).
}
