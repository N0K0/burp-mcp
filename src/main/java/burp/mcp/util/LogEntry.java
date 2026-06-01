package burp.mcp.util;

import java.time.Instant;

/**
 * Structured log entry capturing all context for a single log event.
 * Timerange: timestamp is ISO 8601 with milliseconds.
 * Correlation IDs tie tool calls to their parent server requests.
 */
public record LogEntry(
    String timestamp,
    String level,
    String source,
    String correlationId,
    long elapsedMs,
    String message,
    String throwableSummary
) {
    public static LogEntry create(String level, String source, String correlationId,
                                   long elapsedMs, String message, Throwable t) {
        String ts = java.time.format.DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String summary = t != null ? t.getClass().getName() + ": " + truncate(t.getMessage(), 200) : null;
        return new LogEntry(ts, level, source, correlationId, elapsedMs, message, summary);
    }

    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"ts\":\"").append(escape(timestamp)).append("\"");
        sb.append(",\"level\":\"").append(level).append("\"");
        sb.append(",\"src\":\"").append(escape(source)).append("\"");
        if (correlationId != null) sb.append(",\"corr\":\"").append(correlationId).append("\"");
        sb.append(",\"elapsed\":").append(elapsedMs);
        sb.append(",\"msg\":\"").append(escape(message)).append("\"");
        if (throwableSummary != null) sb.append(",\"exc\":\"").append(escape(throwableSummary)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
