package com.onesay.assistant.v2.asr;

import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.onesay.assistant.v2.net.HttpClient;
import com.onesay.assistant.v2.util.TextUtil;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 阿里云 Token 管理器。
 *
 * <p>阿里云智能语音交互的鉴权模型是两步：
 * <pre>
 *   AK/SK --(CreateToken, POP签名)--> Token(24h) --(X-NLS-Token 头)--> 识别接口
 * </pre>
 * 本类负责第一步，并把结果缓存起来，避免每次识别都去签发一次。
 *
 * <h3>为什么不用官方 SDK</h3>
 * 官方 {@code nls-sdk-java} 依赖 okhttp / gson / slf4j 一大串，而本工程是
 * 纯命令行 {@code javac + d8} 构建（无 Gradle 依赖解析）。POP 签名本身只用
 * JDK 自带的 {@code javax.crypto.Mac} + {@code java.net.HttpURLConnection} 就能算完，
 * 所以这里自己实现，**零第三方依赖**，APK 也不因此变大。
 *
 * <h3>Token 缓存策略（★ 这里有个容易踩的坑）</h3>
 * 响应里的 {@code ExpireTime} 是 **Unix 秒（绝对时间）**，要靠设备时钟换算成剩余时长。
 * 但设备时钟可能是错的（没联网对时的平板/测试机很常见），照着它算 TTL 会导致
 * "以为还有 20 小时，其实早就过期了"。
 *
 * <p>所以本类做了两层保守处理：
 * <ol>
 *   <li>把 TTL 硬顶在 {@link #MAX_CACHE_MS}（2 小时）——即使设备时钟完全错乱，
 *       也不会把过期 Token 当成长期有效；</li>
 *   <li>用 {@link SystemClock#elapsedRealtime()} 记账（单调时钟，不受用户改时间影响）。</li>
 * </ol>
 * 另外，识别接口收到"Token 无效/过期"类错误码时会调 {@link #invalidate()}，
 * 下一次自动重签，所以即使真过期了也能自愈。
 */
public final class AliyunTokenManager {

    private static final String TAG = "AliyunTokenMgr";

    /** 即使服务端说还剩 23 小时，本地也只当 2 小时用（防设备时钟错误） */
    private static final long MAX_CACHE_MS = 2L * 60 * 60 * 1000;
    /** 提前 5 分钟判定过期，避免"刚好在过期瞬间发起请求" */
    private static final long REFRESH_AHEAD_MS = 5L * 60 * 1000;
    /** 签发请求超时 */
    private static final int TOKEN_TIMEOUT_MS = 8000;

    private final AliyunAsrConfig mConfig;

    private String mToken = "";
    /** 用单调时钟记账的过期时刻（elapsedRealtime 基准） */
    private long mValidUntilElapsed = 0L;
    /** 上次失败的描述，仅用于日志/自测展示 */
    private String mLastError = "";

    public AliyunTokenManager(AliyunAsrConfig config) {
        mConfig = config;
    }

    /**
     * 取一个可用 Token。
     *
     * <p><b>这是阻塞方法</b>（可能发起一次 HTTPS 请求），必须在后台线程调用。
     *
     * @param forceRefresh 为 true 时忽略缓存强制重签
     * @return Token 字符串；无法取得时返回 ""（调用方应据此降级到离线）
     */
    public synchronized String getToken(boolean forceRefresh) {
        long nowElapsed = SystemClock.elapsedRealtime();

        // 1. 命中缓存
        if (!forceRefresh && mToken.length() > 0 && nowElapsed < mValidUntilElapsed) {
            return mToken;
        }

        // 2. 手填 Token 兜底（无 AK/SK 时）
        //    注意：手填 Token 拿不到可靠的 ExpireTime，所以只在"从未签发成功过"时使用，
        //    一旦 AK/SK 可用就优先走自动签发。
        if (mConfig.getAccessKeyId().length() == 0 && mConfig.getManualToken().length() > 0) {
            mToken = mConfig.getManualToken();
            // 当 1 小时用；过期后靠识别接口的 401 类错误触发重取，用户会重新手填。
            mValidUntilElapsed = nowElapsed + 60L * 60 * 1000;
            Log.i(TAG, "[TOKEN] 使用手填 Token（无 AK/SK）");
            return mToken;
        }

        // 3. 自动签发
        String fresh = issueToken();
        if (fresh != null && fresh.length() > 0) {
            mToken = fresh;
            mLastError = "";
            return mToken;
        }

        // 4. 签发失败：若旧 Token 还在有效期内，继续用它撑一下
        if (mToken.length() > 0 && nowElapsed < mValidUntilElapsed) {
            Log.w(TAG, "[TOKEN] 重签失败，沿用旧 Token 顶一次: " + mLastError);
            return mToken;
        }
        return "";
    }

    /** 便利方法：非强制刷新。 */
    public synchronized String getToken() {
        return getToken(false);
    }

    /** 标记当前 Token 失效，下次调用会重签。识别接口返回鉴权类错误时调用。 */
    public synchronized void invalidate() {
        mValidUntilElapsed = 0L;
        Log.i(TAG, "[TOKEN] 已标记失效，下次将重新签发");
    }

    /** 当前是否有可用 Token（廉价，不触发网络）。 */
    public synchronized boolean hasValidToken() {
        if (mToken.length() == 0) return mConfig.hasCredential();
        return SystemClock.elapsedRealtime() < mValidUntilElapsed;
    }

    public synchronized String getLastError() {
        return mLastError;
    }

    // ==================================================================
    // 签发
    // ==================================================================

    /** @return Token 字符串；失败返回 null */
    private String issueToken() {
        // 代理模式：由你自己的服务端签发
        String remote = mConfig.getRemoteTokenUrl();
        if (remote.length() > 0) {
            return issueFromRemote(remote);
        }

        String akId = mConfig.getAccessKeyId();
        String akSecret = mConfig.getAccessKeySecret();
        if (akId.length() == 0 || akSecret.length() == 0) {
            mLastError = "缺少 AccessKeyId / AccessKeySecret，且未配置手填 Token";
            return null;
        }

        try {
            String response = callCreateToken(akId, akSecret);
            if (response == null) return null;

            JSONObject root = new JSONObject(response);
            JSONObject tokenObj = root.optJSONObject("Token");
            if (tokenObj == null) {
                // 阿里云失败时返回 {"Code":"...","Message":".."}，把 Message 掏出来给日志
                mLastError = "响应无 Token 字段: " + TextUtil.truncate(response, 200);
                return null;
            }

            String id = tokenObj.optString("Id", "");
            long expireSec = tokenObj.optLong("ExpireTime", 0L);
            if (id.length() == 0) {
                mLastError = "Token.Id 为空";
                return null;
            }

            long cacheMs = computeCacheMs(expireSec);
            mValidUntilElapsed = SystemClock.elapsedRealtime() + cacheMs;
            Log.i(TAG, "[TOKEN] 签发成功，本地有效 " + (cacheMs / 1000) + "s"
                    + "（服务端 ExpireTime=" + expireSec + "）");
            return id;

        } catch (Throwable t) {
            mLastError = "签发异常: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "[TOKEN] " + mLastError);
            return null;
        }
    }

    /** 代理模式：从你自建的服务端拉 Token。约定返回 {"token":"..","expireTime":1730000000}。 */
    private String issueFromRemote(String url) {
        // 强制 https（prefs 被 root 改成 http:// 或奇怪 scheme 时拒绝，不发请求）
        if (!AliyunAsrConfig.isAllowedRemoteTokenUrl(url)) {
            mLastError = "代理签发地址不是 https，已拒绝";
            Log.w(TAG, "[TOKEN] [SEC] " + mLastError);
            return null;
        }
        HttpClient.Response resp = HttpClient.get(url, null, TOKEN_TIMEOUT_MS, TOKEN_TIMEOUT_MS);
        if (resp.error != null) {
            mLastError = "代理模式异常: " + resp.error.getMessage();
            Log.w(TAG, "[TOKEN] " + mLastError);
            return null;
        }
        try {
            int code = resp.code;
            if (code != 200) {
                mLastError = "代理解析返回 HTTP " + code;
                return null;
            }
            String body = resp.body;
            JSONObject o = new JSONObject(body);
            String tk = o.optString("token", o.optString("Token", ""));
            if (tk.length() == 0) {
                mLastError = "代理响应无 token 字段: " + TextUtil.truncate(body, 200);
                return null;
            }
            long expireSec = o.optLong("expireTime", 0L);
            long cacheMs = computeCacheMs(expireSec);
            mValidUntilElapsed = SystemClock.elapsedRealtime() + cacheMs;
            Log.i(TAG, "[TOKEN] 代理模式签发成功，本地有效 " + (cacheMs / 1000) + "s");
            return tk;
        } catch (Throwable t) {
            mLastError = "代理模式异常: " + t.getMessage();
            Log.w(TAG, "[TOKEN] " + mLastError);
            return null;
        }
    }

    /** 调用阿里云 POP 接口 CreateToken。 */
    private String callCreateToken(String akId, String akSecret) throws Exception {
        // ---- 1. 组装公共参数（阿里云 POP RPC 风格） ----
        List<String[]> params = new ArrayList<String[]>();
        params.add(new String[]{"AccessKeyId", akId});
        params.add(new String[]{"Action", "CreateToken"});
        params.add(new String[]{"Version", "2019-02-28"});
        params.add(new String[]{"Format", "JSON"});
        params.add(new String[]{"RegionId", AliyunAsrConfig.tokenRegionFor(mConfig.getTokenEndpoint())});
        params.add(new String[]{"Timestamp", isoTimestampUtc()});
        params.add(new String[]{"SignatureMethod", "HMAC-SHA1"});
        params.add(new String[]{"SignatureVersion", "1.0"});
        params.add(new String[]{"SignatureNonce", UUID.randomUUID().toString()});

        // ---- 2. 按参数名排序 ----
        Collections.sort(params, new java.util.Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[0].compareTo(b[0]);
            }
        });

        // ---- 3. 构造规范化查询串（RFC3986 编码） ----
        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) canonical.append('&');
            canonical.append(percentEncode(params.get(i)[0]))
                    .append('=')
                    .append(percentEncode(params.get(i)[1]));
        }

        // ---- 4. 计算签名 ----
        // StringToSign = HTTPMethod + "&" + percentEncode("/") + "&" + percentEncode(canonicalQuery)
        String stringToSign = "POST" + "&" + percentEncode("/") + "&" + percentEncode(canonical.toString());
        String signature = hmacSha1Base64(akSecret + "&", stringToSign);

        // ---- 5. 发起请求 ----
        String url = mConfig.getTokenEndpoint() + "?" + canonical + "&Signature=" + percentEncode(signature);
        // 走 net.HttpClient；网络异常与原实现一样向上抛（由 issueToken 的 catch 统一记录）
        HttpClient.Response resp = HttpClient.execute(new HttpClient.Request(url)
                .method("POST")
                .timeouts(TOKEN_TIMEOUT_MS, TOKEN_TIMEOUT_MS)
                .body(new byte[0])
                .fixedLength(true));
        if (resp.error != null) {
            if (resp.error instanceof Exception) throw (Exception) resp.error;
            throw new Exception(resp.errorText(), resp.error);
        }
        if (resp.code != 200) {
            mLastError = "CreateToken HTTP " + resp.code + ": " + TextUtil.truncate(resp.body, 200);
            return null;
        }
        return resp.body;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 由服务端 ExpireTime 推算本地可缓存时长。
     *
     * ★ 关键：设备时钟不可信，所以这里必须"向上取小"而不是"照抄"。
     * 若设备时间比真实时间慢，{@code expireSec*1000 - now} 会偏大 → 被 MAX_CACHE_MS 兜住；
     * 若设备时间快，算出来偏小 → 只是多签几次，无害。两个方向都是安全的。
     */
    private static long computeCacheMs(long expireSec) {
        if (expireSec <= 0L) {
            // 服务端没给过期时间：保守用 30 分钟
            return 30L * 60 * 1000;
        }
        long remainMs = expireSec * 1000L - System.currentTimeMillis();
        if (remainMs <= 0) {
            // 设备时间已经"越过"过期点 —— 可能是时钟快，也可能是真过期。
            // 服务端刚签发就说过期，说明是时钟问题，保守缓存 10 分钟试一次。
            return 10L * 60 * 1000;
        }
        long capped = Math.min(remainMs, MAX_CACHE_MS);
        long usable = capped - REFRESH_AHEAD_MS;
        return (usable <= 0) ? (capped / 2) : usable;
    }

    /**
     * RFC 3986 百分号编码。★ 不能直接用 URLEncoder：它把空格变 '+'、且不转义 '*'。
     * public 是为了让自检入口能复现同一套编码 —— 它是纯函数，公开无副作用。
     */
    public static String percentEncode(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (Exception e) {
            return value;
        }
    }

    /** HMAC-SHA1 后 Base64（阿里云 POP 签名要求）。public 理由同上（供自检复现）。 */
    public static String hmacSha1Base64(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA1"));
        byte[] raw = mac.doFinal(data.getBytes("UTF-8"));
        return Base64.encodeToString(raw, Base64.NO_WRAP);
    }

    /**
     * ISO 8601 UTC，形如 2019-04-03T06:15:03Z。
     * 用 SimpleDateFormat 而非 java.time，保证 API 24 上可用。
     */
    public static String isoTimestampUtc() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }
}
