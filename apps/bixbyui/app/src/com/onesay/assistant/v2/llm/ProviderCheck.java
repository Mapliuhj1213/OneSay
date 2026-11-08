package com.onesay.assistant.v2.llm;

/**
 * 「测试」按钮：发一次 {@link ProviderAdapter#check} 请求（模型列表 / 模型详情，不花 token），给出一句中文结论。
 * 纯 Java；传输层可注入（单测用录制好的回复）。
 */
public final class ProviderCheck {
    private ProviderCheck() {}

    public static final int CONNECT_MS = 8000, READ_MS = 12000;

    public static ProviderErrors.Verdict run(ProviderKind kind, String base, String model, String key, ProviderHttp.Transport t) {
        if (key == null || key.trim().length() == 0) return new ProviderErrors.Verdict(ProviderErrors.Kind.AUTH, 0, "还没填 API Key");
        String b = ProviderUrls.normalize(kind, base);
        if (b == null) return new ProviderErrors.Verdict(ProviderErrors.Kind.BAD_RESPONSE, 0, "服务地址不对（只收 https://，不带 ?、# 和账号）");
        if (model != null && model.length() > 0 && !ProviderUrls.validModel(model))
            return new ProviderErrors.Verdict(ProviderErrors.Kind.BAD_RESPONSE, 0, "模型名只能是字母、数字和 . _ - / : @");
        ProviderAdapter a = Adapters.of(kind);
        WireRequest req = a.check(b, model);
        try {
            ProviderHttp.Response r = (t == null ? ProviderHttp.URL_CONNECTION : t).exec(req, key.trim(), CONNECT_MS, READ_MS);
            if (r.status >= 300 && r.status < 400)
                return new ProviderErrors.Verdict(ProviderErrors.Kind.BAD_RESPONSE, r.status, "服务要求跳转到别的地址（为保护 Key 不跟随），请检查服务地址");
            return a.checkVerdict(r.status, r.body, model);
        } catch (Exception e) {
            return ProviderErrors.network(e.getClass().getSimpleName());   // 不带 message：可能含主机名等
        }
    }
}
