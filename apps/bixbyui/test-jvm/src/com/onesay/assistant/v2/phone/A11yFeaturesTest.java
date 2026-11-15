package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.a11ylabel.A11yLabeler;
import com.onesay.assistant.v2.a11ylabel.ButtonNumbers;
import com.onesay.assistant.v2.a11ylabel.LabelSelector;
import com.onesay.assistant.v2.a11ylabel.LabelTap;
import com.onesay.assistant.v2.a11ylabel.ScreenLease;
import com.onesay.assistant.v2.fastscreen.FsTree;

import org.json.JSONObject;
import org.junit.Test;

/** ★ a11y-labels：按标签找控件（快捷指令 / 定时步骤）、「显示按钮」/「N号」、屏幕租约、回放按键找、剪贴板粘贴。 */
public class A11yFeaturesTest {

    static final int C = FakeScreens.C;

    static UiTree lab(FakeScreens.B b, String snap, long now) {
        return A11yLabeler.attach(b.ui(new UiTree.Ids()), snap, now);
    }

    static FakeScreens.B chat() {
        FakeScreens.B b = FakeScreens.screen("com.example.chat", "com.example.chat.ChatActivity")
                .node("android.widget.ImageView", "", "com.example.chat:id/iv_back", C)
                .text("张三")
                .edit("说点什么", "com.example.chat:id/input")
                .button("发送", "com.example.chat:id/btn_send")
                .node("android.widget.ImageView", "", "", C)
                .button("禁用的", "");
        b.t.nodes.get(5).desc = "表情";
        b.t.nodes.get(6).flags = FsTree.F_CLICK | FsTree.F_VISIBLE;   // 没有 F_ENABLED = 不可用
        return b;
    }

    // ------------------------------------------------------------------ 按标签找

    @Test public void selectorFindsByTextDescAndRidWithoutCoordinates() throws Exception {
        UiTree u = lab(chat(), "S1", 0);
        assertEquals("发送", LabelSelector.fromArgs(new JSONObject("{\"target\":\"发送\"}")).find(u).el.label);
        assertEquals("表情", LabelSelector.fromArgs(new JSONObject("{\"target\":\"表情\",\"by\":\"desc\"}")).find(u).el.label);
        assertEquals("发送", LabelSelector.fromArgs(new JSONObject("{\"target\":\"btn_send\",\"by\":\"rid\"}")).find(u).el.label);
        assertEquals("全限定资源 id 也认", "发送", LabelSelector.fromArgs(new JSONObject("{\"target\":\"com.example.chat:id/btn_send\",\"by\":\"rid\"}")).find(u).el.label);
        assertEquals("推断名（无文字图标）按字也找得到", "返回", LabelSelector.fromArgs(new JSONObject("{\"target\":\"返回\"}")).find(u).el.label);
        assertEquals("包含匹配", "说点什么", LabelSelector.fromArgs(new JSONObject("{\"target\":\"说点\"}")).find(u).el.label);
        assertFalse("exact 时不包含匹配", LabelSelector.fromArgs(new JSONObject("{\"target\":\"说点\",\"exact\":true}")).find(u).ok());
        assertFalse("只找真实可点：纯文字不算", LabelSelector.fromArgs(new JSONObject("{\"target\":\"张三\"}")).find(u).ok());
        assertFalse("不可用的不算", LabelSelector.fromArgs(new JSONObject("{\"target\":\"禁用的\"}")).find(u).ok());
        assertFalse("按描述找不会命中屏上文字", LabelSelector.fromArgs(new JSONObject("{\"target\":\"发送\",\"by\":\"desc\"}")).find(u).ok());
        assertFalse("角色不符", LabelSelector.fromArgs(new JSONObject("{\"target\":\"发送\",\"role\":\"输入框\"}")).find(u).ok());
        LabelSelector.Match m = LabelSelector.fromArgs(new JSONObject("{\"target\":\"发送\",\"app\":\"com.other\"}")).find(u);
        assertFalse(m.ok());
        assertTrue(m.error, m.error.contains("前台不是"));
        assertNull(LabelSelector.fromArgs(new JSONObject("{}")));
        // 写法往返
        LabelSelector s = LabelSelector.fromArgs(new JSONObject("{\"target\":\"btn_send\",\"by\":\"rid\",\"role\":\"按钮\"}"));
        LabelSelector back = LabelSelector.fromArgs(s.toArgs());
        assertEquals(s.by, back.by);
        assertEquals(s.value, back.value);
        assertEquals(s.role, back.role);
        // 早期写法：text / desc / rid 直接当键
        assertEquals("发送", LabelSelector.fromArgs(new JSONObject("{\"rid\":\"btn_send\"}")).find(u).el.label);
    }

