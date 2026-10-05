package burp.mcp.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the recent-project lookup: the name matching against
 * Burp's preference entries, and the sibling socket path derivation.
 */
class ProjectFilesTest {

    private final Map<String, String> prefs = new HashMap<>();

    private Function<String, String> lookup() {
        return prefs::get;
    }

    @Test
    void locate_byFileBasename_shouldReturnPath() {
        prefs.put("burp.suite.recentProjectFiles0", "/work/a/exness-20261004.burp");
        Optional<java.nio.file.Path> found = ProjectFiles.locate("exness-20261004.burp", lookup());
        assertThat(found).contains(Paths.get("/work/a/exness-20261004.burp"));
    }

    @Test
    void locate_projectNameWithoutExtension_shouldMatch() {
        prefs.put("burp.suite.recentProjectFiles0", "/work/a/exness-20261004.burp");
        assertThat(ProjectFiles.locate("exness-20261004", lookup()))
                .contains(Paths.get("/work/a/exness-20261004.burp"));
    }

    @Test
    void locate_byRecentName_shouldMatchDifferentlyNamedFile() {
        // Burp's display name can differ from the file basename.
        prefs.put("burp.suite.recentProjectFiles0", "/work/target/2026-10-04-dyson.burp");
        prefs.put("burp.suite.recentProjectNames0", "dyson");
        assertThat(ProjectFiles.locate("dyson", lookup()))
                .contains(Paths.get("/work/target/2026-10-04-dyson.burp"));
    }

    @Test
    void locate_multipleMatches_shouldPreferMostRecent() {
        prefs.put("burp.suite.recentProjectFiles0", "/new/lab.burp");
        prefs.put("burp.suite.recentProjectFiles1", "/old/lab.burp");
        assertThat(ProjectFiles.locate("lab", lookup()))
                .contains(Paths.get("/new/lab.burp"));
    }

    @Test
    void locate_temporaryProject_shouldBeEmpty() {
        prefs.put("burp.suite.recentProjectFiles0", "/work/a/lab.burp");
        assertThat(ProjectFiles.locate("Temporary Project", lookup())).isEmpty();
    }

    @Test
    void locate_noMatch_shouldBeEmpty() {
        prefs.put("burp.suite.recentProjectFiles0", "/work/a/other.burp");
        assertThat(ProjectFiles.locate("lab", lookup())).isEmpty();
    }

    @Test
    void locate_relativePath_shouldBeIgnored() {
        prefs.put("burp.suite.recentProjectFiles0", "relative/lab.burp");
        assertThat(ProjectFiles.locate("lab", lookup())).isEmpty();
    }

    @Test
    void locate_nullLookup_shouldBeEmpty() {
        assertThat(ProjectFiles.locate("lab", null)).isEmpty();
    }

    @Test
    void socketPathFor_shouldSitNextToProjectAndStripBurpSuffix() {
        assertThat(ProjectFiles.socketPathFor(Paths.get("/home/nikolas/burp-projects/exness-20261004.burp")))
                .isEqualTo(Paths.get("/home/nikolas/burp-projects/exness-20261004.sock"));
    }

    @Test
    void isUsableSocketPath_shouldRejectOverlongPaths() {
        assertThat(ProjectFiles.isUsableSocketPath(Paths.get("/tmp/short.sock"))).isTrue();
        String longPath = "/tmp/" + "d".repeat(120) + "/x.sock";
        assertThat(ProjectFiles.isUsableSocketPath(Paths.get(longPath))).isFalse();
    }
}
