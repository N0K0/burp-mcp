package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.organizer.Organizer;
import burp.mcp.util.McpError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Organizer listing (empty store via Proxy) and BCheck definition
 * resolution (pure validation + temp-file round trip). No Burp needed.
 */
class OrganizerBcheckToolsTest {

    @Test
    void organizerList_empty_shouldReportZero() throws Exception {
        MontoyaApi api = (MontoyaApi) java.lang.reflect.Proxy.newProxyInstance(
            MontoyaApi.class.getClassLoader(),
            new Class<?>[] { MontoyaApi.class },
            (proxy, method, args) -> {
                if ("organizer".equals(method.getName())) {
                    return java.lang.reflect.Proxy.newProxyInstance(
                        Organizer.class.getClassLoader(),
                        new Class<?>[] { Organizer.class },
                        (p2, m2, a2) -> {
                            if ("items".equals(m2.getName())) {
                                return List.of();
                            }
                            return null;
                        });
                }
                return null;
            });
        Object out = new OrganizerListTool(api).execute(Map.of("limit", 10));
        var node = new ObjectMapper().valueToTree(out);
        assertThat(node.path("count").asInt()).isEqualTo(0);
        assertThat(node.path("shown").asInt()).isEqualTo(0);
        assertThat(node.path("truncated").asBoolean()).isFalse();
    }

    @Test
    void readDefinition_neitherOption_shouldThrow() {
        assertThatThrownBy(() -> ScannerBcheckImportTool.readDefinition(Map.of()))
                .isInstanceOf(McpError.class)
                .matches(e -> ((McpError) e).getCode() == McpError.INVALID_PARAMS);
    }

    @Test
    void readDefinition_bothOptions_shouldThrow() {
        assertThatThrownBy(() -> ScannerBcheckImportTool.readDefinition(
                Map.of("content", "x", "path", "/tmp/y.bcheck")))
                .isInstanceOf(McpError.class);
    }

    @Test
    void readDefinition_traversalPath_shouldThrow(@TempDir Path tmp) {
        assertThatThrownBy(() -> ScannerBcheckImportTool.readDefinition(
                Map.of("path", "../evil.bcheck")))
                .isInstanceOf(McpError.class);
    }

    @Test
    void readDefinition_file_shouldRoundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("check.bcheck");
        Files.writeString(file, "bcheck-content");
        assertThat(ScannerBcheckImportTool.readDefinition(Map.of("path", file.toString())))
                .isEqualTo("bcheck-content");
        assertThat(ScannerBcheckImportTool.readDefinition(Map.of("content", "inline")))
                .isEqualTo("inline");
    }
}
