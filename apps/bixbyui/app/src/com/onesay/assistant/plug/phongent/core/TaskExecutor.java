/*
 * [phongent 插件 · 宿主端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，
 * 宿主（OneSay 或别的 App）实现这些窄接口把执行端接进来。见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

/** 执行端：主控下发的一句话交给宿主的 agent 跑（宿主负责门禁 / 确认）。 */
public interface TaskExecutor {

    /** 交给本机执行；tainted = 任务正文带第三方原文（通知代回），宿主应从第一步起按不可信过闸。返回执行记录 id，排不进返回 -1。 */
    long submit(String text, boolean tainted);

    /** 读执行记录；没了返回 null。 */
    RunView view(long runId);

    /** 停掉（排队中 / 正在跑都行）。 */
    boolean cancel(long runId);

    /** 一条执行记录。state 取值：PENDING / RUNNING / WAITING_CONFIRM / RETRY_WAIT / DONE / FAILED / CANCELLED。 */
    final class RunView {
        public final String state;
        public final String step;
        public final String result;
        public final String error;

        public RunView(String state, String step, String result, String error) {
            this.state = state == null ? "" : state;
            this.step = step == null ? "" : step;
            this.result = result == null ? "" : result;
            this.error = error == null ? "" : error;
        }
    }
}
