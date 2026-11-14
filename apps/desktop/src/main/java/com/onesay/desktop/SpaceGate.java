package com.onesay.desktop;

/**
 * 「长按空格说话」的判定状态机。Windows 钩子（resources/onesay/hotkey.ps1 里的 C#）逐行照抄这份逻辑，
 * 这里的版本用来做单测；改一边必须改另一边。
 *
 * 按下空格先吞掉并计时：
 *  - 350 ms 内松开 → 补打一个空格（注入的按键带标记，钩子放行，不会循环）
 *  - 按住超过 350 ms → 开始说话，松开结束；按 Esc 取消
 *  - 按住空格期间又按了别的键（打字太快的连按）→ 立刻补打空格，别的键照常，空格松开时吞掉
 * 带 Ctrl/Alt/Win（如 Ctrl+空格切输入法）或在跳过的窗口里（全屏、远程桌面、管理员窗口）一律放行。
 */
public final class SpaceGate {
    public static final long HOLD_MS = 350;

    public enum State { IDLE, PENDING, TALKING, ROLLED }

    /** 每个事件的处理结果。 */
    public static final class Out {
        public boolean swallow, injectSpace, talkStart, talkEnd, talkCancel, startTimer;
        @Override public String toString() {
            return (swallow ? "swallow " : "pass ") + (injectSpace ? "inject " : "") + (talkStart ? "start " : "")
                    + (talkEnd ? "end " : "") + (talkCancel ? "cancel " : "") + (startTimer ? "timer" : "");
        }
    }

    private State state = State.IDLE;
    private int timerGen;

    public State state() { return state; }
    public int timerGen() { return timerGen; }

    public synchronized Out spaceDown(boolean bypass) {
        Out o = new Out();
        switch (state) {
            case IDLE:
                if (bypass) return o;
                state = State.PENDING;
                timerGen++;
                o.startTimer = true;
                o.swallow = true;
                return o;
            default: // 自动重复
                o.swallow = true;
                return o;
        }
    }

    public synchronized Out spaceUp() {
        Out o = new Out();
        switch (state) {
            case PENDING: o.injectSpace = true; o.swallow = true; break;
            case TALKING: o.talkEnd = true; o.swallow = true; break;
            case ROLLED: o.swallow = true; break;
            default: return o;
        }
        state = State.IDLE;
        return o;
    }

    /** 别的键按下（不含注入的）。isEsc：是不是 Esc。 */
    public synchronized Out otherDown(boolean isEsc) {
        Out o = new Out();
        if (state == State.PENDING) { state = State.ROLLED; o.injectSpace = true; }
        else if (state == State.TALKING && isEsc) { state = State.ROLLED; o.talkCancel = true; o.swallow = true; }
        return o;
    }

    public synchronized Out timer(int gen) {
        Out o = new Out();
        if (state == State.PENDING && gen == timerGen) { state = State.TALKING; o.talkStart = true; }
        return o;
    }
}
