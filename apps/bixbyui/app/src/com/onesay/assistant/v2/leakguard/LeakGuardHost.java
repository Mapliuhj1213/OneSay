package com.onesay.assistant.v2.leakguard;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.plug.leakguard.core.PromptLeakGuard;

import java.io.File;

/**
 * ★ fix/leak-guard（2026-11-02）：{@link PromptLeakGuard} 的 Android 宿主接线。
 *
 * <ul>
 *   <li>首次使用时注册：热词前缀 + 静态热词表（{@code AsrHotwords}；动态应用名由 AsrHotwords 每次重建热词表时追加），
 *       以及各 LLM 系统提示词（assets/agent_prompt.md、PromptLibrary 的 ask.output / do.manual、ResearchRunner、
 *       BriefDetail、SoulPrompts.FLUSH_SYSTEM、AgentLoop.UNTRUSTED_RULE、AppChoiceSpec.PROMPT_RULE、WorthTelling 尾巴）。</li>
 *   <li>开关（默认开，debug / release 一样）：kill 文件 {@code files/plug_leakguard.off} &gt; prefs {@code bixby_plug/leakguard}
 *       &gt; 出厂默认 true。读一次缓存 5 s。</li>
 *   <li>命中打 {@code [LEAK-GUARD] where=… reason=… len=… head=…}（只打前 20 字，不打全文）。</li>
 * </ul>
 * 调用方拿到 true = 拦下：不显示、不朗读、不执行，按「没听清」收尾（各接入点自己收尾）。
 */
public final class LeakGuardHost {

    public static final String TAG = "LeakGuard";
    public static final String PREFS = "bixby_plug";
    public static final String KEY = "leakguard";
    public static final String KILL_FILE = "plug_leakguard.off";

    private static volatile boolean sInited;
    private static volatile long sSwitchReadAt;
    private static volatile Context sApp;
    private static volatile int sHits;

    private LeakGuardHost() {}

    /** 命中次数（进程内，调试用）。 */
    public static int hits() { return sHits; }

    /** 输入侧（识别最终结果 / 分段 / 指令入口）。命中返回 true 并打日志。 */
    public static boolean blockInput(Context ctx, String where, String text) {
        if (text == null || text.trim().length() == 0) return false;
        PromptLeakGuard g = ensure(ctx);
        return report(where, text, g.check(text));
    }

    /** 中间结果上屏。命中返回 true 并打日志。 */
    public static boolean blockPartial(Context ctx, String where, String text) {
        if (text == null || text.trim().length() == 0) return false;
        PromptLeakGuard g = ensure(ctx);
        return report(where, text, g.checkPartial(text));
    }

    /** 输出侧（回答卡片 / TTS / 最终回答）。命中返回 true 并打日志。 */
    public static boolean blockOutput(Context ctx, String where, String text) {
        if (text == null || text.trim().length() == 0) return false;
        PromptLeakGuard g = ensure(ctx);
        return report(where, text, g.checkOutput(text));
    }

    private static boolean report(String where, String text, PromptLeakGuard.Verdict v) {
        if (v == null || !v.leak()) return false;
        sHits++;
        try {
            Log.w(TAG, "[LEAK-GUARD] where=" + where + " reason=" + v.describe() + " len=" + text.length()
                    + " head=" + PromptLeakGuard.head20(text));
        } catch (Throwable ignore) {}
        return true;
    }

    /** 取全局守卫（首次注册数据；刷新开关）。任何线程可调；失败不抛。 */
    public static PromptLeakGuard ensure(Context ctx) {
        PromptLeakGuard g = PromptLeakGuard.global();
        if (ctx != null && sApp == null) {
            Context a = null;
            try { a = ctx.getApplicationContext(); } catch (Throwable ignore) {}
            sApp = a != null ? a : ctx;
        }
        refreshSwitch(g);
        if (!sInited) {
            synchronized (LeakGuardHost.class) {
                if (!sInited) {
                    sInited = true;
                    init(g, sApp);
                }
            }
        }
        return g;
    }

    private static void refreshSwitch(PromptLeakGuard g) {
        long now = System.currentTimeMillis();
        if (now - sSwitchReadAt < 5000L) return;
        sSwitchReadAt = now;
        Context c = sApp;
        if (c == null) return;
        try {
            boolean on;
            if (new File(c.getFilesDir(), KILL_FILE).exists()) on = false;
            else on = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
            if (on != g.isEnabled()) Log.i(TAG, "[LEAK-GUARD] 开关 → " + (on ? "开" : "关"));
            g.setEnabled(on);
        } catch (Throwable ignore) {}
    }

