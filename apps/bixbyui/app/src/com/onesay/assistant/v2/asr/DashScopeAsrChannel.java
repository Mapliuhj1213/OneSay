package com.onesay.assistant.v2.asr;

import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.onesay.assistant.v2.net.HttpClient;
import com.onesay.assistant.v2.util.PcmUtil;
import com.onesay.assistant.v2.util.TextUtil;

import org.json.JSONArray;
import org.json.JSONObject;


/**
 * 阿里云百炼（DashScope）语音识别通道 —— 走 <b>OpenAI 兼容端点</b>。
 *
 * <pre>
 *   POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 *   Headers: Authorization: Bearer sk-xxxx   /   Content-Type: application/json
 *   Body   : {"model":"qwen3-asr-flash",
 *             "messages":[{"role":"user","content":[
 *               {"type":"input_audio","input_audio":{"data":"data:audio/wav;base64,..."}}]}]}
 *   响应    : {"choices":[{"message":{"content":"打开蓝牙。",
 *                                     "annotations":[{"language":"zh","emotion":"neutral"}]}}]}
 * </pre>
 *
 * <h3>为什么另起一条通道，而不是复用 {@link AliyunAsrChannel}</h3>
 * 这是**两套完全不同的东西**，只是都叫"阿里云"：
 * <table border="1">
 *   <tr><th></th><th>{@link AliyunAsrChannel}（NLS）</th><th>本类（百炼）</th></tr>
 *   <tr><td>凭据</td><td>AccessKeyId + Secret + AppKey</td><td>一个 API Key（{@code sk-...}）</td></tr>
 *   <tr><td>鉴权</td><td>自签 POP 签名换 Token（HMAC-SHA1）</td><td>直接 {@code Bearer}，无签名无 Token</td></tr>
 *   <tr><td>地址</td><td>{@code nls-gateway-*.aliyuncs.com/stream/v1/asr}</td>
 *               <td>{@code dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}</td></tr>
 * </table>
 * 拿 DashScope 的 Key 去打 NLS 会失败，反之亦然 —— 二者不可互换。
 *
 * <h3>2026-10-02 实测结论（宿主机 + 设备双侧）</h3>
 * <ul>
 *   <li>{@code qwen3-asr-flash} 是账号下**唯一**在 OpenAI 兼容端点上直接可用的识别模型；
 *       {@code qwen-audio-3.1-asr-flash} / {@code fun-asr} 在该端点返回 404，
 *       {@code fun-asr-flash-*} 返回 400（参数键不同）。</li>
 *   <li><b>裸 PCM 不需要包 WAV 头</b>：16k/mono/s16le 原始字节直接 base64 即可，
 *       加不加 {@code format} 字段都能识别（四种组合实测全 200）。
 *       <b>MIME 声明必须与内容一致 —— 塞裸 PCM 却标
 *       {@code audio/wav} 时，端点按 WAV 容器找文件头，长音频被拒（HTTP 400
 *       "audio format is illegal and cannot be opened"，设备端 12s 实录复现）。
 *       正确声明：{@code data:audio/pcm;rate=<sampleRate>;base64,...}（见下方发送处）。</li>
 *   <li>端到端延迟：1.44s 音频 502ms / 4.5s 音频 633ms / 5.6s 音频 748ms，
 *       RTF 0.13~0.35，抖动 &lt;70ms。</li>
 * </ul>
 */
public final class DashScopeAsrChannel implements AsrChannel {

    private static final String TAG = "DashScopeAsr";

    /** 单次音频上限。超过就截尾 —— 语音指令不可能这么长，防呆顺带防天价账单。 */
    private static final int MAX_AUDIO_SECONDS = 60;

    private static final int CONNECT_TIMEOUT_MS = 8000;

    private final AliyunAsrConfig mConfig;

    public DashScopeAsrChannel(AliyunAsrConfig config) {
        mConfig = config;
    }

    @Override
    public String name() {
        return "aliyun-dashscope-" + mConfig.getDashScopeModel();
    }

    @Override
    public boolean isReady() {
        return mConfig.hasDashScopeKey();
    }

    @Override
    public AsrResult recognize(byte[] pcm, int sampleRate) {
        return recognize(pcm, sampleRate, null);
    }

