package burp.mcp.util;

import burp.api.montoya.http.message.*;
import burp.api.montoya.http.message.Cookie;
import burp.api.montoya.http.message.params.HttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.scanner.audit.issues.AuditIssue;

import java.util.*;

import static burp.mcp.util.McpConfig.getInstance;

/**
 * Serializes Montoya HTTP objects to JSON-compatible Maps.
 * Handles body truncation and binary data.
 */
public class HttpMessageSerializer {

    /** Max request/response pairs embedded in one audit issue. */
    private static final int MAX_AUDIT_PAIRS = 100;

    private static int maxResponseBytes() {
        McpConfig config = getInstance();
        return config != null ? config.getMaxResponseBodyBytes() : 100_000;
    }

    /**
     * Truncate text so its UTF-8 encoding fits in maxBytes.
     * Cuts on char boundaries, so multi-byte sequences stay intact.
     */
    static String truncateText(String text, int maxBytes) {
        if (text == null) return "";
        byte[] all = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (all.length <= maxBytes) return text;
        // Estimate the char cut point, then back off until it fits.
        int cut = Math.max(1, (int) ((long) maxBytes * text.length() / all.length));
        while (cut > 0
                && text.substring(0, cut).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            cut--;
        }
        return text.substring(0, cut);
    }

    /**
     * Serialize an HttpRequest to a Map.
     */
    public static Map<String, Object> serializeRequest(HttpRequest request) {
        if (request == null) return Collections.emptyMap();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", request.method());
        result.put("url", request.url());
        result.put("path", request.path().isEmpty() ? "/" : request.path());
        result.put("httpVersion", request.httpVersion());

        // Headers
        List<Map<String, String>> headers = new ArrayList<>();
        for (HttpHeader header : request.headers()) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("name", header.name());
            h.put("value", header.value());
            headers.add(h);
        }
        result.put("headers", headers);

        // Parameters
        List<Map<String, Object>> params = new ArrayList<>();
        for (HttpParameter param : request.parameters()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", param.type().name());
            p.put("name", param.name());
            p.put("value", param.value());
            params.add(p);
        }
        result.put("parameters", params);

        // Body (truncated to max_response_body_bytes, like responses)
        byte[] bodyBytes = request.body().getBytes();
        int maxBytes = maxResponseBytes();
        if (bodyBytes.length > maxBytes) {
            result.put("body", truncateText(ByteArrayConverter.bytesToString(bodyBytes), maxBytes));
            result.put("bodySize", bodyBytes.length);
            result.put("truncated", true);
        } else if (bodyBytes.length > 0) {
            String body = ByteArrayConverter.bytesToString(bodyBytes);
            result.put("body", body);
            result.put("bodySize", bodyBytes.length);
            result.put("truncated", false);
        } else {
            result.put("body", "");
            result.put("bodySize", 0);
            result.put("truncated", false);
        }

        // Content type
        result.put("contentType", request.contentType().name());
        result.put("inScope", request.isInScope());

        return result;
    }

    /**
     * Serialize an HttpResponse to a Map.
     * Truncates body if it exceeds max_response_body_bytes.
     */
    public static Map<String, Object> serializeResponse(HttpResponse response) {
        if (response == null) return Collections.emptyMap();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statusCode", response.statusCode());
        result.put("status_text", response.reasonPhrase());

        // Headers
        List<Map<String, String>> headers = new ArrayList<>();
        for (HttpHeader header : response.headers()) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("name", header.name());
            h.put("value", header.value());
            headers.add(h);
        }
        result.put("headers", headers);

        // Body with truncation (char-boundary safe)
        byte[] bodyBytes = response.body().getBytes();
        int maxBytes = maxResponseBytes();
        if (bodyBytes.length > maxBytes) {
            String body = truncateText(ByteArrayConverter.bytesToString(bodyBytes), maxBytes);
            result.put("body", body);
            result.put("bodySize", bodyBytes.length);
            result.put("truncated", true);
        } else {
            String body = ByteArrayConverter.bytesToString(bodyBytes);
            result.put("body", body);
            result.put("bodySize", bodyBytes.length);
            result.put("truncated", false);
        }

        // MIME type
        result.put("mimeType", response.mimeType().name());

        // Cookies
        List<Map<String, Object>> cookies = new ArrayList<>();
        for (Cookie cookie : response.cookies()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", cookie.name());
            c.put("value", cookie.value());
            c.put("domain", cookie.domain());
            c.put("path", cookie.path());
            c.put("expiration", cookie.expiration().map(Object::toString).orElse(null));
            cookies.add(c);
        }
        result.put("cookies", cookies);

        return result;
    }

    /**
     * Serialize an HttpRequestResponse to a Map with request and response.
     */
    public static Map<String, Object> serializeHttpRequestResponse(HttpRequestResponse pair) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request", serializeRequest(pair.request()));
        result.put("response", serializeResponse(pair.response()));
        return result;
    }

    /**
     * Serialize a summary (no bodies) for sitemap/proxy history.
     */
    public static Map<String, Object> summarizeEntry(HttpRequestResponse pair) {
        Map<String, Object> result = new LinkedHashMap<>();
        HttpRequest req = pair.request();
        result.put("url", req.url());
        result.put("method", req.method());
        result.put("inScope", req.isInScope());

        if (pair.response() != null) {
            HttpResponse resp = pair.response();
            result.put("statusCode", resp.statusCode());
            result.put("bodySize", resp.body().length());
            result.put("mimeType", resp.mimeType().name());
        } else {
            result.put("statusCode", 0);
            result.put("bodySize", 0);
            result.put("mimeType", "UNKNOWN");
        }
        return result;
    }

    /**
     * Serialize an AuditIssue. Caps embedded request/response pairs so one
     * issue with a huge history can't return megabytes.
     */
    public static Map<String, Object> serializeAuditIssue(AuditIssue issue) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", issue.name());
        result.put("detail", issue.detail());
        result.put("remediation", issue.remediation());
        result.put("baseUrl", issue.baseUrl());
        result.put("severity", issue.severity().name());
        result.put("confidence", issue.confidence().name());

        // Request/response pairs (capped)
        List<HttpRequestResponse> all = issue.requestResponses();
        List<Map<String, Object>> reqResps = new ArrayList<>();
        int shown = Math.min(all.size(), MAX_AUDIT_PAIRS);
        for (int i = 0; i < shown; i++) {
            reqResps.add(serializeHttpRequestResponse(all.get(i)));
        }
        result.put("requestResponses", reqResps);
        result.put("requestResponseCount", all.size());
        result.put("truncated", all.size() > shown);

        return result;
    }

    /**
     * Serialize an issue summary (no bodies).
     */
    public static Map<String, Object> summarizeIssue(AuditIssue issue) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", issue.name());
        result.put("baseUrl", issue.baseUrl());
        result.put("severity", issue.severity().name());
        result.put("confidence", issue.confidence().name());
        return result;
    }
}
