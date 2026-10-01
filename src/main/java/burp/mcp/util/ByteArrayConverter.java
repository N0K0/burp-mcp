package burp.mcp.util;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Utility for hex encoding/decoding and byte array conversion.
 * Montoya ByteUtils has no hex methods, so we use java.util.HexFormat (Java 17+).
 */
public class ByteArrayConverter {

    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]+$");

    /**
     * Convert a byte array to a lowercase hex string.
     */
    public static String toHex(byte[] data) {
        if (data == null) return "";
        return HEX.formatHex(data);
    }

    /**
     * Convert a hex string to a byte array.
     * @throws IllegalArgumentException if the string is not valid hex
     */
    public static byte[] fromHex(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string: odd length");
        }
        if (!HEX_PATTERN.matcher(hex).matches()) {
            String preview = hex.length() > 64 ? hex.substring(0, 64) + "..." : hex;
            throw new IllegalArgumentException("Invalid hex string: " + preview);
        }
        return HEX.parseHex(hex);
    }

    /**
     * Convert a string to bytes using UTF-8 encoding.
     */
    public static byte[] stringToBytes(String s) {
        if (s == null) return new byte[0];
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Convert bytes to a string using UTF-8 decoding.
     * Invalid sequences become U+FFFD replacements (String decoding
     * never throws); use {@link #bytesToHex(byte[])} for binary data.
     */
    public static String bytesToString(byte[] data) {
        if (data == null) return "";
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Encode bytes as a hex string.
     */
    public static String bytesToHex(byte[] data) {
        return toHex(data);
    }
}
