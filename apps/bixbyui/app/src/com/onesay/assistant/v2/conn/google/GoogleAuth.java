package com.onesay.assistant.v2.conn.google;

import com.onesay.assistant.v2.conn.Codec;
import com.onesay.assistant.v2.conn.ConnErrors;
import com.onesay.assistant.v2.conn.ConnHttp;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.Redact;
import com.onesay.assistant.v2.conn.SecretStore;

import org.json.JSONObject;

import java.util.List;

/**
 * [conn/google] Google OAuth 2.0（installed app）：授权码 + PKCE（loopback 回调）为主，设备码流程为备（仅限 Google 允许的范围）。
 *
 * <h3>令牌</h3>
 * refresh_token / access_token / 过期时间 / client_secret 全部存 {@link SecretStore}（真机 = Keystore 加密），日志只打状态。
 * Client ID <b>不是秘密也不内置</b>：由用户在 Google Cloud 控制台建「桌面应用」OAuth 客户端后填进来（{@link Config}），
 * 本类不带任何默认值。
 *
 * <h3>断网 / 吊销</h3>
 * <ul>
 *   <li>刷新时网络不通 → {@code UNREACHABLE}，<b>不清令牌</b>；access_token 还没过期就照用；</li>
 *   <li>刷新返回 {@code invalid_grant}（用户在 Google 账号里撤了授权 / 测试状态项目 7 天到期 / 改了密码）→ {@code AUTH_REVOKED}，清掉本地令牌，需要重新登录；</li>
 *   <li>{@link #revoke()}：先调 Google 吊销端点（失败不影响），<b>无论如何</b>清本地令牌。</li>
 * </ul>
 */
public final class GoogleAuth {

    public static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    public static final String DEVICE_URL = "https://oauth2.googleapis.com/device/code";

    static final String K_REFRESH = "google.refresh_token";
    static final String K_ACCESS = "google.access_token";
    static final String K_ACCESS_EXP = "google.access_exp";
    static final String K_SCOPES = "google.scopes";
    static final String K_EMAIL = "google.email";
    public static final String K_CLIENT_SECRET = "google.client_secret";

    /** 提前这么久就当 access_token 过期（避免请求途中过期）。 */
    static final long SKEW_MS = 60000L;

    public interface Config {
        /** 用户填的 OAuth Client ID（桌面应用类型）；没填返回 ""。 */
        String clientId();
    }

    public interface Clock {
        long now();
    }

