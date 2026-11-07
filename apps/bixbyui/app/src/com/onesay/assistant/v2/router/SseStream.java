package com.onesay.assistant.v2.router;

import android.util.Log;



/**
 * OpenAI 兼容端点的 SSE（Server-Sent Events）流式读取器。
 *
 * <h3>为什么需要它</h3>
 * 改造前，ASK 通道是"一次性交付"：用户问完，面板停在"正在解析"，等检索（1~3s）
 * 加生成（1~2s）全部走完，才把整段答案一次贴上去。用户看到的是
 * "长时间空转 → 啪地出现一大段字"，割裂感就来自这段真空期。
 *
 * 现在改为 SSE 逐字上屏：**检索阶段给阶段提示，生成阶段每来一段就刷一次**，
 * 用户在第一秒内就能看到内容开始长出来。
 *
 * <h3>职责边界</h3>
 * 本类只做三件事：拼 HTTP、逐行读 SSE、把 {@code delta.content} 累积回抛。
 * 它**不认识**提示词、不认识业务、不做任何文本加工 —— 那些都在
 * {@link L2LLMCompiler#askStream} 里。
 *
 * <h3>踩过的坑（改这里之前先看）</h3>
 * <ol>
 *   <li><b>不能对 content 用 {@code optString}</b>。org.json 的 {@code optString}
 *       内部走 {@code JSON.toString(value)}，而首帧的 {@code "content": null}
 *       会变成字符串 {@code "null"} 被吐出来 —— 屏幕上会莫名出现一个 "null"。
 *       必须先 {@code opt()} 再做 {@code instanceof String} 判断。</li>
 *   <li><b>readTimeout 必须留足</b>。非流式请求 12s 够用，流式的中途停顿
 *       （模型思考下一步）也可能超过它，超时会切断一条已经吐了一半的回答。</li>
 *   <li><b>取消要看得到</b>。用户点掉面板后，读取循环必须在下一行就退出，
 *       否则线程会抱着连接一直读到模型说完。</li>
 * </ol>
 */
public final class SseStream {

    private static final String TAG = "LlmStream";

    /** 增量回调：每收到一段就把**累积全文**回抛（UI 侧直接整体 setText，天然幂等）。 */
    public interface DeltaSink {
        void onDelta(String accumulated);
    }

    /** 取消令牌：置位后读取循环在下一行退出。 */
    /** ★ int/1102：可选：思考模式下第一次收到思维链增量时回调一次（只用于「思考中」状态；思维链本身不累积、不上屏）。 */
    public interface ThinkingSink extends DeltaSink {
        void onThinking();
    }

    public interface Cancel {
        boolean isCancelled();
    }

    /** 一次流式请求的结果。 */
    public static final class Result {
        /** 是否干净地拿到了至少一段增量 */
        public boolean ok;
        /** 累积全文（失败时也可能有半截，调用方可以拿来兜底） */
        public String text = "";
        /** 失败原因（成功时为 null） */
        public String error;
        public int httpCode;
        /** 收到的增量段数 —— 这个数字本身就是"是不是真的在流"的证据 */
        public int frames;
    }

    private SseStream() {}

    /**
     * 从一行 SSE 文本里抽出增量。
     *
     * <p>兼容三种行：{@code data: {...}}、裸 {@code {...}}、以及必须忽略的
     * {@code :heartbeat} 注释行与 {@code data: [DONE]} 结束行。
     *
     * @return 增量文本；该行不含内容时返回 <b>null</b>（不是空串，调用方靠它跳过）
     */
    public static String parseDelta(String line) {
        // 解析逻辑迁入共用的 LlmResponse（router 内只保留一份）
        return com.onesay.assistant.v2.net.LlmResponse.deltaOf(line);
    }

    /** 是否是流结束标记行。 */
    public static boolean isDoneLine(String line) {
        return com.onesay.assistant.v2.net.LlmResponse.isDoneLine(line);
    }

    /**
     * 发起一次流式请求并读到结束。
     *
     * @param sink   增量回调（可为 null，只为拿最终全文时）
     * @param cancel 取消令牌（可为 null）
     * @return 永不返回 null
     */
    public static Result post(String urlStr, final String apiKey, String jsonBody,
                              final DeltaSink sink, final Cancel cancel) {
        // 建连/读流交给共用的 LlmClient（读超时 30s，见类注释"坑 2"）；
        //   这里只保留"累积增量 + 取消 + 半截兜底"这层业务语义，行为与改造前一致。
        final Result r = new Result();
        final StringBuilder acc = new StringBuilder();
        final boolean[] thinkingSeen = { false };
        com.onesay.assistant.v2.net.LlmClient client = new com.onesay.assistant.v2.net.LlmClient(urlStr,
                new com.onesay.assistant.v2.net.LlmClient.KeyProvider() {
                    @Override public String apiKey() { return apiKey; }
                }, 8000, 30000);
        com.onesay.assistant.v2.net.LlmClient.StreamStatus st = client.stream(jsonBody,
                new com.onesay.assistant.v2.net.LlmClient.StreamSink() {
                    @Override public boolean onLine(String line) {
                        if (cancel != null && cancel.isCancelled()) {
                            r.error = "cancelled";
                            Log.i(TAG, "SSE 被取消，已收 " + r.frames + " 段 / " + acc.length() + " 字");
                            return false;
                        }
                        String piece = parseDelta(line);
                        if (piece == null) {
                            if (!thinkingSeen[0] && sink instanceof ThinkingSink && com.onesay.assistant.v2.net.ThinkingMode.reasoningDeltaOf(line) != null) {
                                thinkingSeen[0] = true;
                                ((ThinkingSink) sink).onThinking();
                            }
                            return true;
                        }
                        // ★ 实测：DeepSeek 首帧是空串增量，跳过（否则 TTFT 被算早、屏幕闪空白卡）
                        if (piece.length() == 0) return true;
                        acc.append(piece);
                        r.frames++;
                        if (sink != null) sink.onDelta(acc.toString());
                        return true;
                    }
                });
        r.httpCode = st.httpCode;
        r.text = acc.toString();
        if (st.error != null && r.error == null) {
            if (st.httpCode != 200 && st.httpCode != 0) {
                r.error = st.error;
                Log.w(TAG, "SSE 建连失败: " + r.error);
            } else {
                // 半截也算数：网络断在中途时，已经吐出来的字比什么都没有强
                r.error = (r.frames > 0 ? "流中断" : "连接异常") + "(" + st.error + ")";
                Log.w(TAG, "SSE 异常: " + r.error + "，已收 " + r.frames + " 段");
            }
            return r;
        }
        r.ok = (r.frames > 0) && (r.error == null);
        if (r.ok) {
            Log.i(TAG, "SSE 完成: " + r.frames + " 段 / " + r.text.length() + " 字");
        } else if (r.error == null) {
            r.error = "端点未返回任何增量（可能不支持 stream 参数）";
            Log.w(TAG, r.error);
        }
        return r;
    }
}
