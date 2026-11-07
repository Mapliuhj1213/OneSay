package com.onesay.assistant.v2.wake;

/**
 * ★ r2/input（2026-10-24）唤醒后语音录入的「说完了没有」裁决（纯 Java，JVM 单测；WakeService 每帧调一次 {@link #decide}）。
 *
 * <h3>为什么改</h3>
 * 用户反馈：「识别时间太短，必须连续说话不能换气，不符合人类习惯」。审计（docs/r2-input.md §1）找到两处真正的切点：
 * <ol>
 *   <li><b>流式主路</b>：服务端 VAD 静音 450 ms 就出 completed，而 R14 收到第一条 completed 就关会话、直接执行 ——
 *       说话中换一口气（中文口语停顿常见 0.5–1.5 s）就被切成半句；</li>
 *   <li><b>非流式兜底</b>：自适应断句尾静音短句 0.7 s / 长句 0.9 s。</li>
 * </ol>
 *
 * <h3>新规则（参考值见调研，docs/r2-input.md §2）</h3>
 * <ul>
 *   <li>服务端 VAD 只负责<b>分段</b>（700 ms 出一段 completed，越快越好拿到文字），段先攒着，<b>不再一段就执行</b>；</li>
 *   <li>本地按「已识别文字的完整度」决定要等多久的尾静音才算说完：
 *     <ul>
 *       <li>{@link Completeness#INCOMPLETE}（以「然后、和、给、把、还有、帮我…」或逗号 / 顿号结尾，或只有一个要宾语的动词「打开」）：
 *           {@link #DEF_INCOMPLETE_MS} = 3.2 s；</li>
 *       <li>{@link Completeness#NEUTRAL}（看不出来）：{@link #DEF_NEUTRAL_MS} = 2.0 s（≈ Siri 默认停顿时长）；</li>
 *       <li>{@link Completeness#COMPLETE}（问句 / 「吗呢吧」/ 动词 + 宾语的完整短指令）：{@link #DEF_COMPLETE_MS} = 1.3 s
 *           （≈ sherpa-onnx 端点规则 2 的 1.2 s）；</li>
 *     </ul>
 *   </li>
 *   <li>服务端 VAD 说「正在说」→ 一律不断句；</li>
 *   <li>没有文字可看（非流式兜底）→ 交给 {@link EndpointDetector}（新默认短句 1.2 s / 长句 1.8 s）。</li>
 * </ul>
 * 首字前的准备时间、整段上限（30 s）、手动结束由 WakeService 管（捕获窗 / 续期 / {@code finishCaptureNow}）。
 */
public final class UtteranceEndpointer {

    public static final int DEF_COMPLETE_MS = 1300;
    public static final int DEF_NEUTRAL_MS = 2000;
    public static final int DEF_INCOMPLETE_MS = 3200;
    /** 任何情况下尾静音不超过这个（防一句「然后」把麦克风挂住太久）。 */
    public static final int MAX_SILENCE_MS = 4000;
    /** 尾静音达到这么久才开始画「快要发送」的倒计时环（之前是正常换气，不打扰）。 */
    public static final int RING_DELAY_MS = 400;

    public enum Completeness { COMPLETE, NEUTRAL, INCOMPLETE }

    public enum Action {
        /** 继续听。 */
        WAIT,
        /** 流式已经给了全部文字（没有在途的半段）→ 把攒下的段拼起来提交。 */
        COMMIT_TEXT,
        /** 流式还有一段没出最终结果 → 再等一小会儿（grace），到点用已有文字 / 兜底。 */
        WAIT_FOR_FINAL,
        /** 不归流式管 → 非流式整段识别。 */
        FIRE_NONSTREAM
    }

    private final int completeMs;
    private final int neutralMs;
    private final int incompleteMs;

