package com.onesay.assistant.v2.pclink;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ToolGate;
import com.onesay.assistant.v2.net.LlmFallback;
import com.onesay.assistant.v2.privacy.Redactor;
import com.onesay.plugin.exec.shizuku.ShizukuWhitelist;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * 2026-11-14 对抗测试修复的配套用例（用户原则：「这类问题弹出确认就行」）。
 * <ul>
 *   <li>写法可疑、守卫认不准的（引号 / 反斜杠拆分、命令名加引号、零宽、特殊空格、全角、同形字、变量 / 命令替换）：手机上不再静默执行，
 *       改成确认卡，卡上写一句为什么问；这种卡<b>不能</b>被「以后这类不用问」记住（记住了所有类型也照样问）。</li>
 *   <li>正常写法照旧直接执行，不多弹卡。</li>
 *   <li>工具名同形字 / 全角：要确认，且不收「始终允许」。</li>
 *   <li>修复不能把正常输入误伤：合法 JSON 数字、合规的备用地址、Shizuku 正常按键、中文标点里的链接、脱敏还原。</li>
 * </ul>
 */
public class AdvOddWritingTest {

    /** 手机执行端桩：记下执行了什么、弹了什么卡（卡上的话、能不能记住）。 */
    static final class Rx implements PcRx.Handler, PcRx.Guard, PcRx.Asker {
        final List<String> ran = new ArrayList<String>();
        final List<String[]> asked = new ArrayList<String[]>();
        int answer = PcConfirm.ALLOW;
        final List<String> kinds = new ArrayList<String>();

        public boolean enabled(String c) { return true; }
        public PcLanClient.Result run(String c, Map<String, Object> a) { ran.add(String.valueOf(a.get("command"))); return new PcLanClient.Result(true, "ran", null, ""); }
        public void record(String c, boolean ok, String t) { }
        public void appFacts(String c, Map<String, Object> a, LinkPolicy.Facts f) {
            if (PcRxPolicy.str(a, "package").startsWith("com.onesay.assistant")) f.appSelf = true;
        }
        public File workspace() { return null; }
        public String script(String c, Map<String, Object> a, String why) { return "scripts/x.sh"; }
        public int ask(String title, String detail, String rememberKind) { asked.add(new String[]{detail, rememberKind}); return answer; }
        public Collection<String> rememberedKinds() { return kinds; }
        public void remember(String kind) { kinds.add(kind); }
        public boolean clipAutoClear() { return false; }
    }

    static PcLanClient.Result exec(Rx rx, String cmd) {
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("command", cmd);
        return PcRx.handle(rx, PcRxPolicy.SYS_EXEC, a);
    }

    static final String[] ODD = {
            "l''s /sdcard", "e\\cho hi", "'ls' /sdcard", "\"ls\" -l", "ls\u200b /sdcard", "ls\u00a0/sdcard", "ls\u3000/sdcard", "ｌｓ /sdcard",
            "l\u0455 /sdcard", "\u0435cho hi", "echo $(id)", "echo `id`", "ls ${HOME}", "p$@m list packages", "ca\"\"t /proc/version",
            "am st'a'rt -a android.intent.action.VIEW", "echo hi; l''s", "ls\ufeff", "ls\u2060-l", "ls\u202e"};

    static final String[] PLAIN = {
            "ls /sdcard", "echo \"hello world\"", "input keyevent 3", "settings put system screen_brightness 100", "cat /proc/version",
            "am start -a android.intent.action.VIEW -d 'https://a.com/x'", "dumpsys battery", "echo 'a b' > /sdcard/x.txt", "ls -la /sdcard/Download"};

