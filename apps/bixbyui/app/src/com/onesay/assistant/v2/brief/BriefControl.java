package com.onesay.assistant.v2.brief;

/**
 * 晨间播报 v2 的 4 条语音控制（lane-a 第 2 项，PLAN ④「再播一遍 / 跳过 / 详细说说 / 停」）。纯 Java，JVM 可测。
 *
 * <h3>怎么控制 v2</h3>
 * v2 的 {@code V2ControlReceiver} 是 exported=false，只能 root 发：
 * <pre>am broadcast -n com.onesay.clock/.V2ControlReceiver -a com.onesay.clock.&lt;REPLAY|SKIP|DETAIL|STOP&gt; [--es index N]</pre>
 * （包名取自 {@link BriefState#SENDER_PKG}；开源版时钟侧尚无该接收端。）
 * 命令由 {@link #amCommand} 用常量拼出（动作来自枚举、序号是 0..{@link #MAX_INDEX} 的整数），不拼接任何用户原话。
 * DETAIL 时 v2 只回一条 {@code BRIEF_STATE(DETAIL, index)}，展开由 {@link BriefDetail} 在 OneSay 一侧用大模型 + 搜索做。
 *
 * <h3>什么时候接管（避免抢走别的意图的「再播一遍 / 停」）</h3>
 * <ul>
 *   <li>句子里点名了简报 / 播报 / 新闻（「再播一遍简报」「跳过这条新闻」）→ 总是接管；</li>
 *   <li>只说「再播一遍 / 跳过 / 详细说说 / 停」这类裸短句 → 只在简报「活跃」时接管：
 *       正在播，或最近 {@link #ACTIVE_WINDOW_MS} 内有过简报事件（岛上刚播完 / 刚生成）。「停」只在正在播时接管。</li>
 * </ul>
 *
 * <h3>按 Bixby 键</h3>
 * 简报播放期间按键 = 只停简报，这一次按键不唤醒（用户 10-22 23:52 定「简报播放时按键 = 停」）。同一次按键会经
 * 文件 / Activity 等多条通道到达，{@link #KEY_DEDUP_MS} 内的后续按键来源一并吞掉。
 */
public final class BriefControl {

    private BriefControl() {}

    public static final String PKG = BriefState.SENDER_PKG;
    public static final String RECEIVER = PKG + "/.V2ControlReceiver";
    public static final String ACTION_PREFIX = PKG + ".";
    /** 新闻条数上限（v2 照读 5 条原标题），多给几位余量。 */
    public static final int MAX_INDEX = 9;
    /** 裸短句接管的活跃窗口。 */
    public static final long ACTIVE_WINDOW_MS = 10L * 60L * 1000L;
    /** 同一次按键的多通道去抖窗口。 */
    public static final long KEY_DEDUP_MS = 1500L;

    public enum Cmd { REPLAY, SKIP, DETAIL, STOP }

    /** 一条解析结果：命令 + 新闻序号（0 起；只有 DETAIL 用，-1 = 没说第几条）。 */
    public static final class Parsed {
        public final Cmd cmd;
        public final int index;

        Parsed(Cmd cmd, int index) {
            this.cmd = cmd;
            this.index = index;
        }

        @Override public String toString() {
            return cmd + (index >= 0 ? "#" + index : "");
        }
    }

    // ------------------------------------------------------------------ 解析

    static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if ("，。！？、,.!?~～…；;：:\"'“”‘’".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        String q = sb.toString();
        // 礼貌前缀 / 语气尾巴
        String[] pre = {"请你", "麻烦", "帮我", "请", "你"};
        for (String p : pre) if (q.startsWith(p) && q.length() > p.length()) q = q.substring(p.length());
        String[] suf = {"一下吧", "一下", "吧", "呢", "啊", "呀", "了"};
        for (String p : suf) if (q.endsWith(p) && q.length() > p.length()) q = q.substring(0, q.length() - p.length());
        return q;
    }

    /** 句子里是否点名了简报。 */
    public static boolean mentionsBrief(String q) {
        return q.contains("简报") || q.contains("播报") || q.contains("新闻") || q.contains("早报");
    }

    /**
     * 解析一句话。
     *
     * @param raw       用户原话
     * @param active    简报此刻是否活跃（{@link #isActive}）
     * @param playing   简报此刻是否在播
     * @return 不是简报控制返回 null
     */
    public static Parsed parse(String raw, boolean active, boolean playing) {
        String q = norm(raw);
        if (q.length() == 0 || q.length() > 20) return null;
        boolean named = mentionsBrief(q);
        String core = q.replace("晨间简报", "").replace("晨间播报", "").replace("简报", "").replace("播报", "")
                .replace("这条新闻", "这条").replace("新闻", "").replace("早报", "");

        Cmd cmd = null;
        if (anyEq(core, "再播一遍", "再播一次", "重播", "重新播", "重新播放", "从头播", "从头再播", "再放一遍", "再听一遍",
                "再说一遍", "再念一遍", "再读一遍", "从头开始", "重来一遍")) {
            cmd = Cmd.REPLAY;
        } else if (anyEq(core, "跳过", "跳过这条", "下一条", "下一段", "跳过这段", "跳到下一条", "下一个", "换一条")) {
            cmd = Cmd.SKIP;
        } else if (core.startsWith("详细说说") || core.startsWith("详细讲讲") || core.startsWith("展开说说")
                || core.startsWith("展开讲讲") || core.startsWith("具体说说") || core.startsWith("详细说一下")
                || core.startsWith("这条详细说说") || core.startsWith("说说这条") || core.startsWith("讲讲这条")
                || (core.startsWith("第") && (core.contains("详细") || core.contains("展开") || core.contains("具体")))) {
            cmd = Cmd.DETAIL;
        } else if (anyEq(core, "停", "停止", "停下", "停一下", "别播", "别说", "别念", "不要播", "不听", "不用播",
                "关掉", "闭嘴", "安静", "停止播放", "别播了")) {
            cmd = Cmd.STOP;
        }
        if (cmd == null) return null;
        if (cmd == Cmd.STOP) {
            if (!playing && !named) return null;   // 裸「停」只在正在播时归简报
        } else if (!named && !active) {
            return null;
        }
        return new Parsed(cmd, cmd == Cmd.DETAIL ? ordinal(q) : -1);
    }

