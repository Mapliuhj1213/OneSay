package com.onesay.plugin.api.exec;

/** 执行结果（不可变）。rc = -1 表示没跑起来（后端不可用 / 超时 / 异常）。 */
public final class ExecResult {
    public final int rc;
    public final String output;
    public final boolean timedOut;
    public final boolean backendDown;

    public ExecResult(int rc, String output, boolean timedOut, boolean backendDown) {
        this.rc = rc;
        this.output = output == null ? "" : output;
        this.timedOut = timedOut;
        this.backendDown = backendDown;
    }

    public static ExecResult down(String why) {
        return new ExecResult(-1, why, false, true);
    }

    public boolean ok() {
        return rc == 0 && !timedOut && !backendDown;
    }
}
