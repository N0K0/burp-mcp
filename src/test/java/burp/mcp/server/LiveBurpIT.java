package burp.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration tests against a real Burp Suite running this extension.
 *
 * <p>Excluded from the default {@code mvn test} run (the {@code *IT} name
 * matches no surefire default include) and skipped gracefully without a
 * live Burp. Run explicitly:
 *
 * <pre>
 *   BURP_MCP_TEST_BASE_URL=http://127.0.0.1:4444 mvn test -Dtest=LiveBurpIT
 * </pre>
 *
 * <p>Optional env: {@code BURP_MCP_TEST_AUTH_TOKEN} (Bearer token),
 * {@code BURP_MCP_TEST_ALLOW_SCANS=1} (enables the crawl characterization
 * test; requires Professional). Tests only touch a unique localhost scope
 * subtree (cleaned up) and a loopback target started by the suite itself —
 * no external traffic, no scans unless explicitly allowed.
 */
class LiveBurpIT {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static String baseUrl;
    private static String token;
    private static HttpClient http;
    private static HttpServer target;
    private static int targetPort;
    private static HttpsServer tlsTarget;
    private static int tlsPort;
    private static String edition = "";

    @BeforeAll
    static void setup() throws Exception {
        baseUrl = System.getenv().getOrDefault("BURP_MCP_TEST_BASE_URL", "http://127.0.0.1:4444");
        token = System.getenv().getOrDefault("BURP_MCP_TEST_AUTH_TOKEN", "");
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpRequest health = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = http.send(health, HttpResponse.BodyHandlers.ofString());
            assumeTrue(res.statusCode() == 200,
                    "live Burp health check failed at " + baseUrl + ": HTTP " + res.statusCode());
        } catch (Exception e) {
            System.out.println("[LiveBurpIT] SKIPPED — no live Burp at " + baseUrl
                    + " (" + e.getMessage() + "). Start Burp with the extension loaded"
                    + " or set BURP_MCP_TEST_BASE_URL.");
            assumeTrue(false, "live Burp not reachable at " + baseUrl + ": " + e.getMessage());
        }

        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/hello", ex -> {
            byte[] body = "hello-burp".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        target.createContext("/ua", ex -> {
            String ua = ex.getRequestHeaders().getFirst("User-Agent");
            byte[] body = ("ua=" + (ua == null ? "none" : ua)).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        target.setExecutor(r -> {
            Thread t = new Thread(r, "liveburp-target");
            t.setDaemon(true);
            t.start();
        });
        target.start();
        targetPort = target.getAddress().getPort();

        // Local TLS target (self-signed; Burp does not verify upstream
        // certs unless withUpstreamTLSVerification is set). Proves the
        // bridge performs real HTTPS instead of downgrading to plaintext.
        tlsTarget = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tlsTarget.setHttpsConfigurator(new HttpsConfigurator(
                burp.mcp.util.TlsManager.createSelfSignedContext(
                        "127.0.0.1", "liveburp".toCharArray())));
        tlsTarget.createContext("/hello", ex -> {
            byte[] body = "hello-burp-tls".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        tlsTarget.setExecutor(r -> {
            Thread t = new Thread(r, "liveburp-tls-target");
            t.setDaemon(true);
            t.start();
        });
        tlsTarget.start();
        tlsPort = tlsTarget.getAddress().getPort();

        edition = resultOf(callTool("burp_info", "{}")).path("burpEdition").asText("");
    }

    @AfterAll
    static void teardown() {
        if (target != null) {
            target.stop(0);
        }
        if (tlsTarget != null) {
            tlsTarget.stop(0);
        }
    }

    // ── Bridge basics ────────────────────────────────────────────

    @Test
    void live_burpInfo_shouldReportVersionProjectAndSocket() {
        JsonNode info = resultOf(callTool("burp_info", "{}"));
        assertThat(info.path("burpVersion").asText()).isNotEmpty();
        assertThat(info.path("burpEdition").asText()).isNotEmpty();
        assertThat(info.path("projectName").asText()).isNotEmpty();
        if (info.has("mcpSocketPath")) {
            assertThat(info.path("mcpSocketPath").asText()).isNotEmpty();
        }
    }

    @Test
    void live_projectCreate_shouldReturnCommand() {
        JsonNode out = resultOf(callTool("project_create", "{\"path\":\"/tmp/it-proj.burp\"}"));
        assertThat(out.path("command").asText()).contains("--project-file");
        assertThat(out.path("side_effects").asText()).isNotEmpty();
    }

    // ── Scope round trip (configured scope, not just sitemap) ────

    @Test
    void live_scope_roundTrip_shouldReflectConfiguredIncludes() {
        String marker = "it-" + System.nanoTime();
        String url = "http://127.0.0.1:" + targetPort + "/" + marker;

        JsonNode included = resultOf(callTool("scope_set",
                "{\"url\":" + jsonStr(url) + ",\"action\":\"include\"}"));
        assertThat(included.path("success").asBoolean()).isTrue();
        try {
            assertThat(resultOf(callTool("scope_check",
                    "{\"url\":" + jsonStr(url) + "}")).path("in_scope").asBoolean()).isTrue();

            JsonNode list = resultOf(callTool("scope_list", "{}"));
            JsonNode includes = list.path("configured_scope").path("includes");
            assertThat(includes.isArray()).isTrue();
            boolean found = false;
            for (JsonNode inc : includes) {
                if (inc.asText().contains(marker)) {
                    found = true;
                    break;
                }
            }
            assertThat(found)
                    .as("scope_list configured includes should contain the just-added URL")
                    .isTrue();
        } finally {
            resultOf(callTool("scope_set", "{\"url\":" + jsonStr(url) + ",\"action\":\"exclude\"}"));
        }
        assertThat(resultOf(callTool("scope_check",
                "{\"url\":" + jsonStr(url) + "}")).path("in_scope").asBoolean()).isFalse();
    }

    // ── Single-request sending ───────────────────────────────────

    @Test
    void live_httpSendRequest_bareUrl_shouldReturn200() {
        JsonNode res = resultOf(callTool("http_send_request",
                "{\"url\":" + jsonStr(helloUrl()) + "}"));
        assertThat(res.path("statusCode").asInt()).isEqualTo(200);
        assertThat(res.path("body").asText()).contains("hello-burp");
    }

    @Test
    void live_httpSendRequest_rawOriginForm_shouldReturn200() {
        String raw = "GET /hello HTTP/1.1\r\nHost: 127.0.0.1:" + targetPort
                + "\r\nConnection: close\r\n\r\n";
        JsonNode res = resultOf(callTool("http_send_request",
                "{\"raw_request\":" + jsonStr(raw) + "}"));
        assertThat(res.path("statusCode").asInt()).isEqualTo(200);
        assertThat(res.path("body").asText()).contains("hello-burp");
    }

    @Test
    void live_httpSendRequests_batch_shouldReturn200s() {
        String raw = "GET /hello HTTP/1.1\\r\\nHost: 127.0.0.1:" + targetPort
                + "\\r\\nConnection: close\\r\\n\\r\\n";
        JsonNode envelope = callTool("http_send_requests",
                "{\"requests\":[\"" + raw + "\",\"" + raw + "\"]}");
        // Batch tool returns a top-level JSON array inside the text content.
        String text = envelope.path("result").path("content").get(0).path("text").asText();
        assertThat(text.split("hello-burp", -1).length - 1)
                .as("both batched responses should carry the marker body")
                .isEqualTo(2);
    }

    /**
     * Positive HTTPS proof: an {@code https://} URL must complete a real
     * TLS handshake against the local TLS target. On the downgrade bug
     * (factory-attached port-80 service kept instead of the URL's
     * https/443 service) this goes out plaintext and fails — plaintext
     * against a TLS port cannot produce this 200.
     */
    @Test
    void live_httpSendRequest_https_shouldReturn200() {
        JsonNode res = resultOf(callTool("http_send_request",
                "{\"url\":" + jsonStr("https://127.0.0.1:" + tlsPort + "/hello") + "}"));
        assertThat(res.path("statusCode").asInt()).isEqualTo(200);
        assertThat(res.path("body").asText()).contains("hello-burp-tls");
    }

    /**
     * Absolute-form raw target must also honor the scheme: {@code GET
     * https://…} against the TLS target must return the TLS body.
     */
    @Test
    void live_httpSendRequest_rawAbsoluteHttps_shouldReturn200() {
        String raw = "GET https://127.0.0.1:" + tlsPort + "/hello HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + tlsPort + "\r\nConnection: close\r\n\r\n";
        JsonNode res = resultOf(callTool("http_send_request",
                "{\"raw_request\":" + jsonStr(raw) + "}"));
        assertThat(res.path("statusCode").asInt()).isEqualTo(200);
        assertThat(res.path("body").asText()).contains("hello-burp-tls");
    }

    // ── Scanner platform reality (opt-in, Pro only) ──────────────

    @Test
    void live_scannerCrawl_trackStatusStop_shouldWork() {
        assumeTrue("1".equals(System.getenv("BURP_MCP_TEST_ALLOW_SCANS")),
                "set BURP_MCP_TEST_ALLOW_SCANS=1 to run scan tests");
        assumeTrue("PROFESSIONAL".equals(edition), "scan tests need Burp Professional");

        JsonNode envelope = callTool("scanner_start_crawl",
                "{\"seed_urls\":[" + jsonStr(helloUrl()) + "]}");
        if (envelope.has("error")) {
            // Older Burp: crawl-only tasks not implemented at all.
            assertThat(envelope.path("error").path("message").asText())
                    .containsIgnoringCase("not yet implemented");
            return;
        }
        JsonNode started = resultOf(envelope);
        String crawlId = started.path("crawl_id").asText();
        assertThat(crawlId).isNotEmpty();
        try {
            JsonNode status = resultOf(callTool("scanner_crawl_status",
                    "{\"crawl_id\":" + jsonStr(crawlId) + "}"));
            // Counters are implemented even where the status text is not.
            assertThat(status.path("requestCount").isNumber()).isTrue();
            assertThat(status.path("errorCount").isNumber()).isTrue();
        } finally {
            JsonNode stopped = resultOf(callTool("scanner_crawl_stop",
                    "{\"crawl_id\":" + jsonStr(crawlId) + "}"));
            assertThat(stopped.path("stopped").asBoolean()).isTrue();
        }
        JsonNode afterStop = callTool("scanner_crawl_status",
                "{\"crawl_id\":" + jsonStr(crawlId) + "}");
        assertThat(afterStop.has("error")).isTrue();
    }

    // ── New endpoints: organizer, http_mode, task engine, bcheck ──

    @Test
    void live_organizer_roundTrip_shouldStashAndList() {
        String marker = "it-org-" + System.nanoTime();
        String raw = "GET /" + marker + " HTTP/1.1\r\nHost: 127.0.0.1:" + targetPort
                + "\r\nConnection: close\r\n\r\n";
        JsonNode sent = resultOf(callTool("http_send_to_organizer",
                "{\"raw_request\":" + jsonStr(raw) + "}"));
        assertThat(sent.path("success").asBoolean()).isTrue();

        JsonNode list = resultOf(callTool("organizer_list", "{\"limit\":100}"));
        boolean found = false;
        for (JsonNode item : list.path("items")) {
            if (item.path("url").asText("").contains(marker)) {
                found = true;
                break;
            }
        }
        assertThat(found).as("organizer_list should contain the stashed URL").isTrue();
        // No delete API exists; the single stashed entry stays (harmless).
    }

    @Test
    void live_httpSendRequest_http1_shouldReturn200() {
        JsonNode res = resultOf(callTool("http_send_request",
                "{\"url\":" + jsonStr(helloUrl()) + ",\"http_mode\":\"http1\"}"));
        assertThat(res.path("statusCode").asInt()).isEqualTo(200);
        assertThat(res.path("body").asText()).contains("hello-burp");
    }

    @Test
    void live_taskEngine_status_shouldReportState() {
        JsonNode status = resultOf(callTool("task_engine_status", "{}"));
        assertThat(status.path("state").asText()).isIn("RUNNING", "PAUSED");
        // GET only: flipping the live engine is left to explicit agent action.
    }

    @Test
    void live_scannerBcheckImport_invalid_shouldReportErrors() {
        JsonNode out = resultOf(callTool("scanner_bcheck_import",
                "{\"content\":\"this is not a bcheck definition\"}"));
        assertThat(out.path("status").asText()).isEqualTo("LOADED_WITH_ERRORS");
        assertThat(out.path("importErrors").size()).isGreaterThan(0);
    }

    // ── Unix socket parity ───────────────────────────────────────

    @Test
    void live_unixSocket_health_shouldMatchTcp() throws Exception {
        String sock = resultOf(callTool("burp_info", "{}")).path("mcpSocketPath").asText("");
        assumeTrue(!sock.isEmpty(), "Unix socket not enabled on this instance");

        String req = "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        try (SocketChannel ch = SocketChannel.open(
                UnixDomainSocketAddress.of(Path.of(sock)))) {
            ch.write(ByteBuffer.wrap(req.getBytes(StandardCharsets.US_ASCII)));
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(8192);
            int n;
            while ((n = ch.read(buf)) != -1) {
                raw.write(buf.array(), 0, n);
                buf.clear();
            }
            String full = raw.toString(StandardCharsets.UTF_8);
            assertThat(full).startsWith("HTTP/1.1 200 ");
            assertThat(full).contains("\"status\":\"ok\"");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static String helloUrl() {
        return "http://127.0.0.1:" + targetPort + "/hello";
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    /** POST a JSON-RPC envelope; returns the parsed response envelope (result or error). */
    private static JsonNode callTool(String name, String argsJson) {
        return rpc("tools/call",
                "{\"name\":" + jsonStr(name) + ",\"arguments\":" + argsJson + "}");
    }

    private static JsonNode rpc(String method, String paramsJson) {
        // Self-throttling: the bridge rate-limits (default 100/min) and this
        // suite fires bursts, so honor 429 (-32006) with the requested wait.
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Thread.sleep(300);
                String body = "{\"jsonrpc\":\"2.0\",\"method\":" + jsonStr(method)
                        + ",\"params\":" + paramsJson + ",\"id\":1}";
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                if (!token.isEmpty()) {
                    builder.header("Authorization", "Bearer " + token);
                }
                HttpResponse<String> res = http.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode envelope = mapper.readTree(res.body());
                long waitSec = rateLimitWaitSec(envelope);
                if (waitSec >= 0 && attempt < 5) {
                    Thread.sleep(Math.min(waitSec, 30) * 1000 + 500);
                    continue;
                }
                return envelope;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted", e);
            } catch (Exception e) {
                lastError = new RuntimeException(
                        "RPC transport failed for " + method + ": " + e.getMessage(), e);
            }
        }
        throw lastError != null ? lastError : new RuntimeException("RPC failed for " + method);
    }

    /** Requested retry wait for a -32006 rate-limit envelope, or -1. */
    private static long rateLimitWaitSec(JsonNode envelope) {
        JsonNode error = envelope.path("error");
        if (error.path("code").asInt() != -32006) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+)s").matcher(error.path("message").asText());
        return m.find() ? Long.parseLong(m.group(1)) : 5;
    }

    /** Unwrap tools/call result text content into JSON. */
    private static JsonNode resultOf(JsonNode envelope) {
        try {
            if (envelope.has("error")) {
                throw new RuntimeException("tool error: " + envelope.path("error"));
            }
            String text = envelope.path("result").path("content").get(0).path("text").asText();
            return mapper.readTree(text);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("cannot unwrap tool result from " + envelope, e);
        }
    }
}
