package run.fabian.daemon.e2e;

import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The outcome of one MCP {@code tools/call} made from inside a sandbox: the first text content and
 * the {@code isError} flag, parsed from either a plain JSON-RPC response or the SSE framing the
 * Streamable HTTP transport may use.
 */
record ToolResult(String text, boolean isError, String raw) {

    static ToolResult parse(String body, long rpcId) {
        String trimmed = body.strip();
        if (trimmed.isEmpty() || trimmed.equals("NO-SESSION")) {
            return fail("MCP call produced no response (" + (trimmed.isEmpty() ? "empty body" : "initialize returned no session id")
                    + ") - is the daemon reachable from the sandbox?");
        }
        JsonNode message = null;
        if (trimmed.startsWith("{")) {
            message = Daemon.parse(trimmed);
        } else {
            for (String line : trimmed.split("\n")) {
                if (line.startsWith("data:")) {
                    JsonNode candidate = Daemon.parse(line.substring(5).strip());
                    if (candidate.path("id").asLong(-1) == rpcId) {
                        message = candidate;
                    }
                }
            }
        }
        if (message == null) {
            return fail("no JSON-RPC response with id " + rpcId + " in: " + body);
        }
        if (message.has("error")) {
            return new ToolResult(message.path("error").toString(), true, body);
        }
        JsonNode content = message.path("result").path("content");
        if (!content.isArray() || content.isEmpty()) {
            return fail("no content in tool result: " + message);
        }
        return new ToolResult(content.get(0).path("text").asText(), message.path("result").path("isError").asBoolean(false), body);
    }

    boolean says(String needle) {
        return text.contains(needle);
    }

    /** The requestId the daemon appends to every Approved/Denied/Still pending answer. */
    String requestId() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("requestId=\"?([0-9a-f-]{36})").matcher(text);
        return m.find() ? m.group(1) : fail("no requestId in tool result: " + text);
    }

    @Override
    public String toString() {
        return (isError ? "[error] " : "") + text;
    }
}
