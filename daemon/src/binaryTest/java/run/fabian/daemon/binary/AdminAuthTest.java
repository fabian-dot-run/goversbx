package run.fabian.daemon.binary;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The admin API is token-gated (threat-model F3); static files and /api/health stay public. */
class AdminAuthTest {

    private static DaemonProcess daemon;

    @BeforeAll
    static void start() {
        daemon = DaemonProcess.start(FakeSbx.create());
    }

    @AfterAll
    static void stop() {
        daemon.close();
    }

    private static int statusNoToken(String method, String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(daemon.admin(path)).timeout(Duration.ofSeconds(10));
        b = method.equals("POST")
                ? b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                : b.GET();
        return DaemonProcess.send(b.build()).statusCode();
    }

    private static int statusWithToken(String path) {
        return DaemonProcess.send(HttpRequest.newBuilder(daemon.admin(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + DaemonProcess.ADMIN_TOKEN).GET().build()).statusCode();
    }

    @Test
    void adminApiRejectsRequestsWithoutTheToken() {
        assertEquals(401, statusNoToken("GET", "/api/requests"));
        assertEquals(401, statusNoToken("POST", "/api/sandboxes/x/register"));
    }

    @Test
    void adminApiAcceptsTheToken() {
        assertEquals(200, statusWithToken("/api/requests"));
        // ?token= is accepted too (what the EventSource uses).
        assertEquals(200, DaemonProcess.send(HttpRequest.newBuilder(
                        URI.create(daemon.admin("/api/requests") + "?token=" + DaemonProcess.ADMIN_TOKEN))
                .timeout(Duration.ofSeconds(10)).GET().build()).statusCode());
    }

    @Test
    void healthAndStaticFilesStayPublic() {
        assertEquals(200, statusNoToken("GET", "/api/health"));
        HttpResponse<String> root = DaemonProcess.send(HttpRequest.newBuilder(daemon.admin("/"))
                .timeout(Duration.ofSeconds(10)).GET().build());
        assertEquals(200, root.statusCode());
    }
}
