package burp.mcp.tool;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.burpsuite.BurpSuite;
import burp.api.montoya.burpsuite.TaskExecutionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task engine get/set via a stubbed engine (plain interface Proxy —
 * no Burp runtime needed).
 */
class TaskEngineToolsTest {

    private MontoyaApi apiWith(AtomicReference<TaskExecutionEngine.TaskExecutionEngineState> state) {
        return (MontoyaApi) java.lang.reflect.Proxy.newProxyInstance(
            MontoyaApi.class.getClassLoader(),
            new Class<?>[] { MontoyaApi.class },
            (proxy, method, args) -> {
                if ("burpSuite".equals(method.getName())) {
                    return java.lang.reflect.Proxy.newProxyInstance(
                        BurpSuite.class.getClassLoader(),
                        new Class<?>[] { BurpSuite.class },
                        (p2, m2, a2) -> {
                            if ("taskExecutionEngine".equals(m2.getName())) {
                                return java.lang.reflect.Proxy.newProxyInstance(
                                    TaskExecutionEngine.class.getClassLoader(),
                                    new Class<?>[] { TaskExecutionEngine.class },
                                    (p3, m3, a3) -> {
                                        if ("getState".equals(m3.getName())) {
                                            return state.get();
                                        }
                                        if ("setState".equals(m3.getName())) {
                                            state.set((TaskExecutionEngine.TaskExecutionEngineState) a3[0]);
                                            return null;
                                        }
                                        return null;
                                    });
                            }
                            return null;
                        });
                }
                return null;
            });
    }

    @Test
    void parseState_shouldMapKnownStates() {
        assertThat(TaskEngineSetTool.parseState("RUNNING"))
                .isEqualTo(TaskExecutionEngine.TaskExecutionEngineState.RUNNING);
        assertThat(TaskEngineSetTool.parseState("paused"))
                .isEqualTo(TaskExecutionEngine.TaskExecutionEngineState.PAUSED);
    }

    @Test
    void parseState_unknown_shouldThrow() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> TaskEngineSetTool.parseState("bogus"))
                .isInstanceOf(burp.mcp.util.McpError.class);
    }

    @Test
    void status_shouldReportEngineState() throws Exception {
        AtomicReference<TaskExecutionEngine.TaskExecutionEngineState> state =
                new AtomicReference<>(TaskExecutionEngine.TaskExecutionEngineState.RUNNING);
        Object out = new TaskEngineStatusTool(apiWith(state)).execute(Map.of());
        assertThat(new ObjectMapper().readTree(out.toString()).path("state").asText())
                .isEqualTo("RUNNING");
    }

    @Test
    void set_shouldPauseAndVerify() throws Exception {
        AtomicReference<TaskExecutionEngine.TaskExecutionEngineState> state =
                new AtomicReference<>(TaskExecutionEngine.TaskExecutionEngineState.RUNNING);
        MontoyaApi api = apiWith(state);
        Object out = new TaskEngineSetTool(api).execute(Map.of("state", "PAUSED"));
        assertThat(new ObjectMapper().readTree(out.toString()).path("success").asBoolean()).isTrue();
        assertThat(state.get()).isEqualTo(TaskExecutionEngine.TaskExecutionEngineState.PAUSED);
    }
}
