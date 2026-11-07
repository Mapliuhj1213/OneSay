package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.executor.ExecutionResult;

/** 后端执行结果：要么是最终结果，要么是"执行成功 + 原始输出"（继续走通用回读）。 */
public final class BackendOutcome {
    public final ExecutionResult terminal;
    public final String output;

    private BackendOutcome(ExecutionResult terminal, String output) {
        this.terminal = terminal;
        this.output = output != null ? output : "";
    }

    public static BackendOutcome terminal(ExecutionResult r) {
        return new BackendOutcome(r, null);
    }

    public static BackendOutcome output(String out) {
        return new BackendOutcome(null, out);
    }

    public boolean isTerminal() {
        return terminal != null;
    }
}