    private static boolean anyEq(String s, String... opts) {
        for (String o : opts) if (s.equals(o)) return true;
        return false;
    }

    /** 「第二条 / 第 3 条 / 第三个」→ 0 起序号；没说 = -1。 */
    public static int ordinal(String q) {
        int i = q.indexOf('第');
        if (i < 0 || i + 1 >= q.length()) return -1;
        char c = q.charAt(i + 1);
        int n;
        if (c >= '1' && c <= '9') n = c - '0';
        else {
            n = "一二三四五六七八九".indexOf(c) + 1;
            if (c == '两') n = 2;
        }
        if (n <= 0 || n - 1 > MAX_INDEX) return -1;
        return n - 1;
    }

    // ------------------------------------------------------------------ 命令

    /** root 执行的广播命令（常量拼接，不含用户文本）。DETAIL 的 index &lt; 0 时按 0。 */
    public static String amCommand(Cmd cmd, int index) {
        if (cmd == null) throw new IllegalArgumentException("cmd");
        StringBuilder sb = new StringBuilder("am broadcast -n ").append(RECEIVER).append(" -a ").append(ACTION_PREFIX).append(cmd.name());
        if (cmd == Cmd.DETAIL) {
            int ix = index < 0 ? 0 : Math.min(index, MAX_INDEX);
            sb.append(" --es index ").append(ix);
        }
        return sb.toString();
    }

    /** 给用户的一句回话（面板 / 岛）。 */
    public static String ackText(Parsed p) {
        switch (p.cmd) {
            case REPLAY: return "好，简报从头再播一遍。";
            case SKIP: return "好，跳到下一条。";
            case DETAIL: return "好，" + (p.index >= 0 ? "第" + (p.index + 1) + "条" : "这条") + "我查一下再详细说说。";
            default: return "好，简报停了。";
        }
    }

    // ------------------------------------------------------------------ 活跃判断 / 按键

    /** 活跃 = 正在播，或最近 {@link #ACTIVE_WINDOW_MS} 内有过简报事件。 */
    public static boolean isActive(boolean playingNow, long lastEventAt, long now) {
        if (playingNow) return true;
        return lastEventAt > 0 && now >= lastEventAt && now - lastEventAt <= ACTIVE_WINDOW_MS;
    }

    /**
     * 这一次按键唤醒要不要吞掉（只停简报、不唤醒）。纯函数。
     *
     * @param keySource    唤醒来源是否按键类（key-*）
     * @param playingNow   简报是否在播
     * @param lastStopAt   上一次因按键停简报的时刻（0 = 没有）
     */
    public static KeyDecision onKey(boolean keySource, boolean playingNow, long lastStopAt, long now) {
        return onKey(keySource, playingNow, lastStopAt, 0, 0, now);
    }

    /**
     * 同一次物理按键（守护 v7/v8 的 seq 相同）的后续事件一并吞掉的上限：长按 → 按住 ≥ 2 s 松手（随口记）这种
     * 会拖过 {@link #KEY_DEDUP_MS}，所以按 seq 认「同一次」；上限只防守护重启后 seq 复用。
     */
    public static final long KEY_SAME_PRESS_MS = 30L * 1000L;

    /**
     * ★ BUG-A1：带按键序号的版本。
     *
     * @param lastStopSeq 上一次因按键停简报时那次按键的 seq（0 = 不知道 / 不是按键文件通道）
     * @param seq         这一次事件的 seq（0 = 不知道：key-activity / key-sim / 旧守护）
     * <ul>
     *   <li>seq 相同（都 &gt; 0）且在 {@link #KEY_SAME_PRESS_MS} 内 → 同一次按键（短按的另一路通道、长按、长按后松手、随口记收音）→ 吞掉；</li>
     *   <li>seq 都知道但不同 → 是新的一次按键，不套 {@link #KEY_DEDUP_MS} 时间去抖（停完简报紧接着再按一次 = 正常唤醒）；</li>
     *   <li>有一边不知道 seq → 退回时间去抖（原行为）。</li>
     * </ul>
     */
    public static KeyDecision onKey(boolean keySource, boolean playingNow, long lastStopAt, int lastStopSeq, int seq, long now) {
        if (!keySource) return KeyDecision.PASS;
        boolean recent = lastStopAt > 0 && now >= lastStopAt;
        boolean seqKnown = seq > 0 && lastStopSeq > 0;
        if (recent && seqKnown && seq == lastStopSeq && now - lastStopAt <= KEY_SAME_PRESS_MS) return KeyDecision.SWALLOW_DUP;
        if (recent && !seqKnown && now - lastStopAt <= KEY_DEDUP_MS) return KeyDecision.SWALLOW_DUP;
        return playingNow ? KeyDecision.STOP_AND_SWALLOW : KeyDecision.PASS;
    }

    public enum KeyDecision { PASS, STOP_AND_SWALLOW, SWALLOW_DUP }
}
