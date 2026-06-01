# Burp MCP Server

Expose Burp Suite to AI agents via the Model Context Protocol. A Java extension that wraps the [Montoya API](https://portswigger.net/burp/extender/api/2.0/) as ~45 MCP tools callable through JSON-RPC 2.0 over HTTP.

```
MCP Client (Hermes / Claude / Cursor)
    |  HTTP POST JSON-RPC 2.0
    v
McPServer (NanoHTTPD :4444)  ← runs inside Burp JVM
    |  Montoya API
    v
Burp Suite Community or Professional
```

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.9+
- Burp Suite (Community or Professional)
- WSL (if building on Windows — builds in WSL, deploys to Windows host)

### Build & Deploy

```bash
git clone <repo-url> burp-mcp
cd burp-mcp
mvn clean package -DskipTests
```

The JAR is auto-copied to `C:\Users\<username>\Downloads\burp-mcp-server.jar`.

In Burp: **Extensions → Installed → Add → Java** → select `burp-mcp-server.jar`.  
Burp watches the Downloads folder and auto-reloads on rebuild.

### Verify

```bash
# List tools
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":"1"}' \
  http://127.0.0.1:4444/

# Get Burp info
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"burp_info","arguments":{}},"id":"2"}' \
  http://127.0.0.1:4444/
```

## Features

| Feature | Description |
|---------|-------------|
| **~45 MCP tools** | HTTP, sitemap, proxy, scope, decoder, cookies, scanner, collaborator, config |
| **5-tab Swing UI** | Status dashboard, Settings, Tool Tester, Permissions tree, Message Viewer |
| **Bearer auth** | Optional token authentication with multi-token support |
| **TLS/HTTPS** | Self-signed or custom PKCS12 keystore |
| **Rate limiting** | Token-bucket per IP, configurable limit, 429 responses |
| **Circuit breakers** | Auto-open after 5 consecutive failures on external tools |
| **Structured logging** | JSON-lines with log rotation, correlation IDs, configurable levels |
| **Metrics** | Prometheus-compatible endpoint, latency percentiles, tool call counts |
| **Health check** | `GET /health` → `{"status":"ok"}` |
| **Permission tree** | Collapsible categories, Pro-edition locking, sensitivity gate |

## Tool Inventory

| Category | Count | Tools |
|----------|-------|-------|
| Info & Metrics | 3 | `burp_info`, `burp_metrics`, `config_list_preferences` |
| HTTP | 8 | `http_send_request`, `http_send_requests`, `http_build_request`, `http_modify_request`, `http_parse_request`, `http_parse_response`, `http_store_request`, `http_get_request` |
| Proxy & WebSocket | 6 | `proxy_history_list`, `proxy_history_get`, `proxy_toggle_intercept`, `proxy_intercept_status`, `proxy_websocket_history_list`, `websocket_history_get` |
| Sitemap & Scope | 5 | `sitemap_list`, `sitemap_list_filtered`, `sitemap_get`, `sitemap_search`, `scope_list`, `scope_check`, `scope_set` |
| Decoder & Analysis | 10 | `decoder_decode`, `decoder_encode`, `http_send_to_repeater`, `http_send_to_intruder`, `http_send_to_comparer`, `http_send_to_decoder`, `cookie_list`, `cookie_set`, `http_diff_responses`, `http_keyword_search` |
| Config | 3 | `config_get`, `config_set`, `logger_add` |
| Scanner (Pro) | 6 | `scanner_start_audit`, `scanner_start_crawl`, `scanner_issues_list`, `scanner_issues_list_filtered`, `scanner_get_issue`, `scanner_generate_report` |
| Collaborator (Pro) | 2 | `collaborator_generate_payload`, `collaborator_interactions` |

## Configuration

Settings persist across Burp restarts via extension preferences.

| Key | Default | Description |
|-----|---------|-------------|
| `mcp_port` | 4444 | HTTP listen port |
| `bind_address` | 127.0.0.1 | Interface to bind |
| `thread_pool_size` | 10 | NanoHTTPD threads (1-50) |
| `max_queue_size` | 100 | Request queue depth (1-1000) |
| `max_response_body_bytes` | 100000 | Truncation limit |
| `max_sitemap_entries` | 500 | Sitemap list cap |
| `request_timeout_ms` | 30000 | HTTP request timeout |
| `cache_ttl_seconds` | 300 | Request cache TTL |
| `rate_limit_per_minute` | 100 | Per-IP rate limit (0=disabled) |
| `max_connections_per_ip` | 10 | Concurrent connections per IP |
| `log_level` | INFO | DEBUG/INFO/WARN/ERROR |
| `auth_enabled` | false | Require Bearer token |
| `auth_token` | — | Bearer token value |
| `tls_enabled` | false | Encrypt with TLS |
| `tls_mode` | self_signed | self_signed or custom PKCS12 |

Environment variable overrides: `BURP_MCP_PORT`, `BURP_MCP_BIND_ADDRESS`, `BURP_MCP_AUTH_TOKEN`, `BURP_MCP_LOG_LEVEL`.

## Hermes Integration

```yaml
# ~/.hermes/config.yaml
mcp_servers:
  burp:
    url: http://127.0.0.1:4444
```

Tools appear as `mcp_burp_*`. For TLS with auth:

```yaml
mcp_servers:
  burp:
    url: https://127.0.0.1:4444
    headers:
      Authorization: Bearer <token>
```

## Claude Desktop Integration

```json
{
  "mcpServers": {
    "burp": {
      "url": "http://127.0.0.1:4444"
    }
  }
}
```

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Extension doesn't load | Invalid JAR signatures | Verify shade plugin excludes `.SF`/`.DSA`/`.RSA` files |
| Port already in use | Another process on 4444 | Change `mcp_port` preference and reload |
| Scanner tools return -32001 | Running Community Edition | Expected — upgrade to Professional |
| Hermes can't connect | Server not running | Verify with curl, check port matches config |
| Empty proxy history | No traffic captured | Browse a site through Burp first |

## License

MIT
