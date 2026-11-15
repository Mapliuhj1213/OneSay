package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.a11ylabel.A11yLabeler;
import com.onesay.assistant.v2.a11ylabel.LabelRender;
import com.onesay.assistant.v2.a11ylabel.SnapshotBinding;
import com.onesay.assistant.v2.fastscreen.FsTree;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ★ a11y-labels：精准标签（快照 ID、稳定键、角色、推断名、阅读序号、渲染 / 日志）与动作绑定快照。 */
public class A11yLabelTest {

    static final int C = FakeScreens.C;

    static FakeScreens.B chat() {
        return FakeScreens.screen("com.example.chat", "com.example.chat.ChatActivity")
                .node("android.widget.ImageView", "", "com.example.chat:id/iv_back", C)
                .button("关注", "com.example.chat:id/follow")
                .button("3条新消息")
                .edit("说点什么", "com.example.chat:id/input")
                .toggle("免打扰", false)
                .text("今天 12:30");
    }

    static UiTree lab(FakeScreens.B b, String snap) {
        return A11yLabeler.attach(b.ui(new UiTree.Ids()), snap, 1000L);
    }

    static Map<String, String> keysByLabel(UiTree u) {
        Map<String, String> m = new HashMap<String, String>();
        for (UiTree.El e : u.els) m.put(e.label, e.nodeKey);
        return m;
    }

    // ------------------------------------------------------------------ 稳定性

    @Test public void sameScreenReadTwiceGivesSameKeysAndNewSnapshotIds() {
        UiTree a = lab(chat(), "S1"), b = lab(chat(), "S2");
        assertEquals("S1", a.snapshotId);
        assertEquals("S2", b.snapshotId);
        assertEquals(a.els.size(), b.els.size());
        for (int i = 0; i < a.els.size(); i++) {
            assertTrue(a.els.get(i).nodeKey.matches("n[0-9a-f]{8}(\\.\\d+)?"));
            assertEquals(a.els.get(i).nodeKey, b.els.get(i).nodeKey);
            assertEquals(a.els.get(i).role, b.els.get(i).role);
            assertEquals(a.els.get(i).ord, b.els.get(i).ord);
        }
    }

    @Test public void defaultSnapshotIdsAreUniquePerRead() {
        UiTree a = A11yLabeler.attach(chat().ui(null), null, 0), b = A11yLabeler.attach(chat().ui(null), null, 0);
        assertTrue(a.snapshotId.startsWith("S"));
        assertNotEquals(a.snapshotId, b.snapshotId);
    }

    @Test public void allKeysOnAScreenAreUnique() {
        UiTree u = lab(FakeScreens.screen("p.k", "a").button("确定").button("确定").button("确定").text("x"), "S1");
        java.util.Set<String> s = new java.util.HashSet<String>();
        for (UiTree.El e : u.els) assertTrue(e.nodeKey, s.add(e.nodeKey));
        assertTrue(u.els.get(1).nodeKey.endsWith(".2"));
        assertTrue(u.els.get(2).nodeKey.endsWith(".3"));
    }

    @Test public void keysSurviveABannerInsertedAbove() {
        UiTree a = lab(chat(), "S1");
        FakeScreens.B b = FakeScreens.screen("com.example.chat", "com.example.chat.ChatActivity").text("网络不给力，点击重试");
        b.node("android.widget.ImageView", "", "com.example.chat:id/iv_back", C).button("关注", "com.example.chat:id/follow")
                .button("3条新消息").edit("说点什么", "com.example.chat:id/input").toggle("免打扰", false).text("今天 12:30");
        UiTree u = lab(b, "S2");
        Map<String, String> ka = keysByLabel(a), kb = keysByLabel(u);
        for (String name : new String[]{"返回", "关注", "3条新消息", "说点什么", "免打扰", "今天 12:30"}) assertEquals(name, ka.get(name), kb.get(name));
    }

