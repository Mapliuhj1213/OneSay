package com.onesay.assistant.v2.errand;


import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.IslandHost;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.router.UnifiedRouter;
import com.onesay.assistant.v2.search.SearchResult;
import com.onesay.assistant.v2.search.TavilyExtractProvider;
import com.onesay.assistant.v2.search.WebSearchClient;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ lane C3：浏览器跑腿的宿主侧挂钩（{@link com.onesay.assistant.v2.kernel.plugins.ErrandPlugin} 委托到这里）。
 *
 * <h3>流程（与 C1 窗口逻辑一致）</h3>
 * <pre>
 *   「帮我调研一下 X」→ route 接管（errand.run，不进 AgentLoop）→ dispatch：
 *     卡片收进灵动岛（s1DetachForCapsule）→ 岛上后台活动「跑腿：X」逐步显示进度（可「停止」）
 *     → 升级链 {@link ResearchLadder}：Tavily search → extract → WebView → 默认浏览器
 *     → 收工：结论写进卡片（不挂回），岛上展开结果卡，「查看」= 流回卡片看全文（task-result- 前缀）。
 * </pre>
 * 岛没挂载（island.off）→ 卡片不收，进度和结论都画在卡片上。
 */
public final class ErrandHooks {

    private ErrandHooks() {}

    static final String TAG = "Errand";
    public static final String OWNER = "errand";
    public static final String SIGN = "errand.run";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int sSeq;
    /** 在途任务：岛活动 id → 取消标志。 */
    private static final Map<String, boolean[]> sRunning = Collections.synchronizedMap(new HashMap<String, boolean[]>());
    private static volatile String sLast = "";

    // ------------------------------------------------------------------ 路由

    public static boolean enabled(Context ctx) {
        return ErrandSettings.enabled(ctx);
    }

    /** 纯规则 + 开关：这句话是不是跑腿（购物分开关）。 */
    public static ErrandIntent intentFor(Context ctx, String query) {
        ErrandIntent in = ErrandIntent.parse(query);
        if (!in.isErrand()) return in;
        if (in.kind == ErrandIntent.Kind.SHOP && !ErrandSettings.SHOP.on(ctx)) return ErrandIntent.none(query);
        // ★ int/1102 问一次记住：购物选了「直接操作 App」→ 不走浏览器跑腿（交给 GUI 多步，下单前确认）；点名「用浏览器」的照旧跑腿
        if (in.kind == ErrandIntent.Kind.SHOP && !ErrandIntent.explicitErrand(query)
                && com.onesay.assistant.v2.askonce.AskOnce.SHOP_GUI.equals(com.onesay.assistant.v2.askonce.AskOnceHooks.shopMode(ctx))) {
            return ErrandIntent.none(query);
        }
        // ★ arch/agent-loop（B 路，用户 11-01 决策）：「帮我调研 / 查资料 / 网上查」改进 AgentLoop（web.search / web.read / research.spawn），
        //   跑腿只保留点名跑腿（「跑个腿」「用浏览器…」）、读链接、购物。开关 bixby_agent_loop.research_in_loop（默认开）关掉即回旧行为。
        if (in.kind == ErrandIntent.Kind.RESEARCH && !ErrandIntent.explicitErrand(query)
                && com.onesay.assistant.v2.agent.LoopConfig.researchInLoop(ctx)) {
            return ErrandIntent.none(query);
        }
        return in;
    }

    public static boolean claims(Context ctx, String query) {
        try {
            return query != null && enabled(ctx) && intentFor(ctx, query).isErrand();
        } catch (Throwable t) {
            return false;
        }
    }

