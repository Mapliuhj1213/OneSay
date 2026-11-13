/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/model/ModelHttp.kt openAiError / geminiError, ModelError.kt,
 * AnthropicModel.mapError) to Java 7 + org.json; sealed classes replaced by an enum + message. Upstream: 8d61573.
 */
package com.onesay.assistant.v2.llm;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/** 把 HTTP 状态 + 报错正文归成一类，并给一句中文说明（「测试」按钮和请求失败时共用）。 */
public final class ProviderErrors {
    private ProviderErrors() {}

    public enum Kind { OK, AUTH, QUOTA, RATE_LIMITED, CONTEXT_TOO_LONG, MODEL_NOT_FOUND, SERVER, CONTENT_REFUSED, NETWORK, BAD_RESPONSE }

    public static final class Verdict {
        public final Kind kind;
        public final int status;
        public final String message;

        Verdict(Kind kind, int status, String message) {
            this.kind = kind;
            this.status = status;
            this.message = message;
        }

        public boolean ok() {
            return kind == Kind.OK;
        }

        @Override public String toString() {
            return kind + "(" + status + "): " + message;
        }
    }

    private static final Pattern CONTEXT_TOO_LONG = Pattern.compile(
            "(?:exceed|longer than|larger than|greater than|max)[^.\\n]{0,40}?(?:context[ _](?:length|size|limit|window)|model[ _]length)"
                    + "|context_length|maximum context|context length of only", Pattern.CASE_INSENSITIVE);

    static boolean contextTooLong(int status, String body) {
        return status == 400 && body != null && CONTEXT_TOO_LONG.matcher(body).find();
    }

    public static Verdict network(String what) {
        return new Verdict(Kind.NETWORK, -1, "连不上服务（" + (what == null ? "网络异常" : what) + "）");
    }

    public static Verdict ok(int status, String message) {
        return new Verdict(Kind.OK, status, message);
    }

    public static Verdict of(ProviderKind kind, int status, String body, String model) {
        String b = body == null ? "" : body;
        String m = model == null || model.length() == 0 ? "" : "「" + model + "」";
        if (status >= 200 && status <= 299) return ok(status, "正常");
        switch (kind) {
            case ANTHROPIC:
                if (status == 401 || status == 403) return v(Kind.AUTH, status, "API Key 无效或没有权限");
                if (status == 402 || b.contains("credit balance is too low")) return v(Kind.QUOTA, status, "额度已用完");
                if (status == 429) return v(Kind.RATE_LIMITED, status, "请求太频繁，请稍后再试");
                if (status == 413 || b.contains("prompt is too long") || contextTooLong(status, b))
                    return v(Kind.CONTEXT_TOO_LONG, status, "对话内容超出模型上下文长度");
                if (status == 404) return v(Kind.MODEL_NOT_FOUND, status, "找不到模型" + m + "（或地址不对）");
                if (status == 529 || b.contains("overloaded_error")) return v(Kind.SERVER, 529, "模型服务过载（HTTP 529）");
                break;
            case GEMINI: {
                String st = "", msg = b;
                try {
                    JSONObject err = new JSONObject(b).optJSONObject("error");
                    if (err != null) { st = err.optString("status", ""); msg = err.optString("message", b); }
                } catch (Exception ignored) { }
                String low = msg.toLowerCase(Locale.ROOT);
                if (status == 400 && (st.equals("INVALID_ARGUMENT") && low.contains("api key"))) return v(Kind.AUTH, status, "API Key 无效");
                if (status == 401 || status == 403) return v(Kind.AUTH, status, "API Key 无效或没有权限");
                if (status == 429 || "RESOURCE_EXHAUSTED".equals(st)) return v(Kind.RATE_LIMITED, status, "请求太频繁或额度不足，请稍后再试");
                if (status == 404) return v(Kind.MODEL_NOT_FOUND, status, "找不到模型" + m);
                if (status == 413 || (status == 400 && low.contains("token")
                        && (low.contains("exceed") || low.contains("too long") || low.contains("maximum"))))
                    return v(Kind.CONTEXT_TOO_LONG, status, "对话内容超出模型上下文长度");
                break;
            }
            default:
                if (status == 401 || status == 403) return v(Kind.AUTH, status, "API Key 无效或没有权限");
                if (status == 402 || b.contains("insufficient_quota") || b.contains("Insufficient Balance"))
                    return v(Kind.QUOTA, status, "额度已用完（或账户余额不足）");
                if (status == 429) return v(Kind.RATE_LIMITED, status, "请求太频繁，请稍后再试");
                if (contextTooLong(status, b)) return v(Kind.CONTEXT_TOO_LONG, status, "对话内容超出模型上下文长度");
                if (status == 404) return v(Kind.MODEL_NOT_FOUND, status, "找不到模型" + m + "（或地址不对）");
        }
        if (status >= 500 && status <= 599) return v(Kind.SERVER, status, "模型服务出错（HTTP " + status + "）");
        return v(Kind.BAD_RESPONSE, status, "HTTP " + status + (b.length() == 0 ? "" : "：" + detail(b)));
    }

    /** 服务端 JSON 报错里的原话（截短到 120 字；不是 JSON 就截原文）。只用于给人看，不含请求内容。 */
    static String detail(String body) {
        String d = body;
        try {
            JSONObject o = new JSONObject(body);
            Object e = o.opt("error");
            if (e instanceof JSONObject) d = ((JSONObject) e).optString("message", body);
            else if (e instanceof String) d = (String) e;
            else if (o.has("message")) d = o.optString("message", body);
        } catch (Exception ignored) { }
        d = d.replace('\n', ' ').trim();
        return d.length() > 120 ? d.substring(0, 120) + "…" : d;
    }

    private static Verdict v(Kind k, int status, String msg) {
        return new Verdict(k, status, msg);
    }
}
