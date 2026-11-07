package com.onesay.assistant.v2.tts;

/**
 * ★ r2/voice-templates（用户 2026-10-30：「直接模板得了，全开口说话没必要，阅读能力比听力重要的多」）：
 * 交互语音改成一小组固定短句，答案正文只上卡片。纯 Java，JVM 可测。
 *
 * <p>★ arch/voice-sync（2026-11-02）：文案整体换成用户认可的<b>话术 v3</b>（phrasebook/REWRITE-v3.md，
 * 音频 phrasebook/audio-v3/wav 的 41 句，按「状态_序号」一一对应进 assets；响度按原模板口径 RMS≈4000 / 峰值 ≤29000 归一，
 * 24 kHz / 16 bit / mono，干净 44 字节头）。v1 的「好了，看屏幕 / 出错了，看屏幕 / 没连上网络 / 已取消 / 已完成」全部退役，
 * 枚举名保留（调用方不用改），文字 / 音频换成 v3 对应状态。v3 没有的「需要你确认」（confirm_prompt.wav）保留。
 * ★ v3b（2026-11-02）：补合成 8 句（ask_user 提问 / 缺信息 / 登录 / 验证 / 付款 / 没 Key / 设备不可用 / 部分完成），
 * 同一音色与归一口径，替换原暂代句。
 *
 * <p>音频全部是百炼 CosyVoice（cosyvoice-v3.5-flash，复刻音色「c」）预先合成、打包在 assets 里 ——
 * 离线可播、零延迟、不占每次对话的合成额度。
 *
 * <h3>两类</h3>
 * <ul>
 *   <li><b>回答提示</b>（{@link #isReply()}）：替代原来 {@code TtsSpeaker.speakAnswer / speakConfirmation / speakFailure}
 *       的整句朗读，走同一套播报进度（fireStart / fireDone），续听、duck、打断照旧。</li>
 *   <li><b>提示音</b>：唤醒应答、确认卡、没听清、没网、应答（「收到，我来弄」）、催一次（「还在弄，快好了」）、退出 ——
 *       一次性提示，不进回答状态机、不触发续听。</li>
 * </ul>
 */
public enum VoiceTemplate {

    // ---------------------------------------------------------------- 唤醒（WAKE_VOICE_SCREEN_OFF，文件名沿用 wake_ack1/2）
    /** 唤醒应答（wake_ack1.wav = v3 WAKE_VOICE_SCREEN_OFF_01；设置「息屏唤醒时说『我在』」开时才说，规则不变）。 */
    WAKE("我在", "wake_ack1.wav", false),
    /** 唤醒应答轮换（wake_ack2.wav = v3 _02，原「在的」）。 */
    WAKE_2("在呢", "wake_ack2.wav", false),
    /** v3 _03（WakeService 目前只轮换 1/2，备用）。 */
    WAKE_3("来了", "vo_WAKE_VOICE_SCREEN_OFF_03.wav", false),

