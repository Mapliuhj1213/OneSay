package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * D13（2026-10-22）：选项注入（以 {@code -} 开头的值）补测。R15-2 只测了「逃出参数变成命令」，这里测
 * 「值留在参数里，却被目标命令当成选项」—— 例如 {@code settings put system screen_brightness --user 0}
 * 在 Android 8 上会把 {@code --user} 当成选项（SettingsService 每个位置都先判 {@code "--user".equals(arg)}）。
 *
 * <h3>逐模板核对的结论（AOSP oreo-mr1-release 源码）</h3>
 * <ul>
 *   <li><b>位置参数</b>只有 2 条，都是 {@code settings put}：SettingsService 不认 {@code --}（会被当成键或值存进去），
 *       所以不能加 {@code --}，改为<b>拒绝</b>以 {@code -} 开头的值；</li>
 *   <li><b>选项参数</b>（am 的 -a/-d/-n/-p/--es/--ei/--el/--ez，media volume 的 --set）：目标命令用 getNextArgRequired()
 *       原样取下一个参数，值以 {@code -} 开头也不会被当成选项 → 不改；</li>
 *   <li><b>嵌在单词里</b>（https://…?q=、tel:、smsto:、package:、market://、geo:、amapuri://、content://）：
 *       值前面有固定前缀，到不了参数开头 → 不改。</li>
 * </ul>
 * am / pm / cmd 这类基于 ShellCommand 的工具支持 {@code --}（getNextOption 遇到 "--" 结束选项），但表里没有它们的位置参数槽位。
 */
public class OptionInjectionTest {

    // ---------------------------------------------------------------- 模板清单

    /** 能力表（内置 + 插件本地能力 + 挖掘出的 PROVIDER 形态）里所有带占位符的命令模板：sign → 模板。 */
    static Map<String, String> templates() {
        Map<String, String> m = new TreeMap<String, String>();
        for (Capability c : CapabilityRegistry.getInstance().listCapabilities()) add(m, c);
        add(m, com.onesay.assistant.v2.s1.S1Capabilities.taxi());
        add(m, com.onesay.assistant.v2.s2.S2Capabilities.get(com.onesay.assistant.v2.s2.S2Capabilities.SIGN_NAV_APP));
        for (Capability c : com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.all()) add(m, c);
        m.put("mined.provider", "content query --uri content://com.example.provider/{path}");
        return m;
    }

