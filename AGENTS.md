# AGENTS.md

Burp Suite MCP Server, a Java extension exposing the Montoya API as MCP tools.
AI agents (you) call these tools to interact with Burp programmatically.

## Build

```bash
cd /home/nikolas/git/burp-mcp
mvn clean compile          # compile only
mvn test                   # run 67 JUnit 5 + AssertJ tests
mvn clean package -DskipTests  # build fat JAR → C:\Users\nikolas\Downloads\
```

Java 17+, Maven 3.9+. WSL builds, Windows host runs Burp.

## Project structure

```
src/main/java/burp/mcp/
├── BurpMcpExtension.java          # BurpExtension entry point
├── server/
│   └── McPServer.java             # NanoHTTPD JSON-RPC handler (health, auth, rate-limit, metrics, circuit-breaker)
├── tool/
│   ├── Tool.java                  # Interface: definition(), inputSchema(), execute()
│   ├── ToolDefinition.java        # {name, description, schema}
│   ├── McpToolRegistry.java       # ~45 tools, dispatch, aliases
│   ├── ScannerBase.java           # checkProEdition() guard
│   └── *.java                     # Individual tool implementations
├── ui/
│   ├── McpUiPanel.java            # 5-tab container (Status, Settings, ToolTester, Permissions, Messages)
│   ├── StatusPanel.java           # Dashboard: metrics, log w/ filter+search+export
│   ├── SettingsPanel.java         # 20+ prefs, groups, validation, export/import
│   ├── ToolTesterPanel.java       # Split-view: args + result, history, copy-as-curl
│   ├── PermissionsPanel.java      # Collapsible tree, Pro locks, sensitivity gate
│   ├── MessageViewerPanel.java    # Proxy/sitemap/WS browser, syntax-highlighted HTTP
│   ├── SafePanel.java             # Exception-catching wrapper for Swing tabs
│   └── McpColors.java             # Shared palette and font constants
└── util/
    ├── McpJson.java               # JSON-RPC 2.0 encode/decode + schema helpers
    ├── McpError.java               # MCP error codes (-32001 to -32007), RuntimeException
    ├── McpConfig.java              # Preferences-backed singleton, env var overrides, validate()
    ├── LogEntry.java               # Structured log record (JSON-lines)
    ├── ErrorLogger.java            # File-based logger with rotation (5MB/3 files)
    ├── MetricsCollector.java       # Latency percentiles, request rate, tool counts
    ├── RateLimiter.java            # Token-bucket per-IP
    ├── CircuitBreaker.java         # 5 failures → open 30s → half-open probe
    ├── InputValidator.java         # URL, path, cookie, custom_data validation
    ├── RequestCache.java           # TTL + LRU eviction + hit/miss stats
    ├── HttpMessageSerializer.java  # Montoya → JSON (request, response, issue)
    ├── ByteArrayConverter.java     # Hex/UTF-8 via java.util.HexFormat
    ├── PermissionManager.java      # READ_ONLY / READ_WRITE / CUSTOM + sensitivity gate
    ├── TlsManager.java             # Self-signed + PKCS12 via BouncyCastle
    └── VersionInfo.java            # Build timestamp from filtered version.properties

src/test/java/burp/mcp/
├── server/McPServerIntegrationTest.java  # Real NanoHTTPD on random port, reflection mock
└── util/{McpJsonTest, McpErrorTest, PermissionManagerTest}.java
```

## How to add a tool

1. Create `src/main/java/burp/mcp/tool/NewTool.java` implementing `Tool`
2. Implement `definition()`, `inputSchema()`, `execute(Map<String,Object>)`
3. **Every parameter must use `McpJson.property(type, description)`**: never bare `put("param", "string")`
4. **`required` must be `McpJson.createArrayNode()`**: never `createObjectNode()`. DeepSeek rejects `"required": {}` with HTTP 400
5. **Scanner/Collaborator tools must extend `ScannerBase`** and call `checkProEdition()` as the first line of `execute()`
6. Register in `McpToolRegistry.registerAllTools()`
7. Add to `PermissionManager.isReadTool()` if it's a query-only tool
8. Add to `PermissionManager.isSensitive()` if it modifies scope/config/scan state

## Critical Montoya API gotchas

These will silently fail or produce wrong behavior if you guess the wrong method name.

| You might guess | Actually exists | Note |
|----------------|----------------|------|
| `NanoHTTPD` (org.nanohttpd) | `fi.iki.elonen.NanoHTTPD` | Different package |
| `ByteArray.toByteArray()` | `ByteArray.getBytes()` | But `HttpRequest.toByteArray()` exists |
| `byteArrayOf(bytes)` | `ByteArray.byteArray(bytes)` | Static factory |
| `request.url().getHost()` | `request.httpService().host()` | |
| `response.statusText()` | `response.reasonPhrase()` | |
| `Preferences.getString(key, def)` | `getString(key)` → check null | No 2-arg overloads |
| `Cookie.domain()` → Optional | Returns `String` directly | Only `expiration()` is Optional |
| `Scope.isInScope(request)` | `Scope.isInScope(String url)` | Takes String, not request |
| `Decoder.base64()` | Use `java.util.Base64` | Decoder has no encode/decode methods |
| `Scanner.generateReport()` returns path | Returns `void`, takes `(List, Format, Path)` | Create file first |
| `ResponseVariationsAnalyzer` | Doesn't exist | Implemented manually |
| `proxy().listeners()` | Doesn't exist in this version | |
| `webSockets()` on MontoyaApi | Doesn't exist in this version | Use proxy WebSocket history |
| `ProxyHttpRequestResponse.messageId()` | `id()` | |
| `HttpRequestResponse.url()` | **Deprecated** — use `request().url()` | |
| `bodyToString()` | `new String(body().getBytes(), UTF_8)` | |

