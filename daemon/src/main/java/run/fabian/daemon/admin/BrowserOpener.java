package run.fabian.daemon.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opens a URL in the host's default browser. Uses the configured opener argv if set
 * ({@code GOVERSBX_BROWSER_OPENER}, also the test seam), else {@code open} on macOS and
 * {@code xdg-open} elsewhere. Fire-and-forget: the daemon never blocks on, or fails because of, the
 * browser.
 */
public final class BrowserOpener {

    private static final Logger log = LoggerFactory.getLogger(BrowserOpener.class);

    private final List<String> configuredCommand;

    public BrowserOpener(List<String> configuredCommand) {
        this.configuredCommand = configuredCommand == null ? List.of() : List.copyOf(configuredCommand);
    }

    public void open(String url) {
        List<String> command = new ArrayList<>(configuredCommand.isEmpty() ? List.of(defaultOpener()) : configuredCommand);
        command.add(url);
        try {
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            log.info("Opened the dashboard in the browser ({})", String.join(" ", command));
        } catch (Exception e) {
            log.warn("Could not open the dashboard in a browser ({}): {}", String.join(" ", command), e.toString());
        }
    }

    private static String defaultOpener() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin") ? "open" : "xdg-open";
    }
}
