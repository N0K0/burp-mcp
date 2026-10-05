# Burp MCP Server

A Burp Suite extension that lets an AI coding agent work inside Burp: send
requests, read proxy history, check scope, run scans, and so on. It exposes
the [Montoya API](https://portswigger.net/burp/extender/api/2.0/) as
[MCP](https://modelcontextprotocol.io/) tools over JSON-RPC 2.0.

```
MCP client (Claude, Cursor, Hermes, opencode, ...)
    |  HTTP POST, JSON-RPC 2.0, over TCP or a Unix socket
    v
McPServer inside Burp (NanoHTTPD on :4444 and/or <project>.sock)
    |  Montoya API
    v
Burp Suite Community or Professional
```

Scanner and Collaborator tools need Burp Professional. Everything else runs
on Community.

## Install

You need Burp Suite, JDK 17 or newer, and Maven 3.9 or newer.

```bash
git clone https://github.com/N0K0/burp-mcp.git
cd burp-mcp
mvn clean package -DskipTests
```

The build writes `target/burp-mcp-server.jar`. Load it in Burp under
Extensions -> Installed -> Add -> Java, and pick the jar. Burp does not
reload extensions on its own, so after a rebuild remove the old entry and
add the jar again.

On WSL, if `/mnt/c` exists, the build also copies the jar to
`C:\Users\nikolas\Downloads\burp-mcp-server.jar`. Everywhere else that step
is skipped and never fails the build.

## Check that it runs

```bash
# list the tools
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":"1"}' \
  http://127.0.0.1:4444/

# ask Burp about itself
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"burp_info","arguments":{}},"id":"2"}' \
  http://127.0.0.1:4444/

# health check
curl -s http://127.0.0.1:4444/health
```

The same calls work over the Unix socket. The live path is in
`burp_info` -> `mcpSocketPath`, and the Status tab can copy it for you:

```bash
# discover the live socket path
SOCK=$(curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"burp_info","arguments":{}},"id":"1"}' \
  http://127.0.0.1:4444/ \
  | python3 -c "import json,sys; print(json.loads(json.load(sys.stdin)['result']['content'][0]['text'])['mcpSocketPath'])")

curl -s --unix-socket "$SOCK" http://localhost/health

curl -s --unix-socket "$SOCK" -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":"1"}' \
  http://localhost/
```

## Two transports

TCP listens on `mcp_port`, 4444 by default. It is the straightforward
option: one port, one Burp instance.

The Unix socket is enabled by default and sits next to the open project
file, so `~/burp-projects/exness-20261004.burp` gets
`~/burp-projects/exness-20261004.sock`. Two Burp instances with different
projects get two sockets and never fight over 4444. Temporary projects,
project files the extension cannot locate, and paths that are too long for
the OS fall back to `<tmp>/burp-mcp-<hash>.sock`. Override the path with the
`socket_path` setting or `BURP_MCP_SOCKET_PATH`, or turn the socket off with
`socket_enabled=false`.

If another program already holds 4444, this instance keeps serving on its
socket and the Status tab shows `Failed: port 4444 already in use (another
Burp instance?)`. Close the other Burp and press Restart. Restart also
re-reads saved settings, so port, bind address, TLS, socket path, and pool
sizes change without reloading the extension.

The socket speaks the same HTTP JSON-RPC as TCP, including auth, permissions,
rate limits, and `/health`. It handles one request per connection with
`Connection: close`. The socket file is owner-only and removed on Stop,
Restart, or unload; a stale file from a crash is replaced on the next start.
Python clients need an AF_UNIX connector, for example
`http.client.HTTPConnection` over `socket.AF_UNIX`. `curl --unix-socket`
works as is.

## The extension tab

The extension adds a "Burp MCP" tab with five screens. Status has
Start/Stop/Restart for the listeners, a metrics dashboard, and the request
log with filter, search, and export. Settings holds every preference below,
with validation and JSON export/import. Tool Tester runs a single tool by
hand and shows the result. Permissions controls what agents may call.
Messages browses proxy, sitemap, and WebSocket traffic with syntax
highlighting.

## Tools

53 tools, grouped by what they touch. Aliases work too: `proxy_list` maps to
`proxy_history_list`, `send_request` to `http_send_request`, and so on.
Permissions and metrics always use the canonical name, so disabling a tool
also disables its aliases.

| Category | Count | Tools |
|----------|-------|-------|
| Info & metrics | 4 | `burp_info`, `burp_metrics`, `config_list_preferences`, `project_create` |
| HTTP | 8 | `http_send_request`, `http_send_requests`, `http_build_request`, `http_modify_request`, `http_parse_request`, `http_parse_response`, `http_store_request`, `http_get_request` |
| Proxy & WebSocket | 6 | `proxy_history_list`, `proxy_history_get`, `proxy_toggle_intercept`, `proxy_intercept_status`, `proxy_websocket_history_list`, `websocket_history_get` |
| Sitemap & scope | 7 | `sitemap_list`, `sitemap_list_filtered`, `sitemap_get`, `sitemap_search`, `scope_list`, `scope_check`, `scope_set` |
| Decoder & analysis | 10 | `decoder_decode`, `decoder_encode`, `http_send_to_repeater`, `http_send_to_intruder`, `http_send_to_comparer`, `http_send_to_decoder`, `cookie_list`, `cookie_set`, `http_diff_responses`, `http_keyword_search` |
| Config & logger | 3 | `config_get`, `config_set`, `logger_add` |
| Scanner (Pro) | 9 | `scanner_start_audit`, `scanner_start_crawl`, `scanner_crawl_status`, `scanner_crawl_stop`, `scanner_issues_list`, `scanner_issues_list_filtered`, `scanner_get_issue`, `scanner_generate_report`, `scanner_bcheck_import` |
| Collaborator (Pro) | 2 | `collaborator_generate_payload`, `collaborator_interactions` |
| Organizer | 2 | `http_send_to_organizer`, `organizer_list` |
| Task engine | 2 | `task_engine_status`, `task_engine_set` |

`burp_metrics` returns request counts, latencies, and per-tool usage. The
same numbers are available in Prometheus format from
`GET /health?format=prometheus`.

## Settings

Burp stores these in extension preferences, so they survive restarts.
Everything here also has a field in the Settings tab.

| Key | Default | What it does |
|-----|---------|--------------|
| `mcp_port` | 4444 | TCP listen port (1-65535) |
| `bind_address` | 127.0.0.1 | Interface to bind |
| `thread_pool_size` | 10 | Worker threads (1-50) |
| `max_queue_size` | 100 | Queue depth (1-1000) |
| `max_response_body_bytes` | 100000 | Truncates tool output (1000-100M) |
| `max_sitemap_entries` | 500 | Sitemap list cap (>0) |
| `request_timeout_ms` | 30000 | HTTP timeout (1000-300K) |
| `cache_ttl_seconds` | 300 | Cache TTL; 0 turns off timer cleanup (0-86400) |
| `rate_limit_per_minute` | 100 | Per-IP limit; 0 disables (0-10000) |
| `max_connections_per_ip` | 10 | Concurrent connections per IP; extras get 503 (1-100) |
| `socket_enabled` | true | Serve on a Unix socket alongside TCP |
| `socket_path` | (empty) | Explicit socket path; empty means next to the `.burp` project file |
| `log_level` | INFO | DEBUG/INFO/WARN/ERROR |
| `logging_file_path` | (empty) | JSON-lines log path; empty means `~/burp-mcp-error.log` |
| `include_request_body` | true | Include request bodies in output |
| `include_response_body` | true | Include response bodies in output |
| `metrics_enabled` | true | Collect metrics (applied on Start/Restart) |
| `cache_enabled` | true | Use the request cache |
| `audit_log_enabled` | false | Extra audit logging |
| `auth_enabled` | false | Require a Bearer token |
| `auth_token` | (none) | Token value; never exported |
| `tls_enabled` | false | Serve HTTPS |
| `tls_mode` | self_signed | `self_signed` or `custom` |
| `tls_keystore_path` | (none) | PKCS12 path for `custom` mode |
| `tls_keystore_password` | burpmcp | Keystore password |

Environment variables win over stored preferences:

```bash
BURP_MCP_PORT=4444
BURP_MCP_BIND_ADDRESS=127.0.0.1
BURP_MCP_AUTH_TOKEN=secret
BURP_MCP_LOG_LEVEL=INFO
BURP_MCP_SOCKET_PATH=/tmp/burp-proj-a.sock
```

Startup logs bad values as `Invalid config: ...`. If `auth_enabled=true` and
the token is empty, the server refuses to serve rather than letting everyone
in. Config export leaves out `auth_token` and `tls_keystore_password`, so
copy those by hand when you move to another machine.

## Permissions

Three modes in the Permissions tab:

- Read-Write: everything allowed. This is the default.
- Read-Only: queries only. That covers `burp_info`, `project_create`,
  `burp_metrics`, `sitemap_*`, `proxy_history_*`, `proxy_intercept_status`,
  `proxy_websocket_history_list`, `websocket_history_get`, `scope_check`,
  `scope_list`, `organizer_list`, `task_engine_status`,
  `scanner_crawl_status`, `cookie_list`, `decoder_*`, `http_parse_*`,
  `http_get_request`, `http_diff_responses`, `http_keyword_search`,
  `config_get`, `config_list_preferences`, the scanner issue reads, and
  `collaborator_interactions`.
- Custom: pick tools one by one. Deny wins. Anything you do not explicitly
  enable stays allowed, so read the list before trusting it.

The sensitivity switch blocks `scope_set`, `config_set`,
`scanner_start_audit`, `scanner_start_crawl`, `scanner_crawl_stop`,
`scanner_bcheck_import`, `task_engine_set`, and `proxy_toggle_intercept` in
every mode. Turn it off when you need them.

## Auth, TLS, and limits

Auth is a single Bearer token:

```yaml
mcp_servers:
  burp:
    url: https://127.0.0.1:4444
    headers:
      Authorization: Bearer <token>
```

Set `tls_enabled=true` to serve HTTPS. `tls_mode` picks between
`self_signed` and `custom` with a PKCS12 keystore; `custom` without a
keystore path fails at startup instead of falling back to plain HTTP.

Inbound request bodies cap at 10 MB, and tool output truncates at
`max_response_body_bytes`. Rate limiting is per socket IP; `X-Forwarded-For`
is ignored because clients can spoof it. A limit of `0` disables it.

Health stays unauthenticated so monitoring can poll it:

```bash
curl http://127.0.0.1:4444/health
curl http://127.0.0.1:4444/health?format=prometheus
```

## Error codes

| Code | Meaning |
|------|---------|
| `-32001` | Pro-only tool on Community |
| `-32002` | Bad params |
| `-32003` | Unknown method or tool |
| `-32004` | Request failed (DNS, timeout, refused) |
| `-32005` | Auth or permission denied |
| `-32006` | Rate limited (`429`) |
| `-32007` | Server busy (HTTP 503: connection cap or saturated pool) |

A malformed `Content-Length` or a truncated body returns `400`, not `500`.

## Logging and failure handling

The request log is JSON lines with a correlation ID per call, and `log_level`
filters it between DEBUG, INFO, WARN, and ERROR. The file copy rotates at
5 MB and keeps three backups. Tools that call outside Burp get a circuit
breaker: five failures in 60 seconds open it for 30 seconds, then one probe
request decides whether to close it again.

## Client setup

Any MCP client that supports remote HTTP servers works. Hermes:

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

opencode:

```json
{
  "mcp": {
    "servers": {
      "burp": {
        "type": "remote",
        "url": "http://127.0.0.1:4444/"
      }
    }
  }
}
```

## Development

```bash
mvn clean compile
mvn test                      # 119 unit and mocked-transport tests
mvn clean package -DskipTests
```

There is no Maven wrapper checked in. Tests use JUnit 5 and AssertJ, and the
integration suite starts a real NanoHTTPD on a random port.

`LiveBurpIT` is excluded from normal runs and needs Burp running with the
extension loaded:

```bash
BURP_MCP_TEST_BASE_URL=http://127.0.0.1:4444 mvn test -Dtest=LiveBurpIT
```

Optional environment: `BURP_MCP_TEST_AUTH_TOKEN` for a Bearer token,
`BURP_MCP_TEST_ALLOW_SCANS=1` to include the crawl test on Professional.
The suite starts its own loopback HTTP and TLS targets, uses one unique scope
subtree and cleans it up afterwards, backs off on `429`, and skips quietly
when Burp is not reachable.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Extension will not load | Corrupt preference or bad port | Check Burp's output for `Invalid config`, fix in Settings |
| Port busy | Another Burp instance holds 4444 | This instance keeps serving on its Unix socket; close the other Burp and press Restart, or change `mcp_port` |
| `-32001` on scanner | Community edition | Expected; scanner and Collaborator need Professional |
| `401` on every call | `auth_enabled` with the wrong token | Copy the token from Settings, check `BURP_MCP_AUTH_TOKEN` |
| `503` auth misconfigured | `auth_enabled=true` with an empty token | Set a token, then Start or Restart |
| `429` | Rate limit hit | Wait for `Retry-After`, raise the limit, or set `0` to disable |
| Empty proxy history | No traffic yet | Browse through Burp first, then retry |
| TLS errors with self-signed | Missing SAN in older certs | Use a `custom` PKCS12 or trust the certificate explicitly |

## Security

The server binds to loopback by default. Opening `bind_address` to `0.0.0.0`
without auth or TLS exposes Burp to your network, so do not do that on
untrusted networks. `http_send_request` and `http_send_requests` can reach
anything Burp can, including cloud metadata addresses. Use Read-Only mode
and the sensitivity switch when the agent does not need to send traffic.

## License

MIT
