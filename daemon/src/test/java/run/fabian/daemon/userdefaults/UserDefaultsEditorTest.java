package run.fabian.daemon.userdefaults;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.userdefaults.UserDefaultsEditor.Outcome;
import run.fabian.daemon.userdefaults.UserDefaultsEditor.UnusableFileException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 0003 slice 3, scenarios S1-S8 at the text level. */
class UserDefaultsEditorTest {

    private static final String KIT = "/opt/homebrew/opt/goversbx/share/goversbx/kits/goversbx";

    private static UserDefaultsEditor editor() {
        return new UserDefaultsEditor(KIT, 8787, null);
    }

    private static UserDefaultsEditor editor(int port) {
        return new UserDefaultsEditor(KIT, port, null);
    }

    @Test
    void aMissingFileIsCreatedWithAgentWorkspaceAndTheKit() {
        Outcome outcome = new UserDefaultsEditor(KIT, 8787, "claude").create();

        assertEquals("""
                schemaVersion: "1"
                agent: claude
                workspace: ${{ env.projectDir }}
                kits:
                  - source: %s
                """.formatted(KIT), outcome.text());
        assertFalse(editor().edit(outcome.text()).changed(), "a created file needs no further change");
    }

    @Test
    void aMissingFileWithoutAnAgentIsNotCreated() {
        Outcome outcome = editor().create();

        assertTrue(outcome.agentMissing());
        assertFalse(outcome.changed());
    }

    @Test
    void theEntryIsAppendedAndEveryOtherByteStays() {
        String original = """
                # my defaults
                schemaVersion: "1"
                agent: 'codex'   # quoted on purpose

                workspace: ${{ env.projectDir }}
                kits:
                  # browsers
                  - docker.io/sbx/playwright:1.61.1
                  - source: /Users/me/kits/other   # mine
                    args:
                      level: "3"

                # trailing comment
                env:
                  FOO: bar
                """;

        Outcome outcome = editor().edit(original);

        assertEquals(original.replace("      level: \"3\"\n", "      level: \"3\"\n  - source: " + KIT + "\n"), outcome.text());
        assertEquals(List.of("goversbx kit added"), outcome.findings());
    }

    @Test
    void aStalePathIsCorrectedInPlace() {
        String original = """
                agent: claude
                workspace: .
                kits:
                  - docker.io/sbx/playwright:1.61.1
                  - source: "/Users/me/IdeaProjects/goversbx/sandbox-kit/kits/goversbx"  # checkout
                    args: {daemonHost: host.docker.internal}
                  - other
                """;

        Outcome outcome = editor().edit(original);

        assertEquals(original.replace("\"/Users/me/IdeaProjects/goversbx/sandbox-kit/kits/goversbx\"", KIT), outcome.text());
        assertEquals(1, outcome.findings().size());
        assertTrue(outcome.findings().get(0).startsWith("goversbx kit path corrected to " + KIT));
    }

    @Test
    void aPlainStringEntryIsCorrectedToo() {
        String original = "agent: claude\nworkspace: .\nkits:\n- /old/kits/goversbx/\n- other\n";

        assertEquals("agent: claude\nworkspace: .\nkits:\n- " + KIT + "\n- other\n", editor().edit(original).text());
    }

    @Test
    void ofSeveralEntriesTheFirstIsKeptWithItsExtraKeys() {
        String original = """
                agent: claude
                workspace: .
                kits:
                  - /old/kits/goversbx
                  # between
                  - source: %s
                    args:
                      daemonHost: host.docker.internal
                  - other
                """.formatted(KIT);

        Outcome outcome = editor().edit(original);

        assertEquals("""
                agent: claude
                workspace: .
                kits:
                  - %s
                  # between
                  - other
                """.formatted(KIT), outcome.text());
        assertEquals(2, outcome.findings().size());
    }

