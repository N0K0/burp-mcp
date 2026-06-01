package burp.mcp.server;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Version;
import burp.api.montoya.core.BurpSuiteEdition;
import burp.api.montoya.burpsuite.BurpSuite;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpJson;
import burp.mcp.util.PermissionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that start a real McPServer on a random port
 * and test the full JSON-RPC 2.0 HTTP lifecycle.
 */
class McPServerIntegrationTest {

    private static McPServer server;
    private static int port;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static MontoyaApi mockApi;
    private static McpToolRegistry registry;

    @BeforeAll
    static void startServer() throws Exception {
        // Find a free port
        port = findFreePort();

        // Configure with test port
        mockApi = createMockApi();
        McpConfig.initialize(new McpConfig.Preferences() {
            private final java.util.Map<String, String> store = new java.util.concurrent.ConcurrentHashMap<>();
            @Override public String getString(String key) { return store.get(key); }
            @Override public Integer getInteger(String key) {
                String v = store.get(key); return v != null ? Integer.parseInt(v) : null;
            }
            @Override public void setString(String key, String value) { store.put(key, value); }
            @Override public void setInteger(String key, Integer value) { store.put(key, String.valueOf(value)); }
        });
        McpConfig.getInstance().setPort(port);

        registry = new McpToolRegistry(mockApi);
        registry.registerAllTools();

        server = new McPServer(mockApi, registry, "127.0.0.1", port, new PermissionManager());
        server.start();
        // Give it a moment
        Thread.sleep(200);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    @BeforeEach
    void resetPermissions() {
        // Reset to defaults between tests
        server.getPermissions().setLevel(PermissionManager.Level.READ_WRITE);
        server.getPermissions().setBlockSensitive(false);
    }

    // ── Protocol Tests ───────────────────────────────────────────

    @Test
    void initialize_shouldReturnProtocolVersion() throws Exception {
        String response = jsonRpc("initialize", null);
        JsonNode result = mapper.readTree(response).get("result");
        assertThat(result.get("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(result.get("serverInfo").get("name").asText()).isEqualTo("burp-mcp");
    }

    @Test
    void toolsList_shouldReturnAllTools() throws Exception {
        String response = jsonRpc("tools/list", null);
        JsonNode tools = mapper.readTree(response).get("result").get("tools");
        assertThat(tools).isNotNull();
        assertThat(tools.size()).isGreaterThanOrEqualTo(40);
    }

    @Test
    void toolsCall_burpInfo_shouldReturnServerInfo() throws Exception {
        String params = "{\"name\":\"burp_info\",\"arguments\":{}}";
        String response = jsonRpc("tools/call", params);
        JsonNode result = mapper.readTree(response).get("result");
        assertThat(result).isNotNull();
        // Check content is present
        String contentText = result.get("content").get(0).get("text").asText();
        JsonNode info = mapper.readTree(contentText);
        assertThat(info.has("burpVersion")).isTrue();
        assertThat(info.has("mcpServerVersion")).isTrue();
        assertThat(info.has("port")).isTrue();
    }

    @Test
    void toolsCall_unknownTool_shouldReturnMethodNotFound() throws Exception {
        String params = "{\"name\":\"nonexistent_tool\",\"arguments\":{}}";
        String response = jsonRpc("tools/call", params);
        JsonNode error = mapper.readTree(response).get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void toolsCall_missingParams_shouldReturnInvalidParams() throws Exception {
        String response = jsonRpc("tools/call", null);
        JsonNode error = mapper.readTree(response).get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void invalidJson_shouldReturnParseError() throws Exception {
        URL url = URI.create("http://127.0.0.1:" + port + "/").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("not json".getBytes(StandardCharsets.UTF_8));
        }
        java.io.InputStream is = conn.getResponseCode() >= 400
                ? conn.getErrorStream() : conn.getInputStream();
        String body = new String(is.readAllBytes());
        JsonNode error = mapper.readTree(body).get("error");
        assertThat(error).isNotNull();
    }

    @Test
    void getMethod_shouldReturnMethodNotAllowed() throws Exception {
        URL url = URI.create("http://127.0.0.1:" + port + "/").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        assertThat(code).isEqualTo(405);
    }

    // ── Schema Tests ──────────────────────────────────────────────

    @Test
    void allToolSchemas_shouldHaveValidRequiredArrays() throws Exception {
        String response = jsonRpc("tools/list", null);
        JsonNode tools = mapper.readTree(response).get("result").get("tools");
        for (JsonNode tool : tools) {
            String name = tool.get("name").asText();
            JsonNode inputSchema = tool.get("inputSchema");

            // Must have type
            assertThat(inputSchema.has("type"))
                    .as("Tool '%s' missing type", name).isTrue();

            // Must have properties (even if empty)
            assertThat(inputSchema.has("properties"))
                    .as("Tool '%s' missing properties", name).isTrue();

            // Required must be array, not object
            assertThat(inputSchema.has("required"))
                    .as("Tool '%s' missing required field", name).isTrue();
            assertThat(inputSchema.get("required").isArray())
                    .as("Tool '%s' required is not an array: %s",
                            name, inputSchema.get("required")).isTrue();
        }
    }

    @Test
    void allToolSchemas_propertiesShouldHaveTypeAndDescription() throws Exception {
        String response = jsonRpc("tools/list", null);
        JsonNode tools = mapper.readTree(response).get("result").get("tools");
        int checked = 0;
        for (JsonNode tool : tools) {
            JsonNode props = tool.get("inputSchema").get("properties");
            if (props.size() > 0) {
                java.util.Iterator<String> fieldNames = props.fieldNames();
                while (fieldNames.hasNext()) {
                    String key = fieldNames.next();
                    JsonNode prop = props.get(key);
                    assertThat(prop.isObject())
                            .as("Tool '%s' prop '%s' is not an object",
                                    tool.get("name").asText(), key).isTrue();
                    assertThat(prop.has("type"))
                            .as("Tool '%s' prop '%s' missing type",
                                    tool.get("name").asText(), key).isTrue();
                    assertThat(prop.has("description"))
                            .as("Tool '%s' prop '%s' missing description",
                                    tool.get("name").asText(), key).isTrue();
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(0);
    }

    // ── Permission Tests ──────────────────────────────────────────

    @Test
    void readOnlyMode_shouldDenyWriteTool() throws Exception {
        server.getPermissions().setLevel(PermissionManager.Level.READ_ONLY);

        String params = "{\"name\":\"http_send_request\",\"arguments\":{\"url\":\"http://example.com\"}}";
        String response = jsonRpc("tools/call", params);
        JsonNode error = mapper.readTree(response).get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code").asInt()).isEqualTo(-32005);
        assertThat(error.get("message").asText()).contains("READ_ONLY");
    }

    @Test
    void readOnlyMode_shouldAllowReadTool() throws Exception {
        server.getPermissions().setLevel(PermissionManager.Level.READ_ONLY);

        String params = "{\"name\":\"burp_info\",\"arguments\":{}}";
        String response = jsonRpc("tools/call", params);
        JsonNode result = mapper.readTree(response).get("result");
        assertThat(result).isNotNull();
    }

    @Test
    void sensitivityBlocked_shouldDenySensitive() throws Exception {
        server.getPermissions().setBlockSensitive(true);

        String params = "{\"name\":\"scope_set\",\"arguments\":{}}";
        String response = jsonRpc("tools/call", params);
        JsonNode error = mapper.readTree(response).get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("message").asText()).contains("sensitive");
    }

    @Test
    void customMode_disabledTool_shouldDeny() throws Exception {
        server.getPermissions().setLevel(PermissionManager.Level.CUSTOM);
        server.getPermissions().disableTool("burp_info");

        String params = "{\"name\":\"burp_info\",\"arguments\":{}}";
        String response = jsonRpc("tools/call", params);
        JsonNode error = mapper.readTree(response).get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("message").asText()).contains("CUSTOM");
    }

    // ── Auth Tests ─────────────────────────────────────────────────

    @Test
    void authDisabled_shouldAllowWithoutToken() throws Exception {
        String params = "{\"name\":\"burp_info\",\"arguments\":{}}";
        String response = jsonRpc("tools/call", params);
        JsonNode result = mapper.readTree(response).get("result");
        assertThat(result).isNotNull();
    }

    @Test
    void authEnabled_shouldDenyWithoutToken() throws Exception {
        McpConfig.getInstance().setAuthEnabled(true);
        McpConfig.getInstance().setAuthToken("test-token-123");

        try {
            String params = "{\"name\":\"burp_info\",\"arguments\":{}}";
            URL url = URI.create("http://127.0.0.1:" + port + "/").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            String body = buildJsonRpcBody("tools/call", params);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            assertThat(code).isEqualTo(401);
        } finally {
            McpConfig.getInstance().setAuthEnabled(false);
            McpConfig.getInstance().setAuthToken("");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private String jsonRpc(String method, String params) throws Exception {
        URL url = URI.create("http://127.0.0.1:" + port + "/").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String body = buildJsonRpcBody(method, params);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        // Read error stream for non-2xx, or input stream for 2xx
        java.io.InputStream is = conn.getResponseCode() >= 400
                ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String buildJsonRpcBody(String method, String params) {
        if (params != null) {
            return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method
                    + "\",\"params\":" + params + ",\"id\":1}";
        }
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"id\":1}";
    }

    private static int findFreePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static MontoyaApi createMockApi() {
        // Use reflection proxy to avoid implementing every MontoyaApi method
        // (the API interface varies between versions)
        return (MontoyaApi) java.lang.reflect.Proxy.newProxyInstance(
            MontoyaApi.class.getClassLoader(),
            new Class<?>[] { MontoyaApi.class },
            (proxy, method, args) -> {
                String methodName = method.getName();
                // Stub burpSuite().version() for burp_info tool
                if ("burpSuite".equals(methodName)) {
                    return java.lang.reflect.Proxy.newProxyInstance(
                        BurpSuite.class.getClassLoader(),
                        new Class<?>[] { BurpSuite.class },
                        (p2, m2, a2) -> {
                            if ("version".equals(m2.getName())) {
                                return java.lang.reflect.Proxy.newProxyInstance(
                                    Version.class.getClassLoader(),
                                    new Class<?>[] { Version.class },
                                    (p3, m3, a3) -> {
                                        switch (m3.getName()) {
                                            case "name": return "Burp Suite Professional (test)";
                                            case "edition": return BurpSuiteEdition.PROFESSIONAL;
                                            default: return null;
                                        }
                                    });
                            }
                            return null;
                        });
                }
                // Stub logging for quiet operation
                if ("logging".equals(methodName)) {
                    return java.lang.reflect.Proxy.newProxyInstance(
                        Logging.class.getClassLoader(),
                        new Class<?>[] { Logging.class },
                        (p2, m2, a2) -> null);
                }
                // Stub persistence with empty prefs
                if ("persistence".equals(methodName)) {
                    return java.lang.reflect.Proxy.newProxyInstance(
                        Persistence.class.getClassLoader(),
                        new Class<?>[] { Persistence.class },
                        (p2, m2, a2) -> {
                            if ("preferences".equals(m2.getName())) {
                                return java.lang.reflect.Proxy.newProxyInstance(
                                    Preferences.class.getClassLoader(),
                                    new Class<?>[] { Preferences.class },
                                    (p3, m3, a3) -> null);
                            }
                            return null;
                        });
                }
                // All other MontoyaApi methods return null
                return null;
            });
    }
}
