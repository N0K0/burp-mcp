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
}
