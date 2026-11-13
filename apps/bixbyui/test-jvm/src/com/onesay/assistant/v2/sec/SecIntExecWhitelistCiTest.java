package com.onesay.assistant.v2.sec;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ShellCommand;
import com.onesay.assistant.v2.executor.ShellScript;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-10-25 渗透测试修复·集成 —— fix-root.md §4-6 要的 CI：App 会经 root 通道发出的命令，逐条丢进
 * exec 守护 v4 的离线判定模式（{@code BIXBY_EXEC_CHECK=1 sh tools/kdaemon/exec_daemon.v4.sh '<命令>'}），断言 ALLOW。
 *
 * <p>三份语料：
 * <ol>
 *   <li>{@code tools/kdaemon/testdata/compat_cmds.txt}：fix-root 从 App 源码抽出的静态命令字面量（扣掉 3 条抽取误报的报错文案）；</li>
 *   <li>本轮拆分后真正执行的单命令（PowerWhitelist / ReliabilityCheck / 城市库 / 诊断快照 / 识屏 / 速记 / 存图 / 能力程序）；</li>
 *   <li>能力注册表里全部 shell 模板（cmd + check），槽位填样例值。</li>
 * </ol>
 * 已知守护侧缺口（{@link #KNOWN_GAPS}）单独断言 DENY 且原因不变 —— 守护补了白名单这里会红，提醒把它挪回 ALLOW；
 * 任何<b>新出现</b>的 DENY 都直接失败（新加的命令上机前就会被发现）。
 */
public class SecIntExecWhitelistCiTest {

    static File root() {
        return new File(System.getProperty("bixby.root", "."));
    }

    static File daemon() {
        return new File(root(), "tools/kdaemon/exec_daemon.v4.sh");
    }

    public static boolean haveSh() {
        return new File("/bin/sh").canExecute() && daemon().isFile();
    }

    /** 跑判定模式：返回 "ALLOW" / "ALLOW (template)" / "DENY <原因>"。 */
    public static String check(String cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", daemon().getAbsolutePath(), cmd, "com.onesay.assistant");
        pb.environment().put("BIXBY_EXEC_CHECK", "1");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        InputStream in = p.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        p.waitFor();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    static final String P = "/data/user/0/com.onesay.assistant/cache/scratch";

    /**
     * 守护侧还没覆盖、App 侧拆不动的命令（逐条对应 INTEGRATION.md「无法拆 / 需 fix-root 补白名单」）。值 = 期望的 DENY 原因前缀。
     */
    static final Map<String, String> KNOWN_GAPS = new LinkedHashMap<String, String>();
    static {
        // 2026-10-25 fix-root2（守护 v4.1）已补：uiautomator --compressed / nice -n 10（G2）、Pictures 受控写（G3）、
        //   dumpsys nfc / account（G4）—— 这几条已挪回 ALLOW，由 splitCommandsAreAllowedExceptKnownDaemonGaps 断言。
        for (String op : new String[]{"RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND"}) {
            KNOWN_GAPS.put("appops get com.onesay.assistant " + op, "PROG_NOT_WHITELISTED");
            KNOWN_GAPS.put("appops set com.onesay.assistant " + op + " allow", "PROG_NOT_WHITELISTED");
            KNOWN_GAPS.put("appops set com.onesay.assistant " + op + " default", "PROG_NOT_WHITELISTED");
        }
        KNOWN_GAPS.put("am start -a android.intent.action.VIEW -d about:blank", "URI_SCHEME");
        KNOWN_GAPS.put("am start -a android.intent.action.VIEW -d 'amapuri://drive/takeTaxi?sourceApplication=bixbyui&dname=abc&dev=0'", "URI_SCHEME");
    }

    /** 拆分后真正执行的命令。 */
    static List<String> splitCommands() {
        List<String> l = new ArrayList<String>();
        com.onesay.assistant.v2.jobs.PowerWhitelist.Original o = com.onesay.assistant.v2.jobs.PowerWhitelist.parse(
                "\n" + com.onesay.assistant.v2.jobs.PowerWhitelist.SEP + "\nNo operations.\n" + com.onesay.assistant.v2.jobs.PowerWhitelist.SEP + "\nNo operations.\n",
                "com.onesay.assistant", 28, 1L);
        l.addAll(ShellScript.commands(com.onesay.assistant.v2.jobs.PowerWhitelist.probeSteps("com.onesay.assistant", 28)));
        l.addAll(ShellScript.commands(com.onesay.assistant.v2.jobs.PowerWhitelist.applySteps(o)));
        l.addAll(ShellScript.commands(com.onesay.assistant.v2.jobs.PowerWhitelist.restoreSteps(o)));
        l.addAll(ShellScript.commands(com.onesay.assistant.v2.jobs.ReliabilityCheck.probeSteps("com.onesay.assistant")));
        l.addAll(ShellScript.commands(com.onesay.assistant.v2.diag.DiagSnapshot.buildReadSteps(com.onesay.assistant.v2.diag.DiagKnowledge.KEYS.keySet())));
        l.add(com.onesay.assistant.v2.vision.SecIntVisionAccess.captureCmd(P + "/vision/vision_shot.png"));
        l.add(com.onesay.assistant.v2.vision.SecIntVisionAccess.captureCmd("/storage/emulated/0/Android/data/com.onesay.assistant/cache/vision_shot.png"));
        l.add(com.onesay.assistant.v2.quicknote.SecIntQuickNoteAccess.captureCmd(P + "/quicknote/qn_raw.png"));
        l.add(com.onesay.assistant.v2.vision.screenref.SecIntScreenRefAccess.activityCmd());
        l.add(com.onesay.assistant.v2.vision.screenref.SecIntScreenRefAccess.dumpCmd(P + "/screenref/sr_ui.xml"));
        l.add(com.onesay.assistant.v2.vision.screenref.SecIntScreenRefAccess.dumpCmdLow(P + "/screenref/sr_ui.xml"));
        l.addAll(Arrays.asList(com.onesay.assistant.v2.imgsave.SecIntImgSaveAccess.captureSteps(
                new File(P + "/imgsave/scr.raw"), new File(P + "/imgsave/ui.xml"))));
        l.addAll(ShellScript.commands(com.onesay.assistant.v2.imgsave.SecIntImgSaveAccess.rootCopySteps(
                new File(P + "/imgsave/out_a.png"), new File("/storage/emulated/0/Pictures/OneSay/com.tencent.mm/a.png"))));
        // 能力程序（ShellPrograms）里的固定命令
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.BRIGHTNESS_GET);
        l.add("settings put system screen_brightness 120");
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.VOLUME_GET);
        l.add("media volume --stream 3 --set 7");
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.TORCH_GET);
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.TORCH_ON);
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.TORCH_OFF);
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.LOC_GET);
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.NFC_ON);
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.NFC_OFF);
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.NFC_STATE);
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.STORE_GOOGLE);
        l.add(com.onesay.assistant.v2.executor.backend.ShellPrograms.screenshotCmd());
        // 能力程序（ShellPrograms）：槽位填样例
        Map<String, String> slots = new HashMap<String, String>();
        slots.put("address", "abc");
        for (String t : new String[]{
                com.onesay.assistant.v2.executor.backend.ShellPrograms.RESOLVE_BROWSER,
                com.onesay.assistant.v2.executor.backend.ShellPrograms.BROWSER_FALLBACK,
                "am start -n com.example.browser/.MainActivity",
                com.onesay.assistant.v2.executor.backend.ShellPrograms.TAXI_AMAP,
                com.onesay.assistant.v2.executor.backend.ShellPrograms.TAXI_GEO,
                com.onesay.assistant.v2.executor.backend.ShellPrograms.NAV_GEO,
                com.onesay.assistant.v2.executor.backend.ShellPrograms.NAV_WEB}) {
            l.add(ShellCommand.fill(t, slots));
        }
        return l;
    }

    @Test public void splitCommandsAreAllowedExceptKnownDaemonGaps() throws Exception {
        Assume.assumeTrue("没有 /bin/sh 或守护脚本", haveSh());
        List<String> bad = new ArrayList<String>();
        Set<String> gapsSeen = new LinkedHashSet<String>();
        for (String c : new LinkedHashSet<String>(splitCommands())) {
            String v = check(c);
            if (KNOWN_GAPS.containsKey(c)) {
                gapsSeen.add(c);
                if (!v.startsWith("DENY " + KNOWN_GAPS.get(c))) bad.add("已知缺口判定变了（守护补了白名单？挪回 ALLOW）: " + c + " → " + v);
                continue;
            }
            if (!v.startsWith("ALLOW")) bad.add(c + " → " + v);
        }
        assertTrue("拆分后的命令被 v4 拒：\n" + join(bad), bad.isEmpty());
        // 已知缺口里的命令要么在语料里出现（是真的会发），要么就是 appops 兜底这类只在首选失败时才发的——都应被覆盖到
        for (String g : KNOWN_GAPS.keySet()) assertTrue("缺口条目没被任何拆分覆盖（过期了？）: " + g, gapsSeen.contains(g));
    }

    /** 灵动岛隐私提示的内联只读命令（IslandSmartHooks，逐字；源码里找不到就说明改过了，要同步这里）。 */
    static final String[] ISLAND_CMDS = {
            "dumpsys audio | grep -E 'RecordActivityMonitor|session:[0-9]+ -- source' | head -n 40",
            "dumpsys media.camera | grep -E 'Active Camera Clients|Client [Pp]ackage|^\\]' | head -n 20",
            "dumpsys media.audio_policy | grep -E -- '- session:|- owner uid:|Inputs dump' | head -n 60"};

    /**
     * 简单命令链（ShellExecutor 自动拆分的安全网）：晨报来源校验 / 标题对账的「stat … && cat …」。
     * 拆开后每段都应 ALLOW；守护固定模板必须原样整串（且守护确认是模板）。
     */
    @Test public void chainsSplitIntoAllowedSegmentsAndTemplatesStayWhole() throws Exception {
        Assume.assumeTrue("没有 /bin/sh 或守护脚本", haveSh());
        List<String> bad = new ArrayList<String>();
        for (String c : new String[]{com.onesay.assistant.v2.brief.BriefState.VERIFY_CMD, com.onesay.assistant.v2.brief.BriefDetail.VERIFY_CMD}) {
            List<String[]> ch = com.onesay.assistant.v2.executor.ExecProtocol.splitChain(c);
            assertNotNull(c, ch);
            assertEquals(2, ch.size());
            for (String[] s : ch) {
                String v = check(s[1]);
                if (!v.startsWith("ALLOW")) bad.add(s[1] + " → " + v);
            }
        }
        for (String t : com.onesay.assistant.v2.executor.ExecProtocol.FIXED_TEMPLATES) {
            assertNull("固定模板不拆: " + t, com.onesay.assistant.v2.executor.ExecProtocol.splitChain(t));
            assertEquals("App 镜像的固定模板与守护一致: " + t, "ALLOW (template)", check(t));
        }
        String isl = new String(Files.readAllBytes(new File(root(), "app/src/com/onesay/assistant/v2/notice/IslandSmartHooks.java").toPath()), StandardCharsets.UTF_8);
        for (String c : ISLAND_CMDS) {
            String lit = c.replace("\\", "\\\\");
            assertTrue("IslandSmartHooks 里的命令变了，同步 ISLAND_CMDS: " + c, isl.contains(lit));
            String v = check(c);
            if (!v.startsWith("ALLOW")) bad.add(c + " → " + v);
        }
        String v = check(com.onesay.assistant.v2.s1.SecIntS1Access.fullscreenCmd());
        if (!v.startsWith("ALLOW")) bad.add("FullscreenPolicy.CMD → " + v);
        assertTrue(join(bad), bad.isEmpty());
    }

    /**
     * 拆不动、v4 下预期被拒的复合命令（INTEGRATION.md「无法拆」）：守护按设计拦下的能力或需守护侧新增模板。
     * 断言它们<b>确实</b>被拒，免得有人以为能用；守护侧补了模板这里会红。
     */
    @Test public void unsplittableCompoundsAreDeniedAsDocumented() throws Exception {
        Assume.assumeTrue("没有 /bin/sh 或守护脚本", haveSh());
        // G7（fix-root2）：控制台按键守护探针原文现在是守护的固定模板（守护内建只读实现，只回 ALIVE|DEAD/MOD|NOMOD/版本/END）
        assertEquals("ALLOW (template)", check(com.onesay.assistant.v2.ui.console.ConsoleStatus.DAEMON_PROBE));
        assertNull(com.onesay.assistant.v2.executor.ExecProtocol.splitChain(com.onesay.assistant.v2.ui.console.ConsoleStatus.DAEMON_PROBE));
        // G8：存图缓存层读别的 App 私有缓存 —— v4 有意拦截，拒绝码明确
        String list = com.onesay.assistant.v2.imgsave.SecIntImgSaveAccess.cacheListCommand("com.tencent.mm");
        assertEquals("DENY OTHER_APP_DATA(com.tencent.mm)", check(list));
        assertNull(com.onesay.assistant.v2.executor.ExecProtocol.splitChain(list));
    }

    /** fix-root 的静态字面量语料：全部 ALLOW（扣掉 3 条以 "id " 开头的报错文案，它们是抽取误报，不是命令）。 */
    @Test public void staticCorpusAllAllowed() throws Exception {
        Assume.assumeTrue("没有 /bin/sh 或守护脚本", haveSh());
        File f = new File(root(), "tools/kdaemon/testdata/compat_cmds.txt");
        Assume.assumeTrue(f.isFile());
        List<String> bad = new ArrayList<String>();
        int n = 0;
        for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
            int t = line.indexOf('\t');
            if (t < 0) continue;
            String cmd = line.substring(t + 1);
            if (cmd.startsWith("id ") && !cmd.matches("id\\s*")) continue;   // 报错文案误报
            n++;
            String v = check(cmd);
            if (!v.startsWith("ALLOW")) bad.add(line + " → " + v);
        }
        assertTrue(n > 80);
        assertTrue("静态语料被 v4 拒：\n" + join(bad), bad.isEmpty());
    }

    // ------------------------------------------------------------------ 能力注册表

    static final Pattern SLOT = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)(?:[=:][^}]*)?\\}");

    static String sample(String name) {
        String n = name.toLowerCase();
        if (n.equals("component")) return "com.example.app/.MainActivity";
        if (n.equals("pkg") || n.equals("package")) return "com.example.app";
        if (n.contains("url") || n.equals("uri") || n.equals("link")) return "https://example.com/a";
        if (n.contains("number") || n.contains("phone") || n.equals("to")) return "10086";
        if (n.contains("address") || n.contains("keyword") || n.contains("query") || n.contains("text") || n.contains("content")
                || n.contains("name") || n.contains("city") || n.contains("title") || n.contains("body") || n.contains("message")) return "abc";
        return "1";
    }

    /**
     * 能力注册表里所有 shell 模板（填样例槽位后）：DENY 集合必须与下面登记的完全一致。
     * 登记的条目 = 能力模板里还在用的守护<b>固定模板</b>之外的复合写法 / 守护缺口，逐条写进 INTEGRATION.md；
     * 由 {@link com.onesay.assistant.v2.executor.backend.ShellPrograms} 接管执行的三个能力，其 cmdTemplate 只作展示，不在此列。
     */
    @Test public void capabilityTemplatesMatchRegisteredDenyList() throws Exception {
        Assume.assumeTrue("没有 /bin/sh 或守护脚本", haveSh());
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        Map<String, String> denied = new TreeMap<String, String>();
        int n = 0;
        for (Capability c : reg.listCapabilities()) {
            boolean prog = com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign(c.sign) != null;
            String[] tpls = {c.cmdTemplate, com.onesay.assistant.v2.executor.backend.ShellPrograms.checkFor(c.sign) != null ? null : c.checkCommand};
            for (int i = 0; i < tpls.length; i++) {
                String tpl = tpls[i];
                if (tpl == null || tpl.trim().length() == 0) continue;
                if (i == 0 && prog) continue;   // 由 ShellPrograms 接管执行，cmdTemplate 只作展示
                if (!"shell".equals(c.executorType) && !"intent".equals(c.executorType) && i == 0 && !tpl.startsWith("am ")
                        && !tpl.contains("settings ") && !tpl.contains("dumpsys")) continue;
                Map<String, String> vals = new HashMap<String, String>();
                Matcher m = SLOT.matcher(tpl);
                while (m.find()) vals.put(m.group(1), sample(m.group(1)));
                String filled;
                try {
                    filled = ShellCommand.fill(tpl, vals);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                n++;
                String v = check(filled);
                if (!v.startsWith("ALLOW")) denied.put(c.sign + (i == 0 ? "" : "#check"), v);
            }
        }
        assertTrue("能力模板数 " + n, n > 50);
        StringBuilder got = new StringBuilder();
        for (Map.Entry<String, String> e : denied.entrySet()) got.append(e.getKey()).append(" → ").append(e.getValue()).append('\n');
        System.out.println("[SecIntExecWhitelistCi] 能力模板 " + n + " 条，v4 拒 " + denied.size() + " 条：\n" + got);
        assertEquals("能力模板的 v4 拒绝集合变了（新命令被拒？或守护补了白名单？）：\n" + got,
                new java.util.TreeSet<String>(CAP_DENY_REGISTERED), denied.keySet());
    }

    /** 登记的能力模板 DENY（sign 或 sign#check）。初值由本测试首次运行得出，逐条在 INTEGRATION.md 说明。 */
    static final Set<String> CAP_DENY_REGISTERED = new LinkedHashSet<String>(Arrays.asList(new String[]{
    }));

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append("  ").append(s).append('\n');
        return sb.toString();
    }
}
