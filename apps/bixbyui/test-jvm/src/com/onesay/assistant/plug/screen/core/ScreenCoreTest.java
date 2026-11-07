package com.onesay.assistant.plug.screen.core;

import com.onesay.assistant.plug.api.Clock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** mika.screen 核心（ScreenReader / TreeQuality / ScreenJson / RefStabilizer / Accuracy / ActionEffect / InterfaceUpdate / ChangeWait）。 */
public class ScreenCoreTest {

    static final int W = 1080, H = 2220;

    static UiNode n(String cls, String text, int l, int t, int r, int b) {
        return UiNode.of(cls, text, l, t, r, b);
    }

    static UiNode btn(String text, int l, int t, int r, int b) {
        UiNode x = n("android.widget.Button", text, l, t, r, b);
        x.clickable = true;
        return x;
    }

    static List<UiWindow> win(UiNode root) {
        List<UiWindow> l = new ArrayList<UiWindow>();
        l.add(new UiWindow(1, UiWindow.APPLICATION, 1, "com.example", root));
        return l;
    }

    static ScreenSnapshot read(UiNode root) {
        return ScreenReader.read("p", win(root), W, H, new ScreenReader.Options().skip("com.onesay.assistant"));
    }

    static UiNode settingsPage() {
        UiNode root = n("android.widget.FrameLayout", "", 0, 0, W, H);
        root.add(n("android.widget.TextView", "设置", 40, 80, 400, 160));
        UiNode list = n("androidx.recyclerview.widget.RecyclerView", "", 0, 200, W, H);
        list.scrollable = true;
        root.add(list);
        String[] items = {"WLAN", "蓝牙", "显示", "声音", "通知", "电池", "关于手机"};
        for (int i = 0; i < items.length; i++) {
            UiNode row = n("android.widget.LinearLayout", "", 0, 200 + i * 200, W, 400 + i * 200);
            row.clickable = true;
            row.add(n("android.widget.TextView", items[i], 60, 220 + i * 200, 600, 300 + i * 200));
            list.add(row);
        }
        return root;
    }

    @Test public void normalizeRoundTrip() {
        assertEquals(0, UiElement.normalize(0, W));
        assertEquals(999, UiElement.normalize(W - 1, W));
        for (int p : new int[]{0, 1, 333, 540, 1079}) {
            int n = UiElement.normalize(p, W);
            assertTrue(Math.abs(UiElement.denormalize(n, W) - p) <= 2);
        }
        assertEquals(12, UiElement.refNo("@e12"));
        assertEquals(-1, UiElement.refNo("x"));
    }

    @Test public void readerFiltersAndNames() {
        UiNode root = n("android.widget.FrameLayout", "", 0, 0, W, H);
        UiNode hidden = n("android.widget.TextView", "看不见", 0, 0, 100, 100);
        hidden.visible = false;
        hidden.add(n("android.widget.TextView", "但子节点可见", 0, 100, 300, 200));
        root.add(hidden);
        root.add(n("android.widget.TextView", "太小", 0, 300, 3, 303));
        UiNode card = n("android.widget.LinearLayout", "", 0, 400, W, 600);
        card.clickable = true;
        card.add(n("android.widget.TextView", "张三", 10, 410, 200, 450));
        card.add(n("android.widget.TextView", "你好", 10, 460, 200, 500));
        root.add(card);
        UiNode pw = n("android.widget.EditText", "secret123", 0, 700, W, 800);
        pw.editable = true;
        pw.password = true;
        pw.hint = "密码";
        root.add(pw);
        UiNode edit = n("android.widget.EditText", "搜索", 0, 900, W, 1000);
        edit.editable = true;
        edit.hint = "搜索";
        edit.rid = "com.example:id/search_src_text";
        root.add(edit);
        ScreenSnapshot s = read(root);
        List<String> names = new ArrayList<String>();
        for (UiElement e : s.elements) names.add(e.name);
        assertFalse(names.contains("看不见"));
        assertTrue(names.contains("但子节点可见"));
        assertFalse(names.contains("太小"));
        assertTrue(names.contains("张三 你好"));
        assertFalse(names.toString().contains("secret123"));
        UiElement p = null, se = null;
        for (UiElement e : s.elements) {
            if (e.secret) p = e;
            if ("search_src_text".equals(e.resourceId)) se = e;
        }
        assertNotNull(p);
        assertEquals("密码", p.name);
        assertNotNull(se);
        assertTrue("文字等于提示语 → hint", se.hint);
        assertEquals("@e1", s.elements.get(0).ref);
        assertEquals(4, s.elements.get(0).bounds.length);
        assertTrue(s.elements.get(0).bounds[2] <= 999);
    }

