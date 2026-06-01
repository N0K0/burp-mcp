package burp.mcp.util;

import java.io.InputStream;
import java.util.Properties;

/**
 * Reads build version info from version.properties (filtered by Maven at build time).
 * Falls back to "dev" values if the properties file is unavailable.
 */
public class VersionInfo {

    private static final String VERSION;
    private static final String BUILD_TIMESTAMP;
    private static final String MONTOYA_API_VERSION;
    private static final String FULL_VERSION;

    static {
        String version = "dev";
        String buildTs = "";
        String montoyaVersion = "unknown";

        try (InputStream is = VersionInfo.class.getClassLoader()
                .getResourceAsStream("version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                version = props.getProperty("version", "dev");
                buildTs = props.getProperty("buildTimestamp", "");
                montoyaVersion = props.getProperty("montoyaApiVersion", "unknown");
            }
        } catch (Exception ignored) {
            // Fall back to defaults
        }

        VERSION = version;
        BUILD_TIMESTAMP = buildTs;
        MONTOYA_API_VERSION = montoyaVersion;
        FULL_VERSION = buildTs.isEmpty()
                ? VERSION + "-dev"
                : VERSION + "+" + buildTs;
    }

    public static String getVersion() {
        return VERSION;
    }

    public static String getBuildTimestamp() {
        return BUILD_TIMESTAMP;
    }

    public static String getFullVersion() {
        return FULL_VERSION;
    }

    public static String getMontoyaApiVersion() {
        return MONTOYA_API_VERSION;
    }
}