    private static final Pattern PH = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)(?:[=:][^}]*)?\\}");

    private static void add(Map<String, String> m, Capability c) {
        if (c == null) return;
        if (c.cmdTemplate != null && PH.matcher(c.cmdTemplate).find()) m.put(c.sign, c.cmdTemplate);
        if (c.checkCommand != null && PH.matcher(c.checkCommand).find()) m.put(c.sign + "#check", c.checkCommand);
    }

    private static Set<String> names(String tpl) {
        Set<String> s = new LinkedHashSet<String>();
        Matcher mm = PH.matcher(tpl);
        while (mm.find()) s.add(mm.group(1));
        return s;
    }

    /** 逐条核对的结果：sign#槽位 → 种类。新增带占位符的模板必须在这里登记（下面的测试会提醒）。 */
    static final Map<String, ShellCommand.SlotKind> EXPECTED = new LinkedHashMap<String, ShellCommand.SlotKind>();
    static {
        ShellCommand.SlotKind P = ShellCommand.SlotKind.POSITIONAL, O = ShellCommand.SlotKind.OPTION_ARG, E = ShellCommand.SlotKind.EMBEDDED;
        EXPECTED.put("settings.brightness.set#level", P);       // settings put system screen_brightness {level}
        EXPECTED.put("settings.screentimeout.set#ms", P);       // settings put system screen_off_timeout {ms}
        EXPECTED.put("volume.media.set#level", O);              // media volume --stream 3 --set {level}
        EXPECTED.put("volume.ring.set#level", O);
        EXPECTED.put("volume.alarm.set#level", O);
        EXPECTED.put("volume.notif.set#level", O);
        EXPECTED.put("app.launch#component", O);                // am start -n {component}
        EXPECTED.put("browser.open#url", O);                    // -d {url}
        EXPECTED.put("browser.search#keyword", E);              // -d https://…?wd={keyword}
        EXPECTED.put("browser.search.bing#keyword", E);
        EXPECTED.put("browser.search.google#keyword", E);
        EXPECTED.put("browser.search.sogou#keyword", E);
        EXPECTED.put("notes.new#content", O);                   // --es android.intent.extra.TEXT '{content}'
        EXPECTED.put("notes.new.text#content", O);
        EXPECTED.put("app.uninstall#pkg", E);                   // -d package:{pkg}
        EXPECTED.put("clock.alarm.set#hour", O);                // --ei …HOUR {hour}
        EXPECTED.put("clock.alarm.set#minute", O);
        EXPECTED.put("clock.timer.set#seconds", O);
        EXPECTED.put("phone.call#number", E);                   // -d tel:{number}
        EXPECTED.put("sms.send#number", E);                     // -d smsto:{number}
        EXPECTED.put("sms.send#text", O);                       // --es sms_body '{text}'
        EXPECTED.put("store.search.market#keyword", E);        // -d 'market://…'
        EXPECTED.put("store.detail.market#pkg", E);
        EXPECTED.put("store.search.play#keyword", E);
        EXPECTED.put("s1.taxi.to#address", E);                  // amapuri://…&dname={address}
        EXPECTED.put("s2.nav.app#address", E);                  // geo:0,0?q={address}
        EXPECTED.put("s2.nav.app#pkg", O);                      // -p {pkg}
        EXPECTED.put("screen.calendar#title", O);               // --es title '{title}'
        EXPECTED.put("screen.calendar#begin_ms", O);            // --el beginTime {begin_ms:i}
        EXPECTED.put("screen.calendar#end_ms", O);
        EXPECTED.put("screen.calendar#all_day", O);             // --ez allDay {all_day}
        EXPECTED.put("screen.nav#address", E);
        EXPECTED.put("mined.provider#path", E);                 // content://auth/{path}
    }

    /** 插件能力的签名在不同版本里可能叫法不同：按模板内容把它们映射到 EXPECTED 里的短名。 */
    static String alias(String sign, String tpl) {
        if (tpl.contains("--el beginTime")) return "screen.calendar";
        if (tpl.contains("amapuri://drive/takeTaxi")) return "s1.taxi.to";
        if (tpl.contains("geo:0,0?q={address}") && tpl.contains("-p {pkg}")) return "s2.nav.app";
        if (tpl.contains("geo:0,0?q={address}")) return "screen.nav";
        return sign;
    }

    @Test public void everyPlaceholderSlotIsClassifiedAsReviewed() {
        Map<String, ShellCommand.SlotKind> actual = new LinkedHashMap<String, ShellCommand.SlotKind>();
        for (Map.Entry<String, String> e : templates().entrySet()) {
            String key = alias(e.getKey(), e.getValue());
            for (ShellCommand.Slot s : ShellCommand.slots(e.getValue())) {
                String k = key + "#" + s.name;
                ShellCommand.SlotKind prev = actual.put(k, s.kind);
                assertTrue("同一模板里同名槽位种类必须一致: " + k, prev == null || prev == s.kind);
            }
        }
        assertEquals("逐模板核对表（新增带占位符的模板请先核对目标命令再登记）",
                new TreeMap<String, ShellCommand.SlotKind>(EXPECTED), new TreeMap<String, ShellCommand.SlotKind>(actual));
    }

    // ---------------------------------------------------------------- 分类器本身

    @Test public void classifierRules() {
        assertKinds("settings put system k {v}", "v", ShellCommand.SlotKind.POSITIONAL);
        assertKinds("am start -d {u}", "u", ShellCommand.SlotKind.OPTION_ARG);
        assertKinds("am start --es key '{t}'", "t", ShellCommand.SlotKind.OPTION_ARG);
        assertKinds("am start --ez k {b}", "b", ShellCommand.SlotKind.OPTION_ARG);
        assertKinds("am start --activity-clear-top {u}", "u", ShellCommand.SlotKind.POSITIONAL);   // 无参开关后面是位置参数
        assertKinds("am start -W {u}", "u", ShellCommand.SlotKind.POSITIONAL);
        assertKinds("am start -d tel:{n}", "n", ShellCommand.SlotKind.EMBEDDED);
        assertKinds("am start -d 'market://search?q={k}'", "k", ShellCommand.SlotKind.EMBEDDED);
        assertKinds("echo x; settings put system k {v}", "v", ShellCommand.SlotKind.POSITIONAL);
        assertKinds("media volume --stream 3 --set {v}", "v", ShellCommand.SlotKind.OPTION_ARG);
        assertKinds("input text {t}", "t", ShellCommand.SlotKind.POSITIONAL);
    }

    private static void assertKinds(String tpl, String name, ShellCommand.SlotKind k) {
        List<ShellCommand.Slot> s = ShellCommand.slots(tpl);
        assertEquals(tpl, 1, s.size());
        assertEquals(tpl, name, s.get(0).name);
        assertEquals(tpl, k, s.get(0).kind);
    }

    // ---------------------------------------------------------------- 拒绝 / 放行

    static Map<String, String> v(String k, String val) {
        Map<String, String> m = new HashMap<String, String>();
        m.put(k, val);
        return m;
    }

    @Test public void positionalSlotRejectsLeadingDash() {
        for (String bad : new String[] { "--user", "-5", "--", "-n", " --user", "\n--user" }) {
            try {
                String out = ShellCommand.fill("settings put system screen_brightness {level}", v("level", bad));
                fail("位置参数放进了以 - 开头的值: [" + bad + "] → " + out);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("level"));
            }
        }
        assertEquals("settings put system screen_brightness 120",
                ShellCommand.fill("settings put system screen_brightness {level}", v("level", "120")));
        assertEquals("中间带 - 不受影响", "settings put system k 'a -b'", ShellCommand.fill("settings put system k {v}", v("v", "a -b")));
    }

    @Test public void optionArgAndEmbeddedSlotsKeepWorking() {
        assertEquals("am start -a android.intent.action.VIEW -d -x",
                ShellCommand.fill("am start -a android.intent.action.VIEW -d {url}", v("url", "-x")));
        assertEquals("am start -d tel:-123", ShellCommand.fill("am start -d tel:{number}", v("number", "-123")));
        assertEquals("--es t '--user'", ShellCommand.fill("--es t '{c}'", v("c", "--user")));
    }

    @Test public void plainFillIsNotAShellCommandAndIsUntouched() {
        assertEquals("-5", ShellCommand.fillPlain("{x}", v("x", "-5")));
    }

    @Test public void executorTurnsRejectionIntoSchemaInvalid() throws Exception {
        String src = new String(Files.readAllBytes(new File(root(), "app/src/com/onesay/assistant/v2/executor/UnifiedExecutor.java").toPath()),
                StandardCharsets.UTF_8);
        int at = src.indexOf("filledCmd = ShellCommand.fill(");
        assertTrue(at > 0);
        String after = src.substring(at, Math.min(src.length(), at + 600));
        assertTrue("IllegalArgumentException → SCHEMA_INVALID", after.contains("catch (IllegalArgumentException e)")
                && after.contains("ErrorCode.SCHEMA_INVALID"));
    }

    static File root() {
        File f = new File(System.getProperty("user.dir"));
        while (f != null && !new File(f, "app/src").isDirectory()) f = f.getParentFile();
        return f;
    }

    // ---------------------------------------------------------------- 整表 × 选项载荷（真实 sh 切词 + 按 AOSP 解析规则模拟）

    static final String[] OPTION_PAYLOADS = { "--user", "-n", "--es", "-d", "--", "-5", "--activity-clear-top", "-W", "--selector",
            "-e x", "--user 0" };

    /** am（Intent.parseCommandArgs + am start 自己的选项）里带 1 个 / 2 个参数的选项；其余 - 开头的都是无参开关。 */
    static final Set<String> AM_ONE = new HashSet<String>(Arrays.asList("-a", "-d", "-t", "-c", "-n", "-p", "-f", "--esn",
            "-P", "--start-profiler", "--sampling", "-R", "--user", "--receiver-permission", "--stack", "--display",
            "--windowingMode", "--activityType", "--task"));
    static final Set<String> AM_TWO = new HashSet<String>(Arrays.asList("-e", "--es", "--ei", "--el", "--ef", "--eu", "--ecn",
            "--eia", "--eial", "--ela", "--elal", "--efa", "--efal", "--esa", "--esal", "--ez"));
    /** media volume（VolumeCtrl）：--stream / --set / --adj 带参数。 */
    static final Set<String> MEDIA_ONE = new HashSet<String>(Arrays.asList("--stream", "--set", "--adj"));
    /** content query：--uri / --projection / --where / --sort / --user 带参数。 */
    static final Set<String> CONTENT_ONE = new HashSet<String>(Arrays.asList("--uri", "--projection", "--where", "--sort", "--user"));

    /** 用真实 sh 把命令切成 argv：am/settings/media/content 换成把参数逐行写到文件的函数。 */
    static List<List<String>> argvs(String cmd) throws Exception {
        File out = File.createTempFile("argv", ".txt");
        out.delete();
        String fn = "rec() { n=$1; shift; { printf '%s\\n' \"@@$n\"; for a in \"$@\"; do printf '%s\\n' \"$a\"; done; } >> '" + out.getPath() + "'; }\n"
                + "am() { rec am \"$@\"; }\nsettings() { rec settings \"$@\"; }\nmedia() { rec media \"$@\"; }\n"
                + "content() { rec content \"$@\"; }\ngrep() { return 1; }\nPATH=\n";
        Process p = new ProcessBuilder("/bin/sh", "-c", fn + cmd + "\n").redirectErrorStream(true).start();
        java.io.InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        while (in.read(buf) > 0) { /* 丢弃 */ }
        p.waitFor();
        List<List<String>> all = new ArrayList<List<String>>();
        if (!out.exists()) return all;
        List<String> cur = null;
        for (String line : Files.readAllLines(out.toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("@@")) {
                cur = new ArrayList<String>();
                cur.add(line.substring(2));
                all.add(cur);
            } else if (cur != null) {
                cur.add(line);
            }
        }
        out.delete();
        return all;
    }

    /**
     * 按 AOSP 的选项解析规则模拟：返回被当成「选项名」的那些参数下标。
     * am / media / content 走 ShellCommand.getNextOption 语义（"-" 开头就是选项，"--" 结束选项）；
     * settings（SettingsService）只认任意位置上恰好等于 "--user" 的参数。
     */
    static Set<Integer> optionIndexes(List<String> argv) {
        Set<Integer> idx = new HashSet<Integer>();
        String tool = argv.get(0);
        if ("settings".equals(tool)) {
            for (int i = 1; i < argv.size(); i++) {
                if ("--user".equals(argv.get(i))) { idx.add(i); i++; }
            }
            return idx;
        }
        Set<String> one = "am".equals(tool) ? AM_ONE : "media".equals(tool) ? MEDIA_ONE : CONTENT_ONE;
        Set<String> two = "am".equals(tool) ? AM_TWO : new HashSet<String>();
        int i = 2;   // argv[1] 是子命令（start / volume / query）
        while (i < argv.size()) {
            String a = argv.get(i);
            if (!a.startsWith("-")) break;   // 第一个非选项 = 位置参数开始
            idx.add(i);
            i++;
            if (a.equals("--")) break;
            if (one.contains(a)) i += 1;
            else if (two.contains(a)) i += 2;
        }
        return idx;
    }

    @Test public void wholeTableTimesOptionPayloadsNeverBecomesAnOption() throws Exception {
        int filled = 0, rejected = 0;
        List<String> bad = new ArrayList<String>();
        for (Map.Entry<String, String> e : templates().entrySet()) {
            String tpl = e.getValue();
            Set<String> ns = names(tpl);
            Map<String, String> safe = new HashMap<String, String>();
            for (String n : ns) safe.put(n, "SAFEVAL");
            String baseCmd;
            try {
                baseCmd = ShellCommand.fill(tpl, safe);
            } catch (IllegalArgumentException ex) {
                // 整数槽位（:i）不接受 SAFEVAL：用 7 做基线
                for (String n : ns) safe.put(n, "7");
                baseCmd = ShellCommand.fill(tpl, safe);
            }
            List<List<String>> base = argvs(baseCmd);
            for (String payload : OPTION_PAYLOADS) {
                for (String target : ns) {
                    Map<String, String> vals = new HashMap<String, String>(safe);
                    vals.put(target, payload);
                    String cmd;
                    try {
                        cmd = ShellCommand.fill(tpl, vals);
                    } catch (IllegalArgumentException ex) {
                        rejected++;
                        continue;
                    }
                    filled++;
                    List<List<String>> got = argvs(cmd);
                    if (got.size() != base.size()) {
                        bad.add(e.getKey() + "#" + target + " [" + payload + "] 调用次数变了");
                        continue;
                    }
                    for (int c = 0; c < got.size(); c++) {
                        List<String> g = got.get(c), b = base.get(c);
                        if (g.size() != b.size()) {
                            bad.add(e.getKey() + "#" + target + " [" + payload + "] 参数个数变了: " + g);
                            continue;
                        }
                        Set<Integer> before = optionIndexes(b);
                        Set<Integer> after = optionIndexes(g);
                        if (!before.equals(after)) {
                            bad.add(e.getKey() + "#" + target + " [" + payload + "] 被当成选项: " + g);
                        }
                    }
                }
            }
        }
        assertTrue("选项注入:\n" + join(bad), bad.isEmpty());
        assertTrue("真的填了不少组合: " + filled, filled > 100);
        assertTrue("位置参数确实拒绝过: " + rejected, rejected >= 2 * OPTION_PAYLOADS.length);
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append('\n');
        return sb.toString();
    }
}
