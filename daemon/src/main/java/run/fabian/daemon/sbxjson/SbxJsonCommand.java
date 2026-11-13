package run.fabian.daemon.sbxjson;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code goversbx-daemon sbx-json ...}: reads sbx's JSON on stdin for the launcher script
 * ({@code goversbx upgrade}, the hint in {@code goversbx run}). No daemon is started.
 *
 * <pre>
 * sbx-json workspace-sandboxes --dir DIR [--dir DIR...]   &lt; sbx ls --format json
 *     stdout: NAME&lt;TAB&gt;AGENT&lt;TAB&gt;STATUS per sandbox with DIR itself among its workspaces
 * sbx-json sandbox --kit-dir DIR                          &lt; sbx inspect NAME --format json
 *     stdout: name=, kit=this|other|none, [kit_path=], created_version=, state=, sessions=, env=
 * exit 0, or 1 with a line on stderr when the input is not what sbx is expected to print
 * </pre>
 */
public final class SbxJsonCommand {

    private final InputStream in;
    private final PrintStream out;
    private final PrintStream err;

    public SbxJsonCommand(InputStream in, PrintStream out, PrintStream err) {
        this.in = in;
        this.out = out;
        this.err = err;
    }

    /** @param args the arguments after {@code sbx-json} */
    public int run(List<String> args) {
        try {
            if (args.isEmpty()) {
                throw new IllegalArgumentException("missing subcommand");
            }
            List<String> dirs = new ArrayList<>();
            String kitDir = null;
            for (int i = 1; i < args.size(); i += 2) {
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException(args.get(i) + " needs a value");
                }
                switch (args.get(i)) {
                    case "--dir" -> dirs.add(args.get(i + 1));
                    case "--kit-dir" -> kitDir = args.get(i + 1);
                    default -> throw new IllegalArgumentException("unknown option " + args.get(i));
                }
            }
            List<String> lines;
            switch (args.get(0)) {
                case "workspace-sandboxes" -> {
                    if (dirs.isEmpty()) {
                        throw new IllegalArgumentException("--dir is required");
                    }
                    lines = SbxJson.workspaceSandboxes(input(), dirs);
                }
                case "sandbox" -> {
                    if (kitDir == null) {
                        throw new IllegalArgumentException("--kit-dir is required");
                    }
                    lines = SbxJson.sandbox(input(), kitDir);
                }
                default -> throw new IllegalArgumentException("unknown subcommand " + args.get(0));
            }
            lines.forEach(out::println);
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("goversbx-daemon sbx-json: " + e.getMessage());
            err.println("usage: sbx-json workspace-sandboxes --dir DIR [--dir DIR...]   < sbx ls --format json");
            err.println("       sbx-json sandbox --kit-dir DIR                          < sbx inspect NAME --format json");
            return 1;
        } catch (SbxJson.UnreadableException | IOException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private String input() throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