    @Test
    void theFirstOfSeveralKeepsItsOtherArgs() {
        String original = """
                agent: claude
                workspace: .
                kits:
                  - source: %s
                    args:
                      daemonHost: gateway.internal
                  - %s
                """.formatted(KIT, KIT);

        assertEquals("""
                agent: claude
                workspace: .
                kits:
                  - source: %s
                    args:
                      daemonHost: gateway.internal
                """.formatted(KIT), editor().edit(original).text());
    }

    @Test
    void aNonDefaultPortBecomesTheDaemonPortArg() {
        String mapping = "agent: claude\nworkspace: .\nkits:\n  - source: " + KIT + "\n";
        String string = "agent: claude\nworkspace: .\nkits:\n  - " + KIT + "  # ours\n  - other\n";
        String withArgs = "agent: claude\nworkspace: .\nkits:\n  - source: " + KIT + "\n    args:\n      daemonHost: h\n";
        String wrongPort = "agent: claude\nworkspace: .\nkits:\n  - source: " + KIT + "\n    args:\n      daemonPort: '9000'\n";

        assertEquals(mapping + "    args:\n      daemonPort: \"9001\"\n", editor(9001).edit(mapping).text());
        assertEquals("agent: claude\nworkspace: .\nkits:\n  - source: " + KIT + "  # ours\n    args:\n      daemonPort: \"9001\"\n  - other\n",
                editor(9001).edit(string).text());
        assertEquals(withArgs + "      daemonPort: \"9001\"\n", editor(9001).edit(withArgs).text());
        assertEquals(wrongPort.replace("'9000'", "\"9001\""), editor(9001).edit(wrongPort).text());
        assertFalse(editor(9001).edit(editor(9001).edit(mapping).text()).changed());
    }

    @Test
    void onTheDefaultPortTheDaemonPortArgIsRemovedAndOtherArgsStay() {
        String only = "agent: claude\nworkspace: .\nkits:\n  - source: " + KIT + "\n    args:\n      daemonPort: \"9001\"\n  - other\n";
        String withOthers = "agent: claude\nworkspace: .\nkits:\n  - source: " + KIT
                + "\n    args:\n      daemonPort: \"9001\"  # was moved\n      daemonHost: h\n";

        assertEquals("agent: claude\nworkspace: .\nkits:\n  - source: " + KIT + "\n  - other\n", editor().edit(only).text());
        assertEquals("agent: claude\nworkspace: .\nkits:\n  - source: " + KIT + "\n    args:\n      daemonHost: h\n",
                editor().edit(withOthers).text());
    }

    @Test
    void aFileWithoutKitsGetsTheListAppended() {
        String original = "schemaVersion: \"1\"\nagent: claude\nworkspace: .";

        assertEquals(original + "\nkits:\n  - source: " + KIT + "\n", editor().edit(original).text());
        assertEquals("agent: claude\nworkspace: .\nkits:\n  - source: " + KIT + "\nenv:\n  A: b\n",
                editor().edit("agent: claude\nworkspace: .\nkits:\nenv:\n  A: b\n").text());
    }

    @Test
    void flowListsAreExtendedInPlace() {
        assertEquals("agent: claude\nworkspace: .\nkits: [a, \"" + KIT + "\"]\n",
                editor().edit("agent: claude\nworkspace: .\nkits: [a]\n").text());
        assertEquals("agent: claude\nworkspace: .\nkits: [\"" + KIT + "\"]\n",
                editor().edit("agent: claude\nworkspace: .\nkits: []\n").text());
        assertEquals("agent: claude\nworkspace: .\nkits: [a, \"" + KIT + "\"]\n",
                editor().edit("agent: claude\nworkspace: .\nkits: [a, /x/kits/goversbx]\n").text());
    }

    @Test
    void anEmptyOrCommentOnlyFileIsFilled() {
        UserDefaultsEditor withAgent = new UserDefaultsEditor(KIT, 8787, "claude");

        assertEquals("# mine\nschemaVersion: \"1\"\nagent: claude\nkits:\n  - source: " + KIT + "\n", withAgent.edit("# mine\n").text());
        assertTrue(editor().edit("").agentMissing());
    }

