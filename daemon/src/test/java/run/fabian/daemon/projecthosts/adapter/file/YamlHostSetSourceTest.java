package run.fabian.daemon.projecthosts.adapter.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import run.fabian.daemon.projecthosts.domain.InvalidHostSetException;
import run.fabian.daemon.projecthosts.domain.ResolvedHostSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The file contract of spec 0001 slice 1: strict, symlink-free, profiles first, capped. */
class YamlHostSetSourceTest {

    @TempDir
    Path workspace;
    @TempDir
    Path profiles;

    private YamlHostSetSource source;

    @BeforeEach
    void setUp() {
        source = new YamlHostSetSource(profiles);
    }

    private Path projectFile(String yaml) throws IOException {
        Path file = workspace.resolve(".goversbx").resolve("network.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
        return file;
    }

    private void profile(String name, String yaml) throws IOException {
        Files.writeString(profiles.resolve(name + ".yaml"), yaml);
    }

    private List<String> problemsOf(Runnable call) {
        InvalidHostSetException e = assertThrows(InvalidHostSetException.class, call::run);
        return e.problems();
    }

    @Test
    void noProjectFileMeansNoSet() {
        assertEquals(Optional.empty(), source.resolve(workspace));
    }

    @Test
    void hostsOnly() throws IOException {
        Path file = projectFile("""
                version: 1
                hosts:
                  - repo.maven.apache.org
                  - plugins.gradle.org
                """);

        ResolvedHostSet set = source.resolve(workspace).orElseThrow();

        assertEquals(List.of("repo.maven.apache.org", "plugins.gradle.org"), set.hosts());
        assertEquals(file.toString(), set.source());
        assertEquals(List.of(), set.profiles());
    }

    @Test
    void profilesResolveFirstThenTheFilesOwnHostsEachOnce() throws IOException {
        profile("jvm-build", "version: 1\nhosts: [services.gradle.org, plugins.gradle.org]\n");
        profile("git", "version: 1\nhosts:\n  - github.com\n");
        projectFile("""
                version: 1
                profiles: [jvm-build, git]
                hosts:
                  - plugins.gradle.org
                  - api.example.com
                """);

        ResolvedHostSet set = source.resolve(workspace).orElseThrow();

        assertEquals(List.of("services.gradle.org", "plugins.gradle.org", "github.com", "api.example.com"), set.hosts());
        assertEquals(List.of("jvm-build", "git"), set.profiles());
    }

    @Test
    void profilesAlone() throws IOException {
        profile("git", "version: 1\nhosts: [github.com]\n");
        projectFile("version: 1\nprofiles: [git]\n");

        assertEquals(List.of("github.com"), source.resolve(workspace).orElseThrow().hosts());
    }

    @Test
    void moreThanTheCapIsInvalid() throws IOException {
        String many = IntStream.rangeClosed(1, 101).mapToObj(i -> "  - h" + i + ".example").collect(Collectors.joining("\n"));
        projectFile("version: 1\nhosts:\n" + many + "\n");

        List<String> problems = problemsOf(() -> source.resolve(workspace));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("101 distinct hosts"), problems.toString());
    }

    @Test
    void duplicatesDoNotCountTowardsTheCap() throws IOException {
        String many = IntStream.rangeClosed(1, 100).mapToObj(i -> "  - h" + i + ".example").collect(Collectors.joining("\n"));
        projectFile("version: 1\nhosts:\n" + many + "\n  - h1.example\n");

        assertEquals(100, source.resolve(workspace).orElseThrow().hosts().size());
    }

    @Test
    void unknownKeysAreErrorsNotSilentlyIgnored() throws IOException {
        projectFile("version: 1\nhost:\n  - a.example\n");

        List<String> problems = problemsOf(() -> source.resolve(workspace));

        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown key 'host'")), problems.toString());
    }

