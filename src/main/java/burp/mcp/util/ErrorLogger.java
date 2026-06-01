package burp.mcp.util;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * File-based error logger with log rotation (max 5MB, keep 3 files),
 * structured JSON-lines output, and JVM info header on new sessions.
 * All writes are guarded — a disk-full IOException will never crash the server.
 */
public class ErrorLogger {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_ROTATED_FILES = 3;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static volatile Path logPath;
    private static volatile boolean jvmInfoWritten = false;

    static {
        logPath = resolveLogPath(null);
    }

    /**
     * Set a custom log path. Call this before any logging happens.
     */
    public static synchronized void setLogPath(String customPath) {
        Path newPath;
        if (customPath != null && !customPath.isEmpty()) {
            newPath = Paths.get(customPath);
        } else {
            newPath = resolveLogPath(null);
        }
        if (!newPath.equals(logPath)) {
            logPath = newPath;
            jvmInfoWritten = false;
        }
    }

    public static Path getLogPath() {
        return logPath;
    }

    public static void log(LogEntry entry) {
        try {
            ensurePath();
            if (!jvmInfoWritten) {
                writeJvmInfo();
                jvmInfoWritten = true;
            }
            rotateIfNeeded();
            try (FileWriter fw = new FileWriter(logPath.toFile(), true)) {
                fw.write(entry.toJsonLine());
                fw.write("\n");
                fw.flush();
            }
        } catch (Exception ignored) {
            // Disk full, permission denied, etc. — must not crash server
        }
    }

    /**
     * Legacy convenience — logs a throwable with context as a structured entry.
     */
    public static void log(String context, Throwable t) {
        log(LogEntry.create("ERROR", "ErrorLogger", null, 0,
                context, t));
    }

    private static void writeJvmInfo() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ts\":\"").append(TS.format(Instant.now())).append("\"");
            sb.append(",\"level\":\"INFO\"");
            sb.append(",\"src\":\"ErrorLogger\"");
            sb.append(",\"msg\":\"JVM/SESSION START\"");
            sb.append(",\"java_version\":\"").append(esc(System.getProperty("java.version"))).append("\"");
            sb.append(",\"os_name\":\"").append(esc(System.getProperty("os.name"))).append("\"");
            sb.append(",\"os_arch\":\"").append(esc(System.getProperty("os.arch"))).append("\"");
            sb.append(",\"processors\":").append(Runtime.getRuntime().availableProcessors());
            sb.append(",\"max_memory_mb\":").append(Runtime.getRuntime().maxMemory() / (1024 * 1024));
            sb.append("}");
            try (FileWriter fw = new FileWriter(logPath.toFile(), true)) {
                fw.write(sb.toString());
                fw.write("\n");
                fw.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static void rotateIfNeeded() {
        try {
            if (!Files.exists(logPath)) return;
            long size = Files.size(logPath);
            if (size < MAX_FILE_SIZE) return;
        } catch (IOException e) {
            return;
        }

        try {
            // Rotate: error.2.log → error.3.log, error.1.log → error.2.log, error.log → error.1.log
            for (int i = MAX_ROTATED_FILES; i >= 1; i--) {
                Path oldFile = rotatedPath(i);
                if (Files.exists(oldFile)) {
                    if (i == MAX_ROTATED_FILES) {
                        Files.deleteIfExists(oldFile);
                    } else {
                        Files.move(oldFile, rotatedPath(i + 1), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            // Move current to .1.log
            Files.move(logPath, rotatedPath(1), StandardCopyOption.REPLACE_EXISTING);
            // New empty log will be created on next write
        } catch (Exception ignored) {
        }
    }

    private static Path rotatedPath(int index) {
        String name = logPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return logPath.resolveSibling(base + "." + index + ext);
    }

    private static void ensurePath() {
        try {
            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception ignored) {
        }
    }

    private static Path resolveLogPath(String custom) {
        if (custom != null && !custom.isEmpty()) {
            return Paths.get(custom);
        }
        Path home = Paths.get(System.getProperty("user.home"));
        return home.resolve("burp-mcp-error.log");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
