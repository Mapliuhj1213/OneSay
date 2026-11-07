package com.onesay.assistant.v2.errand.web;

/**
 * ★ lane C3-2：浏览代理的硬上限与「每步读回校验」（纯 Java）。
 *
 * <h3>上限（研究报告 §3.1.3 建议值，略收紧）</h3>
 * ★ fast-screen-v2：与 App 操作代理同一套 {@link com.onesay.assistant.v2.phone.TaskBudget}（原来死卡 20 步 / 180 s）：
 * 模型调用 ≤ {@link #MAX_STEPS} 次、≤ {@link #MAX_MS} 毫秒（用户接手 / 回答的时间不计）、
 * 无进展（连续 3 轮页面没变 / 同一动作原地重复 3 次）或连续 {@link #MAX_FAILS} 步校验失败 = 卡住。
 * 到上限先问用户（{@link BrowserAgent.BudgetAsk}），用户说继续就再给一份预算；没人可问 / 用户不继续时
 * 带着已完成到哪一步返回（LIMIT），不是只报「失败」。
 *
 * <h3>读回校验（每一步之后重新抽一次页面）</h3>
 * <ul>
 *   <li>点击：URL 或内容签名变了，或被点的元素是输入框 / 下拉（聚焦 / 展开不一定改内容）；</li>
 *   <li>输入：读回的值等于要输的字（提交时则要求页面变化）；</li>
 *   <li>滚动：滚动位置变了；翻页 / 返回 / 打开：URL 或签名变了。</li>
 * </ul>
 * 校验不过 → 告诉模型「上一步没生效」让它换法子，不盲目重复。
 */
public final class BrowsePolicy {

    private BrowsePolicy() {}

    /** 默认模型调用数（= TaskBudget.DEF_MODEL_CALLS；可续）。 */
    public static final int MAX_STEPS = com.onesay.assistant.v2.phone.TaskBudget.DEF_MODEL_CALLS;
    /** 默认总时长（= TaskBudget.DEF_TOTAL_MS；可续）。 */
    public static final long MAX_MS = com.onesay.assistant.v2.phone.TaskBudget.DEF_TOTAL_MS;
    public static final int MAX_SAME_PAGE = 3;
    public static final int MAX_FAILS = 3;
    /** 用户接手最多等多久。 */
    public static final long TAKEOVER_WAIT_MS = 10 * 60 * 1000L;
    /** 页面加载 / 点击后等页面稳定的上限。 */
    public static final long LOAD_TIMEOUT_MS = 20000L;
    public static final long SETTLE_MS = 1200L;

    /** 校验结果。 */
    public static final class Check {
        public final boolean ok;
        public final String note;

        Check(boolean ok, String note) {
            this.ok = ok;
            this.note = note;
        }

        @Override
        public String toString() {
            return (ok ? "✓" : "✗") + (note.length() > 0 ? " " + note : "");
        }
    }

    static Check ok(String note) {
        return new Check(true, note);
    }

    static Check bad(String note) {
        return new Check(false, note);
    }

    public static boolean changed(PageSnapshot a, PageSnapshot b) {
        if (a == null || b == null) return b != null;
        return !a.url.equals(b.url) || !a.sig.equals(b.sig);
    }

    /**
     * @param before   动作前快照
     * @param act      动作
     * @param jsOk     JS 层是否报告成功（点击 / 输入 / 滚动 / 翻页的返回值）
     * @param jsValue  输入后读回的值（只对 TYPE 有意义）
     * @param after    动作后重新抽的快照
     */
    public static Check verify(PageSnapshot before, AgentAction act, boolean jsOk, String jsValue, PageSnapshot after) {
        if (after == null) return bad("动作后读不到页面");
        switch (act.type) {
            case CLICK: {
                if (!jsOk) return bad("点击没有执行（元素可能已消失）");
                if (changed(before, after)) return ok(before != null && !before.url.equals(after.url) ? "已跳转" : "页面已变化");
                PageSnapshot.El e = before == null ? null : before.find(act.id);
                if (e != null && (e.isInput() || e.kind.startsWith("role:"))) return ok("已点击（页面内容未变）");
                return bad("点了「" + (e != null ? PageSnapshot.clip(e.text, 16) : String.valueOf(act.id)) + "」但页面没有变化");
            }
            case TYPE: {
                if (!jsOk) return bad("输入没有执行");
                if (act.submit) return changed(before, after) ? ok("已提交") : bad("提交后页面没有变化");
                String want = act.text.trim();
                String got = jsValue == null ? "" : jsValue.trim();
                if (got.equals(want) || got.contains(want)) return ok("已输入");
                return bad("输入框里读回的是「" + PageSnapshot.clip(got, 20) + "」");
            }
            case SCROLL:
                if (before != null && before.scrollY == after.scrollY && !changed(before, after)) return bad("已经滚到头了");
                return ok("已滚动");
            case NEXT_PAGE:
                if (!jsOk) return bad("没找到「下一页」");
                return changed(before, after) ? ok("已翻页") : bad("翻页后内容没有变化");
            case BACK:
            case OPEN:
                return changed(before, after) ? ok("已打开 " + after.url) : bad("页面没有变化");
            default:
                return ok("");
        }
    }

    /** 收工理由（null = 继续）。 */
    public static String limitReason(int steps, long elapsedMs, int samePage, int fails) {
        if (steps >= MAX_STEPS) return "已走满 " + MAX_STEPS + " 步";
        if (elapsedMs >= MAX_MS) return "已用满 " + (MAX_MS / 1000) + " 秒";
        if (samePage >= MAX_SAME_PAGE) return "在同一页面停了 " + samePage + " 次";
        if (fails >= MAX_FAILS) return "连续 " + fails + " 步没有生效";
        return null;
    }
}
