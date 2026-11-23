package com.onesay.assistant.v2.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * [fix-1123-c] 从服务商拉模型列表（和「测试」按钮同一个请求：模型列表接口，不花 token），给「模型」选择列表用。
 * 纯 Java；传输层可注入（单测）。拉不到返回空表（界面上还有预置的和「自己填一个…」）。Key 只放请求头，只发往服务地址。
 */
public final class ProviderModels {
    private ProviderModels() {}

    public static List<String> list(ProviderKind kind, String base, String key, ProviderHttp.Transport t) {
        List<String> out = new ArrayList<String>();
        if (key == null || key.trim().length() == 0) return out;
        String b = ProviderUrls.normalize(kind, base);
        if (b == null) return out;
        try {
            WireRequest req = Adapters.of(kind).check(b, "");
            ProviderHttp.Response r = (t == null ? ProviderHttp.URL_CONNECTION : t).exec(req, key.trim(), ProviderCheck.CONNECT_MS, ProviderCheck.READ_MS);
            if (r.status < 200 || r.status > 299) return out;
            for (String id : OpenAiCompatAdapter.modelIds(r.body)) if (ProviderUrls.validModel(id) && !out.contains(id) && out.size() < 300) out.add(id);
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
