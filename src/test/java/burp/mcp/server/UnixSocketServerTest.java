package burp.mcp.server;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.burpsuite.BurpSuite;
import burp.api.montoya.core.BurpSuiteEdition;
import burp.api.montoya.core.Version;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.util.McpConfig;
import burp.mcp.util.PermissionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;

import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live Unix-domain-socket tests: real UnixSocketServer + real McPServer
 * dispatch (TCP never started), exercised over real socket connections.
 */
class UnixSocketServerTest {

    @TempDir
    static Path tmp;

    private static final ObjectMapper mapper = new ObjectMapper();
    private static UnixSocketServer socketServer;
    private static Path socketPath;
    private static MontoyaApi mockApi;
    private static McpToolRegistry registry;

    @BeforeAll
    static void startSocketServer() throws Exception {
        if (McpConfig.getInstance() == null) {
            McpConfig.initialize(new McpConfig.Preferences() {
                private final java.util.Map<String, String> store = new ConcurrentHashMap<>();
                @Override public String getString(String key) { return store.get(key); }
                @Override public Integer getInteger(String key) {
                    String v = store.get(key); return v != null ? Integer.parseInt(v) : null;
                }
                @Override public void setString(String key, String value) {
                    if (value == null) store.remove(key); else store.put(key, value);
                }
                @Override public void setInteger(String key, Integer value) {
                    if (value == null) store.remove(key); else store.put(key, String.valueOf(value));
                }
            });
        }
        mockApi = createMockApi();
        registry = new McpToolRegistry(mockApi);
        registry.registerAllTools();
        // TCP is never started — McPServer is only used for serve() dispatch.
        McPServer server = new McPServer(mockApi, registry, "127.0.0.1", 0, new PermissionManager());

        // Stale socket file must be replaced, not fatal.
        socketPath = tmp.resolve("test-mcp.sock");
        Files.writeString(socketPath, "stale");
        socketServer = new UnixSocketServer(server, socketPath, 4);
        socketServer.start();
    }

    @AfterEach
    void assertSocketFileLive() {
        // Every test leaves the server running on its socket file.
        assertThat(socketServer.isRunning()).isTrue();
        assertThat(Files.exists(socketPath)).isTrue();
    }

    @AfterAll
    static void stopSocketServer() {
        socketServer.stop();
        assertThat(socketServer.isRunning()).isFalse();
        assertThat(Files.notExists(socketPath)).isTrue();
    }

    @Test
    void uds_post_toolsCall_shouldReturnBurpInfo() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"burp_info\",\"arguments\":{}},\"id\":1}";
        RawResponse res = post("/", body);
        assertThat(res.statusLine).startsWith("HTTP/1.1 200 ");
        JsonNode rpc = mapper.readTree(res.body);
        assertThat(rpc.get("result").get("content").get(0).get("text").asText())
                .contains("Burp Suite Professional (test)");
    }

    @Test
    void uds_post_toolsList_shouldEnumerateTools() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}";
        RawResponse res = post("/", body);
        assertThat(res.statusLine).startsWith("HTTP/1.1 200 ");
        JsonNode tools = mapper.readTree(res.body).get("result").get("tools");
        assertThat(tools.size()).isGreaterThanOrEqualTo(45);
    }

    @Test
    void uds_getHealth_shouldReturnOk() throws Exception {
        RawResponse res = request("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat(res.statusLine).startsWith("HTTP/1.1 200 ");
        assertThat(res.body).contains("\"status\":\"ok\"");
    }

    @Test
    void uds_getRoot_shouldReturn405() throws Exception {
        RawResponse res = request("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat(res.statusLine).startsWith("HTTP/1.1 405 ");
    }

    @Test
    void uds_post_unknownMethod_shouldReturnMethodNotFound() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"nope\",\"id\":1}";
        RawResponse res = post("/", body);
        assertThat(res.statusLine).startsWith("HTTP/1.1 200 ");
        assertThat(mapper.readTree(res.body).get("error").get("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void uds_restart_shouldRebindSamePath() throws Exception {
        Path restartPath = tmp.resolve("restart.sock");
        McPServer server = new McPServer(mockApi, registry, "127.0.0.1", 0, new PermissionManager());
        UnixSocketServer uds = new UnixSocketServer(server, restartPath, 2);
        try {
            uds.start();
            assertThat(uds.isRunning()).isTrue();
            assertThat(Files.exists(restartPath)).isTrue();
            assertThat(request(restartPath, healthRequest()).statusLine)
                    .startsWith("HTTP/1.1 200 ");

            uds.stop();
            assertThat(uds.isRunning()).isFalse();
            assertThat(Files.notExists(restartPath)).isTrue();

            uds.start();
            assertThat(uds.isRunning()).isTrue();
            assertThat(Files.exists(restartPath)).isTrue();
            assertThat(request(restartPath, healthRequest()).statusLine)
                    .startsWith("HTTP/1.1 200 ");
        } finally {
            uds.stop();
        }
    }

    // ── Helpers ────────────────────────────────────────────────

    private record RawResponse(String statusLine, String body) {}

    private RawResponse post(String path, String jsonBody) throws Exception {
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        String head = "POST " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer req = ByteBuffer.allocate(headBytes.length + bodyBytes.length);
        req.put(headBytes).put(bodyBytes).flip();
        return request(req);
    }

    private RawResponse request(String raw) throws Exception {
        return request(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
    }

    private RawResponse request(ByteBuffer req) throws Exception {
        return request(socketPath, req);
    }

    private ByteBuffer healthRequest() {
        String raw = "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        return ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII));
    }

    private RawResponse request(Path path, ByteBuffer req) throws Exception {
        try (SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(path))) {
            while (req.hasRemaining()) ch.write(req);
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(8192);
            int n;
            while ((n = ch.read(buf)) != -1) {
                raw.write(buf.array(), 0, n);
                buf.clear();
            }
            String full = raw.toString(StandardCharsets.UTF_8);
            int sep = full.indexOf("\r\n\r\n");
            assertThat(sep).isGreaterThan(0);
            String statusLine = full.substring(0, full.indexOf("\r\n"));
            // Validate framing while here.
            String headers = full.substring(0, sep);
            assertThat(headers).contains("Content-Length:");
            return new RawResponse(statusLine, full.substring(sep + 4));
        }
    }

    private static MontoyaApi createMockApi() {
        return (MontoyaApi) java.lang.reflect.Proxy.newProxyInstance(
            MontoyaApi.class.getClassLoader(),
            new Class<?>[] { MontoyaApi.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "burpSuite":
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
                    case "logging":
                        return java.lang.reflect.Proxy.newProxyInstance(
                            Logging.class.getClassLoader(),
                            new Class<?>[] { Logging.class },
                            (p2, m2, a2) -> null);
                    case "persistence":
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
                    default:
                        return null; // scope(), project(), extension(), … unused here
                }
            });
    }
}
