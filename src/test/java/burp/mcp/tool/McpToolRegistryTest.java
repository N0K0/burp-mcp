package burp.mcp.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRegistryTest {

    private McpToolRegistry registry() {
        McpToolRegistry registry = new McpToolRegistry(null);
        registry.registerAllTools();
        return registry;
    }

    @Test
    void registerAllTools_shouldRegister52Tools() {
        assertThat(registry().toolCount()).isEqualTo(52);
    }

    @Test
    void canonicalName_shouldResolveAliases() {
        assertThat(McpToolRegistry.canonicalName("proxy_list")).isEqualTo("proxy_history_list");
        assertThat(McpToolRegistry.canonicalName("proxy_get")).isEqualTo("proxy_history_get");
        assertThat(McpToolRegistry.canonicalName("sitemap_url")).isEqualTo("sitemap_get");
        assertThat(McpToolRegistry.canonicalName("send_request")).isEqualTo("http_send_request");
        assertThat(McpToolRegistry.canonicalName("send_to_repeater")).isEqualTo("http_send_to_repeater");
    }

    @Test
    void canonicalName_shouldKeepCanonicalAndNull() {
        assertThat(McpToolRegistry.canonicalName("http_send_request")).isEqualTo("http_send_request");
        assertThat(McpToolRegistry.canonicalName(null)).isNull();
    }

    @Test
    void getTool_shouldResolveAliasToCanonical() {
        McpToolRegistry registry = registry();
        assertThat(registry.getTool("send_request")).isNotNull();
        assertThat(registry.getTool("send_request").definition().name()).isEqualTo("http_send_request");
        assertThat(registry.getTool("unknown_tool_xyz")).isNull();
    }
}