    /** debug 广播 / 控制台用：写 prefs（立即生效）。 */
    public static void setEnabled(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
        } catch (Throwable ignore) {}
        sSwitchReadAt = 0L;
        PromptLeakGuard.global().setEnabled(on);
    }

    private static void init(PromptLeakGuard g, Context app) {
        configure(g, app, null);
    }

    /**
     * ★ int/1102：线上初始化的本体（{@link #ensure} 首次调用走这里），抽出来让 JVM 审计 harness 用<b>同一份代码</b>构建守卫。
     * @param agentPromptFallback app == null（JVM）时代替 {@code PromptSource.agentPrompt} 的文本（assets/agent_prompt.md 原文）；线上传 null
     */
    public static void configure(PromptLeakGuard g, Context app, String agentPromptFallback) {
        long t0 = System.nanoTime();
        try {
            g.setHotwordPrefix(com.onesay.assistant.v2.asr.AsrHotwords.HOTWORD_PROMPT_PREFIX);
            g.addHotwords(java.util.Arrays.asList(com.onesay.assistant.v2.asr.AsrHotwords.staticWords()));
        } catch (Throwable t) {
            Log.w(TAG, "热词注册失败: " + t);
        }
        reg(g, "asr.hotword_prefix", com.onesay.assistant.v2.asr.AsrHotwords.HOTWORD_PROMPT_PREFIX);
        if (app == null && agentPromptFallback != null) reg(g, "agent_prompt.md", agentPromptFallback);
        else try { reg(g, "agent_prompt.md", com.onesay.assistant.v2.prompt.PromptSource.agentPrompt(app)); } catch (Throwable t) { Log.w(TAG, "agent_prompt 注册失败: " + t); }
        try {
            if (app != null) {
                com.onesay.assistant.v2.router.PromptLibrary lib = com.onesay.assistant.v2.router.PromptLibrary.getInstance(app);
                reg(g, "L2.ask.output", lib.get(com.onesay.assistant.v2.router.PromptLibrary.T_ASK_OUTPUT));
                reg(g, "L2.do.manual", lib.get(com.onesay.assistant.v2.router.PromptLibrary.T_DO_MANUAL));
            }
        } catch (Throwable t) { Log.w(TAG, "PromptLibrary 注册失败: " + t); }
        reg(g, "L2.ask.output.default", com.onesay.assistant.v2.router.PromptLibrary.Defaults.ASK_OUTPUT);
        reg(g, "L2.do.manual.default", com.onesay.assistant.v2.router.PromptLibrary.Defaults.DO_MANUAL);
        try { reg(g, "research", com.onesay.assistant.v2.agent.ResearchRunner.SYSTEM); } catch (Throwable ignore) {}
        try { reg(g, "brief.detail", com.onesay.assistant.v2.brief.BriefDetail.systemPrompt()); } catch (Throwable ignore) {}
        try { reg(g, "soul.flush", com.onesay.assistant.v2.soul.SoulPrompts.FLUSH_SYSTEM); } catch (Throwable ignore) {}
        try { reg(g, "agentloop.untrusted", com.onesay.assistant.v2.router.AgentLoop.UNTRUSTED_RULE); } catch (Throwable ignore) {}
        try { reg(g, "app_choice", com.onesay.assistant.v2.roles.AppChoiceSpec.PROMPT_RULE); } catch (Throwable ignore) {}
        try { reg(g, "worth_telling", com.onesay.assistant.v2.callback.WorthTelling.PROMPT_SUFFIX); } catch (Throwable ignore) {}
        long ms = (System.nanoTime() - t0) / 1000000L;
        try {
            Log.i(TAG, "[LEAK-GUARD] 已就绪：热词 " + g.hotwordCount() + " 个、强指纹 " + g.strongFingerprintCount()
                    + "、弱指纹 " + g.weakFingerprintCount() + "，来源 " + g.promptSources() + "，开关=" + (g.isEnabled() ? "开" : "关")
                    + "（" + ms + "ms）");
        } catch (Throwable ignore) {}
    }

    private static void reg(PromptLeakGuard g, String src, String text) {
        if (text == null) return;
        try { g.registerSystemPrompt(src, text); } catch (Throwable ignore) {}
    }

    /** 运行时再注册一段系统提示词（例如热加载了新模板）。 */
    public static void registerPrompt(String src, String text) {
        reg(PromptLeakGuard.global(), src, text);
    }

    /** 测试：重置初始化标记。 */
    public static void resetForTest() {
        sInited = false;
        sSwitchReadAt = 0L;
        sApp = null;
        sHits = 0;
    }
}
