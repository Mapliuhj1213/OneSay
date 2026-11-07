package com.onesay.assistant.v2.errand.web;

import com.onesay.assistant.v2.errand.BrowseResult;
import com.onesay.assistant.v2.errand.ErrandIntent;
import com.onesay.assistant.v2.errand.ResearchLadder;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ lane C3-2：内置 WebView 浏览代理的主循环（纯 Java：页面驱动 / 规划 / 接手 / 时钟都经接口注入，JVM 可测）。
 *
 * <pre>
 *   load(start) → 循环 {
 *     要人接手？（登录 / 验证码）→ 岛上提示，等用户在接手页处理完 → 重新抽页面继续（接手时间不计入时长）
 *     守卫：购物到了结算页 → CHECKOUT 停下（付款永远交给用户，见 {@link Guard}）
 *     上限？→ LIMIT（带已完成的部分）
 *     规划下一步（模型只能按编号操作）→ 校验参数 → 守卫拦截（付款 / 提交订单等）→ 执行 → 重新抽页面 → 读回校验
 *   }
 * </pre>
 */
public final class BrowserAgent {

    /** 页面驱动（Android 侧是 headless WebView，见 WebViewDriver）。所有方法阻塞，在后台线程调用。 */
    public interface Driver {
        /** 打开网址并等加载完；打不开返回 null。 */
        PageSnapshot load(String url);

        /** 重新抽当前页面（等页面稳定后）。 */
        PageSnapshot snapshot();

        /** 执行 click / type / scroll / next_page；返回 {ok, value}。 */
        Result act(AgentAction a);

        /** 后退；返回是否有上一页。 */
        boolean back();

        /** 最近一次主文档加载失败的原因（站点拒绝 / HTTP 4xx-5xx / 网络错误），没有返回 null。 */
        String blockedReason();
    }

    public static final class Result {
        public final boolean ok;
        public final String value;
        public final String err;

        public Result(boolean ok, String value, String err) {
            this.ok = ok;
            this.value = value;
            this.err = err == null ? "" : err;
        }
    }

    /** 规划器（LLM）。 */
    public interface Planner {
        AgentAction next(ErrandIntent intent, PageSnapshot page, List<String> history, int step, int maxSteps);

        /** 到上限 / 用户结束时，根据已经看到的内容写一个阶段性结论（可返回 null）。 */
        String wrapUp(ErrandIntent intent, PageSnapshot page, List<String> history);
    }

    /** 请用户接手（登录 / 验证码）：阻塞直到用户在接手页点「继续」（true）或放弃 / 超时（false）。 */
    public interface Takeover {
        boolean await(String reason, String url);
    }

    /**
     * ★ fast-screen-v2：预算（模型调用数 / 总时长 / 无进展，见 {@link com.onesay.assistant.v2.phone.TaskBudget}）到了时问用户。
     * true = 再给一份预算继续；false = 按已看到的内容收尾（LIMIT）。不设 = 不问，直接 LIMIT（原行为）。
     */
    public interface BudgetAsk {
        boolean askContinue(String reason, String info);
    }

    private BudgetAsk mAsk;
    private boolean mUnattended;

    /** ★ fast-screen-v3：无人值守 = 8 次调用 / 3 分钟，到上限不问、收尾并在原因里注明「未完成」。 */
    public BrowserAgent setUnattended(boolean u) {
        mUnattended = u;
        return this;
    }

    public BrowserAgent setBudgetAsk(BudgetAsk a) {
        mAsk = a;
        return this;
    }

    /** 时钟（测试注入）。 */
    public interface Clock {
        long now();
    }

    /**
     * 安全守卫（C3-3 购物在这里落地）：
     * {@link #block} 返回非 null = 这一步不准执行（原因回灌给模型）；{@link #stopAt} 返回非 null = 到此为止交给用户（结算页）。
     */
    public interface Guard {
        String block(ErrandIntent intent, PageSnapshot page, AgentAction a);

        String stopAt(ErrandIntent intent, PageSnapshot page);
    }

    /**
     * Guard 可选实现。每个动作执行并读回后调用；返回非 null = 立即停下并告警
     * （{@link BrowseResult.Status#ORDER_ALERT}，例如点击后页面出现「订单已提交 / 支付成功」）。
     */
    public interface PostActionGuard {
        String afterAction(ErrandIntent intent, PageSnapshot before, AgentAction a, PageSnapshot after);
    }

    /** 不加额外限制（密码框在 {@link AgentAction#validate} 里已经拦了）。 */
    public static final Guard NO_GUARD = new Guard() {
        @Override public String block(ErrandIntent intent, PageSnapshot page, AgentAction a) {
            return null;
        }

        @Override public String stopAt(ErrandIntent intent, PageSnapshot page) {
            return null;
        }
    };

