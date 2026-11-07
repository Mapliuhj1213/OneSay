package com.onesay.assistant.v2.asr;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 阿里云语音识别配置（持久化于 SharedPreferences）。
 *
 * <h3>⚠️ 安全须知 —— 请务必先读这段</h3>
 *
 * 阿里云官方明确要求：<b>移动端不得直接使用 AccessKey</b>，AK/SK 应只存在于服务端，
 * 由服务端签发 Token 下发给移动端。
 *
 * 本框架支持两种接入方式，请按你的风险偏好选择：
 *
 * <ol>
 *   <li><b>直连模式（本类当前实现的默认方式）</b>：AK/SK 存在设备上。
 *       方便、零服务端成本，但 <b>AK/SK 一旦泄露 = 账号下该 RAM 子账号的全部权限失守</b>
 *       （反编译 APK / root 后读 SharedPreferences 都能拿到）。
 *       <br>→ 如果走这条路，<b>务必创建一个只授予 {@code AliyunNLSFullAccess} 的 RAM 子账号</b>，
 *       绝不要用主账号 AK，把爆炸半径限制在"语音服务被刷"这一件事上。
 *       阿里云控制台 → 访问控制 RAM → 创建用户（只勾选编程访问）→ 授权 AliyunNLSFullAccess。
 *
 *   <li><b>代理模式（推荐，本框架已留好扩展点）</b>：设备只存一个你自建服务的地址，
 *       Token 由你的服务端签发。实现方式见 {@link AliyunTokenManager} 的
 *       {@code setRemoteTokenUrl()} —— 填上地址即自动切换到代理模式，
 *       服务端只需返回 {@code {"token":"..","expireTime":1730000000}}。
 *   </li>
 * </ol>
 *
 * <p>另外还有第三种最省事的过渡方案：直接在控制台点"获取临时 AccessToken"，
 * 把 Token 填到 {@link #setManualToken} —— 有效期 24 小时，适合先跑通链路，
 * 不适合长期使用（每天要手动换一次）。
 */
public final class AliyunAsrConfig {

    private static final String PREF_NAME = "bixby_asr_cfg";

    private static final String K_ENABLED = "enabled";
    private static final String K_MODE = "mode";
    private static final String K_AK_ID = "ak_id";
    private static final String K_AK_SECRET = "ak_secret";
    private static final String K_APP_KEY = "app_key";
    private static final String K_MANUAL_TOKEN = "manual_token";
    private static final String K_REGION = "region";
    private static final String K_TIMEOUT_MS = "timeout_ms";
    private static final String K_REMOTE_TOKEN_URL = "remote_token_url";
    private static final String K_NLS_GATEWAY = "nls_gateway_url";
    private static final String K_NLS_TOKEN_URL = "nls_token_url";
    private static final String K_REALTIME_URL = "realtime_url";
    private static final String K_ASR_LANG = "asr_language";

    /** ★ 百炼/DashScope 的 API Key（`sk-...`），与 NLS 的 AK/SK 是**两套完全不同的凭据**。 */
    private static final String K_DASHSCOPE_KEY = "dashscope_key";
    /** DashScope 识别模型。qwen3-asr-flash 是 2026-10-02 实测唯一稳过 OpenAI 兼容端点的。 */
    private static final String K_DASHSCOPE_MODEL = "dashscope_model";

    public static final String DEFAULT_DASHSCOPE_MODEL = "qwen3-asr-flash";
    /** OpenAI 兼容端点：一个纯 Bearer + JSON 的同步接口，比 NLS 的 POP 签名简单得多。 */
    /** 预置值；实际请求地址取 {@link com.onesay.assistant.v2.net.LlmEndpoints#dashscopeChatUrl()}（用户可改）。 */
    public static final String DASHSCOPE_COMPAT_URL = com.onesay.assistant.v2.net.LlmEndpoints.DASHSCOPE_PRESET + "/chat/completions";

    /** 默认地域：华东2（上海），与 nls-meta / nls-gateway 的默认域名一致 */
    public static final String DEFAULT_REGION = "cn-shanghai";
    /** 默认超时：这句话说完到返回，给足网络往返余量；超时即降级离线 */
    public static final int DEFAULT_TIMEOUT_MS = 6000;

    private final Context mContext;

    public AliyunAsrConfig(Context context) {
        mContext = context.getApplicationContext();
        com.onesay.assistant.v2.net.LlmEndpoints.load(mContext);   // 用户配置的 DashScope 兼容模式端点
    }

    private SharedPreferences sp() {
        return mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // 开关与策略
    // ------------------------------------------------------------------

    /**
     * 云端 ASR 总开关。
     *
     * <b>默认 false</b> —— 这是刻意的：框架落地不应改变现有行为。
     * 你把 key 配好、验证通过之后再打开。
     */
    public boolean isEnabled() {
        return sp().getBoolean(K_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        sp().edit().putBoolean(K_ENABLED, enabled).apply();
    }

    /**
     * 调度模式，取值见 {@link AsrRouter.Mode}。
     * 默认 {@code OFFLINE_FIRST_CLOUD_FALLBACK}：离线先跑，离线搞不定才用云端。
     */
    public AsrRouter.Mode getMode() {
        String v = sp().getString(K_MODE, AsrRouter.Mode.OFFLINE_FIRST_CLOUD_FALLBACK.name());
        try {
            return AsrRouter.Mode.valueOf(v);
        } catch (Throwable ignore) {
            return AsrRouter.Mode.OFFLINE_FIRST_CLOUD_FALLBACK;
        }
    }

    public void setMode(AsrRouter.Mode mode) {
        sp().edit().putString(K_MODE, mode.name()).apply();
    }

    // ------------------------------------------------------------------
    // 凭据
    // ------------------------------------------------------------------

    public String getAccessKeyId() {
        return com.onesay.assistant.v2.security.SecretPrefs.get(sp(), K_AK_ID);
    }

    public void setAccessKeyId(String v) {
        { SharedPreferences.Editor e = sp().edit(); com.onesay.assistant.v2.security.SecretPrefs.put(e, K_AK_ID, nz(v)); e.apply(); }
    }

    public String getAccessKeySecret() {
        return com.onesay.assistant.v2.security.SecretPrefs.get(sp(), K_AK_SECRET);
    }

    public void setAccessKeySecret(String v) {
        { SharedPreferences.Editor e = sp().edit(); com.onesay.assistant.v2.security.SecretPrefs.put(e, K_AK_SECRET, nz(v)); e.apply(); }
    }

    /** 智能语音交互控制台的「项目 AppKey」——注意它和 AK 是两回事，别填混。 */
    public String getAppKey() {
        return com.onesay.assistant.v2.security.SecretPrefs.get(sp(), K_APP_KEY);
    }

    public void setAppKey(String v) {
        { SharedPreferences.Editor e = sp().edit(); com.onesay.assistant.v2.security.SecretPrefs.put(e, K_APP_KEY, nz(v)); e.apply(); }
    }

    /** 手填 Token（控制台"获取临时 AccessToken"），有效期 24h。有 AK/SK 时优先用 AK/SK 自动签发。 */
    public String getManualToken() {
        return com.onesay.assistant.v2.security.SecretPrefs.get(sp(), K_MANUAL_TOKEN);
    }

    public void setManualToken(String v) {
        { SharedPreferences.Editor e = sp().edit(); com.onesay.assistant.v2.security.SecretPrefs.put(e, K_MANUAL_TOKEN, nz(v)); e.apply(); }
    }

    public String getRegion() {
        String r = sp().getString(K_REGION, DEFAULT_REGION);
        return sanitizeRegion(r);
    }

    public void setRegion(String v) {
        if (v != null && v.trim().length() > 0 && !isValidRegion(v.trim())) {
            android.util.Log.w("AliyunAsrConfig", "[SEC] region 格式非法，拒绝写入（只允许 [a-z]{2}-[a-z]+）");
            return;
        }
        sp().edit().putString(K_REGION, nz(v)).apply();
    }

    /**
     * region 白名单 {@code [a-z]{2}-[a-z]+}（cn-shanghai / cn-beijing / ap-southeast …）。
     * region 拼进 {@code https://nls-gateway-<region>.aliyuncs.com}，填 {@code x@evil.com/#} 会把录音 + X-NLS-Token 发到 evil.com。
     */
    static final java.util.regex.Pattern REGION_OK = java.util.regex.Pattern.compile("[a-z]{2}-[a-z]+");

    public static boolean isValidRegion(String r) {
        return r != null && REGION_OK.matcher(r).matches();
    }

    /** 非法 / 空 → 默认 region（并打告警，不打印原值）。 */
    static String sanitizeRegion(String r) {
        if (r == null || r.trim().length() == 0) return DEFAULT_REGION;
        String t = r.trim();
        if (isValidRegion(t)) return t;
        try {
            android.util.Log.w("AliyunAsrConfig", "[SEC] prefs 里的 region 格式非法（疑似被篡改），已改用默认 " + DEFAULT_REGION);
        } catch (Throwable ignored) {
        }
        return DEFAULT_REGION;
    }

    /** ★ LOGIC-9a：代理签发地址必须是 https 且有 host。 */
    public static boolean isAllowedRemoteTokenUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (!u.regionMatches(true, 0, "https://", 0, 8)) return false;
        return com.onesay.assistant.v2.executor.ConfirmFacts.host(u).length() > 0;
    }

    public int getTimeoutMs() {
        // ★ R13：有界（1–15 s）—— 设置里写 0 / 很大的值都不能让识别无限等
        return com.onesay.assistant.v2.wake.CaptureSessionPolicy.clampAsrTimeoutMs(sp().getInt(K_TIMEOUT_MS, DEFAULT_TIMEOUT_MS));
    }

    public void setTimeoutMs(int ms) {
        sp().edit().putInt(K_TIMEOUT_MS, ms).apply();
    }

    /** 代理模式：你自建服务端签发 Token 的地址；非空即启用代理模式，忽略本地 AK/SK。 */
    public String getRemoteTokenUrl() {
        // r2/fixes 4：地址可能带 token 参数 → Keystore 密文（SecretPrefs 登记）。旧版明文读到时就地迁移成密文、覆盖掉明文
        return com.onesay.assistant.v2.security.SecretPrefs.get(sp(), K_REMOTE_TOKEN_URL);
    }

    public void setRemoteTokenUrl(String v) {
        if (v != null && v.trim().length() > 0 && !isAllowedRemoteTokenUrl(v)) {   // ★ LOGIC-9a：只收 https
            android.util.Log.w("AliyunAsrConfig", "[SEC] remote_token_url 不是 https，拒绝写入");
            return;
        }
        { SharedPreferences.Editor e = sp().edit(); com.onesay.assistant.v2.security.SecretPrefs.put(e, K_REMOTE_TOKEN_URL, nz(v)); e.apply(); }
    }

    // ------------------------------------------------------------------
    // 百炼 / DashScope（★ 与 NLS 是两套独立凭据，别混）
    // ------------------------------------------------------------------

    /**
     * 百炼 DashScope 的 API Key（形如 {@code sk-...}）。
     *
     * <p>★ 重要：这与 NLS 的 AccessKeyId/Secret 是**完全不同的两套凭据**。
     * 用户在百炼控制台创建 API Key 拿到的是这个；用 NLS 的 AK 打 DashScope 会 401，
     * 反之用 DashScope 的 Key 打 NLS 也会失败。二者不能互换。
     */
    public String getDashScopeKey() {
        return com.onesay.assistant.v2.security.SecretPrefs.get(sp(), K_DASHSCOPE_KEY);
    }

    public void setDashScopeKey(String v) {
        { SharedPreferences.Editor e = sp().edit(); com.onesay.assistant.v2.security.SecretPrefs.put(e, K_DASHSCOPE_KEY, nz(v)); e.apply(); }
    }

    public String getDashScopeModel() {
        String m = sp().getString(K_DASHSCOPE_MODEL, DEFAULT_DASHSCOPE_MODEL);
        return (m == null || m.trim().length() == 0) ? DEFAULT_DASHSCOPE_MODEL : m.trim();
    }

    public void setDashScopeModel(String v) {
        putModel(K_DASHSCOPE_MODEL, v);
    }

    /** 识屏 / 看图模型（prefs 键与 ScreenVisionEngine 一致）。 */
    public String getVisionModel() {
        String m = sp().getString("vision_model", "");
        return m == null ? "" : m.trim();
    }

    public void setVisionModel(String v) { putModel("vision_model", v); }

    // ------------------------------------------------------------------
    // ★ R14 流式识别（qwen3-asr-flash-realtime，WebSocket + 服务端 VAD）
    // ------------------------------------------------------------------

    /** 开关：true（默认）= 按下键就建流式连接，失败自动回退非流式；false = 只用非流式（旧行为）。 */
    public static final String K_REALTIME = "realtime";
    public static final String K_REALTIME_MODEL = "realtime_model";
    /** 服务端 VAD 尾静音（毫秒），默认 700（r2/input：450 → 700，只管分段），夹在 [300, 1200]。 */
    public static final String K_REALTIME_SILENCE_MS = "realtime_silence_ms";

    public boolean isRealtimeEnabled() {
        return sp().getBoolean(K_REALTIME, true);
    }

    public void setRealtimeEnabled(boolean on) {
        sp().edit().putBoolean(K_REALTIME, on).apply();
    }

    public String getRealtimeModel() {
        String m = sp().getString(K_REALTIME_MODEL, RealtimeAsrSession.DEFAULT_MODEL);
        return (m == null || m.trim().length() == 0) ? RealtimeAsrSession.DEFAULT_MODEL : m.trim();
    }

    /**
     * ★ r2/input 一次性迁移：服务端 VAD 默认 450 → 700。控制台写下的显式 450（= 旧默认）撤掉，别的值尊重用户。
     * @return true = 这次撤掉了
     */
    public boolean migrateR2RealtimeSilence() {
        if (sp().getBoolean("r2_rt_silence_migrated", false)) return false;
        boolean old = sp().contains(K_REALTIME_SILENCE_MS) && sp().getInt(K_REALTIME_SILENCE_MS, -1) == 450;
        android.content.SharedPreferences.Editor ed = sp().edit();
        if (old) ed.remove(K_REALTIME_SILENCE_MS);
        ed.putBoolean("r2_rt_silence_migrated", true).apply();
        return old;
    }

    public int getRealtimeSilenceMs() {
        int v = sp().getInt(K_REALTIME_SILENCE_MS, RealtimeAsrSession.DEFAULT_SILENCE_MS);
        return v < 300 ? 300 : (v > 1200 ? 1200 : v);
    }

    /** DashScope 通道是否具备发起识别的条件。廉价只读。 */
    public boolean hasDashScopeKey() {
        return getDashScopeKey().length() > 0;
    }

    // ------------------------------------------------------------------
    // 工厂
    // ------------------------------------------------------------------

    /** 预置：智能语音 NLS 一句话识别网关（按地域拼）。 */
    public static String presetAsrEndpoint(String region) {
        return "https://nls-gateway-" + sanitizeRegion(region) + ".aliyuncs.com/stream/v1/asr";
    }

    /** 预置：NLS Token 签发地址（阿里云文档：国内统一 cn-shanghai）。 */
    public static final String PRESET_TOKEN_ENDPOINT = "https://nls-meta.cn-shanghai.aliyuncs.com/";

    /** 智能语音 NLS 一句话识别地址：用户在「API 密钥」页填的（只收 https）优先，否则按地域预置。 */
    public String getAsrEndpoint() {
        String u = sp().getString(K_NLS_GATEWAY, "");
        return u.length() > 0 ? u : presetAsrEndpoint(getRegion());
    }

    /** NLS Token 签发地址：用户填的（只收 https）优先，否则预置。 */
    public String getTokenEndpoint() {
        String u = sp().getString(K_NLS_TOKEN_URL, "");
        return u.length() > 0 ? u : PRESET_TOKEN_ENDPOINT;
    }

    public boolean hasCustomAsrEndpoint() { return sp().getString(K_NLS_GATEWAY, "").length() > 0; }
    public boolean hasCustomTokenEndpoint() { return sp().getString(K_NLS_TOKEN_URL, "").length() > 0; }

    /** 设识别网关（空 = 恢复预置）。@return null = 已保存；否则是没保存的原因 */
    public String setAsrEndpoint(String v) { return putUrl(K_NLS_GATEWAY, v, "https", true); }

    /** 设 Token 签发地址（空 = 恢复预置）。 */
    public String setTokenEndpoint(String v) { return putUrl(K_NLS_TOKEN_URL, v, "https", false); }

    /** Token 签名里的 RegionId：从签发地址 nls-meta.&lt;region&gt;.aliyuncs.com 推出来，推不出按 cn-shanghai。 */
    public static String tokenRegionFor(String tokenEndpoint) {
        String h = com.onesay.assistant.v2.net.LlmEndpoints.host(tokenEndpoint);
        if (h != null && h.startsWith("nls-meta.") && h.endsWith(".aliyuncs.com")) {
            String r = h.substring("nls-meta.".length(), h.length() - ".aliyuncs.com".length());
            if (r.matches("[a-z]{2}-[a-z]+(-[0-9]+)?")) return r;
        }
        return DEFAULT_REGION;
    }

    // ---- 实时识别（百炼 realtime WebSocket）：地址 / 语言可配

    /** 实时识别地址（不含 model；只收 wss）。 */
    public String getRealtimeEndpoint() {
        String u = sp().getString(K_REALTIME_URL, "");
        return u.length() > 0 ? u : RealtimeAsrSession.DEFAULT_ENDPOINT;
    }

    public boolean hasCustomRealtimeEndpoint() { return sp().getString(K_REALTIME_URL, "").length() > 0; }

    public String setRealtimeEndpoint(String v) { return putUrl(K_REALTIME_URL, v, "wss", true); }

    /** 识别语言（预置 zh；空串 = 服务端自动判断）。 */
    public String getAsrLanguage() {
        return sp().getString(K_ASR_LANG, RealtimeAsrSession.DEFAULT_LANGUAGE);
    }

    public String setAsrLanguage(String v) {
        String l = nz(v);
        if (l.length() > 0 && !com.onesay.assistant.v2.net.UserEndpoint.validId(l)) return "语言代码只能是字母、数字和 - _ .（如 zh、en、ja），留空 = 自动";
        sp().edit().putString(K_ASR_LANG, l).apply();
        return null;
    }

    /** 完整的实时识别 wss 地址（同时把识别语言交给会话）。 */
    public String getRealtimeUrl() {
        RealtimeAsrSession.setLanguage(getAsrLanguage());
        return RealtimeAsrSession.urlFor(getRealtimeEndpoint(), getRealtimeModel());
    }

    public void setRealtimeModel(String v) { putModel(K_REALTIME_MODEL, v); }

    /** 模型名统一校验：空 = 恢复预置；非法不写。 */
    private void putModel(String key, String v) {
        String m = nz(v);
        if (m.length() == 0) { sp().edit().remove(key).apply(); return; }
        if (!com.onesay.assistant.v2.net.UserEndpoint.validId(m)) return;
        sp().edit().putString(key, m).apply();
    }

    private String putUrl(String key, String v, String scheme, boolean allowQuery) {
        String s = nz(v);
        if (s.length() == 0) { sp().edit().remove(key).apply(); return null; }
        String n = com.onesay.assistant.v2.net.UserEndpoint.normalize(s, scheme, allowQuery);
        if (n == null) return "必须是 " + scheme + ":// 开头的完整地址（不带用户名 / #片段）";
        sp().edit().putString(key, n).apply();
        return null;
    }

    /** 是否有可用于签发 Token 的凭据（AK/SK、代理地址、或手填 Token 三者之一）。 */
    public boolean hasCredential() {
        if (getRemoteTokenUrl().length() > 0) return true;
        if (getAccessKeyId().length() > 0 && getAccessKeySecret().length() > 0) return true;
        return getManualToken().length() > 0;
    }

    /** 配置是否完整到可以真正发起识别（凭据 + AppKey）。 */
    public boolean isComplete() {
        return hasCredential() && getAppKey().length() > 0;
    }

    /** 一行摘要，用于日志与自测输出。<b>已脱敏</b>，不会泄露完整凭据。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(isEnabled());
        sb.append(", mode=").append(getMode().name());
        sb.append(", appKey=").append(mask(getAppKey(), 4));
        if (getRemoteTokenUrl().length() > 0) {
            sb.append(", 凭据=代理模式(地址已配置)");   // ★ M-3：代理地址可能带 token 参数，不打
        } else if (getAccessKeyId().length() > 0) {
            sb.append(", 凭据=AK直连(AKId=").append(mask(getAccessKeyId(), 4)).append(")");
        } else if (getManualToken().length() > 0) {
            sb.append(", 凭据=手填Token(").append(mask(getManualToken(), 4)).append(")");
        } else {
            sb.append(", 凭据=未配置");
        }
        sb.append(", region=").append(getRegion());
        sb.append(", timeout=").append(getTimeoutMs()).append("ms");
        sb.append(", DashScope=").append(hasDashScopeKey()
                ? (mask(getDashScopeKey(), 6) + "/" + getDashScopeModel()) : "未配置");
        sb.append(", 流式=").append(isRealtimeEnabled() ? getRealtimeModel() + "/vad" + getRealtimeSilenceMs() + "ms" : "关");
        return sb.toString();
    }

    /** 清空所有凭据（保留开关与策略）。用于换账号或安全擦除。 */
    public void clearCredentials() {
        sp().edit()
                .remove(K_AK_ID).remove(K_AK_SECRET)
                .remove(K_APP_KEY).remove(K_MANUAL_TOKEN)
                .remove(K_REMOTE_TOKEN_URL).remove(K_DASHSCOPE_KEY)
                .apply();
    }

    // ------------------------------------------------------------------

    private static String nz(String s) {
        return (s == null) ? "" : s.trim();
    }

    /** describe() 会进日志（WakeService 启动即打）—— 只说有没有，不露前后缀与长度。 */
    private static String mask(String s, int keep) {
        return com.onesay.assistant.v2.util.TextUtil.secretState(s);
    }
}
