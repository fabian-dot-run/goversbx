package run.fabian.daemon.sbxjson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What the launcher needs to know from sbx's JSON output (spec 0003 slice 4): which sandboxes have a
 * directory as their workspace ({@code sbx ls --format json}) and what one sandbox is made of
 * ({@code sbx inspect NAME --format json}). Read through Jackson's tree model on purpose - no
 * reflection on own types, so the native image needs no metadata for it - and only the fields that
 * are needed: sbx adds fields in every release.
 */
public final class SbxJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** What a goversbx kit entry's path ends in (the same rule as in the user defaults). */
    private static final String KIT_SUFFIX = "/kits/goversbx";

    /** sbx printed something this class cannot use. */
    public static final class UnreadableException extends RuntimeException {
        public UnreadableException(String message) {
            super(message);
        }
    }

    private SbxJson() {
    }

    /**
     * The sandboxes that have one of {@code dirs} itself among their workspaces - not a parent of it,
     * not a subdirectory. One line per sandbox: name, agent, status, tab-separated.
     */
    public static List<String> workspaceSandboxes(String lsJson, Collection<String> dirs) {
        JsonNode rows = parse(lsJson);
        if (!rows.isArray()) {
            throw new UnreadableException("sbx ls did not print a JSON list");
        }
        List<String> wanted = dirs.stream().map(SbxJson::normalize).toList();
        List<String> lines = new ArrayList<>();
        for (JsonNode row : rows) {
            for (JsonNode workspace : row.path("workspaces")) {
                if (wanted.contains(normalize(hostPath(workspace.asText())))) {
                    lines.add(row.path("name").asText() + "\t" + row.path("agent").asText("?") + "\t" + row.path("status").asText("?"));
                    break;
                }
            }
        }
        return lines;
    }

    /**
     * One sandbox, as {@code key=value} lines: {@code kit} is {@code this} (the goversbx kit of
     * {@code kitDir}), {@code other} (a goversbx kit from elsewhere; {@code kit_path} follows) or
     * {@code none}; {@code created_version} is {@code none} for a sandbox created by sbx &lt; 0.51;
     * {@code env} says whether an sbxenv.yaml is mounted, i.e. whether {@code sbx env} manages it.
     */
    public static List<String> sandbox(String inspectJson, String kitDir) {
        JsonNode sandbox = parse(inspectJson);
        if (!sandbox.isObject() || !sandbox.hasNonNull("name")) {
            throw new UnreadableException("sbx inspect did not print a sandbox");
        }
        String kit = "none";
        String kitPath = null;
        for (JsonNode entry : sandbox.path("kits")) {
            String path = normalize(entry.asText());
            if (path.endsWith(KIT_SUFFIX)) {
                kitPath = path;
                kit = path.equals(normalize(kitDir)) ? "this" : "other";
                if (kit.equals("this")) {
                    break;
                }
            }
        }
        boolean env = false;
        for (JsonNode mount : sandbox.path("host_mounts")) {
            env |= mount.path("host_path").asText().endsWith("/sbxenv.yaml");
        }
        List<String> lines = new ArrayList<>();
        lines.add("name=" + sandbox.path("name").asText());
        lines.add("kit=" + kit);
        if (kitPath != null) {
            lines.add("kit_path=" + kitPath);
        }
        lines.add("created_version=" + sandbox.path("created_version").asText("none"));
        lines.add("state=" + sandbox.path("state").asText("unknown"));
        lines.add("sessions=" + sandbox.path("sessions").asInt(0));
        lines.add("env=" + (env ? "yes" : "no"));
        return lines;
    }

    private static JsonNode parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || node.isMissingNode()) {
                throw new UnreadableException("sbx printed nothing");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new UnreadableException("sbx did not print JSON: " + e.getOriginalMessage());
        }
    }

    /** A workspace is listed as its host path, read-only ones with a {@code :ro} suffix. */
    private static String hostPath(String workspace) {
        return workspace.endsWith(":ro") ? workspace.substring(0, workspace.length() - 3) : workspace;
    }

    private static String normalize(String path) {
        String result = path;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
