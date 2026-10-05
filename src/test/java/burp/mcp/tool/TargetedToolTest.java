package burp.mcp.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TargetedTool implementations derive outbound targets from raw arguments
 * without Montoya factories, so they are unit-testable.
 */
class TargetedToolTest {

    private static final String RAW_GET =
            "GET /path?q=1 HTTP/1.1\r\nHost: target.test\r\n\r\n";

    // ── Raw request parsing ─────────────────────────────────────────

    @Test
    void targetUrlFromRaw_shouldUseHostHeader() {
        assertThat(HttpSendRequestTool.targetUrlFromRaw(RAW_GET, null))
                .isEqualTo("http://target.test/path?q=1");
    }

    @Test
    void targetUrlFromRaw_443Host_shouldImplyHttpsWithoutPort() {
        assertThat(HttpSendRequestTool.targetUrlFromRaw(
                "GET / HTTP/1.1\r\nHost: secure.test:443\r\n\r\n", null))
                .isEqualTo("https://secure.test/");
    }

    @Test
    void targetUrlFromRaw_customPort_shouldKeepAuthority() {
        assertThat(HttpSendRequestTool.targetUrlFromRaw(
                "POST /api HTTP/1.1\r\nHost: app.test:8443\r\nContent-Length: 0\r\n\r\n", null))
                .isEqualTo("http://app.test:8443/api");
    }

    @Test
    void targetUrlFromRaw_absoluteForm_shouldWinOverHostHeader() {
        assertThat(HttpSendRequestTool.targetUrlFromRaw(
                "GET https://absolute.test/a HTTP/1.1\r\nHost: ignored.test\r\n\r\n", null))
                .isEqualTo("https://absolute.test/a");
    }

    @Test
    void targetUrlFromRaw_urlHint_shouldTakePrecedence() {
        assertThat(HttpSendRequestTool.targetUrlFromRaw(RAW_GET, "https://hint.test/x"))
                .isEqualTo("https://hint.test/x");
    }

    @Test
    void targetUrlFromRaw_missingHost_shouldReturnNull() {
        assertThat(HttpSendRequestTool.targetUrlFromRaw("GET / HTTP/1.1\r\n\r\n", null)).isNull();
        assertThat(HttpSendRequestTool.targetUrlFromRaw("garbage", null)).isNull();
        assertThat(HttpSendRequestTool.targetUrlFromRaw(null, null)).isNull();
    }

    // ── Tool-level extraction ───────────────────────────────────────

    @Test
    void httpSendRequest_shouldReturnUrlArgument() {
        TargetedTool tool = new HttpSendRequestTool(null);
        assertThat(tool.targetUrls(Map.of("url", "https://one.test/a")))
                .containsExactly("https://one.test/a");
    }

    @Test
    void httpSendRequest_shouldDeriveFromRawRequest() {
        TargetedTool tool = new HttpSendRequestTool(null);
        assertThat(tool.targetUrls(Map.of("raw_request", RAW_GET)))
                .containsExactly("http://target.test/path?q=1");
    }

    @Test
    void httpSendRequest_shouldReturnEmptyWhenNothingDerivable() {
        TargetedTool tool = new HttpSendRequestTool(null);
        assertThat(tool.targetUrls(Map.of())).isEmpty();
        assertThat(tool.targetUrls(Map.of("raw_request", "garbage"))).isEmpty();
    }

    @Test
    void httpSendRequests_shouldCollectEveryTarget() {
        TargetedTool tool = new HttpSendRequestsTool(null);
        List<String> targets = tool.targetUrls(Map.of("requests", List.of(
                RAW_GET,
                "GET /b HTTP/1.1\r\nHost: second.test:8080\r\n\r\n",
                "not a request")));
        assertThat(targets).containsExactly(
                "http://target.test/path?q=1",
                "http://second.test:8080/b");
    }

    @Test
    void loggerAdd_shouldDeriveFromRawRequest() {
        TargetedTool tool = new LoggerAddTool(null);
        assertThat(tool.targetUrls(Map.of("raw_request", RAW_GET)))
                .containsExactly("http://target.test/path?q=1");
    }

    @Test
    void scannerAudit_shouldCollectUrlAndUrls() {
        TargetedTool tool = new ScannerStartAuditTool(null);
        assertThat(tool.targetUrls(Map.of(
                "url", "https://seed.test/",
                "urls", List.of("https://a.test/", "https://b.test/"))))
                .containsExactly("https://seed.test/", "https://a.test/", "https://b.test/");
    }

    @Test
    void scannerCrawl_shouldCollectSeedUrls() {
        TargetedTool tool = new ScannerStartCrawlTool(null);
        assertThat(tool.targetUrls(Map.of("seed_urls", List.of("https://crawl.test/"))))
                .containsExactly("https://crawl.test/");
    }
}
