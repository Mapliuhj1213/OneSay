package com.onesay.assistant.v2.router.ctx;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ★ int/1102：上下文预算的 prefs（{@code bixby_conv}）。
 * <ul>
 *   <li>{@code ctx_budget_tokens}（int）：会话历史预算，缺省 / ≤0 = 32,768；debug 广播 {@code --ei conv_budget_tokens N}；</li>
 *   <li>{@code model_windows}（string）：模型窗口覆盖「模型=窗口,…」；debug 广播 {@code --es model_windows "deepseek-flash=1048576"}。</li>
 *   <li>{@code chat_model}（string）：对话模型名（缺省 deepseek-flash，见 {@code net.LlmModels}）；debug 广播 {@code --es chat_model deepseek-v4-pro}。</li>
 * </ul>
 * FloatingSheetService.onCreate 与每次面板离屏、debug 广播写入后调用 {@link #apply}。
 */
public final class ContextPrefs {
    private ContextPrefs() {
    }

    public static final String PREFS = "bixby_conv", K_BUDGET = "ctx_budget_tokens", K_WINDOWS = "model_windows", K_CHAT_MODEL = "chat_model";
    /** ★ int/1102：思考模式 off / agent / all（设置 › 语音 › 思考模式；debug 广播 --es thinking off|agent|all）。缺省 off。 */
    public static final String K_THINKING = "thinking_mode";

    public static void apply(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            com.onesay.assistant.v2.router.ConversationMemory.getInstance().setBudgetTokens(sp.getInt(K_BUDGET, 0));
            ModelWindows.setOverrides(sp.getString(K_WINDOWS, ""));
            com.onesay.assistant.v2.net.LlmModels.setChat(sp.getString(K_CHAT_MODEL, ""));
            com.onesay.assistant.v2.net.ThinkingMode.setLevel(sp.getString(K_THINKING, "off"));
        } catch (Throwable ignored) {
        }
    }

    public static void setBudget(Context ctx, int tokens) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(K_BUDGET, Math.max(0, tokens)).commit();
        apply(ctx);
    }

    public static void setChatModel(Context ctx, String model) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_CHAT_MODEL, model == null ? "" : model.trim()).commit();
        apply(ctx);
    }

    /** ★ int/1102：@return 生效档位 */
    public static com.onesay.assistant.v2.net.ThinkingMode.Level setThinking(Context ctx, String level) {
        com.onesay.assistant.v2.net.ThinkingMode.Level l = com.onesay.assistant.v2.net.ThinkingMode.parse(level);
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_THINKING, com.onesay.assistant.v2.net.ThinkingMode.key(l)).commit();
        apply(ctx);
        return l;
    }

    public static void setWindows(Context ctx, String spec) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_WINDOWS, spec == null ? "" : spec).commit();
        apply(ctx);
    }
}
