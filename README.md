# Burp MCP Server

Burp MCP Server is a Burp Suite extension that turns your running Burp into a
set of MCP tools. An AI coding agent can then send HTTP requests, read what
Burp has already captured, drive the scanner, and hand work to Repeater,
Intruder, Comparer, or Organizer, all inside the project you are working in.

It runs in Burp's JVM and speaks JSON-RPC 2.0 over HTTP. Clients connect
either to a TCP port on loopback or to a Unix socket next to the open project
file.

| | |
|---|---|
| Runs inside | Burp Suite Community or Professional |
| Exposes | 53 MCP tools across HTTP, proxy, sitemap, scope, scanner, Collaborator, Organizer, and config |
| Transports | TCP on `127.0.0.1:4444` and a per-project Unix socket |
| Needs Professional | Scanner, crawler, and Collaborator tools |
| Clients | Any MCP client that can POST JSON-RPC to an HTTP URL |

## Getting the extension

Download `burp-mcp-server.jar` from the
[latest release](https://github.com/N0K0/burp-mcp/releases), or build from
source for the newest work on `main`:

```bash
git clone https://github.com/N0K0/burp-mcp.git
cd burp-mcp
mvn clean package -DskipTests
```

The build needs JDK 17 or newer and Maven 3.9 or newer. It writes
`target/burp-mcp-server.jar`.

Load the jar in Burp under Extensions -> Installed -> Add -> Java. Burp does
not reload Java extensions on its own, so remove the old entry and add the
jar again after a rebuild.

On WSL, if `/mnt/c` exists, the build also copies the jar to
`C:\Users\nikolas\Downloads\burp-mcp-server.jar`. Everywhere else that step
is skipped and never fails the build.

## Checking it works

With Burp running and the extension loaded:

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
```

## How it fits together

```
MCP client (Claude, Cursor, Hermes, opencode, ...)
    |  HTTP POST, JSON-RPC 2.0
    v
Burp MCP Server extension, inside Burp's JVM
    |  TCP :4444 and/or <project>.sock
    |  Montoya API
    v
Burp Suite, with your project, scope, history, and license
```

Because the extension runs inside Burp, an agent works on the project you
have open: the same proxy history, the same site map, the same scope, the
same cookies, the same Professional license. There is no second proxy to
start and no traffic to export first.

The extension adds a "Burp MCP" tab with five screens. Status shows the
listeners with Start/Stop/Restart, a metrics dashboard, and the request log.
Settings holds every preference with validation and JSON export/import. Tool
Tester runs a single tool by hand. Permissions decides what agents may call.
Messages browses proxy, sitemap, and WebSocket traffic with syntax
highlighting.

## Transports and multiple projects

TCP listens on `mcp_port`, 4444 by default. One port, one Burp instance, and
a client config that never changes.

The Unix socket is on by default and lives next to the open project file, so
`~/burp-projects/exness-20261004.burp` gets
`~/burp-projects/exness-20261004.sock`. Each project gets its own socket, so
two Burp instances do not have to share a port. Temporary projects, project
files the extension cannot locate, and paths over the OS limit fall back to
`<tmp>/burp-mcp-<hash>.sock`. Override the path with the `socket_path`
setting or `BURP_MCP_SOCKET_PATH`, or turn the socket off with
`socket_enabled=false`.

If something already holds 4444, that instance keeps serving on its socket
and the Status tab shows `Failed: port 4444 already in use (another Burp
instance?)`. Close the instance holding the port and press Restart.

Both transports speak the same HTTP JSON-RPC, with the same auth,
permissions, rate limits, and `/health`. The socket handles one request per
connection (`Connection: close`), its file is owner-only, and it is removed
on Stop, Restart, or unload. A stale file from a crash is replaced on the
next start. Python clients need an AF_UNIX connector, for example
`http.client.HTTPConnection` over `socket.AF_UNIX`. `curl --unix-socket`
works as is.

## The tools

53 tools in total. Aliases work too: `proxy_list` maps to
`proxy_history_list`, `send_request` to `http_send_request`, and so on.
Permissions and metrics use the canonical name, so disabling a tool also
disables its aliases.

### Send and shape HTTP

| Tool | What it does |
|------|--------------|
| `http_send_request` | Send a URL as a GET, or a complete raw HTTP message. Options for redirects, timeout, and HTTP version. |
| `http_send_requests` | Send several raw requests in parallel. |
| `http_build_request` | Build a request from method, URL, headers, and body. Returns the raw message and a `request_id`. |
| `http_modify_request` | Add or remove headers, replace the body, or change the method. |
| `http_parse_request` | Split a raw request into method, URL, path, query, headers, body, and HTTP version. |
| `http_parse_response` | Split a raw response into status, headers, body, MIME type, and cookies. |
| `http_store_request` | Keep a raw request in the extension's cache. |
| `http_get_request` | Fetch a stored request by its `request_id`. |

### Read what Burp has seen

| Tool | What it does |
|------|--------------|
| `proxy_history_list` | List captured HTTP requests, filtered by URL prefix, method, or status code. |
| `proxy_history_get` | Fetch one proxy history entry by ID. |
| `proxy_websocket_history_list` | List WebSocket messages captured by the proxy. |
| `websocket_history_get` | Fetch one WebSocket message by index. |
| `sitemap_list` | List site map entries, optionally in-scope only. |
| `sitemap_list_filtered` | Filter the site map by URL prefix, host, method, or status. |
| `sitemap_get` | Fetch a site map entry by URL, exact or prefix match. |
| `sitemap_search` | Regex search across request and response bodies. |
| `organizer_list` | List items stashed in Burp's Organizer. |
| `cookie_list` | Read Burp's cookie jar. |
| `cookie_set` | Add or update a cookie. |

### Hand work to Burp's own tools

| Tool | What it does |
|------|--------------|
| `http_send_to_repeater` | Open a raw request in Repeater, optionally naming the tab. |
| `http_send_to_intruder` | Send a request to Intruder, keeping `~` payload markers. |
| `http_send_to_comparer` | Send two or more strings to Comparer. |
| `http_send_to_decoder` | Send data to Decoder. |
| `http_send_to_organizer` | Stash a request in Organizer for follow-up. |
| `logger_add` | Send a request and add the request/response pair to the site map. Burp has no Logger write API, so this uses `sitemap.add()`. |

### Scanner and crawler (Professional)

| Tool | What it does |
|------|--------------|
| `scanner_start_audit` | Active or passive audit of one or more seed URLs, with optional headers such as cookies. Burp stamps its own User-Agent on the traffic it generates; a seed User-Agent only feeds the UA insertion point checks. |
| `scanner_issues_list` | List every issue in the site map. |
| `scanner_issues_list_filtered` | Filter issues by URL prefix or severity. |
| `scanner_get_issue` | Full details for one issue, by URL and optional name. |
| `scanner_generate_report` | Write an HTML or XML report and return the path. |
| `scanner_bcheck_import` | Load a BCheck from text or a file. |
| `scanner_start_crawl` | Start a crawl from seed URLs. Returns a `crawl_id`. |
| `scanner_crawl_status` | Poll a crawl's request and error counts. Current Burp builds leave the crawl status message unimplemented, so counts are the honest progress signal. |
| `scanner_crawl_stop` | Stop and delete a crawl. |
| `task_engine_status` | Check whether Spider and Scanner are running or paused. |
| `task_engine_set` | Pause or resume Spider and Scanner, for example before manual replay work. |

### Out-of-band testing (Professional)

| Tool | What it does |
|------|--------------|
| `collaborator_generate_payload` | Generate a unique Collaborator payload, optionally with up to 16 alphanumeric characters of custom data. |
| `collaborator_interactions` | Poll interactions for a payload's secret key, filtered by type (DNS, HTTP, SMTP, and so on). |

### Scope, project, and settings

| Tool | What it does |
|------|--------------|
| `scope_check` | Is this URL in Burp's target scope? |
| `scope_list` | Show the include rules plus in-scope URLs seen in sitemap traffic. |
| `scope_set` | Add or remove include/exclude rules, one URL or many. |
| `burp_info` | Burp version and edition, project name and ID, and the live socket path. |
| `project_create` | Returns the command to launch a new `.burp` project file. Projects cannot be created through the Montoya API. |
| `config_get` | Export Burp configuration as JSON, project or user scope. |
| `config_set` | Import Burp configuration from JSON. |
| `config_list_preferences` | Dump the extension's own settings. |
| `proxy_intercept_status` | Check whether proxy interception is on. |
| `proxy_toggle_intercept` | Flip proxy interception on or off. |

### Encode, compare, search

| Tool | What it does |
|------|--------------|
| `decoder_decode` | Decode base64, URL, or hex. |
| `decoder_encode` | Encode to base64, URL, or hex. |
| `http_diff_responses` | Compare two responses and report what differs and what stays the same. |
| `http_keyword_search` | Check which keywords appear in all responses and which appear in only some. |

### Server health

`burp_metrics` returns uptime, request counts, latency percentiles, per-tool
call counts, cache hit rate, and active connections. The same numbers are
available in Prometheus format from `GET /health?format=prometheus`.

## A worked example

Agents pick their own tools, so this is what a session tends to look like
rather than a fixed script. Say you ask:

> Map everything under `https://shop.example.test` and look at the checkout
> flow.

The agent usually ends up alternating between reading Burp's state and
sending requests:

1. `scope_check` on the seed to confirm it is in scope.
2. `sitemap_list` and `sitemap_search` for what Burp already knows, plus
   `proxy_history_list` for captured traffic.
3. `http_send_request` to cover gaps and exercise the checkout flow.
4. `http_send_to_repeater` or `http_send_to_organizer` for anything worth a
   human look.
5. On Professional: `scanner_start_audit` on the interesting URLs, then
   `scanner_issues_list_filtered` at severity HIGH, then `scanner_get_issue`
   for details, and `scanner_generate_report` at the end.
6. For blind bugs: `collaborator_generate_payload`, put the payload in the
   request, and later `collaborator_interactions`.

The extension does not test anything by itself. The agent decides what to
call, and every call is subject to the permission rules below.

## Connecting a client

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

For a custom client, the surface is small. POST JSON-RPC to `/`, with the
usual MCP methods:

```
initialize
tools/list
tools/call     {"name": "burp_info", "arguments": {}}
```

`GET /health` sits outside JSON-RPC and returns `{"status":"ok"}` or
`{"status":"error"}`.

## Permissions and safety

The Permissions tab has three modes:

- Read-Write: everything is allowed. This is the default.
- Read-Only: queries only. That covers `burp_info`, `project_create`,
  `burp_metrics`, `sitemap_*`, `proxy_history_*`, `proxy_intercept_status`,
  `proxy_websocket_history_list`, `websocket_history_get`, `scope_check`,
  `scope_list`, `organizer_list`, `task_engine_status`,
  `scanner_crawl_status`, `cookie_list`, `decoder_*`, `http_parse_*`,
  `http_get_request`, `http_diff_responses`, `http_keyword_search`,
  `config_get`, `config_list_preferences`, the scanner issue reads, and
  `collaborator_interactions`.
- Custom: enable tools one by one. Deny wins, and anything you do not
  explicitly enable stays allowed, so read the list before relying on it.

The sensitivity switch blocks `scope_set`, `config_set`,
`scanner_start_audit`, `scanner_start_crawl`, `scanner_crawl_stop`,
`scanner_bcheck_import`, `task_engine_set`, and `proxy_toggle_intercept` in
every mode. Turn it off when you actually need those.

Two things worth knowing before you point an agent at a live target:

- The tools run with your Burp license and your network position.
  `http_send_request` and `http_send_requests` can reach anything Burp can
  reach, in or out of scope. Burp's scope is a useful signal, not a sandbox.
- Scanner and crawler tools create real traffic against the target. Read-Only
  mode and the sensitivity switch exist so passive work stays passive.

## Configuration

Burp stores these in extension preferences, so they survive restarts. The
Settings tab exposes all of them, with JSON export/import. Exports leave out
`auth_token` and `tls_keystore_password`, so copy those by hand when you move
machines.

| Key | Default | What it does |
|-----|---------|--------------|
| `mcp_port` | 4444 | TCP listen port (1-65535) |
| `bind_address` | 127.0.0.1 | Interface to bind |
| `thread_pool_size` | 10 | Worker threads (1-50) |
| `max_queue_size` | 100 | Queue depth (1-1000) |
| `max_response_body_bytes` | 100000 | Truncates tool output (1000-100M) |
| `max_sitemap_entries` | 500 | Site map list cap (>0) |
| `request_timeout_ms` | 30000 | HTTP timeout (1000-300K) |
| `cache_ttl_seconds` | 300 | Cache TTL; 0 turns off timer cleanup (0-86400) |
| `rate_limit_per_minute` | 100 | Per-IP limit; 0 disables (0-10000) |
| `max_connections_per_ip` | 10 | Concurrent connections per IP; extras get 503 (1-100) |
| `socket_enabled` | true | Serve on a Unix socket alongside TCP |
| `socket_path` | (empty) | Explicit socket path; empty means next to the `.burp` project file |
| `log_level` | INFO | DEBUG, INFO, WARN, or ERROR |
| `logging_file_path` | (empty) | JSON-lines log path; empty means `~/burp-mcp-error.log` |
| `include_request_body` | true | Include request bodies in tool output |
| `include_response_body` | true | Include response bodies in tool output |
| `metrics_enabled` | true | Collect metrics (applied on Start/Restart) |
| `cache_enabled` | true | Use the request cache |
| `audit_log_enabled` | false | Extra audit logging |
| `auth_enabled` | false | Require a Bearer token |
| `auth_token` | (none) | Token value; never exported |
| `tls_enabled` | false | Serve HTTPS |
| `tls_mode` | self_signed | `self_signed` or `custom` |
| `tls_keystore_path` | (none) | PKCS12 path for `custom` mode |
| `tls_keystore_password` | burpmcp | Keystore password |

Environment variables win over stored preferences, which helps in CI or
containers:

```bash
BURP_MCP_PORT=4444
BURP_MCP_BIND_ADDRESS=127.0.0.1
BURP_MCP_AUTH_TOKEN=secret
BURP_MCP_LOG_LEVEL=INFO
BURP_MCP_SOCKET_PATH=/tmp/burp-proj-a.sock
```

Bad values are logged at startup as `Invalid config: ...`. If
`auth_enabled=true` and the token is empty, the server refuses to serve
rather than letting everyone in.

## Health, logs, and limits

Auth is a single Bearer token. Set `auth_enabled=true` and put the token in a
header:

```yaml
mcp_servers:
  burp:
    url: https://127.0.0.1:4444
    headers:
      Authorization: Bearer <token>
```

TLS is off by default. Set `tls_enabled=true`, then pick `self_signed` for
local work or `custom` with a PKCS12 keystore. `custom` without a keystore
path fails at startup instead of silently falling back to HTTP.

Health and metrics:

```bash
curl http://127.0.0.1:4444/health
curl http://127.0.0.1:4444/health?format=prometheus
```

Health stays unauthenticated so monitoring can poll it.

Limits: inbound bodies cap at 10 MB and tool output truncates at
`max_response_body_bytes`. Rate limiting is per socket IP, and
`X-Forwarded-For` is ignored because clients can spoof it. The connection
cap returns 503 with `-32007` when it is saturated. Tools that call outside
Burp get a circuit breaker: five failures in 60 seconds open it for 30
seconds, then one probe request decides whether to close it again.

Logs are JSON lines with a correlation ID per call, filtered by `log_level`.
The file copy rotates at 5 MB and keeps three backups.

Errors come back as JSON-RPC codes:

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

The Status tab has Start, Stop, and Restart for the listeners. Restart
re-reads saved settings, so changes to the port, bind address, TLS, socket,
and pool sizes apply without reloading the extension.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Extension will not load | Corrupt preference or bad port | Check Burp's output for `Invalid config` and fix it in Settings |
| Agent cannot connect | Wrong port, or another program owns it | Check `curl http://127.0.0.1:4444/health`; if that fails, the instance is on its socket only and the Status tab says why |
| Port busy | Another Burp instance holds 4444 | This instance keeps serving on its Unix socket; close the other instance and press Restart, or change `mcp_port` |
| Tools look stale after a rebuild | Burp kept the old jar loaded | Remove and re-add the extension |
| `-32001` on scanner | Community edition | Expected; scanner and Collaborator need Professional |
| `401` on every call | `auth_enabled` with the wrong token | Copy the token from Settings, check `BURP_MCP_AUTH_TOKEN` |
| `503` auth misconfigured | `auth_enabled=true` with an empty token | Set a token, then Start or Restart |
| `429` | Rate limit hit | Wait for `Retry-After`, raise the limit, or set it to 0 |
| Empty proxy history | No traffic yet | Browse through Burp first, then retry |
| TLS errors with self-signed | Missing SAN in older certificates | Use a `custom` PKCS12 or trust the certificate explicitly |

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

Optional environment: `BURP_MCP_TEST_AUTH_TOKEN` for a Bearer token, and
`BURP_MCP_TEST_ALLOW_SCANS=1` to include the crawl test on Professional. The
suite starts its own loopback HTTP and TLS targets, uses one unique scope
subtree and cleans it up, backs off on `429`, and skips quietly when Burp is
not reachable.

The codebase conventions, project layout, and the checklist for adding a tool
live in [AGENTS.md](AGENTS.md).

## Security

The server binds to loopback by default. Opening `bind_address` to `0.0.0.0`
without auth or TLS exposes Burp to your network, so do not do that on
untrusted networks. The tools act with your Burp session's access, so treat
an MCP client like any other operator at your keyboard. Use Read-Only mode
and the sensitivity switch when an agent only needs to look.

## License

MIT