    public UtteranceEndpointer(int completeMs, int neutralMs, int incompleteMs) {
        this.completeMs = clamp(completeMs, EndpointDetector.FLOOR_MS, MAX_SILENCE_MS);
        this.neutralMs = clamp(Math.max(neutralMs, this.completeMs), EndpointDetector.FLOOR_MS, MAX_SILENCE_MS);
        this.incompleteMs = clamp(Math.max(incompleteMs, this.neutralMs), EndpointDetector.FLOOR_MS, MAX_SILENCE_MS);
    }

    public static UtteranceEndpointer defaults() {
        return new UtteranceEndpointer(DEF_COMPLETE_MS, DEF_NEUTRAL_MS, DEF_INCOMPLETE_MS);
    }

    public int completeMs() { return completeMs; }
    public int neutralMs() { return neutralMs; }
    public int incompleteMs() { return incompleteMs; }

    // ------------------------------------------------------------------ 完整度

    /** 结尾是这些标点 = 话没说完（逗号 / 顿号 / 冒号 / 分号 / 省略号）。 */
    private static final String OPEN_PUNCT = "，,、：:；;…—-";
    /** 句末标点（判断前先剥掉；问号 / 叹号另算完整）。 */
    private static final String END_PUNCT = "。.！!？?～~ \t\n\r\u3000";

    /**
     * 以这些词结尾 = 后面还有话。按长度从长到短匹配（「然后」先于「后」…… 这里没有单字「后」）。
     * 单字只收几乎不会出现在完整指令末尾的虚词 / 介词（「和 / 跟 / 给 / 把 / 让 / 被 / 向 / 往 / 从 / 对 / 在 / 到 / 是 / 要 / 想 / 就 / 再 / 还 / 又 / 也 / 或」）。
     * 「的」「了」不收：「这是我的」「好了」是完整的。
     */
    static final String[] CONNECTIVES = {
            // 连接 / 承接
            "然后呢", "然后", "接着", "之后", "还有就是", "还有", "并且", "而且", "以及", "或者", "还是", "还要", "另外",
            "就是说", "就是", "因为", "所以", "但是", "不过", "如果", "要是", "的话", "比如说", "比如", "例如", "那么", "那个", "这个",
            "就是那个", "什么的",
            // 请求开头还没到宾语
            "帮我把", "帮我给", "帮我", "麻烦你", "麻烦", "请你", "我想要", "我想", "我要", "能不能", "可不可以", "可以帮我",
            // 语气填充
            "嗯", "呃", "额", "啊那个",
            // 单字虚词 / 介词
            "和", "跟", "与", "及", "给", "把", "让", "被", "向", "往", "从", "对", "在", "是", "要", "想", "就", "再", "还", "又",
            "也", "或", "请", "帮"
    };

    /**
     * 要宾语的动词：句子以它结尾 = 宾语还没说（「帮我打开」）。但「暂停播放」「继续播放」这类本身是完整指令 ——
     * 先过 {@link #COMMAND_VERBS} 规则，没命中才按没说完算。
     */
    static final String[] OBJECT_VERBS = {
            "打开", "关闭", "关掉", "开启", "搜索", "搜一下", "查一下", "查查", "查询", "播放", "放一首", "发给", "发送给", "告诉",
            "提醒我", "设置", "设一个", "定一个", "导航到", "导航去", "打给", "打电话给", "发消息给", "发短信给", "问一下", "翻译",
            "叫", "去"
    };

    /** 句末语气词 = 一句问话 / 请求已经说完。 */
    private static final String END_PARTICLES = "吗呢吧嘛么";

    /** 以疑问词结尾的问句（ASR 没加问号时）= 说完了。 */
    static final String[] QUESTION_ENDINGS = {
            "几点了", "几点", "怎么样", "什么", "多少", "多少钱", "哪里", "哪儿", "为什么", "怎么办", "几度", "多久", "谁", "几号", "星期几"
    };

    /** 完整的短指令开头（动词 + 至少一个宾语字）。 */
    static final String[] COMMAND_VERBS = {
            "打开", "关闭", "关掉", "开启", "调高", "调低", "调大", "调小", "增大", "减小", "播放", "暂停", "停止", "继续",
            "静音", "取消", "退出", "返回", "截屏", "截图", "拍照", "录音"
    };

