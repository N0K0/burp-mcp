package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.burpsuite.BurpSuite;
import burp.api.montoya.core.BurpSuiteEdition;
import burp.api.montoya.core.Version;
import burp.api.montoya.scanner.Crawl;
import burp.api.montoya.scanner.Scanner;
import burp.mcp.util.CrawlTracker;
import burp.mcp.util.McpError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Crawl start/poll/stop against a stub Crawl (plain interface impl —
 * no Burp runtime needed). The stub mirrors real Burp: counters work,
 * statusMessage() throws "Not yet implemented".
 */
class ScannerCrawlToolsTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Mirrors Burp: working counters, unimplemented status text. */
    static class FakeCrawl implements Crawl {
        int requests = 3;
        int errors = 1;
        boolean deleted;
        @Override public int requestCount() { return requests; }
        @Override public int errorCount() { return errors; }
        @Override public void delete() { deleted = true; }
        @Override public String statusMessage() {
            throw new UnsupportedOperationException("Not yet implemented");
        }
    }

    private FakeCrawl fake;
    private MontoyaApi api;

    @BeforeEach
    void setup() {
        CrawlTracker.clear();
        fake = new FakeCrawl();
        api = (MontoyaApi) java.lang.reflect.Proxy.newProxyInstance(
            MontoyaApi.class.getClassLoader(),
            new Class<?>[] { MontoyaApi.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "burpSuite" -> java.lang.reflect.Proxy.newProxyInstance(
                    BurpSuite.class.getClassLoader(),
                    new Class<?>[] { BurpSuite.class },
                    (p2, m2, a2) -> {
                        if ("version".equals(m2.getName())) {
                            return java.lang.reflect.Proxy.newProxyInstance(
                                Version.class.getClassLoader(),
                                new Class<?>[] { Version.class },
                                (p3, m3, a3) -> {
                                    if ("edition".equals(m3.getName())) {
                                        return BurpSuiteEdition.PROFESSIONAL;
                                    }
                                    return null;
                                });
                        }
                        return null;
                    });
                case "scanner" -> java.lang.reflect.Proxy.newProxyInstance(
                    Scanner.class.getClassLoader(),
                    new Class<?>[] { Scanner.class },
                    (p2, m2, a2) -> {
                        if ("startCrawl".equals(m2.getName())) {
                            return fake;
                        }
                        return null;
                    });
                default -> null;
            });
    }

    @Test
    void status_shouldReportCountersDespiteUnimplementedText() throws Exception {
        String id = CrawlTracker.track(fake);
        ScannerCrawlStatusTool status = new ScannerCrawlStatusTool(api);
        JsonNode out = mapper.readTree(status.execute(Map.of("crawl_id", id)).toString());

        assertThat(out.path("crawl_id").asText()).isEqualTo(id);
        assertThat(out.path("requestCount").asInt()).isEqualTo(3);
        assertThat(out.path("errorCount").asInt()).isEqualTo(1);
        assertThat(out.path("statusMessage").asText()).contains("unavailable");
    }

    @Test
    void stop_shouldDeleteAndForget() throws Exception {
        String id = CrawlTracker.track(fake);
        ScannerCrawlStopTool stop = new ScannerCrawlStopTool(api);
        JsonNode out = mapper.readTree(stop.execute(Map.of("crawl_id", id)).toString());

        assertThat(out.path("stopped").asBoolean()).isTrue();
        assertThat(fake.deleted).isTrue();
        assertThat(CrawlTracker.trackedCount()).isEqualTo(0);
        assertThatThrownBy(() -> new ScannerCrawlStatusTool(api).execute(Map.of("crawl_id", id)))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("Unknown crawl_id");
    }

    @Test
    void status_unknownId_shouldThrowInvalidParams() {
        assertThatThrownBy(
                () -> new ScannerCrawlStatusTool(api).execute(Map.of("crawl_id", "nope")))
                .isInstanceOf(McpError.class)
                .matches(e -> ((McpError) e).getCode() == McpError.INVALID_PARAMS);
    }

    @Test
    void start_emptySeeds_shouldThrowInvalidParams() {
        // CrawlConfiguration factory is Burp-bound; empty input must fail first.
        assertThatThrownBy(() -> new ScannerStartCrawlTool(api)
                .execute(Map.of("seed_urls", java.util.List.of())))
                .isInstanceOf(McpError.class);
    }
}
