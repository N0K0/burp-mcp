# AGENTS.md

Burp Suite MCP Server, a Java extension exposing the Montoya API as MCP tools.
AI agents (you) call these tools to interact with Burp programmatically.

## Build

```bash
cd /home/nikolas/git/burp-mcp
mvn clean compile          # compile only
mvn test                   # run 180 JUnit 5 + AssertJ tests (live Burp suite excluded, see below)
mvn clean package -DskipTests  # build fat JAR → C:\Users\nikolas\Downloads\
```

Java 17+, Maven 3.9+. WSL builds, Windows host runs Burp.

## Project structure

```
src/main/java/burp/mcp/
├── BurpMcpExtension.java          # BurpExtension entry point
├── server/
│   ├── McPServer.java             # NanoHTTPD JSON-RPC handler (health, auth, rate-limit, metrics, circuit-breaker, access gate)
│   ├── McpServerManager.java      # start/stop/restart lifecycle; owns ApprovalManager + AccessGate; binds TCP and the Unix socket independently
│   ├── AccessGate.java            # permission + target-scope evaluation, operator-approval wait/pending protocol
│   └── UnixSocketServer.java      # Unix-domain-socket HTTP listener → McPServer.serve() via fake IHTTPSession
├── tool/
│   ├── Tool.java                  # Interface: definition(), inputSchema(), execute()
│   ├── TargetedTool.java          # targetUrls(args) for traffic-sending tools (scope gate input)
│   ├── ToolDefinition.java        # {name, description, schema}
│   ├── McpToolRegistry.java       # ~55 tools, dispatch, aliases
│   ├── ScannerBase.java           # checkProEdition() guard
│   └── *.java                     # Individual tool implementations
├── ui/
│   ├── McpUiPanel.java            # 6-tab container (Status, Settings, ToolTester, Permissions, Approvals, Messages)
│   ├── StatusPanel.java           # Dashboard: metrics, log w/ filter+search+export
│   ├── SettingsPanel.java         # 20+ prefs, groups, validation, export/import
│   ├── ToolTesterPanel.java       # Split-view: args + result, history, copy-as-curl
│   ├── PermissionsPanel.java      # Modes + per-tool Allow/Prompt/Deny tree, sensitivity gate, scope enforcement
│   ├── ApprovalsPanel.java        # Pending-decision cards, popup, session grants, history, tab badge
│   ├── ApprovalCard.java          # One pending request: choices + deny reason, shared by tab and popup
│   ├── MessageViewerPanel.java    # Proxy/sitemap/WS browser, syntax-highlighted HTTP
│   ├── SafePanel.java             # Exception-catching wrapper for Swing tabs
│   └── McpColors.java             # Shared palette and font constants
└── util/
    ├── McpJson.java               # JSON-RPC 2.0 encode/decode + schema helpers
    ├── McpError.java               # MCP error codes (-32001 to -32010), RuntimeException
    ├── McpConfig.java              # Preferences-backed singleton, env var overrides, validate()
    ├── ProjectFiles.java            # recent-project lookup → default socket next to the .burp file
    ├── LogEntry.java               # Structured log record (JSON-lines)
    ├── ErrorLogger.java            # File-based logger with rotation (5MB/3 files)
    ├── MetricsCollector.java       # Latency percentiles, request rate, tool counts
    ├── RateLimiter.java            # Token-bucket per-IP
    ├── CircuitBreaker.java         # 5 failures → open 30s → half-open probe
    ├── InputValidator.java         # URL, path, cookie, custom_data validation
    ├── RequestCache.java           # TTL + LRU eviction + hit/miss stats
    ├── HttpMessageSerializer.java  # Montoya → JSON (request, response, issue)
    ├── ByteArrayConverter.java     # Hex/UTF-8 via java.util.HexFormat
    ├── PermissionManager.java      # READ_ONLY / READ_WRITE / PROMPT / CUSTOM + per-tool Policy + sensitivity gate
    ├── ApprovalManager.java        # pending approvals, session grants, TTL, listener events, scope-add callback
    ├── TlsManager.java             # Self-signed + PKCS12 via BouncyCastle
    └── VersionInfo.java            # Build timestamp from filtered version.properties

src/test/java/burp/mcp/
├── server/McPServerIntegrationTest.java  # Real NanoHTTPD on random port, reflection mock, prompt/scope gate cases
├── server/AccessGateTest.java            # Gate decisions with a mocked Scope
└── util/{McpJsonTest, McpErrorTest, PermissionManagerTest, ApprovalManagerTest}.java
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
9. If the tool sends traffic, implement `TargetedTool#targetUrls(args)` (pure parsing,
   no Montoya factories) so the scope gate can prompt before out-of-scope calls

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

