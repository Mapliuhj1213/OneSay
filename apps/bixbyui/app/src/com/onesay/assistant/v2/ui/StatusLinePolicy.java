package com.onesay.assistant.v2.ui;

/**
 * [round7] 面板状态行副标题在「答案已上屏」时怎么改（纯 Java，JVM 可测）。
 *
 * <h3>问题（10-10 真机 ro5_store2.png / d2_redcard.png）</h3>
 * <p>每条指令提交时状态行先进「正在解析」，副标题写死「云端大模型思考中...」；之后答案若由本地层直接给出
 * （识屏指代「屏幕上没找到链接」、角色层「没有安装曹操出行」、L0 / AskEngine 本地问答…），
 * {@code showAskReply} 只把标题改成「智能问答」，副标题没人管 —— 答案都上屏了，还在说云端大模型思考中。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>副标题还是过渡态（「…思考中」「正在…」「唤醒确认」）→ 换成终态：云端作答 = {@link #DONE_CLOUD}，
 *       本地 / 非云端 = {@link #DONE_LOCAL}，来源未知 = {@link #DONE_UNKNOWN}；</li>
 *   <li>副标题已经是别人写的终态 / 说明（「确认后才会执行该操作」「已拒绝」…）→ 不动（返回 null）。</li>
 * </ul>
 */
public final class StatusLinePolicy {

    private StatusLinePolicy() {}

    /** 提交指令时的过渡副标题（FloatingSheetView「正在解析」分支）。 */
    public static final String PARSING = "云端大模型思考中...";
    public static final String DONE_CLOUD = "云端大模型 · 已作答";
    public static final String DONE_LOCAL = "本地直答 · 未调用云端";
    public static final String DONE_UNKNOWN = "已作答";

    /** 回答来源。 */
    public enum Source { CLOUD, LOCAL, UNKNOWN }

    /** 副标题是不是过渡态（答案上屏后不该再留着）。 */
    public static boolean isTransient(String sub) {
        if (sub == null) return true;
        String s = sub.trim();
        return s.length() == 0 || s.contains("思考中") || s.startsWith("正在") || s.contains("唤醒确认");
    }

    /**
     * 答案上屏后的副标题；返回 null = 不改。
     *
     * @param currentSub 当前副标题
     * @param source     回答来源
     */
    public static String afterReply(String currentSub, Source source) {
        if (!isTransient(currentSub)) return null;
        if (source == Source.CLOUD) return DONE_CLOUD;
        if (source == Source.LOCAL) return DONE_LOCAL;
        return DONE_UNKNOWN;
    }
}
