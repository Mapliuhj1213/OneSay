package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.fastscreen.FsTree;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** ★ mika-plug：PhoneAgent × Mika 执行层插件（MikaExec）的接线：读屏 / 步骤写法 / 效果 / 守卫 / 风险 / 节奏。 */
public class MikaPhoneTest {

    static MikaExec.Flags all() {
        MikaExec.Flags f = new MikaExec.Flags();
        f.screen = f.refs = f.changewait = f.actchain = f.effect = f.guard = f.actgate = f.pacing = f.dosteps = true;
        return f;
    }

    /** 假手机的 Mika 版：读屏走 MikaExec.build（真实的 ScreenReader + RefStabilizer），awaitChange = 读一次。 */
    static final class MikaPhone implements PhoneAgent.MikaDriver {
        final FakeScreens.Phone p;
        final MikaExec m;
        int awaits, xyTaps;

        MikaPhone(FakeScreens.Phone p, MikaExec m) {
            this.p = p;
            this.m = m;
        }

        @Override public UiTree observe(UiTree.Ids ids) {
            p.observe(ids);   // 推进转场
            return m.build(p.screens.get(p.cur).t, ids, 1080, 2220);
        }

        @Override public boolean tapXY(int x, int y) {
            xyTaps++;
            return true;
        }

        @Override public UiTree awaitChange(UiTree before, UiTree.Ids ids) {
            awaits++;
            return observe(ids);
        }

        @Override public boolean tap(UiTree.El e) {
            return p.tap(e);
        }

        @Override public boolean longPress(UiTree.El e, int ms) {
            return p.longPress(e, ms);
        }

        @Override public String type(UiTree.El e, String t) {
            return p.type(e, t);
        }

        @Override public boolean scroll(UiTree.El e, int d) {
            return p.scroll(e, d);
        }

        @Override public boolean swipe(int a, int b, int c, int d, int ms) {
            return true;
        }

        @Override public boolean key(String k) {
            return p.key(k);
        }

        @Override public boolean launch(String pkg) {
            return p.launch(pkg);
        }

        @Override public void waitIdle(int i, int t) {
            p.waitIdle(i, t);
        }

        @Override public String describeScreen() {
            return null;
        }

        @Override public long now() {
            return p.now();
        }

        @Override public void sleep(long ms) {
            p.sleep(ms);
        }
    }

    /** 模型读 JSON 里的 ref：把 {ref:名字} 换成当前提示词里该名字的 @eN。 */
    static final class RefScript implements PhoneAgent.Planner {
        final String[] outs;
        int i;
        String lastUser, lastSystem;

        RefScript(String... o) {
            outs = o;
        }

        @Override public String next(String system, String user) {
            lastUser = user;
            lastSystem = system;
            String o = i < outs.length ? outs[i++] : "{\"done\":\"脚本用完\"}";
            Matcher m = Pattern.compile("\\{ref:([^}]+)\\}").matcher(o);
            StringBuffer b = new StringBuffer();
            while (m.find()) m.appendReplacement(b, refOf(user, m.group(1)));
            m.appendTail(b);
            return b.toString();
        }
    }

    /** 从提示词里的屏幕 JSON 找名字对应的 ref。 */
    static String refOf(String user, String name) {
        for (String line : user.split("\n")) {
            if (!line.startsWith("{") || !line.contains("\"elements\"")) continue;
            try {
                JSONArray els = new JSONObject(line).getJSONArray("elements");
                for (int i = 0; i < els.length(); i++) if (name.equals(els.getJSONObject(i).optString("name"))) return els.getJSONObject(i).getString("ref");
            } catch (Exception ignored) {
            }
        }
        return "@e999";
    }

