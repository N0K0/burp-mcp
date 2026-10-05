package burp.mcp.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Coordinates operator approvals for prompt-gated tool calls and out-of-scope
 * targets.
 *
 * <p>Flow: a tool call that needs approval creates (or reuses) a
 * {@link PendingApproval} and {@link #await waits} briefly for a UI decision.
 * If the operator answers in time the decision is applied immediately; if not,
 * the call returns a pending token and the agent retries the same call while
 * the operator can still answer from the Burp UI. Decisions are cached on the
 * pending request until it is consumed or its TTL expires, so nothing executes
 * after the client has stopped waiting.
 *
 * <p>Thread-safe. UI callbacks ({@link Listener}) fire on whichever thread
 * triggered the event and must be marshalled to the EDT by the listener.
 */
public class ApprovalManager {

    /** Decision for a permission-gated tool. */
    public enum PermissionDecision {
        ALLOW_ONCE,
        ALLOW_SESSION,
        DENY
    }

    /** Decision for an out-of-scope target. */
    public enum ScopeDecision {
        ADD_TO_SCOPE,
        ALLOW_ONCE,
        ALLOW_SESSION,
        DENY
    }

    /** One operator decision covering the permission and/or scope requirement. */
    public static final class Decision {
        private final PermissionDecision permission;
        private final ScopeDecision scope;
        private final String reason;

        public Decision(PermissionDecision permission, ScopeDecision scope, String reason) {
            this.permission = permission;
            this.scope = scope;
            this.reason = reason;
        }

        public PermissionDecision getPermission() {
            return permission;
        }

        public ScopeDecision getScope() {
            return scope;
        }

        public String getReason() {
            return reason;
        }

        public boolean isDenied() {
            return permission == PermissionDecision.DENY || scope == ScopeDecision.DENY;
        }

        public String reasonOrDefault(String fallback) {
            return reason == null || reason.isBlank() ? fallback : reason;
        }
    }

    /** Everything a pending approval needs to describe itself. */
    public static final class Context {
        private final String clientIp;
        private final String toolName;
        private final String fingerprint;
        private final String argsSummary;
        private final List<String> targets;
        private final List<String> outOfScopeTargets;
        private final List<String> outOfScopeOrigins;
        private final boolean permissionPrompt;
        private final boolean scopePrompt;
        private final String permissionReason;
        private final String scopeReason;

        public Context(String clientIp, String toolName, String fingerprint, String argsSummary,
                       List<String> targets, List<String> outOfScopeTargets,
                       List<String> outOfScopeOrigins,
                       boolean permissionPrompt, boolean scopePrompt,
                       String permissionReason, String scopeReason) {
            this.clientIp = clientIp;
            this.toolName = toolName;
            this.fingerprint = fingerprint;
            this.argsSummary = argsSummary;
            this.targets = List.copyOf(targets != null ? targets : List.of());
            this.outOfScopeTargets = List.copyOf(outOfScopeTargets != null ? outOfScopeTargets : List.of());
            this.outOfScopeOrigins = List.copyOf(outOfScopeOrigins != null ? outOfScopeOrigins : List.of());
            this.permissionPrompt = permissionPrompt;
            this.scopePrompt = scopePrompt;
            this.permissionReason = permissionReason;
            this.scopeReason = scopeReason;
        }

        public String getClientIp() { return clientIp; }
        public String getToolName() { return toolName; }
        public String getFingerprint() { return fingerprint; }
        public String getArgsSummary() { return argsSummary; }
        public List<String> getTargets() { return targets; }
        public List<String> getOutOfScopeTargets() { return outOfScopeTargets; }
        public List<String> getOutOfScopeOrigins() { return outOfScopeOrigins; }
        public boolean isPermissionPrompt() { return permissionPrompt; }
        public boolean isScopePrompt() { return scopePrompt; }
        public String getPermissionReason() { return permissionReason; }
        public String getScopeReason() { return scopeReason; }
    }

    /** A pending (or already resolved but not yet consumed) approval request. */
    public static final class PendingApproval {
        private final String id;
        private final String clientIp;
        private final String toolName;
        private final String fingerprint;
        private final String argsSummary;
        private final List<String> targets;
        private final List<String> outOfScopeTargets;
        private final List<String> outOfScopeOrigins;
        private final boolean permissionPrompt;
        private final boolean scopePrompt;
        private final String permissionReason;
        private final String scopeReason;
        private final long createdAt;
        private final long expiresAt;

        private volatile Decision decision;
        private volatile boolean cancelled;
        private final CompletableFuture<Decision> future = new CompletableFuture<>();
        private final AtomicInteger waiters = new AtomicInteger();

        private PendingApproval(Context ctx, long ttlMs) {
            this.id = UUID.randomUUID().toString();
            this.clientIp = ctx.getClientIp();
            this.toolName = ctx.getToolName();
            this.fingerprint = ctx.getFingerprint();
            this.argsSummary = ctx.getArgsSummary();
            this.targets = ctx.getTargets();
            this.outOfScopeTargets = ctx.getOutOfScopeTargets();
            this.outOfScopeOrigins = ctx.getOutOfScopeOrigins();
            this.permissionPrompt = ctx.isPermissionPrompt();
            this.scopePrompt = ctx.isScopePrompt();
            this.permissionReason = ctx.getPermissionReason();
            this.scopeReason = ctx.getScopeReason();
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = this.createdAt + Math.max(1L, ttlMs);
        }

        public String getId() { return id; }
        public String getClientIp() { return clientIp; }
        public String getToolName() { return toolName; }
        public String getFingerprint() { return fingerprint; }
        public String getArgsSummary() { return argsSummary; }
        public List<String> getTargets() { return targets; }
        public List<String> getOutOfScopeTargets() { return outOfScopeTargets; }
        public List<String> getOutOfScopeOrigins() { return outOfScopeOrigins; }
        public boolean isPermissionPrompt() { return permissionPrompt; }
        public boolean isScopePrompt() { return scopePrompt; }
        public String getPermissionReason() { return permissionReason; }
        public String getScopeReason() { return scopeReason; }
        public long getCreatedAt() { return createdAt; }
        public long getExpiresAt() { return expiresAt; }
        public Decision getDecision() { return decision; }
        public boolean isCancelled() { return cancelled; }
        public boolean isResolved() { return decision != null; }
        public boolean isWaiting() { return waiters.get() > 0; }
    }

    public enum WaitKind {
        DECIDED,
        PENDING,
        EXPIRED
    }

    public static final class Outcome {
        private final WaitKind kind;
        private final PendingApproval pending;

        Outcome(WaitKind kind, PendingApproval pending) {
            this.kind = kind;
            this.pending = pending;
        }

        public WaitKind getKind() { return kind; }
        public PendingApproval getPending() { return pending; }
    }

    /** UI hook; callbacks run on the calling thread. */
    public interface Listener {
        default void onPendingAdded(PendingApproval pending) {}
        default void onPendingResolved(PendingApproval pending, Decision decision) {}
        default void onPendingExpired(PendingApproval pending) {}
        default void onGrantsChanged() {}
    }

    private final ConcurrentHashMap<String, PendingApproval> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingApproval> byKey = new ConcurrentHashMap<>();
    private final Set<String> toolGrants = ConcurrentHashMap.newKeySet();
    private final Set<String> originGrants = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Consumer<String> scopeAdder = url -> { };

    // ── Pending requests ─────────────────────────────────────────────

    /**
     * Find the pending approval for this client + fingerprint, or create it.
     * Resolved-but-unconsumed (denied) pendings are returned so retries get
     * the cached decision instead of a second prompt.
     */
    public PendingApproval findOrCreate(Context ctx, long ttlMs) {
        sweepExpired();
        String key = key(ctx.getClientIp(), ctx.getFingerprint());
        PendingApproval existing = byKey.get(key);
        if (existing != null && !existing.cancelled) {
            return existing;
        }
        PendingApproval created = new PendingApproval(ctx, ttlMs);
        PendingApproval raced = byKey.putIfAbsent(key, created);
        if (raced != null && !raced.cancelled) {
            return raced;
        }
        if (raced != null) {
            byKey.put(key, created);
        }
        byId.put(created.id, created);
        fire(l -> l.onPendingAdded(created));
        return created;
    }

    /** Look up an unconsumed pending by client + fingerprint, or null. */
    public PendingApproval find(String clientIp, String fingerprint) {
        sweepExpired();
        PendingApproval p = byKey.get(key(clientIp, fingerprint));
        return p != null && !p.cancelled ? p : null;
    }

    /**
     * Wait up to {@code waitMs} for a decision. Only one waiter per pending
     * request; extra callers are told PENDING immediately so retries cannot
     * tie up worker threads.
     */
    public Outcome await(PendingApproval pending, long waitMs) {
        if (pending == null) {
            return new Outcome(WaitKind.EXPIRED, null);
        }
        sweepExpired();
        if (pending.cancelled) return new Outcome(WaitKind.EXPIRED, pending);
        if (pending.decision != null) return new Outcome(WaitKind.DECIDED, pending);
        if (waitMs <= 0 || !pending.waiters.compareAndSet(0, 1)) {
            return new Outcome(WaitKind.PENDING, pending);
        }
        try {
            Decision d;
            try {
                d = pending.future.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return new Outcome(WaitKind.PENDING, pending);
            } catch (ExecutionException e) {
                return new Outcome(WaitKind.EXPIRED, pending);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(WaitKind.EXPIRED, pending);
            }
            return d == null ? new Outcome(WaitKind.EXPIRED, pending)
                             : new Outcome(WaitKind.DECIDED, pending);
        } finally {
            pending.waiters.decrementAndGet();
        }
    }

    // ── Decisions ────────────────────────────────────────────────────

    /**
     * Record an operator decision. Applies session grants and scope additions
     * immediately, so they take effect even if the client never retries.
     *
     * @return true when this call made the decision, false if unknown/already decided.
     */
    public boolean resolve(String id, Decision decision) {
        if (id == null || decision == null) {
            return false;
        }
        PendingApproval p = byId.get(id);
        if (p == null) {
            return false;
        }
        synchronized (p) {
            if (p.decision != null || p.cancelled) {
                return false;
            }
            p.decision = decision;
        }

        if (decision.getPermission() == PermissionDecision.ALLOW_SESSION
                && toolGrants.add(p.toolName)) {
            fire(Listener::onGrantsChanged);
        }
        if (decision.getScope() == ScopeDecision.ALLOW_SESSION) {
            boolean changed = false;
            for (String origin : p.outOfScopeOrigins) {
                changed |= originGrants.add(origin);
            }
            if (changed) {
                fire(Listener::onGrantsChanged);
            }
        }
        if (decision.getScope() == ScopeDecision.ADD_TO_SCOPE) {
            Consumer<String> adder = scopeAdder;
            for (String origin : p.outOfScopeOrigins) {
                try {
                    adder.accept(origin);
                } catch (Exception e) {
                    // A failed scope modification must not lose the decision.
                    burp.mcp.util.ErrorLogger.log("ApprovalManager/addToScope", e);
                }
            }
        }

        p.future.complete(decision);
        fire(l -> l.onPendingResolved(p, decision));
        return true;
    }

    /** Cancel a pending request (server stop, UI cancel, expiry). */
    public boolean cancel(String id) {
        PendingApproval p = id == null ? null : byId.get(id);
        return p != null && cancelInternal(p);
    }

    /** Remove a pending request after its allow decision has been consumed. */
    public void consume(PendingApproval pending) {
        if (pending != null) {
            remove(pending);
        }
    }

    private boolean cancelInternal(PendingApproval p) {
        synchronized (p) {
            if (p.cancelled || p.decision != null) {
                return false;
            }
            p.cancelled = true;
        }
        p.future.complete(null);
        remove(p);
        fire(l -> l.onPendingExpired(p));
        return true;
    }

    /** Expire unresolved pendings past their TTL and prune resolved ones. */
    public void sweepExpired() {
        long now = System.currentTimeMillis();
        for (PendingApproval p : byId.values()) {
            if (p.cancelled) {
                remove(p);
            } else if (now > p.expiresAt) {
                if (p.decision == null) {
                    cancelInternal(p);
                } else {
                    remove(p);
                }
            }
        }
    }

    /** Cancel every pending request (e.g. listener shutdown). */
    public void cancelAll(String reason) {
        for (PendingApproval p : new ArrayList<>(byId.values())) {
            if (p.decision != null) {
                // Already decided: nothing to cancel, just drop it so a
                // restart/reset does not retain stale decisions.
                remove(p);
            } else {
                cancelInternal(p);
            }
        }
    }

    private void remove(PendingApproval p) {
        byId.remove(p.id);
        byKey.remove(key(p.clientIp, p.fingerprint), p);
    }

    // ── Session grants ───────────────────────────────────────────────

    public boolean isToolGranted(String toolName) {
        return toolName != null && toolGrants.contains(toolName);
    }

    public boolean isOriginGranted(String origin) {
        return origin != null && originGrants.contains(origin);
    }

    public Set<String> getToolGrants() {
        return Collections.unmodifiableSet(toolGrants);
    }

    public Set<String> getOriginGrants() {
        return Collections.unmodifiableSet(originGrants);
    }

    public boolean revokeToolGrant(String toolName) {
        boolean removed = toolGrants.remove(toolName);
        if (removed) {
            fire(Listener::onGrantsChanged);
        }
        return removed;
    }

    public boolean revokeOriginGrant(String origin) {
        boolean removed = originGrants.remove(origin);
        if (removed) {
            fire(Listener::onGrantsChanged);
        }
        return removed;
    }

    public void clearSessionGrants() {
        if (toolGrants.isEmpty() && originGrants.isEmpty()) {
            return;
        }
        toolGrants.clear();
        originGrants.clear();
        fire(Listener::onGrantsChanged);
    }

    /** Cancel pending requests and drop all session grants (test/reset helper). */
    public void clearAll() {
        cancelAll("cleared");
        clearSessionGrants();
    }

    // ── Introspection / wiring ───────────────────────────────────────

    /** Unresolved pending requests, oldest first. */
    public List<PendingApproval> getPending() {
        sweepExpired();
        List<PendingApproval> out = new ArrayList<>();
        for (PendingApproval p : byId.values()) {
            if (!p.cancelled && p.decision == null) {
                out.add(p);
            }
        }
        out.sort(Comparator.comparingLong(PendingApproval::getCreatedAt));
        return out;
    }

    public int pendingCount() {
        return getPending().size();
    }

    public PendingApproval getById(String id) {
        return id == null ? null : byId.get(id);
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Scope mutation callback used for ADD_TO_SCOPE decisions. */
    public void setScopeAdder(Consumer<String> adder) {
        this.scopeAdder = adder != null ? adder : url -> { };
    }

    private void fire(Consumer<Listener> action) {
        for (Listener l : listeners) {
            try {
                action.accept(l);
            } catch (Exception ignored) {
                // A broken UI listener must never break approval handling.
            }
        }
    }

    private static String key(String clientIp, String fingerprint) {
        return (clientIp != null ? clientIp : "?") + "|" + (fingerprint != null ? fingerprint : "?");
    }
}
