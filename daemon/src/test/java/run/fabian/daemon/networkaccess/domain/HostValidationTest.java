package run.fabian.daemon.networkaccess.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Host input validation for network-access requests (threat-model F2). */
class HostValidationTest {

    @Test
    void acceptsPlainHostnames() {
        assertNull(HostValidation.rejectionReason("api.example.com"));
        assertNull(HostValidation.rejectionReason("www.spiegel.de"));
        assertNull(HostValidation.rejectionReason("example.com"));
        assertNull(HostValidation.rejectionReason("intranet"));
        assertNull(HostValidation.rejectionReason("a-b.c-d.example.com"));
    }

    @Test
    void acceptsAHostnameWithAValidPort() {
        assertNull(HostValidation.rejectionReason("host.docker.internal:3000"));
        assertNull(HostValidation.rejectionReason("api.example.com:443"));
    }

    @Test
    void rejectsWildcards() {
        assertNotNull(HostValidation.rejectionReason("**"));
        assertNotNull(HostValidation.rejectionReason("*"));
        assertNotNull(HostValidation.rejectionReason("*.example.com"));
    }

    @Test
    void rejectsUrlsAndPaths() {
        assertNotNull(HostValidation.rejectionReason("https://example.com"));
        assertNotNull(HostValidation.rejectionReason("example.com/path"));
        assertNotNull(HostValidation.rejectionReason("example.com/"));
    }

    @Test
    void rejectsIpLiterals() {
        assertNotNull(HostValidation.rejectionReason("93.184.216.34"));       // public IPv4
        assertNotNull(HostValidation.rejectionReason("169.254.169.254"));      // cloud metadata
        assertNotNull(HostValidation.rejectionReason("127.0.0.1"));
        assertNotNull(HostValidation.rejectionReason("10.0.0.5:8080"));
        assertNotNull(HostValidation.rejectionReason("::1"));
        assertNotNull(HostValidation.rejectionReason("[::1]:8788"));
        assertNotNull(HostValidation.rejectionReason("fd00::1"));
    }

    @Test
    void rejectsWhitespaceBlankAndMalformed() {
        assertNotNull(HostValidation.rejectionReason(null));
        assertNotNull(HostValidation.rejectionReason(""));
        assertNotNull(HostValidation.rejectionReason("   "));
        assertNotNull(HostValidation.rejectionReason("foo bar.com"));
        assertNotNull(HostValidation.rejectionReason("-leadinghyphen.com"));
        assertNotNull(HostValidation.rejectionReason("double..dot.com"));
        assertNotNull(HostValidation.rejectionReason("example.com:0"));
        assertNotNull(HostValidation.rejectionReason("example.com:notaport"));
        assertNotNull(HostValidation.rejectionReason("example.com:99999"));
    }
}
