package burp.mcp.server;

import burp.mcp.util.ErrorLogger;
import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Minimal HTTP/1.1 listener on a Unix domain socket, exposing the same
 * JSON-RPC surface as the TCP server.
 *
 * <p>NanoHTTPD is TCP-only, so this runs a small parallel listener: each
 * connection is parsed into a fake {@link NanoHTTPD.IHTTPSession} and passed
 * straight into {@link McPServer#serve}, reusing auth, rate limiting,
 * permissions, circuit breakers, metrics, and connection caps unchanged.
 *
 * <p>One request per connection ({@code Connection: close}); the socket file
 * is owner-only where the platform allows, and is removed on {@link #stop}.
 * The default path is per-project (see {@code McpConfig.resolveSocketPath}),
 * so multiple Burp instances never clash — unlike fixed TCP ports.
 */
public class UnixSocketServer {

    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024; // mirrors McPServer limit
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final McPServer server;
    private final Path socketPath;
    private final int threadPoolSize;
    private final Consumer<String> infoLog;

    private volatile ServerSocketChannel channel;
    private volatile ExecutorService executor;
    private volatile Thread acceptThread;
    private volatile boolean running;

    public UnixSocketServer(McPServer server, Path socketPath, int threadPoolSize) {
        this(server, socketPath, threadPoolSize, msg -> {});
    }

    public UnixSocketServer(McPServer server, Path socketPath, int threadPoolSize,
                            Consumer<String> infoLog) {
        this.server = server;
        this.socketPath = socketPath.toAbsolutePath();
        this.threadPoolSize = Math.max(1, threadPoolSize);
        this.infoLog = infoLog != null ? infoLog : msg -> {};
    }

    public Path getSocketPath() {
        return socketPath;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        Path parent = socketPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Replace a stale socket file from an unclean shutdown.
        Files.deleteIfExists(socketPath);

        channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        channel.bind(UnixDomainSocketAddress.of(socketPath));

        // Owner-only access where the platform supports POSIX perms.
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(socketPath, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX platform (e.g. Windows) — socket ACLs stay default.
        }

        executor = Executors.newFixedThreadPool(threadPoolSize, r -> {
            Thread t = new Thread(r, "burp-mcp-uds");
            t.setDaemon(true);
            return t;
        });
        running = true;
        acceptThread = new Thread(this::acceptLoop, "burp-mcp-uds-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        infoLog.accept("[burp-mcp] Unix socket listening on " + socketPath);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
        }
        if (executor != null) executor.shutdownNow();
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
        }
        infoLog.accept("[burp-mcp] Unix socket stopped: " + socketPath);
    }

    // ── Accept loop ──────────────────────────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel conn = channel.accept();
                ExecutorService ex = executor;
                if (ex != null && running) {
                    final SocketChannel c = conn;
                    ex.submit(() -> handle(c));
                } else {
                    closeQuietly(conn);
                }
            } catch (ClosedChannelException e) {
                break; // stopped
            } catch (IOException e) {
                if (running) {
                    ErrorLogger.log("UnixSocketServer/accept", e);
                }
            }
        }
    }

    // ── Per-connection handling ──────────────────────────────────

    private void handle(SocketChannel conn) {
        try (SocketChannel c = conn) {
            c.configureBlocking(true);
            Request req = readRequest(c);
            NanoHTTPD.Response resp;
            try {
                resp = server.serve(new SocketSession(req));
            } catch (Throwable t) {
                ErrorLogger.log("UnixSocketServer/serve", t);
                resp = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json",
                        "{\"error\":\"Internal server error\"}");
            }
            try {
                writeResponse(c, resp);
            } finally {
                try {
                    resp.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            ErrorLogger.log("UnixSocketServer/handle", e);
        }
    }

    // ── Minimal HTTP/1.1 parsing ─────────────────────────────────

    private static final class Request {
        String method;
        String path;
        String rawQuery = "";
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];
    }

    private void writeInterimContinue(SocketChannel c) throws IOException {
        c.write(ByteBuffer.wrap("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
    }

    private Request readRequest(SocketChannel c) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        ByteBuffer one = ByteBuffer.allocate(1);
        int headerEnd = -1;
        while (head.size() < MAX_HEADER_BYTES) {
            one.clear();
            int n = c.read(one);
            if (n == -1) {
                throw new IOException("EOF before request headers complete");
            }
            head.write(one.get(0));
            byte[] buf = head.toByteArray();
            if (buf.length >= 4
                    && buf[buf.length - 4] == '\r' && buf[buf.length - 3] == '\n'
                    && buf[buf.length - 2] == '\r' && buf[buf.length - 1] == '\n') {
                headerEnd = buf.length;
                break;
            }
        }
        if (headerEnd == -1) {
            throw new IOException("Request headers exceed " + MAX_HEADER_BYTES + " bytes");
        }
        String headStr = new String(head.toByteArray(), 0, headerEnd, StandardCharsets.US_ASCII);
        String[] lines = headStr.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("Empty request");
        }
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            throw new IOException("Malformed request line: " + lines[0]);
        }

        Request req = new Request();
        req.method = requestLine[0].trim().toUpperCase(java.util.Locale.ROOT);
        String target = requestLine[1].trim();
        int qi = target.indexOf('?');
        if (qi >= 0) {
            req.path = target.substring(0, qi);
            req.rawQuery = target.substring(qi + 1);
        } else {
            req.path = target;
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int ci = line.indexOf(':');
            if (ci <= 0) {
                continue;
            }
            // NanoHTTPD lowercases header names — match that behavior.
            String name = line.substring(0, ci).trim().toLowerCase(java.util.Locale.ROOT);
            String value = line.substring(ci + 1).trim();
            req.headers.putIfAbsent(name, value);
        }

        // Chunked bodies are not supported on this listener.
        if ("chunked".equalsIgnoreCase(req.headers.getOrDefault("transfer-encoding", ""))) {
            throw new IOException("Chunked transfer-encoding not supported; send Content-Length");
        }

        String expect = req.headers.getOrDefault("expect", "");
        if (expect.toLowerCase(java.util.Locale.ROOT).contains("100-continue")) {
            writeInterimContinue(c);
        }

        int len = 0;
        String cl = req.headers.get("content-length");
        if (cl != null && !cl.isEmpty()) {
            try {
                len = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length: " + cl);
            }
            if (len < 0 || len > MAX_BODY_BYTES) {
                throw new IOException("Bad body size: " + len);
            }
        }
        byte[] body = new byte[len];
        int off = 0;
        ByteBuffer buf = ByteBuffer.allocate(Math.min(8192, Math.max(1, len)));
        while (off < len) {
            buf.clear();
            buf.limit(Math.min(buf.capacity(), len - off));
            int n = c.read(buf);
            if (n == -1) {
                throw new IOException("Truncated body: expected=" + len + " received=" + off);
            }
            System.arraycopy(buf.array(), 0, body, off, n);
            off += n;
        }
        req.body = body;
        return req;
    }

    private void writeResponse(SocketChannel c, NanoHTTPD.Response resp) throws IOException {
        NanoHTTPD.Response.IStatus st = resp.getStatus();
        String mime = resp.getMimeType() != null ? resp.getMimeType() : "application/json";
        byte[] body = readAll(resp.getData(), MAX_RESPONSE_BYTES);
        String head = "HTTP/1.1 " + st.getRequestStatus() + " " + st.getDescription() + "\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        c.write(ByteBuffer.wrap(head.getBytes(StandardCharsets.US_ASCII)));
        if (body.length > 0) {
            c.write(ByteBuffer.wrap(body));
        }
    }

    private static byte[] readAll(InputStream in, int cap) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > cap) {
                throw new IOException("Response exceeds " + cap + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(SocketChannel c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }

    // ── Query parsing (mirrors NanoHTTPD decoding: '+' → space) ──

    static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            out.computeIfAbsent(decode(k), x -> new ArrayList<>()).add(decode(v));
        }
        return out;
    }

    private static String decode(String s) {
        try {
            // URLDecoder maps '+' → space and '%2B' → '+', matching query semantics.
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static NanoHTTPD.Method toMethod(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "GET" -> NanoHTTPD.Method.GET;
            case "PUT" -> NanoHTTPD.Method.PUT;
            case "POST" -> NanoHTTPD.Method.POST;
            case "DELETE" -> NanoHTTPD.Method.DELETE;
            case "HEAD" -> NanoHTTPD.Method.HEAD;
            case "OPTIONS" -> NanoHTTPD.Method.OPTIONS;
            default -> null;
        };
    }

    // ── Fake session: feeds parsed socket data into McPServer.serve ──

    private final class SocketSession implements NanoHTTPD.IHTTPSession {
        private final Request req;
        private final Map<String, List<String>> queryParams;

        SocketSession(Request req) {
            this.req = req;
            this.queryParams = parseQuery(req.rawQuery);
        }

        @Override
        public void execute() {
            // Body already consumed during parsing — nothing to do.
        }

        @Override
        public NanoHTTPD.CookieHandler getCookies() {
            return server.new CookieHandler(req.headers);
        }

        @Override
        public Map<String, String> getHeaders() {
            return req.headers;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(req.body);
        }

        @Override
        public NanoHTTPD.Method getMethod() {
            return toMethod(req.method);
        }

        @Override
        public Map<String, String> getParms() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : queryParams.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    out.put(e.getKey(), e.getValue().get(0));
                }
            }
            return out;
        }

        @Override
        public Map<String, List<String>> getParameters() {
            return queryParams;
        }

        @Override
        public String getQueryParameterString() {
            return req.rawQuery;
        }

        @Override
        public String getUri() {
            return req.path;
        }

        @Override
        public void parseBody(Map<String, String> files) {
            // Body already consumed during parsing — nothing to do.
        }

        @Override
        public String getRemoteIpAddress() {
            return "unix-socket";
        }

        @Override
        public String getRemoteHostName() {
            return "localhost";
        }
    }
}
