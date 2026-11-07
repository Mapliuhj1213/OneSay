package com.onesay.assistant.v2.tts;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ★ r2/cloud-tts（第一阶段）：云端语音回复配置（prefs {@code bixby_tts_cfg}）。
 *
 * <ul>
 *   <li><b>不存密钥</b>：百炼 key 复用 ASR 那把（{@code AliyunAsrConfig.getDashScopeKey()}，Keystore 加密）。</li>
 *   <li>音色 / 模型 / Workspace ID 都不是秘密，但音色与 Workspace ID 绑定具体账号：默认值来自构建时注入的
 *       {@link com.onesay.assistant.v2.LocalDefaults}（refactor/oss：不再写死在源码里；本机构建用未跟踪的 local-config/，
 *       行为与之前一致）。复刻音色只能配它复刻时的模型（cosyvoice-v3.5-flash）。没有默认音色时云端朗读视为未配置。</li>
 *   <li>OneSay：云端语音是唯一朗读通道；没有 Key 或没有音色 = 不朗读（不回退系统 TTS）。开关默认开。</li>
 *   <li>服务地址可由用户自填（只收 wss://），见 {@link #setEndpoint}。</li>
 *   <li>域名：默认先走业务空间专属域名（{@code {WorkspaceId}.cn-beijing.maas.aliyuncs.com}，官方推荐）；
 *       专属域名握手被拒（401/403/404）时本进程内改走 {@code dashscope.aliyuncs.com}（见 TtsSpeaker）。</li>
 * </ul>
 */
public final class CloudTtsConfig {

    public static final String PREF = "bixby_tts_cfg";
    static final String K_ENABLED = "cloud_enabled";
    static final String K_MODEL = "model";
    static final String K_VOICE = "voice";
    static final String K_WORKSPACE = "workspace_id";
    static final String K_HOST_MODE = "host_mode";          // workspace | dashscope
    static final String K_CONNECT_MS = "connect_timeout_ms";
    static final String K_FIRST_AUDIO_MS = "first_audio_timeout_ms";
    static final String K_SAMPLE_RATE = "sample_rate";
    static final String K_ENDPOINT = "endpoint";            // 用户自填的 wss:// 合成地址（优先于 host_mode）

    public static final String DEFAULT_MODEL = "cosyvoice-v3.5-flash";
    /** 音色「c」：账号下最早创建（2026-09-28）的复刻音色，target_model = cosyvoice-v3.5-flash。 */
    public static final String DEFAULT_VOICE = com.onesay.assistant.v2.LocalDefaults.CLOUD_TTS_VOICE;
    /** 百炼业务空间 ID（北京）：不是密钥，用来拼专属域名。 */
    public static final String DEFAULT_WORKSPACE_ID = com.onesay.assistant.v2.LocalDefaults.CLOUD_TTS_WORKSPACE_ID;
    public static final String HOST_WORKSPACE = "workspace";
    public static final String HOST_DASHSCOPE = "dashscope";
    public static final int DEFAULT_SAMPLE_RATE = 24000;

    public static final String DASHSCOPE_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";

    private final SharedPreferences mSp;

    public CloudTtsConfig(Context ctx) {
        mSp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        // refactor/oss：没有任何音色（构建时没注入默认音色、用户也没填）= 云端朗读不可用，直接走模板 / 系统 TTS。
        //   本机构建注入了默认音色，这一条恒为真，行为不变。
        // OneSay：云端语音是唯一的朗读通道（没有系统 TTS 兜底）。配好音色 + Key 即默认开。
        if (getVoice().length() == 0) return false;
        return mSp.getBoolean(K_ENABLED, true);
    }

    public void setEnabled(boolean on) {
        mSp.edit().putBoolean(K_ENABLED, on).apply();
    }

    public String getModel() { return nonEmpty(mSp.getString(K_MODEL, null), DEFAULT_MODEL); }
    public String getVoice() { return nonEmpty(mSp.getString(K_VOICE, null), DEFAULT_VOICE); }
    public String getWorkspaceId() { return nonEmpty(mSp.getString(K_WORKSPACE, null), DEFAULT_WORKSPACE_ID); }
    public String getHostMode() { return nonEmpty(mSp.getString(K_HOST_MODE, null), HOST_WORKSPACE); }
    public int getConnectTimeoutMs() { return mSp.getInt(K_CONNECT_MS, CloudUtterance.DEFAULT_CONNECT_TIMEOUT_MS); }
    public int getFirstAudioTimeoutMs() { return mSp.getInt(K_FIRST_AUDIO_MS, CloudUtterance.DEFAULT_FIRST_AUDIO_TIMEOUT_MS); }
    public int getSampleRate() { return mSp.getInt(K_SAMPLE_RATE, DEFAULT_SAMPLE_RATE); }

