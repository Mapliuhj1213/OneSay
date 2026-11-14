package com.onesay.plan.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * [borrow-1112] 待办强提醒（思路来源 涛声 TaoSheng，MIT）：记录本、响铃策略、按键、给助手的两个函数、表结构升级与接线。
 */
public class StrongAlertTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar k = Calendar.getInstance(TZ);
        k.clear();
        k.set(y, mo - 1, d, h, mi, 0);
        return k.getTimeInMillis();
    }

    static final long NOW = at(2026, 11, 12, 14, 0);
    static final long MIN = 60000L;

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("plan.root", "."), rel).toPath()), Charset.forName("UTF-8"));
    }

    static Row todo(long id, String title, long due, long done) {
        return new Row().with("_id", id).with("title", title).with("due_at", due).with("done_at", done).with("remind", 1L);
    }

    // ------------------------------------------------------------------ 记录本

    @Test public void ringSnoozeStopLifecycle() {
        StrongAlert.Book b = new StrongAlert.Book();
        b.ring(5, "交周报", NOW, NOW);
        assertEquals(StrongAlert.RINGING, b.get(5).state);
        assertSame(b.get(5), b.ringing());
        b.snooze(5, NOW + MIN, 10, false);
        assertEquals(StrongAlert.SNOOZED, b.get(5).state);
        assertEquals(NOW + 11 * MIN, b.get(5).snoozeUntil);
        assertNull("稍后中不算正在响", b.ringing());
        assertEquals(NOW + 11 * MIN, b.nextSnoozeAt(NOW));
        assertEquals("还没到", 0, b.snoozesDue(NOW, NOW + 10 * MIN).size());
        assertEquals("到点了", 1, b.snoozesDue(NOW, NOW + 11 * MIN).size());
        b.ring(5, "交周报", NOW, NOW + 11 * MIN);
        assertEquals("同一轮再响：第一次提醒时刻不变", NOW, b.get(5).firstAt);
        b.stop(5, NOW + 12 * MIN, StrongAlert.STOPPED);
        assertEquals(StrongAlert.STOPPED, b.get(5).state);
        assertEquals(-1, b.nextSnoozeAt(NOW));
        b.ring(5, "交周报", NOW, NOW + 60 * MIN);
        assertEquals("停了之后再响 = 新的一轮", NOW + 60 * MIN, b.get(5).firstAt);
    }

    @Test public void notifiedDoesNotOverrideRinging() {
        StrongAlert.Book b = new StrongAlert.Book();
        b.ring(1, "A", NOW, NOW);
        b.notified(1, "A", NOW, NOW + MIN);
        assertEquals(StrongAlert.RINGING, b.get(1).state);
        b.notified(2, "B", NOW, NOW);
        assertEquals(StrongAlert.NOTIFIED, b.get(2).state);
        assertFalse(b.get(2).strong);
    }

    @Test public void autoSnoozeIsCapped() {
        StrongAlert.Book b = new StrongAlert.Book();
        b.ring(1, "A", NOW, NOW);
        for (int i = 0; i < StrongAlert.MAX_AUTO_SNOOZE; i++) {
            assertEquals("第 " + (i + 1) + " 次没人管 → 自动稍后", StrongAlert.Timeout.SNOOZE, StrongAlert.onTimeout(b.get(1)));
            b.snooze(1, NOW, 10, true);
            b.ring(1, "A", NOW, NOW + 11 * MIN);
        }
        assertEquals("自动稍后 3 次后 → 停（不无限循环）", StrongAlert.Timeout.STOP, StrongAlert.onTimeout(b.get(1)));
        b.snooze(1, NOW, 10, false);
        assertEquals("手动稍后不计次", 3, b.get(1).autoSnoozes);
        assertEquals(StrongAlert.Timeout.STOP, StrongAlert.onTimeout(null));
    }

    @Test public void keysAndSnoozeMinutes() {
        assertEquals(StrongAlert.Key.SNOOZE, StrongAlert.key(24));
        assertEquals(StrongAlert.Key.SNOOZE, StrongAlert.key(25));
        assertEquals(StrongAlert.Key.SNOOZE, StrongAlert.key(164));
        assertEquals(StrongAlert.Key.STOP, StrongAlert.key(26));
        assertEquals(StrongAlert.Key.NONE, StrongAlert.key(4));
        assertEquals(10, StrongAlert.DEFAULT_SNOOZE_MIN);
        assertEquals(10, StrongAlert.snoozeMin(0));
        assertEquals(10, StrongAlert.snoozeMin(61));
        assertEquals(5, StrongAlert.snoozeMin(5));
    }

    @Test public void serializeAndPrune() {
        StrongAlert.Book b = new StrongAlert.Book();
        b.ring(1, "A\"引号", NOW, NOW);
        b.snooze(1, NOW, 10, true);
        b.notified(2, "B", NOW, NOW - 25 * 3600000L);
        StrongAlert.Book c = StrongAlert.Book.parse(b.serialize());
        assertEquals(2, c.entries.size());
        assertEquals("A\"引号", c.get(1).title);
        assertEquals(1, c.get(1).autoSnoozes);
        assertEquals(b.get(1).snoozeUntil, c.get(1).snoozeUntil);
        assertEquals("过期的不活跃记录删掉", 1, c.prune(NOW));
        assertNotNull("稍后中的留着", c.get(1));
        assertEquals("坏串 → 空本", 0, StrongAlert.Book.parse("{x").entries.size());
        StrongAlert.Book big = new StrongAlert.Book();
        for (int i = 1; i <= 60; i++) big.notified(i, "t" + i, 0, NOW + i);
        big.prune(NOW);
        assertEquals(StrongAlert.MAX_ENTRIES, big.entries.size());
        assertNull("超出条数删最旧的", big.get(1));
    }

    // ------------------------------------------------------------------ 给助手

    @Test public void recentForAssistant() throws Exception {
        StrongAlert.Book b = new StrongAlert.Book();
        b.notified(1, "旧的", 0, NOW - 40 * MIN);            // 超出 30 分钟
        b.notified(2, "刚提醒", 0, NOW - 5 * MIN);
        b.ring(3, "正在响", NOW, NOW - 1 * MIN);
        b.notified(4, "已完成", 0, NOW - 2 * MIN);
        b.notified(5, "已删除", 0, NOW - 2 * MIN);
        b.ring(6, "稍后中", NOW, NOW - 50 * MIN);
        b.snooze(6, NOW - 49 * MIN, 60, false);               // 稍后到 NOW+11，超出窗口但仍算正在提醒
        b.notified(7, "停了的", 0, NOW - 3 * MIN);
        b.stop(7, NOW - 2 * MIN, StrongAlert.STOPPED);
        Map<Long, Row> todos = new HashMap<Long, Row>();
        for (long id : new long[]{1, 2, 3, 6, 7}) todos.put(id, todo(id, "t" + id, 0, 0));
        todos.put(4L, todo(4, "t4", 0, NOW - MIN));
        List<StrongAlert.Entry> l = StrongAlert.recent(b, todos, NOW, StrongAlert.windowMs(30));
        long[] ids = new long[l.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = l.get(i).todoId;
        assertArrayEquals("正在响的最前，其余按最近提醒倒序；不含已完成 / 已删 / 窗口外", new long[]{3, 7, 2, 6}, ids);
        JSONArray a = new JSONArray(StrongAlert.recentJson(l, todos));
        JSONObject first = a.getJSONObject(0);
        assertEquals(3, first.getLong("id"));
        assertEquals("t3", first.getString("title"));
        assertEquals(StrongAlert.RINGING, first.getString("state"));
        assertTrue(first.getBoolean("strong"));
        for (String k : new String[]{"id", "title", "due_at", "state", "reminded_at", "first_at", "strong", "snooze_until"}) assertTrue(k, first.has(k));
        assertEquals("窗口放大到 60 分钟就有旧的", 5, StrongAlert.recent(b, todos, NOW, StrongAlert.windowMs(60)).size());

        assertTrue(StrongAlert.completable(b, todos, 3, NOW, StrongAlert.windowMs(30)));
        assertTrue(StrongAlert.completable(b, todos, 7, NOW, StrongAlert.windowMs(30)));
        assertFalse("不在最近提醒里的不让勾（防一句「发了」勾错）", StrongAlert.completable(b, todos, 1, NOW, StrongAlert.windowMs(30)));
        assertFalse("从没提醒过的", StrongAlert.completable(b, todos, 99, NOW, StrongAlert.windowMs(30)));
        assertEquals(30 * MIN, StrongAlert.windowMs(0));
        assertEquals(120 * MIN, StrongAlert.windowMs(999));
        assertEquals(5 * MIN, StrongAlert.windowMs(5));
    }

    @Test public void remindLinkContract() {
        assertEquals("listRemindedTasks", RemindLink.F_LIST_REMINDED);
        assertEquals("completeRemindedTask", RemindLink.F_COMPLETE_REMINDED);
        assertTrue(RemindLink.isRemindMethod(RemindLink.F_LIST_REMINDED));
        assertTrue(RemindLink.isRemindMethod(RemindLink.F_COMPLETE_REMINDED));
        assertFalse(RemindLink.isRemindMethod("ping"));
        assertFalse(RemindLink.isRemindMethod(null));
        assertEquals("结果码与个人版伴侣连接一致", Arrays.asList(1, 2, 3, 4),
                Arrays.asList(RemindLink.RC_OK, RemindLink.RC_BAD_ARGS, RemindLink.RC_NOT_FOUND, RemindLink.RC_UNKNOWN));
        // 门卫：root / adb 拒绝，同证书放行（开源版数据接口的门就是提醒接口的门）
        assertFalse(AccessPolicy.allowed(AccessPolicy.decide(0, 10100, true, true)));
        assertFalse(AccessPolicy.allowed(AccessPolicy.decide(2000, 10100, true, true)));
        assertFalse(AccessPolicy.allowed(AccessPolicy.decide(10200, 10100, false, false)));
        assertTrue(AccessPolicy.allowed(AccessPolicy.decide(10200, 10100, false, true)));
    }

    // ------------------------------------------------------------------ 排程与表结构

    @Test public void remindersMarkStrongTodos() {
        Reminders.Input in = new Reminders.Input();
        in.todos.add(todo(1, "普通", NOW + 10 * MIN, 0));
        in.todos.add(todo(2, "强提醒", NOW + 20 * MIN, 0).with("strong", 1L));
        List<Reminders.Item> l = Reminders.between(in, NOW, NOW + 60 * MIN, TZ);
        assertEquals(2, l.size());
        assertFalse(l.get(0).strong);
        assertTrue(l.get(1).strong);
        in.todos.get(1).put("done_at", NOW);
        assertEquals("完成的强提醒不排", 1, Reminders.between(in, NOW, NOW + 60 * MIN, TZ).size());
        Reminders.Input ev = new Reminders.Input();
        ev.events.add(new Row().with("_id", 1L).with("title", "会").with("start_at", NOW + 30 * MIN).with("strong", 1L));
        assertFalse("强提醒只对待办", Reminders.between(ev, NOW, NOW + 60 * MIN, TZ).get(0).strong);
    }

    @Test public void schemaUpgradeAddsStrongWithDefaultZero() {
        Schema.Col c = Schema.TODOS.col("strong");
        assertNotNull(c);
        assertEquals(Schema.INT, c.type);
        assertEquals("0", c.dflt);
        assertTrue("助手 / 界面可写", c.writable);
        assertTrue(Schema.TODOS.createSql().contains("strong INTEGER DEFAULT 0"));
        HashSet<String> old = new HashSet<String>(Arrays.asList(Schema.TODOS.columnNames()));
        old.remove("strong");
        assertEquals("旧库（schema 3）只补这一列，旧行默认 0 = 不强提醒", Arrays.asList("ALTER TABLE todos ADD COLUMN strong INTEGER DEFAULT 0"), Schema.TODOS.addMissingSql(old));
        assertEquals(4, Contract.SCHEMA_VERSION);
    }

    @Test public void dueText() {
        assertEquals("截止 14:30", StrongAlert.dueText(at(2026, 11, 12, 14, 30), TZ));
        assertEquals("", StrongAlert.dueText(0, TZ));
    }

    // ------------------------------------------------------------------ 接线（读源码）

    @Test public void wiring() throws Exception {
        String rm = src("app/src/com/onesay/plan/remind/Remind.java");
        assertTrue("强提醒的待办到点去响铃", rm.contains("it.strong) { StrongRingService.ring("));
        assertTrue("稍后也排进同一个闹钟", rm.contains("nextSnoozeAt(0)"));
        assertTrue("勾掉 / 删除时在响就停", rm.indexOf("StrongRingService.cancelIfRinging(c, id)") > rm.indexOf("public static void dismiss("));
        String svc = src("app/src/com/onesay/plan/remind/StrongRingService.java");
        assertTrue("闹钟音频流", svc.contains("AudioAttributes.USAGE_ALARM"));
        // [borrow-1112] 并表同时提醒（2026-11-12 用户决定）：原断言 svc.contains("finish(ringingId, StrongAlert.STOPPED") → 电源键改为全部停止
        assertTrue("电源键（屏幕熄灭）= 全部停止", svc.contains("Intent.ACTION_SCREEN_OFF") && svc.contains("stopAll(StrongAlert.STOPPED)"));
        assertTrue("没人管有超时", svc.contains("StrongAlert.RING_TIMEOUT_MS") && svc.contains("StrongAlert.onTimeout("));
        assertTrue("整屏意图", svc.contains("setFullScreenIntent("));
        String act = src("app/src/com/onesay/plan/ui/StrongAlertActivity.java");
        // [borrow-1112] 并表同时提醒：原断言 act.contains("send(StrongRingService.A_SNOOZE)") → send 多了待办 id 参数，音量键传 0 = 全部稍后
        assertTrue("音量键 = 全部稍后", act.contains("StrongAlert.key(keyCode)") && act.contains("send(StrongRingService.A_SNOOZE, 0)"));
        assertTrue("有完成按钮", act.contains("\"完成\"") && act.contains("StrongRingService.A_DONE"));
        String man = src("app/AndroidManifest.xml");
        assertTrue("响铃服务不导出", Pattern.compile("StrongRingService\"[^>]*android:exported=\"false\"").matcher(man).find());
        assertTrue("整屏页不导出", Pattern.compile("StrongAlertActivity\"[^>]*android:exported=\"false\"", Pattern.DOTALL).matcher(man).find());
        assertTrue(man.contains("android.permission.FOREGROUND_SERVICE") && man.contains("android.permission.USE_FULL_SCREEN_INTENT"));
        // 给助手的接口：门卫（enforce → AccessPolicy）之后才转进来
        String pp = src("app/src/com/onesay/plan/data/PlanProvider.java");
        int door = pp.indexOf("Caller who = enforce();", pp.indexOf("public Bundle call("));
        int route = pp.indexOf("RemindLink.isRemindMethod(method)) return RemindCalls.call(");
        assertTrue("门卫在前、提醒接口在后", door > 0 && route > door);
        String rc = src("app/src/com/onesay/plan/data/RemindCalls.java");
        assertTrue("勾掉前先确认在最近提醒里", rc.indexOf("StrongAlert.completable(") > 0 && rc.indexOf("StrongAlert.completable(") < rc.indexOf("Remind.markDone(c, id)"));
        assertTrue("勾掉走同一条写入路径", rc.contains("Remind.markDone(c, id)"));
        String ed = src("app/src/com/onesay/plan/ui/Editors.java");
        assertTrue("编辑待办能开强提醒", ed.contains(".with(\"strong\", strong.isChecked() ? 1 : 0)"));
        for (String f : new String[]{"app/src/com/onesay/plan/remind/StrongRingService.java", "app/src/com/onesay/plan/ui/StrongAlertActivity.java",
                "app/src/com/onesay/plan/data/RemindCalls.java", "app/src/com/onesay/plan/core/StrongAlert.java"}) {
            String s = strings(src(f));
            assertFalse("新界面文字不含品牌字样 " + f, s.contains("Bixby") || s.contains("三星"));
        }
    }

    // ------------------------------------------------------------------ 并表同时提醒（2026-11-12 用户决定）

    @Test public void mergeSimultaneousAndLateArrivals() {
        StrongAlert.Book b = new StrongAlert.Book();
        b.ring(2, "交周报", NOW + 5 * MIN, NOW);
        b.ring(1, "回电话", NOW, NOW);                       // 同一时刻到点
        b.ring(3, "取快递", NOW + 2 * MIN, NOW + 2 * MIN);    // 正在响时又到一条
        List<StrongAlert.Entry> r = b.ringingAll();
        assertEquals("三条合并在同一次响铃里，谁也没被转成稍后", 3, r.size());
        assertEquals("按到点先后；同一时刻按截止时间", Arrays.asList(1L, 2L, 3L), ids(r));
        for (StrongAlert.Entry e : b.entries) assertEquals(StrongAlert.RINGING, e.state);
        assertEquals(-1, b.nextSnoozeAt(NOW));
        assertSame(b.get(1), b.ringing());
        assertTrue(b.anyRinging());
        assertEquals("3 条待办到点了", StrongAlert.ringTitle(r));
        assertEquals("回电话、交周报、取快递", StrongAlert.ringText(r));
        assertEquals("单条时通知照旧", "回电话", StrongAlert.ringTitle(r.subList(0, 1)));
        assertEquals("强提醒 · 音量键稍后，电源键停止", StrongAlert.ringText(r.subList(0, 1)));
    }

    @Test public void completeOneOthersKeepRinging() {
        StrongAlert.Book b = threeRinging();
        List<StrongAlert.Entry> left = b.complete(2, NOW + MIN);
        assertEquals("完成一条，其余继续响", Arrays.asList(1L, 3L), ids(left));
        assertEquals(StrongAlert.DONE, b.get(2).state);
        assertEquals(StrongAlert.RINGING, b.get(1).state);
        assertTrue("还有在响的 → 不停铃", b.anyRinging());
        assertEquals("不在响的再点完成不影响别的", 2, b.complete(2, NOW + MIN).size());
        b.complete(1, NOW + 2 * MIN);
        assertTrue(b.anyRinging());
        assertTrue("全部完成 → 停铃", b.complete(3, NOW + 3 * MIN).isEmpty());
        assertFalse(b.anyRinging());
        assertNull(b.ringing());
    }

    @Test public void snoozeAllAndStopAll() {
        StrongAlert.Book b = threeRinging();
        b.notified(9, "普通", 0, NOW);
        List<StrongAlert.Entry> s = b.snoozeAll(NOW + MIN, 10, false);
        assertEquals("音量键 = 全部稍后", 3, s.size());
        for (long id : new long[]{1, 2, 3}) {
            assertEquals(StrongAlert.SNOOZED, b.get(id).state);
            assertEquals(NOW + 11 * MIN, b.get(id).snoozeUntil);
            assertEquals("手动稍后不计次", 0, b.get(id).autoSnoozes);
        }
        assertEquals("普通提醒不受影响", StrongAlert.NOTIFIED, b.get(9).state);
        assertFalse(b.anyRinging());
        assertEquals("稍后到点一起回来，再次合并响", 3, b.snoozesDue(NOW, NOW + 11 * MIN).size());
        for (StrongAlert.Entry e : b.snoozesDue(NOW, NOW + 11 * MIN)) b.ring(e.todoId, e.title, e.dueAt, NOW + 11 * MIN);
        assertEquals(3, b.ringingAll().size());
        List<StrongAlert.Entry> st = b.stopAll(NOW + 12 * MIN, StrongAlert.STOPPED);
        assertEquals("电源键 / 停止 = 全部停止", 3, st.size());
        for (long id : new long[]{1, 2, 3}) assertEquals(StrongAlert.STOPPED, b.get(id).state);
        assertEquals(-1, b.nextSnoozeAt(NOW));
        assertTrue("没有在响的时候全部稍后 / 停止是空操作", b.snoozeAll(NOW, 10, false).isEmpty() && b.stopAll(NOW, StrongAlert.STOPPED).isEmpty());
    }

    @Test public void autoSnoozeCapIsPerEntry() {
        StrongAlert.Book b = new StrongAlert.Book();
        b.ring(1, "A", NOW, NOW);
        b.ring(2, "B", NOW, NOW + 2 * MIN);
        assertEquals("每条各自计时：最早到时的是先响的那条", NOW + 5 * MIN, b.nextTimeoutAt());
        assertEquals(Arrays.asList(1L), ids(b.timedOut(NOW + 5 * MIN)));
        assertEquals(Arrays.asList(1L, 2L), ids(b.timedOut(NOW + 7 * MIN)));
        // A 连续 3 次没人管自动稍后，B 一直是新来的
        long t = NOW;
        for (int i = 0; i < StrongAlert.MAX_AUTO_SNOOZE; i++) {
            assertEquals(StrongAlert.Timeout.SNOOZE, StrongAlert.onTimeout(b.get(1)));
            b.snooze(1, t + 5 * MIN, 10, true);
            t += 15 * MIN;
            b.ring(1, "A", NOW, t);
        }
        assertEquals("A 满 3 次 → 停", StrongAlert.Timeout.STOP, StrongAlert.onTimeout(b.get(1)));
        assertEquals("B 还是 0 次 → 照样自动稍后", 0, b.get(2).autoSnoozes);
        assertEquals(StrongAlert.Timeout.SNOOZE, StrongAlert.onTimeout(b.get(2)));
        b.stop(1, t + 5 * MIN, StrongAlert.MISSED);
        assertEquals("A 停了，B 继续响", Arrays.asList(2L), ids(b.ringingAll()));
        b.stop(2, t, StrongAlert.STOPPED);
        assertEquals(-1, b.nextTimeoutAt());
    }

    @Test public void listRemindedTasksReturnsEveryRingingOne() throws Exception {
        StrongAlert.Book b = new StrongAlert.Book();
        b.notified(5, "刚提醒", 0, NOW - 1 * MIN);
        b.ring(3, "后到的", NOW + 30 * MIN, NOW - 2 * MIN);
        b.ring(4, "先到的", NOW, NOW - 4 * MIN);
        b.ring(6, "同时到的", NOW + 10 * MIN, NOW - 4 * MIN);
        Map<Long, Row> todos = new HashMap<Long, Row>();
        for (long id : new long[]{3, 4, 5, 6}) todos.put(id, todo(id, "t" + id, 0, 0));
        List<StrongAlert.Entry> l = StrongAlert.recent(b, todos, NOW, StrongAlert.windowMs(30));
        assertEquals("正在响的都在最前，按到点先后（同时到的按截止时间）；其余在后", Arrays.asList(4L, 6L, 3L, 5L), ids(l));
        JSONArray a = new JSONArray(StrongAlert.recentJson(l, todos));
        int ringing = 0;
        for (int i = 0; i < a.length(); i++) if (StrongAlert.RINGING.equals(a.getJSONObject(i).getString("state"))) ringing++;
        assertEquals("三条都返回 state=ringing（助手对得上多条就追问是哪一条）", 3, ringing);
        assertEquals(StrongAlert.NOTIFIED, a.getJSONObject(3).getString("state"));
        for (long id : new long[]{3, 4, 6}) assertTrue("在响的每条都能被助手按 id 勾掉", StrongAlert.completable(b, todos, id, NOW, StrongAlert.windowMs(30)));
        // 助手勾掉其中一条（Remind.markDone → done_at）后，其余仍是 ringing
        b.complete(4, NOW);
        todos.put(4L, todo(4, "t4", 0, NOW));
        List<StrongAlert.Entry> after = StrongAlert.recent(b, todos, NOW, StrongAlert.windowMs(30));
        assertEquals(Arrays.asList(6L, 3L, 5L), ids(after));
        assertEquals(StrongAlert.RINGING, after.get(0).state);
        assertEquals(StrongAlert.RINGING, after.get(1).state);
    }

    @Test public void staleRingingClearedAfterProcessDeath() {
        StrongAlert.Book b = threeRinging();
        assertEquals("进程被杀后残留的「正在响」记为停止，只留真在响的", 2, b.clearStaleRinging(Arrays.asList(2L), NOW + MIN));
        assertEquals(Arrays.asList(2L), ids(b.ringingAll()));
        assertEquals(StrongAlert.STOPPED, b.get(1).state);
        assertEquals(1, b.clearStaleRinging(null, NOW + MIN));
        assertFalse(b.anyRinging());
    }

    @Test public void mergeWiring() throws Exception {
        String svc = src("app/src/com/onesay/plan/remind/StrongRingService.java");
        assertFalse("不再把正在响的转成稍后", svc.contains("snooze(ringingId") || svc.contains("ringingId"));
        assertTrue("并进同一次响铃", svc.contains("LIVE.add(id)"));
        assertTrue("已在响就不重新起铃", svc.contains("if (fresh || player == null) play();"));
        int on = svc.indexOf("public int onStartCommand(");
        assertTrue("稍后 = 全部稍后", svc.indexOf("snoozeAll(false)", on) > on);
        assertTrue("停止 = 全部停止", svc.indexOf("stopAll(StrongAlert.STOPPED)", on) > on);
        assertTrue("完成 = 只完成这一条", svc.indexOf("complete(tid)", on) > on && svc.contains("b.complete(id, System.currentTimeMillis())"));
        assertTrue("没人管各条各自处理", svc.contains("b.timedOut(now)") && svc.contains("snoozeOne(e.todoId)") && svc.contains("finishOne(e.todoId, StrongAlert.MISSED, true)"));
        int drop = svc.indexOf("void drop(long id)");
        assertTrue("拿掉一条后还有在响的就继续响", drop > 0 && svc.indexOf("if (anyRinging()) { refresh(false); armTimeout(); }", drop) > drop);
        assertTrue("通知合并", svc.contains("StrongAlert.ringTitle(l)") && svc.contains("Notification.InboxStyle"));
        String act = src("app/src/com/onesay/plan/ui/StrongAlertActivity.java");
        assertTrue("整屏页列出所有在响的", act.contains("StrongRingService.ringingEntries(this)"));
        assertTrue("每条自己的完成按钮", act.contains("button(ui, \"完成\", 0xFF1FA463, StrongRingService.A_DONE, e.todoId)"));
        assertTrue("单条完成不关页（其余继续）", act.contains("if (!StrongRingService.A_DONE.equals(action)) finish();"));
        String rm = src("app/src/com/onesay/plan/remind/Remind.java");
        assertTrue("勾掉 / 删除时只停这一条", rm.contains("StrongRingService.cancelIfRinging(c, id)"));
    }

    static StrongAlert.Book threeRinging() {
        StrongAlert.Book b = new StrongAlert.Book();
        b.ring(1, "A", NOW, NOW);
        b.ring(2, "B", NOW + MIN, NOW);
        b.ring(3, "C", NOW, NOW + 30000L);
        return b;
    }

    static List<Long> ids(List<StrongAlert.Entry> l) {
        List<Long> r = new java.util.ArrayList<Long>();
        for (StrongAlert.Entry e : l) r.add(e.todoId);
        return r;
    }

    /** 源码里双引号字符串（用户可见文字；日志 TAG 例外地不算，因为不显示给用户）。 */
    static String strings(String code) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(code);
        while (m.find()) if (!m.group(1).equals("BixbyPlan")) sb.append(m.group(1)).append('\n');
        return sb.toString();
    }
}