    /** 整句就是一个完整指令（不需要宾语）。 */
    static final String[] WHOLE_COMMANDS = {
            "几点了", "现在几点", "下一首", "上一首", "暂停", "停止", "继续", "取消", "退出", "算了", "好的", "好", "是的", "不用了",
            "不要了", "不要", "不用", "不想", "没有", "对", "不对", "关灯", "开灯", "静音", "截屏", "截图", "拍照", "返回", "回到桌面"
    };

    public static Completeness classify(String text) {
        if (text == null) return Completeness.NEUTRAL;
        String t = text.trim();
        if (t.length() == 0) return Completeness.NEUTRAL;
        char last = t.charAt(t.length() - 1);
        if (OPEN_PUNCT.indexOf(last) >= 0) return Completeness.INCOMPLETE;
        boolean question = last == '？' || last == '?';
        boolean exclaim = last == '！' || last == '!';
        String core = stripEnd(t);
        if (core.length() == 0) return Completeness.NEUTRAL;
        for (String w : WHOLE_COMMANDS) {
            if (core.equals(w)) return Completeness.COMPLETE;
        }
        // 「然后？」这类：标点是 ASR 加的，词才是证据
        for (String c : CONNECTIVES) {
            if (core.endsWith(c)) return Completeness.INCOMPLETE;
        }
        if (question || exclaim) return Completeness.COMPLETE;
        if (END_PARTICLES.indexOf(core.charAt(core.length() - 1)) >= 0) return Completeness.COMPLETE;
        for (String q : QUESTION_ENDINGS) {
            if (core.endsWith(q)) return Completeness.COMPLETE;
        }
        String body = stripPolitePrefix(core);
        for (String v : COMMAND_VERBS) {
            if (body.startsWith(v) && body.length() >= v.length() + 1) return Completeness.COMPLETE;
        }
        for (String v : OBJECT_VERBS) {
            if (core.endsWith(v)) return Completeness.INCOMPLETE;
        }
        return Completeness.NEUTRAL;
    }

    /** 尾静音要等多久（毫秒）。text 为空 / null → 中性。 */
    public int requiredSilenceMs(String text) {
        switch (classify(text)) {
            case COMPLETE: return completeMs;
            case INCOMPLETE: return incompleteMs;
            default: return neutralMs;
        }
    }

    // ------------------------------------------------------------------ 每帧裁决

    /** 一帧的输入（WakeService 读循环 / 单测模拟同一套字段）。 */
    public static final class Input {
        public long now;
        /** 本地能量门：本窗第一次 / 最后一次人声（0 = 没有）。 */
        public long firstSpeechAt, lastSpeechAt;
        public int speechFrames, minFrames;
        /** 最短语句保护（EndpointDetector.utteranceLongEnough 的结果）。 */
        public boolean longEnough = true;
        /** 本窗识别归流式管。 */
        public boolean rtOwns;
        /** 服务端 VAD 此刻认为正在说。 */
        public boolean rtSpeaking;
        /** 流式已出的段（拼好的）、在途的半段（中间结果）。 */
        public String finals = "", partial = "";
        /** 流式最后一次有动静（开始说话 / 中间结果 / 段结束）的时刻（0 = 没有）。 */
        public long rtActivityAt;
        /** 非流式兜底的尾静音（EndpointDetector 算好的；rtOwns 时不用）。 */
        public int noTextSilenceMs = DEF_NEUTRAL_MS;
    }

    /** 裁决结果：动作 + 这一帧用的尾静音 + 已静音时长 + 倒计时环进度（0..1，0 = 不画）。 */
    public static final class Verdict {
        public final Action action;
        public final int requiredMs;
        public final long silentMs;
        public final float ring;
        public final Completeness completeness;

