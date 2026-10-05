package burp.mcp.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;
import java.util.prefs.Preferences;

/**
 * Locates the {@code .burp} file of the current project.
 *
 * <p>The Montoya API exposes only the project name and id, never the file
 * path. Burp does record its recent project files in its user-level Java
 * preferences ({@code burp.suite.recentProjectFilesN} paired with
 * {@code burp.suite.recentProjectNamesN}, index 0 = most recent), so the
 * current project's file can be matched by name.
 *
 * <p>Everything here is best-effort: a missing key, unreadable preferences
 * backend, relative path, or temporary project yields an empty result and
 * the caller falls back to a temporary socket path.
 */
public final class ProjectFiles {

    static final String FILES_KEY = "burp.suite.recentProjectFiles";
    static final String NAMES_KEY = "burp.suite.recentProjectNames";
    static final String TEMPORARY_PROJECT = "Temporary Project";
    private static final int MAX_SLOTS = 20;

    private ProjectFiles() {
    }

    /** Read-only lookup backed by Burp's user-level Java preferences. */
    public static Function<String, String> burpPrefLookup() {
        return key -> {
            try {
                Preferences node = Preferences.userRoot().node("burp");
                try {
                    // Burp writes these prefs from the same JVM; sync picks up
                    // a project opened after this extension was loaded.
                    node.sync();
                } catch (Exception ignored) {
                    // Read the cached view if syncing fails.
                }
                return node.get(key, null);
            } catch (Exception e) {
                return null;
            }
        };
    }

    /**
     * Find the project file for {@code projectName}: the most recent entry
     * whose file basename or recorded project name matches, ignoring a
     * trailing {@code .burp} and case.
     */
    public static Optional<Path> locate(String projectName, Function<String, String> lookup) {
        if (projectName == null || projectName.isBlank() || lookup == null) {
            return Optional.empty();
        }
        String wanted = stripBurpSuffix(projectName.trim());
        if (wanted.isEmpty() || TEMPORARY_PROJECT.equalsIgnoreCase(wanted)) {
            return Optional.empty();
        }

        for (int i = 0; i < MAX_SLOTS; i++) {
            String file = lookup.apply(FILES_KEY + i);
            if (file == null || file.isBlank()) {
                continue;
            }
            String name = lookup.apply(NAMES_KEY + i);
            boolean fileMatches = stripBurpSuffix(baseName(file.trim()))
                    .equalsIgnoreCase(wanted);
            boolean nameMatches = name != null && !name.isBlank()
                    && stripBurpSuffix(name.trim()).equalsIgnoreCase(wanted);
            if (fileMatches || nameMatches) {
                return absolutePath(file);
            }
        }
        return Optional.empty();
    }

    /** Socket path next to a project file: {@code <dir>/<basename>.sock}. */
    public static Path socketPathFor(Path projectFile) {
        String base = stripBurpSuffix(projectFile.getFileName().toString());
        if (base.isBlank()) {
            base = "project";
        }
        Path dir = projectFile.toAbsolutePath().getParent();
        return (dir != null ? dir : projectFile.toAbsolutePath()).resolve(base + ".sock");
    }

    /**
     * Socket paths must stay under the OS {@code sockaddr_un} ceiling
     * (~108 bytes on Linux); leave headroom for the trailing NUL.
     */
    public static boolean isUsableSocketPath(Path socketPath) {
        return socketPath.toString().length() < 100;
    }

    private static Optional<Path> absolutePath(String file) {
        try {
            Path path = Paths.get(file);
            return path.isAbsolute() ? Optional.of(path) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    static String stripBurpSuffix(String name) {
        if (name.length() > 5 && name.regionMatches(true, name.length() - 5, ".burp", 0, 5)) {
            return name.substring(0, name.length() - 5);
        }
        return name;
    }
}
