# Burp MCP Server

Let AI agents drive Burp Suite through the Model Context Protocol. A Java extension that wraps the [Montoya API](https://portswigger.net/burp/extender/api/2.0/) as 45 MCP tools over JSON-RPC 2.0.

```
MCP Client (Claude / Hermes / Cursor)
    |  HTTP POST JSON-RPC 2.0
    v
McPServer (NanoHTTPD :4444)  <- runs inside Burp JVM
    |  Montoya API
    v
Burp Suite Community or Professional
```

Scanner and Collaborator tools need Professional. Everything else works on Community.

## Quick start

You need Java 17+, Maven 3.9+, and Burp Suite.

```bash
git clone https://github.com/your-org/burp-mcp.git
cd burp-mcp
mvn clean package -DskipTests
```

That builds `target/burp-mcp-server.jar`. On WSL it also tries to copy to `C:\Users\nikolas\Downloads\burp-mcp-server.jar`. That copy never fails the build on Linux or macOS.

Load it in Burp: **Extensions → Installed → Add → Java** → pick the JAR. When you rebuild, remove and re-add the extension (Burp does not auto-reload).

Check it works:

```bash
# List tools
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":"1"}' \
  http://127.0.0.1:4444/

# Get Burp info
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"burp_info","arguments":{}},"id":"2"}' \
  http://127.0.0.1:4444/

# Health
curl -s http://127.0.0.1:4444/health
```

## What you get

| Feature | Notes |
|---------|-------|
| **45 MCP tools** | HTTP, sitemap, proxy, scope, decoder, cookies, scanner, collaborator, config |
| **5-tab UI** | Status, Settings, Tool Tester, Permissions, Message Viewer |
| **Bearer auth** | Optional single-token `Authorization: Bearer <token>` |
| **TLS** | Self-signed or your own PKCS12 keystore |
| **Rate limiting** | Token-bucket per IP, `429` with `Retry-After` when you hit it |
| **Circuit breakers** | External tools trip open after 5 failures in 60s, probe after 30s |
| **Logging** | JSON-lines with rotation, correlation IDs, `DEBUG/INFO/WARN/ERROR` |
| **Metrics** | `burp_metrics` tool and `GET /health?format=prometheus` |
| **Permissions** | Read-Write / Read-Only / Custom plus a sensitivity gate |

Aliases work too: `proxy_list` → `proxy_history_list`, `send_request` → `http_send_request`, and so on. Permissions and metrics use the canonical name, so disabling a tool also disables its aliases.

## Tool inventory

45 total (44 in `registerAllTools` + `burp_metrics` wired separately with its `MetricsCollector`).

| Category | Count | Tools |
|----------|-------|-------|
| Info & Metrics | 3 | `burp_info`, `burp_metrics`, `config_list_preferences` |
| HTTP | 8 | `http_send_request`, `http_send_requests`, `http_build_request`, `http_modify_request`, `http_parse_request`, `http_parse_response`, `http_store_request`, `http_get_request` |
| Proxy & WebSocket | 6 | `proxy_history_list`, `proxy_history_get`, `proxy_toggle_intercept`, `proxy_intercept_status`, `proxy_websocket_history_list`, `websocket_history_get` |
| Sitemap & Scope | 7 | `sitemap_list`, `sitemap_list_filtered`, `sitemap_get`, `sitemap_search`, `scope_list`, `scope_check`, `scope_set` |
| Decoder & Analysis | 10 | `decoder_decode`, `decoder_encode`, `http_send_to_repeater`, `http_send_to_intruder`, `http_send_to_comparer`, `http_send_to_decoder`, `cookie_list`, `cookie_set`, `http_diff_responses`, `http_keyword_search` |
| Config & Logger | 3 | `config_get`, `config_set`, `logger_add` |
| Scanner (Pro) | 6 | `scanner_start_audit`, `scanner_start_crawl`, `scanner_issues_list`, `scanner_issues_list_filtered`, `scanner_get_issue`, `scanner_generate_report` |
| Collaborator (Pro) | 2 | `collaborator_generate_payload`, `collaborator_interactions` |

## Configuration

Burp stores settings in extension preferences, so they survive restarts.

| Key | Default | What it does |
|-----|---------|--------------|
| `mcp_port` | 4444 | Listen port (1-65535) |
| `bind_address` | 127.0.0.1 | Interface to bind |
| `thread_pool_size` | 10 | Worker threads (1-50) |
| `max_queue_size` | 100 | Queue depth (1-1000) |
| `max_response_body_bytes` | 100000 | Truncates tool output (1000-100M) |
| `max_sitemap_entries` | 500 | Sitemap list cap (>0) |
| `request_timeout_ms` | 30000 | HTTP timeout (1000-300K) |
| `cache_ttl_seconds` | 300 | Cache TTL, 0 disables timer cleanup (0-86400) |
| `rate_limit_per_minute` | 100 | Per-IP limit, 0 disables (0-10000) |
| `max_connections_per_ip` | 10 | Concurrent connections per IP, extras get 503 (1-100) |
| `log_level` | INFO | DEBUG/INFO/WARN/ERROR |
| `logging_file_path` | (empty) | JSON-lines log path |
| `include_request_body` | true | Include request bodies in output |
| `include_response_body` | true | Include response bodies in output |
| `metrics_enabled` | true | Collect metrics (takes effect on reload) |
| `cache_enabled` | true | Use request cache |
| `audit_log_enabled` | false | Extra audit logging |
| `auth_enabled` | false | Require Bearer token |
| `auth_token` | — | Token value, never exported |
| `tls_enabled` | false | Serve HTTPS |
| `tls_mode` | self_signed | `self_signed` or `custom` |
| `tls_keystore_path` | — | PKCS12 path for `custom` mode |
| `tls_keystore_password` | burpmcp | Keystore password |

Env overrides win over stored prefs:

```bash
BURP_MCP_PORT=4444
BURP_MCP_BIND_ADDRESS=127.0.0.1
BURP_MCP_AUTH_TOKEN=secret
BURP_MCP_LOG_LEVEL=INFO
```

Bad values are logged at startup (`Invalid config: ...`). Empty token with `auth_enabled=true` refuses to start serving rather than letting everyone in.

Export in Settings skips `auth_token` and `tls_keystore_password` on purpose. Copy those by hand if you move machines.

## Permissions

Three modes in the Permissions tab:

- **Read-Write**: everything allowed (default).
- **Read-Only**: query/list/parse/decode only. That includes `burp_info`, `burp_metrics`, `sitemap_*`, `proxy_history_*`, `websocket_history_get`, `scope_check`, `scope_list`, `cookie_list`, `decoder_*`, `http_parse_*`, `http_get_request`, `config_get`, `config_list_preferences`, scanner issue reads, and analysis reads.
- **Custom**: pick tools one by one. Deny wins; anything you do not explicitly enable stays allowed, so review the list before you rely on it.

The sensitivity switch blocks `scope_set`, `config_set`, `scanner_start_audit`, `scanner_start_crawl`, and `proxy_toggle_intercept` in every mode. Turn it off to use them.

## Auth, TLS, and limits

Auth:

```yaml
# Hermes with TLS + auth
mcp_servers:
  burp:
    url: https://127.0.0.1:4444
    headers:
      Authorization: Bearer <token>
```

TLS: set `tls_enabled=true`. Use `self_signed` for local work or `custom` with a PKCS12 path. `custom` with no path fails fast instead of silently falling back.

Limits: inbound bodies cap at 10 MB. Tool output truncates at `max_response_body_bytes`. Rate limiting is per socket IP. `X-Forwarded-For` is ignored because clients can spoof it. `0` means off.

Health:

```bash
curl http://127.0.0.1:4444/health
curl http://127.0.0.1:4444/health?format=prometheus
```

Health stays unauthenticated so load balancers can poll it.

## Errors

| Code | Meaning |
|------|---------|
| `-32001` | Pro-only on Community |
| `-32002` | Bad params |
| `-32003` | Unknown method/tool |
| `-32004` | Request failed (DNS, timeout, refused) |
| `-32005` | Auth or permission denied |
| `-32006` | Rate limited (`429`) |
| `-32007` | Server busy (HTTP 503: connection cap or saturated pool) |

Malformed `Content-Length` or truncated bodies return `400`, not `500`.

## Client setup

Hermes:

```yaml
# ~/.hermes/config.yaml
mcp_servers:
  burp:
    url: http://127.0.0.1:4444
```

Claude Desktop:

```json
{
  "mcpServers": {
    "burp": {
      "url": "http://127.0.0.1:4444"
    }
  }
}
```

## Build and test

```bash
mvn clean compile
mvn test
mvn clean package -DskipTests
```

No Maven wrapper is checked in. Tests are JUnit 5 + AssertJ with a live NanoHTTPD integration test on a random port.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Extension won't load | Corrupt pref or bad port | Check Burp output for `Invalid config`, fix in Settings |
| Port busy | Something else on 4444 | Change `mcp_port`, reload extension |
| `-32001` on scanner | Community edition | Expected. Needs Professional |
| `401` on every call | `auth_enabled` with wrong token | Copy token from Settings, check `BURP_MCP_AUTH_TOKEN` |
| `503` auth misconfigured | `auth_enabled=true`, empty token | Set a token, reload |
| `429` | Rate limit hit | Wait for `Retry-After`, raise limit, or set `0` to disable |
| Empty proxy history | No traffic yet | Browse through Burp first, then retry |
| TLS errors with self-signed | No SAN in older certs | Use `custom` PKCS12 or trust the cert explicitly |

## Security notes

Binds to loopback by default. Opening `bind_address` to `0.0.0.0` without auth and TLS exposes Burp to your network. Do not do that on untrusted networks. `http_send_request(s)` can reach any URL Burp can, including cloud metadata IPs. Use Read-Only mode and the sensitivity gate when agents don't need to send traffic.

## License

MIT