    // ---------------------------------------------------------------- 结果（回答类）
    /** 动作执行成功 / 收尾无话可说（v3 OK_AGENT_DONE_NOANSWER；ack_done.wav 同步换成这一段）。 */
    DONE("好了", "ack_done.wav", true),
    /** 问答 / 本地接管 / Agent 回答已上卡片（v1「好了，看屏幕」→ v3「好了」）。 */
    ANSWER("好了", "ack_done.wav", true),
    /** 多步全部做完（v3 OK_PLAN_ALL）。 */
    PLAN_ALL("都弄好了", "vo_OK_PLAN_ALL_01.wav", true),
    /** 做了但没回读到结果（v3 OK_UNVERIFIED）。 */
    UNVERIFIED("我点了，不过没看到结果，你看一眼？", "vo_OK_UNVERIFIED_01.wav", true),
    /** 执行失败 / 出错（v1「出错了，看屏幕」→ v3 FAIL_GENERIC）。 */
    ERROR("哎呀，没弄成", "vo_FAIL_GENERIC_01.wav", true),
    /** 循环异常（v3 AGENT_EXCEPTION_01）。 */
    EXCEPTION("哎呀，出岔子了，没弄好", "vo_AGENT_EXCEPTION_01.wav", true),
    /** 卡住 / 防死循环（v3 AGENT_EXCEPTION_02）。 */
    STUCK("卡住了，没弄成", "vo_AGENT_EXCEPTION_02.wav", true),
    /** 执行时没网（v1「没连上网络」→ v3 FAIL_NETWORK_01）。 */
    OFFLINE("没网，弄不了", "vo_FAIL_NETWORK_01.wav", true),
    /** 网络 / 模型服务不稳（v3 FAIL_NETWORK_02）。 */
    NET_WEAK("网不太好，等会儿再试", "vo_FAIL_NETWORK_02.wav", true),
    /** 任务被取消（v1「已取消」→ v3 CANCEL_VOICE_01）。 */
    CANCELLED("好，不弄了", "vo_CANCEL_VOICE_01.wav", true),
    /** 取消轮换（v3 CANCEL_VOICE_02，备用）。 */
    CANCELLED_2("行，那算了", "vo_CANCEL_VOICE_02.wav", true),
    /** 用户在确认卡上拒绝（v3 USER_REJECTED）。 */
    USER_REJECTED("好，不弄了", "vo_USER_REJECTED_01.wav", true),
    /** 超时（v3 FAIL_TIMEOUT）。 */
    TIMEOUT("等了半天没反应，没弄成", "vo_FAIL_TIMEOUT_01.wav", true),
    /** 安全策略拦截（v3 FAIL_GATE_01）。 */
    GATE("这个有风险，我不敢动", "vo_FAIL_GATE_01.wav", true),
    /** 拦截轮换（v3 FAIL_GATE_02，备用）。 */
    GATE_2("这步有点危险，我先没动", "vo_FAIL_GATE_02.wav", true),
    /** 要用户亲手来（登录 / 验证 / 付款 / 计划里有敏感步骤；v3 PLAN_ABORT_GATE）。 */
    HANDOFF("最后一步得你自己点，我不敢乱按", "vo_PLAN_ABORT_GATE_01.wav", true),
    /** 不会 / 没有能用的工具（v3 FAIL_UNSUPPORTED）。 */
    UNSUPPORTED("这个我还不会，抱歉", "vo_FAIL_UNSUPPORTED_01.wav", true),
    /** 要先解锁（v3 FAIL_NEED_UNLOCK）。 */
    NEED_UNLOCK("先解个锁吧", "vo_FAIL_NEED_UNLOCK_01.wav", true),
    /** 问了没回（确认卡 / ask_user 超时；v3 CONFIRM_TIMEOUT）。 */
    CONFIRM_TIMEOUT("你没回我，那我先不弄了", "vo_CONFIRM_TIMEOUT_01.wav", true),

    // ---------------------------------------------------------------- ★ v3b 补合成 8 句（REWRITE-v3.md「v3b 补充 8 句」，替换 VOICE-SYNC.md §7 的暂代句）
    /** needs_user：缺信息（v3b NEED_INFO；原暂代「哎呀，没弄成」）。 */
    NEED_INFO("还缺点信息，你补一句？", "vo_NEED_INFO_01.wav", true),
    /** needs_user：要登录（v3b NEED_LOGIN；原暂代 HANDOFF）。 */
    NEED_LOGIN("得你先登录一下", "vo_NEED_LOGIN_01.wav", true),
    /** needs_user：验证码 / 滑块（v3b NEED_VERIFY；原暂代 HANDOFF）。 */
    NEED_VERIFY("验证得你自己来", "vo_NEED_VERIFY_01.wav", true),
    /** needs_user：付款（v3b NEED_PAY；原暂代 HANDOFF）。 */
    NEED_PAY("付款得你自己点", "vo_NEED_PAY_01.wav", true),
    /** blocked：没 Key / 没配置（v3b BLOCKED_NO_KEY；原暂代「哎呀，没弄成」）。 */
    NO_KEY("这个还没配好，我用不了", "vo_BLOCKED_NO_KEY_01.wav", true),
    /** blocked：设备不可用（读屏助手不可用 / 另一个任务在跑；v3b BLOCKED_DEVICE_BUSY；原暂代「哎呀，没弄成」）。 */
    DEVICE_BUSY("手机这会儿腾不开手，等会儿再试", "vo_BLOCKED_DEVICE_BUSY_01.wav", true),
    /** 多步只完成一部分（v3b PLAN_PARTIAL；原暂代「哎呀，没弄成」）。 */
    PLAN_PARTIAL("弄好了一部分，剩下的没弄成", "vo_PLAN_PARTIAL_01.wav", true),