    @Test public void readerSkipsOwnPackageImeAndOrdersByLayer() {
        List<UiWindow> ws = new ArrayList<UiWindow>();
        ws.add(new UiWindow(1, UiWindow.APPLICATION, 1, "com.example", btn("底下", 0, 0, 500, 200)));
        ws.add(new UiWindow(2, UiWindow.APPLICATION, 9, "com.onesay.assistant.debug", btn("自家岛", 0, 0, 500, 200)));
        ws.add(new UiWindow(3, UiWindow.IME, 8, "com.iflytek", btn("键盘", 0, 1800, 500, 2000)));
        ws.add(new UiWindow(4, UiWindow.SYSTEM, 5, "com.android.packageinstaller", btn("允许", 0, 900, 500, 1000)));
        ScreenSnapshot s = ScreenReader.read("p", ws, W, H, new ScreenReader.Options().skip("com.onesay.assistant"));
        assertEquals(2, s.elements.size());
        assertEquals("允许", s.elements.get(0).name);
        assertEquals("底下", s.elements.get(1).name);
        assertEquals("com.example", s.packageName);
    }

    @Test public void readerPrunesCanvasAndMasksNumbers() {
        UiNode root = n("android.widget.FrameLayout", "", 0, 0, W, H);
        UiNode map = n("com.baidu.mapapi.map.MapView", "", 0, 0, W, 1500);
        for (int i = 0; i < 50; i++) map.add(n("android.view.View", "poi" + i, i, i, i + 50, i + 50));
        root.add(map);
        root.add(n("android.widget.TextView", "卡号 6222 0212 3456 7890 123", 0, 1600, W, 1700));
        root.add(n("android.widget.TextView", "身份证 110101199003071234", 0, 1700, W, 1800));
        ScreenSnapshot s = read(root);
        int canvas = 0;
        for (UiElement e : s.elements) {
            assertFalse(e.name.startsWith("poi"));
            if (e.name.equals("[画布]")) canvas++;
            assertFalse(e.name, e.name.contains("6222 0212") || e.name.contains("110101199003071234"));
        }
        assertEquals(1, canvas);
        assertTrue(ScreenPrivacy.mask("身份证 110101199003071234").endsWith("****1234"));
    }

    @Test public void protectedRidKeepsPlaceholder() {
        UiNode root = n("android.widget.FrameLayout", "", 0, 0, W, H);
        UiNode f = n("android.widget.EditText", "123456", 0, 100, W, 200);
        f.editable = true;
        f.rid = "com.pay:id/pay_pwd_input";
        root.add(f);
        ScreenSnapshot s = read(root);
        assertEquals(1, s.elements.size());
        assertEquals(ScreenPrivacy.PROTECTED_NAME, s.elements.get(0).name);
        assertTrue(s.elements.get(0).secret);
        assertEquals(1, s.omittedByPrivacy);
    }

    @Test public void readableRid() {
        assertEquals("cart_icon", ScreenReader.readableResourceId("com.x:id/cart_icon"));
        assertNull(ScreenReader.readableResourceId("com.x:id/a1"));
        assertNull(ScreenReader.readableResourceId("com.x:id/1234"));
        assertNull(ScreenReader.readableResourceId(null));
    }

    @Test public void treeQuality() {
        assertEquals(TreeQuality.ADEQUATE, TreeQuality.of(read(settingsPage()))[0]);
        UiNode root = n("android.widget.FrameLayout", "", 0, 0, W, H);
        for (int i = 0; i < 5; i++) root.add(btn("", i * 200, 0, i * 200 + 150, 150));
        assertEquals(TreeQuality.UNUSABLE, TreeQuality.of(read(root))[0]);
        root.add(n("android.widget.TextView", "标题", 0, 300, 500, 400));
        assertEquals(TreeQuality.PARTIAL, TreeQuality.of(read(root))[0]);
        UiNode blind = n("android.widget.FrameLayout", "", 0, 0, W, H);
        blind.add(n("android.widget.TextView", "顶栏", 0, 0, W, 100));
        UiNode big = n("android.view.View", "", 0, 200, W, 2000);
        big.add(n("android.view.View", "", 0, 300, 100, 400));
        blind.add(big);
        ScreenSnapshot bs = read(blind);
        assertTrue(bs.blind);
        assertEquals(TreeQuality.PARTIAL, TreeQuality.of(bs)[0]);
        assertEquals(TreeQuality.UNUSABLE, TreeQuality.of(read(n("android.widget.FrameLayout", "", 0, 0, W, H)))[0]);
    }

