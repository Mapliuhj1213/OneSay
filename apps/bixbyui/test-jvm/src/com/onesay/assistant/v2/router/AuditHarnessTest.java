package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * ★ int/1102 全量小毛病审计（用户标准：功能更新完立即跑几百条常用指令）。
 *
 * <p>语料 = triage-fast 的 380 句（{@code triage_corpus.tsv}）+ 本轮扩充（{@code audit_extra.tsv}，scripts/gen_audit_corpus.py），
 * 共 ≥500 句，逐条在 JVM 路由模型（{@link RouteLayerModel}，复刻 shouldUseAgentLoop + cascade）里跑，抓五类：
 * <ol>
 *   <li><b>ASK</b> 多余追问：批量 / 信息已全的句子落到 CLARIFY，或单步能力缺必填参数（线上会 SlotFiller 反问）；</li>
 *   <li><b>DENY</b> 无确认卡硬拒：单步 shell 能力的命令被 exec 守护 v4.4 判定模式拒（线上 = DAEMON_DENIED，无卡无降级）；</li>
 *   <li><b>PRE</b> 漏前置：导航没走带「定位前置」的导航模板；依赖蓝牙 / NFC / WiFi 的句子单步执行却不带前置开关；</li>
 *   <li><b>SLOW</b> 不必要的慢路径：能模板却进 LLM / AgentLoop / GUI；诊断句被单步开关模板误吃也记在这里（路径不当）；</li>
 *   <li><b>LEAK</b> 回吐 / 泄漏：热词表 / 提示词回吐没被 PromptLeakGuard 拦，或正常指令被误拦。</li>
 * </ol>
 * 结果写 {@code -Daudit.out}（缺省 test-jvm/build/audit-findings.tsv），并断言每类条目都在 {@link #KNOWN} 清单里（仍未修项，报告列明）。
 */
public class AuditHarnessTest {

    static final class Row {
        final String utt, group, target;
        final Set<String> flags = new LinkedHashSet<String>();

        Row(String u, String g, String t, String f) {
            utt = u;
            group = g;
            target = t;
            if (f != null && !"-".equals(f)) for (String x : f.split(",")) flags.add(x.trim());
        }
    }

    static final List<Row> rows = new ArrayList<Row>();
    static RouteLayerModel model;
    static CapabilityRegistry reg;

    /** 仍未修项（类别|句子）。每条的原因见 INT-1102.md §审计「仍未修」。 */
    /** 日程审计固定在一个周三中午（上海时区），不随跑测试的日期与时区变化：语料里有「周日晚上七点」之类的句子，周日当天跑会被判成需要追问。 */
    static final java.util.TimeZone AUDIT_TZ = java.util.TimeZone.getTimeZone("Asia/Shanghai");
    static final long AUDIT_NOW_MS = 1793764800000L; // 2026-11-04 (Wed) 12:00 +08:00
    static final Set<String> KNOWN = new LinkedHashSet<String>();

    static {
        // （修后清单在下方 knownRemaining() 里维护）
    }

    static File root() {
        return new File(System.getProperty("bixby.root", "."));
    }

    static void load(String rel, boolean extra) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(new File(root(), rel)), "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("#") || line.trim().length() == 0) continue;
            String[] f = line.split("\t");
            rows.add(new Row(f[0], f[1], f.length > 2 ? f[2] : "-", extra && f.length > 3 ? f[3] : "-"));
        }
        br.close();
    }

    @BeforeClass public static void setup() throws Exception {
        load("test-jvm/fixtures/triage_corpus.tsv", false);
        load("test-jvm/fixtures/audit_extra.tsv", true);
        model = new RouteLayerModel(true);
        reg = model.registry();
    }

    // ------------------------------------------------------------------ 单步落点

    /** 单步落点的 Invocation（快路径 / 计划 / L0）；没有返回 null。 */
    static Invocation landing(String q) {
        Invocation f = FastIntents.route(q);
        if (f != null) return f;
        Invocation plan = CompoundSplitter.split(q, new CompoundSplitter.Resolver() {
            @Override public Invocation resolve(String clause) { return model.l0(clause); }
        });
        if (plan != null) return plan;
        Invocation l0 = model.l0(q);
        if (l0 != null) return l0;
        String core = TriageDecider.stripFrames(TriageDecider.normalize(q));
        return core != null && core.length() >= 2 ? model.l0(core) : null;
    }

    static Capability cap(String sign) {
        Capability c = reg.describe(sign);
        if (c == null) c = com.onesay.assistant.v2.s2.S2Capabilities.get(sign);
        if (c == null) c = com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.get(sign);
        return c;
    }

    static List<Invocation> steps(Invocation inv) {
        List<Invocation> l = new ArrayList<Invocation>();
        if (inv == null) return l;
        if (inv.isPlan()) l.addAll(inv.steps);
        else l.add(inv);
        return l;
    }

    static Set<String> missingRequired(Invocation inv) {
        Set<String> miss = new LinkedHashSet<String>();
        for (Invocation s : steps(inv)) {
            Capability c = cap(s.sign);
            if (c == null || c.paramsSchema == null) continue;
            for (String k : c.paramsSchema.getRequired()) {
                if (s.args == null || !s.args.has(k) || String.valueOf(s.args.opt(k)).trim().length() == 0) miss.add(s.sign + "." + k);
            }
        }
        return miss;
    }

    static final Map<String, String> DAEMON_CACHE = new HashMap<String, String>();

    static String daemon(String cmd) throws Exception {
        String v = DAEMON_CACHE.get(cmd);
        if (v == null) {
            v = com.onesay.assistant.v2.sec.SecIntExecWhitelistCiTest.check(cmd);
            DAEMON_CACHE.put(cmd, v);
        }
        return v;
    }

    /** 单步 shell 能力（没有 ShellPrograms 覆盖的）实际发给守护的命令；闹钟不经守护。 */
    static List<String> daemonCmds(Invocation inv) {
        List<String> out = new ArrayList<String>();
        for (Invocation s : steps(inv)) {
            Capability c = cap(s.sign);
            if (c == null) continue;
            if ("alarmbridge".equals(c.executorType)) continue;   // 闹钟走 OneSay 时钟接口 / 系统 AlarmClock，不经守护
            if (!"shell".equals(c.executorType) || c.cmdTemplate == null) continue;
            if (com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign(s.sign) != null) continue;   // 程序化命令由 SecIntExecWhitelistCiTest 逐条守
            Map<String, String> slots = new HashMap<String, String>();
            if (s.args != null) {
                Iterator<String> it = s.args.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    slots.put(k, String.valueOf(s.args.opt(k)));
                }
            }
            if (c.paramsSchema != null) {
                for (Map.Entry<String, com.onesay.assistant.v2.capability.ParamsSchema.PropertyRule> e : c.paramsSchema.getRules().entrySet()) {
                    if (!slots.containsKey(e.getKey()) && e.getValue().defaultValue != null) slots.put(e.getKey(), e.getValue().defaultValue);
                }
            }
            out.add(com.onesay.assistant.v2.executor.ShellCommand.fill(c.cmdTemplate, slots));
        }
        return out;
    }

    // ------------------------------------------------------------------ 审计

    static final class Finding {
        final String cat, utt, group, detail;

        Finding(String c, Row r, String d) {
            cat = c;
            utt = r.utt;
            group = r.group;
            detail = d;
        }

        String key() {
            return cat + "|" + utt;
        }
    }

    static boolean isSlowLayer(RouteLayerModel.Layer l) {
        return l == RouteLayerModel.Layer.L2 || l == RouteLayerModel.Layer.AGENT || l == RouteLayerModel.Layer.GUI;
    }

    static List<Finding> audit() throws Exception {
        List<Finding> out = new ArrayList<Finding>();
        boolean sh = com.onesay.assistant.v2.sec.SecIntExecWhitelistCiTest.haveSh();
        com.onesay.assistant.plug.leakguard.core.PromptLeakGuard guard = productionGuard();
        for (Row r : rows) {
            RouteLayerModel.Result res = model.predict(r.utt);
            Invocation land = (res.layer == RouteLayerModel.Layer.L0 || res.layer == RouteLayerModel.Layer.L1) ? landing(r.utt) : null;
            String where = res.layer + "|" + res.sign + " #" + res.rule;

            // ⑤ LEAK
            boolean leak = guard.check(r.utt).leak();
            if (r.flags.contains("leak") && !leak) out.add(new Finding("LEAK", r, "回吐没被拦"));
            if (!r.flags.contains("leak") && leak) out.add(new Finding("LEAK", r, "正常指令被误拦（" + guard.check(r.utt).reason + "）"));
            if (r.flags.contains("leak")) continue;

            // ① ASK
            if ((r.flags.contains("batch") || r.flags.contains("complete")) && "clarify".equals(res.sign)) {
                out.add(new Finding("ASK", r, "信息已全 / 批量却判 CLARIFY：" + where));
            }
            if (land != null && res.layer == RouteLayerModel.Layer.L0) {
                Set<String> miss = missingRequired(land);
                if (!miss.isEmpty()) out.add(new Finding("ASK", r, "落点缺必填参数 " + miss + " → 会反问：" + where));
            }

            // ★ int/1102 日程：必须走本地日程（不被记一记 / 记忆吞），信息全不追问（真跑一遍 SchedEngine，空库）
            if (r.flags.contains("sched")) {
                if (res.rule.startsWith("6m") || res.rule.startsWith("3m")) out.add(new Finding("SLOW", r, "日程句被记一记 / 记忆吞：" + where));
                else if (!res.rule.startsWith("6d") && !res.rule.startsWith("5d")) out.add(new Finding("SLOW", r, "日程句没走本地日程：" + where));
                com.onesay.assistant.v2.sched.SchedEngine se = new com.onesay.assistant.v2.sched.SchedEngine(new com.onesay.assistant.v2.sched.SchedStore.Memory(), AUDIT_TZ);
                com.onesay.assistant.v2.sched.SchedEngine.Reply sr = se.handle(r.utt, AUDIT_NOW_MS);
                if (sr == null) out.add(new Finding("SLOW", r, "日程引擎不接：" + where));
                else if (sr.kind == com.onesay.assistant.v2.sched.SchedEngine.Kind.ASK && !r.flags.contains("askok")) out.add(new Finding("ASK", r, "日程多余追问：" + sr.text));
                else if (sr.text.contains("记住了") || sr.text.contains("记下了")) out.add(new Finding("SLOW", r, "日程回执像记一记：" + sr.text));
            }

            // ② DENY
            if (sh && land != null) {
                for (String c : daemonCmds(land)) {
                    String v = daemon(c);
                    if (v.startsWith("DENY")) out.add(new Finding("DENY", r, v + " ← " + c));
                }
            }

            // ③ PRE
            if (r.flags.contains("nav")) {
                boolean ok = land != null && !land.isPlan() && ("s2.nav.app".equals(land.sign) || "screen.nav.to".equals(land.sign));
                if (!ok) out.add(new Finding("PRE", r, "导航没走带定位前置的导航模板：" + where));
            }
            if ("前置".equals(r.group) && land != null) {
                String signs = land.isPlan() ? land.toString() : land.sign;
                boolean pre = signs.contains("bt") || signs.contains("bluetooth") || signs.contains("nfc") || signs.contains("wifi");
                if (!pre) out.add(new Finding("PRE", r, "单步执行不带前置开关：" + where));
            }

            // ④ SLOW（含「路径不当」）
            if (!"-".equals(r.target)) {
                String[] t = r.target.split("\\|");
                boolean ok = res.layer.name().equals(t[0]) && ("*".equals(t[1]) || res.sign.equals(t[1]));
                if (!ok && isSlowLayer(res.layer)) out.add(new Finding("SLOW", r, "期望 " + r.target + " 实际 " + where));
                else if (!ok) out.add(new Finding("SLOW", r, "落点不对：期望 " + r.target + " 实际 " + where));
            } else {
                if (r.flags.contains("nolloop") && (res.layer == RouteLayerModel.Layer.AGENT || res.layer == RouteLayerModel.Layer.GUI)) {
                    out.add(new Finding("SLOW", r, "不该进 AgentLoop/GUI：" + where));
                } else if (r.flags.contains("nogui") && res.layer == RouteLayerModel.Layer.GUI) {
                    out.add(new Finding("SLOW", r, "不该进 GUI：" + where));
                }
                if (r.flags.contains("notl0") && res.layer == RouteLayerModel.Layer.L0) {
                    out.add(new Finding("SLOW", r, "诊断句被单步模板误吃：" + where));
                }
            }
        }
        return out;
    }

    /** 模拟的「手机上已装应用」标签（路由模型那张 + 回吐语料里点名的 6 个）。 */
    static final List<String> DEVICE_APP_LABELS = new ArrayList<String>(RouteLayerModel.INSTALLED);

    static {
        java.util.Collections.sort(DEVICE_APP_LABELS);
        DEVICE_APP_LABELS.addAll(java.util.Arrays.asList("闲鱼", "得物", "盒马", "叮咚买菜", "朴朴", "山姆会员商店"));
    }

    /**
     * ★ 按生产方式构建的守卫：调线上同一份 {@link com.onesay.assistant.v2.leakguard.LeakGuardHost#configure}（热词前缀 + 静态热词 + 全部系统提示词来源），
     * 再像线上 {@code AsrHotwords.compose} 那样把<b>真实会发出去的</b>热词上下文（{@code wordsFor(已装应用) → composeText}）交给
     * {@code addHotwordCorpus}。JVM 没有 Context：agent_prompt 用 assets 原文代替 PromptSource，PromptLibrary 用内置默认模板（线上同样会注册默认模板）。
     */
    static com.onesay.assistant.plug.leakguard.core.PromptLeakGuard productionGuard() throws Exception {
        com.onesay.assistant.plug.leakguard.core.PromptLeakGuard g = new com.onesay.assistant.plug.leakguard.core.PromptLeakGuard();
        String agent = new String(java.nio.file.Files.readAllBytes(new File(root(), "app/assets/agent_prompt.md").toPath()), "UTF-8");
        com.onesay.assistant.v2.leakguard.LeakGuardHost.configure(g, null, agent);
        g.addHotwordCorpus(com.onesay.assistant.v2.asr.AsrHotwords.composeText(com.onesay.assistant.v2.asr.AsrHotwords.wordsFor(DEVICE_APP_LABELS)));
        return g;
    }

    /** 线上真正发给 ASR 的热词上下文（含已装应用名）—— 报告用：静态词已占满 300 字上限时，动态应用名一个也进不去。 */
    @Test public void hotwordContextBudget() {
        String out = com.onesay.assistant.v2.asr.AsrHotwords.composeText(com.onesay.assistant.v2.asr.AsrHotwords.wordsFor(DEVICE_APP_LABELS));
        java.util.Set<String> sent = new java.util.HashSet<String>(java.util.Arrays.asList(out.split(" ")));
        int stat = 0, dyn = 0;
        for (String w : com.onesay.assistant.v2.asr.AsrHotwords.staticWords()) if (sent.contains(w)) stat++;
        for (String w : DEVICE_APP_LABELS) if (sent.contains(w) && !java.util.Arrays.asList(com.onesay.assistant.v2.asr.AsrHotwords.staticWords()).contains(w)) dyn++;
        System.out.println("[HOTWORDS] 上下文 " + out.length() + " 字：静态词 " + stat + "/" + com.onesay.assistant.v2.asr.AsrHotwords.staticWords().length
                + "，动态应用名 " + dyn);
        assertTrue(out.length() <= com.onesay.assistant.v2.asr.AsrHotwords.MAX_CHARS_V2);
    }

    static Map<String, List<Finding>> byCat(List<Finding> fs) {
        Map<String, List<Finding>> m = new LinkedHashMap<String, List<Finding>>();
        for (String c : new String[]{"ASK", "DENY", "PRE", "SLOW", "LEAK"}) m.put(c, new ArrayList<Finding>());
        for (Finding f : fs) m.get(f.cat).add(f);
        return m;
    }

    @Test public void corpusAtLeast500() {
        assertTrue("审计语料 ≥500 句，实际 " + rows.size(), rows.size() >= 500);
    }

    /** 跑审计、落盘、打印每类计数；断言没有清单外的新毛病。 */
    @Test public void auditAll() throws Exception {
        List<Finding> fs = audit();
        Map<String, List<Finding>> m = byCat(fs);
        File outF = new File(System.getProperty("audit.out", new File(root(), "test-jvm/build/audit-findings.tsv").getPath()));
        outF.getParentFile().mkdirs();
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outF), "UTF-8"));
        pw.println("# int/1102 审计结果：类别\t分组\t句子\t说明（语料 " + rows.size() + " 句）");
        StringBuilder sum = new StringBuilder("[AUDIT] 语料 " + rows.size() + " 句：");
        for (Map.Entry<String, List<Finding>> e : m.entrySet()) {
            sum.append(e.getKey()).append('=').append(e.getValue().size()).append(' ');
            for (Finding f : e.getValue()) pw.println(f.cat + "\t" + f.group + "\t" + f.utt + "\t" + f.detail);
        }
        pw.close();
        System.out.println(sum + " → " + outF);
        StringBuilder unexpected = new StringBuilder();
        Set<String> known = knownRemaining();
        for (Finding f : fs) if (!known.contains(f.key())) unexpected.append("\n  ").append(f.cat).append(" [").append(f.group).append("] ")
                .append(f.utt).append(" — ").append(f.detail);
        assertEquals("清单外的新毛病：" + unexpected, 0, unexpected.length());
    }

    /** 仍未修项（修后清单；每条原因见 INT-1102.md）。 */
    static Set<String> knownRemaining() {
        Set<String> s = new LinkedHashSet<String>(KNOWN);
        String p = System.getProperty("audit.known");
        if (p != null) {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(p), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) if (line.trim().length() > 0 && !line.startsWith("#")) s.add(line.trim());
                br.close();
            } catch (Exception ignored) {
            }
        }
        File f = new File(root(), "test-jvm/fixtures/audit_known.txt");
        if (f.isFile()) {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) if (line.trim().length() > 0 && !line.startsWith("#")) s.add(line.trim());
                br.close();
            } catch (Exception ignored) {
            }
        }
        return s;
    }
}
