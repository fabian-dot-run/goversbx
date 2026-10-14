package run.fabian.daemon.binary;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every file of the admin UI, byte for byte: these are classpath resources, which a native image
 * only contains if its resource metadata says so.
 */
class AdminUiTest {

    private static DaemonProcess daemon;

    @BeforeAll
    static void start() {
        daemon = DaemonProcess.start(FakeSbx.create());
    }

    @AfterAll
    static void stop() {
        daemon.close();
    }

    static Stream<Path> publicFiles() throws IOException {
        Path dir = Path.of(System.getProperty("goversbx.publicDir"));
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).map(dir::relativize).toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("publicFiles")
    void servesEveryFileUnchanged(Path relative) throws Exception {
        byte[] expected = Files.readAllBytes(Path.of(System.getProperty("goversbx.publicDir")).resolve(relative));
        HttpResponse<byte[]> response = DaemonProcess.HTTP.send(
                HttpRequest.newBuilder(daemon.admin("/" + relative.toString().replace('\\', '/'))).timeout(java.time.Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), relative.toString());
        assertArrayEquals(expected, response.body(), relative.toString());
        String type = response.headers().firstValue("Content-Type").orElse("");
        String name = relative.getFileName().toString();
        if (name.endsWith(".js")) assertTrue(type.contains("javascript"), name + " -> " + type);
        if (name.endsWith(".css")) assertTrue(type.startsWith("text/css"), name + " -> " + type);
        if (name.endsWith(".html")) assertTrue(type.startsWith("text/html"), name + " -> " + type);
    }

    @Test
    void rootServesIndexHtml() throws Exception {
        byte[] index = Files.readAllBytes(Path.of(System.getProperty("goversbx.publicDir"), "index.html"));
        HttpResponse<byte[]> response = DaemonProcess.HTTP.send(HttpRequest.newBuilder(daemon.admin("/")).timeout(java.time.Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(index, response.body());
    }

    @Test
    void unknownFileIs404() {
        assertEquals(404, daemon.get(daemon.admin("/does-not-exist.js")).statusCode());
    }
}
