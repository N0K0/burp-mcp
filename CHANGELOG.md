# Changelog

All notable changes to the Burp MCP Server plugin.

## [1.2.0] - 2026-10-05

### Added
- Server controls in the Status tab: **Start**, **Stop**, and **Restart** without reloading the extension; Restart re-reads saved settings (port, bind, TLS, socket, pools)
- Unix domain socket transport alongside TCP. The default path sits next to the open `.burp` project file (`exness.burp` → `exness.sock`), so multiple projects never clash on ports; `socket_enabled`, `socket_path`, and `BURP_MCP_SOCKET_PATH` override, and temporary projects fall back to `<tmp>/burp-mcp-<hash>.sock`
- `burp_info` reports the live `mcpSocketPath`; the Status tab shows it with a **Copy Socket Path** button
- New tools: `project_create`, `http_send_to_organizer`, `organizer_list`, `task_engine_status`, `task_engine_set`, `scanner_crawl_status`, `scanner_crawl_stop`, `scanner_bcheck_import`
- `http_mode` on `http_send_request` (`auto`, `http1`, `http2`, `http2-no-alpn`)

### Changed
- TCP bind failures no longer abort startup: a busy port (another Burp instance) is reported in the Status tab while the Unix socket keeps serving this project
- Release workflow no longer deploys to GitHub Pages (unavailable on this plan) and re-runs upload the jar to the existing release

## [1.1.0] - 2026-10-03

### Fixed
- Permission drift: `scope_list`, `sitemap_search`, `config_list_preferences`, `websocket_history_get`, and `burp_metrics` are now read-only tools
- Alias bypass: permission checks use the canonical tool name, so disabling a tool also disables its aliases
- Auth bypass when no token is configured; token comparison is now constant-time
- Rate limit of 0 permanently returning 429; it now correctly disables limiting
- Request cache crashing init on TTL 0 and leaking its cleanup thread on reload
- `McpJson.toJson` returning non-JSON on serialization failure
- ToolTester and MessageViewer blocking the Swing EDT on slow operations
- Settings export writing a trailing comma and dropping `tls_keystore_path`
- Permissions panel preset inversion and hidden quick/filter bar
- Schema defaults: `config_json`, request `body`, cookie `expiration`, missing `request_id` requirement

### Added
- Enforced `max_connections_per_ip` (HTTP 503 + `-32007`) with a live active-connections gauge
- Bounded worker pool honoring `thread_pool_size` / `max_queue_size`
- `metrics_enabled` and `cache_enabled` now take effect instead of being ignored
- Input validation wired into HTTP, scanner, scope, cookie, collaborator, sitemap, and config tools
- Symmetric, char-boundary-safe body truncation for requests and audit issues

## [1.1.0] - 2026-06-01

### Added
- **Structured Logging:** `LogEntry` record with ISO 8601 timestamps, log levels, correlation IDs, and JSON-lines output to disk
- **Log Levels:** `log_level` preference (DEBUG/INFO/WARN/ERROR, default INFO) with level filtering in UI log viewer
- **Log Rotation:** ErrorLogger now rotates at 5MB, keeps 3 backup files, writes JVM info on session start
- **Metrics:** `MetricsCollector` with latency percentile ring buffer, rolling request rate, per-tool call counts; new `burp_metrics` MCP tool (JSON + Prometheus format); `metrics_enabled` preference
- **Health Endpoint:** `GET /health` returns `{"status":"ok"}/{"status":"error"}` with uptime and version; `?format=prometheus` for Prometheus export
- **Rate Limiting:** Token-bucket rate limiter keyed by source IP with `rate_limit_per_minute` (default 100) and 10% burst; returns HTTP 429 with Retry-After header
- **Circuit Breaker:** For external-calling tools (http_send_*, scanner_start_*, collaborator_*); opens after 5 consecutive failures in 60s, half-open probe after 30s
- **New Tools:** `burp_metrics`, `config_list_preferences`, `sitemap_search` (regex full-text), `scope_list`; tool aliases (`proxy_list`→`proxy_history_list`, `send_request`→`http_send_request`, etc.)
- **UI dashboard:** StatusPanel now shows uptime, req/min, P95 latency, error count, rate-limited count
- **UI log filtering:** Level filter dropdown (ALL/INFO/WARN/ERROR/DEBUG), real-time search with highlighting, auto-scroll toggle, export button
- **UI settings:** Inline validation (red border on invalid fields), logical group separators (Server/Limits/Features/Logging/Security), export/import config as JSON
- **UI ToolTester:** Split-view (args/result side-by-side), invocation history dropdown, Copy cURL, Share as markdown, Format JSON button, visual error treatment
- **UI permissions:** Presets dropdown (Read-Write/Read-Only/Custom), tool count summary, search/filter bar, lock icons for sensitive tools, Select All Read/Write buttons, status bar
- **UI McpColors:** Shared color palette and font constants for consistent styling across all panels
- **Input Validation:** `InputValidator` utility for URLs, file paths, cookies, and Collaborator data
- **Error Translation:** McpServer maps `ConnectException`, `SocketTimeoutException`, `UnknownHostException`, `SSLException`, and `IllegalArgumentException` to meaningful MCP error codes
- **Cache Improvements:** RequestCache now has LRU eviction (max 1000 entries), hit/miss/eviction stats, `cache_enabled` preference
- **Config:** `log_level`, `logging_file_path`, `max_queue_size`, `max_connections_per_ip`, `metrics_enabled`, `cache_enabled`, `audit_log_enabled` preferences; environment variable support (`BURP_MCP_PORT`, `BURP_MCP_BIND_ADDRESS`, `BURP_MCP_AUTH_TOKEN`, `BURP_MCP_LOG_LEVEL`)
- **Error Codes:** `-32006 RATE_LIMITED`, `-32007 SERVER_BUSY`

### Changed
- Refactored `McPServer.serve()` with structured logging, correlation IDs, rate limiting, circuit breaker, and error translation
- `McpToolRegistry.callTool()` resolves aliases with DEBUG-level logging
- `ErrorLogger` uses structured JSON-lines output with rotation
- `BurpMcpExtension` wires MetricsCollector, ErrorLogger config path, and new tools
- Settings panel now has 20+ configurable preferences with validated input fields

### Fixed
- Inline validation in SettingsPanel catches invalid port numbers and numeric ranges before save
- Log area auto-truncates at 10,000 lines to prevent UI memory leaks

## [1.0.0] - 2026-05-28

### Added
- Initial release with ~40 MCP tools covering HTTP, sitemap, proxy, scope, decoder, repeater, intruder, comparer, cookie, scanner (Pro), collaborator (Pro), config, and logger
- 4-tab Swing UI (Status/Settings/ToolTester/Permissions)
- Bearer token authentication middleware
- TLS/HTTPS support via BouncyCastle (self-signed or custom PKCS12)
- Permission middleware (READ_ONLY/READ_WRITE/CUSTOM with sensitivity gate)
- File-based ErrorLogger for offline log inspection
- JSON-RPC 2.0 over HTTP via NanoHTTPD
- 41 JUnit 5 + AssertJ tests (26 unit + 15 integration)
