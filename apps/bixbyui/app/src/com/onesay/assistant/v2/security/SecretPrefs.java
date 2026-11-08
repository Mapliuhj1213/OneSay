package com.onesay.assistant.v2.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 凭据（邮箱授权码、各家 API Key）在 SharedPreferences 里改存 Android Keystore 密文。
 *
 * <ul>
 *   <li>读：{@link #get}——密文解密；老版本留下的明文照常返回，并<b>就地迁移</b>成密文（回读比对一致才写）；</li>
 *   <li>写：{@link #put}——加密失败（Keystore 不可用）就<b>不保存</b>（不落明文），日志记一次 {@code [SECRET]}；</li>
 *   <li>启动时 {@link #migrateAll} 把登记在 {@link #REGISTRY} 的键一次迁完；</li>
 *   <li>日志、调试状态都只说「未配置 / 明文 / 已加密 / 解不开」，绝不打印值。</li>
 * </ul>
 * 回滚到旧版 APK 时旧版不认识 {@code ks1:} 前缀 → 需要还原迁移前的数据备份或重新填写（见 stage-20261011 待确认事项表）。
 */
public final class SecretPrefs {

    private static final String TAG = "SecretPrefs";

    /** 登记的凭据键：prefs 文件名 → 键。新加凭据记得登记（{@code SecretRegistryTest} 守住这张表）。 */
    public static final String[][] REGISTRY = {
            {"bixby_llm_cfg", "deepseek_key"},
            {"bixby_asr_cfg", "ak_id"},
            {"bixby_asr_cfg", "ak_secret"},
            {"bixby_asr_cfg", "app_key"},
            {"bixby_asr_cfg", "manual_token"},
            {"bixby_asr_cfg", "dashscope_key"},
            {"bixby_asr_cfg", "remote_token_url"},   // r2/fixes 4：代理签发地址可能带 token 参数

            {"bixby_mail_cfg", "pass"},
            {"bixby_search_cfg", "tavily_key"},
            {"bixby_search_cfg", "bocha_key"},
            {"bixby_rain", "qweather_key"},    // ★ feat/rain-minutely 和风天气 API Key
            {"bixby_rain", "qweather_host"},   // ★ 私有 API Host（覆盖 local-config 里的默认值）
            {"bixby_pclink", "pair_key"},      // ★ feat/pclink 电脑联动配对密钥（32 字节，base64url）
            {"bixby_vyrse", "vyrse_token"},  // ★ compat-vyrse VYRSE 电脑发给本机的 key（电脑只存哈希）
    };

    private static volatile SecretCodec sCodec;
    private static volatile boolean sWarnedFallback;
    /** 密文 → 明文 的进程内缓存（避免每次 LLM 请求都走一遍 Keystore）。键是密文本身，换值自然失效。 */
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<String, String>();

    /** LOGIC-9a：每个键只告警一次。 */
    private static final java.util.Set<String> WARNED_PLAIN = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** LOGIC-9a：键是否登记在 {@link #REGISTRY}。 */
    public static boolean isRegistered(String key) {
        if (key == null) return false;
        for (String[] row : REGISTRY) if (key.equals(row[1])) return true;
        return false;
    }

    /** 仅测试用：明文告警记录（键名）。 */
    static boolean warnedPlain(String key) {
        return WARNED_PLAIN.contains(key);
    }

    private SecretPrefs() {}

    static SecretCodec codec() {
        SecretCodec c = sCodec;
        if (c == null) {
            synchronized (SecretPrefs.class) {
                if (sCodec == null) sCodec = new KeystoreCodec();
                c = sCodec;
            }
        }
        return c;
    }

    /** 仅测试用：替换加解密实现（null = 恢复真机 Keystore）。 */
    public static void setCodecForTest(SecretCodec c) {
        sCodec = c;
        CACHE.clear();
        sWarnedFallback = false;
    }

    public static String get(SharedPreferences sp, String key) {
        if (sp == null) return "";
        String stored;
        try { stored = sp.getString(key, ""); } catch (ClassCastException e) { return ""; }
        if (stored == null || stored.length() == 0) return "";
        if (SecretValues.isEncrypted(stored)) {
            String hit = CACHE.get(stored);
            if (hit != null) return hit;
            SecretValues.Read r = SecretValues.decode(stored, codec());
            if (r.state == SecretValues.State.UNDECRYPTABLE) {
                Log.w(TAG, "[SECRET] " + key + " 已加密但解不开（数据被还原到另一次安装 / Keystore 被清？）→ 视为未配置，请重新填写");
                return "";
            }
            CACHE.put(stored, r.value);
            return r.value;
        }
        // 登记键读到明文 → 告警（老版本遗留，或 prefs 被 root / 同 uid 代码改写）。只打键名，不打值。
        if (isRegistered(key) && WARNED_PLAIN.add(key)) {
            Log.w(TAG, "[SECRET] [SEC] 登记的凭据键 " + key + " 读到明文（老版本遗留，或配置文件被外部改写？）→ 就地迁移成密文");
        }
        // 老版本明文 → 就地迁移（失败就保持明文，不影响本次返回值）
        migrateOne(sp, key, stored);
        return stored;
    }

    public static void put(SharedPreferences.Editor e, String key, String plain) {
        if (e == null) return;
        SecretValues.Write w = SecretValues.encode(plain == null ? "" : plain, codec());
        if (!w.encrypted && w.error != null) {
            // OneSay：加密失败就不保存（宁可让用户重填，也不落明文）
            warnFallback(key, w.error);
            e.remove(key);
            return;
        }
        if (w.encrypted) CACHE.put(w.stored, plain);
        e.putString(key, w.stored);
    }

    public static SecretValues.State state(SharedPreferences sp, String key) {
        if (sp == null) return SecretValues.State.EMPTY;
        String stored;
        try { stored = sp.getString(key, ""); } catch (ClassCastException ex) { return SecretValues.State.EMPTY; }
        if (stored == null || stored.length() == 0) return SecretValues.State.EMPTY;
        if (!SecretValues.isEncrypted(stored)) return SecretValues.State.PLAIN;
        if (CACHE.containsKey(stored)) return SecretValues.State.ENCRYPTED;
        return SecretValues.decode(stored, codec()).state;
    }

    /** @return true = 这次真的写成了密文 */
    static boolean migrateOne(SharedPreferences sp, String key, String plain) {
        SecretValues.Write w = SecretValues.encode(plain, codec());
        if (!w.encrypted) {
            if (w.error != null) warnFallback(key, w.error);
            return false;
        }
        // commit()：迁移是一次性的，要确定落盘；写之前再看一眼值没被别处改掉
        String now = sp.getString(key, "");
        if (!plain.equals(now)) return false;
        boolean ok = sp.edit().putString(key, w.stored).commit();
        if (ok) {
            CACHE.put(w.stored, plain);
            Log.i(TAG, "[SECRET] " + key + " 明文 → Keystore 密文（已回读校验）");
        }
        return ok;
    }

    /** 启动时调用：把登记的键全部迁移。返回本次迁移的个数。异常一律吞掉（只记日志），不影响启动。 */
    public static int migrateAll(Context ctx) {
        int n = 0;
        try {
            Context app = ctx.getApplicationContext();
            for (String[] row : REGISTRY) {
                SharedPreferences sp = app.getSharedPreferences(row[0], Context.MODE_PRIVATE);
                String stored = sp.getString(row[1], "");
                if (stored != null && stored.length() > 0 && !SecretValues.isEncrypted(stored)) {
                    if (migrateOne(sp, row[1], stored)) n++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[SECRET] migrateAll 异常（保持原样）：" + t.getClass().getSimpleName());
        }
        return n;
    }

    /** 调试 / 控制台：每个登记键一行状态，不含值。 */
    public static String status(Context ctx) {
        StringBuilder sb = new StringBuilder();
        Context app = ctx.getApplicationContext();
        for (String[] row : REGISTRY) {
            SharedPreferences sp = app.getSharedPreferences(row[0], Context.MODE_PRIVATE);
            sb.append(row[0]).append('/').append(row[1]).append(" = ")
              .append(SecretValues.describe(state(sp, row[1]))).append('\n');
        }
        return sb.toString();
    }

    private static void warnFallback(String key, String why) {
        if (sWarnedFallback) return;
        sWarnedFallback = true;
        Log.w(TAG, "[SECRET] Keystore 加密不可用（" + why + "），" + key + " 等凭据不保存（不落明文），请稍后重试");
    }
}
