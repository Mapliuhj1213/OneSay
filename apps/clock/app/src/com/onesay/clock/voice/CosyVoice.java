package com.onesay.clock.voice;

import com.onesay.clock.content.Http;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 百炼 CosyVoice。开源版：音色 ID 与模型由用户在设置里填写（不内置任何个人复刻音色）。非流式：拿音频 URL 再下载 24 kHz WAV。
 * key 只在内存里拼进 Authorization 头，不写日志、不落盘。
 */
public final class CosyVoice {
    private final String endpoint, key, model, voice;

    /** endpoint 必须是 {@link com.onesay.clock.core.Endpoint#clean} 过的 https 地址；Key 只发往这个主机（Authorization 头，不进 URL）。 */
    public CosyVoice(String endpoint, String key, String model, String voice) {
        this.endpoint = endpoint; this.key = key; this.model = model; this.voice = voice;
    }

    /** 测试连通性 / 列音色：DashScope 兼容的「列出本账号自定义音色」。返回音色 ID 列表（可能为空：预置音色不在这里列）。 */
    public static java.util.List<String> listVoices(String synthEndpoint, String key, int timeoutMs) throws Exception {
        String url = com.onesay.clock.core.Endpoint.voiceListUrl(synthEndpoint);
        if (url.length() == 0) throw new Exception("该服务地址不支持拉取音色列表，请手动填写音色 ID");
        String body = new JSONObject().put("model", "voice-enrollment")
                .put("input", new JSONObject().put("action", "list_voice").put("page_size", 100).put("page_index", 0)).toString();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(Math.min(8000, timeoutMs));
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setInstanceFollowRedirects(false);   // 不跟随重定向：Authorization 头不会被带到别的主机
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        OutputStream o = c.getOutputStream();
        try { o.write(body.getBytes("UTF-8")); } finally { o.close(); }
        int code = c.getResponseCode();
        if (code == 401 || code == 403) throw new Exception("Key 无效或无权限（HTTP " + code + "）");
        if (code != 200) throw new Exception("HTTP " + code);
        JSONObject r = new JSONObject(new String(Http.readAll(c.getInputStream()), "UTF-8"));
        java.util.List<String> out = new java.util.ArrayList<String>();
        org.json.JSONObject op = r.optJSONObject("output");
        org.json.JSONArray l = op == null ? null : op.optJSONArray("voice_list");
        for (int i = 0; l != null && i < l.length(); i++) {
            JSONObject v = l.optJSONObject(i);
            if (v != null && v.optString("voice_id").length() > 0) out.add(v.optString("voice_id"));
        }
        return out;
    }

    public byte[] synth(String text, double rate, int timeoutMs) throws Exception {
        JSONObject in = new JSONObject().put("text", text).put("voice", voice).put("format", "wav").put("sample_rate", 24000);
        if (Math.abs(rate - 1.0) > 1e-6) in.put("rate", rate);
        String body = new JSONObject().put("model", model).put("input", in).toString();
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(Math.min(8000, timeoutMs));
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        OutputStream o = c.getOutputStream();
        try { o.write(body.getBytes("UTF-8")); } finally { o.close(); }
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("cosy http " + code);
        JSONObject r = new JSONObject(new String(Http.readAll(c.getInputStream()), "UTF-8"));
        String url = r.getJSONObject("output").getJSONObject("audio").optString("url", "");
        if (url.length() == 0) throw new Exception("cosy no url");
        HttpURLConnection g = (HttpURLConnection) new URL(url).openConnection();
        g.setConnectTimeout(8000);
        g.setReadTimeout(timeoutMs);
        return Http.readAll(g.getInputStream());
    }
}
