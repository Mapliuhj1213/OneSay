package com.onesay.desktop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 语音识别：阿里云百炼（DashScope）OpenAI 兼容端点，模型 qwen3-asr-flash，自带 API Key。
 * 与手机端 DashScopeAsrChannel 同一做法：16 kHz 单声道 16 位裸 PCM，声明 audio/pcm;rate=16000。
 */
public final class Asr {
    public static final String DEFAULT_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public static final String MODEL = "qwen3-asr-flash";
    static final int MAX_SECONDS = 60;

    private final String base, key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public Asr(String base, String key) {
        this.base = (base == null || base.isBlank() ? DEFAULT_BASE : base).replaceAll("/+$", "");
        this.key = key;
    }

    static String requestBody(byte[] pcm, int rate) {
        int max = rate * 2 * MAX_SECONDS;
        byte[] p = pcm.length > max ? java.util.Arrays.copyOf(pcm, max) : pcm;
        Map<String, Object> audio = new TreeMap<>();
        audio.put("data", "data:audio/pcm;rate=" + rate + ";base64," + Base64.getEncoder().encodeToString(p));
        Map<String, Object> part = new TreeMap<>();
        part.put("type", "input_audio");
        part.put("input_audio", audio);
        Map<String, Object> msg = new TreeMap<>();
        msg.put("role", "user");
        msg.put("content", List.of(part));
        Map<String, Object> body = new TreeMap<>();
        body.put("model", MODEL);
        body.put("messages", List.of(msg));
        body.put("stream", false);
        return Json.stringify(body);
    }

    @SuppressWarnings("unchecked")
    static String parse(String json) {
        Map<String, Object> m = Json.obj(json);
        Object ch = m.get("choices");
        if (!(ch instanceof List) || ((List<?>) ch).isEmpty()) {
            Object err = m.get("error");
            throw new IllegalStateException("识别失败：" + (err instanceof Map ? ((Map<String, Object>) err).get("message") : "无结果"));
        }
        Map<String, Object> c0 = (Map<String, Object>) ((List<Object>) ch).get(0);
        Map<String, Object> msg = (Map<String, Object>) c0.get("message");
        Object content = msg == null ? null : msg.get("content");
        if (content instanceof List) { // 个别返回是分段数组
            StringBuilder b = new StringBuilder();
            for (Object o : (List<Object>) content) if (o instanceof Map && ((Map<String, Object>) o).get("text") != null) b.append(((Map<String, Object>) o).get("text"));
            return b.toString().trim();
        }
        return content == null ? "" : String.valueOf(content).trim();
    }

    public String recognize(byte[] pcm, int rate) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(pcm, rate), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() == 401 || r.statusCode() == 403) throw new IllegalStateException("识别 Key 无效（" + r.statusCode() + "）");
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("识别服务返回 " + r.statusCode());
        return parse(r.body());
    }
}
