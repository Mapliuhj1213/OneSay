package com.onesay.assistant.v2.net;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ★ borrow-1112：备用模型的配置（prefs bixby_llm_backup）。出厂 = 关（没配置 → 主模型出错照旧报错）。
 * <ul>
 *   <li>{@link #P_OFF} 关；</li>
 *   <li>{@link #P_DASHSCOPE} 通义千问（百炼 OpenAI 兼容端点 {@link LlmClient#DASHSCOPE_COMPAT_URL}，模型默认 {@link #DASHSCOPE_MODEL}）；
 *       Key 优先用这里单独填的，没填就用「语音识别 Key」同一把百炼 Key；</li>
 *   <li>{@link #P_CUSTOM} 自定义 OpenAI 兼容端点（https 地址 + 模型名 + Key）。</li>
 * </ul>
 * Key 加密存（SecretPrefs，键 backup_key，已登记）。
 */
public final class BackupModelConfig implements LlmFallback.Source {

    public static final String PREFS = "bixby_llm_backup";
    public static final String K_PROVIDER = "provider", K_URL = "url", K_MODEL = "model", K_KEY = "backup_key";
    public static final String P_OFF = "off", P_DASHSCOPE = "dashscope", P_CUSTOM = "custom";
    public static final String DASHSCOPE_MODEL = "qwen-plus";

    private final Context mApp;

    public BackupModelConfig(Context ctx) {
        mApp = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
    }

    public SharedPreferences sp() {
        return mApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String provider() {
        return sp().getString(K_PROVIDER, P_OFF);
    }

    public String model() {
        String m = sp().getString(K_MODEL, "");
        if (m.length() == 0 && P_DASHSCOPE.equals(provider())) return DASHSCOPE_MODEL;
        return m;
    }

    public String url() {
        return P_DASHSCOPE.equals(provider()) ? LlmClient.DASHSCOPE_COMPAT_URL : sp().getString(K_URL, "");
    }

    public String ownKey() {
        return com.onesay.assistant.v2.security.SecretPrefs.get(sp(), K_KEY);
    }

    public String key() {
        String k = ownKey();
        if (k != null && k.trim().length() > 0) return k.trim();
        if (P_DASHSCOPE.equals(provider())) {
            try {
                return new com.onesay.assistant.v2.asr.AliyunAsrConfig(mApp).getDashScopeKey();
            } catch (Throwable t) {
                return "";
            }
        }
        return "";
    }

    /** 保存（provider 不认识 → 关）。key 为 null = 不改 Key。 */
    public void save(String provider, String url, String model, String key) {
        String p = P_DASHSCOPE.equals(provider) || P_CUSTOM.equals(provider) ? provider : P_OFF;
        SharedPreferences.Editor e = sp().edit().putString(K_PROVIDER, p).putString(K_URL, url == null ? "" : url.trim())
                .putString(K_MODEL, model == null ? "" : model.trim());
        if (key != null) com.onesay.assistant.v2.security.SecretPrefs.put(e, K_KEY, key.trim());
        e.apply();
    }

    @Override public LlmFallback.Backup current() {
        String p = provider();
        if (P_OFF.equals(p)) return null;
        String label = P_DASHSCOPE.equals(p) ? "通义千问 " + model() : model();
        return new LlmFallback.Backup(url(), model(), label, new LlmClient.KeyProvider() {
            @Override public String apiKey() {
                return key();
            }
        });
    }

    /** 设置页的一句话。 */
    public String summary() {
        String p = provider();
        if (P_OFF.equals(p)) return "未配置 · 主模型出错就照旧报错";
        LlmFallback.Backup b = current();
        if (b == null || !b.usable()) return "配置不完整（缺 Key / 地址 / 模型名）";
        return (P_DASHSCOPE.equals(p) ? "通义千问（百炼）" : "自定义") + " · " + model();
    }
}