    @Test public void jsonCapsAtBudget() throws Exception {
        UiNode root = n("android.widget.FrameLayout", "", 0, 0, W, H);
        for (int i = 0; i < 200; i++) root.add(btn("按钮第" + i + "个有点长的名字", 0, i * 10, W, i * 10 + 9));
        ScreenSnapshot s = read(root);
        JSONObject o = ScreenJson.observe(s, 6000);
        assertTrue(o.toString().length() < 6600);
        assertTrue(o.getBoolean("truncated"));
        assertTrue(o.getInt("omitted") > 0);
        JSONArray els = o.getJSONArray("elements");
        assertEquals("@e1", els.getJSONObject(0).getString("ref"));
        assertTrue(els.getJSONObject(0).getJSONObject("center").getInt("x") <= 999);
        String txt = ScreenJson.render(s, 500);
        assertTrue(txt.length() <= 500);
        assertTrue(txt.contains("还有"));
    }

    @Test public void stabilizerKeepsRefsAcrossSameScreen() {
        RefStabilizer rs = new RefStabilizer("s");
        RefStabilizer.ModelSnapshot a = rs.next(read(settingsPage()));
        RefStabilizer.ModelSnapshot b = rs.next(read(settingsPage()));
        assertEquals("s1", a.snapshotId);
        assertEquals("s2", b.snapshotId);
        for (int i = 0; i < a.screen.elements.size(); i++) {
            UiElement x = a.screen.elements.get(i);
            if (x.name.length() == 0 && x.resourceId == null) {   // 没名字没 rid 的（列表容器）永远拿新号
                assertNotEquals(x.ref, b.screen.elements.get(i).ref);
                continue;
            }
            assertEquals(x.name, x.ref, b.screen.elements.get(i).ref);
        }
        String r = a.screen.elements.get(0).ref;
        RefStabilizer.Resolved old = rs.resolve(r, "s1");
        assertFalse(old.stale());
        assertTrue(old.relocated);
        assertTrue(rs.resolve("@e999", "s2").stale());
        assertTrue(rs.resolve(r, "x9").stale());
    }

    @Test public void stabilizerNewRefsAfterScrollInList() {
        RefStabilizer rs = new RefStabilizer("s");
        RefStabilizer.ModelSnapshot a = rs.next(read(settingsPage()));
        UiNode moved = settingsPage();
        UiNode list = moved.children.get(1);
        for (UiNode row : list.children) {
            row.t -= 150;
            row.b -= 150;
            for (UiNode c : row.children) {
                c.t -= 150;
                c.b -= 150;
            }
        }
        RefStabilizer.ModelSnapshot b = rs.next(read(moved));
        UiElement wa = null, wb = null;
        for (UiElement e : a.screen.elements) if (e.name.equals("WLAN")) wa = e;
        for (UiElement e : b.screen.elements) if (e.name.equals("WLAN")) wb = e;
        assertNotNull(wa);
        assertNotNull(wb);
        assertNotEquals("列表滚动后同名行拿新编号", wa.ref, wb.ref);
        assertEquals("标题不在列表里，原地不动沿用", a.screen.elements.get(0).ref, b.screen.elements.get(0).ref);
    }

    @Test public void relocateRules() {
        List<ElementLocator> before = Arrays.asList(
                new ElementLocator("Button", "跳过 3", "skip", 900, 100, null, false, false),
                new ElementLocator("TextView", "标题", null, 500, 300, null, false, false),
                new ElementLocator("TextView", "正文", null, 500, 600, "标题", false, false));
        List<ElementLocator> after = Arrays.asList(
                new ElementLocator("Button", "跳过 2", "skip", 900, 100, null, false, false),
                new ElementLocator("TextView", "标题", null, 500, 300, null, false, false),
                new ElementLocator("TextView", "正文", null, 500, 600, "标题", false, false));
        assertEquals("数字变化原地同 rid 算同一个", 0, Accuracy.relocate(before.get(0), before, after, 1080));
        assertEquals(2, Accuracy.relocate(before.get(2), before, after, 1080));
        List<ElementLocator> follow = Arrays.asList(new ElementLocator("Button", "已关注", "skip", 900, 100, null, false, false));
        assertEquals(Accuracy.GONE, Accuracy.relocate(new ElementLocator("Button", "关注", null, 900, 100, null, false, false), before, follow, 1080));
        assertEquals(Accuracy.AMBIGUOUS, Accuracy.relocate(new ElementLocator("View", "", null, 1, 1, null, false, false), before, after, 1080));
    }