    private final Driver mDriver;
    private final Planner mPlanner;
    private final Takeover mTakeover;
    private final Guard mGuard;
    private final Clock mClock;

    public BrowserAgent(Driver d, Planner p, Takeover t, Guard g, Clock c) {
        mDriver = d;
        mPlanner = p;
        mTakeover = t;
        mGuard = g != null ? g : NO_GUARD;
        mClock = c != null ? c : new Clock() {
            @Override public long now() {
                return System.currentTimeMillis();
            }
        };
    }

    /** 已访问过的页面（给来源编号用）。 */
    private final List<String[]> mSources = new ArrayList<String[]>();
    /** 每步的日志（测试 / 诊断）。 */
    public final List<String> trace = new ArrayList<String>();

    public BrowseResult run(ErrandIntent intent, String startUrl, ResearchLadder.Progress progress) {
        ResearchLadder.Progress pr = progress != null ? progress : new ResearchLadder.Progress() {
            @Override public void step(String text) {
            }

            @Override public boolean cancelled() {
                return false;
            }
        };
        long t0 = mClock.now();
        long paused = 0;
        List<String> history = new ArrayList<String>();
        PageSnapshot page = mDriver.load(startUrl);
        if (page == null) {
            String why = mDriver.blockedReason();
            return BrowseResult.blocked(startUrl, why != null ? why : "页面打不开");
        }
        String blocked = mDriver.blockedReason();
        if (blocked != null && page.els.isEmpty() && page.text.length() < 200) return BrowseResult.blocked(startUrl, blocked);
        source(page);

        int steps = 0, fails = 0;
        com.onesay.assistant.v2.phone.TaskBudget budget = com.onesay.assistant.v2.phone.TaskBudget.forTask(mUnattended, t0);
        while (true) {
            if (pr.cancelled()) return result(BrowseResult.Status.FAILED, null, page, "已停止", steps);

            // ① 要人接手？
            String need = page.takeoverReason();
            if (need != null) {
                trace.add("takeover: " + need + " @" + page.url);
                pr.step(need + "（请在灵动岛上点「允许」接手）");
                long w0 = mClock.now();
                boolean back = mTakeover != null && mTakeover.await(need, page.url);
                paused += mClock.now() - w0;
                budget.pausedMs += mClock.now() - w0;
                if (!back) return result(BrowseResult.Status.TAKEOVER, null, page, need, steps);
                budget.resetProgress();
                PageSnapshot again = mDriver.snapshot();
                if (again == null) again = mDriver.load(page.url);
                if (again == null) return result(BrowseResult.Status.TAKEOVER, null, page, need, steps);
                if (again.takeoverReason() != null && again.url.equals(page.url) && again.sig.equals(page.sig)) {
                    return result(BrowseResult.Status.TAKEOVER, null, again, need + "（接手后页面没有变化）", steps);
                }
                history.add("用户已手动处理：" + need);
                page = again;
                source(page);
                continue;
            }

            // ② 结算页：停下交给用户
            String stop = mGuard.stopAt(intent, page);
            if (stop != null) {
                trace.add("stop: " + stop);
                String sum = mPlanner.wrapUp(intent, page, history);
                return result(BrowseResult.Status.CHECKOUT, sum, page, stop, steps);
            }

            // ③ 预算（TaskBudget：模型调用数 / 总时长 / 无进展；连续校验失败也算卡住）→ 先问用户
            com.onesay.assistant.v2.phone.TaskBudget.Verdict v = budget.check(mClock.now());
            if (v == com.onesay.assistant.v2.phone.TaskBudget.Verdict.OK && fails >= BrowsePolicy.MAX_FAILS) {
                v = com.onesay.assistant.v2.phone.TaskBudget.Verdict.STUCK;
                budget.reason = "连续 " + fails + " 步没有生效";
            }
            if (v != com.onesay.assistant.v2.phone.TaskBudget.Verdict.OK) {
                String lim = budget.unattended ? budget.unfinishedReason() : budget.reason;
                trace.add("budget " + v + ": " + lim + "（" + budget.info(mClock.now()) + "）");
                boolean go = false;
                if (mAsk != null && budget.canAskUser(v)) {
                    long w0 = mClock.now();
                    go = mAsk.askContinue(v == com.onesay.assistant.v2.phone.TaskBudget.Verdict.STUCK ? "好像卡住了：" + lim : lim + "，还要继续吗？",
                            budget.info(mClock.now()));
                    paused += mClock.now() - w0;
                    budget.pausedMs += mClock.now() - w0;
                    if (pr.cancelled()) return result(BrowseResult.Status.FAILED, null, page, "已停止", steps);
                }
                if (go) {
                    if (v == com.onesay.assistant.v2.phone.TaskBudget.Verdict.LIMIT) budget.extend();
                    budget.resetProgress();
                    fails = 0;
                    history.add("（" + lim + "，用户让继续" + (v == com.onesay.assistant.v2.phone.TaskBudget.Verdict.STUCK ? "：换一个没试过的做法" : "") + "）");
                    trace.add("budget: 用户选择继续");
                    continue;
                }
                String sum = mPlanner.wrapUp(intent, page, history);
                return result(BrowseResult.Status.LIMIT, sum, page, lim, steps);
            }

            // ④ 规划
            budget.onModelCall();
            budget.onTurn(pageSig(page));
            AgentAction a = mPlanner.next(intent, page, history, steps + 1, budget.maxCalls);
            steps++;
            if (a == null) a = AgentAction.invalid("模型没有返回");
            String bad = a.validate(page);
            if (a.type == AgentAction.Type.DONE && bad == null) {
                trace.add("done");
                return result(BrowseResult.Status.DONE, a.answer, page, null, steps);
            }
            if (a.type == AgentAction.Type.TAKEOVER) {
                // 模型自己判断要人来（例如页面要求扫码）：按接手处理，下一轮 ① 不一定能识别，直接在这里等
                String why = a.answer.length() > 0 ? a.answer : "需要你处理";
                long w0 = mClock.now();
                boolean back = mTakeover != null && mTakeover.await(why, page.url);
                paused += mClock.now() - w0;
                budget.pausedMs += mClock.now() - w0;
                if (!back) return result(BrowseResult.Status.TAKEOVER, null, page, why, steps);
                budget.resetProgress();
                PageSnapshot again = mDriver.snapshot();
                if (again != null) page = again;
                history.add("用户已手动处理：" + why);
                continue;
            }
            if (a.type == AgentAction.Type.CHECKOUT) {
                // ★ C3-3：只有守卫也认定是结算 / 收银页才算；模型说了不算（回灌「这里还不是结算页」）
                String st = mGuard.stopAt(intent, page);
                if (st != null) return result(BrowseResult.Status.CHECKOUT, a.answer, page, st, steps);
                fails++;
                history.add("第" + steps + "步 到结算页停下 → 不对：这里还不是结算 / 确认订单页");
                continue;
            }
            if (bad == null) bad = mGuard.block(intent, page, a);
            if (bad != null) {
                fails++;
                history.add("第" + steps + "步 " + a.describe(page) + " → 不允许：" + bad);
                trace.add("blocked: " + a + " " + bad);
                continue;
            }

            // ⑤ 执行 + 读回
            pr.step("第 " + steps + " 步：" + a.describe(page));
            PageSnapshot before = page;
            Result r;
            PageSnapshot after;
            switch (a.type) {
                case BACK:
                    r = new Result(mDriver.back(), null, null);
                    after = mDriver.snapshot();
                    break;
                case OPEN:
                    after = mDriver.load(a.url);
                    r = new Result(after != null, null, after == null ? "打不开" : null);
                    break;
                default:
                    r = mDriver.act(a);
                    after = mDriver.snapshot();
                    break;
            }
            BrowsePolicy.Check c = BrowsePolicy.verify(before, a, r != null && r.ok, r != null ? r.value : null, after);
            trace.add(a + " → " + c);
            history.add("第" + steps + "步 " + a.describe(before) + " → " + (c.ok ? c.note : "没有生效：" + c.note + (r != null && r.err.length() > 0 ? "（" + r.err + "）" : "")));
            if (c.ok) fails = 0;
            else fails++;
            boolean progressed = !pageSig(before).equals(pageSig(after)) || (a.type == AgentAction.Type.TYPE && !a.submit && c.ok);
            budget.onAction(a.toString(), progressed);
            if (after != null) {
                page = after;
                if (!after.url.equals(before.url)) source(after);
            }
            if (mGuard instanceof PostActionGuard) {
                String alarm = ((PostActionGuard) mGuard).afterAction(intent, before, a, after);
                if (alarm != null) {
                    trace.add("ALERT: " + alarm + " @" + (after != null ? after.url : before.url));
                    return result(BrowseResult.Status.ORDER_ALERT, null, page, alarm, steps);
                }
            }
        }
    }

    /** 页面签名（无进展检测）：URL + 内容签名 + 滚动位置（往下读长帖也算进展）。 */
    static String pageSig(PageSnapshot p) {
        return p == null ? "" : p.url + "|" + p.sig + "|" + p.scrollY;
    }

    private void source(PageSnapshot p) {
        for (String[] s : mSources) if (s[1].equals(p.url)) return;
        mSources.add(new String[]{p.title.length() > 0 ? p.title : p.url, p.url});
    }

    private BrowseResult result(BrowseResult.Status st, String answer, PageSnapshot page, String reason, int steps) {
        return new BrowseResult(st, answer, page != null ? page.url : null, reason, mSources, steps);
    }
}
