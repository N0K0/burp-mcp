package burp.mcp.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Circuit breaker for external-calling tools.
 * Tracks failures in a sliding 60s window. After 5 consecutive failures,
 * opens the circuit for 30s, then allows one probe request.
 */
public class CircuitBreaker {

    private final String toolName;
    private volatile State state = State.CLOSED;
    private volatile int consecutiveFailures = 0;
    private volatile long lastFailureTime = 0;
    private volatile long openedAt = 0;
    private static final int FAILURE_THRESHOLD = 5;
    private static final long WINDOW_MS = 60_000;
    private static final long OPEN_TIMEOUT_MS = 30_000;

    private final LongAdder totalFailures = new LongAdder();
    private final LongAdder totalTrips = new LongAdder();

    public CircuitBreaker(String toolName) {
        this.toolName = toolName;
    }

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /**
     * Called before executing the tool. Returns null if allowed,
     * or an error message if the circuit is open.
     */
    public synchronized String allowRequest() {
        long now = System.currentTimeMillis();

        if (state == State.OPEN) {
            if (now - openedAt >= OPEN_TIMEOUT_MS) {
                state = State.HALF_OPEN;
            } else {
                long remain = (OPEN_TIMEOUT_MS - (now - openedAt)) / 1000;
                return "Tool temporarily unavailable (circuit breaker open, retry in " + remain + "s)";
            }
        }
        return null; // Allowed
    }

    /**
     * Called when a tool call succeeds.
     */
    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            consecutiveFailures = 0;
        }
        // In CLOSED state, success resets the failure window
        if (state == State.CLOSED && consecutiveFailures > 0) {
            long now = System.currentTimeMillis();
            if (now - lastFailureTime > WINDOW_MS) {
                consecutiveFailures = 0;
            }
        }
    }

    /**
     * Called when a tool call fails.
     */
    public synchronized void recordFailure() {
        long now = System.currentTimeMillis();
        totalFailures.increment();

        if (state == State.HALF_OPEN) {
            // Probe failed — re-open
            state = State.OPEN;
            openedAt = now;
            totalTrips.increment();
            return;
        }

        if (state == State.CLOSED) {
            if (now - lastFailureTime > WINDOW_MS) {
                consecutiveFailures = 0;
            }
            consecutiveFailures++;
            lastFailureTime = now;
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                state = State.OPEN;
                openedAt = now;
                totalTrips.increment();
            }
        }
    }

    public synchronized void reset() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        lastFailureTime = 0;
        openedAt = 0;
    }

    public State getState() { return state; }
    public String getToolName() { return toolName; }
    public long getTotalFailures() { return totalFailures.sum(); }
    public long getTotalTrips() { return totalTrips.sum(); }
}
