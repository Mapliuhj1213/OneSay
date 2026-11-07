package com.onesay.assistant.v2.kernel;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [kernel] 功能插件内核：注册表 + 生命周期 + 挂钩分发（2026-10-09 第 2 阶段）。
 *
 * <p>宿主（UnifiedRouter / L0RuleRouter / FloatingSheetService）在原来逐个写死功能调用的位置，改为调内核的一个方法；
 * 内核按每个挂钩上的优先级依次调各插件。迁移进来的功能用优先级复刻迁移前的顺序，行为不变。
 *
 * <h3>隔离</h3>
 * 每次插件调用都包 try/catch：抛异常的插件记一条 {@code [KERNEL]} 警告并按「没接管 / 返回空」处理，其他插件照常。
 * 唯一例外是 {@link #dispatch}：插件已认领签名后抛异常，按「已处理」返回，避免把它的签名落到通用执行路径上。
 *
 * <h3>生命周期</h3>
 * {@link #assemble(Context)}（面板服务 onCreate）：每个插件 onInit 一次，再按总开关 onEnable；
 * {@link #refresh(Context)}（每次唤醒）：总开关翻转的插件补调 onEnable / onDisable。onInit 抛异常的插件标记 FAILED，之后整轮跳过。
 *
 * <p>线程：注册在类加载时完成（{@link BuiltinPlugins}），之后只读；状态表用同步方法保护。
 */
public final class FeatureKernel {

    private static final String TAG = "FeatureKernel";

    public enum State { REGISTERED, ENABLED, DISABLED, FAILED }

    private static volatile FeatureKernel sInstance;

    private final List<FeaturePlugin> mPlugins = new ArrayList<FeaturePlugin>();
    private final Map<String, State> mState = new LinkedHashMap<String, State>();
    private final Map<FeatureHook, List<FeaturePlugin>> mOrder = new EnumMap<FeatureHook, List<FeaturePlugin>>(FeatureHook.class);
    private boolean mInitDone;

    /** 带内置插件的全局实例。 */
    public static FeatureKernel get() {
        FeatureKernel k = sInstance;
        if (k == null) {
            synchronized (FeatureKernel.class) {
                k = sInstance;
                if (k == null) {
                    k = new FeatureKernel();
                    BuiltinPlugins.registerAll(k);
                    sInstance = k;
                }
            }
        }
        return k;
    }

    /** 空内核（单测用）。 */
    public static FeatureKernel newEmpty() {
        return new FeatureKernel();
    }

    // ------------------------------------------------------------------ 注册表

    public synchronized FeatureKernel register(FeaturePlugin p) {
        if (p == null) return this;
        for (FeaturePlugin q : mPlugins) {
            if (q.id().equals(p.id())) throw new IllegalArgumentException("插件 id 重复: " + p.id());
        }
        mPlugins.add(p);
        mState.put(p.id(), State.REGISTERED);
        mOrder.clear();
        return this;
    }

    public synchronized List<FeaturePlugin> plugins() {
        return Collections.unmodifiableList(new ArrayList<FeaturePlugin>(mPlugins));
    }

    public synchronized FeaturePlugin find(String id) {
        for (FeaturePlugin p : mPlugins) if (p.id().equals(id)) return p;
        return null;
    }

    public synchronized State state(String id) {
        return mState.get(id);
    }

    /** 某挂钩上的调用顺序（稳定排序：优先级小的先，相同按注册顺序）。 */
    public synchronized List<FeaturePlugin> order(final FeatureHook hook) {
        List<FeaturePlugin> l = mOrder.get(hook);
        if (l == null) {
            l = new ArrayList<FeaturePlugin>(mPlugins);
            final List<FeaturePlugin> reg = new ArrayList<FeaturePlugin>(mPlugins);
            Collections.sort(l, new Comparator<FeaturePlugin>() {
                @Override
                public int compare(FeaturePlugin a, FeaturePlugin b) {
                    int pa = a.priority(hook), pb = b.priority(hook);
                    if (pa != pb) return pa < pb ? -1 : 1;
                    return reg.indexOf(a) - reg.indexOf(b);
                }
            });
            l = Collections.unmodifiableList(l);
            mOrder.put(hook, l);
        }
        return l;
    }

    /** 某挂钩上的插件 id 顺序（测试 / 文档）。 */
    public List<String> orderIds(FeatureHook hook) {
        List<String> out = new ArrayList<String>();
        for (FeaturePlugin p : order(hook)) out.add(p.id());
        return out;
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动装配：每个插件 onInit 一次，再按总开关 onEnable。可重复调用（只 init 一次）。 */
    public void assemble(Context ctx) {
        List<FeaturePlugin> ps;
        synchronized (this) {
            if (mInitDone) {
                ps = null;
            } else {
                mInitDone = true;
                ps = new ArrayList<FeaturePlugin>(mPlugins);
            }
        }
        if (ps != null) {
            for (FeaturePlugin p : ps) {
                try {
                    p.onInit(ctx);
                } catch (Throwable t) {
                    setState(p, State.FAILED);
                    Log.w(TAG, "[KERNEL] " + p.id() + ".onInit 异常，本插件停用: " + t);
                }
            }
        }
        refresh(ctx);
    }

    /** 比对总开关：翻转的插件调 onEnable / onDisable。返回发生翻转的插件数。 */
    public int refresh(Context ctx) {
        int flips = 0;
        for (FeaturePlugin p : plugins()) {
            State s = state(p.id());
            if (s == State.FAILED) continue;
            boolean on;
            try {
                on = p.enabled(ctx);
            } catch (Throwable t) {
                Log.w(TAG, "[KERNEL] " + p.id() + ".enabled 异常，按关闭处理: " + t);
                on = false;
            }
            State want = on ? State.ENABLED : State.DISABLED;
            if (s == want) continue;
            try {
                if (on) p.onEnable(ctx);
                else if (s == State.ENABLED) p.onDisable(ctx);
                setState(p, want);
                if (s != State.REGISTERED) flips++;
            } catch (Throwable t) {
                setState(p, State.FAILED);
                Log.w(TAG, "[KERNEL] " + p.id() + (on ? ".onEnable" : ".onDisable") + " 异常，本插件停用: " + t);
            }
        }
        return flips;
    }

    private synchronized void setState(FeaturePlugin p, State s) {
        mState.put(p.id(), s);
    }

    /** 本插件此刻是否参与挂钩分发。 */
    private boolean active(FeaturePlugin p, Context ctx) {
        if (state(p.id()) == State.FAILED) return false;
        if (!p.kernelGated()) return true;
        try {
            return p.enabled(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void warn(FeaturePlugin p, String hook, Throwable t) {
        Log.w(TAG, "[KERNEL] " + p.id() + "." + hook + " 异常（按未接管处理，不影响其他插件）: " + t);
    }

    // ------------------------------------------------------------------ 分诊 / L0 / 路由

    public TriageSwitches triageSwitches(Context ctx) {
        boolean diag = false, implicit = false, img = false, sum = false, s3 = false;
        for (FeaturePlugin p : order(FeatureHook.TRIAGE)) {
            String sig = p.triageSignal();
            if (sig == null || !active(p, ctx)) continue;
            boolean on;
            try {
                on = p.triageOn(ctx);
            } catch (Throwable t) {
                warn(p, "triageOn", t);
                on = false;
            }
            if (TriageSwitches.DIAG.equals(sig)) diag |= on;
            else if (TriageSwitches.IMPLICIT_MEMORY.equals(sig)) implicit |= on;
            else if (TriageSwitches.IMAGE_SAVE.equals(sig)) img |= on;
            else if (TriageSwitches.SUMMARY.equals(sig)) sum |= on;
            else if (TriageSwitches.S3.equals(sig)) s3 |= on;
        }
        return new TriageSwitches(diag, implicit, img, sum, s3);
    }

    /** L0 直达命中。 */
    public static final class Hit {
        public final FeaturePlugin plugin;
        public final Invocation inv;

        Hit(FeaturePlugin plugin, Invocation inv) {
            this.plugin = plugin;
            this.inv = inv;
        }
    }

    public Hit l0(Context ctx, String raw) {
        for (FeaturePlugin p : order(FeatureHook.L0)) {
            if (!active(p, ctx)) continue;
            try {
                Invocation inv = p.l0(ctx, raw);
                if (inv != null) return new Hit(p, inv);
            } catch (Throwable t) {
                warn(p, "l0", t);
            }
        }
        return null;
    }

    public Hit takeover(Context ctx, String query, L2LLMCompiler l2) {
        for (FeaturePlugin p : order(FeatureHook.TAKEOVER)) {
            if (!active(p, ctx)) continue;
            try {
                Invocation inv = p.route(ctx, query, l2);
                if (inv != null) return new Hit(p, inv);
            } catch (Throwable t) {
                warn(p, "route", t);
            }
        }
        return null;
    }

    /** 任一插件认领这句话 → 不进 AgentLoop。 */
    public boolean claims(Context ctx, String query) {
        for (FeaturePlugin p : order(FeatureHook.CLAIMS)) {
            if (!active(p, ctx)) continue;
            try {
                if (p.claims(ctx, query)) return true;
            } catch (Throwable t) {
                warn(p, "claims", t);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 面板

    public boolean interceptCommand(FloatingSheetService svc, String command) {
        for (FeaturePlugin p : order(FeatureHook.INTERCEPT)) {
            if (!active(p, svc)) continue;
            try {
                if (p.interceptCommand(svc, command)) return true;
            } catch (Throwable t) {
                warn(p, "interceptCommand", t);
            }
        }
        return false;
    }

    public boolean bypassScreenRef(Context ctx, String command) {
        for (FeaturePlugin p : order(FeatureHook.BYPASS_SCREENREF)) {
            if (!active(p, ctx)) continue;
            try {
                if (p.bypassScreenRef(ctx, command)) return true;
            } catch (Throwable t) {
                warn(p, "bypassScreenRef", t);
            }
        }
        return false;
    }

    public boolean dispatch(FloatingSheetService svc, Invocation inv, String command) {
        for (FeaturePlugin p : order(FeatureHook.DISPATCH)) {
            if (!active(p, svc)) continue;
            boolean claimed = false;
            try {
                claimed = p.dispatch(svc, inv, command);
            } catch (Throwable t) {
                warn(p, "dispatch", t);
                return true;   // 已进入插件的派发：不落到通用执行路径
            }
            if (claimed) return true;
        }
        return false;
    }

    /** 注意：DESCRIBE 不按总开关过滤（迁移前查能力描述与开关无关）。 */
    public Capability describe(String sign) {
        for (FeaturePlugin p : order(FeatureHook.DESCRIBE)) {
            if (state(p.id()) == State.FAILED) continue;
            try {
                Capability c = p.describe(sign);
                if (c != null) return c;
            } catch (Throwable t) {
                warn(p, "describe", t);
            }
        }
        return null;
    }

    public void onReplyShown(FloatingSheetService svc, Invocation inv) {
        for (FeaturePlugin p : order(FeatureHook.REPLY_SHOWN)) {
            if (!active(p, svc)) continue;
            try {
                p.onReplyShown(svc, inv);
            } catch (Throwable t) {
                warn(p, "onReplyShown", t);
            }
        }
    }

    public void onExecuted(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
        for (FeaturePlugin p : order(FeatureHook.EXECUTED)) {
            if (!active(p, svc)) continue;
            try {
                p.onExecuted(svc, cap, args, r);
            } catch (Throwable t) {
                warn(p, "onExecuted", t);
            }
        }
    }

    public void onExecFailed(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
        for (FeaturePlugin p : order(FeatureHook.EXEC_FAILED)) {
            if (!active(p, svc)) continue;
            try {
                p.onExecFailed(svc, cap, args, r);
            } catch (Throwable t) {
                warn(p, "onExecFailed", t);
            }
        }
    }

    /** 服务创建：先装配（onInit / onEnable），再按顺序调 onServiceCreate。 */
    public void onServiceCreate(FloatingSheetService svc) {
        assemble(svc);
        for (FeaturePlugin p : order(FeatureHook.SERVICE_CREATE)) {
            if (!active(p, svc)) continue;
            try {
                p.onServiceCreate(svc);
            } catch (Throwable t) {
                warn(p, "onServiceCreate", t);
            }
        }
    }

    /** 服务销毁：不按开关过滤（关掉的功能也要释放它开着的窗口 / 线程）。 */
    public void onServiceDestroy(FloatingSheetService svc) {
        for (FeaturePlugin p : order(FeatureHook.SERVICE_DESTROY)) {
            if (state(p.id()) == State.FAILED) continue;
            try {
                p.onServiceDestroy(svc);
            } catch (Throwable t) {
                warn(p, "onServiceDestroy", t);
            }
        }
    }

    /** 新唤醒、识屏之前（[s1] 收起在岛上的前台任务被新唤醒接管）。 */
    public void onWakeEarly(FloatingSheetService svc) {
        for (FeaturePlugin p : order(FeatureHook.WAKE_EARLY)) {
            if (!active(p, svc)) continue;
            try {
                p.onWakeEarly(svc);
            } catch (Throwable t) {
                warn(p, "onWakeEarly", t);
            }
        }
    }

    /** 新唤醒、识屏之后（[roles] 收起上一轮残留的安装提示）；随后比对开关（{@link #refresh}）。 */
    public void onWake(FloatingSheetService svc) {
        for (FeaturePlugin p : order(FeatureHook.WAKE)) {
            if (!active(p, svc)) continue;
            try {
                p.onWake(svc);
            } catch (Throwable t) {
                warn(p, "onWake", t);
            }
        }
        refresh(svc);
    }

    /** 物理键手势，第一个处理的插件胜出；没人处理返回 false。 */
    public boolean onKeyGesture(Context ctx, String gesture) {
        for (FeaturePlugin p : order(FeatureHook.KEY_GESTURE)) {
            if (!active(p, ctx)) continue;
            try {
                if (p.onKeyGesture(ctx, gesture)) return true;
            } catch (Throwable t) {
                warn(p, "onKeyGesture", t);
            }
        }
        return false;
    }

    /** 是否有启用中的插件要接这个手势。 */
    public boolean wantsKeyGesture(Context ctx, String gesture) {
        for (FeaturePlugin p : order(FeatureHook.KEY_GESTURE)) {
            if (!active(p, ctx)) continue;
            try {
                if (p.wantsKeyGesture(ctx, gesture)) return true;
            } catch (Throwable t) {
                warn(p, "wantsKeyGesture", t);
            }
        }
        return false;
    }

    public void afterExec(Context ctx, String query, Capability cap, JSONObject args, ExecutionResult r) {
        for (FeaturePlugin p : order(FeatureHook.AFTER_EXEC)) {
            if (!active(p, ctx)) continue;
            try {
                p.afterExec(ctx, query, cap, args, r);
            } catch (Throwable t) {
                warn(p, "afterExec", t);
            }
        }
    }

    // ------------------------------------------------------------------ 第 4 阶段（D-3a）：识屏类挂钩

    /** AGENT_PROMPT 的结果：改写后的内容 + 是否注入了不可信内容。 */
    public static final class Prompt {
        public final String content;
        public final boolean tainted;

        Prompt(String content, boolean tainted) {
            this.content = content;
            this.tainted = tainted;
        }
    }

    /** ★ device-1102：任一插件要求这句进 AgentLoop（{@link FeaturePlugin#wantsAgentLoop}）。 */
    public boolean wantsAgentLoop(Context ctx, String query, L2LLMCompiler l2) {
        for (FeaturePlugin p : order(FeatureHook.CLAIMS)) {
            if (!active(p, ctx)) continue;
            try {
                if (p.wantsAgentLoop(ctx, query, l2)) return true;
            } catch (Throwable t) {
                warn(p, "wantsAgentLoop", t);
            }
        }
        return false;
    }

    /** ★ device-1102：AgentLoop 开局提示（各插件按 AGENT_PROMPT 顺序拼接）；没有返回 null。 */
    public String agentStartHint(Context ctx, String query, boolean webOffered) {
        StringBuilder sb = null;
        for (FeaturePlugin p : order(FeatureHook.AGENT_PROMPT)) {
            if (!active(p, ctx)) continue;
            try {
                String h = p.agentStartHint(ctx, query, webOffered);
                if (h == null || h.length() == 0) continue;
                if (sb == null) sb = new StringBuilder(h);
                else sb.append('\n').append(h);
            } catch (Throwable t) {
                warn(p, "agentStartHint", t);
            }
        }
        return sb == null ? null : sb.toString();
    }

    /** ★ device-1102：AgentLoop 收工前提醒（第一个非 null）。 */
    public String agentFinishNudge(Context ctx, String query, boolean webOffered, List<String> calledSigns) {
        for (FeaturePlugin p : order(FeatureHook.AGENT_PROMPT)) {
            if (!active(p, ctx)) continue;
            try {
                String n = p.agentFinishNudge(ctx, query, webOffered, calledSigns);
                if (n != null && n.length() > 0) return n;
            } catch (Throwable t) {
                warn(p, "agentFinishNudge", t);
            }
        }
        return null;
    }

    /** AgentLoop 用户消息改写（按顺序链式）；某插件抛异常 = 跳过它的改写。 */
    public Prompt agentPrompt(Context ctx, String query, String content) {
        String cur = content;
        boolean tainted = false;
        for (FeaturePlugin p : order(FeatureHook.AGENT_PROMPT)) {
            if (!active(p, ctx)) continue;
            try {
                String next = p.agentPrompt(ctx, query, cur);
                if (next == null) next = cur;
                if (p.agentPromptTainted(cur, next)) tainted = true;   // ctx == null 的口径由宿主判（AgentLoop：mContext != null &&）
                cur = next;
            } catch (Throwable t) {
                warn(p, "agentPrompt", t);
            }
        }
        return new Prompt(cur, tainted);
    }

    public String agentPrecheck(Context ctx, String name, JSONObject args) {
        for (FeaturePlugin p : order(FeatureHook.AGENT_PRECHECK)) {
            if (!active(p, ctx)) continue;
            try {
                String obs = p.agentPrecheck(ctx, name, args);
                if (obs != null) return obs;
            } catch (Throwable t) {
                warn(p, "agentPrecheck", t);
            }
        }
        return null;
    }

    public boolean screenReference(FloatingSheetService svc, String command) {
        for (FeaturePlugin p : order(FeatureHook.SCREEN_REFERENCE)) {
            if (!active(p, svc)) continue;
            try {
                if (p.screenReference(svc, command)) return true;
            } catch (Throwable t) {
                warn(p, "screenReference", t);
            }
        }
        return false;
    }

    public void onTextMode(FloatingSheetService svc) {
        for (FeaturePlugin p : order(FeatureHook.TEXT_MODE)) {
            if (!active(p, svc)) continue;
            try {
                p.onTextMode(svc);
            } catch (Throwable t) {
                warn(p, "onTextMode", t);
            }
        }
    }

    public int regionChips(FloatingSheetService svc, Object region) {
        for (FeaturePlugin p : order(FeatureHook.REGION_CHIPS)) {
            if (!active(p, svc)) continue;
            try {
                int n = p.regionChips(svc, region);
                if (n > 0) return n;
            } catch (Throwable t) {
                warn(p, "regionChips", t);
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ 第 4 阶段（D-3a）：灵动岛承载面

    private volatile IslandHost mIsland;

    /** 岛控制器挂载（面板服务 onCreate，S1Hooks 建好 IslandController 之后）。 */
    public void attachIsland(IslandHost host) {
        mIsland = host;
    }

    /** 岛控制器卸载（面板服务 onDestroy）；只卸载自己挂的那个。 */
    public void detachIsland(IslandHost host) {
        if (mIsland == host) mIsland = null;
    }

    public boolean islandAttached() {
        return mIsland != null;
    }

    /** 插件往岛上放活动的通道（owner = 插件 id）。 */
    public IslandHost.Channel island(String owner) {
        return new IslandHost.Channel(this, owner);
    }

    /** 通道的转交目标：没挂载 → null；插件 FAILED、或 kernelGated 且总开关关 → null；没声明的种类照转并警告。 */
    IslandHost islandTarget(String owner, String kind) {
        IslandHost h = mIsland;
        if (h == null) return null;
        FeaturePlugin p = find(owner);
        if (p != null) {
            if (!active(p, h.hostContext())) return null;
            if (kind != null && !contains(p.islandActivityKinds(), kind)) {
                Log.w(TAG, "[KERNEL] 插件 " + owner + " 往岛上放了没声明的活动种类 " + kind + "（照常显示，请在 islandActivityKinds() 里声明）");
            }
        }
        return h;
    }

    /** 2026-10-23（lane-a B-9）：只用于「撤下」—— 已挂载的岛，不看插件开关。 */
    IslandHost islandCleanupTarget() {
        return mIsland;
    }

    private static boolean contains(String[] a, String x) {
        if (a == null) return false;
        for (String s : a) if (x.equals(s)) return true;
        return false;
    }

    // ------------------------------------------------------------------ AgentLoop / 执行后端

    public void appendAgentTools(Context ctx, org.json.JSONArray tools, String query) {
        for (FeaturePlugin p : order(FeatureHook.AGENT_TOOLS)) {
            if (!active(p, ctx)) continue;
            try {
                p.appendAgentTools(ctx, tools, query);
            } catch (Throwable t) {
                warn(p, "appendAgentTools", t);
            }
        }
    }

    public Capability describeForAgent(Context ctx, String name) {
        for (FeaturePlugin p : order(FeatureHook.AGENT_DESCRIBE)) {
            if (!active(p, ctx)) continue;
            try {
                Capability c = p.describeForAgent(ctx, name);
                if (c != null) return c;
            } catch (Throwable t) {
                warn(p, "describeForAgent", t);
            }
        }
        return null;
    }

    public ExecutionResult executeForAgent(Context ctx, Capability cap, JSONObject args) {
        for (FeaturePlugin p : order(FeatureHook.AGENT_EXECUTE)) {
            if (!active(p, ctx)) continue;
            try {
                ExecutionResult r = p.executeForAgent(ctx, cap, args);
                if (r != null) return r;
            } catch (Throwable t) {
                warn(p, "executeForAgent", t);
            }
        }
        return null;
    }

    /**
     * 各插件的执行后端（BACKENDS 顺序）。执行器构造时收集一次；不按开关过滤（迁移前后端常驻，开关在能力 / 引擎里判）。
     */
    public List<com.onesay.assistant.v2.executor.backend.ExecutorBackend> executorBackends() {
        List<com.onesay.assistant.v2.executor.backend.ExecutorBackend> out = new ArrayList<com.onesay.assistant.v2.executor.backend.ExecutorBackend>();
        for (FeaturePlugin p : order(FeatureHook.BACKENDS)) {
            if (state(p.id()) == State.FAILED) continue;
            try {
                com.onesay.assistant.v2.executor.backend.ExecutorBackend b = p.executorBackend();
                if (b != null) out.add(b);
            } catch (Throwable t) {
                warn(p, "executorBackend", t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 控制台

    /**
     * 控制台 v1 卡片（CONSOLE 顺序）：R12 起只画<b>没迁移到 v2</b> 的插件（「功能与权限 › 其他」）。
     * 不按开关过滤：关掉的功能也要能在控制台里打开。返回画了几个插件。
     */
    public int addConsoleCards(ConsoleHost host, android.widget.LinearLayout root) {
        int n = 0;
        for (FeaturePlugin p : order(FeatureHook.CONSOLE)) {
            if (state(p.id()) == State.FAILED) continue;
            try {
                if (p.consoleV2()) continue;
                int before = root.getChildCount();
                p.addConsoleCard(host, root);
                if (root.getChildCount() > before) n++;
            } catch (Throwable t) {
                warn(p, "addConsoleCard", t);
            }
        }
        return n;
    }

    /** [R12] 控制台 v2：按 CONSOLE 顺序让各插件往页面 page 里加内容。FAILED 的插件跳过（开发者页列出原因）。 */
    public void addConsolePage(ConsoleHost host, String page, android.widget.LinearLayout root) {
        for (FeaturePlugin p : order(FeatureHook.CONSOLE)) {
            if (state(p.id()) == State.FAILED) continue;
            try {
                if (p.consoleV2()) p.addConsoleEntries(host, page, root);
            } catch (Throwable t) {
                warn(p, "addConsoleEntries(" + page + ")", t);
            }
        }
    }

    /**
     * [控制台分组 2026-11-05] 同 {@link #addConsolePage(ConsoleHost, String, android.widget.LinearLayout)}，但只让一部分插件画：
     * {@code include} 非空 = 只画这些 id；否则画除 {@code exclude} 以外的全部（以后新加的插件默认落在这里，不会丢）。
     * 插件本身的 addConsoleEntries 不变 —— 只是控制台按分组挑谁画在哪一页。
     */
    public void addConsolePage(ConsoleHost host, String page, android.widget.LinearLayout root, String[] include, String[] exclude) {
        for (FeaturePlugin p : order(FeatureHook.CONSOLE)) {
            if (state(p.id()) == State.FAILED) continue;
            if (!pickedFor(p.id(), include, exclude)) continue;
            try {
                if (p.consoleV2()) p.addConsoleEntries(host, page, root);
            } catch (Throwable t) {
                warn(p, "addConsoleEntries(" + page + ")", t);
            }
        }
    }

    /** include 非空 → id 在 include 里；否则 id 不在 exclude 里。纯逻辑，JVM 可测。 */
    public static boolean pickedFor(String id, String[] include, String[] exclude) {
        if (include != null && include.length > 0) {
            for (String s : include) if (s.equals(id)) return true;
            return false;
        }
        if (exclude != null) for (String s : exclude) if (s.equals(id)) return false;
        return true;
    }

    /** [R12] 某插件的一句控制台状态（首页入口行）。后台线程。 */
    public String consoleSummary(Context ctx, String id) {
        FeaturePlugin p = find(id);
        if (p == null || state(id) == State.FAILED) return null;
        try {
            return p.consoleSummary(ctx);
        } catch (Throwable t) {
            warn(p, "consoleSummary", t);
            return null;
        }
    }

    /** [R12] 某插件的首页大卡数据 {数字, 副标题}。后台线程。 */
    public String[] consoleTile(Context ctx, String id) {
        FeaturePlugin p = find(id);
        if (p == null || state(id) == State.FAILED) return null;
        try {
            return p.consoleTile(ctx);
        } catch (Throwable t) {
            warn(p, "consoleTile", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 依赖（第 3 阶段：只声明 + 提示）

    /** 某插件声明的依赖里，此刻没注册 / 已停用 / 总开关关着的那些（不改变任何插件的行为）。 */
    public List<String> missingDeps(Context ctx, String id) {
        List<String> out = new ArrayList<String>();
        FeaturePlugin p = find(id);
        if (p == null) return out;
        String[] deps;
        try {
            deps = p.dependsOn();
        } catch (Throwable t) {
            return out;
        }
        if (deps == null) return out;
        for (String d : deps) {
            FeaturePlugin q = find(d);
            if (q == null || state(d) == State.FAILED) {
                out.add(d);
                continue;
            }
            boolean on;
            try {
                on = q.enabled(ctx);
            } catch (Throwable t) {
                on = false;
            }
            if (!on) out.add(d);
        }
        return out;
    }

    /** 声明了但没注册的依赖 id（单测用：防止写错 id）。 */
    public List<String> unknownDeps() {
        List<String> out = new ArrayList<String>();
        for (FeaturePlugin p : plugins()) {
            for (String d : p.dependsOn()) if (find(d) == null) out.add(p.id() + "→" + d);
        }
        return out;
    }

    /** debugExtra → 插件 id（null 的跳过；重复的抛异常 —— 单测保证）。 */
    public java.util.Map<String, String> debugExtras() {
        java.util.Map<String, String> m = new LinkedHashMap<String, String>();
        for (FeaturePlugin p : plugins()) {
            String e = p.debugExtra();
            if (e == null) continue;
            if (m.containsKey(e)) throw new IllegalStateException("debugExtra 重复: " + e);
            m.put(e, p.id());
        }
        return m;
    }

    // ------------------------------------------------------------------ 调试

    /** 多行状态：每个插件一行。 */
    public String dump(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (FeaturePlugin p : plugins()) {
            String st;
            try {
                st = p.debugStatus(ctx);
            } catch (Throwable t) {
                st = "status异常 " + t;
            }
            sb.append(p.id()).append(" state=").append(state(p.id()))
                    .append(" module=").append(p.acceptanceModule())
                    .append(" tag=").append(p.logTag());
            List<String> miss = missingDeps(ctx, p.id());
            if (!miss.isEmpty()) sb.append(" deps-off=").append(miss);
            String[] kinds = p.islandActivityKinds();
            if (kinds != null && kinds.length > 0) sb.append(" island=").append(java.util.Arrays.toString(kinds));
            sb.append(" | ").append(st).append('\n');
        }
        sb.append("island-host=").append(islandAttached() ? "attached" : "none").append('\n');
        return sb.toString();
    }
}