    @Test public void textChangeRules() {
        // 有资源 id：文字怎么变键都不变
        UiTree a = lab(FakeScreens.screen("p.k", "a").button("关注", "p.k:id/follow"), "S1");
        UiTree b = lab(FakeScreens.screen("p.k", "a").button("已关注", "p.k:id/follow"), "S2");
        assertEquals(a.els.get(0).nodeKey, b.els.get(0).nodeKey);
        // 没有资源 id：只改数字键不变（验证码 / 号码 / 角标不进键）
        a = lab(FakeScreens.screen("p.k", "a").button("3条新消息"), "S1");
        b = lab(FakeScreens.screen("p.k", "a").button("12条新消息"), "S2");
        assertEquals(a.els.get(0).nodeKey, b.els.get(0).nodeKey);
        a = lab(FakeScreens.screen("p.k", "a").text("验证码 123456"), "S1");
        b = lab(FakeScreens.screen("p.k", "a").text("验证码 654321"), "S2");
        assertEquals(a.els.get(0).nodeKey, b.els.get(0).nodeKey);
        // 没有资源 id：改了别的字 = 另一个控件
        a = lab(FakeScreens.screen("p.k", "a").button("确定"), "S1");
        b = lab(FakeScreens.screen("p.k", "a").button("取消"), "S2");
        assertNotEquals(a.els.get(0).nodeKey, b.els.get(0).nodeKey);
        // 类名不同 = 另一个控件
        b = lab(FakeScreens.screen("p.k", "a").node("android.widget.TextView", "确定", "", C), "S2");
        assertNotEquals(a.els.get(0).nodeKey, b.els.get(0).nodeKey);
    }

    @Test public void typingIntoAFieldDoesNotChangeItsKey() {
        // 有资源 id
        UiTree a = lab(FakeScreens.screen("p.k", "a").edit("搜索", "p.k:id/q"), "S1");
        UiTree b = lab(FakeScreens.screen("p.k", "a").edit("北京天气", "p.k:id/q"), "S2");
        assertEquals(a.els.get(0).nodeKey, b.els.get(0).nodeKey);
        // 没有资源 id、有提示语
        FakeScreens.B x = FakeScreens.screen("p.k", "a").edit("搜索", "");
        x.t.nodes.get(1).hint = "搜索";
        FakeScreens.B y = FakeScreens.screen("p.k", "a").edit("北京天气", "");
        y.t.nodes.get(1).hint = "搜索";
        UiTree ux = lab(x, "S1"), uy = lab(y, "S2");
        assertEquals(ux.els.get(0).nodeKey, uy.els.get(0).nodeKey);
        assertEquals(A11yLabeler.SRC_HINT, ux.els.get(0).nameSrc);
        assertEquals(A11yLabeler.SRC_TEXT, uy.els.get(0).nameSrc);
    }

    @Test public void listRowsSharingAResourceIdAreTellApartByText() {
        FakeScreens.B b = FakeScreens.screen("p.k", "a");
        b.node("androidx.recyclerview.widget.RecyclerView", "", "p.k:id/list", FsTree.F_SCROLL | FsTree.F_ENABLED | FsTree.F_VISIBLE);
        int list = b.t.nodes.size() - 1;
        for (String s : new String[]{"张三", "李四"}) {
            b.node("android.widget.TextView", s, "p.k:id/name", C);
            b.t.nodes.get(b.t.nodes.size() - 1).parent = list;
        }
        UiTree u = lab(b, "S1");
        UiTree.El z = u.findText("张三"), l = u.findText("李四");
        assertEquals(A11yLabeler.R_ITEM, z.role);
        assertNotEquals(z.nodeKey, l.nodeKey);
        assertFalse(z.nodeKey.contains("."));
        assertEquals(A11yLabeler.R_LIST, u.els.get(0).role);
    }

    // ------------------------------------------------------------------ 角色 / 名字

    @Test public void rolesFromClassAndFlags() {
        FakeScreens.B b = FakeScreens.screen("p.k", "a").button("登录").edit("手机号", "p.k:id/phone").toggle("WLAN", true)
                .node("android.widget.CheckBox", "同意协议", "", C | FsTree.F_CHECKABLE)
                .node("android.widget.RadioButton", "男", "", C | FsTree.F_CHECKABLE | FsTree.F_CHECKED)
                .node("android.widget.ImageView", "", "", FsTree.F_ENABLED | FsTree.F_VISIBLE)
                .text("说明")
                .node("android.widget.SeekBar", "", "p.k:id/volume", C)
                .node("android.widget.LinearLayout", "首页", "", C);
        b.t.nodes.get(6).desc = "头像";
        UiTree u = lab(b, "S1");
        assertEquals(A11yLabeler.R_BUTTON, u.findText("登录").role);
        assertEquals(A11yLabeler.R_INPUT, u.findText("手机号").role);
        assertEquals(A11yLabeler.R_SWITCH, u.findText("WLAN").role);
        assertEquals(A11yLabeler.R_CHECK, u.findText("同意协议").role);
        assertEquals(A11yLabeler.R_RADIO, u.findText("男").role);
        assertEquals(A11yLabeler.R_IMAGE, u.findText("头像").role);
        assertEquals(A11yLabeler.R_TEXT, u.findText("说明").role);
        assertEquals("资源 id 不在词表就原样给", A11yLabeler.R_SLIDER, u.findText("volume").role);
        assertEquals(A11yLabeler.R_BUTTON, u.findText("首页").role);
        String r = u.render(50);
        assertTrue(r, r.contains("勾选框「同意协议」 未勾选"));
        assertTrue(r, r.contains("单选「男」 已勾选"));
        assertTrue(r, r.contains("开关「WLAN」 已开"));
    }

