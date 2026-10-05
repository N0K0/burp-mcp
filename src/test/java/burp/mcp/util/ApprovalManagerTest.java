package burp.mcp.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalManagerTest {

    private ApprovalManager manager;

    @BeforeEach
    void setUp() {
        manager = new ApprovalManager();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static ApprovalManager.Context context(String ip, String tool, String fingerprint,
                                                   boolean permission, boolean scope,
                                                   List<String> targets, List<String> outOfScope,
                                                   List<String> origins) {
        return new ApprovalManager.Context(ip, tool, fingerprint, "{}", targets, outOfScope, origins,
                permission, scope,
                permission ? "write operation requires approval" : null,
                scope ? "target not in scope" : null);
    }

    private static ApprovalManager.Context permissionOnly(String fingerprint) {
        return context("127.0.0.1", "http_send_request", fingerprint,
                true, false, List.of("http://target.test/"), List.of(), List.of());
    }

    private static ApprovalManager.Context scopeOnly(String fingerprint) {
        return context("127.0.0.1", "http_send_request", fingerprint,
                false, true, List.of("http://out.test/"), List.of("http://out.test/"),
                List.of("http://out.test"));
    }

    private static void awaitWaiting(ApprovalManager.PendingApproval pending) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!pending.isWaiting() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pending.isWaiting()).as("waiter should attach").isTrue();
    }

    // ── Decision delivery ───────────────────────────────────────────

    @Test
    void allowOnce_shouldBeDeliveredToWaiter() throws Exception {
        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp1"), 5000);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ApprovalManager.Outcome> waiting = pool.submit(() -> manager.await(pending, 2000));
            awaitWaiting(pending);

            assertThat(manager.resolve(pending.getId(),
                    new ApprovalManager.Decision(
                            ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null))).isTrue();

            ApprovalManager.Outcome outcome = waiting.get(2, TimeUnit.SECONDS);
            assertThat(outcome.getKind()).isEqualTo(ApprovalManager.WaitKind.DECIDED);
            assertThat(pending.isResolved()).isTrue();

            manager.consume(pending);
            assertThat(manager.pendingCount()).isZero();
            assertThat(manager.find("127.0.0.1", "fp1")).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deny_shouldStoreReasonAndSurviveForRetry() {
        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp2"), 5000);
        assertThat(manager.resolve(pending.getId(),
                new ApprovalManager.Decision(
                        ApprovalManager.PermissionDecision.DENY, null, "use staging instead"))).isTrue();

        assertThat(pending.getDecision().isDenied()).isTrue();
        assertThat(pending.getDecision().reasonOrDefault("fallback")).isEqualTo("use staging instead");

        // A retry matches the cached, resolved pending until its TTL.
        assertThat(manager.find("127.0.0.1", "fp2")).isSameAs(pending);
        assertsWaitDecided(pending);
    }

    private void assertsWaitDecided(ApprovalManager.PendingApproval pending) {
        ApprovalManager.Outcome outcome = manager.await(pending, 10);
        assertThat(outcome.getKind()).isEqualTo(ApprovalManager.WaitKind.DECIDED);
    }

    @Test
    void allowSession_shouldGrantToolAndFireGrantsEvent() {
        AtomicInteger grantsEvents = new AtomicInteger();
        manager.addListener(new ApprovalManager.Listener() {
            @Override public void onGrantsChanged() {
                grantsEvents.incrementAndGet();
            }
        });

        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp3"), 5000);
        manager.resolve(pending.getId(), new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.ALLOW_SESSION, null, null));

        assertThat(manager.isToolGranted("http_send_request")).isTrue();
        assertThat(manager.isToolGranted("other_tool")).isFalse();
        assertThat(grantsEvents.get()).isEqualTo(1);

        assertThat(manager.revokeToolGrant("http_send_request")).isTrue();
        assertThat(manager.isToolGranted("http_send_request")).isFalse();
        assertThat(grantsEvents.get()).isEqualTo(2);
    }

    @Test
    void scopeAllowSession_shouldGrantOrigin() {
        ApprovalManager.PendingApproval pending = manager.findOrCreate(scopeOnly("fp4"), 5000);
        manager.resolve(pending.getId(), new ApprovalManager.Decision(
                null, ApprovalManager.ScopeDecision.ALLOW_SESSION, null));

        assertThat(manager.isOriginGranted("http://out.test")).isTrue();
        assertThat(manager.isOriginGranted("http://other.test")).isFalse();
    }

    @Test
    void addToScope_shouldInvokeScopeAdderForOrigins() {
        List<String> added = Collections.synchronizedList(new ArrayList<>());
        manager.setScopeAdder(added::add);

        ApprovalManager.PendingApproval pending = manager.findOrCreate(scopeOnly("fp5"), 5000);
        manager.resolve(pending.getId(), new ApprovalManager.Decision(
                null, ApprovalManager.ScopeDecision.ADD_TO_SCOPE, null));

        assertThat(added).containsExactly("http://out.test");
    }

    // ── Waiting / timeout / expiry ──────────────────────────────────

    @Test
    void await_timeout_shouldReturnPendingAndKeepRequestQueued() {
        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp6"), 5000);
        ApprovalManager.Outcome outcome = manager.await(pending, 50);

        assertThat(outcome.getKind()).isEqualTo(ApprovalManager.WaitKind.PENDING);
        assertThat(manager.pendingCount()).isEqualTo(1);
        assertThat(manager.getPending()).containsExactly(pending);
    }

    @Test
    void secondWaiter_shouldReturnPendingImmediately() throws Exception {
        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp7"), 5000);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ApprovalManager.Outcome> first = pool.submit(() -> manager.await(pending, 5000));
            awaitWaiting(pending);

            long start = System.currentTimeMillis();
            ApprovalManager.Outcome second = manager.await(pending, 5000);
            long elapsed = System.currentTimeMillis() - start;

            assertThat(second.getKind()).isEqualTo(ApprovalManager.WaitKind.PENDING);
            assertThat(elapsed).isLessThan(1000);

            manager.resolve(pending.getId(), new ApprovalManager.Decision(
                    ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null));
            assertThat(first.get(2, TimeUnit.SECONDS).getKind())
                    .isEqualTo(ApprovalManager.WaitKind.DECIDED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiry_shouldCancelPendingAndNotify() throws Exception {
        AtomicInteger expiredEvents = new AtomicInteger();
        manager.addListener(new ApprovalManager.Listener() {
            @Override public void onPendingExpired(ApprovalManager.PendingApproval pending) {
                expiredEvents.incrementAndGet();
            }
        });

        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp8"), 20);
        Thread.sleep(40);
        manager.sweepExpired();

        assertThat(pending.isCancelled()).isTrue();
        assertThat(manager.pendingCount()).isZero();
        assertThat(expiredEvents.get()).isEqualTo(1);
        assertThat(manager.await(pending, 10).getKind())
                .isEqualTo(ApprovalManager.WaitKind.EXPIRED);
        assertThat(manager.resolve(pending.getId(), new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null))).isFalse();
    }

    @Test
    void cancelAll_shouldWakeWaiters() throws Exception {
        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp9"), 5000);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ApprovalManager.Outcome> waiting = pool.submit(() -> manager.await(pending, 5000));
            awaitWaiting(pending);
            manager.cancelAll("server stopped");
            assertThat(waiting.get(2, TimeUnit.SECONDS).getKind())
                    .isEqualTo(ApprovalManager.WaitKind.EXPIRED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelAll_shouldAlsoDropResolvedPendings() {
        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp-resolved"), 5000);
        manager.resolve(pending.getId(), new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.DENY, null, "no"));
        assertThat(manager.find("127.0.0.1", "fp-resolved")).isSameAs(pending);

        manager.cancelAll("reset");
        assertThat(manager.find("127.0.0.1", "fp-resolved")).isNull();
    }

    // ── Dedupe / lifecycle ──────────────────────────────────────────

    @Test
    void findOrCreate_shouldDedupeByClientAndFingerprint() {
        ApprovalManager.PendingApproval a = manager.findOrCreate(permissionOnly("same"), 5000);
        ApprovalManager.PendingApproval b = manager.findOrCreate(permissionOnly("same"), 5000);
        ApprovalManager.PendingApproval c = manager.findOrCreate(permissionOnly("other"), 5000);

        assertThat(b).isSameAs(a);
        assertThat(c).isNotSameAs(a);
        assertThat(manager.pendingCount()).isEqualTo(2);
    }

    @Test
    void sameFingerprintFromDifferentClients_shouldNotShareDecision() {
        ApprovalManager.PendingApproval mobile = manager.findOrCreate(
                context("10.0.0.1", "http_send_request", "fp", true, false,
                        List.of("http://x/"), List.of(), List.of()), 5000);
        ApprovalManager.PendingApproval laptop = manager.findOrCreate(
                context("10.0.0.2", "http_send_request", "fp", true, false,
                        List.of("http://x/"), List.of(), List.of()), 5000);

        assertThat(mobile).isNotSameAs(laptop);
        manager.resolve(mobile.getId(), new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.DENY, null, "no"));
        assertThat(laptop.isResolved()).isFalse();
    }

    @Test
    void resolvedAllow_shouldBeFindableUntilConsumed() {
        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp10"), 5000);
        manager.resolve(pending.getId(), new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null));

        assertThat(manager.find("127.0.0.1", "fp10")).isSameAs(pending);
        manager.consume(pending);
        assertThat(manager.find("127.0.0.1", "fp10")).isNull();
    }

    @Test
    void sweep_shouldPruneResolvedPendingAfterTtl() throws Exception {
        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp11"), 20);
        manager.resolve(pending.getId(), new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.DENY, null, "no"));
        Thread.sleep(40);
        manager.sweepExpired();

        assertThat(manager.find("127.0.0.1", "fp11")).isNull();
    }

    @Test
    void resolveUnknownOrDuplicate_shouldReturnFalse() {
        assertThat(manager.resolve("nope", new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null))).isFalse();

        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp12"), 5000);
        assertThat(manager.resolve(pending.getId(), new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null))).isTrue();
        assertThat(manager.resolve(pending.getId(), new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.DENY, null, null))).isFalse();
    }

    @Test
    void clearSessionGrants_shouldRemoveAllGrants() {
        ApprovalManager.PendingApproval toolPending = manager.findOrCreate(permissionOnly("fp13"), 5000);
        manager.resolve(toolPending.getId(), new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.ALLOW_SESSION, null, null));
        ApprovalManager.PendingApproval scopePending = manager.findOrCreate(scopeOnly("fp14"), 5000);
        manager.resolve(scopePending.getId(), new ApprovalManager.Decision(
                null, ApprovalManager.ScopeDecision.ALLOW_SESSION, null));

        assertThat(manager.getToolGrants()).containsExactly("http_send_request");
        assertThat(manager.getOriginGrants()).containsExactly("http://out.test");

        manager.clearSessionGrants();
        assertThat(manager.getToolGrants()).isEmpty();
        assertThat(manager.getOriginGrants()).isEmpty();
    }

    @Test
    void listener_shouldSeeAddAndResolveEvents() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        manager.addListener(new ApprovalManager.Listener() {
            @Override public void onPendingAdded(ApprovalManager.PendingApproval p) {
                events.add("added:" + p.getToolName());
            }
            @Override public void onPendingResolved(ApprovalManager.PendingApproval p,
                                                    ApprovalManager.Decision d) {
                events.add("resolved:" + d.getPermission());
            }
        });

        ApprovalManager.PendingApproval pending = manager.findOrCreate(permissionOnly("fp15"), 5000);
        manager.resolve(pending.getId(), new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null));

        assertThat(events).containsExactly("added:http_send_request", "resolved:ALLOW_ONCE");
    }

    @Test
    void context_shouldExposeRequirementDescriptions() {
        ApprovalManager.PendingApproval pending = manager.findOrCreate(
                context("1.2.3.4", "scanner_start_audit", "fp16", true, true,
                        List.of("http://out.test/a"), List.of("http://out.test/a"),
                        List.of("http://out.test")), 5000);

        assertThat(pending.isPermissionPrompt()).isTrue();
        assertThat(pending.isScopePrompt()).isTrue();
        assertThat(pending.getOutOfScopeTargets()).containsExactly("http://out.test/a");
        assertThat(pending.getOutOfScopeOrigins()).containsExactly("http://out.test");
        assertThat(pending.getPermissionReason()).contains("approval");
        assertThat(pending.getScopeReason()).contains("scope");
        assertThat(pending.getExpiresAt()).isGreaterThan(pending.getCreatedAt());
    }
}
