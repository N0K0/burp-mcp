package burp.mcp.server;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.burpsuite.BurpSuite;
import burp.api.montoya.core.BurpSuiteEdition;
import burp.api.montoya.core.Version;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;
import burp.mcp.tool.McpToolRegistry;
import burp.mcp.util.McpConfig;
import burp.mcp.util.MetricsCollector;
import burp.mcp.util.PermissionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle regression tests: a busy TCP port must not stop the Unix socket
 * from serving, and a restart must rebind both listeners.
 */
class McpServerManagerTest {

    @TempDir
    static Path tmp;

    private static MontoyaApi mockApi;
    private static McpToolRegistry registry;
    private static MetricsCollector metrics;

    private McpServerManager manager;
    private Integer previousPort;

    @BeforeAll
    static void setup() {
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
        metrics = new MetricsCollector();
    }

    @AfterEach
    void cleanup() {
        if (manager != null) {
            manager.stop();
        }
        if (previousPort != null) {
            McpConfig.getInstance().setPort(previousPort);
        }
        McpConfig.getInstance().setSocketPath("");
        McpConfig.getInstance().setSocketEnabled(true);
    }

    @Test
    void busyTcpPort_shouldKeepSocketServing_andRestartShouldRebind() throws Exception {
        McpConfig config = McpConfig.getInstance();
        previousPort = config.getPort();
        Path socketPath = tmp.resolve("manager-test.sock");

        try (ServerSocket blocker = new ServerSocket()) {
            blocker.bind(new InetSocketAddress("127.0.0.1", 0));
            int busyPort = blocker.getLocalPort();
            config.setPort(busyPort);
            config.setSocketPath(socketPath.toString());
            config.setSocketEnabled(true);

            manager = new McpServerManager(mockApi, registry, new PermissionManager(), metrics);
            manager.start();

            // The exact defect from the field: TCP cannot bind, but the
            // Unix socket must still serve this project.
            assertThat(manager.isTcpRunning()).isFalse();
            assertThat(manager.getTcpError()).contains("already in use");
            assertThat(manager.isSocketRunning()).isTrue();
            assertThat(manager.getSocketPath()).isEqualTo(socketPath);
            assertThat(Files.exists(socketPath)).isTrue();
            assertThat(healthStatus(socketPath)).startsWith("HTTP/1.1 200 ");

            // Free the port, then restart: both listeners come up.
            blocker.close();
        }

        manager.restart();
        assertThat(manager.isTcpRunning()).isTrue();
        assertThat(manager.getTcpError()).isNull();
        assertThat(manager.isSocketRunning()).isTrue();
        assertThat(manager.getSocketPath()).isEqualTo(socketPath);
        assertThat(healthStatus(socketPath)).startsWith("HTTP/1.1 200 ");

        manager.stop();
        assertThat(manager.isRunning()).isFalse();
        assertThat(Files.notExists(socketPath)).isTrue();
    }

    @Test
    void restart_shouldNotLeakOldSocketFile() throws Exception {
        McpConfig config = McpConfig.getInstance();
        previousPort = config.getPort();
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        config.setPort(freePort);
        Path socketPath = tmp.resolve("restart-clean.sock");
        config.setSocketPath(socketPath.toString());
        config.setSocketEnabled(true);

        manager = new McpServerManager(mockApi, registry, new PermissionManager(), metrics);
        manager.start();
        manager.restart();
        manager.restart();

        assertThat(manager.isSocketRunning()).isTrue();
        assertThat(manager.getSocketPath()).isEqualTo(socketPath);
        assertThat(healthStatus(socketPath)).startsWith("HTTP/1.1 200 ");
        assertThat(manager.isTcpRunning()).isTrue();
    }

    @Test
    void startCalledTwice_shouldNotLeakListeners() throws Exception {
        McpConfig config = McpConfig.getInstance();
        previousPort = config.getPort();
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        config.setPort(freePort);
        Path socketPath = tmp.resolve("double-start.sock");
        config.setSocketPath(socketPath.toString());
        config.setSocketEnabled(true);

        manager = new McpServerManager(mockApi, registry, new PermissionManager(), metrics);
        manager.start();
        manager.start();

        assertThat(manager.isTcpRunning()).isTrue();
        assertThat(manager.isSocketRunning()).isTrue();
        assertThat(healthStatus(socketPath)).startsWith("HTTP/1.1 200 ");
    }

    // ── Helpers ────────────────────────────────────────────────

    private static String healthStatus(Path socketPath) throws Exception {
        String raw = "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        try (SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))) {
            ch.write(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(4096);
            int n;
            while ((n = ch.read(buf)) != -1) {
                out.write(buf.array(), 0, n);
                buf.clear();
            }
            String full = out.toString(StandardCharsets.UTF_8);
            int eol = full.indexOf("\r\n");
            return eol > 0 ? full.substring(0, eol) : full;
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
                        return null; // project() null: socket path comes from config
                }
            });
    }
}
