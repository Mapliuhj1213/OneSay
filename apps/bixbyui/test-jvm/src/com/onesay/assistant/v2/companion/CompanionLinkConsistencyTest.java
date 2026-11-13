package com.onesay.assistant.v2.companion;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * 伴侣连接 · 三个 App 之间的一致性（读源码 / 清单 / 构建脚本，不需要真机）：
 * <ol>
 *   <li>契约常量：助手 CompanionContract ↔ 计划 core/LinkContract ↔ 时钟 core/LinkContract 逐项一致；</li>
 *   <li>权限名：release / debug 两个变体下，计划 / 时钟清单（占位符 ${assistantAppId} 按各自构建脚本填）里引用的助手权限，
 *       都正好是助手清单（debug 按 build_linux.sh 的 sed 改名）里声明的那个 —— 11-08 真机的 CONTROL 名对不上就是这条；</li>
 *   <li>接线：路由优先级、AgentLoop 闸门、AlarmBridge 连接优先、简报经连接对账。</li>
 * </ol>
 * 计划 / 时钟工程默认在 ../plan、../clock（开源仓库 apps/ 下）；另有 -Dcompanion.plan.root / -Dcompanion.clock.root。找不到就跳过跨工程部分。
 */
public class CompanionLinkConsistencyTest {