    @Test public void iconButtonsGetInferredNamesMarked() {
        FakeScreens.B b = FakeScreens.screen("p.k", "a")
                .node("android.widget.ImageView", "", "p.k:id/iv_back", C)
                .node("android.widget.ImageButton", "", "p.k:id/btnMoreActions", C)
                .node("android.widget.ImageView", "", "", C)
                .node("android.widget.ImageView", "", "p.k:id/a1", C)
                .node("android.widget.ImageView", "", "p.k:id/shopping_cart_icon", C);
        b.t.nodes.get(3).desc = "分享";
        UiTree u = lab(b, "S1");
        UiTree.El back = u.els.get(0), more = u.els.get(1), share = u.els.get(2), anon = u.els.get(3), cart = u.els.get(4);
        assertEquals("返回", back.label);
        assertEquals(A11yLabeler.SRC_RID, back.nameSrc);
        assertEquals(A11yLabeler.R_BUTTON, back.role);
        assertEquals("更多", more.label);
        assertEquals("分享", share.label);
        assertEquals(A11yLabeler.SRC_DESC, share.nameSrc);
        assertEquals("混淆过的短 id 不猜", "", anon.label);
        assertFalse("有资源 id 就不是弱键", anon.weakKey);
        assertEquals("购物车", cart.label);
        String r = u.render(50);
        assertTrue(r, r.contains("按钮「返回」推断"));
        assertTrue(r, r.contains("按钮「分享」推断"));
        assertTrue(r, r.contains("按钮 #a1"));
        assertEquals("iv_back → 返回", "返回", A11yLabeler.inferFromRid("com.x:id/iv_back"));
        assertEquals("seekVolume", A11yLabeler.inferFromRid("p:id/seekVolume"));
        assertNull(A11yLabeler.inferFromRid("p:id/b2"));
        assertNull(A11yLabeler.inferFromRid(""));
    }

    @Test public void readingOrderNumbersOnlyTappableElements() {
        FakeScreens.B b = FakeScreens.screen("p.k", "a");
        // 同一行：右边的先进树，左边的后进树；序号仍按从左到右
        b.node("android.widget.Button", "右", "", C);
        FsTree.Node r = b.t.nodes.get(1);
        r.l = 600;
        r.r = 1000;
        b.y -= 130;
        b.node("android.widget.Button", "左", "", C);
        FsTree.Node l = b.t.nodes.get(2);
        l.l = 0;
        l.r = 400;
        l.t += 10;   // 顶边差一点点，仍算同一行
        b.text("说明文字").button("下面");
        UiTree u = lab(b, "S1");
        assertEquals(1, u.findText("左").ord);
        assertEquals(2, u.findText("右").ord);
        assertEquals(0, u.findText("说明文字").ord);
        assertEquals(3, u.findText("下面").ord);
        assertSame(u.findText("右"), u.findOrd(2));
        assertNull(u.findOrd(4));
    }

    // ------------------------------------------------------------------ 渲染 / 记录 / 日志

    @Test public void renderIsCompactAndCarriesSnapshotButNoKeys() {
        UiTree u = lab(chat(), "S7");
        String r = u.render(50);
        assertTrue(r, r.startsWith("前台：com.example.chat / ChatActivity · 快照 S7\n"));
        assertTrue(r, r.contains("输入框「说点什么」 #input"));
        assertTrue(r, r.contains("按钮「关注」 @"));
        assertFalse("关注有名字，资源 id 不重复写", r.contains("#follow"));
        for (UiTree.El e : u.els) assertFalse(r.contains(e.nodeKey));
        UiTree plain = chat().ui(new UiTree.Ids());
        assertTrue("贴标签后不比原口径长太多", r.length() <= plain.render(50).length() + 40);
        assertTrue(u.render(2).contains("还有 4 个元素"));
    }

