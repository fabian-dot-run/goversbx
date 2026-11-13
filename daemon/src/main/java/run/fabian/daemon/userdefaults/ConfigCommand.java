package run.fabian.daemon.userdefaults;

import run.fabian.daemon.userdefaults.UserDefaultsEditor.Outcome;
import run.fabian.daemon.userdefaults.UserDefaultsEditor.UnusableFileException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code goversbx-daemon config ...}: the file work behind {@code goversbx setup}, {@code init} and
 * {@code run} (spec 0003 slice 3). No daemon is started; the launcher script asks the questions and
 * prints, this decides and writes.
 *
 * <pre>
 * config user-defaults --kit-dir DIR [--port P] [--agent A] [--check] [--file PATH]
 *     stdout: one line per change or finding, then "warning: ..." / "note: ..." lines
 *     exit 0 ok, 1 unusable file (stderr, nothing written), 2 drift (--check), 3 agent needed (nothing written)
 * config user-defaults --print-agent [--file PATH]     stdout: the agent, or nothing
 * config project-kits --file PATH                      stdout: the kits sources, one per line
 * </pre>
 */
public final class ConfigCommand {

    public static final int OK = 0;
    public static final int UNUSABLE = 1;
    public static final int DRIFT = 2;
    public static final int AGENT_NEEDED = 3;

    static final Pattern AGENT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@-]*");
    private static final String BACKUP_SUFFIX = ".goversbx-bak";

    private final PrintStream out;
    private final PrintStream err;

    public ConfigCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /** @param args the arguments after {@code config} */
    public int run(List<String> args) {
        try {
            if (args.isEmpty()) {
                throw new UsageException("missing subcommand");
            }
            Options options = Options.parse(args.subList(1, args.size()));
            return switch (args.get(0)) {
                case "user-defaults" -> options.printAgent ? printAgent(options) : userDefaults(options);
                case "project-kits" -> projectKits(options);
                default -> throw new UsageException("unknown subcommand " + args.get(0));
            };
        } catch (UsageException e) {
            err.println("goversbx-daemon config: " + e.getMessage());
            err.println("usage: config user-defaults --kit-dir DIR [--port P] [--agent A] [--check] [--file PATH]");
            err.println("       config user-defaults --print-agent [--file PATH]");
            err.println("       config project-kits --file PATH");
            return UNUSABLE;
        } catch (UnusableFileException e) {
            err.println(e.getMessage());
            return UNUSABLE;
        } catch (IOException e) {
            err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
            return UNUSABLE;
        }
    }

    private int userDefaults(Options options) throws IOException {
        if (options.kitDir == null) {
            throw new UsageException("--kit-dir is required");
        }
        if (options.agent != null && !AGENT_NAME.matcher(options.agent).matches()) {
            throw new UsageException("not an agent name: " + options.agent);
        }
        Path file = options.userDefaultsFile();
        UserDefaultsEditor editor = new UserDefaultsEditor(options.kitDir, options.port, options.agent);
        String original = read(file);
        Outcome outcome;
        try {
            outcome = original == null ? editor.create() : editor.edit(original);
        } catch (UnusableFileException e) {
            throw new UnusableFileException(file + ": " + e.getMessage());
        }

        if (options.check) {
            List<String> findings = new ArrayList<>(outcome.findings());
            if (original == null && !outcome.agentMissing()) {
                findings = List.of("the file does not exist");
            }
            findings.forEach(out::println);
            outcome.notes().forEach(out::println);
            return findings.isEmpty() ? OK : DRIFT;
        }
        if (outcome.agentMissing()) {
            return AGENT_NEEDED;
        }
        if (outcome.changed()) {
            write(file, outcome.text(), original != null);
        }
        outcome.findings().forEach(out::println);
        outcome.notes().forEach(out::println);
        return OK;
    }

    private int printAgent(Options options) throws IOException {
        String text = read(options.userDefaultsFile());
        if (text != null) {
            UserDefaultsEditor.agentOf(text).ifPresent(out::println);
        }
        return OK;
    }

    private int projectKits(Options options) throws IOException {
        if (options.file == null) {
            throw new UsageException("--file is required");
        }
        String text = read(Path.of(options.file));
        if (text != null) {
            UserDefaultsEditor.kitSources(text).forEach(out::println);
        }
        return OK;
    }

    private static String read(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /**
     * Backup first, then temp file + rename next to the real file (the link's target when the path is
     * a symlink), so a crash leaves either the old or the new content. A new file is owner-only, an
     * edited one keeps its mode.
     */
    private static void write(Path file, String text, boolean existed) throws IOException {
        Path target = existed ? file.toRealPath() : file.toAbsolutePath();
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
        if (existed) {
            Files.copy(target, Path.of(file + BACKUP_SUFFIX), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            permissions = Files.getPosixFilePermissions(target);
        } else if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Path temp = Files.createTempFile(target.getParent(), ".sbxenv.", ".goversbx-tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(temp, permissions);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private static final class Options {
        String kitDir;
        int port = UserDefaultsEditor.DEFAULT_PORT;
        String agent;
        String file;
        boolean check;
        boolean printAgent;

        static Options parse(List<String> args) {
            Options options = new Options();
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                switch (arg) {
                    case "--check" -> options.check = true;
                    case "--print-agent" -> options.printAgent = true;
                    case "--kit-dir", "--port", "--agent", "--file" -> {
                        if (++i >= args.size()) {
                            throw new UsageException(arg + " needs a value");
                        }
                        String value = args.get(i);
                        switch (arg) {
                            case "--kit-dir" -> options.kitDir = value;
                            case "--agent" -> options.agent = value;
                            case "--file" -> options.file = value;
                            default -> options.port = port(value);
                        }
                    }
                    default -> throw new UsageException("unknown option " + arg);
                }
            }
            return options;
        }

        private static int port(String value) {
            try {
                int port = Integer.parseInt(value);
                if (port > 0 && port < 65536) {
                    return port;
                }
            } catch (NumberFormatException ignored) {
                // falls through
            }
            throw new UsageException("not a port: " + value);
        }

        Path userDefaultsFile() {
            if (file != null) {
                return Path.of(file);
            }
            // $HOME first, like sbx (Go's os.UserHomeDir): both must mean the same file.
            String home = System.getenv("HOME");
            return Path.of(home == null || home.isBlank() ? System.getProperty("user.home") : home, ".sbxenv.yaml");
        }
    }
}