## Key patterns

### JSON schema for tool parameters

```java
ObjectNode props = McpJson.createObjectNode();
props.set("url", McpJson.property("string", "Target URL"));
props.set("limit", McpJson.property("integer", "Max results", 500));

ArrayNode required = McpJson.createArrayNode(); // NOT createObjectNode()!
required.add("url");
schema.set("required", required);
```

### Error handling in tools

```java
// For missing params
throw new McpError(McpError.INVALID_PARAMS, "'url' is required");

// For Pro-only features
checkProEdition(); // in ScannerBase subclasses

// For network failures
throw new McpError(McpError.REQUEST_FAILED, "Connection refused: " + host);
```

### Logging

```java
// Structured via McPServer (automatic):
// - INFO: successful requests
// - DEBUG: body sizes, auth checks, permission gates, cache hits
// - WARN: rate limits, auth failures, permission denies
// - ERROR: exceptions

// Direct logging (avoid — use structured path):
api.logging().logToOutput("[burp-mcp] message");
api.logging().logToError("error", throwable); // 2-arg prints stack trace
ErrorLogger.log(context, throwable); // to ~/burp-mcp-error.log
```

### Configuration access

```java
McpConfig cfg = McpConfig.getInstance();
int port = cfg.getPort(); // handles null → default
String logLevel = cfg.getLogLevel(); // "INFO" default

// Validate (returns list of error messages)
List<String> errors = cfg.validate();
```

## UI conventions

- Panels are registered in `McpUiPanel` constructor, each wrapped in `SafePanel` (catches paint/layout NPEs)
- StatusPanel gets the server reference via `setServer()` after server.start()
- All colors through `McpColors` constants: no bare hex values
- All fonts through `McpColors.LABEL_FONT` / `MONO_FONT` / `MONO_SMALL`
- Tooltips on every setting field and button

## Testing

- 67 tests: 50 unit + 17 integration
- Integration tests start a real NanoHTTPD on a random free port
- MontoyaApi is mocked via `java.lang.reflect.Proxy` (survives API version bumps)
- Preferences use ConcurrentHashMap-backed in-memory store for test isolation
- Always read error responses from `conn.getErrorStream()`. `getInputStream()` throws on 4xx/5xx

```bash
mvn test  # all tests
```

## Common pitfalls

1. **Shade plugin must exclude JAR signatures**: BouncyCastle's `.SF`/`.DSA`/`.RSA` become invalid after shading, Burp rejects the entire JAR. Exclude `META-INF/*.SF`, `*.DSA`, `*.RSA`, and `module-info.class` from all artifacts.
2. **Thread.setDefaultUncaughtExceptionHandler is JVM-global**: survives extension reloads. Replace it at the top of `initialize()` with a handler that logs to `api.logging()` (never a silent no-op).
3. **POST body reading**: read exactly `Content-Length` bytes, not until EOF (hangs on keep-alive). Do NOT use try-with-resources on `session.getInputStream()` (closes the socket).
4. **Lambda variables must be final**: when filtering with client-side predicates, declare captured variables `final`.
5. **Never block the EDT**: all HTTP and tool execution runs on NanoHTTPD's thread pool.
6. **Montoya objects are immutable**: `with*()` methods return new instances. Capture the return value.

## Preferences reference

All persisted via `api.persistence().preferences()`. See `McpConfig.java` for defaults and getters/setters.

| Key | Default | Range |
|-----|---------|-------|
| `mcp_port` | 4444 | 1-65535 |
| `bind_address` | 127.0.0.1 | valid IP/hostname |
| `thread_pool_size` | 10 | 1-50 |
| `max_queue_size` | 100 | 1-1000 |
| `max_response_body_bytes` | 100000 | 1000-100M |
| `max_sitemap_entries` | 500 | >0 |
| `request_timeout_ms` | 30000 | 1000-300K |
| `cache_ttl_seconds` | 300 | 0-86400 |
| `rate_limit_per_minute` | 100 | 0-10000 (0=disabled) |
| `max_connections_per_ip` | 10 | 1-100 |
| `log_level` | INFO | DEBUG/INFO/WARN/ERROR |
| `logging_file_path` | ~/burp-mcp-error.log | any path |
| `include_request_body` | true | boolean |
| `include_response_body` | true | boolean |
| `metrics_enabled` | true | boolean |
| `cache_enabled` | true | boolean |
| `auth_enabled` | false | boolean |
| `auth_token` | — | string |
| `tls_enabled` | false | boolean |
| `tls_mode` | self_signed | self_signed/custom |
| `tls_keystore_path` | — | PKCS12 path |
| `tls_keystore_password` | burpmcp | string |
