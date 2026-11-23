package com.onesay.assistant.v2.ui.console;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ feat/console-ui（设计稿 ①）：首页的问候、「可以这样说」、输入栏行为。纯 Java，JVM 可测。
 */
public final class ConsoleHomeModel {

    private ConsoleHomeModel() {}

    // ------------------------------------------------------------------ 问候

    /** 按钟点问候（App 里没有可靠的用户称呼，就不带名字，不编）。 */
    public static String greeting(int hour) {
        if (hour >= 5 && hour < 11) return "早上好";
        if (hour >= 11 && hour < 13) return "中午好";
        if (hour >= 13 && hour < 18) return "下午好";
        if (hour >= 18) return "晚上好";
        return "夜深了";
    }

    public static final String SUBTITLE = "想让我干点什么？说一句或打字都行";
    public static final String SUGGEST_TITLE = "可以这样说";
    public static final String TODAY_TITLE = "今天";
    public static final String INPUT_HINT = "打字，或按住麦克风说话";
    public static final String BAR_HINT = "点一下说话 · 按住说完松手";

    // ------------------------------------------------------------------ 可以这样说

    public static final class Suggestion {
        /** 点了就当作用户说了这句。 */
        public final String say;
        /** 下面的小字（做什么）。 */
        public final String tag;

        Suggestion(String say, String tag) {
            this.say = say;
            this.tag = tag;
        }

        @Override public String toString() {
            return say + "/" + tag;
        }
    }

    /** 四条；没配对电脑时「交给电脑」那条换成问天气（点了做不成的建议不摆出来）。 */
    public static List<Suggestion> suggestions(boolean pcPaired) {
        List<Suggestion> l = new ArrayList<Suggestion>();
        l.add(new Suggestion("明早 7 点叫我", "定闹钟"));
        l.add(new Suggestion("看看屏幕上是什么", "识屏"));
        l.add(pcPaired ? new Suggestion("把这张图发到电脑", "交给电脑") : new Suggestion("明天天气怎么样", "查天气"));
        l.add(new Suggestion("半小时后提醒喝水", "提醒"));
        return l;
    }

    /** 发出一句话的出口（页面里是「打开对话窗并提交」，测试里是记录）。 */
    public interface Sender {
        void send(String text);
    }

    /** 点了一条建议：原话发出（就像用户自己打的）。 */
    public static boolean tapSuggestion(Suggestion s, Sender out) {
        if (s == null || out == null) return false;
        return submit(s.say, out);
    }

    /** 输入框发送：去掉首尾空白，空的不发。 */
    public static boolean submit(String text, Sender out) {
        if (text == null || out == null) return false;
        if (!com.onesay.assistant.v2.ui.widget.SendMicPolicy.hasText(text)) return false;   // 全是空白（含全角空格）不发
        String t = text.trim();
        out.send(t);
        return true;
    }

    // ------------------------------------------------------------------ 麦克风：点一下说话 · 按住说完松手

    /** 按住多久算「按住说」（松手就算说完）。短于它 = 点一下（自己听到说完为止）。 */
    public static final long HOLD_MS = 450L;

    public enum MicRelease {
        /** 点一下：已经开始听了，等自然说完。 */
        TAP_KEEP_LISTENING,
        /** 按住说完松手：说过话 → 马上识别。 */
        HOLD_FINISH,
        /** 按住但一个字没说就松手：停掉，不发。 */
        HOLD_CANCEL
    }

    public static MicRelease onRelease(long heldMs, boolean heardSpeech) {
        if (heldMs < HOLD_MS) return MicRelease.TAP_KEEP_LISTENING;
        return heardSpeech ? MicRelease.HOLD_FINISH : MicRelease.HOLD_CANCEL;
    }

    /**
     * 输入框去掉首尾空白后有字 → 麦克风位换成纸飞机「发送」；清空 → 换回麦克风（点麦克风只录音，不弹键盘）。
     * 和对话窗底栏同一个判断（{@code SendMicPolicy.hasText}，全角空格也算空白）。
     */
    public static boolean showSend(String text) {
        return com.onesay.assistant.v2.ui.widget.SendMicPolicy.hasText(text);
    }


    // ------------------------------------------------------------------ ☰ 历史（只有当前这次对话：App 不存以前的对话）

    /** 每条回答预览最多几个字。 */
    public static final int HISTORY_PREVIEW = 40;

    /** 这次对话说过的几句 {问, 答的开头}（去掉内部的「更早摘要」那条）。 */
    public static List<String[]> historyRows(List<com.onesay.assistant.v2.router.ConversationMemory.Turn> turns) {
        List<String[]> out = new ArrayList<String[]>();
        if (turns == null) return out;
        for (com.onesay.assistant.v2.router.ConversationMemory.Turn t : turns) {
            if (t == null || t.question == null) continue;
            if (com.onesay.assistant.v2.router.ConversationMemory.SUMMARY_QUESTION.equals(t.question)) continue;
            String q = t.question.trim();
            if (q.length() == 0) continue;
            String a = t.answer == null ? "" : t.answer.replace('\n', ' ').trim();
            if (a.length() > HISTORY_PREVIEW) a = a.substring(0, HISTORY_PREVIEW - 1) + "…";
            out.add(new String[]{q, a});
        }
        return out;
    }
}
