package com.onesay.assistant.v2.agent;

import com.onesay.assistant.v2.s1.AskUserBroker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * ★ arch/agent-loop（B 路）：ask_user 的宿主侧辅助（纯 Java，JVM 可测）。
 *
 * <ul>
 *   <li>{@link #sanitizeOptions}：选项去空去重，最多 4 个；少于 2 个当作自由回答（不给芯片）。</li>
 *   <li>{@link #await}：在 {@link AskUserBroker} 上等答案，每 250 ms 看一眼是否被取消（停止键 / 新指令接管），
 *       超时或取消 → 撤回问题，返回 null（= no_answer）。</li>
 *   <li>{@link #open} / {@link #interceptVoice}：<b>麦克风 / 打字兜底</b> —— 有问题在等时，用户下一句话（「第二个」「选 B」「就 XX」或直接说内容）
 *       当作回答回填，而不是开一个新请求。岛上芯片（C 路）和面板芯片也走同一个回填入口。</li>
 * </ul>
 * 选项借鉴 Mika ask_user（Apache-2.0，wangzan101/mika core/general/OutcomeTools.kt：2–8 个选项、no_answer 语义）；本项目收紧为 2–4 个。
 */
public final class AskUserSupport {

    private AskUserSupport() {}

    public static final int MAX_OPTIONS = 4;

    /** ★ 11-04 ③：一个任务最多问一次。 */
    public static final int MAX_ASKS_PER_TASK = 1;

    /** ★ 11-04 ③：第二次 ask_user 的观测：不再问，按默认继续并写明假设。 */
    public static final String ALREADY_ASKED_OBS = "{\"ok\":false,\"tool\":\"ask_user\",\"code\":\"ALREADY_ASKED\","
            + "\"note\":\"这个任务已经问过用户一次了，不要再问。按最常见、最合理的默认继续做，并在答复开头用一句话写明你的假设（用户可以再改）。\"}";

    /** 「你决定」类默认项。 */
    public static final String DEFAULT_OPTION = "你决定";

    private static final java.util.regex.Pattern DELEGATE = java.util.regex.Pattern.compile(
            "^(?:好|好的|行|可以|嗯|都行|都可以|随便|随意|无所谓|你决定|你定|你来定|你看着办|看着办|你挑|你选|按你的|听你的|默认|就这样)(?:吧|的|啊|呀|。|！|!)?$");

    /** 用户把决定权交回来（「好 / 你决定 / 随便 / 都行」）。 */
    public static boolean delegated(String answer) {
        return answer != null && DELEGATE.matcher(answer.trim()).matches();
    }

    /** ★ 11-04 ③：有 2–3 个选项且里面没有「你决定 / 都行 / 随便」类 → 末尾补「你决定」（给默认、一句话就能答）。 */
    public static List<String> withDefault(List<String> opts) {
        if (opts == null || opts.size() < 2 || opts.size() >= MAX_OPTIONS) return opts;
        for (String o : opts) if (o != null && (delegated(o) || o.contains("你决定") || o.contains("都行") || o.contains("随便"))) return opts;
        List<String> out = new ArrayList<String>(opts);
        out.add(DEFAULT_OPTION);
        return out;
    }

    /** 宿主侧取消查询。 */
    public interface Cancel {
        boolean isCancelled();
    }

    /** 撤回问题（宿主实现 = S1Hooks.cancelAskUser）。 */
    public interface Withdraw {
        void withdraw(String id);
    }

    /** 选项规整：trim、去空、去重、最多 4 个；有效项 &lt; 2 → 空列表（自由回答）。 */
    public static List<String> sanitizeOptions(List<String> in) {
        List<String> out = new ArrayList<String>();
        if (in != null) {
            for (String s : in) {
                if (s == null) continue;
                String t = s.trim();
                if (t.length() == 0 || out.contains(t)) continue;
                if (t.length() > 24) t = t.substring(0, 24);
                out.add(t);
                if (out.size() >= MAX_OPTIONS) break;
            }
        }
        return out.size() < 2 ? Collections.<String>emptyList() : out;
    }

    /**
     * 阻塞等答案。返回答案；超时 / 取消 → 撤回并返回 null。
     *
     * @param clock 毫秒时钟（JVM 测试可注入）；null = System.nanoTime
     */
    public static String await(AskUserBroker broker, String id, long timeoutMs, Cancel cancel, Withdraw withdraw, Clock clock) {
        if (broker == null || id == null) return null;
        Clock c = clock != null ? clock : Clock.SYSTEM;
        long deadline = c.now() + Math.max(0L, timeoutMs);
        try {
            while (true) {
                if (!broker.isPending(id)) {
                    String a = broker.take(id);
                    clearIfCurrent(id);
                    return a;
                }
                if (cancel != null && cancel.isCancelled()) break;
                if (c.now() >= deadline) break;
                c.sleep(250L);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (withdraw != null) {
            try {
                withdraw.withdraw(id);
            } catch (Throwable ignored) {
            }
        } else {
            broker.expire(id);
        }
        broker.take(id);
        clearIfCurrent(id);
        return null;
    }

    /** 时钟（JVM 测试用假时钟）。 */
    public interface Clock {
        long now();

        void sleep(long ms) throws InterruptedException;

        Clock SYSTEM = new Clock() {
            @Override public long now() {
                return System.nanoTime() / 1000000L;
            }

            @Override public void sleep(long ms) throws InterruptedException {
                Thread.sleep(ms);
            }
        };
    }

    // ------------------------------------------------------------------ 语音 / 打字兜底

    private static volatile String sOpenId;
    private static volatile List<String> sOpenOptions = Collections.emptyList();

    /** 记下当前在等的问题（宿主问之前调；同一时刻只有一个）。 */
    public static void open(String id, List<String> options) {
        sOpenOptions = options == null ? Collections.<String>emptyList() : new ArrayList<String>(options);
        sOpenId = id;
    }

    public static String openId() {
        return sOpenId;
    }

    public static List<String> openOptions() {
        return sOpenOptions;
    }

    static void clearIfCurrent(String id) {
        if (id != null && id.equals(sOpenId)) {
            sOpenId = null;
            sOpenOptions = Collections.emptyList();
        }
    }

    public static void clearForTest() {
        sOpenId = null;
        sOpenOptions = Collections.emptyList();
    }

    private static final String[] ORDINALS = {"一", "二", "三", "四"};
    private static final String[] LETTERS = {"a", "b", "c", "d"};

    /**
     * 把用户的一句话对到选项上（纯函数）：「第二个 / 2 / 选B / B」→ 第 2 项；包含某个选项原文 → 该项；
     * 都对不上 → 原话（自由回答）。空 → null。
     */
    public static String matchAnswer(String text, List<String> options) {
        if (text == null) return null;
        String t = text.trim().replaceAll("[。！!？?，,、\\s]+$", "").trim();
        if (t.length() == 0) return null;
        if (options == null || options.isEmpty()) return t;
        String low = t.toLowerCase(Locale.ROOT).replace(" ", "");
        String core = low.replaceAll("^(?:选|我选|就|要|用|那就|就选|选择)", "").replaceAll("(?:吧|啊|呀|好了|那个|这个|项|个)+$", "");
        for (int i = 0; i < options.size() && i < MAX_OPTIONS; i++) {
            String n = String.valueOf(i + 1);
            if (core.equals(n) || core.equals("第" + n) || core.equals("第" + ORDINALS[i]) || core.equals(ORDINALS[i])
                    || core.equals(LETTERS[i])) {
                return options.get(i);
            }
        }
        // 原文精确 / 包含（长的优先，避免「是」吃掉「是的，继续」）
        String best = null;
        for (String o : options) {
            String ol = o.toLowerCase(Locale.ROOT).replace(" ", "");
            if (ol.equals(core) || ol.equals(low)) return o;
            if (ol.length() >= 2 && low.contains(ol) && (best == null || ol.length() > best.length())) best = o;
        }
        return best != null ? best : t;
    }

    /**
     * 有问题在等时，把这句话当回答：返回要回填的 (id, 答案)；没有在等的问题 → null（照常处理这句话）。
     * 「取消 / 停止」不当回答（交给原流程取消任务）。
     */
    public static String[] interceptVoice(String text) {
        String id = sOpenId;
        if (id == null || text == null || text.trim().length() == 0) return null;
        String t = text.trim();
        if (t.equals("取消") || t.equals("停止") || t.equals("停") || t.equals("算了")) return null;
        String a = matchAnswer(t, sOpenOptions);
        return a == null ? null : new String[]{id, a};
    }
}
