package burp.mcp.server;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.scope.Scope;
import burp.mcp.util.ApprovalManager;
import burp.mcp.util.McpError;
import burp.mcp.util.PermissionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class AccessGateTest {

    private PermissionManager permissions;
    private ApprovalManager approvals;

    @BeforeEach
    void setUp() {
        permissions = new PermissionManager();
        approvals = new ApprovalManager();
    }

    // ── Fixtures ────────────────────────────────────────────────────

    static final class ScopeState {
        final List<String> included = Collections.synchronizedList(new ArrayList<>());
        volatile Predicate<String> inScope = url -> true;

        ScopeState(Predicate<String> inScope) {
            if (inScope != null) {
                this.inScope = inScope;
            }
        }
    }

    private static AccessGate.Settings settings(String enforcement, int waitSeconds, int ttlSeconds) {
        return new AccessGate.Settings() {
            @Override public String scopeEnforcement() { return enforcement; }
            @Override public int approvalWaitSeconds() { return waitSeconds; }
            @Override public int approvalTtlSeconds() { return ttlSeconds; }
        };
    }

    private AccessGate gate(ScopeState scope, String enforcement, int waitSeconds) {
        return new AccessGate(mockApi(scope), permissions, approvals,
                settings(enforcement, waitSeconds, 60));
    }

    private static MontoyaApi mockApi(ScopeState state) {
        return (MontoyaApi) Proxy.newProxyInstance(
                MontoyaApi.class.getClassLoader(),
                new Class<?>[]{MontoyaApi.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "scope":
                            return (Scope) Proxy.newProxyInstance(
                                    Scope.class.getClassLoader(),
                                    new Class<?>[]{Scope.class},
                                    (p, m, a) -> {
                                        switch (m.getName()) {
                                            case "isInScope":
                                                return state.inScope.test((String) a[0]);
                                            case "includeInScope":
                                                state.included.add((String) a[0]);
                                                return null;
                                            default:
                                                return null;
                                        }
                                    });
                        case "logging":
                            return (Logging) Proxy.newProxyInstance(
                                    Logging.class.getClassLoader(),
                                    new Class<?>[]{Logging.class},
                                    (p, m, a) -> null);
                        default:
                            return null;
                    }
                });
    }

    /** Resolve every new pending synchronously (runs on the gate's own thread). */
    private ApprovalManager.Listener autoResolver(ApprovalManager.PermissionDecision permission,
                                                  ApprovalManager.ScopeDecision scope,
                                                  String reason) {
        return new ApprovalManager.Listener() {
            @Override
            public void onPendingAdded(ApprovalManager.PendingApproval pending) {
                approvals.resolve(pending.getId(),
                        new ApprovalManager.Decision(permission, scope, reason));
            }
        };
    }

    private static Map<String, Object> urlArgs(String url) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("url", url);
        return args;
    }

    // ── Permission gate ─────────────────────────────────────────────

    @Test
    void policyDeny_shouldReturnPermissionDeniedWithoutPrompt() {
        permissions.setLevel(PermissionManager.Level.CUSTOM);
        permissions.setToolPolicy("http_send_request", PermissionManager.Policy.DENY);
        AccessGate gate = gate(new ScopeState(null), "prompt", 0);

        AccessGate.Result result = gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://target.test/"), List.of("http://target.test/"));

        assertThat(result.isDenied()).isTrue();
        assertThat(result.getCode()).isEqualTo(McpError.PERMISSION_DENIED);
        assertThat(result.getData()).containsEntry("source", "policy");
        assertThat(approvals.pendingCount()).isZero();
    }

    @Test
    void readWrite_inScope_shouldAllow() {
        AccessGate gate = gate(new ScopeState(null), "prompt", 0);
        AccessGate.Result result = gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://target.test/"), List.of("http://target.test/"));
        assertThat(result.isAllowed()).isTrue();
        assertThat(approvals.pendingCount()).isZero();
    }

    @Test
    void promptPolicy_instantAllow_shouldExecuteAndConsume() {
        permissions.setLevel(PermissionManager.Level.PROMPT);
        approvals.addListener(autoResolver(ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null));
        AccessGate gate = gate(new ScopeState(null), "prompt", 2);

        AccessGate.Result result = gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://target.test/"), List.of("http://target.test/"));

        assertThat(result.isAllowed()).isTrue();
        assertThat(approvals.pendingCount()).isZero();
    }

    @Test
    void promptPolicy_denyWithReason_shouldSurfaceReason() {
        permissions.setLevel(PermissionManager.Level.PROMPT);
        approvals.addListener(autoResolver(ApprovalManager.PermissionDecision.DENY, null, "not on my watch"));
        AccessGate gate = gate(new ScopeState(null), "prompt", 2);

        AccessGate.Result result = gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://target.test/"), List.of("http://target.test/"));

        assertThat(result.isDenied()).isTrue();
        assertThat(result.getCode()).isEqualTo(McpError.PERMISSION_DENIED);
        assertThat(result.getMessage()).contains("not on my watch");
        assertThat(result.getData())
                .containsEntry("source", "operator")
                .containsEntry("reason", "not on my watch")
                .containsEntry("permission_denied", true);
    }

    @Test
    void promptPolicy_noDecision_shouldReturnApprovalPending() {
        permissions.setLevel(PermissionManager.Level.PROMPT);
        AccessGate gate = gate(new ScopeState(null), "prompt", 0);

        AccessGate.Result result = gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://target.test/"), List.of("http://target.test/"));

        assertThat(result.isPending()).isTrue();
        assertThat(result.getCode()).isEqualTo(McpError.APPROVAL_PENDING);
        assertThat(result.getData()).containsKey("approval_id");
        assertThat(approvals.pendingCount()).isEqualTo(1);
    }

    @Test
    void promptPolicy_sessionGrant_shouldSkipNextPrompt() {
        permissions.setLevel(PermissionManager.Level.PROMPT);
        approvals.addListener(autoResolver(ApprovalManager.PermissionDecision.ALLOW_SESSION, null, null));
        AccessGate gate = gate(new ScopeState(null), "prompt", 0);

        assertThat(gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://target.test/a"), List.of("http://target.test/a")).isAllowed()).isTrue();
        assertThat(approvals.isToolGranted("http_send_request")).isTrue();

        // Different args, same tool: the session grant bypasses the prompt.
        assertThat(gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://target.test/b"), List.of("http://target.test/b")).isAllowed()).isTrue();
        assertThat(approvals.pendingCount()).isZero();
    }

    // ── Pending / retry protocol ────────────────────────────────────

    @Test
    void retryAfterPending_shouldHonourCachedAllowOnce() {
        permissions.setLevel(PermissionManager.Level.PROMPT);
        AccessGate gate = gate(new ScopeState(null), "prompt", 0);
        Map<String, Object> args = urlArgs("http://target.test/");

        AccessGate.Result first = gate.check("127.0.0.1", "http_send_request", args, List.of("http://target.test/"));
        assertThat(first.isPending()).isTrue();

        String id = (String) first.getData().get("approval_id");
        assertThat(approvals.resolve(id, new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null))).isTrue();

        AccessGate.Result retry = gate.check("127.0.0.1", "http_send_request", args, List.of("http://target.test/"));
        assertThat(retry.isAllowed()).isTrue();
        assertThat(approvals.pendingCount()).isZero();

        // The allow-once grant is consumed: a third identical call prompts again.
        AccessGate.Result third = gate.check("127.0.0.1", "http_send_request", args, List.of("http://target.test/"));
        assertThat(third.isPending()).isTrue();
    }

    @Test
    void retryAfterDeny_shouldKeepReturningReasonUntilTtl() {
        permissions.setLevel(PermissionManager.Level.PROMPT);
        AccessGate gate = gate(new ScopeState(null), "prompt", 0);
        Map<String, Object> args = urlArgs("http://target.test/");

        AccessGate.Result first = gate.check("127.0.0.1", "http_send_request", args, List.of("http://target.test/"));
        String id = (String) first.getData().get("approval_id");
        approvals.resolve(id, new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.DENY, null, "no"));

        for (int i = 0; i < 2; i++) {
            AccessGate.Result retry = gate.check("127.0.0.1", "http_send_request", args, List.of("http://target.test/"));
            assertThat(retry.isDenied()).isTrue();
            assertThat(retry.getData()).containsEntry("reason", "no");
        }
    }

    @Test
    void sameFingerprintFromDifferentClient_shouldNotReceiveCachedDecision() {
        permissions.setLevel(PermissionManager.Level.PROMPT);
        AccessGate gate = gate(new ScopeState(null), "prompt", 0);
        Map<String, Object> args = urlArgs("http://target.test/");

        AccessGate.Result first = gate.check("10.0.0.1", "http_send_request", args, List.of("http://target.test/"));
        String id = (String) first.getData().get("approval_id");
        approvals.resolve(id, new ApprovalManager.Decision(
                ApprovalManager.PermissionDecision.ALLOW_ONCE, null, null));

        // A different client gets its own prompt, not the other client's allow.
        AccessGate.Result other = gate.check("10.0.0.2", "http_send_request", args, List.of("http://target.test/"));
        assertThat(other.isPending()).isTrue();
    }

    // ── Scope gate ──────────────────────────────────────────────────

    @Test
    void outOfScope_denyEnforcement_shouldReturnOutOfScope() {
        AccessGate gate = gate(new ScopeState(url -> false), "deny", 0);

        AccessGate.Result result = gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://evil.test/"), List.of("http://evil.test/"));

        assertThat(result.isDenied()).isTrue();
        assertThat(result.getCode()).isEqualTo(McpError.OUT_OF_SCOPE);
        assertThat(result.getData())
                .containsEntry("out_of_scope", true)
                .containsEntry("source", "scope_policy");
    }

    @Test
    void outOfScope_prompt_addToScope_shouldModifyScopeAndAllow() {
        ScopeState state = new ScopeState(null);
        state.inScope = url -> state.included.contains(AccessGate.originOf(url));
        approvals.addListener(autoResolver(null, ApprovalManager.ScopeDecision.ADD_TO_SCOPE, null));
        AccessGate gate = gate(state, "prompt", 2);

        AccessGate.Result result = gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://out.test/a"), List.of("http://out.test/a"));

        assertThat(result.isAllowed()).isTrue();
        assertThat(state.included).containsExactly("http://out.test");

        // Later calls to the same origin are now in scope.
        AccessGate.Result second = gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://out.test/b"), List.of("http://out.test/b"));
        assertThat(second.isAllowed()).isTrue();
        assertThat(approvals.pendingCount()).isZero();
    }

    @Test
    void outOfScope_scopeSessionGrant_shouldSkipNextPrompt() {
        ScopeState state = new ScopeState(url -> false);
        approvals.addListener(autoResolver(null, ApprovalManager.ScopeDecision.ALLOW_SESSION, null));
        AccessGate gate = gate(state, "prompt", 2);

        assertThat(gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://out.test/a"), List.of("http://out.test/a")).isAllowed()).isTrue();
        assertThat(approvals.isOriginGranted("http://out.test")).isTrue();

        // Different target on the granted origin no longer prompts.
        assertThat(gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://out.test/b"), List.of("http://out.test/b")).isAllowed()).isTrue();
        assertThat(approvals.pendingCount()).isZero();
    }

    @Test
    void outOfScope_scopeDenied_shouldSurfaceOperatorReason() {
        approvals.addListener(autoResolver(null, ApprovalManager.ScopeDecision.DENY, "prod is off limits"));
        AccessGate gate = gate(new ScopeState(url -> false), "prompt", 2);

        AccessGate.Result result = gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://prod.test/"), List.of("http://prod.test/"));

        assertThat(result.isDenied()).isTrue();
        assertThat(result.getCode()).isEqualTo(McpError.PERMISSION_DENIED);
        assertThat(result.getData())
                .containsEntry("scope_denied", true)
                .containsEntry("reason", "prod is off limits");
    }

    @Test
    void scopeEnforcementOff_shouldSkipScopeChecks() {
        AccessGate gate = gate(new ScopeState(url -> false), "off", 0);
        assertThat(gate.check("127.0.0.1", "http_send_request",
                urlArgs("http://evil.test/"), List.of("http://evil.test/")).isAllowed()).isTrue();
    }

    // ── Fingerprint / origin helpers ────────────────────────────────

    @Test
    void fingerprint_shouldIgnoreKeyInsertionOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("url", "http://x.test/");
        a.put("nested", Map.of("b", 2, "a", 1));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("nested", Map.of("a", 1, "b", 2));
        b.put("url", "http://x.test/");

        assertThat(AccessGate.fingerprint("http_send_request", a))
                .isEqualTo(AccessGate.fingerprint("http_send_request", b));
        assertThat(AccessGate.fingerprint("http_send_request", a))
                .isNotEqualTo(AccessGate.fingerprint("logger_add", a));
    }

    @Test
    void originOf_shouldNormalizeSchemesHostsAndPorts() {
        assertThat(AccessGate.originOf("https://example.com/a?b=1")).isEqualTo("https://example.com");
        assertThat(AccessGate.originOf("http://example.com:8080/x")).isEqualTo("http://example.com:8080");
        assertThat(AccessGate.originOf("https://example.com:443/")).isEqualTo("https://example.com");
        assertThat(AccessGate.originOf("http://[::1]:8080/x")).isEqualTo("http://[::1]:8080");
        assertThat(AccessGate.originOf("not a url")).isNull();
        assertThat(AccessGate.originOf(null)).isNull();
    }
}