    static final Clock SYSTEM = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };

    private final ConnHttp mHttp;
    private final SecretStore mSec;
    private final Config mCfg;
    private final Clock mClock;
    private volatile String mAccess;
    private volatile long mAccessExp;

    public GoogleAuth(ConnHttp http, SecretStore sec, Config cfg) {
        this(http, sec, cfg, SYSTEM);
    }

    public GoogleAuth(ConnHttp http, SecretStore sec, Config cfg, Clock clock) {
        mHttp = http;
        mSec = sec;
        mCfg = cfg;
        mClock = clock;
        mAccess = sec.get(K_ACCESS);
        try {
            mAccessExp = Long.parseLong(sec.get(K_ACCESS_EXP));
        } catch (Exception e) {
            mAccessExp = 0;
        }
    }

    // ------------------------------------------------------------------ 状态

    /** Client ID 形状校验（xxx.apps.googleusercontent.com）。 */
    public static boolean validClientId(String v) {
        return v != null && v.matches("[0-9A-Za-z\\-_.]+\\.apps\\.googleusercontent\\.com");
    }

    public String clientId() {
        String c = mCfg == null ? "" : mCfg.clientId();
        return c == null ? "" : c.trim();
    }

    /** 填了 Client ID。 */
    public boolean configured() {
        return clientId().length() > 0;
    }

    /** 有 refresh_token（登录过且没被吊销）。 */
    public boolean signedIn() {
        return mSec.get(K_REFRESH).length() > 0;
    }

    public String grantedScopes() {
        return mSec.get(K_SCOPES);
    }

    public boolean hasScope(String scope) {
        return GoogleScopes.has(grantedScopes(), scope);
    }

    public String email() {
        return mSec.get(K_EMAIL);
    }

    public String status() {
        if (!configured()) return "google client_id=未填";
        if (!signedIn()) return "google 未登录";
        return "google 已登录" + (email().length() > 0 ? "（" + maskEmail(email()) + "）" : "")
                + " scopes=" + shortScopes(grantedScopes());
    }

    static String maskEmail(String e) {
        int at = e.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? e.substring(at) : "");
        return e.charAt(0) + "***" + e.substring(at);
    }

    static String shortScopes(String s) {
        if (s == null) return "";
        return s.replace("https://www.googleapis.com/auth/", "");
    }

    // ------------------------------------------------------------------ 授权码 + PKCE

    public static final class Pkce {
        public final String verifier;
        public final String challenge;
        public final String state;

        Pkce(String verifier, String state) {
            this.verifier = verifier;
            this.challenge = Codec.b64url(Codec.sha256(Codec.utf8(verifier)));
            this.state = state;
        }
    }

    public static Pkce newPkce() {
        return new Pkce(Codec.random(48), Codec.random(16));
    }

    /** 测试用：固定 verifier / state。 */
    static Pkce pkceOf(String verifier, String state) {
        return new Pkce(verifier, state);
    }

    public String authUrl(String redirectUri, Pkce p, List<String> scopes) {
        return AUTH_URL + "?" + Codec.form(
                "client_id", clientId(),
                "redirect_uri", redirectUri,
                "response_type", "code",
                "scope", GoogleScopes.join(scopes),
                "code_challenge", p.challenge,
                "code_challenge_method", "S256",
                "state", p.state,
                "access_type", "offline",
                "prompt", "consent",          // 每次都给 refresh_token（重新登录时 Google 否则可能不再下发）
                "include_granted_scopes", "true");
    }

    /** 用回调拿到的 code 换令牌。 */
    public synchronized ConnResult exchangeCode(String code, String redirectUri, Pkce p) {
        if (!configured()) return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "没填 Google OAuth Client ID");
        if (code == null || code.length() == 0) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "回调里没有 code");
        String secret = mSec.get(K_CLIENT_SECRET);
        ConnHttp.Resp r = mHttp.send(new ConnHttp.Req("POST", TOKEN_URL).timeouts(10000, 20000)
                .body(Codec.form("code", code, "client_id", clientId(), "client_secret", secret.length() > 0 ? secret : null,
                        "redirect_uri", redirectUri, "grant_type", "authorization_code", "code_verifier", p.verifier),
                        "application/x-www-form-urlencoded"));
        return storeTokens(r, true);
    }

    // ------------------------------------------------------------------ 设备码（备用）

    public static final class DeviceStart {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUrl;
        public final long expiresAt;
        public volatile int intervalSec;

        DeviceStart(String deviceCode, String userCode, String verificationUrl, long expiresAt, int intervalSec) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.expiresAt = expiresAt;
            this.intervalSec = intervalSec;
        }
    }

    /** 发起设备码流程；Google 不支持的范围直接拒绝（不白跑一趟拿 invalid_scope）。@return null 时看 lastError */
    public DeviceStart startDevice(List<String> scopes, StringBuilder err) {
        if (!configured()) {
            if (err != null) err.append("没填 Google OAuth Client ID");
            return null;
        }
        if (!GoogleScopes.deviceFlowSupports(scopes)) {
            if (err != null) err.append("Google 的设备码登录不支持 Gmail / 日历 / Tasks 权限，请用「浏览器登录」（loopback）");
            return null;
        }
        ConnHttp.Resp r = mHttp.send(new ConnHttp.Req("POST", DEVICE_URL).timeouts(10000, 20000)
                .body(Codec.form("client_id", clientId(), "scope", GoogleScopes.join(scopes)), "application/x-www-form-urlencoded"));
        if (!r.ok()) {
            if (err != null) err.append(r.networkError() ? ConnErrors.human(ConnErrors.classify(r.error)) : "HTTP " + r.code + " " + oauthError(r.body));
            return null;
        }
        try {
            JSONObject o = new JSONObject(r.body);
            String url = o.optString("verification_url", o.optString("verification_uri", ""));
            return new DeviceStart(o.getString("device_code"), o.getString("user_code"), url,
                    mClock.now() + o.optLong("expires_in", 1800) * 1000L, Math.max(5, o.optInt("interval", 5)));
        } catch (Exception e) {
            if (err != null) err.append("设备码响应解析失败");
            return null;
        }
    }

    /**
     * 轮询一次。@return OK = 已登录；RATE_LIMITED = 还在等用户（authorization_pending / slow_down，调用方按 intervalSec 继续）；
     * 其它 = 终止（拒绝 / 过期 / 网络）。
     */
    public synchronized ConnResult pollDevice(DeviceStart d) {
        if (mClock.now() > d.expiresAt) return ConnResult.fail(ConnResult.Code.TIMEOUT, "设备码已过期，请重新开始");
        String secret = mSec.get(K_CLIENT_SECRET);
        ConnHttp.Resp r = mHttp.send(new ConnHttp.Req("POST", TOKEN_URL).timeouts(10000, 20000)
                .body(Codec.form("client_id", clientId(), "client_secret", secret.length() > 0 ? secret : null,
                        "device_code", d.deviceCode, "grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                        "application/x-www-form-urlencoded"));
        if (r.code == 428 || (r.code == 400 || r.code == 403) && r.body.contains("authorization_pending")) {
            return ConnResult.fail(ConnResult.Code.RATE_LIMITED, "等待用户在另一台设备上授权");
        }
        if (r.body.contains("slow_down")) {
            d.intervalSec += 5;
            return ConnResult.fail(ConnResult.Code.RATE_LIMITED, "轮询太快，放慢");
        }
        if (r.body.contains("access_denied")) return ConnResult.fail(ConnResult.Code.DENIED, "你拒绝了授权");
        if (r.body.contains("expired_token")) return ConnResult.fail(ConnResult.Code.TIMEOUT, "设备码已过期");
        return storeTokens(r, true);
    }

    // ------------------------------------------------------------------ access_token

    /** 拿可用的 access_token：缓存没过期直接给；否则用 refresh_token 刷新。失败时 code 说明原因。 */
    public synchronized Token accessToken() {
        if (!configured()) return Token.fail(ConnResult.Code.NOT_CONFIGURED, "没填 Google OAuth Client ID");
        if (!signedIn()) return Token.fail(ConnResult.Code.NOT_CONFIGURED, "还没登录 Google");
        if (mAccess != null && mAccess.length() > 0 && mClock.now() < mAccessExp - SKEW_MS) return Token.ok(mAccess);
        ConnResult r = refresh();
        if (r.ok()) return Token.ok(mAccess);
        return Token.fail(r.code, r.text);
    }

    /** 让缓存的 access_token 失效（API 返回 401 时调，下一次 accessToken() 会刷新）。 */
    public synchronized void invalidateAccess() {
        mAccess = "";
        mAccessExp = 0;
        mSec.put(K_ACCESS, null);
        mSec.put(K_ACCESS_EXP, null);
    }

    public synchronized ConnResult refresh() {
        String rt = mSec.get(K_REFRESH);
        if (rt.length() == 0) return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "还没登录 Google");
        String secret = mSec.get(K_CLIENT_SECRET);
        ConnHttp.Resp r = mHttp.send(new ConnHttp.Req("POST", TOKEN_URL).timeouts(8000, 15000)
                .body(Codec.form("client_id", clientId(), "client_secret", secret.length() > 0 ? secret : null,
                        "refresh_token", rt, "grant_type", "refresh_token"), "application/x-www-form-urlencoded"));
        if (r.networkError()) {
            // 断网 / 墙：不清令牌，旧 access_token 如果其实还没过期也不会走到这里
            return ConnResult.fail(ConnErrors.classify(r.error), "刷新 Google 令牌时连不上服务器");
        }
        if (!r.ok() && r.body.contains("invalid_grant")) {
            clearLocal();
            return ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "Google 授权已失效（被撤销或过期），需要重新登录");
        }
        if (!r.ok() && (r.body.contains("invalid_client") || r.body.contains("unauthorized_client"))) {
            return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "Client ID / Secret 不对（" + oauthError(r.body) + "）");
        }
        return storeTokens(r, false);
    }

    /** 退出登录并吊销：先请求 Google 吊销 refresh_token（失败不影响），本地令牌无论如何清掉。 */
    public synchronized ConnResult revoke() {
        String rt = mSec.get(K_REFRESH);
        String tok = rt.length() > 0 ? rt : (mAccess == null ? "" : mAccess);
        ConnResult out = ConnResult.ok("已退出 Google（本地令牌已清除）");
        if (tok.length() > 0) {
            ConnHttp.Resp r = mHttp.send(new ConnHttp.Req("POST", REVOKE_URL).timeouts(8000, 10000)
                    .body(Codec.form("token", tok), "application/x-www-form-urlencoded"));
            if (r.networkError()) {
                out = ConnResult.ok("已清除本地令牌；连不上 Google，远端授权没能撤销，可到 myaccount.google.com/permissions 手动移除");
            } else if (!r.ok() && r.code != 400) {
                out = ConnResult.ok("已清除本地令牌；Google 吊销接口返回 HTTP " + r.code + "，可到 myaccount.google.com/permissions 手动确认");
            }
        }
        clearLocal();
        return out;
    }

    public synchronized void clearLocal() {
        mSec.put(K_REFRESH, null);
        mSec.put(K_SCOPES, null);
        mSec.put(K_EMAIL, null);
        invalidateAccess();
    }

    private ConnResult storeTokens(ConnHttp.Resp r, boolean initial) {
        if (r.networkError()) return ConnResult.fail(ConnErrors.classify(r.error), "连不上 Google 授权服务器");
        if (!r.ok()) {
            ConnResult.Code c = r.body.contains("invalid_grant") ? ConnResult.Code.AUTH_REVOKED : ConnErrors.classifyHttp(r.code);
            if (c == ConnResult.Code.OK) c = ConnResult.Code.REMOTE_ERROR;
            return ConnResult.fail(c, "Google 授权失败：HTTP " + r.code + " " + oauthError(r.body));
        }
        try {
            JSONObject o = new JSONObject(r.body);
            String at = o.optString("access_token", "");
            if (at.length() == 0) return ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "授权响应里没有 access_token");
            long exp = mClock.now() + o.optLong("expires_in", 3600) * 1000L;
            mAccess = at;
            mAccessExp = exp;
            mSec.put(K_ACCESS, at);
            mSec.put(K_ACCESS_EXP, String.valueOf(exp));
            String rt = o.optString("refresh_token", "");
            if (rt.length() > 0) mSec.put(K_REFRESH, rt);
            else if (initial && mSec.get(K_REFRESH).length() == 0) {
                return ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "Google 没有下发 refresh_token（请到账号权限页移除本应用后重新登录）");
            }
            String sc = o.optString("scope", "");
            if (sc.length() > 0) mSec.put(K_SCOPES, sc);
            String email = emailFromIdToken(o.optString("id_token", ""));
            if (email.length() > 0) mSec.put(K_EMAIL, email);
            return ConnResult.ok(initial ? "Google 登录成功" : "令牌已刷新");
        } catch (Exception e) {
            return ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "授权响应解析失败");
        }
    }

    /** id_token 是 JWT：只解中段取 email 显示用（不做签名校验——它来自我们直连的 HTTPS 令牌端点，且不用于鉴权）。 */
    static String emailFromIdToken(String jwt) {
        if (jwt == null) return "";
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return "";
        try {
            return new JSONObject(Codec.str(Codec.unb64(parts[1]))).optString("email", "");
        } catch (Exception e) {
            return "";
        }
    }

    static String oauthError(String body) {
        try {
            JSONObject o = new JSONObject(body);
            Object e = o.opt("error");
            if (e instanceof JSONObject) return Redact.s(((JSONObject) e).optString("message", ""), 120);
            return Redact.s(o.optString("error", "") + " " + o.optString("error_description", ""), 120).trim();
        } catch (Exception ex) {
            return Redact.s(body, 80);
        }
    }

    /** access_token 或失败原因。 */
    public static final class Token {
        public final ConnResult.Code code;
        public final String value;
        public final String message;

        private Token(ConnResult.Code code, String value, String message) {
            this.code = code;
            this.value = value;
            this.message = message;
        }

        static Token ok(String v) {
            return new Token(ConnResult.Code.OK, v, "");
        }

        static Token fail(ConnResult.Code c, String m) {
            return new Token(c, "", m);
        }

        public boolean ok() {
            return code == ConnResult.Code.OK;
        }
    }
}