### Access gate (permission + scope) and approvals

Dispatch order in `McPServer`: rate limit → parse args → circuit breaker →
`AccessGate.check(ip, toolName, args, targets)` → tool.

```java
// PermissionManager.evaluate(tool) -> ALLOW | PROMPT | DENY (Access)
// AccessGate resolves PROMPT and out-of-scope targets through ApprovalManager:
//   - waits up to approval_wait_seconds for an operator decision
//   - on timeout returns APPROVAL_PENDING (-32008); the agent retries the same
//     call (matched by client IP + tool + normalized-args fingerprint)
//   - deny returns PERMISSION_DENIED (-32005) with data.reason
//   - scope_enforcement=deny returns OUT_OF_SCOPE (-32010) without a dialog
// Gate results carry structured data: {decision, source, reason, approval_id, targets}
// Add-to-scope decisions call api.scope().includeInScope(origin) at resolve time.
```

Rules that must hold when extending this:
- A gated call must never execute if no live client collects the decision
  (retry-only execution is deliberate; do not switch to fire-and-forget).
- `ApprovalManager.Listener` callbacks fire on arbitrary threads — marshal to the EDT.
- Adding a traffic-sending tool without `TargetedTool` silently bypasses scope checks.

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
- StatusPanel gets the `McpServerManager` via `setManager()`; Start/Stop/Restart run off the EDT (SwingWorker) and the panel polls manager state
- All colors through `McpColors` constants: no bare hex values
- All fonts through `McpColors.LABEL_FONT` / `MONO_FONT` / `MONO_SMALL`
- Tooltips on every setting field and button

## Testing

- 180 tests: unit + mocked-transport integration (McPServerIntegrationTest,
  UnixSocketServerTest); LiveBurpIT (14 tests vs a real Burp) is opt-in
- Integration tests start a real NanoHTTPD on a random free port
- Gate coverage: AccessGateTest (policy/scope decisions with a mocked Scope),
  ApprovalManagerTest (grants, deny reasons, TTL, retry dedupe), prompt/scope
  scenarios in McPServerIntegrationTest using registered test tools
- UnixSocketServerTest drives the real socket transport over temp-dir sockets (TCP never started) and covers stop→start rebinding
- McpServerManagerTest pins the busy-TCP-port lifecycle: socket keeps serving, restart rebinds both listeners
- LiveBurpIT (`mvn test -Dtest=LiveBurpIT`, needs Burp + extension on
  BURP_MCP_TEST_BASE_URL, default 127.0.0.1:4444; BURP_MCP_TEST_AUTH_TOKEN
  for Bearer; BURP_MCP_TEST_ALLOW_SCANS=1 for the crawl characterization).
  Spins its own loopback HTTP+TLS targets, seeds their origins into Burp scope
  (only what it added is removed), scope-churns one unique subtree (cleaned
  up), skips gracefully when Burp is unreachable. Honors 429s.
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
7. **Swing timers outlive their extension instance**: a `javax.swing.Timer` keeps firing after the panel is discarded; after a reload its Montoya proxies are dead, so it spams NPEs on the EDT. Stop every timer in the unloading handler — `McpUiPanel.dispose()` (called from `BurpMcpExtension`) stops the Status timer and detaches the Approvals listener/popup. If an EDT lambda can touch the API, wrap it and stop polling on failure.

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
| `socket_enabled` | true | boolean (Unix socket alongside TCP) |
| `socket_path` | — (auto) | explicit path, or empty = next to the `.burp` project file (temp path for temporary projects) |
| `scope_enforcement` | prompt | off/prompt/deny; env `BURP_MCP_SCOPE_ENFORCEMENT` |
| `approval_wait_seconds` | 30 | 5-600; how long a gated call blocks for a decision |
| `approval_ttl_seconds` | 300 | 30-3600; how long an undecided request stays queued |
| `approval_popup_enabled` | true | boolean; popup dialog on new approval requests |