    /**
     * 带**热词上下文**的识别（新增，本工程纠错链路的落点）。
     *
     * <p>做法：在 messages 最前面插一条纯文本 {@code system} 消息，内容是
     * {@link AsrHotwords} 构建的热词表。已经宿主机实测确认该端点接受文本消息，
     * 且探针显示 {@code text_tokens} 从 0 变为非 0（上下文确实被吃进去了），
     * 音频识别本身不受影响。
     *
     * <p>这样纠错就从"事后拿枚举表改文本"变成了"事前给模型先验" ——
     * 用户说"必应"、音频听起来像"毕竟"时，模型因为事先知道可能有"必应"，
     * 会把候选往正确的一侧调。文本层此后原样透传，日志里留下的就是 ASR 的真实输出。
     */
    @Override
    public AsrResult recognize(byte[] pcm, int sampleRate, String hotwords) {
        long t0 = SystemClock.elapsedRealtime();

        if (!mConfig.hasDashScopeKey()) {
            return AsrResult.none("DashScope API Key 未配置");
        }
        if (pcm == null || pcm.length == 0) {
            return AsrResult.fail("音频为空", AsrResult.Source.CLOUD, 0L);
        }

        byte[] audio = PcmUtil.clip(pcm, sampleRate, MAX_AUDIO_SECONDS);
        if (audio != pcm) {
            Log.w(TAG, "音频超 " + MAX_AUDIO_SECONDS + "s，已截尾");
        }

        try {
            // --- 组请求体。data 用 data URI；裸 PCM 实测可直接识别，无需 WAV 头 ---
            String b64 = Base64.encodeToString(audio, Base64.NO_WRAP);
            // MIME 必须如实声明为 PCM，而不是 audio/wav。
            //   实测 qwen3-asr-flash 端点按 MIME 决定解析器：塞裸 PCM 却标成
            //   audio/wav，它会按 WAV 容器找文件头，找不到即报
            //   「audio format is illegal and cannot be opened」（HTTP 400，长音频必现）。
            //   标 audio/pcm 后端点直接按裸 PCM + 采样率解析，与实测相符。
            JSONObject inputAudio = new JSONObject();
            inputAudio.put("data", "data:audio/pcm;rate=" + sampleRate + ";base64," + b64);

            JSONObject content = new JSONObject();
            content.put("type", "input_audio");
            content.put("input_audio", inputAudio);

            JSONArray contentArr = new JSONArray();
            contentArr.put(content);

            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", contentArr);

            JSONArray messages = new JSONArray();

            // ★ 热词上下文前置（为空则完全不发送，行为与旧版逐字节一致）
            final boolean withHotwords = (hotwords != null && hotwords.trim().length() > 0);
            if (withHotwords) {
                JSONObject hwContent = new JSONObject();
                hwContent.put("type", "text");
                hwContent.put("text", hotwords.trim());
                JSONArray hwArr = new JSONArray();
                hwArr.put(hwContent);

                JSONObject hwMsg = new JSONObject();
                hwMsg.put("role", "system");
                hwMsg.put("content", hwArr);
                messages.put(hwMsg);
            }

            messages.put(msg);

            JSONObject body = new JSONObject();
            body.put("model", mConfig.getDashScopeModel());
            body.put("messages", messages);

            byte[] payload = body.toString().getBytes("UTF-8");

            // --- 发请求（走 net.HttpClient）---
            HttpClient.Response resp = HttpClient.execute(
                    new HttpClient.Request(com.onesay.assistant.v2.net.LlmEndpoints.dashscopeChatUrl())
                            .method("POST")
                            .timeouts(CONNECT_TIMEOUT_MS, Math.max(1000, mConfig.getTimeoutMs()))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header("Authorization", "Bearer " + mConfig.getDashScopeKey())
                            .body(payload));
            long ms = SystemClock.elapsedRealtime() - t0;
            if (resp.error != null) {
                String err = resp.errorText();
                Log.w(TAG, "识别异常 (" + ms + "ms): " + err);
                return AsrResult.fail(err, AsrResult.Source.CLOUD, ms);
            }
            int code = resp.code;
            String text = resp.body;

            if (code != 200) {
                // 401/403 = Key 无效或没权限，是最常见的失败，单独给出可操作提示
                if (code == 401 || code == 403) {
                    return AsrResult.fail("DashScope 鉴权失败(HTTP " + code + ")："
                            + "API Key 无效或无权访问该模型 —— " + brief(text),
                            AsrResult.Source.CLOUD, ms);
                }
                return AsrResult.fail("DashScope HTTP " + code + "：" + brief(text),
                        AsrResult.Source.CLOUD, ms);
            }

            JSONObject json = new JSONObject(text);
            String answer = extractText(json);
            if (answer == null || answer.trim().length() == 0) {
                return AsrResult.fail("DashScope 返回为空（可能是纯静音或非语音音频）",
                        AsrResult.Source.CLOUD, ms);
            }

            // 语言识别结果附在 annotations 里，非中文时可以提前拦掉（本工程只做中文指令）
            String lang = extractLanguage(json);

            // ★**近空音频幻觉门**。
            //   实测事故：VAD 送出仅 100ms/0s 音频（见 EnergyGate.arm 的根因分析），
            //   qwen3-asr-flash 拿上下文幻觉出一整段输出，而且**把热词提示词原文
            //   逐字回吐**（"语音识别热词表，以下专有名词请优先按原词识别：必应、bing…"），
            //   439 字原样挂上面板成为"用户指令"。
            //   这里做两道零成本硬校验，命中即判幻觉、丢弃（返回 fail，上层走"没听清"）：
            //   ① 时长↔长度合理性：< 0.8s 的音频不可能有 > 12 字转写（中文语速上限约 7 字/秒，
            //      留一倍余量）；② 提示词回吐：任何以热词表开场白开头的转写都是幻觉。
            //   注意：热词表**不在**合法转写里出现——用户不会把整张表念出来。
            int audioSec = PcmUtil.seconds(audio, sampleRate);
            String ansTrim = answer.trim();
            if (audioSec < 1 && ansTrim.length() > 12) {
                Log.w(TAG, "[HALLUCINATION] 丢弃近空音频幻觉：音频=" + audioSec
                        + "s 却返回 " + ansTrim.length() + " 字：" + brief(ansTrim));
                return AsrResult.fail("近空音频幻觉已丢弃（音频 " + audioSec + "s / 转写 "
                        + ansTrim.length() + " 字）", AsrResult.Source.CLOUD, ms);
            }
            if (ansTrim.startsWith(AsrHotwords.HOTWORD_PROMPT_PREFIX)) {
                Log.w(TAG, "[HALLUCINATION] 丢弃提示词回吐幻觉：云端把热词表原文当成了识别结果"
                        + "（" + ansTrim.length() + " 字）");
                return AsrResult.fail("提示词回吐幻觉已丢弃", AsrResult.Source.CLOUD, ms);
            }
            // ★ device-1102：上下文改成纯词表后不再有前缀可判 → 用词表回吐判据（与流式同一口径）
            if (withHotwords && RealtimeAsrSession.isCorpusEcho(ansTrim, RealtimeAsrSession.corpusFromHotwords(hotwords))) {
                Log.w(TAG, "[HALLUCINATION] 丢弃词表回吐幻觉（" + ansTrim.length() + " 字）");
                return AsrResult.fail("词表回吐幻觉已丢弃", AsrResult.Source.CLOUD, ms);
            }

            // 热词开关一定要打进日志：这是"纠错到底有没有生效"的唯一直接证据。
            // 光看"识别成功"证明不了任何事 —— 热词带上与不带上都会 200。
            Log.i(TAG, "识别成功 (" + ms + "ms, lang=" + lang + ", 热词="
                    + (withHotwords ? (hotwords.trim().length() + "字") : "未带") + ")："
                    + answer + "  音频=" + audioSec + "s");
            return AsrResult.ok(ansTrim, AsrResult.Source.CLOUD, ms);

        } catch (Throwable t) {
            long ms = SystemClock.elapsedRealtime() - t0;
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "识别异常 (" + ms + "ms): " + msg);
            return AsrResult.fail(msg, AsrResult.Source.CLOUD, ms);
        }
    }

    // ------------------------------------------------------------------

    /**
     * 取识别文本。
     *
     * <p>★ 两个端点的响应结构**不一样**，这里都兜住：
     * <ul>
     *   <li>OpenAI 兼容：{@code choices[0].message.content} 是**字符串**；</li>
     *   <li>原生 {@code /api/v1/services/aigc/multimodal-generation/generation}：
     *       {@code output.choices[0].message.content} 是**数组** {@code [{"text":".."}]}。</li>
     * </ul>
     * 只需换端点 URL 就能切换，不必改解析 —— 这是刻意做的容错。
     */
    private static String extractText(JSONObject json) {
        JSONObject root = json;
        if (json.has("output")) {
            root = json.optJSONObject("output");
            if (root == null) return null;
        }
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return null;
        JSONObject c0 = choices.optJSONObject(0);
        if (c0 == null) return null;
        JSONObject message = c0.optJSONObject("message");
        if (message == null) return null;

        Object content = message.opt("content");
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof JSONArray) {           // 原生端点：数组形式
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String s = item.optString("text", "");
                if (s.length() > 0) sb.append(s);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    /** 取语言识别结果（annotations[0].language），失败返回 "?"。 */
    private static String extractLanguage(JSONObject json) {
        try {
            JSONObject root = json.has("output") ? json.optJSONObject("output") : json;
            if (root == null) return "?";
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "?";
            JSONObject message = choices.optJSONObject(0).optJSONObject("message");
            if (message == null) return "?";
            JSONArray ann = message.optJSONArray("annotations");
            if (ann == null || ann.length() == 0) return "?";
            return ann.optJSONObject(0).optString("language", "?");
        } catch (Throwable ignore) {
            return "?";
        }
    }

    /** 错误响应可能很长（含文档链接），截断后入日志与 UI。 */
    private static String brief(String s) {
        return TextUtil.oneLine(s, 300, "...", "(无响应体)");
    }
}
