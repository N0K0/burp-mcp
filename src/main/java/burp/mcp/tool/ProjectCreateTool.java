package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.mcp.util.McpJson;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How to create a new Burp project via the Burp executable.
 *
 * <p>The Montoya API exposes the current project read-only ({@code name()}/{@code id()}),
 * so a new project cannot be created from inside Burp. The supported path is launching
 * the Burp executable with {@code --project-file}, which creates the file as a new
 * project when it does not exist yet (verified against {@code BurpSuite --help}).
 *
 * <p>This tool performs no side effects — it returns the exact command to run yourself
 * outside Burp, plus useful flags and caveats.
 */
public class ProjectCreateTool implements Tool {

    private final MontoyaApi api;

    public ProjectCreateTool(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "project_create",
                "How to create a new Burp project. Projects cannot be created via the Montoya API, so pass 'path' for the new .burp file and this returns the exact Burp executable command plus useful flags and caveats. Performs no side effects — run the returned command yourself outside Burp.",
                inputSchema()
        );
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = McpJson.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = McpJson.createObjectNode();
        props.set("path", McpJson.property("string", "Filesystem path for the new .burp project file (created as a new project if it does not exist)"));
        props.set("config_file", McpJson.property("string", "Optional project configuration file to preload (repeat with multiple calls if needed)"));
        schema.set("properties", props);

        ArrayNode required = McpJson.createArrayNode();
        schema.set("required", required);

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String path = (String) args.get("path");
        String configFile = (String) args.get("config_file");

        String target = (path == null || path.isEmpty()) ? "<project-file.burp>" : path;
        StringBuilder command = new StringBuilder("<burp-executable> --project-file=\"")
                .append(target).append("\"");
        if (configFile != null && !configFile.isEmpty()) {
            command.append(" --config-file=\"").append(configFile).append("\"");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("side_effects", "none — run the command yourself outside Burp");
        out.put("command", command.toString());
        out.put("executable_examples", List.of(
                "~/BurpSuite/BurpSuite (Linux)",
                "C:\\Program Files\\BurpSuitePro\\BurpSuitePro.exe (Windows)",
                "/Applications/Burp Suite Professional.app/Contents/MacOS/Burp Suite Professional (macOS)"));

        List<Map<String, String>> flags = new ArrayList<>();
        flags.add(flag("--config-file <file>",
                "Preload project configuration (scope, scan settings). Repeatable."));
        flags.add(flag("--user-config-file <file>",
                "Preload user-level configuration. Repeatable."));
        flags.add(flag("--unpause-spider-and-scanner",
                "Do not pause spider/scanner when reopening an existing project file."));
        flags.add(flag("--use-defaults",
                "Start with default settings instead of saved ones."));
        flags.add(flag("--auto-repair",
                "Automatically repair a corrupted project file."));
        out.put("useful_flags", flags);

        out.put("caveats", List.of(
                "Launching the command starts a full Burp instance (needs a display and a Professional license for scanning).",
                "Do NOT pass --disable-extensions on the new instance if you want this MCP extension loaded there.",
                "Each instance gets its own Unix socket automatically (next to its project file, see burp_info mcpSocketPath) — no port clashes. If TCP is taken by another instance, this one keeps serving on its socket; press Start/Restart from the Status tab once the port is free.",
                "Project files are locked per instance; close or keep instances on separate files.",
                "After switching, point the agent at the new instance (TCP port or Unix socket) and verify with burp_info (projectName/projectId)."));

        // Current project context (read-only in Montoya — best effort)
        try {
            if (api != null && api.project() != null) {
                Map<String, Object> current = new LinkedHashMap<>();
                current.put("name", api.project().name());
                current.put("id", api.project().id());
                out.put("current_project", current);
            }
        } catch (Exception ignored) {
            // Project unavailable (e.g. mocked API in tests) — omit field
        }

        return out;
    }

    private static Map<String, String> flag(String flag, String description) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("flag", flag);
        m.put("description", description);
        return m;
    }
}