    public static Invocation route(Context ctx, String query) {
        try {
            if (!claims(ctx, query)) return null;
            JSONObject a = new JSONObject();
            a.put("query", query.trim());
            return new Invocation(SIGN, a, 1.0, Invocation.RouteSource.L0_RULE, query);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 派发（主线程）

    public static void onDispatch(final FloatingSheetService svc, Invocation inv, final String command) {
        final ErrandIntent intent = intentFor(svc, command);
        if (!intent.isErrand()) return;
        final String id;
        synchronized (ErrandHooks.class) {
            id = "errand-" + (++sSeq);
        }
        final boolean[] cancel = {false};
        sRunning.put(id, cancel);
        final IslandHost.Channel ch = channel();
        final boolean onIsland = ch != null && ch.ready();
        final String title = titleFor(intent);
        final int seq = svc.requestSeq();   // ★ LC-5：派发时的前台请求号（收工时卡片若已被新请求接管，结论只放岛上）
        Log.i(TAG, "[ERRAND] 开始 " + id + " " + com.onesay.assistant.v2.util.LogText.u(intent) + (onIsland ? "（岛上）" : "（卡片上）"));

        FloatingSheetView sheet = svc.getSheetView();
        if (sheet != null) {
            sheet.updateState("正在跑腿", android.graphics.Color.parseColor("#5E5CE6"), title + " · 搜索与比价网取价");   // ★ 11-04 ②：不再说「开浏览器」
        }
        com.onesay.assistant.v2.router.ResearchVisuals.resetImages();   // ★ 11-04 ④
        if (sheet != null) {
            sheet.setDismissLabel("✕ 关闭");
        }
        if (onIsland) {
            ch.setScopedListener(islandListener(svc));
            ch.bgStarted(id, title);
            svc.s1DetachForCapsule();   // C1：一转任务立刻收进岛
        }

        com.onesay.assistant.v2.util.Threads.start("errand-" + id, new Runnable() {
            @Override public void run() {
                ResearchLadder.Outcome out;
                try {
                    out = ladder(svc, id, ch, onIsland, cancel).run(intent, new ResearchLadder.Progress() {
                        @Override public void step(String text) {
                            Log.i(TAG, "[ERRAND] " + id + " · " + com.onesay.assistant.v2.util.LogText.u(text));
                            if (onIsland) ch.bgStep(id, com.onesay.assistant.v2.island.IslandStepText.fit(text, 16));   // ★ 11-04 ①：短句、整词截断
                            else sheetState(svc, "正在跑腿", text);
                        }

                        @Override public boolean cancelled() {
                            return cancel[0];
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "[ERRAND] 异常: " + t);
                    out = null;
                }
                // ★ C3-3：到了结算页 → 岛上问一句「去付款？」，允许 = 打开结算页（同一套 Cookie），由用户自己核对并付款
                if (out != null && out.browseStatus == BrowseResult.Status.CHECKOUT && out.handoffUrl != null && !cancel[0]) {
                    String head = ErrandReport.checkoutPrompt(out);
                    boolean opened = awaitUser(svc, id, ch, onIsland, cancel, head, out.handoffUrl, ErrandBrowserActivity.MODE_HANDOFF);
                    Log.i(TAG, "[ERRAND] 结算页交给用户 " + id + " → " + (opened ? "已打开" : "稍后 / 没回应"));
                }
                // 点击后页面出现「订单已提交 / 支付成功」→ 已立即停下；告警 + 岛上让用户马上打开订单页核对
                if (out != null && out.browseStatus == BrowseResult.Status.ORDER_ALERT) {
                    Log.w(TAG, "[ERRAND] [SEC] 疑似已下单 / 已付款，已停下告警 " + id + " @" + com.onesay.assistant.v2.util.LogText.u(String.valueOf(out.handoffUrl)));
                    if (out.handoffUrl != null && !cancel[0]) {
                        awaitUser(svc, id, ch, onIsland, cancel, "⚠ 订单可能已提交，马上核对？", out.handoffUrl, ErrandBrowserActivity.MODE_HANDOFF);
                    }
                }
                sRunning.remove(id);
                deliver(svc, ch, onIsland, id, intent, command, out, cancel[0], seq);
            }
        });
    }

    /** 收工：结论写进卡片，岛上展开结果卡（「查看」流回卡片）。 */
    static void deliver(final FloatingSheetService svc, final IslandHost.Channel ch, final boolean onIsland, final String id,
                        final ErrandIntent intent, final String command, final ResearchLadder.Outcome out, final boolean cancelled,
                        final int seq) {
        final ErrandReport.Card card = ErrandReport.card(intent, out, cancelled);
        if (!cancelled && intent != null && intent.kind != ErrandIntent.Kind.READ) {   // ★ 11-04 ④：结论配图（代表款缩略图 + 价格条形图）
            com.onesay.assistant.v2.router.ResearchVisuals.Model vm = com.onesay.assistant.v2.router.ResearchVisuals.attach(card.full, System.currentTimeMillis());
            if (vm != null) Log.i(TAG, "[ERRAND] [VISUALS] 条形图 " + vm.bars.size() + " 条、缩略图 " + vm.images.size() + " 张");
        }
        rememberSources(out, cancelled);
        // ★ fix/shop-research：结论结尾在提议 / 反问 → 记下，「好 / 你决定」接回同一任务
        if (!cancelled) com.onesay.assistant.v2.router.TaskContinuation.record(command, out != null ? out.answer : null,
                com.onesay.assistant.v2.router.TaskContinuation.Origin.ERRAND, System.currentTimeMillis());
        sLast = id + " " + (out != null ? out.toString() : "null") + (cancelled ? " cancelled" : "");
        Log.i(TAG, "[ERRAND] 收工 " + sLast);
        Log.i(TAG, "[ERRAND] 结论 " + com.onesay.assistant.v2.util.LogText.u(card.full, 1600));
        if (!cancelled) com.onesay.assistant.v2.util.ConclusionLog.log("errand/" + (intent != null ? intent.kind : "?"), card.full);   // ★ 11-03：结论原文（前 1500 字）便于机上核价
        MAIN.post(new Runnable() {
            @Override public void run() {
                FloatingSheetView sheet = svc.getSheetView();
                // ★ LC-5：派发后来过新请求（卡片正在显示新回答）→ 结论不覆盖卡片，只放岛上，「查看」时再画回卡片
                boolean ownsCard = com.onesay.assistant.v2.ui.wake.TaskResultRouting.writeToCard(seq, svc.requestSeq());
                if (!cancelled) svc.s1RememberTaskResult(IslandModel.TASK_RESULT_PREFIX + id, command, card.full, seq);
                if (!ownsCard) Log.i(TAG, "[ERRAND] [LC-5] 卡片已被新请求接管（请求号 " + seq + " → " + svc.requestSeq() + "），结论只放岛上");
                if (sheet != null && !cancelled && ownsCard) sheet.showAskReply(command, card.full);
                if (!onIsland) {
                    if (!ownsCard) return;   // 岛不可用且卡片已归新请求：不挂回、不覆盖
                    if (!cancelled) svc.s1AttachSheet();
                    return;
                }
                ch.removeBg(id);
                if (cancelled) return;
                List<String> acts = new ArrayList<String>();
                acts.add("查看");
                ch.noteResult(IslandModel.TASK_RESULT_PREFIX + id, card.ok, card.headline, card.detail, card.meta, acts);
            }
        });
    }

    private static IslandHost.Listener islandListener(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        return new IslandHost.Listener() {
            @Override public void onBgConfirm(String id, boolean allow) {
                Pending t = sPending.get(id);
                if (t == null) return;
                IslandHost.Channel ch = channel();
                if (MODE_BUDGET.equals(t.mode)) {   // ★ fast-screen-v2：预算到了的「还要继续吗」
                    Log.i(TAG, "[ERRAND] 岛上预算确认 " + id + "（" + t.reason + "）：" + (allow ? "继续" : "收尾"));
                    if (ch != null) ch.bgConfirmResolved(id);
                    t.finish(allow);
                    return;
                }
                Log.i(TAG, "[ERRAND] 岛上确认卡 " + id + "（" + t.reason + "）：" + (allow ? "打开页面" : "放弃"));
                if (ch != null) ch.bgConfirmResolved(id);
                if (allow) {
                    if (ch != null) ch.bgStep(id, t.mode.equals(ErrandBrowserActivity.MODE_TAKEOVER) ? "等你处理完点「继续跑腿」…" : "已打开页面");
                    open(app, t);
                    if (!t.mode.equals(ErrandBrowserActivity.MODE_TAKEOVER)) t.finish(true);
                } else {
                    t.finish(false);
                }
            }

            @Override public void onBgStop(String id) {
                boolean[] c = sRunning.get(id);
                if (c != null) c[0] = true;
                Pending t = sPending.get(id);
                if (t != null) t.finish(false);
                Log.i(TAG, "[ERRAND] 用户在岛上停止 " + id);
                IslandHost.Channel ch = channel();
                if (ch != null) ch.bgEnded(id, false, "已停止");
            }

            @Override public void onRemindDone(String id) {
            }

            @Override public void onRemindSnooze(String id) {
            }

            @Override public void onTodoCheck(String id) {
            }
        };
    }

    // ------------------------------------------------------------------ 请用户接手（登录 / 验证码）/ 交给用户（结算页）

    /** 一次等用户的请求。 */
    static final class Pending {
        final String id;
        final String url;
        final String reason;
        final String mode;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        volatile boolean ok;

        Pending(String id, String url, String reason, String mode) {
            this.id = id;
            this.url = url;
            this.reason = reason;
            this.mode = mode;
        }

        void finish(boolean ok) {
            this.ok = ok;
            latch.countDown();
        }
    }

    private static final Map<String, Pending> sPending = Collections.synchronizedMap(new HashMap<String, Pending>());

    static void open(Context app, Pending t) {
        try {
            app.startActivity(ErrandBrowserActivity.intent(app, t.url, t.id, t.mode, t.reason));
        } catch (Throwable e) {
            Log.w(TAG, "[ERRAND] 打不开接手页: " + e.getMessage());
            t.finish(false);
        }
    }

    /**
     * 阻塞等用户（后台线程）：岛上出确认卡「允许 = 打开页面」；岛不可用时直接打开接手页。
     * 接手模式等用户在页面里点「继续跑腿」/「结束」；交给用户模式（结算页）点了「允许」就算交接完成。
     */
    static boolean awaitUser(Context ctx, String id, IslandHost.Channel ch, boolean onIsland, boolean[] cancel,
                             String reason, String url, String mode) {
        Pending t = new Pending(id, url, reason, mode);
        sPending.put(id, t);
        try {
            String detail = ErrandBrowserActivity.MODE_TAKEOVER.equals(mode)
                    ? "点「允许」打开页面，你自己处理完点「继续跑腿」"
                    : "点「允许」打开页面，由你自己核对并付款";
            if (onIsland && ch != null) ch.bgConfirm(id, reason, detail);
            else open(ctx.getApplicationContext(), t);
            long until = android.os.SystemClock.elapsedRealtime() + com.onesay.assistant.v2.errand.web.BrowsePolicy.TAKEOVER_WAIT_MS;
            while (android.os.SystemClock.elapsedRealtime() < until) {
                if (cancel != null && cancel[0]) return false;
                try {
                    if (t.latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
                } catch (InterruptedException e) {
                    return false;
                }
            }
            Log.i(TAG, "[ERRAND] 等用户结束 " + id + " " + mode + " → " + (t.ok ? "继续" : "放弃 / 超时"));
            return t.ok;
        } finally {
            sPending.remove(id);
            if (onIsland && ch != null) ch.bgConfirmResolved(id);
        }
    }

    /** ★ fast-screen-v2：预算确认（不打开页面，岛上「允许」= 继续）。 */
    static final String MODE_BUDGET = "budget";
    /** 预算确认最多等多久（没人理就按已看到的内容收尾）。 */
    static final long BUDGET_ASK_WAIT_MS = 2 * 60 * 1000L;

    /** 预算到了：岛上问「还要继续吗」，阻塞等用户（后台线程）。岛不可用 → false（不打断前台，按原样收尾）。 */
    static boolean askBudget(String id, IslandHost.Channel ch, boolean onIsland, boolean[] cancel, String reason, String info) {
        if (!onIsland || ch == null) return false;
        Pending t = new Pending(id, null, reason, MODE_BUDGET);
        sPending.put(id, t);
        try {
            ch.bgConfirm(id, reason, info + "。点「允许」再给一份预算接着做，点「拒绝」就按已经看到的内容总结");
            long until = android.os.SystemClock.elapsedRealtime() + BUDGET_ASK_WAIT_MS;
            while (android.os.SystemClock.elapsedRealtime() < until) {
                if (cancel != null && cancel[0]) return false;
                try {
                    if (t.latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
                } catch (InterruptedException e) {
                    return false;
                }
            }
            Log.i(TAG, "[ERRAND] 预算确认 " + id + " → " + (t.ok ? "继续" : "收尾 / 超时"));
            return t.ok;
        } finally {
            sPending.remove(id);
            ch.bgConfirmResolved(id);
        }
    }

    /** 接手页「继续跑腿」/「结束」/ 返回。 */
    public static void takeoverDone(String id, boolean resume, String url) {
        Pending t = id == null ? null : sPending.get(id);
        if (t != null) t.finish(resume);
    }

    static IslandHost.Channel channel() {
        try {
            IslandHost.Channel ch = FeatureKernel.get().island(OWNER);
            return ch.ready() ? ch : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sheetState(final FloatingSheetService svc, final String badge, final String text) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                FloatingSheetView s = svc.getSheetView();
                if (s != null) s.updateState(badge, android.graphics.Color.parseColor("#5E5CE6"), text);
            }
        });
    }

    static String titleFor(ErrandIntent in) {
        String q = ResearchLadder.clip(in.query.length() > 0 ? in.query : (in.url != null ? ResearchLadder.host(in.url) : in.raw), 14);
        switch (in.kind) {
            case SHOP: return "购物：" + q;
            case READ: return "读网页：" + q;
            default: return "跑腿：" + q;
        }
    }

    // ------------------------------------------------------------------ 升级链装配

    static ResearchLadder ladder(final Context ctx, String id, IslandHost.Channel ch, boolean onIsland, boolean[] cancel) {
        final WebSearchClient ws = WebSearchClient.getInstance(ctx);
        final TavilyExtractProvider tx = new TavilyExtractProvider(ws.config());
        ResearchLadder.Searcher s = new ResearchLadder.Searcher() {
            @Override public List<SearchResult> search(String q) {
                return ws.search(q);
            }
        };
        ResearchLadder.Extractor e = new ResearchLadder.Extractor() {
            @Override public boolean ready() {
                return true;   // ★ fix/research-depth：没配 Tavily 也能读原网页（SimplePageReader 直接 GET）
            }

            @Override public List<TavilyExtractProvider.Page> extract(List<String> urls, String q) {
                if (tx.isReady()) return tx.extract(urls, q);
                Log.i(TAG, "[ERRAND] 没配 Tavily，直接读原网页 " + urls.size() + " 个");
                return SimplePageReader.read(urls);
            }
        };
        return new ResearchLadder(s, e, browser(ctx, id, ch, onIsland, cancel), external(ctx), summarizer(ctx)).withRendered(rendered(ctx))
                .withCompare(compareHttp());
    }

    public static com.onesay.assistant.v2.errand.compare.CompareSites.Http compareHttpPublic() {
        return compareHttp();
    }

    /** ★ fix/shop-research（用户 11-03 15:53「用比价网取价」）：比价网静态读取（https GET、桌面 UA、不带 Cookie；只记网址与字节数）。 */
    static com.onesay.assistant.v2.errand.compare.CompareSites.Http compareHttp() {
        return new com.onesay.assistant.v2.errand.compare.CompareSites.Http() {
            @Override public String get(String url, int maxBytes) {
                long t0 = System.currentTimeMillis();
                String b = SimplePageReader.fetchRaw(url, maxBytes);
                if (com.onesay.assistant.v2.errand.compare.CompareSites.oversize(b)) {
                    Log.w(TAG, "[ERRAND] 比价网读取 " + ResearchLadder.host(url) + " 超过上限 " + maxBytes + " B（已读 "
                            + b.substring(com.onesay.assistant.v2.errand.compare.CompareSites.OVERSIZE.length()) + " B 即停），不解析，走下一个比价源 "
                            + (System.currentTimeMillis() - t0) + "ms");
                    return b;
                }
                Log.i(TAG, "[ERRAND] 比价网读取 " + ResearchLadder.host(url) + " len=" + (b == null ? -1 : b.length()) + " " + (System.currentTimeMillis() - t0) + "ms");
                return b;
            }
        };
    }

    /** ★ fix/shop-research（用户 11-03 14:37）：离屏 WebView 只读取价（电脑模式；进程内单实例、串行、12 s 超时；一轮结束清 Cookie / 缓存）。 */
    static ResearchLadder.Rendered rendered(final Context ctx) {
        final com.onesay.assistant.v2.errand.web.BrowserReader br = com.onesay.assistant.v2.errand.web.BrowserReader.get(ctx);
        return new ResearchLadder.Rendered() {
            @Override public boolean available() {
                return br.available();
            }

            @Override public com.onesay.assistant.v2.errand.web.ReaderResult read(String url) {
                com.onesay.assistant.v2.errand.web.ReaderResult r = br.read(url);
                Log.i(TAG, "[ERRAND] 浏览器取价 " + r);
                return r;
            }

            @Override public void clear() {
                br.clear();
            }
        };
    }

    /** 内置 WebView 浏览代理（PC UA；每次跑腿新建一个无头 WebView，跑完销毁，Cookie 保留）。 */
    static ResearchLadder.Browser browser(final Context ctx, final String id, final IslandHost.Channel ch, final boolean onIsland,
                                          final boolean[] cancel) {
        final com.onesay.assistant.v2.errand.web.LlmPlanner planner = new com.onesay.assistant.v2.errand.web.LlmPlanner(llm(ctx));
        return new ResearchLadder.Browser() {
            @Override public boolean available() {
                return ErrandSettings.WEBVIEW.on(ctx) && planner.ready();
            }

            @Override public BrowseResult browse(ErrandIntent intent, String startUrl, ResearchLadder.Progress p) {
                com.onesay.assistant.v2.errand.web.WebViewDriver d = new com.onesay.assistant.v2.errand.web.WebViewDriver(ctx);
                try {
                    com.onesay.assistant.v2.errand.web.BrowserAgent agent = new com.onesay.assistant.v2.errand.web.BrowserAgent(d, planner,
                            new com.onesay.assistant.v2.errand.web.BrowserAgent.Takeover() {
                                @Override public boolean await(String reason, String url) {
                                    return awaitUser(ctx, id, ch, onIsland, cancel, reason, url, ErrandBrowserActivity.MODE_TAKEOVER);
                                }
                            }, guard(), null);
                    // ★ fast-screen-v2：TaskBudget 到上限先在岛上问「还要继续吗」（岛不可用 = 不问，按原样收尾）
                    agent.setBudgetAsk(new com.onesay.assistant.v2.errand.web.BrowserAgent.BudgetAsk() {
                        @Override public boolean askContinue(String reason, String info) {
                            return askBudget(id, ch, onIsland, cancel, reason, info);
                        }
                    });
                    BrowseResult r = agent.run(intent, startUrl, p);
                    Log.i(TAG, "[ERRAND] 浏览器 " + id + " → " + r + " 拦下拉起 App " + d.blockedSchemeCount() + " 次");
                    for (String line : agent.trace) Log.i(TAG, "[ERRAND]   · " + com.onesay.assistant.v2.util.LogText.u(line));
                    return r;
                } finally {
                    d.destroy();
                }
            }
        };
    }

    /**
     * 安全守卫（C3-3）：所有跑腿都用 —— 结算 / 收银页停下，下单 / 付款类按钮和支付输入框一律不碰。
     * 故意不区分购物 / 非购物跑腿（调研类任务也可能误点到付款），所以不看任务意图（2026-10-23 审计 g3 D7：去掉了没用的参数）。
     */
    static com.onesay.assistant.v2.errand.web.BrowserAgent.Guard guard() {
        return com.onesay.assistant.v2.errand.web.CheckoutGuard.INSTANCE;
    }

    /** ★ r2/fast-screen：App 操作代理复用同一个 LlmClient。 */
    public static com.onesay.assistant.v2.net.LlmClient llmFor(Context ctx) {
        return llm(ctx);
    }

    static com.onesay.assistant.v2.net.LlmClient llm(Context ctx) {
        try {
            L2LLMCompiler l2 = UnifiedRouter.getInstance(ctx).getL2();
            return l2 == null ? null : l2.client();
        } catch (Throwable t) {
            return null;
        }
    }

    /** ★ C3-4 浏览器兜底：用默认浏览器（系统解析）只打开链接，不做 GUI 点击；可配桌面版参数（见 {@link DefaultBrowser}）。 */
    static ResearchLadder.External external(final Context ctx) {
        return new ResearchLadder.External() {
            @Override public boolean available() {
                return ErrandSettings.BROWSER.on(ctx) && DefaultBrowser.available(ctx);
            }

            private volatile boolean mDesktop;

            @Override public boolean open(String url) {
                DefaultBrowser.Spec sp = DefaultBrowser.launch(ctx, url);
                mDesktop = sp != null && sp.desktop;
                return sp != null;
            }

            @Override public boolean openedDesktop() {
                return mDesktop;
            }
        };
    }

    static ResearchLadder.Summarizer summarizer(final Context ctx) {
        return new ResearchLadder.Summarizer() {
            @Override public String answer(ErrandIntent intent, String material) {
                try {
                    L2LLMCompiler l2 = UnifiedRouter.getInstance(ctx).getL2();
                    if (l2 == null || !l2.hasApiKey()) return null;
                    com.onesay.assistant.v2.net.LlmClient c = l2.client().withReadTimeout(ErrandPrompt.READ_TIMEOUT_MS).withTag("errand");
                    String body = com.onesay.assistant.v2.net.LlmClient.body(ErrandPrompt.MODEL, ErrandPrompt.TEMPERATURE, ErrandPrompt.maxTokens(intent),
                            com.onesay.assistant.v2.net.LlmClient.messages(ErrandPrompt.answerSystem(intent), ErrandPrompt.answerUser(intent, material)),
                            null, false).toString();
                    com.onesay.assistant.v2.net.LlmResponse r = c.post(body);
                    if (!r.ok()) {
                        Log.w(TAG, "[ERRAND] 整理回答失败: " + r.error);
                        return null;
                    }
                    return r.content();
                } catch (Throwable t) {
                    Log.w(TAG, "[ERRAND] 整理回答异常: " + t.getMessage());
                    return null;
                }
            }
        };
    }

    // ------------------------------------------------------------------ ★ C3-5 「打开第三个」：结果卡里的来源编号

    /** 跑腿来源的编号列表有效期（用户听完结果再唤醒说「打开第二个」）。 */
    static final long SOURCES_TTL_MS = 10 * 60 * 1000L;
    private static final List<String[]> sLastSources = new ArrayList<String[]>();

    static void rememberSources(ResearchLadder.Outcome out, boolean cancelled) {
        synchronized (sLastSources) {
            sLastSources.clear();
            if (out == null || cancelled) return;
            int n = Math.min(ErrandReport.SOURCES_SHOWN, out.sources.size());
            for (int i = 0; i < n; i++) {
                ResearchLadder.Source s = out.sources.get(i);
                if (s.url.length() > 0) sLastSources.add(new String[]{s.title, s.url});
                else break;   // 编号必须和结果卡上的一致：中间缺链接就不再往后登记
            }
            if (!sLastSources.isEmpty()) {
                com.onesay.assistant.v2.vision.screenref.NumberedRefs.publish(com.onesay.assistant.v2.vision.screenref.NumberedRefs.OWNER_ERRAND,
                        sLastSources.size(), System.currentTimeMillis(), SOURCES_TTL_MS);
            }
        }
    }

    /**
     * ★ fix/shop-research（R5）：上一轮任务（跑腿 / AgentLoop）结尾提议或反问了，这句是「好 / 行 / 你决定 / 你看着办」
     * → 改写成「原话【接着上一轮】用户答：…」重新提交（购物 → 跑腿；其余 → 分诊 0c-continue 进 AgentLoop），不当闲聊答。
     */
    public static boolean interceptContinuation(final FloatingSheetService svc, String command) {
        try {
            if (svc == null || command == null) return false;
            final String rw = com.onesay.assistant.v2.router.TaskContinuation.rewrite(command, System.currentTimeMillis());
            if (rw == null) return false;
            Log.i(TAG, "[CONTINUE] 「" + com.onesay.assistant.v2.util.LogText.u(command) + "」接回上一轮任务 → " + com.onesay.assistant.v2.util.LogText.u(rw));
            MAIN.post(new Runnable() {
                @Override public void run() {
                    try {
                        svc.onSubmitCommand(rw);
                    } catch (Throwable t) {
                        Log.w(TAG, "[CONTINUE] 重新提交失败: " + t.getMessage());
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 面板输入：「打开第三个」且最近摆出的编号列表是跑腿来源 → 在接手页（电脑版）打开第 N 个来源。主线程。 */
    public static boolean interceptOrdinal(FloatingSheetService svc, String command) {
        try {
            if (svc == null || command == null || !enabled(svc)) return false;
            if (!com.onesay.assistant.v2.vision.screenref.NumberedRefs.isLatest(com.onesay.assistant.v2.vision.screenref.NumberedRefs.OWNER_ERRAND, System.currentTimeMillis())) {
                return false;
            }
            String[] src;
            int size;
            int k;
            synchronized (sLastSources) {
                size = sLastSources.size();
                k = com.onesay.assistant.v2.vision.screenref.NumberedRefs.pick(command, size);
                src = k >= 0 ? sLastSources.get(k) : null;
            }
            FloatingSheetView sheet = svc.getSheetView();
            if (k == com.onesay.assistant.v2.vision.screenref.NumberedRefs.OUT_OF_RANGE) {
                if (sheet != null) sheet.showAskReply(command, "刚才只有 " + size + " 个来源，可以说「打开第一个」到「第" + size + "个」。");
                return true;
            }
            if (src == null) return false;
            Log.i(TAG, "[ERRAND] 按编号打开第 " + (k + 1) + " 个来源");
            svc.s1HideSheetNow();
            svc.startActivity(ErrandBrowserActivity.intent(svc, src[1], null, ErrandBrowserActivity.MODE_HANDOFF,
                    "第 " + (k + 1) + " 个来源：" + ResearchLadder.clip(src[0].length() > 0 ? src[0] : src[1], 24)));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[ERRAND] 按编号打开来源失败: " + t.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 状态

    public static String debugStatus(Context ctx) {
        return ErrandSettings.dump(ctx) + " running=" + sRunning.keySet() + " last=" + sLast;
    }
}
