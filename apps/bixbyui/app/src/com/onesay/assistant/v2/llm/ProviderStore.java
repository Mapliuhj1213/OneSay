package com.onesay.assistant.v2.llm;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.v2.security.SecretPrefs;

/**
 * 自带 Key 服务商的设置（prefs {@value #PREF}；Key 一律经 {@link SecretPrefs} 用系统密钥库加密，加密失败不落明文）。
 *
 * <ul>
 *   <li>{@code active}：主对话服务商 id；空 / {@code deepseek} = 原 DeepSeek 通道（{@link ProviderRouting} 设为 null，行为不变）；</li>
 *   <li>DeepSeek 的 Key / 地址沿用原来的 {@code bixby_llm_cfg/deepseek_key} 与「服务地址」；百炼沿用 {@code bixby_asr_cfg/dashscope_key}
 *       （同一把百炼 Key 也能调兼容模式对话），不另存一份；</li>
 *   <li>其余每家：{@code base_<id>}、{@code model_<id>} 明文（不是秘密），{@code key_<id>} 密文（已登记在 {@code SecretPrefs.REGISTRY}）。</li>
 * </ul>
 */
public final class ProviderStore {
    private ProviderStore() {}

    public static final String PREF = "bixby_llm_providers";
    static final String K_ACTIVE = "active";

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String keyPrefName(String id) {
        return "key_" + id;
    }

    public static String activeId(Context c) {
        String a = sp(c).getString(K_ACTIVE, "");
        return ProviderPreset.get(a) == null ? "" : a;
    }

    public static void setActive(Context c, String id) {
        sp(c).edit().putString(K_ACTIVE, id == null || ProviderPreset.get(id) == null ? "" : id).apply();
        apply(c);
    }

    public static String base(Context c, ProviderPreset p) {
        if (ProviderPreset.DEEPSEEK.equals(p.id)) return com.onesay.assistant.v2.net.LlmEndpoints.deepseekBase();
        String b = sp(c).getString("base_" + p.id, "");
        return b.length() > 0 ? b : p.defaultBase;
    }

    /** 保存地址；空串 = 恢复预置。@return null 已保存，否则是原因。 */
    public static String saveBase(Context c, ProviderPreset p, String v) {
        String in = v == null ? "" : v.trim();
        if (ProviderPreset.DEEPSEEK.equals(p.id)) {
            return com.onesay.assistant.v2.net.LlmEndpoints.saveDeepseek(c, in) ? done(c) : "只收 https:// 地址";
        }
        if (in.length() == 0) { sp(c).edit().remove("base_" + p.id).apply(); return done(c); }
        String n = ProviderUrls.normalize(p.kind, in);
        if (n == null) return "只收 https:// 地址，不带 ?、# 和账号";
        sp(c).edit().putString("base_" + p.id, n).apply();
        return done(c);
    }

    public static String model(Context c, ProviderPreset p) {
        if (ProviderPreset.DEEPSEEK.equals(p.id)) return com.onesay.assistant.v2.net.LlmModels.chat();
        String m = sp(c).getString("model_" + p.id, "");
        return m.length() > 0 ? m : p.defaultModel;
    }

    public static String saveModel(Context c, ProviderPreset p, String v) {
        String in = v == null ? "" : v.trim();
        if (in.length() > 0 && !ProviderUrls.validModel(in)) return "模型名只能是字母、数字和 . _ - / : @";
        if (ProviderPreset.DEEPSEEK.equals(p.id)) {
            com.onesay.assistant.v2.router.ctx.ContextPrefs.setChatModel(c, in);
            return done(c);
        }
        if (in.length() == 0) sp(c).edit().remove("model_" + p.id).apply();
        else sp(c).edit().putString("model_" + p.id, in).apply();
        return done(c);
    }

    public static String key(Context c, ProviderPreset p) {
        if (ProviderPreset.DEEPSEEK.equals(p.id)) return SecretPrefs.get(c.getSharedPreferences("bixby_llm_cfg", Context.MODE_PRIVATE), "deepseek_key");
        if (ProviderPreset.DASHSCOPE.equals(p.id)) return new com.onesay.assistant.v2.asr.AliyunAsrConfig(c).getDashScopeKey();
        return SecretPrefs.get(sp(c), keyPrefName(p.id));
    }

    public static void saveKey(Context c, ProviderPreset p, String v) {
        String k = v == null ? "" : v.trim();
        if (ProviderPreset.DEEPSEEK.equals(p.id)) {
            SharedPreferences.Editor e = c.getSharedPreferences("bixby_llm_cfg", Context.MODE_PRIVATE).edit();
            SecretPrefs.put(e, "deepseek_key", k);
            e.apply();
        } else if (ProviderPreset.DASHSCOPE.equals(p.id)) {
            new com.onesay.assistant.v2.asr.AliyunAsrConfig(c).setDashScopeKey(k);
        } else {
            SharedPreferences.Editor e = sp(c).edit();
            if (k.length() == 0) e.remove(keyPrefName(p.id));
            else SecretPrefs.put(e, keyPrefName(p.id), k);
            e.apply();
        }
        apply(c);
    }

    private static String done(Context c) {
        apply(c);
        return null;
    }

    /** 把设置读进 {@link ProviderRouting}（启动、改设置后调用；异常吞掉 = 保持原 DeepSeek 通道）。 */
    public static void apply(Context ctx) {
        try {
            final Context c = ctx.getApplicationContext();
            String id = activeId(c);
            final ProviderPreset p = ProviderPreset.get(id);
            ProviderRouting.set(routeFor(p, p == null ? null : base(c, p), p == null ? null : model(c, p), new ProviderRoute.KeySource() {
                @Override public String key() {
                    return p == null ? "" : ProviderStore.key(c, p);
                }
            }));
        } catch (Throwable t) {
            ProviderRouting.set(null);
        }
    }

    /** 纯逻辑（JVM 可测）：选中项 + 配置 → 路由；DeepSeek / 没选 / 地址或模型不合法 → null（原通道）。 */
    public static ProviderRoute routeFor(ProviderPreset p, String base, String model, ProviderRoute.KeySource key) {
        if (p == null || ProviderPreset.DEEPSEEK.equals(p.id)) return null;
        String b = ProviderUrls.normalize(p.kind, base);
        if (b == null || model == null || !ProviderUrls.validModel(model)) return null;
        return new ProviderRoute(p.id, p.kind, b, model, key);
    }
}
