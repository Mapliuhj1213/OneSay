/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

/** 执行端口（宿主：打开 App / URI、交给 ScreenshotAnalyzer、有副作用的交给 ExecutionGate）。只在用户点了 RUN 按钮后调。 */
public interface ActionRunner {
    void run(Suggestion s, SAction a, Done done);

    interface Done {
        void finished(boolean ok, String shortReason);
    }

    ActionRunner NONE = new ActionRunner() {
        @Override public void run(Suggestion s, SAction a, Done done) {
            if (done != null) done.finished(false, "no_runner");
        }
    };
}