    public void setModel(String v) { putOrClear(K_MODEL, v); }
    public void setVoice(String v) { putOrClear(K_VOICE, v); }
    public void setWorkspaceId(String v) { putOrClear(K_WORKSPACE, v); }
    public void setHostMode(String v) { putOrClear(K_HOST_MODE, v); }
    public void setConnectTimeoutMs(int ms) { mSp.edit().putInt(K_CONNECT_MS, clampTimeout(ms)).apply(); }
    public void setFirstAudioTimeoutMs(int ms) { mSp.edit().putInt(K_FIRST_AUDIO_MS, clampTimeout(ms)).apply(); }

    /** 按当前设置拼 WebSocket 地址：用户自填地址优先。 */
    public String wsUrl() {
        String ep = getEndpoint();
        if (ep.length() > 0) return ep;
        return wsUrlFor(getHostMode(), getWorkspaceId());
    }

    public String getEndpoint() { return cleanWss(mSp.getString(K_ENDPOINT, "")); }
    public boolean hasCustomEndpoint() { return getEndpoint().length() > 0; }

    /** 保存自填地址；不合法返回原因（不保存），合法返回 null。空串 = 恢复默认。 */
    public String setEndpoint(String v) {
        if (v == null || v.trim().length() == 0) { mSp.edit().remove(K_ENDPOINT).apply(); return null; }
        String c = cleanWss(v);
        if (c.length() == 0) return "只收 wss:// 地址（加密），不能带账号、?查询串或 #";
        mSp.edit().putString(K_ENDPOINT, c).apply();
        return null;
    }

    /** 只认 wss://（TLS），主机里不许有 @ / 空格，不许查询串 / 片段，长度上限 300；不合法返回 ""。 */
    public static String cleanWss(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.length() == 0 || u.length() > 300) return "";
        if (!u.toLowerCase(java.util.Locale.ROOT).startsWith("wss://")) return "";
        String rest = u.substring(6);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        if (host.length() == 0 || host.indexOf('@') >= 0 || host.indexOf(' ') >= 0) return "";
        if (u.indexOf('?') >= 0 || u.indexOf('#') >= 0) return "";
        return u;
    }

    /** 由 wss 合成地址推出同主机的 https 根（拉音色列表 / 试听用）；推不出返回 ""。 */
    public static String httpsBase(String wss) {
        String u = cleanWss(wss);
        if (u.length() == 0) return "";
        String rest = u.substring(6);
        int slash = rest.indexOf('/');
        return "https://" + (slash < 0 ? rest : rest.substring(0, slash));
    }

    public String describe() {
        return "cloud=" + isEnabled() + " model=" + getModel() + " voice=" + (getVoice().length() > 0 ? "set" : "none") + " host=" + getHostMode()
                + " ws=" + getWorkspaceId() + " connect=" + getConnectTimeoutMs() + "ms first=" + getFirstAudioTimeoutMs() + "ms";
    }

    // ------------------------------------------------------------------ 纯函数（JVM 单测）

    /** Workspace ID 只允许字母数字和连字符（拼进主机名，防注入）。 */
    public static boolean isValidWorkspaceId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9-]{1,62}");
    }

    public static String wsUrlFor(String hostMode, String workspaceId) {
        if (HOST_WORKSPACE.equals(hostMode) && isValidWorkspaceId(workspaceId)) {
            return "wss://" + workspaceId + ".cn-beijing.maas.aliyuncs.com/api-ws/v1/inference";
        }
        return DASHSCOPE_WS_URL;
    }

    /** 专属域名握手被拒（鉴权 / 不存在）→ 值得改走 dashscope 域名。 */
    public static boolean shouldDowngradeHost(int handshakeHttpCode) {
        return handshakeHttpCode == 401 || handshakeHttpCode == 403 || handshakeHttpCode == 404;
    }

    static int clampTimeout(int ms) {
        return Math.max(300, Math.min(ms, 10000));
    }

    private void putOrClear(String k, String v) {
        if (v == null || v.trim().length() == 0) mSp.edit().remove(k).apply();
        else mSp.edit().putString(k, v.trim()).apply();
    }

    private static String nonEmpty(String v, String def) {
        return v == null || v.trim().length() == 0 ? def : v.trim();
    }
}