    // ---------------------------------------------------------------- 过程提示（一次性）
    /** 应答 · 简单（v3 THINK_AGENT_START_01）。 */
    ACK_OK("好的", "vo_THINK_AGENT_START_01.wav", false),
    /** 应答 · 上岛 / GUI（v3 THINK_AGENT_START_02）。 */
    ACK_ON_IT("收到，我来弄", "vo_THINK_AGENT_START_02.wav", false),
    /** 应答 · 子目标 ≥3 / 明显多步（v3 THINK_AGENT_START_03）。 */
    ACK_MANY("这个有点多，我一步步来", "vo_THINK_AGENT_START_03.wav", false),
    /** 应答（v3 THINK_AGENT_START_04，备用）。 */
    ACK_NOW("马上", "vo_THINK_AGENT_START_04.wav", false),
    /** 超过约 8 秒催一次（v3 THINK_STILL_WORKING_01）。 */
    STILL_WORKING("还在弄，快好了", "vo_THINK_STILL_WORKING_01.wav", false),
    /** 催一次轮换（v3 THINK_STILL_WORKING_02，备用）。 */
    STILL_WORKING_2("别急，快了", "vo_THINK_STILL_WORKING_02.wav", false),
    /** 确认卡弹出（敏感操作确认卡 / 执行模式确认；v3 没有这一状态，保留原 confirm_prompt.wav，同一音色 c）。 */
    CONFIRM("需要你确认", "confirm_prompt.wav", false),
    /** ★ v3b：ask_user 提问（v3b ASK_USER_PROMPT；原暂代 CONFIRM「需要你确认」）。字幕仍是问题本身。 */
    ASK_PICK("你选一下", "vo_ASK_USER_PROMPT_01.wav", false),
    /** 新唤醒没听清（v3 ASR_MISS_1_01；续听窗没人说话仍然安静收起，不说这句）。 */
    MISS("没听清，再说一遍？", "vo_ASR_MISS_1_01.wav", false),
    /** 没听清轮换（v3 ASR_MISS_1_02，备用）。 */
    MISS_ALT("刚没听清楚，你再说一次", "vo_ASR_MISS_1_02.wav", false),
    /** 连续第 2 次没听清（v3 ASR_MISS_2）。 */
    MISS_2("还是没听懂，换个说法试试？比如「打开蓝牙」", "vo_ASR_MISS_2_01.wav", false),
    /** 连续 ≥3 次没听清（v3 ASR_MISS_MAX）。 */
    MISS_MAX("我实在没听懂，待会儿再叫我吧", "vo_ASR_MISS_MAX_01.wav", false),
    /** 识别时没网（v3 ASR_OFFLINE_01）。 */
    ASR_OFFLINE("没网了，听不了", "vo_ASR_OFFLINE_01.wav", false),
    /** 识别没网轮换（v3 ASR_OFFLINE_02，备用）。 */
    ASR_OFFLINE_2("网断了，我先歇会儿", "vo_ASR_OFFLINE_02.wav", false),
    /** 续听窗说结束语（v3 EXIT_GOODBYE_01）。 */
    EXIT("好的", "vo_EXIT_GOODBYE_01.wav", false),
    /** 退出轮换（v3 EXIT_GOODBYE_02，备用）。 */
    EXIT_2("那我先走了，有事叫我", "vo_EXIT_GOODBYE_02.wav", false),
    /** 追问时间（v3 ASK_SLOT_TIME，暂未接线）。 */
    ASK_TIME("定几点？", "vo_ASK_SLOT_TIME_01.wav", false),
    ASK_TIME_2("几点？比如「明早七点」", "vo_ASK_SLOT_TIME_02.wav", false),
    /** 追问对象（v3 ASK_SLOT_WHO，暂未接线）。 */
    ASK_WHO_CALL("打给谁？", "vo_ASK_SLOT_WHO_01.wav", false),
    ASK_WHO_SEND("发给谁？", "vo_ASK_SLOT_WHO_02.wav", false),
    /** 追问内容（v3 ASK_SLOT_CONTENT，暂未接线）。 */
    ASK_CONTENT("要发什么？", "vo_ASK_SLOT_CONTENT_01.wav", false),
    ASK_CONTENT_2("想说什么？", "vo_ASK_SLOT_CONTENT_02.wav", false);

    public final String text;
    public final String asset;
    private final boolean mReply;

    VoiceTemplate(String text, String asset, boolean reply) {
        this.text = text;
        this.asset = asset;
        this.mReply = reply;
    }

    public boolean isReply() {
        return mReply;
    }

    /** 执行失败按错误码挑一句（v3）。null → 哎呀，没弄成。 */
    public static VoiceTemplate forFailure(com.onesay.assistant.v2.executor.ErrorCode code) {
        if (code == null) return ERROR;
        switch (code) {
            case CANCELLED:
                return CANCELLED;
            case USER_REJECTED:
            case RISK_REJECTED:
                return USER_REJECTED;
            case NETWORK_ERROR:
            case TLS_ERROR:
                return OFFLINE;
            case TIMEOUT:
            case CHANNEL_TIMEOUT:
                return TIMEOUT;
            case GATE_DENIED:
            case DAEMON_DENIED:
                return GATE;
            case UNKNOWN_CAPABILITY:
                return UNSUPPORTED;
            default:
                return ERROR;
        }
    }

