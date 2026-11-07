package com.onesay.assistant.v2.planner;

/**
 * [planner] 「这一刻用不用计划 App」的开关（纯 Java）。真机实现见 {@link PlannerLink}：装了计划 App、ping 得通、
 * 用户没关「数据放在计划 App」→ true。读写出错 → {@link #failed}，冷却期内回落本地，之后再试。
 */
public interface PlannerGate {
    boolean on();

    void failed(Throwable t);

    /** 单测用：固定开 / 关。 */
    final class Fixed implements PlannerGate {
        public boolean on;
        public int failures;

        public Fixed(boolean on) { this.on = on; }

        @Override public boolean on() { return on; }

        @Override public void failed(Throwable t) {
            failures++;
            on = false;
        }
    }
}
