package com.onesay.assistant.v2.asr;

/**
 * 语音识别通道抽象。
 *
 * 一个 "通道" = 一种把 PCM 变成文字的能力（本地 vosk / 阿里云一句话识别 / 将来的流式通道）。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #recognize} 是**阻塞**调用，实现方负责自己的超时；调用方在后台线程调用，
 *       绝不要在麦克风线程或主线程直接调。</li>
 *   <li>实现方**不得抛异常**——任何失败都要收敛成 {@link AsrResult#fail}。
 *       这是刻意的：识别失败是常态（断网、超时、额度耗尽），
 *       不该让一个网络抖动把整条语音链路炸掉。</li>
 *   <li>{@link #isReady} 必须廉价（只读内存状态），因为它会被热路径查询。</li>
 * </ul>
 */
public interface AsrChannel {

    /** 通道名，用于日志（如 "offline-vosk" / "aliyun-oneshot"）。 */
    String name();

    /** 当前是否可用（配置齐全、Token 有效等）。必须廉价，不做 IO。 */
    boolean isReady();

    /**
     * 把一段 PCM 识别成文字。
     *
     * @param pcm        16-bit little-endian 单声道 PCM 原始字节
     * @param sampleRate 采样率（本工程恒为 16000）
     * @return 识别结果，永不为 null
     */
    AsrResult recognize(byte[] pcm, int sampleRate);

    /**
     * 带**热词上下文**的识别（2026-10-03 新增）。
     *
     * <p>热词 = 本次识别可能出现的专有名词（搜索引擎、应用名、系统控制词…），
     * 由 {@link AsrHotwords} 构建。它的作用是纠正**声学层**的同音误判：
     * 用户说"必应"被听成"毕竟"，光看音频两者确实难分，
     * 但如果模型事先知道"这次可能出现『必应』"，就能把候选调过来。
     *
     * <p>为什么要放在识别环节而不是识别完再改文本：
     * 文本层替换是治标（声学错法无穷，枚举追不上），而且会污染溯源 ——
     * 替换之后日志与字幕都不是 ASR 的真实输出，出了问题没法区分"没听见"和"听错了"。
     * 详见 {@link AsrHotwords} 的类注释。
     *
     * @param hotwords 热词上下文文本；为 null 或空表示本次不带先验
     *                 （不支持热词的通道应当忽略此参数，而不是报错）
     */
    AsrResult recognize(byte[] pcm, int sampleRate, String hotwords);
}
