package burp.mcp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpErrorTest {

    @Test
    void shouldHaveCorrectCodeAndMessage() {
        McpError err = new McpError(McpError.INVALID_PARAMS, "Missing 'url' parameter");
        assertThat(err.getCode()).isEqualTo(McpError.INVALID_PARAMS);
        assertThat(err.getMessage()).isEqualTo("Missing 'url' parameter");
    }

    @Test
    void shouldExtendRuntimeException() {
        McpError err = new McpError(McpError.INTERNAL_ERROR, "Oops");
        assertThat(err).isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldStoreExtraData() {
        McpError err = new McpError(McpError.PRO_ONLY_FEATURE, "Scanner requires Pro", "CE edition");
        assertThat(err.getData()).isEqualTo("CE edition");
    }

    @Test
    void errorCodes_shouldBeStandardJsonRpc() {
        assertThat(McpError.INVALID_REQUEST).isEqualTo(-32600);
        assertThat(McpError.METHOD_NOT_FOUND).isEqualTo(-32601);
        assertThat(McpError.INVALID_PARAMS).isEqualTo(-32602);
        assertThat(McpError.INTERNAL_ERROR).isEqualTo(-32603);
    }

    @Test
    void customErrorCodes_shouldBeInCustomRange() {
        assertThat(McpError.PRO_ONLY_FEATURE).isEqualTo(-32001);
        assertThat(McpError.INVALID_HTTP_REQUEST).isEqualTo(-32002);
        assertThat(McpError.REQUEST_FAILED).isEqualTo(-32003);
        assertThat(McpError.NOT_FOUND).isEqualTo(-32004);
        assertThat(McpError.PERMISSION_DENIED).isEqualTo(-32005);
    }
}
