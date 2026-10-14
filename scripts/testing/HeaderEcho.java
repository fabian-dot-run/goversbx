import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tiny header-echo server for scripts/diagnose-substitution.sh: answers every request with the
 * method, path and request headers it received, as JSON, so the diagnostic can see exactly which
 * placeholders the sbx proxy substituted on the way in. Run as a single source file:
 *
 *   java scripts/testing/HeaderEcho.java 8799
 */
public final class HeaderEcho {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8799;
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(exchange.getRequestHeaders());
            StringBuilder json = new StringBuilder("{\"method\":\"").append(exchange.getRequestMethod())
                    .append("\",\"path\":\"").append(escape(exchange.getRequestURI().toString()))
                    .append("\",\"headers\":{");
            boolean first = true;
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(escape(e.getKey().toLowerCase())).append("\":\"")
                        .append(escape(String.join(", ", e.getValue()))).append('"');
            }
            json.append("}}\n");
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            System.out.print(json);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("HeaderEcho listening on " + port);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
