package burp.mcp.server;

import fi.iki.elonen.NanoHTTPD;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded AsyncRunner honoring the {@code thread_pool_size} and
 * {@code max_queue_size} preferences.
 *
 * The default NanoHTTPD runner spawns an unbounded thread per connection.
 * This runner caps both threads and queued connections; when saturated it
 * closes the excess connection instead of growing without bound.
 */
public class BoundedAsyncRunner implements NanoHTTPD.AsyncRunner {

    private final ThreadPoolExecutor executor;
    private final LongAdder rejected = new LongAdder();

    public BoundedAsyncRunner(int threads, int queueSize) {
        int pool = Math.max(1, threads);
        int queue = Math.max(1, queueSize);
        this.executor = new ThreadPoolExecutor(
                pool, pool,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue),
                r -> {
                    Thread t = new Thread(r, "burp-mcp-http");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void exec(NanoHTTPD.ClientHandler clientHandler) {
        try {
            executor.execute(clientHandler);
        } catch (RejectedExecutionException e) {
            rejected.increment();
            try {
                clientHandler.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void closed(NanoHTTPD.ClientHandler clientHandler) {
        // No per-handler bookkeeping: the executor owns lifecycle.
    }

    @Override
    public void closeAll() {
        executor.shutdownNow();
    }

    /** Connections dropped because pool + queue were full. */
    public long getRejectedCount() {
        return rejected.sum();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