    static final class Field implements Accuracy.TextField {
        String value;
        int writes;
        final boolean stuck;

        Field(String v, boolean stuck) {
            value = v;
            this.stuck = stuck;
        }

        @Override public String read() {
            return value;
        }

        @Override public boolean write(String t) {
            writes++;
            if (!stuck) value = t;
            return true;
        }
    }

    @Test public void typeAndVerify() {
        Field f = new Field("", false);
        assertEquals(Accuracy.Verification.MATCH, Accuracy.typeAndVerify(f, "上海 天气", 2, null, null, false));
        Field s = new Field("提示", true);
        assertEquals(Accuracy.Verification.MISMATCH, Accuracy.typeAndVerify(s, "abc", 2, null, null, false));
        assertEquals(2, s.writes);
        Field s2 = new Field("提示", true);
        assertEquals(Accuracy.Verification.UNKNOWN, Accuracy.typeAndVerify(s2, "abc", 2, null, new Accuracy.Reacted() {
            @Override public boolean reacted() {
                return true;
            }
        }, false));
        Field pw = new Field("x", false);
        assertEquals(Accuracy.Verification.UNKNOWN, Accuracy.typeAndVerify(pw, "pw", 2, null, null, true));
        assertEquals(1, pw.writes);
    }

    @Test public void effectKinds() {
        ScreenSnapshot a = read(settingsPage());
        assertEquals(ActionEffect.Kind.NO_EFFECT, ActionEffect.classify(ActionEffect.Verb.TAP, a, read(settingsPage()), null, null, null, false, false).kind);
        UiNode other = n("android.widget.FrameLayout", "", 0, 0, W, H);
        other.add(n("android.widget.TextView", "WLAN", 40, 80, 400, 160));
        for (int i = 0; i < 6; i++) other.add(btn("网络" + i, 0, 300 + i * 150, W, 440 + i * 150));
        assertEquals(ActionEffect.Kind.DONE, ActionEffect.classify(ActionEffect.Verb.TAP, a, read(other), null, null, null, false, false).kind);
        ActionEffect.PortFacts pf = new ActionEffect.PortFacts();
        pf.switchedTo = "com.tencent.mm";
        ActionEffect pc = ActionEffect.classify(ActionEffect.Verb.TAP, a, a, pf, null, null, false, false);
        assertEquals(ActionEffect.Kind.PLACE_CHANGED, pc.kind);
        assertTrue(ActionEffect.hint(pc, ActionEffect.Verb.TAP).contains("com.tencent.mm"));
        UiNode t1 = settingsPage();
        UiNode sw = n("android.widget.Switch", "WLAN 开关", 800, 220, 1000, 300);
        sw.clickable = true;
        t1.add(sw);
        UiNode t2 = settingsPage();
        UiNode sw2 = n("android.widget.Switch", "WLAN 开关", 800, 220, 1000, 300);
        sw2.clickable = true;
        sw2.checked = true;
        t2.add(sw2);
        ActionEffect tg = ActionEffect.classify(ActionEffect.Verb.TAP, read(t1), read(t2), null, null, null, false, false);
        assertEquals(ActionEffect.Kind.SMALL_CHANGE, tg.kind);
        assertEquals(1, tg.diff.toggled);
        assertNull(ActionEffect.classify(ActionEffect.Verb.TAP, null, null, null, null, null, false, false));
        String h = ActionEffect.hint(ActionEffect.classify(ActionEffect.Verb.BACK, a, a, null, null, null, false, false), ActionEffect.Verb.BACK);
        assertTrue(h.contains("返回"));
    }

