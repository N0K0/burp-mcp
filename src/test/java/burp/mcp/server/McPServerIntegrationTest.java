package burp.mcp.server;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Version;
import burp.api.montoya.core.BurpSuiteEdition;
import burp.api.montoya.burpsuite.BurpSuite;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;
import burp.api.montoya.project.Project;
import burp.api.montoya.scope.Scope;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.tool.TargetedTool;
import burp.mcp.tool.Tool;
import burp.mcp.tool.ToolDefinition;
import burp.mcp.util.ApprovalManager;
import burp.mcp.util.McpConfig;
import burp.mcp.util.McpJson;
import burp.mcp.util.PermissionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

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
    private static PermissionManager permissions;
    private static ApprovalManager approvals;

    // Gate seams: tests flip these between cases.
    private static final AtomicReference<String> scopeEnforcement = new AtomicReference<>("off");
    private static final AtomicReference<Integer> approvalWaitSeconds = new AtomicReference<>(1);
    private static final AtomicReference<Predicate<String>> scopeInScope =
            new AtomicReference<>(url -> true);
    private static final List<String> scopeIncluded =
            Collections.synchronizedList(new ArrayList<>());

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
        // Gate tests make many rapid calls; the per-IP rate limiter is not under test here.
        McpConfig.getInstance().setRateLimitPerMinute(0);

        registry = new McpToolRegistry(mockApi);
        registry.registerAllTools();
        // Deterministic tools for gate tests (no Montoya dependencies)
        registry.register(new TestTool("test_echo", null));
        registry.register(new TestTool("test_targeted", "http://targeted.test/"));

        permissions = new PermissionManager();
        approvals = new ApprovalManager();
        AccessGate gate = new AccessGate(mockApi, permissions, approvals, new AccessGate.Settings() {
            @Override public String scopeEnforcement() { return scopeEnforcement.get(); }
            @Override public int approvalWaitSeconds() { return approvalWaitSeconds.get(); }
            @Override public int approvalTtlSeconds() { return 60; }
        });

        server = new McPServer(mockApi, registry, "127.0.0.1", port, permissions, null, gate);
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
        permissions.setLevel(PermissionManager.Level.READ_WRITE);
        permissions.setBlockSensitive(false);
        permissions.setToolPolicies(Map.of());
        approvals.clearAll();
        scopeEnforcement.set("off");
        approvalWaitSeconds.set(1);
        scopeInScope.set(url -> true);
        scopeIncluded.clear();
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
        assertThat(tools.size()).isGreaterThanOrEqualTo(44);
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
        assertThat(info.get("projectName").asText()).isEqualTo("Test Project");
        assertThat(info.get("projectId").asText()).isEqualTo("test-project-id");
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

    @Test
    void customMode_aliasOfDisabledTool_shouldDeny() throws Exception {
        server.getPermissions().setLevel(PermissionManager.Level.CUSTOM);
        server.getPermissions().disableTool("http_send_request");
        try {
            String params = "{\"name\":\"send_request\",\"arguments\":{\"url\":\"http://example.com\"}}";
            String response = jsonRpc("tools/call", params);
            JsonNode error = mapper.readTree(response).get("error");
            assertThat(error).isNotNull();
            assertThat(error.get("code").asInt()).isEqualTo(-32005);
        } finally {
            server.getPermissions().enableTool("http_send_request");
        }
    }

    @Test
    void connectionCap_shouldRejectOverLimit() throws Exception {
        McpConfig.getInstance().setMaxConnectionsPerIp(2);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread t1 = new Thread(() -> {
            try { server.serve(stubSession(new GateStream(inside, release))); } catch (Exception ignored) {}
        });
        Thread t2 = new Thread(() -> {
            try { server.serve(stubSession(new GateStream(inside, release))); } catch (Exception ignored) {}
        });
        try {
            t1.start();
            t2.start();
            assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // Third concurrent connection exceeds the cap of 2
            fi.iki.elonen.NanoHTTPD.Response resp =
                    server.serve(stubSession(new java.io.ByteArrayInputStream(new byte[0])));
            assertThat(resp.getStatus().getRequestStatus()).isEqualTo(503);
        } finally {
            release.countDown();
            t1.join(5000);
            t2.join(5000);
            McpConfig.getInstance().setMaxConnectionsPerIp(10);
        }
    }

    /** IHTTPSession stub backed by the given body stream. */
    private fi.iki.elonen.NanoHTTPD.IHTTPSession stubSession(java.io.InputStream in) {
        java.util.Map<String, String> headers = java.util.Map.of("content-length", "4");
        return (fi.iki.elonen.NanoHTTPD.IHTTPSession) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{fi.iki.elonen.NanoHTTPD.IHTTPSession.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getMethod": return fi.iki.elonen.NanoHTTPD.Method.POST;
                        case "getUri": return "/";
                        case "getHeaders": return headers;
                        case "getInputStream": return in;
                        case "getRemoteIpAddress": return "127.0.0.1";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    /** Blocks the first read until released, then signals EOF. */
    private static class GateStream extends java.io.InputStream {
        private final java.util.concurrent.CountDownLatch inside;
        private final java.util.concurrent.CountDownLatch release;
        private boolean entered = false;

        GateStream(java.util.concurrent.CountDownLatch inside,
                   java.util.concurrent.CountDownLatch release) {
            this.inside = inside;
            this.release = release;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (!entered) {
                entered = true;
                inside.countDown();
            }
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public int read() {
            return -1;
        }
    }

    // ── Approval / scope gate tests ───────────────────────────────

    @Test
    void promptMode_allowOnceViaListener_shouldExecuteTool() throws Exception {
        permissions.setLevel(PermissionManager.Level.CUSTOM);
        permissions.setToolPolicy("test_echo", PermissionManager.Policy.PROMPT);
        ApprovalManager.Listener autoAllow = new ApprovalManager.Listener() {
            @Override
            public void onPendingAdded(ApprovalManager.PendingApproval pending) {
                approvals.resolve(pending.getId(), new ApprovalManager.Decision(
                        ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null));
            }
        };
        approvals.addListener(autoAllow);
        try {
            String response = jsonRpc("tools/call",
                    "{\"name\":\"test_echo\",\"arguments\":{\"value\":\"hello\"}}");
            JsonNode result = mapper.readTree(response).get("result");
            assertThat(result).as("response: %s", response).isNotNull();
            assertThat(result.get("content").get(0).get("text").asText()).contains("hello");
            assertThat(approvals.pendingCount()).isZero();
        } finally {
            approvals.removeListener(autoAllow);
        }
    }

    @Test
    void promptMode_denyWithReason_shouldReturnStructuredError() throws Exception {
        permissions.setLevel(PermissionManager.Level.CUSTOM);
        permissions.setToolPolicy("test_echo", PermissionManager.Policy.PROMPT);
        ApprovalManager.Listener autoDeny = new ApprovalManager.Listener() {
            @Override
            public void onPendingAdded(ApprovalManager.PendingApproval pending) {
                approvals.resolve(pending.getId(), new ApprovalManager.Decision(
                        ApprovalManager.PermissionDecision.DENY, null, "no thanks"));
            }
        };
        approvals.addListener(autoDeny);
        try {
            String response = jsonRpc("tools/call",
                    "{\"name\":\"test_echo\",\"arguments\":{\"value\":\"hello\"}}");
            JsonNode error = mapper.readTree(response).get("error");
            assertThat(error).isNotNull();
            assertThat(error.get("code").asInt()).isEqualTo(-32005);
            assertThat(error.get("message").asText()).contains("no thanks");
            assertThat(error.get("data").get("reason").asText()).isEqualTo("no thanks");
            assertThat(error.get("data").get("source").asText()).isEqualTo("operator");
            assertThat(error.get("data").get("permission_denied").asBoolean()).isTrue();
        } finally {
            approvals.removeListener(autoDeny);
        }
    }

    @Test
    void promptMode_pendingThenRetry_shouldExecute() throws Exception {
        permissions.setLevel(PermissionManager.Level.CUSTOM);
        permissions.setToolPolicy("test_echo", PermissionManager.Policy.PROMPT);
        approvalWaitSeconds.set(0); // no blocking: respond pending immediately

        String params = "{\"name\":\"test_echo\",\"arguments\":{\"value\":\"retry\"}}";
        String first = jsonRpc("tools/call", params);
        JsonNode error = mapper.readTree(first).get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code").asInt()).isEqualTo(-32008);
        String approvalId = error.get("data").get("approval_id").asText();
        assertThat(approvalId).isNotBlank();
        assertThat(approvals.pendingCount()).isEqualTo(1);

        assertThat(approvals.resolve(approvalId, new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null))).isTrue();

        String retry = jsonRpc("tools/call", params);
        JsonNode result = mapper.readTree(retry).get("result");
        assertThat(result).isNotNull();
        assertThat(result.get("content").get(0).get("text").asText()).contains("retry");
        assertThat(approvals.pendingCount()).isZero();
    }

    @Test
    void promptMode_cancelWhileWaiting_shouldReturnApprovalTimeout() throws Exception {
        permissions.setLevel(PermissionManager.Level.CUSTOM);
        permissions.setToolPolicy("test_echo", PermissionManager.Policy.PROMPT);
        approvalWaitSeconds.set(2); // long enough that the cancel lands during the wait

        String params = "{\"name\":\"test_echo\",\"arguments\":{\"value\":\"expire\"}}";
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<String> pendingResponse = pool.submit(() -> {
                try {
                    return jsonRpc("tools/call", params);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Wait for the gate to queue the request, then cancel it.
            String approvalId = null;
            long deadline = System.currentTimeMillis() + 5000;
            while (approvalId == null && System.currentTimeMillis() < deadline) {
                var pending = approvals.getPending();
                if (!pending.isEmpty()) {
                    approvalId = pending.get(0).getId();
                } else {
                    Thread.sleep(10);
                }
            }
            assertThat(approvalId).isNotNull();
            assertThat(approvals.cancel(approvalId)).isTrue();

            JsonNode error = mapper.readTree(pendingResponse.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .get("error");
            assertThat(error).isNotNull();
            assertThat(error.get("code").asInt()).isEqualTo(-32009);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void outOfScope_denyEnforcement_shouldReturnOutOfScopeError() throws Exception {
        scopeEnforcement.set("deny");
        scopeInScope.set(url -> false);

        String response = jsonRpc("tools/call",
                "{\"name\":\"test_targeted\",\"arguments\":{\"value\":\"x\"}}");
        JsonNode error = mapper.readTree(response).get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code").asInt()).isEqualTo(-32010);
        assertThat(error.get("data").get("out_of_scope").asBoolean()).isTrue();
        assertThat(error.get("data").get("targets").get(0).asText()).isEqualTo("http://targeted.test/");
    }

    @Test
    void outOfScope_promptAddToScope_shouldIncludeOriginAndExecute() throws Exception {
        scopeEnforcement.set("prompt");
        scopeInScope.set(url -> scopeIncluded.contains(AccessGate.originOf(url)));
        ApprovalManager.Listener addScope = new ApprovalManager.Listener() {
            @Override
            public void onPendingAdded(ApprovalManager.PendingApproval pending) {
                approvals.resolve(pending.getId(), new ApprovalManager.Decision(
                        null, ApprovalManager.ScopeDecision.ADD_TO_SCOPE, null));
            }
        };
        approvals.addListener(addScope);
        try {
            String response = jsonRpc("tools/call",
                    "{\"name\":\"test_targeted\",\"arguments\":{\"value\":\"x\"}}");
            assertThat(mapper.readTree(response).get("result")).isNotNull();
            assertThat(scopeIncluded).containsExactly("http://targeted.test");
        } finally {
            approvals.removeListener(addScope);
        }
    }

    @Test
    void outOfScope_promptDeny_shouldReturnOperatorReason() throws Exception {
        scopeEnforcement.set("prompt");
        scopeInScope.set(url -> false);
        ApprovalManager.Listener denyScope = new ApprovalManager.Listener() {
            @Override
            public void onPendingAdded(ApprovalManager.PendingApproval pending) {
                approvals.resolve(pending.getId(), new ApprovalManager.Decision(
                        null, ApprovalManager.ScopeDecision.DENY, "prod is off limits"));
            }
        };
        approvals.addListener(denyScope);
        try {
            String response = jsonRpc("tools/call",
                    "{\"name\":\"test_targeted\",\"arguments\":{\"value\":\"x\"}}");
            JsonNode error = mapper.readTree(response).get("error");
            assertThat(error).isNotNull();
            assertThat(error.get("code").asInt()).isEqualTo(-32005);
            assertThat(error.get("data").get("scope_denied").asBoolean()).isTrue();
            assertThat(error.get("data").get("reason").asText()).isEqualTo("prod is off limits");
        } finally {
            approvals.removeListener(denyScope);
        }
    }

    @Test
    void promptMode_sessionGrant_shouldExecuteSubsequentCallsWithoutPrompt() throws Exception {
        permissions.setLevel(PermissionManager.Level.CUSTOM);
        permissions.setToolPolicy("test_echo", PermissionManager.Policy.PROMPT);
        ApprovalManager.Listener sessionAllow = new ApprovalManager.Listener() {
            @Override
            public void onPendingAdded(ApprovalManager.PendingApproval pending) {
                approvals.resolve(pending.getId(), new ApprovalManager.Decision(
                        ApprovalManager.PermissionDecision.ALLOW_SESSION, null, null));
            }
        };
        approvals.addListener(sessionAllow);
        try {
            assertThat(mapper.readTree(jsonRpc("tools/call",
                    "{\"name\":\"test_echo\",\"arguments\":{\"value\":\"one\"}}")).get("result")).isNotNull();

            String second = jsonRpc("tools/call",
                    "{\"name\":\"test_echo\",\"arguments\":{\"value\":\"two\"}}");
            assertThat(mapper.readTree(second).get("result")).isNotNull();
            assertThat(approvals.pendingCount()).isZero();
        } finally {
            approvals.removeListener(sessionAllow);
        }
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
                // Stub project() for burp_info project fields
                if ("project".equals(methodName)) {
                    return java.lang.reflect.Proxy.newProxyInstance(
                        Project.class.getClassLoader(),
                        new Class<?>[] { Project.class },
                        (p2, m2, a2) -> {
                            switch (m2.getName()) {
                                case "name": return "Test Project";
                                case "id": return "test-project-id";
                                default: return null;
                            }
                        });
                }
                // Stub logging for quiet operation
                if ("logging".equals(methodName)) {
                    return java.lang.reflect.Proxy.newProxyInstance(
                        Logging.class.getClassLoader(),
                        new Class<?>[] { Logging.class },
                        (p2, m2, a2) -> null);
                }
                // Stub scope for the access-gate tests
                if ("scope".equals(methodName)) {
                    return java.lang.reflect.Proxy.newProxyInstance(
                        Scope.class.getClassLoader(),
                        new Class<?>[] { Scope.class },
                        (p2, m2, a2) -> {
                            switch (m2.getName()) {
                                case "isInScope":
                                    return scopeInScope.get().test((String) a2[0]);
                                case "includeInScope":
                                    scopeIncluded.add((String) a2[0]);
                                    return null;
                                default:
                                    return null;
                            }
                        });
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

    /** Minimal deterministic tool for gate tests; optional fixed outbound target. */
    private static final class TestTool implements Tool, TargetedTool {
        private final String name;
        private final String target;

        TestTool(String name, String target) {
            this.name = name;
            this.target = target;
        }

        @Override
        public ToolDefinition definition() {
            ObjectNode schema = McpJson.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = McpJson.createObjectNode();
            props.set("value", McpJson.property("string", "Value echoed back"));
            schema.set("properties", props);
            schema.set("required", McpJson.createArrayNode());
            return new ToolDefinition(name, "Integration-test tool", schema);
        }

        @Override
        public ObjectNode inputSchema() {
            return definition().inputSchema();
        }

        @Override
        public Object execute(Map<String, Object> args) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("echo", args.getOrDefault("value", "ok"));
            return result;
        }

        @Override
        public List<String> targetUrls(Map<String, Object> args) {
            return target != null ? List.of(target) : List.of();
        }
    }
}