    @Test
    void versionMustBeOne() throws IOException {
        projectFile("version: 2\nhosts: [a.example]\n");
        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).contains("version 2 is not supported"));

        projectFile("hosts: [a.example]\n");
        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).contains("'version: 1' is required"));

        projectFile("version: \"1\"\nhosts: [a.example]\n");
        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).contains("is not supported"));
    }

    @Test
    void anUnknownProfileIsAnError() throws IOException {
        projectFile("version: 1\nprofiles: [nope]\nhosts: [a.example]\n");

        List<String> problems = problemsOf(() -> source.resolve(workspace));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("profile 'nope' not found"), problems.get(0));
        assertTrue(problems.get(0).contains(profiles.resolve("nope.yaml").toString()), problems.get(0));
    }

    @Test
    void aProfileNameMustBeAPlainName() throws IOException {
        projectFile("version: 1\nprofiles: [\"../etc\", \"Jvm\"]\n");

        List<String> problems = problemsOf(() -> source.resolve(workspace));

        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("'../etc' is not allowed"), problems.toString());
    }

    @Test
    void aProfileCannotNameProfilesOrBeEmpty() throws IOException {
        profile("nested", "version: 1\nprofiles: [other]\nhosts: [a.example]\n");
        profile("empty", "version: 1\nhosts: []\n");
        projectFile("version: 1\nprofiles: [nested, empty]\n");

        List<String> problems = problemsOf(() -> source.resolve(workspace));

        assertTrue(problems.stream().anyMatch(p -> p.contains("nested.yaml") && p.contains("unknown key 'profiles'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("empty.yaml") && p.contains("names nothing")), problems.toString());
    }

    @Test
    void everyProblemIsReportedAtOnce() throws IOException {
        projectFile("version: 3\nbogus: 1\nhosts: [a.example, 42]\nprofiles: [missing]\n");

        List<String> problems = problemsOf(() -> source.resolve(workspace));

        assertEquals(4, problems.size(), problems.toString());
    }

    @Test
    void anEmptyOrNonMappingFileIsInvalid() throws IOException {
        projectFile("");
        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).endsWith("is empty"));
        projectFile("- a.example\n");
        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).contains("expected a mapping"));
        projectFile("version: 1\nhosts: a.example\n");
        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).contains("'hosts' must be a list"));
        projectFile("version: 1\n");
        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).contains("neither 'profiles' nor 'hosts'"));
    }

    @Test
    void unparseableYamlIsReportedNotThrownRaw() throws IOException {
        projectFile("version: 1\nhosts: [a.example\n");

        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).contains("cannot be parsed"));
    }

    @Test
    void symlinksAreNotFollowed() throws IOException {
        Path elsewhere = Files.createTempDirectory("elsewhere");
        Files.writeString(elsewhere.resolve("network.yaml"), "version: 1\nhosts: [a.example]\n");
        Path dir = Files.createDirectories(workspace.resolve(".goversbx"));
        Files.createSymbolicLink(dir.resolve("network.yaml"), elsewhere.resolve("network.yaml"));
        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).contains("symbolic link"));

        Files.delete(dir.resolve("network.yaml"));
        Files.delete(dir);
        Files.createSymbolicLink(workspace.resolve(".goversbx"), elsewhere);
        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).contains("symbolic link"));
    }

    @Test
    void anOversizedFileIsRefused() throws IOException {
        projectFile("version: 1\nhosts: [a.example]\n# " + "x".repeat(70_000) + "\n");

        assertTrue(problemsOf(() -> source.resolve(workspace)).get(0).contains("larger than"));
    }

    @Test
    void hostsAreTrimmedButNotValidatedHere() throws IOException {
        // Host syntax (F2/F3) is networkaccess's rule, applied by the service through the published api.
        projectFile("version: 1\nhosts: [\"  a.example \", \"*.example\"]\n");

        assertEquals(List.of("a.example", "*.example"), source.resolve(workspace).orElseThrow().hosts());
    }
}