    static FakeScreens.Phone settings() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("home", FakeScreens.screen("com.android.settings", ".Settings").text("设置").button("WLAN").button("蓝牙").button("显示").button("声音").button("关于手机").button("电池"));
        p.add("wlan", FakeScreens.screen("com.android.settings", ".Wifi").text("WLAN").toggle("WLAN 开关", true).button("家里的网").button("公司网络").button("添加网络").button("高级"));
        p.on("home", "WLAN", "wlan");
        return p;
    }

    @Test public void buildUsesMikaElements() throws Exception {
        MikaExec m = new MikaExec(all());
        UiTree.Ids ids = new UiTree.Ids();
        FakeScreens.B b = FakeScreens.screen("com.x", ".A").text("标题").button("确定", "com.x:id/ok_btn").edit("搜索", "com.x:id/search_src_text");
        UiTree t = m.build(b.t, ids, 1080, 2220);
        assertNotNull(t.mika);
        assertTrue(t.mikaStable);
        JSONObject j = new JSONObject(t.mikaJson);
        JSONArray els = j.getJSONArray("elements");
        assertEquals("@e1", els.getJSONObject(0).getString("ref"));
        assertEquals(1, t.els.get(0).id);
        UiTree.El ok = t.findText("确定");
        assertEquals("ok_btn", ok.rid);
        assertTrue(ok.src > 0);
        assertEquals("Button", ok.cls);
        assertTrue(t.render(120).contains("\"ref\":\"@e"));
        UiTree t2 = m.build(b.t, ids, 1080, 2220);
        assertEquals("同屏再读编号不变", ok.id, t2.findText("确定").id);
        assertNotNull(ids.mikaRefs);
    }

    @Test public void windowsFromHelperTree() {
        FsTree t = new FsTree();
        t.pkg = "com.x";
        FsTree.Win w0 = new FsTree.Win();
        w0.id = 7;
        w0.type = "system";
        w0.layer = 9;
        w0.pkg = "com.android.packageinstaller";
        FsTree.Win w1 = new FsTree.Win();
        w1.id = 3;
        w1.layer = 1;
        w1.pkg = "com.x";
        t.windows.add(w0);
        t.windows.add(w1);
        FsTree.Node a = new FsTree.Node();
        a.cls = "android.widget.Button";
        a.text = "允许";
        a.r = 500;
        a.b = 200;
        a.flags = FakeScreens.C;
        a.win = 0;
        FsTree.Node b = new FsTree.Node();
        b.cls = "android.widget.TextView";
        b.text = "App";
        b.r = 500;
        b.b = 200;
        b.flags = FsTree.F_VISIBLE | FsTree.F_ENABLED;
        b.win = 1;
        t.nodes.add(a);
        t.nodes.add(b);
        java.util.List<com.onesay.assistant.plug.screen.core.UiWindow> ws = MikaExec.windows(t);
        assertEquals(2, ws.size());
        assertEquals("system", ws.get(0).type);
        assertEquals(9, ws.get(0).layer);
        UiTree u = new MikaExec(all()).build(t, new UiTree.Ids(), 1080, 2220);
        assertEquals("允许", u.els.get(0).label);
        assertEquals("com.x", u.pkg);
        // 旧助手（没有窗口表）：一个根 = 一个 application 窗口
        FsTree old = new FsTree();
        old.pkg = "com.y";
        old.nodes.add(a);
        assertEquals(1, MikaExec.windows(old).size());
    }

    @Test public void planParsesMikaShapes() {
        PhonePlan p = PhonePlan.parse("{\"steps\":[{\"do\":\"tap\",\"ref\":\"@e12\",\"expect\":\"WLAN\"}]}", true);
        assertEquals(12, p.steps.get(0).id);
        assertEquals(-1, PhonePlan.parse("{\"steps\":[{\"do\":\"tap\",\"ref\":\"@e12\"}]}", false).steps.get(0).id);
        PhonePlan d = PhonePlan.parse("{\"do_steps\":{\"steps\":[{\"tool\":\"phone_tap\",\"args\":{\"ref\":\"@e3\"},\"expect\":\"好\"},"
                + "{\"tool\":\"type_text\",\"args\":{\"ref\":\"@e4\",\"text\":\"天气\",\"pressEnter\":true}},{\"tool\":\"scroll\",\"args\":{\"direction\":\"up\"}},"
                + "{\"tool\":\"phone_launch_app\",\"args\":{\"package\":\"com.x\"}}]}}", true);
        assertEquals(PhonePlan.Kind.STEPS, d.kind);
        assertEquals(4, d.steps.size());
        assertEquals("tap", d.steps.get(0).op);
        assertEquals("好", d.steps.get(0).expect);
        assertEquals("type", d.steps.get(1).op);
        assertTrue(d.steps.get(1).enter);
        assertEquals(-1, d.steps.get(2).dir);
        assertEquals("com.x", d.steps.get(3).app);
        PhonePlan one = PhonePlan.parse("{\"tool\":\"tap\",\"args\":{\"x\":500,\"y\":120,\"target\":\"右上角关闭\"}}", true);
        assertEquals(500, one.steps.get(0).x);
        assertNull(PhonePlan.validate(one.steps.get(0), null));
        PhonePlan noTarget = PhonePlan.parse("{\"steps\":[{\"do\":\"tap\",\"x\":5,\"y\":5}]}", true);
        assertNotNull(PhonePlan.validate(noTarget.steps.get(0), null));
        PhonePlan nd = PhonePlan.parse("{\"end_not_done\":\"找不到这个功能\"}", true);
        assertEquals(PhonePlan.Kind.NOT_DONE, nd.kind);
        assertEquals("找不到这个功能", nd.answer);
        assertEquals(PhonePlan.Kind.INVALID, PhonePlan.parse("{\"end_not_done\":\"x\"}", false).kind);
    }

    @Test public void agentRunsWithMikaLayer() {
        FakeScreens.Phone p = settings();
        MikaExec m = new MikaExec(all());
        MikaPhone d = new MikaPhone(p, m);
        RefScript s = new RefScript("{\"steps\":[{\"do\":\"tap\",\"ref\":\"{ref:WLAN}\"}]}", "{\"done\":\"已打开 WLAN 页\"}");
        PhoneAgent a = new PhoneAgent(d, s, new FakeScreens.Host());
        a.mika = m;
        PhoneAgent.Result r = a.run("打开 WLAN", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals("wlan", p.cur);
        assertTrue(s.lastSystem.contains("0–999"));
        assertTrue(s.lastUser, s.lastUser.contains("effect=done") || s.lastUser.contains("\"full\""));
        assertEquals("没带条件的一步走 ChangeWait", 1, d.awaits);
    }

    @Test public void guardStopsAfterNoEffectLadder() {
        FakeScreens.Phone p = settings();
        MikaExec m = new MikaExec(all());
        MikaPhone d = new MikaPhone(p, m);
        String[] outs = new String[14];
        for (int i = 0; i < outs.length; i++) outs[i] = "{\"steps\":[{\"do\":\"tap\",\"ref\":\"{ref:显示}\"}]}";
        RefScript s = new RefScript(outs);
        PhoneAgent a = new PhoneAgent(d, s, new FakeScreens.Host());
        a.mika = m;
        PhoneAgent.Result r = a.run("一直点显示", null);
        assertEquals(PhoneAgent.Status.FAILED, r.status);
        assertTrue(r.reason, r.reason.contains("已安全停止") || r.reason.contains("卡住") || r.reason.contains("无进展") || r.reason.contains("重复"));
        assertTrue(m.effectsNoEffect >= 2);
    }

    @Test public void riskGateAsksForApplyAndPayment() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("job", FakeScreens.screen("com.boss", ".Job").text("岗位详情").text("月薪 ¥ 30000").button("立即申请"));
        MikaExec m = new MikaExec(all());
        FakeScreens.Host h = new FakeScreens.Host();
        h.confirmAnswer = false;
        PhoneAgent a = new PhoneAgent(new MikaPhone(p, m), new RefScript("{\"steps\":[{\"do\":\"tap\",\"ref\":\"{ref:立即申请}\"}]}", "{\"done\":\"没申请\"}"), h);
        a.mika = m;
        a.run("申请这个岗位", null);
        assertEquals(1, h.confirms.size());
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("申请") || h.confirms.get(0).contains("提交"));
        assertTrue(h.confirms.get(0).contains("金额") || h.confirms.get(0).contains("花钱"));
        assertFalse("拒绝了就没点", p.log.contains("tap 立即申请"));
    }

    @Test public void pacingSpacesOutwardActions() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("chat", FakeScreens.screen("com.pacing.test" + System.nanoTime(), ".Chat").text("聊天").button("发送"));
        MikaExec m = new MikaExec(all());
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent a = new PhoneAgent(new MikaPhone(p, m), new RefScript("{\"steps\":[{\"do\":\"tap\",\"ref\":\"{ref:发送}\"},{\"do\":\"tap\",\"ref\":\"{ref:发送}\"}]}", "{\"done\":\"发了两条\"}"), h);
        a.mika = m;
        long t0 = p.now();
        PhoneAgent.Result r = a.run("连发两条", null);
        boolean paced = false;
        for (String t : r.trace) if (t.startsWith("pacing ")) paced = true;
        assertTrue(r.trace.toString(), paced);
        assertTrue(p.now() - t0 >= 7000);
    }

    @Test public void flagsOffMeansNoAddendum() {
        assertEquals("", new MikaExec(new MikaExec.Flags()).promptAddendum());
        assertFalse(new MikaExec.Flags().any());
        assertEquals("", new MikaExec(new MikaExec.Flags()).afterStep(new PhonePlan.Step(), null, null, "已点击"));
    }
}