    @Test public void selectorStillFindsTheButtonAfterItMoved() throws Exception {
        FakeScreens.B b = FakeScreens.screen("com.example.chat", "com.example.chat.ChatActivity").text("新横幅").text("又一条");
        b.button("发送", "com.example.chat:id/btn_send");
        UiTree u = lab(b, "S2", 0);
        UiTree.El el = LabelSelector.fromArgs(new JSONObject("{\"target\":\"发送\"}")).find(u).el;
        assertEquals(u.findText("发送").cy(), el.cy());
    }

    @Test public void legacyCoordinatesHitTheControlThereOrFailNeverBlindTap() throws Exception {
        UiTree u = lab(chat(), "S1", 0);
        UiTree.El send = u.findText("发送");
        int x = com.onesay.assistant.plug.screen.core.UiElement.normalize(send.cx(), u.screenW), y = com.onesay.assistant.plug.screen.core.UiElement.normalize(send.cy(), u.screenH);
        LabelSelector old = LabelSelector.fromArgs(new JSONObject("{\"x\":" + x + ",\"y\":" + y + "}"));
        assertTrue(old.legacy());
        LabelSelector.Match m = old.find(u);
        assertSame(send, m.el);
        assertNotNull("建议迁移成按资源 id 找", m.migrated);
        assertEquals(LabelSelector.BY_RID, m.migrated.by);
        assertEquals("btn_send", m.migrated.value);
        // 那个位置没有可点控件（「张三」是纯文字）→ 失败，不点坐标
        UiTree.El txt = u.findText("张三");
        LabelSelector.Match miss = LabelSelector.fromArgs(new JSONObject("{\"x\":500,\"y\":" + com.onesay.assistant.plug.screen.core.UiElement.normalize(txt.cy(), u.screenH) + "}")).find(u);
        assertFalse(miss.ok());
        assertTrue(miss.error, miss.error.contains("没有按坐标盲点"));
        assertNull("坐标越界不算", LabelSelector.fromArgs(new JSONObject("{\"x\":1200,\"y\":5}")));
    }

    @Test public void labelTapRefusesPayAndSendButtons() throws Exception {
        UiTree u = lab(chat(), "S1", 0);
        LabelTap.Plan p = LabelTap.decide(new JSONObject("{\"target\":\"发送\"}"), u, ButtonNumbers.DEFAULT_GUARD);
        assertTrue(p.userMustTap);
        assertFalse(p.ok());
        assertTrue(p.error, p.error.contains("要你自己点"));
        UiTree pay = lab(FakeScreens.screen("com.shop", "com.shop.Pay").text("实付 ¥99.00").button("立即支付", "com.shop:id/pay"), "S2", 0);
        p = LabelTap.decide(new JSONObject("{\"target\":\"立即支付\"}"), pay, ButtonNumbers.DEFAULT_GUARD);
        assertTrue(p.userMustTap);
        assertTrue(p.error, p.error.contains("付款"));
        p = LabelTap.decide(new JSONObject("{\"target\":\"返回\"}"), u, ButtonNumbers.DEFAULT_GUARD);
        assertTrue(p.ok());
        assertEquals("返回", p.target.label);
        p = LabelTap.decide(new JSONObject("{\"target\":\"不存在的按钮\"}"), u, ButtonNumbers.DEFAULT_GUARD);
        assertFalse(p.ok());
        assertTrue(p.error, p.error.contains("没找到"));
        assertFalse(LabelTap.decide(new JSONObject("{}"), u, null).ok());
        assertFalse(LabelTap.decide(new JSONObject("{\"target\":\"返回\"}"), null, null).ok());
    }