    @Test public void logLineHasNoTextAndRecordHasEverything() throws Exception {
        UiTree u = lab(FakeScreens.screen("p.k", "a").button("给13800138000打电话", "p.k:id/call").text("a@b.com"), "S1");
        for (UiTree.El e : u.els) {
            String log = LabelRender.logLine(u, e);
            assertFalse(log, log.contains("138"));
            assertFalse(log, log.contains("@b.com"));
            assertFalse(log, log.contains("call"));
            assertTrue(log, log.startsWith("S1 n"));
        }
        JSONObject o = LabelRender.record(u, u.els.get(0));
        assertEquals("S1", o.getString("snapshot"));
        assertEquals("p.k", o.getString("pkg"));
        assertEquals(A11yLabeler.R_BUTTON, o.getString("role"));
        assertEquals(1, o.getInt("ord"));
        assertEquals(4, o.getJSONArray("bounds").length());
        assertTrue(o.getBoolean("clickable"));
        assertTrue(o.getBoolean("enabled"));
    }

    @Test public void mikaBuildPathAlsoGetsLabels() {
        FakeScreens.B b = chat();
        MikaExec mx = new MikaExec(MikaPhoneTest.all());
        UiTree u = mx.build(b.t, new UiTree.Ids(), 1080, 2220);
        A11yLabeler.attach(u, "S1", 0);
        UiTree v = A11yLabeler.attach(mx.build(chat().t, new UiTree.Ids(), 1080, 2220), "S2", 0);
        assertFalse(u.els.isEmpty());
        for (int i = 0; i < u.els.size(); i++) {
            assertTrue(u.els.get(i).node >= 0);
            assertEquals(u.els.get(i).nodeKey, v.els.get(i).nodeKey);
        }
        assertTrue(u.render(50).contains("快照 S1"));
    }

    // ------------------------------------------------------------------ 绑定（纯函数）

    @Test public void bindingVerdicts() {
        UiTree seen = lab(chat(), "S1");
        int follow = seen.findText("关注").id;
        // 同一界面：通过，目标是新树里的元素
        UiTree fresh = lab(chat(), "S2");
        SnapshotBinding.Verdict v = SnapshotBinding.check(seen, follow, fresh, 2000, 0);
        assertTrue(v.ok());
        assertSame(fresh.findText("关注"), v.target);
        // 过期
        v = SnapshotBinding.check(seen, follow, fresh, 1000 + 61000, 60000);
        assertEquals(SnapshotBinding.Kind.EXPIRED, v.kind);
        assertTrue(v.refused());
        // Activity 变了
        FakeScreens.B other = chat();
        other.t.activity = "com.example.chat.ProfileActivity";
        v = SnapshotBinding.check(seen, follow, lab(other, "S3"), 2000, 0);
        assertEquals(SnapshotBinding.Kind.PAGE_CHANGED, v.kind);
        // 包名变了
        FakeScreens.B pk = chat();
        pk.t.pkg = "com.other";
        assertEquals(SnapshotBinding.Kind.PAGE_CHANGED, SnapshotBinding.check(seen, follow, lab(pk, "S4"), 2000, 0).kind);
        // 节点不在了
        UiTree gone = lab(FakeScreens.screen("com.example.chat", "com.example.chat.ChatActivity").button("3条新消息"), "S5");
        v = SnapshotBinding.check(seen, follow, gone, 2000, 0);
        assertEquals(SnapshotBinding.Kind.NODE_GONE, v.kind);
        assertTrue(v.reason, v.reason.contains("找不到"));
        // 读不到
        assertEquals(SnapshotBinding.Kind.PAGE_CHANGED, SnapshotBinding.check(seen, follow, null, 2000, 0).kind);
        // 没贴标签：退回原口径（不算拒绝）
        v = SnapshotBinding.check(chat().ui(null), 1, fresh, 2000, 0);
        assertEquals(SnapshotBinding.Kind.UNLABELED, v.kind);
        assertFalse(v.refused());
        // 坐标点：只看页面
        assertTrue(SnapshotBinding.checkPage(seen, fresh, 2000, 0).ok());
        assertEquals(SnapshotBinding.Kind.PAGE_CHANGED, SnapshotBinding.checkPage(seen, lab(other, "S6"), 2000, 0).kind);
        assertEquals(SnapshotBinding.Kind.UNLABELED, SnapshotBinding.checkPage(null, fresh, 2000, 0).kind);
    }

