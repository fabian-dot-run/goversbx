package run.fabian.daemon.userdefaults;

import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What goversbx owns in sbx's user defaults ({@code ~/.sbxenv.yaml}): exactly one {@code kits}
 * entry pointing at the installed goversbx kit and, when the file has none, the {@code agent}.
 * Pure text in, text out (spec 0003 slice 3, D2).
 *
 * <p>The file is the user's. It is parsed only to find positions; every change is a splice into the
 * original text, so comments, ordering, quoting and blank lines of everything else survive byte for
 * byte. A layout this class cannot splice safely is refused ({@link UnusableFileException}), never
 * rewritten, and every result is parsed again and must come out clean before it is returned.
 */
public final class UserDefaultsEditor {

    public static final int DEFAULT_PORT = 8787;
    /** What a goversbx kit entry's path ends in - the rule that identifies "our" entry. */
    static final String KIT_SUFFIX = "/kits/goversbx";
    private static final Pattern PLAIN_SAFE = Pattern.compile("[A-Za-z0-9/_.][A-Za-z0-9/_.~+-]*");

    /** The file cannot be edited (not YAML, or a shape goversbx will not touch). Nothing is written. */
    public static final class UnusableFileException extends RuntimeException {
        public UnusableFileException(String message) {
            super(message);
        }
    }

    /**
     * @param text         the file's new content, or {@code null} when nothing has to change
     * @param findings     one line per thing that was (or, when checking, would be) changed
     * @param notes        lines that are not drift: {@code warning: ...} and {@code note: ...}
     * @param agentMissing the file has no agent and none was given - nothing was planned
     */
    public record Outcome(String text, List<String> findings, List<String> notes, boolean agentMissing) {
        public boolean changed() {
            return text != null;
        }
    }

    private record Edit(int start, int end, String replacement, int order) {
    }

    private final String kitDir;
    private final int port;
    private final String agent;

    /**
     * @param kitDir the kit directory the entry must point at
     * @param port   the daemon's sandbox-facing port; {@code args.daemonPort} exists only when it is not 8787
     * @param agent  the agent to write when the file has none, or {@code null}
     */
    public UserDefaultsEditor(String kitDir, int port, String agent) {
        this.kitDir = kitDir;
        this.port = port;
        this.agent = agent;
    }

    /** The content of a user defaults file that does not exist yet. */
    public Outcome create() {
        if (agent == null) {
            return new Outcome(null, List.of("no default agent"), List.of(), true);
        }
        String text = "schemaVersion: \"1\"\n"
                + "agent: " + scalar(agent) + "\n"
                + "workspace: ${{ env.projectDir }}\n"
                + "kits:\n" + entryLines("  ", "    ");
        return new Outcome(text, List.of("created with agent " + agent + " and the goversbx kit"), List.of(), false);
    }

    /** What has to change in an existing file, and the changed text. */
    public Outcome edit(String original) {
        Outcome outcome = plan(original);
        if (outcome.changed()) {
            Outcome again = plan(outcome.text());
            if (again.changed() || !again.findings().isEmpty()) {
                throw new UnusableFileException("goversbx cannot edit this file safely (its own change did not parse back as intended: "
                        + again.findings() + ") - add the kit entry by hand: kits: [ {source: " + kitDir + "} ]");
            }
        }
        return outcome;
    }

    /** The agent the file names, if any. */
    public static Optional<String> agentOf(String text) {
        return Optional.ofNullable(scalarValue(valueOf(root(text), "agent")));
    }

    /** The sources of every {@code kits} entry of an sbxenv file, in order. */
    public static List<String> kitSources(String text) {
        List<String> sources = new ArrayList<>();
        if (valueOf(root(text), "kits") instanceof SequenceNode kits) {
            for (Node item : kits.getValue()) {
                String source = sourceOf(item);
                if (source != null) {
                    sources.add(source);
                }
            }
        }
        return sources;
    }

    static boolean isGoversbxKit(String source) {
        String path = source;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.endsWith(KIT_SUFFIX);
    }

    // ---- planning ----

