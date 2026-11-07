package com.onesay.assistant.v2.conn.google;

import com.onesay.assistant.v2.conn.ConnErrors;
import com.onesay.assistant.v2.conn.ConnHttp;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.Redact;

import org.json.JSONObject;

/**
 * [conn/google] 带鉴权的 Google REST 调用：自动取 / 刷新 access_token；401 → 作废缓存、刷新一次再试；
 * 403 区分「缺权限范围」（要重新登录授权）与「限流」。不抛异常，结果统一成 {@link ConnResult}（data = 响应体 JSON）。
 */
public final class GoogleApi {

    private final ConnHttp mHttp;
    private final GoogleAuth mAuth;
    private volatile com.onesay.assistant.v2.conn.GoogleReach mReach;

    /** 接上可达性状态：冷却期内直接快速失败（不等超时），每次调用结果回报。 */
    public GoogleApi reach(com.onesay.assistant.v2.conn.GoogleReach r) {
        mReach = r;
        return this;
    }

    public com.onesay.assistant.v2.conn.GoogleReach reach() {
        return mReach;
    }

    public GoogleApi(ConnHttp http, GoogleAuth auth) {
        mHttp = http;
        mAuth = auth;
    }

    public GoogleAuth auth() {
        return mAuth;
    }

    public ConnResult get(String url) {
        return call("GET", url, null);
    }

    public ConnResult post(String url, JSONObject body) {
        return call("POST", url, body);
    }

    public ConnResult put(String url, JSONObject body) {
        return call("PUT", url, body);
    }

    public ConnResult patch(String url, JSONObject body) {
        return call("PATCH", url, body);
    }

    public ConnResult delete(String url) {
        return call("DELETE", url, null);
    }

    public ConnResult call(String method, String url, JSONObject body) {
        com.onesay.assistant.v2.conn.GoogleReach reach = mReach;
        if (reach != null && !reach.tryGoogle()) {
            return ConnResult.fail(ConnResult.Code.UNREACHABLE, reach.notice() + "，暂不重试");
        }
        ConnResult r = callOnce(method, url, body);
        if (reach != null) reach.reportGoogle(r);
        return r;
    }

    private ConnResult callOnce(String method, String url, JSONObject body) {
        for (int attempt = 0; attempt < 2; attempt++) {
            GoogleAuth.Token t = mAuth.accessToken();
            if (!t.ok()) return ConnResult.fail(t.code, t.message);
            ConnHttp.Req req = new ConnHttp.Req(method, url).timeouts(8000, 20000)
                    .header("Authorization", "Bearer " + t.value).header("Accept", "application/json");
            if (body != null) req.body(body.toString(), "application/json; charset=UTF-8");
            ConnHttp.Resp r = mHttp.send(req);
            if (r.networkError()) {
                return ConnResult.fail(ConnErrors.classify(r.error), "连不上 Google（" + Redact.t(r.error) + "）");
            }
            if (r.code == 401 && attempt == 0) {
                mAuth.invalidateAccess();   // 令牌可能被提前作废：刷新一次再试
                continue;
            }
            if (r.ok()) return ConnResult.ok("OK", r.body.length() == 0 ? "{}" : r.body);
            return ConnResult.fail(classify(r.code, r.body), "Google API 返回 HTTP " + r.code + " " + reason(r.body));
        }
        return ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "Google 一直返回 401，需要重新登录");
    }

    static ConnResult.Code classify(int code, String body) {
        if (code == 403) {
            String b = body == null ? "" : body;
            if (b.contains("rateLimitExceeded") || b.contains("userRateLimitExceeded") || b.contains("RESOURCE_EXHAUSTED")) {
                return ConnResult.Code.RATE_LIMITED;
            }
            if (b.contains("insufficient") || b.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT") || b.contains("PERMISSION_DENIED")) {
                return ConnResult.Code.AUTH_REVOKED;
            }
            if (b.contains("accessNotConfigured") || b.contains("SERVICE_DISABLED")) return ConnResult.Code.NOT_CONFIGURED;
            return ConnResult.Code.DENIED;
        }
        if (code == 404 || code == 410) return ConnResult.Code.BAD_ARGS;
        ConnResult.Code c = ConnErrors.classifyHttp(code);
        return c == ConnResult.Code.OK ? ConnResult.Code.REMOTE_ERROR : c;
    }

    static String reason(String body) {
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            if (e == null) return "";
            String m = e.optString("message", "");
            if (m.contains("has not been used") || m.contains("is disabled")) m = "该 API 在 Cloud 项目里没启用（" + m + "）";
            return Redact.s(m, 160);
        } catch (Exception ex) {
            return "";
        }
    }
}
