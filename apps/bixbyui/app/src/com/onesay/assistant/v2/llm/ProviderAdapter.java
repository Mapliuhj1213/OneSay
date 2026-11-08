package com.onesay.assistant.v2.llm;

import org.json.JSONObject;

import java.util.List;

/**
 * 一种接口协议的适配器（纯 Java + org.json，无网络、无 Android，JVM 可测）。
 *
 * <p>App 里其余代码一律按 OpenAI Chat Completions 的格式组请求体、解析回复；适配器只在出口 / 入口翻译：
 * <ul>
 *   <li>{@link #chat}：OpenAI 格式请求体 → 这家的 {@link WireRequest}（地址、头、正文；Key 发送时再填）；</li>
 *   <li>{@link #toOpenAi}：这家的非流式回复 → OpenAI {@code chat.completion} 形状（{@code LlmResponse.parse} 照常能读）；</li>
 *   <li>{@link #newStream}：这家的 SSE 行 → OpenAI 流式行（{@code data: {"choices":[{"delta":…}]}}，结尾 {@code data: [DONE]}）；</li>
 *   <li>{@link #check}：「测试」按钮发的那一个请求（尽量用不花 token 的模型列表 / 模型详情接口）。</li>
 * </ul>
 */
public interface ProviderAdapter {

    ProviderKind kind();

    WireRequest chat(String base, String model, JSONObject openAiBody, boolean stream) throws Exception;

    JSONObject toOpenAi(String body, String model) throws Exception;

    StreamTranslator newStream(String model);

    WireRequest check(String base, String model);

    /** 「测试」回复的判定（2xx 时可再看正文，比如模型列表里有没有这个模型）。 */
    ProviderErrors.Verdict checkVerdict(int status, String body, String model);

    /** 流式翻译：一行进，零或多行 OpenAI 格式出。 */
    interface StreamTranslator {
        List<String> onLine(String line) throws Exception;

        /** 流读完（EOF）时补的行；对方没给结束标记也要补 {@code data: [DONE]}。 */
        List<String> finish();

        /** 已经收到对方的结束信号（之后的行可以不读了）。 */
        boolean done();
    }
}
