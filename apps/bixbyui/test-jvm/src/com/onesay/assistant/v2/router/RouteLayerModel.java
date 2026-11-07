package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * ★ fix/triage-fast：覆盖矩阵用的「这句话会走哪一层」模型（JVM，无 Context）。
 *
 * <p>逐行复刻线上入口：{@code FloatingSheetService.onSubmitCommand → UnifiedRouter.shouldUseAgentLoop（triage）
 * → AgentLoop | routeStreaming（cascade）}。用的都是线上同一批纯函数：{@code FastPathTriage}（反射：修前的代码里没有这个类，
 * 同一份模型也能编译到修前代码上跑出「修前」矩阵）、{@link L0RuleRouter#forRegistry}、{@link TriageDecider#decide}、
 * {@link AskEngine#tryAnswer}、{@code agenttools.GuiFold}。
 *
 * <p>近似的地方（报告里写明）：
 * <ul>
 *   <li>插件本地接管（存图 / 摘要 / S3 / S2 / 诊断）按 TriageDecider 里与插件同源的规则号判（6i/5s/6t/5t/6m/3m/5r/2d）；</li>
 *   <li>app.launch 的 {app} 槽位线上靠 IntentExecutor 查已装应用，JVM 没有 —— 用 {@link #INSTALLED} 这张「常见已装应用」表模拟，
 *       修前修后用同一张表；</li>
 *   <li>AMBIGUOUS 的 LLM 分类兜底：按线上带 Key 的情况记一次分类调用（+1.2 s），结论按 DO_ACT 处理（规则层没认出、但 L0/L1 都没接住的
 *       祈使句，线上几乎都被判成 DO_ACT → 进 AgentLoop）；</li>
 *   <li>L1 技能缓存按冷启动（未命中）算；默认配了 LLM Key（用户已配 DeepSeek）。</li>
 * </ul>
 */
public final class RouteLayerModel {

    public enum Layer {
        L0("L0 模板", 200),
        L1("L1 规则/本地", 300),
        L2("L2 LLM 单步", 2000),
        AGENT("AgentLoop 多步", 8000),
        GUI("GUI 点界面", 20000);

        public final String zh;
        /** 预期耗时（毫秒，经验值：L0 root 单命令 ~150 ms；一次 DeepSeek 调用 ~1.5 s；AgentLoop 平均 4 轮；GUI 每步 ~4 s）。 */
        public final int ms;

        Layer(String zh, int ms) {
            this.zh = zh;
            this.ms = ms;
        }
    }

    public static final class Result {
        public final Layer layer;
        public final String sign;
        public final String rule;
        public final int ms;

        Result(Layer layer, String sign, String rule, int extraMs) {
            this.layer = layer;
            this.sign = sign == null ? "-" : sign;
            this.rule = rule;
            this.ms = layer.ms + extraMs;
        }

        @Override public String toString() {
            return layer + "\t" + sign + "\t" + rule + "\t" + ms;
        }
    }

    /** 模拟「已装应用」（修前修后同一张）。 */
    public static final Set<String> INSTALLED = new HashSet<String>(Arrays.asList(
            "微信", "支付宝", "淘宝", "抖音", "高德地图", "百度地图", "美团", "美团外卖", "qq", "qq音乐", "网易云音乐", "哔哩哔哩", "b站",
            "京东", "拼多多", "小红书", "相册", "图库", "设置", "日历", "时钟", "计算器", "浏览器", "文件管理", "我的文件", "联系人",
            "电话", "信息", "短信", "地图", "谷歌地图", "youtube", "gmail", "chrome", "应用商店", "三星商店", "备忘录", "三星笔记",
            "录音机", "天气", "相机", "邮箱", "qq邮箱", "钉钉", "飞书", "知乎", "微博", "爱奇艺", "腾讯视频", "优酷", "滴滴出行", "滴滴"));

    private static final java.util.regex.Pattern LAUNCH = java.util.regex.Pattern.compile(
            "^(?:帮我|请|麻烦|给我|快)?(?:打开|启动|开启|进入|运行|开一下|打开一下)(.+?)(?:吧|一下)?$");

    private final CapabilityRegistry mReg;
    private final L0RuleRouter mL0;
    private final boolean mFast;

    /** @param fast true = 用修后代码的分诊第 0 层（需要 FastPathTriage / FastCaps 在 classpath 上）；false = 修前行为 */
    public RouteLayerModel(boolean fast) {
        mFast = fast && hasFastPath();
        mReg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(mReg);
        if (mFast) {
            try {
                Class.forName("com.onesay.assistant.v2.capability.caps.FastCaps").getMethod("register", CapabilityRegistry.class).invoke(null, mReg);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
        mL0 = L0RuleRouter.forRegistry(mReg);
    }

    public static boolean hasFastPath() {
        try {
            Class.forName("com.onesay.assistant.v2.router.FastPathTriage");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ★ int/1102 审计用：模型里的能力表。 */
    public CapabilityRegistry registry() {
        return mReg;
    }

    public boolean fast() {
        return mFast;
    }

    /** L0 + app.launch 模拟。 */
    public Invocation l0(String q) {
        Invocation inv = null;
        try {
            inv = mL0.route(q);
        } catch (Throwable ignored) {
        }
        if (inv != null) return inv;
        java.util.regex.Matcher m = LAUNCH.matcher(TriageDecider.normalize(q));
        if (m.matches() && INSTALLED.contains(m.group(1))) {
            org.json.JSONObject a = new org.json.JSONObject();
            try { a.put("app", m.group(1)); } catch (Exception ignored) {}
            return new Invocation("app.launch", a, 1.0, Invocation.RouteSource.L0_RULE, q);
        }
        return null;
    }

    public Result predict(String query) {
        // ★ int/1102 常识：记一记插件在级联最前面（FeatureKernel.takeover，先于分诊 / 快路径）接管硬句式（取件码 / 停车 / 我的快递 / 随口记）
        //   日程插件（TAKEOVER 290）排在记一记（8000）前面：日程句先归日程
        if (mFast && com.onesay.assistant.v2.quicknote.QuickNoteIntent.beforeS2(query)
                && !(TriageDecider.sSchedOn && com.onesay.assistant.v2.sched.SchedEngine.looksLikeSchedule(query))) return new Result(Layer.L1, "plugin:quicknote", "kq-quicknote", 0);
        if (mFast) {
            Object t = fastTriage(query);
            if (t != null) {
                try {
                    Invocation plan = (Invocation) t.getClass().getField("plan").get(t);
                    Invocation l0 = (Invocation) t.getClass().getField("l0").get(t);
                    if (plan != null) {
                        StringBuilder sb = new StringBuilder();
                        for (Invocation s : plan.steps) sb.append(sb.length() > 0 ? "+" : "").append(s.sign);
                        return new Result(Layer.L1, sb.toString(), "0c-compound", 150 * (plan.steps.size() - 1));
                    }
                    return new Result(Layer.L0, l0.sign, "0f-fast", 0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        Invocation l0 = mFast ? l0WithCore(query) : l0(query);
        String l0Sign = l0 != null ? l0.sign : null;
        TriageDecider.Decision d0 = TriageDecider.decide(query, new TriageDecider.Signals(l0Sign, false, false));
        boolean askLocal = AskEngine.tryAnswer(query) != null
                || (d0.core != null && d0.core.length() > 0 && AskEngine.tryAnswer(d0.core) != null);
        TriageDecider.Decision d = TriageDecider.decide(query, new TriageDecider.Signals(l0Sign, false, askLocal));
        String r = d.rule;
        // 插件本地接管（与插件同源的规则号）
        if (r.startsWith("6d") || r.startsWith("5d") || r.startsWith("6i") || r.startsWith("5s") || r.startsWith("6t") || r.startsWith("5t") || r.startsWith("6m")
                || r.startsWith("3m") || r.startsWith("5r") || r.startsWith("2d")) {
            return new Result(Layer.L1, "plugin:" + r, r, 0);
        }
        int extra = 0;
        if (d.label == TriageDecider.Label.AMBIGUOUS) {
            extra = 1200;   // LLM 分类兜底
            d = d.relabel(TriageDecider.Label.DO_ACT, TriageDecider.LLM_MIN_CONFIDENCE, "llm",
                    new TriageDecider.Signals(l0Sign, false, askLocal));
            r = "10-unsure→llm";
        }
        if (d.useAgentLoop) {
            boolean gui = guiFirst(query, d.label.name());
            return new Result(gui ? Layer.GUI : Layer.AGENT, gui ? "phone.operate" : "agent", r, extra);
        }
        switch (d.label) {
            case ASK:
                return askLocal ? new Result(Layer.L1, "ask.local", r, extra) : new Result(Layer.L2, "ask.reply", r, extra);
            case CLARIFY:
                return new Result(Layer.L1, "clarify", r, extra);
            case DIAGNOSE:
                return new Result(Layer.L1, "diag", r, extra);
            case MIXED:
                return new Result(Layer.AGENT, "agent", r, extra);
            default:
                if (l0 != null) return new Result(Layer.L0, l0.sign, r, extra);
                if (askLocal) return new Result(Layer.L1, "ask.local", r, extra);
                return new Result(Layer.L2, "llm.compile", r, extra);
        }
    }

    private Invocation l0WithCore(String q) {
        Invocation hit = l0(q);
        if (hit != null) return hit;
        String n = TriageDecider.normalize(q);
        String core = TriageDecider.stripFrames(n);
        if (core == null || core.length() < 2 || core.equals(n)) return null;
        return l0(core);
    }

    private Object fastTriage(final String query) {
        try {
            Class<?> fp = Class.forName("com.onesay.assistant.v2.router.FastPathTriage");
            Class<?> res = Class.forName("com.onesay.assistant.v2.router.CompoundSplitter$Resolver");
            Object resolver = java.lang.reflect.Proxy.newProxyInstance(res.getClassLoader(), new Class<?>[]{res},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object p, Method m, Object[] a) {
                            if ("resolve".equals(m.getName())) return l0((String) a[0]);
                            return null;
                        }
                    });
            Method m = fp.getDeclaredMethod("triage", String.class, res);
            m.setAccessible(true);
            return m.invoke(null, query, resolver);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean guiFirst(String q, String label) {
        try {
            Class<?> g = Class.forName("com.onesay.assistant.v2.agenttools.GuiFold");
            boolean explicit = (Boolean) g.getMethod("explicitUi", String.class).invoke(null, q);
            boolean tri = (Boolean) g.getMethod("triageGui", String.class, String.class).invoke(null, q, label);
            return explicit || tri;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 命令行：java RouteLayerModel <语料 tsv> <输出 tsv>（第一列 = 句子，第二列 = 分组）。 */
    public static void main(String[] args) throws Exception {
        RouteLayerModel m = new RouteLayerModel(true);
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(args[0]), "UTF-8"));
        java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(args[1]), "UTF-8"));
        pw.println("# fast=" + m.fast());
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("#") || line.trim().length() == 0) continue;
            String[] f = line.split("\t");
            Result r = m.predict(f[0]);
            pw.println(f[0] + "\t" + (f.length > 1 ? f[1] : "") + "\t" + r);
        }
        pw.close();
        br.close();
    }
}
