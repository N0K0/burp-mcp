package burp.mcp.util;

/**
 * Input validation for MCP tool parameters.
 * Validates URLs, file paths, cookie values, and custom data
 * against injection and format attacks.
 */
public final class InputValidator {

    private InputValidator() {}

    /**
     * Validates a URL: must start with http:// or https://,
     * max 2048 chars, no null bytes, no CR/LF injection.
     * Returns null if valid, or an error message.
     */
    public static String validateUrl(String url, String paramName) {
        if (url == null || url.isEmpty()) {
            return paramName + " must not be empty";
        }
        if (url.length() > 2048) {
            return paramName + " exceeds maximum length of 2048 characters";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return paramName + " must start with http:// or https://";
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '\0') return paramName + " contains null byte";
            if (c == '\n' || c == '\r') return paramName + " contains CR/LF injection";
        }
        return null;
    }

    /**
     * Validates a file path: no .. traversal, no null bytes.
     * Returns null if valid, or an error message.
     */
    public static String validateFilePath(String path, String paramName) {
        if (path == null || path.isEmpty()) return null; // optional
        if (path.contains("..")) {
            return paramName + " must not contain '..' path traversal";
        }
        if (path.contains("\0") || path.contains("\n") || path.contains("\r")) {
            return paramName + " contains invalid characters";
        }
        return null;
    }

    /**
     * Validates a cookie name or value: no CR/LF, max 4096 chars.
     */
    public static String validateCookieValue(String value, String paramName) {
        if (value == null) return paramName + " must not be null";
        if (value.length() > 4096) {
            return paramName + " exceeds maximum length of 4096 characters";
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r') return paramName + " contains CR/LF injection";
        }
        return null;
    }

    /**
     * Validates Collaborator custom data: max 16 alphanumeric chars.
     */
    public static String validateCustomData(String data, String paramName) {
        if (data == null || data.isEmpty()) return null; // optional
        if (data.length() > 16) {
            return paramName + " must be max 16 characters";
        }
        if (!data.matches("[a-zA-Z0-9]+")) {
            return paramName + " must be alphanumeric only";
        }
        return null;
    }
}
