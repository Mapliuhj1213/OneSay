package com.onesay.assistant.v2.phone;

import java.util.Locale;

/**
 * ★ borrow-1112：手机操作每一步的结构化回执 {@code {ok, code, next}}（纯 Java，JVM 可测）。
 *
 * <p>以前每步结果只是一句中文（「已点击」「没执行：REF_STALE：…」），模型得自己读懂；现在每步另附一行固定格式的回执：
 * <ul>
 *   <li>{@code ok}：这一步算不算做成；一批里停在哪一步，那一步就是 {@code ok=false}，后面的不再执行；</li>
 *   <li>{@code code}：固定枚举 {@link Code}（不是自由文字），旧码 {@code REF_STALE} 统一成 {@link Code#STALE_ELEMENT}，
 *       {@link Code#of} 仍认旧名（兼容旧日志 / 旧提示词）；</li>
 *   <li>{@code next}：建议的下一步 {@link Next}——先看画面 / 改命令 / 问用户 / 稍后重试 / 继续。</li>
 * </ul>
 * 中文说明照旧保留在同一行里（旧断言、旧日志不受影响），回执跟在后面：{@code 回执 {"ok":false,"code":"STALE_ELEMENT","next":"look"}}。
 *
 * <p>思路借鉴 Agent-VScreen（https://github.com/kilolonion/agent-vscreen，MIT）的「每次结果带 ok / code / next」与
 * STALE_ELEMENT（用了已不在画面上的旧编号就报错，不点到别的东西）；代码是自己写的，没有复制其源码。
 */
public final class StepReceipt {

    /** 固定错误 / 状态码。 */
    public enum Code {
        /** 做成了。 */
        OK,
        /** 做了，但动作前后画面没有变化（可能没点中 / 已经是这个状态）—— ok 仍为 true，建议先看画面。 */
        NO_CHANGE,
        /** 编号指的元素已不在画面上 / 位置变了且有同名元素（旧码 REF_STALE）。 */
        STALE_ELEMENT,
        /** 编号本来就不在当前界面里（不是「过期」，是给错了）。 */
        NOT_FOUND,
        /** 参数不对（缺 id、缺包名、往非输入框里写字…）。 */
        BAD_ARGS,
        /** 执行了但失败（点击失败、打不开 App…）。 */
        ACTION_FAILED,
        /** 等的条件到超时也没出现 / 没消失。 */
        TIMEOUT,
        /** 界面意外变化（弹窗、换了 App），剩下的步交回。 */
        UNEXPECTED_SCREEN,
        /** 用户在确认卡上拒绝了这一步。 */
        USER_REFUSED,
        /** 用户接手了（点了接管 / 按了返回或主页键）。 */
        USER_TAKEOVER,
        /** 用户停止了任务。 */
        STOPPED,
        /** 付款 / 转账的最后一下只能用户亲手点（能批就不拦硬规则 ①）。 */
        NEEDS_USER_TAP,
        /** 不支持的动作。 */
        UNSUPPORTED;

        /** 认旧名：REF_STALE → STALE_ELEMENT；不认识的 → null。 */
        public static Code of(String s) {
            if (s == null) return null;
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (t.equals(StableRead.REF_STALE)) return STALE_ELEMENT;
            for (Code c : values()) if (c.name().equals(t)) return c;
            return null;
        }
    }

    /** 建议的下一步。 */
    public enum Next {
        CONTINUE("continue", "继续"), LOOK("look", "先看画面"), FIX("fix", "改命令"), ASK_USER("ask_user", "问用户"), RETRY_LATER("retry_later", "稍后重试");

        public final String id, zh;

        Next(String id, String zh) {
            this.id = id;
            this.zh = zh;
        }
    }

    public final boolean ok;
    public final Code code;
    public final Next next;

    public StepReceipt(boolean ok, Code code, Next next) {
        this.ok = ok;
        this.code = code == null ? Code.OK : code;
        this.next = next == null ? defaultNext(this.code) : next;
    }

    public static StepReceipt of(Code c) {
        return new StepReceipt(c == Code.OK || c == Code.NO_CHANGE, c, defaultNext(c));
    }

    /** 每个码默认的下一步。 */
    public static Next defaultNext(Code c) {
        if (c == null) return Next.CONTINUE;
        switch (c) {
            case OK: return Next.CONTINUE;
            case NO_CHANGE:
            case STALE_ELEMENT:
            case UNEXPECTED_SCREEN:
            case USER_TAKEOVER: return Next.LOOK;
            case NOT_FOUND:
            case BAD_ARGS:
            case UNSUPPORTED: return Next.FIX;
            case USER_REFUSED:
            case NEEDS_USER_TAP:
            case STOPPED: return Next.ASK_USER;
            case TIMEOUT:
            case ACTION_FAILED: return Next.RETRY_LATER;
            default: return Next.LOOK;
        }
    }

    /** 紧凑 JSON（固定键顺序，便于模型和测试读）。 */
    public String json() {
        return "{\"ok\":" + ok + ",\"code\":\"" + code.name() + "\",\"next\":\"" + next.id + "\"}";
    }

    /** 跟在中文说明后面的那一截。 */
    public String tail() {
        return " 回执 " + json();
    }

    @Override public String toString() {
        return json();
    }

    // ------------------------------------------------------------------ 从旧的中文结果归类

    /** PhonePlan.validate 的说明 → 码（编号不在 = NOT_FOUND，其余参数问题 = BAD_ARGS）。 */
    public static Code ofValidate(String bad) {
        if (bad == null) return Code.OK;
        if (bad.contains("不在当前界面")) return Code.NOT_FOUND;
        return Code.BAD_ARGS;
    }

    /**
     * 执行结果（exec 的返回）+ 等待说明 + 画面变没变 → 回执。
     * @param op 动作名
     */
    public static StepReceipt ofEffect(String op, String effect, String wait, boolean changed) {
        String e = effect == null ? "" : effect;
        String w = wait == null ? "" : wait;
        if (e.equals("不支持")) return of(Code.UNSUPPORTED);
        if (e.contains("失败") || e.startsWith("打不开")) return of(Code.ACTION_FAILED);
        if (w.startsWith("没等到")) return of(Code.TIMEOUT);
        boolean expectsChange = "tap".equals(op) || "long".equals(op) || "back".equals(op) || "launch".equals(op) || "swipe".equals(op);
        if (expectsChange && !changed) return of(Code.NO_CHANGE);
        return of(Code.OK);
    }
}