    // ------------------------------------------------------------------ 显示按钮 / N号

    @Test public void showAndHideAndPickPhrases() {
        for (String s : new String[]{"显示按钮", "显示按钮。", "请显示按钮", "帮我显示一下按钮", "给按钮编号", "标出按钮", "显示编号", "把按钮标出来", "标出可点的地方"})
            assertTrue(s, ButtonNumbers.isShow(s));
        for (String s : new String[]{"按钮", "显示一下今天的天气", "把空调按钮打开", "编号是多少", "怎么显示按钮的编号呢我想知道"})
            assertFalse(s, ButtonNumbers.isShow(s));
        for (String s : new String[]{"隐藏编号", "关掉按钮编号", "不用编号了", "取消编号", "收起按钮"}) assertTrue(s, ButtonNumbers.isHide(s));
        assertFalse(ButtonNumbers.isHide("隐藏应用"));
        assertEquals(3, ButtonNumbers.pick("3号", 10));
        assertEquals(3, ButtonNumbers.pick("点3号", 10));
        assertEquals(3, ButtonNumbers.pick("三号", 10));
        assertEquals(12, ButtonNumbers.pick("点十二号", 20));
        assertEquals(23, ButtonNumbers.pick("二十三号按钮", 30));
        assertEquals(2, ButtonNumbers.pick("第二个", 5));
        assertEquals(2, ButtonNumbers.pick("两号", 5));
        assertEquals(7, ButtonNumbers.pick("按 7 号吧", 9));
        assertEquals(ButtonNumbers.OUT_OF_RANGE, ButtonNumbers.pick("12号", 5));
        assertEquals(ButtonNumbers.NONE, ButtonNumbers.pick("3号线怎么走", 10));
        assertEquals(ButtonNumbers.NONE, ButtonNumbers.pick("明天3号开会", 10));
        assertEquals(ButtonNumbers.NONE, ButtonNumbers.pick("三", 10));
        assertEquals(ButtonNumbers.NONE, ButtonNumbers.pick("0号", 10));
        assertEquals(ButtonNumbers.NONE, ButtonNumbers.pick(null, 10));
    }

    @Test public void numbersOnlyRealTappableNodesInReadingOrder() {
        UiTree u = lab(chat(), "S1", 0);
        ButtonNumbers.Session s = ButtonNumbers.open(u, 0);
        // 返回、输入框、发送、表情；「张三」是文字、「禁用的」不可用，都不标
        assertEquals(4, s.size());
        assertEquals("返回", s.get(1).label);
        assertEquals("说点什么", s.get(2).label);
        assertEquals("发送", s.get(3).label);
        assertEquals("表情", s.get(4).label);
        assertNull(s.get(5));
        assertTrue(ButtonNumbers.openSay(s), ButtonNumbers.openSay(s).contains("一共 4 个"));
        int[][] pos = ButtonNumbers.badgePositions(s, 30);
        assertEquals(4, pos.length);
    }