    /** 语音识别失败分型 → 提示音。没识别出东西 = 没听清；网络 = 没网了，听不了；其余（未配置 / Key / 限流 / 服务端 / 超时）= 哎呀，没弄成。 */
    public static VoiceTemplate forAsrFailure(com.onesay.assistant.v2.asr.AsrFailure.Kind kind) {
        if (kind == null) return ERROR;
        switch (kind) {
            case NO_SPEECH:
                return MISS;
            case NETWORK:
                return ASR_OFFLINE;
            default:
                return ERROR;
        }
    }

    /** ★ arch/voice-sync：连续第 n 次（1 起）新唤醒没听清 → v3 ASR_MISS_1 / ASR_MISS_2 / ASR_MISS_MAX。 */
    public static VoiceTemplate forMiss(int consecutive) {
        if (consecutive <= 1) return MISS;
        if (consecutive == 2) return MISS_2;
        return MISS_MAX;
    }

    /**
     * 这次回答用模板还是整句朗读。force = 主动播报（定时提醒 / 「详细说说」，用户要的就是内容）→ 照旧整句；
     * 其余看设置「朗读完整回答」（默认关 → 模板）。
     */
    public static boolean useTemplate(boolean force, boolean fullReadOpt) {
        return !force && !fullReadOpt;
    }

    /**
     * 原「完成应答音」ack_done 要不要单独响：这一轮会播回答模板时不响（模板里已经有「已完成 / 好了，看屏幕」，
     * 否则会连说两句）；不出声的回合（文字回合等）和「朗读完整回答」开着时照旧响。
     */
    public static boolean ackDoneSuppressed(boolean replyWillSpeak, boolean fullReadOpt) {
        return replyWillSpeak && !fullReadOpt;
    }

    /**
     * 模板全文（去标点）——给「扬声器自己的话」黑名单用：续听窗万一收进回读，整句等于模板就丢弃。
     * ★ arch/voice-sync：v3 有「好了 / 好的」这类同时是用户结束语 / 应答词的短句 —— 结束语（{@code ConversationTurnPolicy.isDismissPhrase}）
     * 和 ≤2 字的短句不进黑名单（「好了」仍是结束语，用户说「好的」不能被吞）。
     */
    public static boolean isTemplateEcho(String text) {
        if (text == null) return false;
        String s = strip(text);
        if (s.length() <= 2) return false;
        if (com.onesay.assistant.v2.wake.ConversationTurnPolicy.isDismissPhrase(s)) return false;
        for (VoiceTemplate t : values()) {
            if (s.equals(strip(t.text))) return true;
        }
        return false;
    }

    private static String strip(String s) {
        return s.replaceAll("[\\s，。？！、,.!?：；「」“”]", "");
    }

    // ------------------------------------------------------------------ WAV（assets 里的模板音频）

    /** 解析出来的 PCM（16 bit mono）。 */
    public static final class Pcm {
        public final int sampleRate;
        public final byte[] data;

        Pcm(int sampleRate, byte[] data) {
            this.sampleRate = sampleRate;
            this.data = data;
        }

        public long durationMs() {
            return sampleRate <= 0 ? 0 : data.length * 1000L / (sampleRate * 2L);
        }
    }

    /**
     * 解析 RIFF/WAVE：只认 PCM 16 bit 单声道（模板都是这个格式）；格式不对 / 截断 → null（调用方当「模板缺失」处理）。
     */
    public static Pcm parseWav(byte[] b) {
        if (b == null || b.length < 44) return null;
        if (!tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) return null;
        int pos = 12;
        int sr = -1;
        int ch = -1;
        int bits = -1;
        int fmtTag = -1;
        while (pos + 8 <= b.length) {
            int len = le32(b, pos + 4);
            int body = pos + 8;
            if (len < 0 || body + (long) len > b.length) {
                // data 块长度写多了（流式写的 wav 常见）：按文件剩余算
                if (tag(b, pos, "data") && fmtTag == 1 && ch == 1 && bits == 16 && sr > 0) {
                    return pcm(sr, b, body, b.length - body);
                }
                return null;
            }
            if (tag(b, pos, "fmt ") && len >= 16) {
                fmtTag = le16(b, body);
                ch = le16(b, body + 2);
                sr = le32(b, body + 4);
                bits = le16(b, body + 14);
            } else if (tag(b, pos, "data")) {
                if (fmtTag != 1 || ch != 1 || bits != 16 || sr <= 0) return null;
                return pcm(sr, b, body, len);
            }
            pos = body + len + (len & 1);
        }
        return null;
    }

    private static Pcm pcm(int sr, byte[] b, int off, int len) {
        len &= ~1;
        if (len <= 0) return null;
        byte[] d = new byte[len];
        System.arraycopy(b, off, d, 0, len);
        return new Pcm(sr, d);
    }

    private static boolean tag(byte[] b, int off, String t) {
        if (off + 4 > b.length) return false;
        for (int i = 0; i < 4; i++) if (b[off + i] != (byte) t.charAt(i)) return false;
        return true;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