    static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读不到 " + f + ": " + e);
        }
    }

    static String src(String rel) {
        return read(new File(rel));
    }

    static File planRoot() {
        return new File(System.getProperty("companion.plan.root", "../plan"));
    }

    static File clockRoot() {
        return new File(System.getProperty("companion.clock.root", "../clock"));
    }

    static void needSiblings() {
        Assume.assumeTrue("计划 / 时钟工程不在旁边，跳过跨工程检查",
                new File(planRoot(), "app/AndroidManifest.xml").exists() && new File(clockRoot(), "app/AndroidManifest.xml").exists());
    }

    static final String ASSISTANT_SRC = "app/src/" + CompanionContract.class.getPackage().getName().replace('.', '/') + "/";

    static File findOne(File root, String name) {
        List<File> l = new ArrayList<File>();
        walk(new File(root, "app/src"), name, l);
        assertEquals(name + " 应正好一个: " + l, 1, l.size());
        return l.get(0);
    }

    static void walk(File d, String name, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) walk(f, name, out);
            else if (f.getName().equals(name) && f.getPath().contains("/core/")) out.add(f);
        }
    }

    // ------------------------------------------------------------------ 1. 契约常量

    static final Pattern STR = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\s*=\\s*\"([^\"]*)\"");
    static final Pattern INT = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\s*=\\s*(\\d+)\\s*[,;]");

    static Map<String, String> consts(String s) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        Matcher a = STR.matcher(s);
        while (a.find()) m.put(a.group(1), a.group(2));
        Matcher b = INT.matcher(s);
        while (b.find()) if (!m.containsKey(b.group(1))) m.put(b.group(1), b.group(2));
        return m;
    }

    static List<String> array(String s, String name, Map<String, String> c) {
        int i = s.indexOf(name + " = {");
        assertTrue("没有数组 " + name, i >= 0);
        String body = s.substring(s.indexOf('{', i) + 1, s.indexOf('}', i));
        List<String> out = new ArrayList<String>();
        for (String p : body.split(",")) {
            p = p.trim();
            if (p.length() == 0) continue;
            out.add(c.containsKey(p) ? c.get(p) : p.replace("\"", ""));
        }
        return out;
    }

    static final String[] SHARED = {"IFACE", "VERSION", "MIN_PEER_VERSION", "DEBUG_SUFFIX", "META_ASSISTANT", "ACTION_QUERY", "M_HELLO",
            "K_IFACE", "K_VERSION", "K_MIN_CLIENT", "K_CAPS", "K_APP_VERSION", "K_DESCRIPTOR", "K_CLIENT_VERSION", "K_CLIENT_PKG",
            "K_OK", "K_CODE", "K_DATA", "K_ERROR", "K_METHOD", "RC_OK", "RC_BAD_ARGS", "RC_NOT_FOUND", "RC_UNKNOWN", "F_GET_ASSISTANT_STATUS"};

    @Test public void contractConstantsMatchAcrossTheThreeApps() {
        needSiblings();
        String as = src(ASSISTANT_SRC + "CompanionContract.java");
        String ps = read(findOne(planRoot(), "LinkContract.java"));
        String cs = read(findOne(clockRoot(), "LinkContract.java"));
        Map<String, String> a = consts(as), p = consts(ps), c = consts(cs);
        for (String k : SHARED) {
            assertTrue("助手缺 " + k, a.containsKey(k));
            assertEquals("计划 " + k, a.get(k), p.get(k));
            assertEquals("时钟 " + k, a.get(k), c.get(k));
        }
        assertEquals("ASSISTANT_PKG（计划）", a.get("ASSISTANT_PKG"), p.get("ASSISTANT_PKG"));
        assertEquals("ASSISTANT_PKG（时钟）", a.get("ASSISTANT_PKG"), c.get("ASSISTANT_PKG"));
        for (String f : new String[]{"F_CREATE_TASK", "F_LIST_TASKS", "F_UPDATE_TASK", "F_COMPLETE_TASK", "F_DELETE_TASK", "F_CREATE_EVENT", "F_LIST_EVENTS",
                "X_ID", "X_TITLE", "X_DUE_AT", "X_DONE", "X_START_AT", "X_END_AT", "X_LOCATION", "X_FROM", "X_TO", "X_INCLUDE_DONE"})
            assertEquals("计划 " + f, a.get(f), p.get(f));
        for (String f : new String[]{"F_CREATE_ALARM", "F_LIST_ALARMS", "F_DELETE_ALARM", "F_TOGGLE_ALARM", "F_GET_BRIEF_STATUS",
                "X_ID", "X_HOUR", "X_MINUTE", "X_DAYS", "X_ON", "X_LABEL"})
            assertEquals("时钟 " + f, a.get(f), c.get(f));
        assertEquals("计划能力表", array(as, "PLAN_CAPS", a), array(ps, "PLAN_CAPS", p));
        assertEquals("时钟能力表", array(as, "CLOCK_CAPS", a), array(cs, "CLOCK_CAPS", c));
        // authority / 权限 / 包名
        Map<String, String> pc = consts(read(findOne(planRoot(), "Contract.java")));
        assertEquals(a.get("PLAN_PKG"), pc.get("PKG"));
        assertEquals(a.get("PLAN_AUTHORITY"), pc.get("AUTHORITY"));
        assertEquals(a.get("PLAN_PERMISSION"), pc.get("PERMISSION"));
        assertEquals(a.get("CLOCK_AUTHORITY"), c.get("AUTHORITY"));
        Map<String, String> api = consts(read(findOne(clockRoot(), "AlarmApi.java")));
        assertEquals(a.get("CLOCK_PERMISSION"), api.get("PERMISSION"));
        // 闹钟函数 ↔ 旧广播动作（助手 CompanionAlarm 与时钟 LinkContract.ALARM_MAP）
        for (String fn : CompanionContract.CLOCK_CAPS) {
            String act = CompanionAlarm.action(fn);
            if (fn.equals(CompanionContract.F_GET_BRIEF_STATUS)) {
                assertNull(act);
                continue;
            }
            assertNotNull(fn, act);
            String key = null;
            for (Map.Entry<String, String> e : api.entrySet()) if (e.getValue().equals(act)) key = e.getKey();
            assertNotNull("时钟 AlarmApi 没有动作 " + act, key);
            assertTrue("时钟 ALARM_MAP 里 " + key + " ↔ " + fn, cs.replaceAll("\\s+", " ").contains("{AlarmApi." + key + ", F_" + constName(a, fn) + "}"));
        }
    }

    static String constName(Map<String, String> a, String value) {
        for (Map.Entry<String, String> e : a.entrySet()) if (e.getKey().startsWith("F_") && e.getValue().equals(value)) return e.getKey().substring(2);
        return "?";
    }

    // ------------------------------------------------------------------ 2. 权限名一致（release / debug）

    static final Pattern DECLARED = Pattern.compile("<permission\\s+android:name=\"([^\"]+)\"");
    static final Pattern USES = Pattern.compile("<uses-permission\\s+android:name=\"([^\"]+)\"");
    static final Pattern SED = Pattern.compile("-e 's#([^#]+)#([^#]+)#g'");

    /** 助手清单在某个变体下的样子：debug = 按 build_linux.sh 里的 sed 改名（解析脚本本身，不在测试里另抄一份）。 */
    static String assistantManifest(boolean debug) {
        String m = src("app/AndroidManifest.xml");
        if (!debug) return m;
        Matcher s = SED.matcher(src("build_linux.sh"));
        int n = 0;
        while (s.find()) {
            m = m.replace(s.group(1).replace("\\.", "."), s.group(2));
            n++;
        }
        assertTrue("build_linux.sh 的 debug 改名没解析到", n >= 2);
        return m;
    }

    static Set<String> all(Pattern p, String m) {
        Set<String> out = new LinkedHashSet<String>();
        Matcher x = p.matcher(m.replaceAll("<!--(?s:.*?)-->", ""));
        while (x.find()) out.add(x.group(1));
        return out;
    }

    static String peerManifest(File root, String assistantId) {
        return read(new File(root, "app/AndroidManifest.xml")).replace("${assistantAppId}", assistantId);
    }

    /** 组件开始标签里的属性值（到开始标签的 '>' 为止）。 */
    static String attr(String m, String component, String attr) {
        int i = m.indexOf(component);
        assertTrue("清单里没有 " + component, i > 0);
        String tag = m.substring(i, m.indexOf('>', i));
        Matcher x = Pattern.compile("android:" + attr + "=\"([^\"]+)\"").matcher(tag);
        return x.find() ? x.group(1) : null;
    }

    @Test public void permissionNamesMatchInBothVariants() {
        needSiblings();
        String rel = CompanionContract.ASSISTANT_PKG, dbg = CompanionContract.ASSISTANT_PKG + CompanionContract.DEBUG_SUFFIX;
        // 计划 / 时钟构建脚本的默认值正是这两个（debug 构建配 debug 助手）
        String planSh = read(new File(planRoot(), "build_linux.sh"));
        assertTrue(planSh.contains("ASSISTANT_APP_ID=\"${ASSISTANT_APP_ID:-" + dbg + "}\""));
        assertTrue(planSh.contains("ASSISTANT_APP_ID=\"${ASSISTANT_APP_ID:-" + rel + "}\""));
        String clockDbg = read(new File(clockRoot(), "build_linux.sh"));
        assertTrue(clockDbg.contains("ASSISTANT_APP_ID=\"${ASSISTANT_APP_ID:-" + dbg + "}\""));
        File clockRelSh = new File(clockRoot(), "build_release.sh");
        if (clockRelSh.exists()) assertTrue(read(clockRelSh).contains("ASSISTANT_APP_ID=\"${ASSISTANT_APP_ID:-" + rel + "}\""));

        for (boolean debug : new boolean[]{false, true}) {
            String v = debug ? "debug" : "release";
            String am = assistantManifest(debug);
            Set<String> declared = all(DECLARED, am);
            String control = CompanionContract.assistantControlPermission(debug ? dbg : rel);
            assertTrue(v + " 助手声明 " + control, declared.contains(control));
            assertEquals(v + " 伴侣接收器受 CONTROL 保护", control, attr(am, "v2.companion.CompanionLinkReceiver", "permission"));
            assertEquals(v + " 简报接收器受 CONTROL 保护", control, attr(am, "v2.brief.BriefStateReceiver", "permission"));
            for (File root : new File[]{planRoot(), clockRoot()}) {
                String pm = peerManifest(root, debug ? dbg : rel);
                Set<String> uses = all(USES, pm);
                assertTrue(v + " " + root + " 要 uses-permission " + control + "，实际 " + uses, uses.contains(control));
                for (String u : uses) {
                    if (u.startsWith(CompanionContract.ASSISTANT_PKG + "."))
                        assertTrue(v + " " + root + " 引用了助手没声明的权限 " + u, declared.contains(u));
                }
                assertFalse(root + " 不得写死 release 名", read(new File(root, "app/AndroidManifest.xml"))
                        .contains("android:name=\"" + CompanionContract.assistantControlPermission(rel) + "\""));
                assertTrue(root + " meta-data 配对助手", pm.contains("android:name=\"" + CompanionContract.META_ASSISTANT + "\" android:value=\"" + (debug ? dbg : rel) + "\""));
            }
            // 助手 → 计划 / 时钟的权限（不随变体）
            Set<String> aUses = all(USES, am);
            assertTrue(aUses.contains(CompanionContract.PLAN_PERMISSION));
            assertTrue(aUses.contains(CompanionContract.CLOCK_PERMISSION));
        }
        String plan = read(new File(planRoot(), "app/AndroidManifest.xml")), clock = read(new File(clockRoot(), "app/AndroidManifest.xml"));
        assertTrue(all(DECLARED, plan).contains(CompanionContract.PLAN_PERMISSION));
        assertTrue(all(DECLARED, clock).contains(CompanionContract.CLOCK_PERMISSION));
        int p = clock.indexOf(".api.LinkProvider");
        String prov = clock.substring(p, clock.indexOf("/>", p));
        assertTrue(prov.contains("android:authorities=\"" + CompanionContract.CLOCK_AUTHORITY + "\""));
        assertTrue(prov.contains("android:permission=\"" + CompanionContract.CLOCK_PERMISSION + "\""));
    }

    @Test public void assistantManifestStillHasNoProvider() {
        String m = src("app/AndroidManifest.xml");
        assertFalse("助手不放 provider（debug / release authority 会撞），反查走有序广播", m.contains("<provider"));
        int i = m.indexOf("v2.companion.CompanionLinkReceiver");
        assertTrue(i > 0);
        assertTrue(m.substring(i, m.indexOf("</receiver>", i)).contains("<action android:name=\"" + CompanionContract.ACTION_QUERY + "\""));
    }

    // ------------------------------------------------------------------ 3. 接线

    static String body(String s, String sig) {
        int i = s.indexOf(sig);
        assertTrue("找不到 " + sig, i >= 0);
        int j = s.indexOf("\n    }", i);
        return s.substring(i, j < 0 ? s.length() : j);
    }

    @Test public void routerPriorityWiring() {
        String ur = src("app/src/" + pkgPath("router") + "UnifiedRouter.java");
        String b = body(ur, "public boolean shouldUseAgentLoop(String query)");
        int claim = b.indexOf("companion.CompanionHooks.claims(mContext, query)) return false");
        assertTrue("shouldUseAgentLoop 里有伴侣闸门", claim > 0);
        assertTrue("在 API key 检查之前", claim < b.indexOf("hasApiKey()"));
        assertTrue("在诊断插件 wantsAgentLoop 之前", claim < b.indexOf("wantsAgentLoop("));
        assertTrue("在分诊之前", claim < b.indexOf("triage(query)"));

        String al = src("app/src/" + pkgPath("router") + "AgentLoop.java");
        assertTrue("AgentLoop 工具过闸有伴侣闸门", al.contains("companion.CompanionHooks.agentPrecheck(mContext, name, args)"));

        String pp = src("app/src/" + pkgPath("kernel/plugins") + "PlannerPlugin.java");
        String route = body(pp, "public Invocation route(Context ctx, String query, L2LLMCompiler l2)");
        assertTrue("计划插件先走伴侣路由", route.indexOf("CompanionHooks.route(ctx, query)") < route.indexOf("PlannerHooks.route(ctx, query)"));
        assertTrue(pp.contains("CompanionHooks.claims(ctx, query) || PlannerHooks.claims(ctx, query)"));
        // 计划插件 TAKEOVER 在 S3（300）之前
        Matcher m = Pattern.compile("case TAKEOVER: return (\\d+);").matcher(pp);
        assertTrue(m.find());
        assertTrue(Integer.parseInt(m.group(1)) < 300);

        String aal = src("app/src/" + pkgPath("executor") + "AndroidAlarmLink.java");
        String cc = body(aal, "public AlarmBridge.ClockReply callClock(AlarmBridge.ClockCmd cmd)");
        assertTrue("闹钟先走伴侣连接", cc.indexOf("callClockViaLink(cmd)") < cc.indexOf("sendOrderedBroadcast"));

        String br = src("app/src/" + pkgPath("brief") + "BriefStateReceiver.java");
        assertTrue("简报先经时钟连接对账（不要 root）", br.indexOf("briefStateViaLink(app)") > 0 && br.indexOf("briefStateViaLink(app)") < br.indexOf("BriefState.VERIFY_CMD"));

        String link = src(ASSISTANT_SRC + "CompanionLink.java");
        assertTrue("发现：签名", link.contains("checkSignatures(app.getPackageName(), p.pkg)"));
        assertTrue("发现：provider 归属", link.contains("resolveContentProvider(p.authority, 0)"));
        assertTrue("发现：握手", link.contains("CompanionContract.M_HELLO"));
        assertFalse("不用 shell / root", link.contains("ShellExecutor") || link.contains("Runtime.getRuntime"));
        assertFalse(src(ASSISTANT_SRC + "CompanionHooks.java").contains("ShellExecutor"));
    }

    static String pkgPath(String sub) {
        String base = CompanionContract.class.getPackage().getName().replace('.', '/');
        return base.substring(0, base.lastIndexOf('/') + 1) + sub + "/";
    }
}