    @Test public void pageWithoutControlsSaysSoAndDrawsNoFakeGrid() {
        UiTree u = lab(FakeScreens.screen("com.reader", "com.reader.Page").text("第一段").text("第二段"), "S1", 0);
        ButtonNumbers.Session s = ButtonNumbers.open(u, 0);
        assertEquals(0, s.size());
        assertTrue(ButtonNumbers.openSay(s).contains("没有可点的按钮"));
        assertEquals(0, ButtonNumbers.badgePositions(s, 30).length);
        assertNotNull(ButtonNumbers.choose(s, 1, u, 0, null).say);
        assertTrue(ButtonNumbers.openSay(ButtonNumbers.open(null, 0)).contains("读不到"));
        assertEquals("没贴标签不标", 0, ButtonNumbers.open(FakeScreens.screen("p", "a").button("x").ui(null), 0).size());
    }

    @Test public void pickIsBoundToTheNumberedSnapshot() {
        ButtonNumbers.Session s = ButtonNumbers.open(lab(chat(), "S1", 1000), 1000);
        // 同一界面：点到「返回」（新树里的那个）
        UiTree fresh = lab(chat(), "S2", 2000);
        ButtonNumbers.Pick p = ButtonNumbers.choose(s, 1, fresh, 2000, ButtonNumbers.DEFAULT_GUARD);
        assertTrue(p.ok());
        assertSame(fresh.findText("返回"), p.target);
        // 过期（> 60 秒）：重标
        p = ButtonNumbers.choose(s, 1, fresh, 1000 + ButtonNumbers.TTL_MS + 1, ButtonNumbers.DEFAULT_GUARD);
        assertFalse(p.ok());
        assertTrue(p.reopen);
        // 换了页面：重标，不按旧位置点
        FakeScreens.B other = chat();
        other.t.activity = "com.example.chat.ProfileActivity";
        p = ButtonNumbers.choose(s, 1, lab(other, "S3", 2000), 2000, ButtonNumbers.DEFAULT_GUARD);
        assertTrue(p.reopen);
        assertNull(p.target);
        // 编号不存在
        p = ButtonNumbers.choose(s, 9, fresh, 2000, ButtonNumbers.DEFAULT_GUARD);
        assertFalse(p.reopen);
        assertTrue(p.say, p.say.contains("只有 1 到 4 号"));
        // 发送按钮照标，但要本人点
        p = ButtonNumbers.choose(s, 3, fresh, 2000, ButtonNumbers.DEFAULT_GUARD);
        assertTrue(p.userMustTap);
        assertFalse(p.ok());
        assertTrue(p.say, p.say.contains("要你自己点"));
        // 没开编号层
        assertTrue(ButtonNumbers.choose(null, 1, fresh, 2000, null).say.contains("显示按钮"));
    }

    // ------------------------------------------------------------------ 屏幕租约

    @Test public void screenLeaseIsExclusiveRenewableAndExpires() {
        final long[] now = {0};
        ScreenLease l = new ScreenLease(new ScreenLease.Clock() {
            @Override public long now() {
                return now[0];
            }
        });
        ScreenLease.Lease voice = l.tryAcquire("语音任务", 1000);
        assertNotNull(voice);
        assertNull("定时任务不抢屏", l.tryAcquire("定时任务", 1000));
        assertEquals("语音任务", l.holder());
        assertTrue(l.busySay().contains("语音任务"));
        now[0] = 900;
        assertTrue(l.renew(voice, 1000));
        now[0] = 1500;
        assertNull("续期后还没到期", l.tryAcquire("定时任务", 1000));
        now[0] = 2000;
        ScreenLease.Lease sched = l.tryAcquire("定时任务", 1000);
        assertNotNull("到期自动作废（持有方忘了释放也不会锁死）", sched);
        assertEquals(1, l.expired);
        assertFalse("旧租约续不了", l.renew(voice, 1000));
        l.release(voice);
        assertEquals("放别人的租约无事发生", "定时任务", l.holder());
        l.release(sched);
        assertNull(l.holder());
        assertEquals("", l.busySay());
        assertNotNull(l.tryAcquire(null, 10));
        assertEquals(2, l.refused);
    }