    @Test
    void whatCannotBeEditedIsRefused() {
        assertThrows(UnusableFileException.class, () -> editor().edit("agent: [claude\n"), "not YAML");
        assertThrows(UnusableFileException.class, () -> editor().edit("- a\n- b\n"), "top level is a list");
        assertThrows(UnusableFileException.class, () -> editor().edit("agent: claude\nkits:\n  a: b\n"), "kits is a mapping");
        assertThrows(UnusableFileException.class, () -> editor().edit("agent: claude\nkits: nope\n"), "kits is a string");
        assertThrows(UnusableFileException.class, () -> editor().edit("{agent: claude}\n"), "a flow mapping cannot take a key");
        assertThrows(UnusableFileException.class,
                () -> editor(9001).edit("agent: claude\nkits:\n  - {source: " + KIT + "}\n"), "flow entry needs an arg");
    }

    @Test
    void aCorrectFileNeedsNothing() {
        String text = "agent: claude\nworkspace: .\nkits:\n  - source: " + KIT + "\n";

        Outcome outcome = editor().edit(text);

        assertFalse(outcome.changed());
        assertTrue(outcome.findings().isEmpty());
        assertTrue(outcome.notes().isEmpty());
    }

    @Test
    void anExistingAgentIsNeverChangedAndAMissingOneIsWrittenOrAskedFor() {
        String withAgent = "agent: codex\nworkspace: .\nkits:\n  - " + KIT + "\n";
        String without = "workspace: .\nkits:\n  - " + KIT + "\n";

        Outcome kept = new UserDefaultsEditor(KIT, 8787, "claude").edit(withAgent);
        assertFalse(kept.changed());
        assertEquals(List.of("note: the default agent stays codex (not changed to claude)"), kept.notes());

        assertTrue(editor().edit(without).agentMissing());
        Outcome written = new UserDefaultsEditor(KIT, 8787, "claude").edit(without);
        assertEquals(without + "agent: claude\n", written.text());
        assertEquals(List.of("default agent set to claude"), written.findings());
    }

    @Test
    void aMissingWorkspaceIsAWarningNotAChange() {
        Outcome outcome = editor().edit("agent: claude\nkits:\n  - " + KIT + "\n");

        assertFalse(outcome.changed());
        assertTrue(outcome.findings().isEmpty());
        assertEquals(1, outcome.notes().size());
        assertTrue(outcome.notes().get(0).startsWith("warning: no 'workspace'"));
    }

    @Test
    void nonAsciiTextBeforeTheEntryDoesNotShiftTheSplice() {
        String original = "# Grüße 🙂 von mir\nagent: claude\nworkspace: .\nkits:\n  - /älter/kits/goversbx  # 🙂\n  - other\n";

        assertEquals(original.replace("/älter/kits/goversbx", KIT), editor().edit(original).text());
    }

    @Test
    void aPathThatNeedsQuotesIsQuoted() {
        String kit = "/Users/me/My Apps/share/goversbx/kits/goversbx";
        Outcome outcome = new UserDefaultsEditor(kit, 8787, null).edit("agent: claude\nworkspace: .\n");

        assertEquals("agent: claude\nworkspace: .\nkits:\n  - source: \"" + kit + "\"\n", outcome.text());
        assertEquals(List.of(kit), UserDefaultsEditor.kitSources(outcome.text()));
    }

    @Test
    void kitSourcesAndAgentAreRead() {
        String text = "agent: omp\nkits:\n  - a\n  - source: /b/kits/goversbx\n    args: {x: y}\n  - {nope: 1}\n";

        assertEquals(List.of("a", "/b/kits/goversbx"), UserDefaultsEditor.kitSources(text));
        assertEquals("omp", UserDefaultsEditor.agentOf(text).orElseThrow());
        assertTrue(UserDefaultsEditor.agentOf("workspace: .\n").isEmpty());
        assertTrue(UserDefaultsEditor.isGoversbxKit("/b/kits/goversbx/"));
        assertFalse(UserDefaultsEditor.isGoversbxKit("/b/kits/goversbx-old"));
    }
}