    private Outcome plan(String original) {
        Text t = new Text(original);
        MappingNode root = root(original);
        List<String> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<Edit> edits = new ArrayList<>();
        StringBuilder appended = new StringBuilder();
        String topIndent = root == null || root.getValue().isEmpty() ? ""
                : " ".repeat(t.column(t.start(root.getValue().get(0).getKeyNode())));

        if (root == null) {
            appended.append("schemaVersion: \"1\"\n");
        }

        String existingAgent = scalarValue(valueOf(root, "agent"));
        if (existingAgent == null || existingAgent.isBlank()) {
            if (valueOf(root, "agent") != null) {
                throw new UnusableFileException("'agent' is there but empty - set it by hand");
            }
            if (agent == null) {
                return new Outcome(null, List.of("no default agent"), List.of(), true);
            }
            appended.append(topIndent).append("agent: ").append(scalar(agent)).append('\n');
            findings.add("default agent set to " + agent);
        } else if (agent != null && !agent.equals(existingAgent)) {
            notes.add("note: the default agent stays " + existingAgent + " (not changed to " + agent + ")");
        }

        Node kits = valueOf(root, "kits");
        if (kits == null) {
            appended.append(topIndent).append("kits:\n").append(entryLines(topIndent + "  ", topIndent + "    "));
            findings.add("goversbx kit added");
        } else if (isNull(kits)) {
            Node key = keyOf(root, "kits");
            String indent = " ".repeat(t.column(t.start(key)) + 2);
            edits.add(new Edit(t.afterLine(t.end(key)), t.afterLine(t.end(key)), t.newlineIfMissing(t.end(key)) + entryLines(indent, indent + "  "), 0));
            findings.add("goversbx kit added");
        } else if (kits instanceof SequenceNode sequence) {
            planKits(t, sequence, edits, findings);
        } else {
            throw new UnusableFileException("'kits' is not a list");
        }

        if (valueOf(root, "workspace") == null && root != null) {
            notes.add("warning: no 'workspace' here - a project without its own sbxenv.yaml gets a sandbox without a mount"
                    + " (add: workspace: ${{ env.projectDir }})");
        }

        if (appended.length() > 0) {
            if (root != null && root.getFlowStyle() == FlowStyle.FLOW) {
                throw new UnusableFileException("the file is one flow mapping ({...}) - goversbx cannot add keys to it");
            }
            String separator = original.isEmpty() || original.endsWith("\n") ? "" : "\n";
            edits.add(new Edit(original.length(), original.length(), separator + appended, 1));
        }
        if (edits.isEmpty()) {
            return new Outcome(null, findings, notes, false);
        }
        return new Outcome(apply(original, edits), findings, notes, false);
    }

    private void planKits(Text t, SequenceNode kits, List<Edit> edits, List<String> findings) {
        boolean flow = kits.getFlowStyle() == FlowStyle.FLOW;
        List<Node> ours = new ArrayList<>();
        for (Node item : kits.getValue()) {
            String source = sourceOf(item);
            if (source != null && isGoversbxKit(source)) {
                ours.add(item);
            }
        }

        if (ours.isEmpty()) {
            if (flow) {
                String entry = port == DEFAULT_PORT ? quoted(kitDir)
                        : "{source: " + quoted(kitDir) + ", args: {daemonPort: \"" + port + "\"}}";
                int close = t.end(kits) - 1;
                edits.add(new Edit(close, close, (kits.getValue().isEmpty() ? "" : ", ") + entry, 0));
            } else {
                Node last = kits.getValue().get(kits.getValue().size() - 1);
                String indent = " ".repeat(t.column(t.dash(kits.getValue().get(0))));
                int at = t.afterLine(t.lastLeafEnd(last));
                edits.add(new Edit(at, at, t.newlineIfMissing(t.lastLeafEnd(last)) + entryLines(indent, indent + "  "), 0));
            }
            findings.add("goversbx kit added");
            return;
        }

        for (Node duplicate : ours.subList(1, ours.size())) {
            if (flow) {
                throw new UnusableFileException("'kits' is a flow list ([...]) with several goversbx entries - remove the extra ones by hand");
            }
            edits.add(new Edit(t.lineStart(t.dash(duplicate)), t.afterLine(t.lastLeafEnd(duplicate)), "", 0));
            findings.add("duplicate goversbx kit entry removed (" + sourceOf(duplicate) + ")");
        }

        Node kept = ours.get(0);
        String source = sourceOf(kept);
        boolean pathWrong = !source.equals(kitDir);
        String wantedPort = port == DEFAULT_PORT ? null : Integer.toString(port);
        if (pathWrong) {
            findings.add("goversbx kit path corrected to " + kitDir + " (was " + source + ")");
        }

        if (kept instanceof ScalarNode) {
            if (wantedPort == null) {
                if (pathWrong) {
                    edits.add(new Edit(t.start(kept), t.end(kept), flow ? quoted(kitDir) : scalar(kitDir), 0));
                }
            } else if (flow) {
                edits.add(new Edit(t.start(kept), t.end(kept),
                        "{source: " + quoted(kitDir) + ", args: {daemonPort: \"" + wantedPort + "\"}}", 0));
                findings.add("daemonPort set to " + wantedPort);
            } else {
                String indent = " ".repeat(t.column(t.start(kept)));
                edits.add(new Edit(t.start(kept), t.end(kept), "source: " + scalar(kitDir), 0));
                int at = t.afterLine(t.end(kept));
                edits.add(new Edit(at, at, t.newlineIfMissing(t.end(kept)) + argsLines(indent, wantedPort), 0));
                findings.add("daemonPort set to " + wantedPort);
            }
            return;
        }

        MappingNode entry = (MappingNode) kept;
        if (pathWrong) {
            Node value = valueOf(entry, "source");
            boolean inFlow = flow || entry.getFlowStyle() == FlowStyle.FLOW;
            edits.add(new Edit(t.start(value), t.end(value), inFlow ? quoted(kitDir) : scalar(kitDir), 0));
        }
        planPort(t, entry, wantedPort, edits, findings);
    }

