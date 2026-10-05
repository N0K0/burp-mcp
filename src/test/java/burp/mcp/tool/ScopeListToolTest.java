package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.burpsuite.BurpSuite;
import burp.api.montoya.scope.Scope;
import burp.api.montoya.sitemap.SiteMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scope_list must reflect configured includes even with an empty sitemap.
 * MontoyaApi is a reflection proxy; only Jackson parsing is exercised.
 */
class ScopeListToolTest {

    private static final String CONFIG = "{"
            + "\"target\":{\"scope\":{\"advanced_mode\":false,"
            + "\"include\":["
            + "{\"enabled\":true,\"prefix\":\"https://target.test/\"},"
            + "{\"enabled\":true,\"prefix\":\"https://target.test/app\"},"
            + "{\"enabled\":false,\"prefix\":\"https://other.test/\"}],"
            + "\"exclude\":[{\"enabled\":true,\"prefix\":\"https://target.test/logout\"}]"
            + "}}}";

    @Test
    @SuppressWarnings("unchecked")
    void execute_shouldReportConfiguredIncludesWithEmptySitemap() {
        ScopeListTool tool = new ScopeListTool(mockApi(CONFIG, false));
        Object result = tool.execute(Map.of());

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> out = (Map<String, Object>) result;
        Map<String, Object> configured = (Map<String, Object>) out.get("configured_scope");
        assertThat(configured).isNotNull();
        assertThat(configured.get("advanced_mode")).isEqualTo(false);
        assertThat(configured.get("include_count")).isEqualTo(2);
        assertThat((List<String>) configured.get("includes"))
                .containsExactly("https://target.test/", "https://target.test/app");
        assertThat((List<String>) configured.get("excludes"))
                .containsExactly("https://target.test/logout");
        // Observed side still present (empty traffic).
        assertThat(out.get("count")).isEqualTo(0);
        assertThat((List<String>) out.get("urls")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_shouldSummarizeAdvancedRulesWithoutPrefix() {
        String advanced = "{\"target\":{\"scope\":{\"advanced_mode\":true,"
                + "\"include\":[{\"enabled\":true,\"protocol\":\"https\",\"host\":\"target.test\"}],"
                + "\"exclude\":[]}}}";
        ScopeListTool tool = new ScopeListTool(mockApi(advanced, false));
        Map<String, Object> out = (Map<String, Object>) tool.execute(Map.of());

        Map<String, Object> configured = (Map<String, Object>) out.get("configured_scope");
        assertThat(configured.get("advanced_mode")).isEqualTo(true);
        List<String> includes = (List<String>) configured.get("includes");
        assertThat(includes).hasSize(1);
        assertThat(includes.get(0)).startsWith("advanced:");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_shouldFallBackWhenExportFails() {
        ScopeListTool tool = new ScopeListTool(mockApi(CONFIG, true));
        Map<String, Object> out = (Map<String, Object>) tool.execute(Map.of());

        assertThat(out).doesNotContainKey("configured_scope");
        assertThat(out.get("configured_scope_unavailable")).isNotNull();
        assertThat(out.get("count")).isEqualTo(0);
    }

    private static MontoyaApi mockApi(String configJson, boolean exportFails) {
        return (MontoyaApi) java.lang.reflect.Proxy.newProxyInstance(
            MontoyaApi.class.getClassLoader(),
            new Class<?>[] { MontoyaApi.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "burpSuite" -> java.lang.reflect.Proxy.newProxyInstance(
                    BurpSuite.class.getClassLoader(),
                    new Class<?>[] { BurpSuite.class },
                    (p2, m2, a2) -> {
                        if ("exportProjectOptionsAsJson".equals(m2.getName())) {
                            if (exportFails) throw new RuntimeException("export boom");
                            return configJson;
                        }
                        return null;
                    });
                case "siteMap" -> java.lang.reflect.Proxy.newProxyInstance(
                    SiteMap.class.getClassLoader(),
                    new Class<?>[] { SiteMap.class },
                    (p2, m2, a2) -> {
                        if ("requestResponses".equals(m2.getName()) && (a2 == null || a2.length == 0)) {
                            return List.of();
                        }
                        return null;
                    });
                case "scope" -> java.lang.reflect.Proxy.newProxyInstance(
                    Scope.class.getClassLoader(),
                    new Class<?>[] { Scope.class },
                    (p2, m2, a2) -> {
                        if ("isInScope".equals(m2.getName())) return true;
                        return null;
                    });
                default -> null;
            });
    }
}