    @Test public void oddWritingAsksAndCannotBeRemembered() {
        for (String c : ODD) {
            assertTrue("[" + c + "] 应认成写法可疑", LinkPolicy.oddShellWriting(c).startsWith("命令写法可疑："));
            Rx rx = new Rx();
            rx.answer = PcConfirm.DENY;
            PcLanClient.Result r = exec(rx, c);
            assertFalse("[" + c + "] 拒绝后不能执行", r.ok);
            assertTrue("[" + c + "] 没执行", rx.ran.isEmpty());
            assertEquals("[" + c + "] 弹一张卡", 1, rx.asked.size());
            assertTrue("[" + c + "] 卡上写为什么问：" + rx.asked.get(0)[0], rx.asked.get(0)[0].contains("命令写法可疑"));
            assertEquals("[" + c + "] 这张卡不能记住", "", rx.asked.get(0)[1]);
            assertEquals(LinkPolicy.R_CONFIRM_DENIED, r.data.get("reason"));

            Rx yes = new Rx();
            yes.answer = PcConfirm.REMEMBER; // 就算点了「以后这类不用问」也不记
            assertTrue("[" + c + "] 允许后照做", exec(yes, c).ok);
            assertEquals(1, yes.ran.size());
            assertTrue("[" + c + "] 什么都没记住", yes.kinds.isEmpty());

            Rx all = new Rx();
            all.kinds.addAll(Arrays.asList("phone.blocked_app.open", "phone.blocked_app.uninstall", "phone.system_app", "phone.url.risky"));
            all.answer = PcConfirm.DENY;
            exec(all, c);
            assertEquals("[" + c + "] 记住了所有类型也照样问", 1, all.asked.size());
        }
    }

    @Test public void plainCommandsStillRunWithoutCard() {
        for (String c : PLAIN) {
            assertEquals("[" + c + "]", "", LinkPolicy.oddShellWriting(c));
            Rx rx = new Rx();
            PcLanClient.Result r = exec(rx, c);
            assertTrue("[" + c + "] 照旧直接执行", r.ok);
            assertTrue("[" + c + "] 不弹卡", rx.asked.isEmpty());
        }
    }

    @Test public void normalizedRedLineStillHardRule() {
        String[][] rows = {{"pm g\\rant com.x android.permission.CAMERA", "grant"}, {"\"pm\" \"grant\" com.x android.permission.CAMERA", "grant"},
                {"settings put secure enabled_accessibility_''services com.x/.S", "grant"}, {"pm \"disable-user\" com.onesay.assistant", "self"},
                {"echo hi; p''m uninstall com.onesay.assistant", "self"}};
        for (String[] row : rows) {
            Rx rx = new Rx();
            PcLanClient.Result r = exec(rx, row[0]);
            assertFalse(row[0], r.ok);
            assertTrue(row[0] + " 硬规则不弹卡（确认也绕不过）", rx.asked.isEmpty());
            assertTrue(rx.ran.isEmpty());
            assertEquals(row[0], row[1].equals("grant") ? LinkPolicy.R_SCRIPT_GENERATED : LinkPolicy.R_SELF_GRANT_BLOCKED, r.data.get("reason"));
        }
        assertEquals("pm grant com.x", LinkPolicy.unquoteShell("'p'm \"gr\"a\\nt  com.x"));
    }

