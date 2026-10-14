package run.fabian.mcpserver;

import run.fabian.common.JsonSupport;
import run.fabian.common.NetworkAccessRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class DaemonClient {

    private final URI baseUri;
    private final String sandboxName;
    private final HttpClient httpClient;

    public DaemonClient(String daemonUrl, String sandboxName) {
        this.baseUri = URI.create(daemonUrl);
        this.sandboxName = sandboxName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public NetworkAccessRequest createRequest(String host, String reason) throws IOException, InterruptedException {
        Map<String, String> body = Map.of(
                "sandboxName", sandboxName,
                "host", host,
                "reason", reason == null ? "" : reason);

        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/requests"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.MAPPER.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(15))
                .build();

        return send(request);
    }

    public NetworkAccessRequest awaitDecision(String requestId, int timeoutSeconds) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        baseUri.resolve("/api/requests/" + requestId + "/await?timeoutSeconds=" + timeoutSeconds))
                .GET()
                // client-side timeout must exceed the server-side long-poll timeout
                .timeout(Duration.ofSeconds(timeoutSeconds + 15L))
                .build();

        return send(request);
    }

    public NetworkAccessRequest getStatus(String requestId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/requests/" + requestId))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        return send(request);
    }

    private NetworkAccessRequest send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("daemon returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return JsonSupport.MAPPER.readValue(response.body(), NetworkAccessRequest.class);
    }
}
