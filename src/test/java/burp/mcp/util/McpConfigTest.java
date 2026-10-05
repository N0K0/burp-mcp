package burp.mcp.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for McpConfig validation. Uses the global singleton; every mutation
 * is restored to a behaviorally identical value in a finally block so other
 * test classes sharing the singleton are unaffected.
 */
class McpConfigTest {

    @BeforeAll
    static void ensureInitialized() {
        if (McpConfig.getInstance() == null) {
            McpConfig.initialize(new McpConfig.Preferences() {
                private final java.util.Map<String, String> store = new ConcurrentHashMap<>();
                @Override public String getString(String key) { return store.get(key); }
                @Override public Integer getInteger(String key) {
                    String v = store.get(key);
                    return v != null ? Integer.parseInt(v) : null;
                }
                @Override public void setString(String key, String value) {
                    if (value == null) store.remove(key); else store.put(key, value);
                }
                @Override public void setInteger(String key, Integer value) {
                    if (value == null) store.remove(key); else store.put(key, String.valueOf(value));
                }
            });
        }
    }

    @Test
    void validate_defaults_shouldBeValid() {
        assertThat(McpConfig.getInstance().validate()).isEmpty();
    }

    @Test
    void validate_badPort_shouldFail() {
        McpConfig cfg = McpConfig.getInstance();
        int prev = cfg.getPort();
        cfg.setPort(99999);
        try {
            assertThat(cfg.validate()).anyMatch(e -> e.contains("Port"));
        } finally {
            cfg.setPort(prev);
        }
    }

    @Test
    void validate_authWithoutToken_shouldFail() {
        McpConfig cfg = McpConfig.getInstance();
        cfg.setAuthEnabled(true);
        cfg.setAuthToken("");
        try {
            assertThat(cfg.validate()).anyMatch(e -> e.contains("Auth token"));
        } finally {
            cfg.setAuthEnabled(false);
        }
    }

    @Test
    void validate_unknownTlsMode_shouldFail() {
        McpConfig cfg = McpConfig.getInstance();
        String prev = cfg.getTlsMode();
        cfg.setTlsMode("bogus");
        try {
            assertThat(cfg.validate()).anyMatch(e -> e.contains("TLS mode"));
        } finally {
            cfg.setTlsMode(prev);
        }
    }

    @Test
    void validate_customTlsWithoutPath_shouldFail() {
        McpConfig cfg = McpConfig.getInstance();
        boolean prevTls = cfg.isTlsEnabled();
        String prevMode = cfg.getTlsMode();
        String prevPath = cfg.getTlsKeystorePath();
        cfg.setTlsEnabled(true);
        cfg.setTlsMode("custom");
        cfg.setTlsKeystorePath("");
        try {
            assertThat(cfg.validate()).anyMatch(e -> e.contains("keystore"));
        } finally {
            cfg.setTlsEnabled(prevTls);
            cfg.setTlsMode(prevMode);
            cfg.setTlsKeystorePath(prevPath);
        }
    }

    @Test
    void socket_defaults_shouldBeEnabledWithTmpFallback() {
        McpConfig cfg = McpConfig.getInstance();
        java.util.function.Function<String, String> noPrefs = key -> null;
        assertThat(cfg.isSocketEnabled()).isTrue();
        assertThat(cfg.getSocketPath()).isEmpty();
        assertThat(cfg.resolveSocketPath("proj-a", "id-a", noPrefs).toString())
                .contains("burp-mcp-").endsWith(".sock");
        // Stable per project, distinct across projects.
        assertThat(cfg.resolveSocketPath("proj-a", "id-a", noPrefs))
                .isEqualTo(cfg.resolveSocketPath("proj-a", "id-a", noPrefs));
        assertThat(cfg.resolveSocketPath("proj-a", "id-a", noPrefs))
                .isNotEqualTo(cfg.resolveSocketPath("proj-b", "id-b", noPrefs));
    }

    @Test
    void socket_projectFileMatch_shouldSitNextToProject() {
        java.util.Map<String, String> prefs = new java.util.HashMap<>();
        prefs.put("burp.suite.recentProjectFiles0",
                "/home/nikolas/burp-projects/exness-20261004.burp");
        prefs.put("burp.suite.recentProjectNames0", "exness-20261004.burp");
        assertThat(McpConfig.getInstance()
                .resolveSocketPath("exness-20261004.burp", "id", prefs::get))
                .isEqualTo(java.nio.file.Paths.get(
                        "/home/nikolas/burp-projects/exness-20261004.sock"));
    }

    @Test
    void socket_projectNameMatchesRecentName_shouldUseThatFile() {
        // Burp records the display name separately from the file basename.
        java.util.Map<String, String> prefs = new java.util.HashMap<>();
        prefs.put("burp.suite.recentProjectFiles0",
                "/home/nikolas/git/burp-mcp/target/2026-10-04-dyson.burp");
        prefs.put("burp.suite.recentProjectNames0", "dyson");
        assertThat(McpConfig.getInstance().resolveSocketPath("dyson", "id", prefs::get))
                .isEqualTo(java.nio.file.Paths.get(
                        "/home/nikolas/git/burp-mcp/target/2026-10-04-dyson.sock"));
    }

    @Test
    void socket_explicitPath_shouldWinOverProjectFile() {
        McpConfig cfg = McpConfig.getInstance();
        String prev = cfg.getSocketPath();
        cfg.setSocketPath("/tmp/explicit.sock");
        try {
            java.util.Map<String, String> prefs = java.util.Map.of(
                    "burp.suite.recentProjectFiles0",
                    "/home/nikolas/burp-projects/exness-20261004.burp");
            assertThat(cfg.resolveSocketPath("exness-20261004.burp", "id", prefs::get))
                    .isEqualTo(java.nio.file.Paths.get("/tmp/explicit.sock"));
        } finally {
            cfg.setSocketPath(prev);
        }
    }

    @Test
    void socket_tooLongSiblingPath_shouldFallBackToTmp() {
        String longDir = "/tmp/" + "d".repeat(120);
        java.util.Map<String, String> prefs = java.util.Map.of(
                "burp.suite.recentProjectFiles0", longDir + "/proj.burp");
        java.nio.file.Path resolved =
                McpConfig.getInstance().resolveSocketPath("proj", "id", prefs::get);
        assertThat(resolved.toString()).contains("burp-mcp-");
        assertThat(resolved.toString()).doesNotContain("d".repeat(50));
    }

    @Test
    void validate_longExplicitSocketPath_shouldFail() {
        McpConfig cfg = McpConfig.getInstance();
        String prev = cfg.getSocketPath();
        boolean prevEnabled = cfg.isSocketEnabled();
        cfg.setSocketEnabled(true);
        cfg.setSocketPath("/tmp/" + "x".repeat(200) + ".sock");
        try {
            assertThat(cfg.validate()).anyMatch(e -> e.contains("Socket path"));
        } finally {
            cfg.setSocketPath(prev);
            cfg.setSocketEnabled(prevEnabled);
        }
    }
}