    private void planPort(Text t, MappingNode entry, String wantedPort, List<Edit> edits, List<String> findings) {
        Node args = valueOf(entry, "args");
        if (args != null && !(args instanceof MappingNode)) {
            if (wantedPort == null && isNull(args)) {
                return;
            }
            throw new UnusableFileException("'args' of the goversbx kit entry is not a mapping - fix it by hand");
        }
        MappingNode argsMap = (MappingNode) args;
        Node current = valueOf(argsMap, "daemonPort");
        String currentPort = scalarValue(current);
        if (wantedPort == null ? current == null : wantedPort.equals(currentPort)) {
            return;
        }
        boolean entryFlow = entry.getFlowStyle() == FlowStyle.FLOW;

        if (wantedPort != null && current != null) {
            edits.add(new Edit(t.start(current), t.end(current), "\"" + wantedPort + "\"", 0));
            findings.add("daemonPort set to " + wantedPort);
            return;
        }
        if (entryFlow || (argsMap != null && argsMap.getFlowStyle() == FlowStyle.FLOW && !(wantedPort == null && argsMap.getValue().size() == 1))) {
            throw new UnusableFileException("the goversbx kit entry is written in flow style ({...}) - set args.daemonPort"
                    + (wantedPort == null ? " (remove it)" : " to \"" + wantedPort + "\"") + " by hand");
        }
        if (wantedPort == null) {
            Node argsKey = keyOf(entry, "args");
            Node first = argsMap.getValue().size() == 1 ? argsKey : keyOf(argsMap, "daemonPort");
            int end = argsMap.getFlowStyle() == FlowStyle.FLOW ? t.end(argsMap) : t.end(current);
            if (t.onFirstLineOf(entry, first)) {
                throw new UnusableFileException("cannot remove args.daemonPort from the goversbx kit entry safely - remove it by hand");
            }
            edits.add(new Edit(t.lineStart(t.start(first)), t.afterLine(end), "", 0));
            findings.add("daemonPort removed (the daemon is on " + DEFAULT_PORT + ")");
            return;
        }
        if (argsMap == null) {
            String indent = " ".repeat(t.column(t.start(entry.getValue().get(0).getKeyNode())));
            int at = t.afterLine(t.lastLeafEnd(entry));
            edits.add(new Edit(at, at, t.newlineIfMissing(t.lastLeafEnd(entry)) + argsLines(indent, wantedPort), 0));
        } else if (argsMap.getValue().isEmpty()) {
            throw new UnusableFileException("'args' of the goversbx kit entry is empty - set args.daemonPort to \"" + wantedPort + "\" by hand");
        } else {
            String indent = " ".repeat(t.column(t.start(argsMap.getValue().get(0).getKeyNode())));
            int at = t.afterLine(t.lastLeafEnd(argsMap));
            edits.add(new Edit(at, at, t.newlineIfMissing(t.lastLeafEnd(argsMap)) + indent + "daemonPort: \"" + wantedPort + "\"\n", 0));
        }
        findings.add("daemonPort set to " + wantedPort);
    }

    private String entryLines(String dashIndent, String keyIndent) {
        String lines = dashIndent + "- source: " + scalar(kitDir) + "\n";
        return port == DEFAULT_PORT ? lines : lines + argsLines(keyIndent, Integer.toString(port));
    }

    private static String argsLines(String indent, String port) {
        return indent + "args:\n" + indent + "  daemonPort: \"" + port + "\"\n";
    }

    private static String apply(String original, List<Edit> edits) {
        List<Edit> sorted = new ArrayList<>(edits);
        // At one offset an insertion goes before a removal that starts there.
        sorted.sort(Comparator.comparingInt(Edit::start).thenComparingInt(e -> e.end() - e.start()).thenComparingInt(Edit::order));
        StringBuilder out = new StringBuilder();
        int position = 0;
        for (Edit edit : sorted) {
            if (edit.start() < position) {
                throw new UnusableFileException("goversbx cannot edit this file safely (overlapping changes) - fix the goversbx kit entry by hand");
            }
            out.append(original, position, edit.start()).append(edit.replacement());
            position = edit.end();
        }
        return out.append(original.substring(position)).toString();
    }