    @Test public void weakKeyAlsoNeedsSamePlace() {
        FakeScreens.B a = FakeScreens.screen("p.k", "a").node("android.view.View", "", "", C);
        UiTree seen = lab(a, "S1");
        assertTrue(seen.els.get(0).weakKey);
        assertTrue(SnapshotBinding.check(seen, seen.els.get(0).id, lab(FakeScreens.screen("p.k", "a").node("android.view.View", "", "", C), "S2"), 0, 0).ok());
        FakeScreens.B moved = FakeScreens.screen("p.k", "a").node("android.view.View", "", "", C);
        moved.t.nodes.get(1).t += 600;
        moved.t.nodes.get(1).b += 600;
        assertEquals(SnapshotBinding.Kind.NODE_GONE, SnapshotBinding.check(seen, seen.els.get(0).id, lab(moved, "S2"), 0, 0).kind);
    }

    // ------------------------------------------------------------------ 绑定（接进 PhoneAgent）

    /** 逐帧假手机：每读一次屏取下一帧（停在最后一帧）；点了某个名字就固定到某一帧。 */
    static final class Frames implements PhoneAgent.Driver {
        final List<FakeScreens.B> frames = new ArrayList<FakeScreens.B>();
        final Map<String, FakeScreens.B> onTap = new HashMap<String, FakeScreens.B>();
        final List<String> log = new ArrayList<String>();
        FakeScreens.B forced;
        int k, observes;
        long clock;

        Frames add(FakeScreens.B... bs) {
            for (FakeScreens.B b : bs) frames.add(b);
            return this;
        }

        @Override public UiTree observe(UiTree.Ids ids) {
            observes++;
            clock += 10;
            FakeScreens.B b = forced != null ? forced : frames.get(Math.min(k++, frames.size() - 1));
            return b.ui(ids);
        }

        @Override public boolean tap(UiTree.El e) {
            log.add("tap " + (e == null ? "null" : e.label + "@" + e.cx() + "," + e.cy()));
            if (e != null && onTap.containsKey(e.label)) forced = onTap.get(e.label);
            return e != null;
        }

        @Override public boolean longPress(UiTree.El e, int ms) {
            log.add("long " + e.label);
            return true;
        }

        @Override public String type(UiTree.El e, String text) {
            log.add("type " + text);
            return text;
        }

        @Override public boolean scroll(UiTree.El e, int dir) {
            return false;
        }

        @Override public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
            return true;
        }

        @Override public boolean key(String key) {
            log.add("key " + key);
            return true;
        }

        @Override public boolean launch(String pkg) {
            return true;
        }

        @Override public void waitIdle(int idleMs, int timeoutMs) {
            clock += idleMs;
        }

        @Override public String describeScreen() {
            return null;
        }

        @Override public long now() {
            return clock;
        }