    @Test public void effectReadback() {
        UiNode r1 = n("android.widget.FrameLayout", "", 0, 0, W, H);
        UiNode e1 = n("android.widget.EditText", "", 0, 100, W, 200);
        e1.editable = true;
        e1.focused = true;
        e1.hint = "搜索";
        r1.add(e1);
        UiNode r2 = n("android.widget.FrameLayout", "", 0, 0, W, H);
        UiNode e2 = n("android.widget.EditText", "天气", 0, 100, W, 200);
        e2.editable = true;
        e2.focused = true;
        r2.add(e2);
        ScreenSnapshot a = read(r1), b = read(r2);
        ActionEffect ok = ActionEffect.classify(ActionEffect.Verb.TYPE, a, b, null, a.elements.get(0), "天气", false, false);
        assertEquals(ActionEffect.Kind.DONE, ok.kind);
        assertTrue(ok.readbackMatches);
        ActionEffect bad = ActionEffect.classify(ActionEffect.Verb.TYPE, a, b, null, a.elements.get(0), "下雨", false, false);
        assertFalse(bad.readbackMatches);
        assertTrue(ActionEffect.hint(bad, ActionEffect.Verb.TYPE).contains("天气"));
    }

    @Test public void treeBlindOnUnusable() {
        ScreenSnapshot a = read(settingsPage());
        ActionEffect e = ActionEffect.classify(ActionEffect.Verb.TAP, a, a, null, null, null, false, false);
        assertTrue(ActionEffect.withTreeBlind(e, ActionEffect.Verb.TAP, TreeQuality.UNUSABLE, a, null, new int[]{1, 1}).treeBlind);
        assertFalse(ActionEffect.withTreeBlind(e, ActionEffect.Verb.TAP, TreeQuality.ADEQUATE, a, null, new int[]{1, 1}).treeBlind);
    }

    @Test public void interfaceUpdateDiffAndFull() throws Exception {
        RefStabilizer rs = new RefStabilizer("s");
        ScreenSnapshot a = rs.next(read(settingsPage())).screen;
        UiNode p2 = settingsPage();
        p2.add(btn("新按钮", 0, 2100, 500, 2200));
        ScreenSnapshot b = rs.next(read(p2)).screen;
        JSONObject d = InterfaceUpdate.between(a, b, true, 0, 6000);
        assertFalse(d.toString(), d.getBoolean("full"));
        assertTrue(d.getJSONArray("added").toString().contains("新按钮"));
        assertTrue("无名列表容器换号 = 1 增 1 删", d.getJSONArray("added").length() <= 2);
        assertEquals("s1", d.getString("base"));
        assertTrue(InterfaceUpdate.between(a, b, true, InterfaceUpdate.MAX_CONSECUTIVE_DIFFS, 6000).getBoolean("full"));
        assertTrue(InterfaceUpdate.between(null, b, true, 0, 6000).getBoolean("full"));
    }

    static final class FakeClock implements Clock {
        long t;

        @Override public long elapsed() {
            return t;
        }

        @Override public long wall() {
            return t;
        }

        @Override public void sleep(long ms) {
            t += Math.max(1, ms);
        }
    }

    @Test public void changeWait() {
        final FakeClock c = new FakeClock();
        // 事件在 600 ms 后安静、树已变 → ≈1000 ms 内判变了
        boolean ch = ChangeWait.awaitStableChange(c, new ChangeWait.Quiet() {
            @Override public long quietForMs() {
                return c.t < 600 ? 0 : c.t - 600;
            }
        }, new ChangeWait.TreeChanged() {
            @Override public boolean changed() {
                return true;
            }
        }, 0);
        assertTrue(ch);
        assertTrue(c.t >= 1000 && c.t < 1300);
        final FakeClock c2 = new FakeClock();
        boolean no = ChangeWait.awaitStableChange(c2, new ChangeWait.Quiet() {
            @Override public long quietForMs() {
                return Long.MAX_VALUE;
            }
        }, new ChangeWait.TreeChanged() {
            @Override public boolean changed() {
                return false;
            }
        }, 0);
        assertFalse(no);
        assertTrue(c2.t >= ChangeWait.CAP_MS && c2.t <= ChangeWait.CAP_MS + ChangeWait.POLL_MS);
        // 一直不安静 → 到上限按树下结论
        final FakeClock c3 = new FakeClock();
        assertTrue(ChangeWait.awaitStableChange(c3, new ChangeWait.Quiet() {
            @Override public long quietForMs() {
                return 0;
            }
        }, new ChangeWait.TreeChanged() {
            @Override public boolean changed() {
                return true;
            }
        }, 0));
        assertTrue(c3.t >= ChangeWait.CAP_MS);
    }
}
