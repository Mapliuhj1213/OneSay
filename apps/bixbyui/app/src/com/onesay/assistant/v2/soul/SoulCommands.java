package com.onesay.assistant.v2.soul;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [soul] 语音里管人设 / 画像的几句话（纯函数、保守：认不准就不接管，交回原流程）。
 *
 * <table>
 *   <tr><td>{@link Type#CALL_ME}</td><td>「以后叫我老王」「称呼我小刘」</td><td>画像（称呼），立即写，不确认</td></tr>
 *   <tr><td>{@link Type#STYLE}</td><td>「你以后回答简短点」「以后说话别那么啰嗦」</td><td>画像（回答风格），立即写，不确认</td></tr>
 *   <tr><td>{@link Type#PERSONA_ADD}</td><td>「把你的人设加一条：多用比喻」「改一下你的性格：幽默一点」</td><td>人设提议 → 确认卡 → 生效（可回滚）</td></tr>
 *   <tr><td>{@link Type#PERSONA_NAME}</td><td>「你的名字改成小贝」「以后你就叫小贝」</td><td>身份名字提议 → 确认卡</td></tr>
 *   <tr><td>{@link Type#PERSONA_ROLLBACK}</td><td>「人设恢复上一版」「把人设改回去」</td><td>确认卡</td></tr>
 *   <tr><td>{@link Type#PERSONA_RESET}</td><td>「恢复默认人设」</td><td>确认卡</td></tr>
 *   <tr><td>{@link Type#CLEAR_CHAT}</td><td>「清空这次对话」「删掉刚才的对话」</td><td>清当前会话上下文（忘记后「一并删除对话」）</td></tr>
 * </table>
 */
public final class SoulCommands {

    private SoulCommands() {}

    public enum Type { CALL_ME, STYLE, PERSONA_ADD, PERSONA_NAME, PERSONA_ROLLBACK, PERSONA_RESET, CLEAR_CHAT }

    public static final class Cmd {
        public final Type type;
        public final String value;

        Cmd(Type type, String value) {
            this.type = type;
            this.value = value;
        }

        @Override public String toString() { return type + (value != null ? " len=" + value.length() : ""); }
    }

    private static final Pattern QUESTION = Pattern.compile("[？?]|什么|啥|吗$|么$|怎么|如何|多少|哪个|哪里|是不是|能不能");

    private static final Pattern CALL_ME = Pattern.compile(
            "^(?:以后|今后|往后|从现在起|从今往后)?(?:你)?(?:就)?(?:都)?(?:叫我|称呼我|喊我)([\\u4e00-\\u9fa5A-Za-z]{1,6}?)(?:吧|就行|就好|好了)?$");
    private static final Pattern CALL_NOT = Pattern.compile("起床|起来|吃饭|一声|一下|回来|过去|出门|下楼|开会|醒|去|来|别|不要");

    private static final Pattern STYLE = Pattern.compile(
            "^(?:你)?(?:以后|今后|往后|从现在起|从今往后)(?:你)?(?:说话|回答|回复|讲话|跟我说话)(?:的时候)?(?:要|就|都|尽量)?(.{2,20})$");

    private static final Pattern PERSONA_ADD = Pattern.compile(
            "^(?:请|帮我|你)?(?:把)?(?:你的)?(?:人设|性格|个性)(?:里)?(?:加一条|加上|添一条|补一条|改成|改为|换成|调整为|调成|更新为|要)[:：,，]?(.{2,60})$"
          + "|^(?:请|帮我)?(?:改|修改|调整|更新)(?:一下)?(?:你的)?(?:人设|性格|个性)[:：,，]?(.{2,60})$");

    private static final Pattern PERSONA_NAME = Pattern.compile(
            "^(?:把)?你的名字(?:改成|改为|换成|就叫)([\\u4e00-\\u9fa5A-Za-z0-9]{1,8}?)(?:吧|好了)?$|^(?:以后|今后)?你(?:就)?(?:改名叫|改名为)([\\u4e00-\\u9fa5A-Za-z0-9]{1,8}?)(?:吧|好了)?$"
          + "|^以后你就叫([\\u4e00-\\u9fa5A-Za-z0-9]{1,8}?)(?:吧|好了)?$");

    private static final Pattern ROLLBACK = Pattern.compile(
            "(?:人设|性格|个性)(?:恢复|回滚|退回|还原|改回)(?:到)?(?:上一版|上个版本|之前的|原来的|上次的)|^(?:把)?(?:你的)?人设(?:改回去|撤销|回滚)");

    private static final Pattern RESET = Pattern.compile("^(?:把)?(?:你的)?(?:人设)?(?:恢复|重置|还原)(?:成|为|到)?默认(?:的)?(?:人设)$|^重置(?:你的)?人设$");

    private static final Pattern CLEAR_CHAT = Pattern.compile(
            "^(?:帮我|请|你)?(?:把)?(?:清空|删掉|删除|清除|忘掉)(?:这次|本次|刚才|刚刚|这段|我们)?(?:的)?(?:对话|聊天|会话)(?:记录|内容|上下文)?(?:吧|也删了|一起删了)?$");

    /** ★ device-1102：「新对话 / 换个话题 / 重新开始」= 立即结束当前会话（会话保持 5 分钟后，这是用户主动重置的口令）。 */
    private static final Pattern NEW_TOPIC = Pattern.compile(
            "^(?:我们|咱们)?(?:开始|开|开个|来个|换个|新开)?(?:一个)?(?:新对话|新的对话|新会话|新话题|新的话题)(?:吧)?$"
                    + "|^(?:我们|咱们)?换(?:个|一个)话题(?:吧)?$|^(?:我们|咱们)?重新开始(?:吧|聊)?$|^不聊这个了$");

    public static Cmd parse(String utterance) {
        if (utterance == null) return null;
        String u = utterance.trim().replaceAll("[。！!，,\\s]+$", "").replaceAll("\\s+", "");
        if (u.length() == 0 || u.length() > 70) return null;
        Matcher m;
        if (CLEAR_CHAT.matcher(u).find() || NEW_TOPIC.matcher(u).find()) return new Cmd(Type.CLEAR_CHAT, null);
        if (ROLLBACK.matcher(u).find()) return new Cmd(Type.PERSONA_ROLLBACK, null);
        if (RESET.matcher(u).find()) return new Cmd(Type.PERSONA_RESET, null);
        if (QUESTION.matcher(u).find()) return null;
        if ((m = PERSONA_NAME.matcher(u)).find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            return new Cmd(Type.PERSONA_NAME, v);
        }
        if ((m = PERSONA_ADD.matcher(u)).find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            if (v == null || SoulSensitive.reason(v) != null) return null;
            return new Cmd(Type.PERSONA_ADD, v);
        }
        if ((m = CALL_ME.matcher(u)).find()) {
            String v = m.group(1);
            if (CALL_NOT.matcher(v).find()) return null;
            return new Cmd(Type.CALL_ME, v);
        }
        if ((m = STYLE.matcher(u)).find()) {
            String v = m.group(1);
            if (SoulSensitive.reason(v) != null) return null;
            return new Cmd(Type.STYLE, u.replaceFirst("^你", ""));
        }
        return null;
    }

    /** 锁屏时要换成「先解锁」的 S2 回答类型（回忆 / 回忆没命中 / 列表都会暴露记过什么）。 */
    public static boolean lockedHides(String s2Type) {
        return "RECALL".equals(s2Type) || "RECALL_MISS".equals(s2Type) || "LIST".equals(s2Type);
    }

    public static final String LOCKED_REPLY = "手机还锁着，记忆里的内容要解锁后才能看。解锁后再问我一次。";

    /** 画像条目文本。 */
    public static String profileText(Cmd c) {
        if (c.type == Type.CALL_ME) return "叫我" + c.value;
        return c.value;
    }
}