        Verdict(Action action, int requiredMs, long silentMs, float ring, Completeness c) {
            this.action = action;
            this.requiredMs = requiredMs;
            this.silentMs = silentMs;
            this.ring = ring;
            this.completeness = c;
        }

        @Override
        public String toString() {
            return action + " 尾静音 " + silentMs + "/" + requiredMs + "ms " + completeness;
        }
    }

    public Verdict decide(Input in) {
        String text = in.rtOwns ? joinForJudge(in.finals, in.partial) : null;
        Completeness c = text == null ? Completeness.NEUTRAL : classify(text);
        int required = in.rtOwns ? requiredSilenceMs(text) : Math.max(EndpointDetector.FLOOR_MS, in.noTextSilenceMs);
        if (in.firstSpeechAt <= 0 || in.lastSpeechAt <= 0 || in.speechFrames < Math.max(1, in.minFrames)) {
            return new Verdict(Action.WAIT, required, 0L, 0f, c);
        }
        long lastAct = Math.max(in.lastSpeechAt, in.rtOwns ? in.rtActivityAt : 0L);
        long silent = Math.max(0L, in.now - lastAct);
        if (in.rtOwns && in.rtSpeaking) return new Verdict(Action.WAIT, required, 0L, 0f, c);
        float ring = ringProgress(silent, required);
        if (silent <= required || !in.longEnough) return new Verdict(Action.WAIT, required, silent, ring, c);
        if (!in.rtOwns) return new Verdict(Action.FIRE_NONSTREAM, required, silent, 1f, c);
        boolean pending = in.partial != null && in.partial.trim().length() > 0;
        boolean haveFinals = in.finals != null && in.finals.trim().length() > 0;
        if (haveFinals && !pending) return new Verdict(Action.COMMIT_TEXT, required, silent, 1f, c);
        return new Verdict(Action.WAIT_FOR_FINAL, required, silent, 1f, c);
    }

    /** 倒计时环：静音 {@link #RING_DELAY_MS} 之后才出现，到 required 满一圈。 */
    public static float ringProgress(long silentMs, int requiredMs) {
        if (silentMs <= RING_DELAY_MS || requiredMs <= RING_DELAY_MS) return 0f;
        float p = (silentMs - RING_DELAY_MS) / (float) (requiredMs - RING_DELAY_MS);
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }

    // ------------------------------------------------------------------ 段拼接

    /** 把服务端分出的段拼成一句交给路由：段尾的句号改成逗号（「打开微信。然后给张三发消息。」→「打开微信，然后给张三发消息。」）。 */
    public static String joinSegments(java.util.List<String> segs) {
        StringBuilder sb = new StringBuilder();
        if (segs == null) return "";
        for (String s : segs) {
            if (s == null) continue;
            String t = s.trim();
            if (t.length() == 0) continue;
            if (sb.length() > 0) {
                char prev = sb.charAt(sb.length() - 1);
                if (prev == '。' || prev == '.') sb.setCharAt(sb.length() - 1, '，');
                else if (OPEN_PUNCT.indexOf(prev) < 0 && prev != '？' && prev != '?' && prev != '！' && prev != '!') sb.append('，');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    /** 裁决看的文字 = 已出段 + 在途半段（只用于判断完整度）。 */
    static String joinForJudge(String finals, String partial) {
        String f = finals == null ? "" : finals.trim();
        String p = partial == null ? "" : partial.trim();
        if (p.length() == 0) return f;
        if (f.length() == 0) return p;
        return f + p;
    }

    private static String stripEnd(String t) {
        int e = t.length();
        while (e > 0 && END_PUNCT.indexOf(t.charAt(e - 1)) >= 0) e--;
        return t.substring(0, e);
    }

    private static String stripPolitePrefix(String t) {
        String[] p = {"帮我", "请你", "请", "麻烦你", "麻烦", "给我", "你"};
        for (String s : p) {
            if (t.startsWith(s) && t.length() > s.length()) return t.substring(s.length());
        }
        return t;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