    // ---- YAML helpers ----

    private static MappingNode root(String text) {
        Optional<Node> node;
        try {
            node = new Compose(LoadSettings.builder().build()).composeString(text);
        } catch (YamlEngineException e) {
            throw new UnusableFileException("not valid YAML: " + e.getMessage().strip().replace("\n", " "));
        }
        if (node.isEmpty() || isNull(node.get())) {
            return null;
        }
        if (node.get() instanceof MappingNode mapping) {
            return mapping;
        }
        throw new UnusableFileException("the top level is not a mapping");
    }

    private static Node valueOf(MappingNode mapping, String key) {
        if (mapping != null) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode scalar && key.equals(scalar.getValue())) {
                    return tuple.getValueNode();
                }
            }
        }
        return null;
    }

    private static Node keyOf(MappingNode mapping, String key) {
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode scalar && key.equals(scalar.getValue())) {
                return tuple.getKeyNode();
            }
        }
        throw new IllegalStateException("no key " + key);
    }

    private static String scalarValue(Node node) {
        return node instanceof ScalarNode scalar && !isNull(node) ? scalar.getValue() : null;
    }

    private static boolean isNull(Node node) {
        return node instanceof ScalarNode scalar && scalar.isPlain()
                && (scalar.getValue().isEmpty() || scalar.getValue().equals("~") || scalar.getValue().equalsIgnoreCase("null"));
    }

    /** A kits item's source: the item itself when it is a string, its {@code source} when a mapping. */
    private static String sourceOf(Node item) {
        if (item instanceof MappingNode mapping) {
            return scalarValue(valueOf(mapping, "source"));
        }
        return scalarValue(item);
    }

    static String scalar(String value) {
        return PLAIN_SAFE.matcher(value).matches() ? value : quoted(value);
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Positions in the original text. snakeyaml counts code points; this converts to char offsets. */
    private static final class Text {
        private final String text;

        Text(String text) {
            this.text = text;
        }

        int start(Node node) {
            return text.offsetByCodePoints(0, node.getStartMark().orElseThrow().getIndex());
        }

        int end(Node node) {
            return text.offsetByCodePoints(0, node.getEndMark().orElseThrow().getIndex());
        }

        int lineStart(int position) {
            return text.lastIndexOf('\n', position - 1) + 1;
        }

        int column(int position) {
            return position - lineStart(position);
        }

        /** The offset right after the line that ends at or after {@code position}. */
        int afterLine(int position) {
            if (position > 0 && text.charAt(position - 1) == '\n') {
                return position;
            }
            int newline = text.indexOf('\n', position);
            return newline < 0 ? text.length() : newline + 1;
        }

        /** A line break to put in front of inserted lines when the text ends without one there. */
        String newlineIfMissing(int position) {
            int after = afterLine(position);
            return after == text.length() && after > 0 && text.charAt(after - 1) != '\n' ? "\n" : "";
        }

        /** The "-" of a block sequence item. */
        int dash(Node item) {
            int i = start(item) - 1;
            while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
                i--;
            }
            if (i < 0 || text.charAt(i) != '-') {
                throw new UnusableFileException("goversbx cannot locate a 'kits' list item in this layout - fix the goversbx kit entry by hand");
            }
            return i;
        }

        boolean onFirstLineOf(Node container, Node node) {
            return lineStart(start(node)) == lineStart(dashOrStart(container));
        }

        private int dashOrStart(Node container) {
            int i = start(container) - 1;
            while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                i--;
            }
            return i >= 0 && text.charAt(i) == '-' ? i : start(container);
        }

        /**
         * The end of the last scalar inside a node. A block collection's own end mark sits at the next
         * token, i.e. after any comments and blank lines that follow it - those belong to the user.
         */
        int lastLeafEnd(Node node) {
            if (node instanceof MappingNode mapping && !mapping.getValue().isEmpty() && mapping.getFlowStyle() != FlowStyle.FLOW) {
                NodeTuple last = mapping.getValue().get(mapping.getValue().size() - 1);
                return Math.max(lastLeafEnd(last.getKeyNode()), lastLeafEnd(last.getValueNode()));
            }
            if (node instanceof SequenceNode sequence && !sequence.getValue().isEmpty() && sequence.getFlowStyle() != FlowStyle.FLOW) {
                return lastLeafEnd(sequence.getValue().get(sequence.getValue().size() - 1));
            }
            return Math.max(start(node), end(node));
        }
    }
}