    @Test public void decideOddWritingNeverRememberable() {
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        f.oddWriting = "命令写法可疑：引号 / 反斜杠拆分";
        LinkPolicy.Decision ph = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SHELL_EXEC, f);
        assertEquals(LinkPolicy.Verdict.CONFIRM, ph.verdict);
        assertFalse(ph.rememberable());
        assertTrue(ph.text.startsWith("命令写法可疑"));
        LinkPolicy.Decision pc = LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SHELL_EXEC, f);
        assertEquals(LinkPolicy.Verdict.CONFIRM, pc.verdict);
        assertFalse("电脑端也不能记住", pc.rememberable());
        assertSame("记住了也照样问", pc, LinkPolicy.remembered(pc, Arrays.asList("pc.shell_exec")));
        LinkPolicy.Decision full = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.ON_REQUEST),
                LinkPolicy.Cat.SHELL_EXEC, f);
        assertEquals("完全访问本来不问，写法可疑也要问", LinkPolicy.Verdict.CONFIRM, full.verdict);
        LinkPolicy.Decision never = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.NEVER),
                LinkPolicy.Cat.SHELL_EXEC, f);
        assertEquals("从不询问：问不了就拒", LinkPolicy.Verdict.DENY, never.verdict);
        // 硬规则不被确认卡替代
        assertEquals(LinkPolicy.Verdict.SCRIPT, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SELF_GRANT, f).verdict);
        assertEquals(LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.PAY_FINAL, f).verdict);
    }

    @Test public void encodedIpAsksPlainIpAllowed() {
        String[] enc = {"http://2130706433/", "http://0x7f.0.0.1/", "http://0177.0.0.1/", "http://127.1/", "http://192.168.001.001/"};
        for (String u : enc) {
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.url = LinkPolicy.checkUrl(u, null);
            LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f);
            assertEquals(u, LinkPolicy.Verdict.CONFIRM, d.verdict);
            assertFalse(u, d.rememberable());
            assertTrue(u + " " + d.text, d.text.contains("编码过的 IP"));
        }
        for (String u : new String[]{"http://192.168.1.1/", "http://10.0.0.1:8080/x", "http://[::1]/", "http://nas.local/"}) {
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.url = LinkPolicy.checkUrl(u, null);
            assertEquals(u, LinkPolicy.Verdict.ALLOW, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f).verdict);
        }
        assertTrue(LinkPolicy.checkUrl("https://a.com:65535/", null).ok);
        assertTrue(LinkPolicy.checkUrl("https://a.com:0443/", null).ok);
        assertTrue("路径里的不换行空格照旧交给浏览器", LinkPolicy.checkUrl("https://a.com/\u00a0x", null).ok);
    }

    @Test public void finalPayLabelNoFalsePositive() {
        for (String s : new String[]{"支付宝", "查看订单", "提交", "下一步", "Pay later options", "确认收货", "PayPal", "取消支付"})
            assertFalse(s, LinkPolicy.finalPayLabel(s));
        for (String s : new String[]{"确认支付", "立即付款", "Pay Now", "Place Order", "確認付款 ¥12.50", "支付 ¥30"})
            assertTrue(s, LinkPolicy.finalPayLabel(s));
    }

    @Test public void toolSignOddWriting() throws Exception {
        for (String sign : new String[]{"conn.x.l\u0456st_items", "conn.x.ｌｉｓｔ_items", "conn.x.list\u200b_items", "conn.x.list items"}) {
            Capability cap = new Capability.Builder(sign, "adv").domain("conn").risk(0, false).sideEffect(SideEffect.NONE).cmd("echo").build();
            for (boolean t : new boolean[]{false, true}) {
                ToolGate.GateResult g = ToolGate.check(cap, new JSONObject(), t);
                assertEquals(sign + " t=" + t, ToolGate.Decision.REQUIRE_CONFIRM, g.decision);
                assertTrue(g.reason, g.reason.contains("工具名写法可疑"));
            }
            assertFalse(sign + " 不收「始终允许」", ConsentPolicy.acceptsAlwaysAllow(cap));
        }
        Capability ok = new Capability.Builder("conn.x.list_items", "adv").domain("conn").risk(0, false).sideEffect(SideEffect.NONE).cmd("echo").build();
        assertEquals(ToolGate.Decision.ALLOW, ToolGate.check(ok, new JSONObject(), false).decision);
        Capability reply = new Capability.Builder("ask.reply", "adv").risk(0, false).sideEffect(SideEffect.NONE).cmd("echo").build();
        assertEquals("纯回答不算外发", ToolGate.Decision.ALLOW, ToolGate.check(reply, new JSONObject(), true).decision);
        Capability alarm = new Capability.Builder("clock.alarm.set", "adv").risk(0, false).sideEffect(SideEffect.DEVICE_CONTROL).cmd("echo").build();
        assertEquals("内置闹钟（risk 0）污点下照旧放行", ToolGate.Decision.ALLOW, ToolGate.check(alarm, new JSONObject(), true).decision);
    }

    @Test public void backupUrlCheck() {
        assertNull(LlmFallback.checkUrl("https://api.example.com/v1/chat/completions", false));
        assertNull(LlmFallback.checkUrl("  https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions ", false));
        assertNull(LlmFallback.checkUrl("http://127.0.0.1:8080/v1/chat/completions", true));
        assertNull(LlmFallback.checkUrl("http://localhost:11434/v1/chat/completions", true));
        String[] bad = {"http://127.0.0.1:8080/v1/chat/completions|false", "http://127.0.0.1.evil.example/v1|true", "http://localhost.evil.example/v1|true",
                "http://127.0.0.2/v1|true", "http://evil.example/v1|true", "https://user@api.example.com/v1|true", "https://api.example.com/v1?x|true",
                "https://api.example.com/v1#|true", "https://api.example.com/v1\u00a0|true", "https://api.exa\u200bmple.com/v1|true", "ftp://a.com/v1|true",
                "https:///v1|true", "|true"};
        for (String b : bad) {
            String[] p = b.split("\\|");
            assertNotNull(b, LlmFallback.checkUrl(p.length > 0 ? p[0] : "", Boolean.parseBoolean(p[p.length - 1])));
        }
    }

    @Test public void shizukuLeadingZeroNormalized() {
        ShizukuWhitelist w = new ShizukuWhitelist("com.onesay.assistant");
        ShizukuWhitelist.Verdict v = w.check("input keyevent 03");
        assertTrue(v.allowed);
        assertEquals("执行的是规范化后的写法", Arrays.asList("input", "keyevent", "3"), v.argv);
        assertTrue(w.check("input keyevent --longpress 024").allowed);
        for (String c : new String[]{"input keyevent 0026", "input keyevent 00026", "input keyevent 223", "input keyevent 0223", "input keyevent KEYCODE_POWER"})
            assertFalse(c, w.check(c).allowed);
    }

    @Test public void pcJsonStillAcceptsValid() {
        assertEquals(0L, PcJson.parse("0"));
        assertEquals(-1L, PcJson.parse("-1"));
        assertEquals(1500.0, (Double) PcJson.parse("1.5e3"), 0);
        assertEquals(100.0, (Double) PcJson.parse("1E+2"), 0);
        assertEquals("A", PcJson.parse("\"\\u0041\""));
        assertEquals("\u00e9", PcJson.parse("\"\\u00E9\""));
        for (String bad : new String[]{"1.", ".5", "1e", "-", "00", "0x10", "\"\\u004\"", "\"\\u００41\"", "-1e400", "NaN"}) {
            try {
                Object v = PcJson.parse(bad);
                fail(bad + " → " + v);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test public void mediaTidyChinesePunct() {
        String[][] rows = {{"看这个：https://a.com/v。", "https://a.com/v"}, {"https://a.com/v，好看", "https://a.com/v"}, {"【https://a.com/v】", "https://a.com/v"},
                {"「https://a.com/v」", "https://a.com/v"}, {"https://a.com/v?t=10&", "https://a.com/v?t=10"}, {"https://a.com/v\u00a0更多", "https://a.com/v"},
                {"https://a.com/v…", "https://a.com/v"}, {"https://a.com/(x)", "https://a.com/(x)"}};
        for (String[] r : rows) assertEquals(r[0], r[1], PcMedia.tidy(r[0]));
    }

    @Test public void redactorSameValueSamePlaceholder() {
        Redactor r = new Redactor();
        String in = "电话 13800138000，又写成 １３８００１３８０００ 和 138\u200b0013\u200b8000";
        String out = r.redact(in);
        assertEquals("同一个号码不同写法共用一个占位符", 1, r.size());
        assertFalse(out, out.contains("8000"));
        // 和原来的「同一值不同写法（空格 / 短横 / +86）共用占位符」一样：还原成第一次见到的写法
        assertEquals("电话 13800138000，又写成 13800138000 和 13800138000", r.restore(out));
        Redactor r2 = new Redactor();
        String plain = "订单号 ２０２５ 不是号码";
        assertEquals(plain, r2.redact(plain));
    }
}
