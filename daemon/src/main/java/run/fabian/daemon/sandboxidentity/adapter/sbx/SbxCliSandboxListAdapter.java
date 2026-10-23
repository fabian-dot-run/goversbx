package run.fabian.daemon.sandboxidentity.adapter.sbx;

import run.fabian.daemon.sandboxidentity.application.port.SbxSandboxListPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only - no dry-run mode needed, unlike the mutating {@code sbx} adapters in this package.
 * {@code sbx ls}'s JSON flag spelling changed across versions ({@code --json} pre-0.48.0,
 * {@code --format json} from 0.48.0 on); {@code lsFormatArgs} is configurable
 * (`GOVERSBX_SBX_LS_FORMAT_ARGS`) rather than hardcoded for exactly that reason. Only stdout is
 * parsed: sbx prints warnings on stderr (seen: "docker hub refresh lock held by another process").
 */
public final class SbxCliSandboxListAdapter implements SbxSandboxListPort {

    private static final Logger log = LoggerFactory.getLogger(SbxCliSandboxListAdapter.class);

    private final String sbxBinary;
    private final List<String> lsFormatArgs;
    private final ObjectMapper mapper = new ObjectMapper();

    public SbxCliSandboxListAdapter(String sbxBinary, List<String> lsFormatArgs) {
        this.sbxBinary = sbxBinary;
        this.lsFormatArgs = lsFormatArgs;
    }

    @Override
    public List<ListedSandbox> listSandboxes() {
        List<String> command = new java.util.ArrayList<>();
        command.add(sbxBinary);
        command.add("ls");
        command.addAll(lsFormatArgs);

        try {
            Process process = new ProcessBuilder(command).start();
            String output;
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("sbx ls failed (exit {}): {}", exitCode, output);
                return List.of();
            }
            List<ListedSandbox> sandboxes = parseSandboxes(output, mapper);
            log.info("sbx ls: running sandboxes {}", sandboxes.stream().filter(ListedSandbox::running).map(ListedSandbox::name).toList());
            return sandboxes;
        } catch (Exception e) {
            log.error("Failed to invoke sbx ls", e);
            return List.of();
        }
    }

    static List<ListedSandbox> parseSandboxes(String json, ObjectMapper mapper) throws Exception {
        SandboxSummary[] sandboxes = mapper.readValue(json, SandboxSummary[].class);
        return Arrays.stream(sandboxes)
                .map(s -> new ListedSandbox(s.name(), s.id(), "running".equals(s.status()), s.workspaces()))
                .toList();
    }
}
