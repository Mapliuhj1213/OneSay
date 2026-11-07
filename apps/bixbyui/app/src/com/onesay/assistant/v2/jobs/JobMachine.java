package com.onesay.assistant.v2.jobs;

/**
 * 定时任务 / 任务队列共用的状态机（lane-a 第 3、4 项）。纯 Java，JVM 可测。
 *
 * <pre>
 *   PENDING(待执行) ──START──▶ RUNNING(执行中) ──SUCCEED──▶ DONE(完成)
 *      ▲  │                      │  │
 *      │  │CANCEL                │  └─NEED_CONFIRM──▶ WAITING_CONFIRM(等确认) ──CONFIRMED──▶ RUNNING
 *      │  ▼                      │                        │DENIED / CANCEL / 超时
 *      │ CANCELLED               └─FAIL──▶ RETRY_WAIT(失败待重试) ──RETRY_DUE──▶ PENDING
 *      │                                   （次数用完 → FAILED）
 *      └──RECOVER（App 被杀后：RUNNING 视为中断，有重试余量 → PENDING，否则 FAILED）
 * </pre>
 * 「等确认」包括：运行时碰到越权调用（不执行，停在这里并在岛上提示）、需要每次单独确认的动作、任务改了要重新授权。
 * 只有显式确认（CONFIRMED）能离开等确认；超时 / 拒绝一律落到 CANCELLED，绝不自动批准。
 */
public final class JobMachine {

    private JobMachine() {}

    public enum State {
        PENDING("待执行"), RUNNING("执行中"), WAITING_CONFIRM("等确认"), RETRY_WAIT("失败待重试"),
        DONE("完成"), FAILED("失败"), CANCELLED("已取消");

        public final String label;

        State(String label) {
            this.label = label;
        }

        public boolean terminal() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }

        /** 占着队列的执行位（队列一次只跑一个）。 */
        public boolean active() {
            return this == RUNNING || this == WAITING_CONFIRM;
        }
    }

    public enum Event { START, NEED_CONFIRM, CONFIRMED, DENIED, SUCCEED, FAIL, RETRY_DUE, CANCEL, RECOVER }

    /** 默认最多尝试次数（含第一次）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 重试退避：第 n 次失败后等 base × 2^(n-1)，封顶 cap。 */
    public static final long RETRY_BASE_MS = 60L * 1000L;
    public static final long RETRY_CAP_MS = 30L * 60L * 1000L;

    /** 一次迁移的结果：新状态（null = 非法迁移，状态不变）。 */
    public static State next(State s, Event e, int attempts, int maxAttempts) {
        if (s == null || e == null) return null;
        if (e == Event.CANCEL) return s.terminal() ? null : State.CANCELLED;
        switch (s) {
            case PENDING:
                if (e == Event.START) return State.RUNNING;
                if (e == Event.NEED_CONFIRM) return State.WAITING_CONFIRM;   // 入库前 / 改动后要重新授权
                return null;
            case RUNNING:
                if (e == Event.SUCCEED) return State.DONE;
                if (e == Event.NEED_CONFIRM) return State.WAITING_CONFIRM;
                if (e == Event.FAIL) return attempts < maxAttempts ? State.RETRY_WAIT : State.FAILED;
                if (e == Event.RECOVER) return attempts < maxAttempts ? State.PENDING : State.FAILED;
                return null;
            case WAITING_CONFIRM:
                if (e == Event.CONFIRMED) return State.RUNNING;
                if (e == Event.DENIED) return State.CANCELLED;
                if (e == Event.RECOVER) return State.WAITING_CONFIRM;   // 等确认跨重启保留（卡片重新弹）
                return null;
            case RETRY_WAIT:
                if (e == Event.RETRY_DUE) return State.PENDING;
                if (e == Event.RECOVER) return State.RETRY_WAIT;
                return null;
            default:
                return null;
        }
    }

    /** 第 attempts 次失败后的重试等待。 */
    public static long retryDelay(int attempts) {
        if (attempts <= 0) return RETRY_BASE_MS;
        long d = RETRY_BASE_MS;
        for (int i = 1; i < attempts && d < RETRY_CAP_MS; i++) d *= 2;
        return Math.min(d, RETRY_CAP_MS);
    }

    public static State parse(String s) {
        if (s == null) return State.PENDING;
        try {
            return State.valueOf(s);
        } catch (IllegalArgumentException e) {
            return State.PENDING;
        }
    }
}
