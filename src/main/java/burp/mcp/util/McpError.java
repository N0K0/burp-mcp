package burp.mcp.util;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Custom exception for MCP errors with JSON-RPC error codes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpError extends RuntimeException {

    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int PRO_ONLY_FEATURE = -32001;
    public static final int INVALID_HTTP_REQUEST = -32002;
    public static final int REQUEST_FAILED = -32003;
    public static final int NOT_FOUND = -32004;
    public static final int PERMISSION_DENIED = -32005;
    public static final int RATE_LIMITED = -32006;
    public static final int SERVER_BUSY = -32007;
    public static final int APPROVAL_PENDING = -32008;
    public static final int APPROVAL_TIMEOUT = -32009;
    public static final int OUT_OF_SCOPE = -32010;

    private final int code;
    private final Object data;

    public McpError(int code, String message) {
        super(message);
        this.code = code;
        this.data = null;
    }

    public McpError(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public Object getData() {
        return data;
    }
}