        @Override public void sleep(long ms) {
            clock += ms;
        }
    }

    static PhoneAgent agent(PhoneAgent.Driver d, PhoneAgent.Planner p, boolean labels) {
        PhoneAgent a = new PhoneAgent(d, p, new FakeScreens.Host());
        a.labels = labels;
        final int[] n = {0};
        a.snapIds = new A11yLabeler.Ids() {
            @Override public String next() {
                return "S" + (++n[0]);
            }
        };
        return a;
    }

    static FakeScreens.B shop(String act) {
        return FakeScreens.screen("com.shop", act).button("加入购物车", "com.shop:id/add_cart").button("客服");
    }

    @Test public void modelSeesLabelsAndSnapshotAndTheAddendum() {
        Frames d = new Frames().add(shop("com.shop.Detail"));
        FakeScreens.Script p = new FakeScreens.Script("{\"done\":\"看完了\"}");
        agent(d, p, true).run("看看", null);
        assertTrue(p.users.get(0), p.users.get(0).contains("快照 S1"));
        assertTrue(p.users.get(0), p.users.get(0).contains("按钮「加入购物车」"));
        assertTrue(p.systems.get(0).contains("【精准标签】"));
        // 关着：原口径，没有快照
        FakeScreens.Script p0 = new FakeScreens.Script("{\"done\":\"看完了\"}");
        agent(new Frames().add(shop("com.shop.Detail")), p0, false).run("看看", null);
        assertFalse(p0.users.get(0).contains("快照"));
        assertFalse(p0.systems.get(0).contains("【精准标签】"));
    }

    @Test public void tapIsRefusedWhenThePageChangedBeforeExecution() {
        Frames d = new Frames().add(shop("com.shop.Detail"), shop("com.shop.Cart"));
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:加入购物车}}]}", "{\"done\":\"好\"}");
        PhoneAgent a = agent(d, p, true);
        PhoneAgent.Result r = a.run("加购", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertTrue("没有按旧坐标点：" + d.log, d.log.isEmpty());
        assertEquals(1, a.bindRefused);
        assertTrue(r.trace.toString(), r.trace.toString().contains("bind PAGE_CHANGED snap=S1"));
        String second = p.users.get(1);
        assertTrue(second, second.contains("STALE_ELEMENT"));
        assertTrue(second, second.contains("页面从 Detail 换成了 Cart"));
        assertTrue("新界面交回模型", second.contains("Cart · 快照 S2"));
    }

    @Test public void tapIsRefusedWhenTheNodeIsGone() {
        Frames d = new Frames().add(shop("com.shop.Detail"), FakeScreens.screen("com.shop", "com.shop.Detail").button("客服"));
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:加入购物车}},{\"do\":\"tap\",\"id\":{id:客服}}]}", "{\"done\":\"好\"}");
        PhoneAgent a = agent(d, p, true);
        PhoneAgent.Result r = a.run("加购", null);
        assertTrue(d.log.toString(), d.log.isEmpty());
        assertTrue(r.trace.toString(), r.trace.toString().contains("bind NODE_GONE"));
        assertTrue(p.users.get(1), p.users.get(1).contains("执行了 0/2 步"));
    }

    @Test public void movedNodeWithSameKeyIsTappedAtItsNewPlace() {
        FakeScreens.B moved = FakeScreens.screen("com.shop", "com.shop.Detail").text("限时优惠横幅");
        moved.button("加入购物车", "com.shop:id/add_cart").button("客服");
        Frames d = new Frames().add(shop("com.shop.Detail"), moved);
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:加入购物车}}]}", "{\"done\":\"好\"}");
        PhoneAgent a = agent(d, p, true);
        a.run("加购", null);
        assertEquals(1, d.log.size());
        UiTree now = A11yLabeler.attach(moved.ui(null), "x", 0);
        UiTree.El el = now.findText("加入购物车");
        assertEquals("按新位置点（不是旧坐标）", "tap 加入购物车@" + el.cx() + "," + el.cy(), d.log.get(0));
        assertEquals(0, a.bindRefused);
    }

    @Test public void laterStepInABatchIsBoundToo() {
        FakeScreens.B detail = shop("com.shop.Detail");
        FakeScreens.B cart = FakeScreens.screen("com.shop", "com.shop.Cart").button("客服").button("结算");
        Frames d = new Frames().add(detail);
        d.onTap.put("加入购物车", cart);
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:加入购物车}},{\"do\":\"tap\",\"id\":{id:客服}}]}", "{\"done\":\"好\"}");
        PhoneAgent a = agent(d, p, true);
        PhoneAgent.Result r = a.run("加购", null);
        assertEquals("第二步对着的是详情页的客服，页面换了就不点：" + d.log, 1, d.log.size());
        assertTrue(r.trace.toString(), r.trace.toString().contains("bind PAGE_CHANGED"));
    }

    @Test public void payFinalButtonStillNeedsTheUserWithLabelsOn() {
        FakeScreens.B pay = FakeScreens.screen("com.shop", "com.shop.Pay").text("实付 ¥99.00").button("立即支付", "com.shop:id/pay");
        Frames d = new Frames().add(pay);
        FakeScreens.Script p = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:立即支付}}]}", "{\"done\":\"停在付款页\"}");
        PhoneAgent a = agent(d, p, true);
        PhoneAgent.Result r = a.run("付款", null);
        assertTrue(d.log.toString(), d.log.isEmpty());
        assertTrue(r.trace.toString(), r.trace.toString().contains("pay_final_user_tap"));
    }

    @Test public void labelsOffKeepsTheOldBehaviourAndObserveCount() {
        Frames on = new Frames().add(shop("com.shop.Detail"));
        Frames off = new Frames().add(shop("com.shop.Detail"));
        agent(off, new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:客服}}]}", "{\"done\":\"好\"}"), false).run("x", null);
        agent(on, new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:客服}}]}", "{\"done\":\"好\"}"), true).run("x", null);
        assertEquals(off.log, on.log);
        assertEquals("开了只多一次执行前重读", off.observes + 1, on.observes);
    }
}