    @Test public void phoneTaskTakesAndReleasesTheGlobalLease() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/phone/PhoneHooks.java")), "UTF-8");
        assertTrue(src.contains("ScreenLease.get().tryAcquire(\"手机操作任务\""));
        assertTrue(src.contains("ScreenLease.get().release(lease);"));
        int acquire = src.indexOf("tryAcquire(\"手机操作任务\""), fin = src.indexOf("} finally {", acquire), rel = src.indexOf("ScreenLease.get().release(lease);\n            sLast", acquire);
        assertTrue("释放在 finally 里", fin > acquire && rel > fin);
    }

    // ------------------------------------------------------------------ 回放：按节点键找（旧路径兼容）

    @Test public void replayTargetPrefersNodeKeyAndOldPathsStillWork() throws Exception {
        UiTree rec = lab(FakeScreens.screen("p.k", "a").button("关注", "p.k:id/follow"), "S1", 0);
        SmartReplay.Target g = SmartReplay.target(rec.findText("关注"), rec);
        assertTrue(g.nk.length() > 0);
        // 文字变了（关注 → 已关注），键没变：按键找到
        UiTree live = lab(FakeScreens.screen("p.k", "a").button("已关注", "p.k:id/follow"), "S2", 0);
        assertEquals("已关注", SmartReplay.locate(g, live).el.label);
        // 旧路径（JSON 里没有 nk）照原规则
        JSONObject j = g.json();
        assertTrue(j.has("nk"));
        j.remove("nk");
        SmartReplay.Target old = SmartReplay.Target.of(j);
        assertEquals("", old.nk);
        assertNull("旧路径没有键：文字变了按原规则找不到（这正是加键的用处）", SmartReplay.locate(old, live).el);
        UiTree same = lab(FakeScreens.screen("p.k", "a").button("关注", "p.k:id/follow"), "S3", 0);
        assertEquals("旧路径在原样的界面上照常找到", "关注", SmartReplay.locate(old, same).el.label);
        // 没贴标签的录制不存 nk
        UiTree plain = FakeScreens.screen("p.k", "a").button("关注", "p.k:id/follow").ui(null);
        assertEquals("", SmartReplay.target(plain.findText("关注"), plain).nk);
        assertFalse(SmartReplay.target(plain.findText("关注"), plain).json().has("nk"));
    }

    // ------------------------------------------------------------------ 剪贴板粘贴

    @Test public void pasteFallbackRestoresClipboardAndMarksSensitive() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/phone/FastScreenDriver.java")), "UTF-8");
        int legacy = src.indexOf("// 退回：剪贴板 + 粘贴");
        assertTrue(legacy > 0);
        assertTrue("原口径的粘贴也走 ClipboardGuard（粘完还原）", src.indexOf("ClipboardGuard.pasteAndRestore", legacy) > legacy
                && src.indexOf("ClipboardGuard.pasteAndRestore", legacy) < src.indexOf("String typeVerified"));
        assertTrue(src.contains("\"android.content.extra.IS_SENSITIVE\""));
        assertTrue(src.contains("cd.getDescription().setExtras(ex)"));
        final String[] clip = {"用户原来的剪贴板"};
        final java.util.List<String> pasted = new java.util.ArrayList<String>();
        com.onesay.assistant.plug.action.core.ClipboardGuard.Result r = com.onesay.assistant.plug.action.core.ClipboardGuard.pasteAndRestore(
                new com.onesay.assistant.plug.action.core.ClipboardGuard.ClipboardPort() {
                    @Override public String get() {
                        return clip[0];
                    }

                    @Override public boolean set(String t) {
                        clip[0] = t;
                        return true;
                    }
                }, "北京天气", new com.onesay.assistant.plug.action.core.ClipboardGuard.Paste() {
                    @Override public boolean paste() {
                        pasted.add(clip[0]);
                        return true;
                    }
                });
        assertTrue(r.ok);
        assertTrue(r.restored);
        assertEquals("北京天气", pasted.get(0));
        assertEquals("用户原来的剪贴板", clip[0]);
    }
}
