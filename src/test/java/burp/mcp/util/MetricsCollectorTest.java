package burp.mcp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCollectorTest {

    @Test
    void disabled_shouldNotRecord() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.setEnabled(false);
        assertThat(metrics.isEnabled()).isFalse();
        metrics.recordRequest();
        metrics.recordError();
        metrics.recordRateLimited();
        metrics.recordServerBusy();
        metrics.recordLatencyMs(50);
        metrics.recordToolCall("burp_info");
        assertThat(metrics.getTotalRequests()).isZero();
        assertThat(metrics.getErrorCount()).isZero();
        assertThat(metrics.getRateLimitedCount()).isZero();
        assertThat(metrics.getServerBusyCount()).isZero();
        assertThat(metrics.getToolCounts()).isEmpty();
    }

    @Test
    void enabled_shouldRecordRequestsAndTools() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordRequest();
        metrics.recordToolCall("burp_info");
        metrics.recordServerBusy();
        assertThat(metrics.getTotalRequests()).isEqualTo(1);
        assertThat(metrics.getToolCounts()).containsEntry("burp_info", 1L);
        assertThat(metrics.getServerBusyCount()).isEqualTo(1);
    }
}
