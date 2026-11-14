package com.onesay.plan.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * 找茬 · 计划 App 对外接口与强提醒的边界：
 * <ul>
 *   <li>gate：数据接口门卫（AccessPolicy）——本 App / signature 权限 / 同证书放行，root（0）与 adb shell（2000）不管有没有权限都拒；</li>
 *   <li>method：provider call 方法名——提醒接口与伴侣连接两组不重叠、大小写 / 空白 / 前缀写法都不认；</li>
 *   <li>manifest：清单里只有已知的导出组件，数据 provider 读写都要 signature 级权限，强提醒的服务 / 整屏页 / 广播不导出；</li>
 *   <li>window / recent：「最近提醒过」的窗口（默认 30、限 1–120 分钟）、只认未完成未删除的、正在响的排前面、按 id 勾完成只认列表里的；</li>
 *   <li>key / snooze / timeout：整屏页按键映射、稍后分钟数、没人管自动稍后最多 3 次。</li>
 * </ul>
 */
@RunWith(Parameterized.class)
public class AdvPlanGateTest {

    static final int OWN = 10123;

    @Parameterized.Parameters(name = "{index}: {0} {1}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        int[] uids = {OWN, 0, 2000, 1000, 10050, 10124, 99999, -1, 1001, 2001};
        for (int u : uids)
            for (int p = 0; p < 2; p++)
                for (int s = 0; s < 2; s++) out.add(new Object[]{"gate", u + "/" + p + "/" + s});
        String[] methods = {"listRemindedTasks", "completeRemindedTask", "link.hello", "createTask", "listTasks", "updateTask", "completeTask", "deleteTask",
                "createEvent", "listEvents", "ListRemindedTasks", " listRemindedTasks", "listRemindedTasks ", "link.Hello", "LINK.HELLO", "hello", "link.hello.x",
                "getAssistantStatus", "", "query", "delete", "insert", "listTasks\u200b", "completeTask;deleteTask", "link."};
        for (String m : methods) out.add(new Object[]{"method", m});
        String[] mf = {"providerPermission", "strongNotExported", "exportedSet", "permissionSignature", "noRootShellPath"};
        for (String x : mf) out.add(new Object[]{"manifest", x});
        int[] wins = {-100, -1, 0, 1, 2, 29, 30, 31, 119, 120, 121, 1000, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int w : wins) out.add(new Object[]{"window", String.valueOf(w)});
        String[] recent = {"ringingFirst", "doneExcluded", "deletedExcluded", "outsideWindow", "edgeWindow", "snoozedOld", "stoppedOld", "completableOnlyListed",
                "twoRingingOrder", "notifiedRecent", "jsonFields", "missingTitleFromRow"};
        for (String r : recent) out.add(new Object[]{"recent", r});
        int[] keys = {24, 25, 164, 26, 3, 4, 0, -1, 23, 66, 187, 79};
        for (int k : keys) out.add(new Object[]{"key", String.valueOf(k)});
        int[] snz = {-5, 0, 1, 5, 10, 59, 60, 61, 1000};
        for (int s : snz) out.add(new Object[]{"snooze", String.valueOf(s)});
        for (int a = 0; a <= 5; a++) out.add(new Object[]{"timeout", String.valueOf(a)});
        String[] src = {"self|", "perm|com.onesay.assistant", "perm|com.onesay.assistant.debug", "signer|com.other.app", "perm|", "perm|com.onesay",
                "signer|com.onesay.assistan"};
        for (String s : src) out.add(new Object[]{"source", s});
        return out;
    }

    private final String kind, spec;

    public AdvPlanGateTest(String kind, String spec) {
        this.kind = kind;
        this.spec = spec;
    }

    static String manifest() throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("plan.root", "."), "app/AndroidManifest.xml").toPath()), Charset.forName("UTF-8"));
    }

    static Row todo(long id, String title, boolean done) {
        return new Row().with("_id", id).with("title", title).with("due_at", 1_794_000_600_000L).with("done_at", done ? 1L : 0L);
    }

    @Test public void check() throws Exception {
        if (kind.equals("gate")) {
            String[] p = spec.split("/");
            int uid = Integer.parseInt(p[0]);
            boolean perm = p[1].equals("1"), signer = p[2].equals("1");
            AccessPolicy.Who w = AccessPolicy.decide(uid, OWN, perm, signer);
            AccessPolicy.Who want = uid == OWN ? AccessPolicy.Who.SELF : uid == 0 || uid == 2000 ? AccessPolicy.Who.DENIED
                    : perm ? AccessPolicy.Who.PERMISSION : signer ? AccessPolicy.Who.SAME_SIGNER : AccessPolicy.Who.DENIED;
            assertEquals(spec, want, w);
            assertEquals(want != AccessPolicy.Who.DENIED, AccessPolicy.allowed(w));
        } else if (kind.equals("method")) {
            boolean remind = spec.equals("listRemindedTasks") || spec.equals("completeRemindedTask");
            boolean link = spec.equals("link.hello") || spec.equals("createTask") || spec.equals("listTasks") || spec.equals("updateTask") || spec.equals("completeTask")
                    || spec.equals("deleteTask") || spec.equals("createEvent") || spec.equals("listEvents");
            assertEquals(spec, remind, RemindLink.isRemindMethod(spec));
            assertEquals(spec, link, LinkContract.isLinkMethod(spec));
            assertFalse("两组方法名不重叠", RemindLink.isRemindMethod(spec) && LinkContract.isLinkMethod(spec));
        } else if (kind.equals("manifest")) {
            manifestCase();
        } else if (kind.equals("window")) {
            int w = Integer.parseInt(spec);
            long want = (w <= 0 ? 30 : Math.min(120, w)) * 60000L;
            assertEquals(spec, want, StrongAlert.windowMs(w));
            assertTrue(StrongAlert.windowMs(w) >= 60000L && StrongAlert.windowMs(w) <= 120 * 60000L);
        } else if (kind.equals("recent")) {
            recentCase();
        } else if (kind.equals("key")) {
            int k = Integer.parseInt(spec);
            StrongAlert.Key want = k == 24 || k == 25 || k == 164 ? StrongAlert.Key.SNOOZE : k == 26 ? StrongAlert.Key.STOP : StrongAlert.Key.NONE;
            assertEquals(spec, want, StrongAlert.key(k));
        } else if (kind.equals("snooze")) {
            int v = Integer.parseInt(spec);
            assertEquals(spec, v < 1 || v > 60 ? StrongAlert.DEFAULT_SNOOZE_MIN : v, StrongAlert.snoozeMin(v));
        } else if (kind.equals("timeout")) {
            StrongAlert.Entry e = new StrongAlert.Entry();
            e.autoSnoozes = Integer.parseInt(spec);
            assertEquals(spec, e.autoSnoozes < StrongAlert.MAX_AUTO_SNOOZE ? StrongAlert.Timeout.SNOOZE : StrongAlert.Timeout.STOP, StrongAlert.onTimeout(e));
            assertEquals(StrongAlert.Timeout.STOP, StrongAlert.onTimeout(null));
        } else if (kind.equals("source")) {
            String[] p = spec.split("\\|", -1);
            AccessPolicy.Who who = p[0].equals("self") ? AccessPolicy.Who.SELF : p[0].equals("perm") ? AccessPolicy.Who.PERMISSION : AccessPolicy.Who.SAME_SIGNER;
            String pkg = p[1].length() == 0 ? null : p[1];
            boolean oneSay = pkg != null && pkg.startsWith("com.onesay.assistant");
            String oneSayTag = AccessPolicy.source(AccessPolicy.Who.PERMISSION, "com.onesay.assistant");   // 数据格式里的历史取值，按实现取，不写死
            assertNotEquals("ui", oneSayTag);
            assertNotEquals("other", oneSayTag);
            String want = who == AccessPolicy.Who.SELF ? "ui" : oneSay ? oneSayTag : "other";
            assertEquals(spec, want, AccessPolicy.source(who, pkg));
            assertEquals(spec, oneSay ? oneSayTag : "root", AccessPolicy.runSource(pkg));
        } else fail(kind);
    }

    void manifestCase() throws Exception {
        String m = manifest();
        if (spec.equals("providerPermission")) {
            // 设计（清单注释）：provider 导出，但每个入口先在代码里过门卫 —— 核对清单不授 URI 权限、PlanProvider 每个入口第一句都是 enforce()
            Matcher pm = Pattern.compile("<provider[^>]*>", Pattern.DOTALL).matcher(m);
            assertTrue(pm.find());
            String p = pm.group();
            assertTrue(p, p.contains("com.onesay.plan.provider"));
            assertTrue("不授 URI 临时权限：" + p, p.contains("android:grantUriPermissions=\"false\""));
            assertFalse("不能再有第二个 provider", pm.find());
            String src = new String(Files.readAllBytes(new File(System.getProperty("plan.root", "."), "app/src/com/onesay/plan/data/PlanProvider.java").toPath()),
                    Charset.forName("UTF-8"));
            for (String entry : new String[]{"Cursor query(", "Uri insert(", "int update(", "int delete(", "ParcelFileDescriptor openFile(", "Bundle call("}) {
                int at = src.indexOf(entry);
                assertTrue(entry, at > 0);
                String body = src.substring(src.indexOf('{', at) + 1).trim();
                assertTrue(entry + " 第一句要过门卫", body.startsWith("enforce();") || body.startsWith("Caller who = enforce();"));
            }
        } else if (spec.equals("permissionSignature")) {
            Matcher pm = Pattern.compile("<permission[^>]*>", Pattern.DOTALL).matcher(m);
            assertTrue(pm.find());
            assertTrue(pm.group(), pm.group().contains("android:protectionLevel=\"signature\""));
            assertTrue(pm.group(), pm.group().contains(Contract.PERMISSION));
        } else if (spec.equals("strongNotExported")) {
            for (String c : new String[]{".remind.StrongRingService", ".ui.StrongAlertActivity", ".remind.RemindReceiver", ".ui.TicketActivity", ".ui.ShortcutRunActivity"}) {
                Matcher cm = Pattern.compile("android:name=\"" + Pattern.quote(c) + "\"[^>]*>", Pattern.DOTALL).matcher(m);
                assertTrue(c, cm.find());
                assertTrue(c + " 不导出", cm.group().contains("android:exported=\"false\""));
            }
        } else if (spec.equals("exportedSet")) {
            List<String> exported = new ArrayList<String>();
            Matcher cm = Pattern.compile("<(activity|provider|receiver|service)\\s+([^>]*?)>", Pattern.DOTALL).matcher(m);
            while (cm.find()) {
                String a = cm.group(2);
                if (a.contains("android:exported=\"true\"")) {
                    Matcher nm = Pattern.compile("android:name=\"([^\"]+)\"").matcher(a);
                    exported.add(nm.find() ? nm.group(1) : "?");
                }
            }
            assertEquals("导出组件只有：主界面、数据 provider、开机广播", "[.ui.MainActivity, .data.PlanProvider, .remind.BootReceiver]", exported.toString());
        } else if (spec.equals("noRootShellPath")) {
            assertEquals(AccessPolicy.Who.DENIED, AccessPolicy.decide(AccessPolicy.ROOT_UID, OWN, true, true));
            assertEquals(AccessPolicy.Who.DENIED, AccessPolicy.decide(AccessPolicy.SHELL_UID, OWN, true, true));
            assertFalse(m.contains("android:sharedUserId"));
        }
    }

    void recentCase() throws Exception {
        long now = 1_794_000_000_000L, min = 60000L, win = StrongAlert.windowMs(30);
        StrongAlert.Book b = new StrongAlert.Book();
        Map<Long, Row> todos = new HashMap<Long, Row>();
        for (long id = 1; id <= 4; id++) todos.put(id, todo(id, "待办" + id, false));
        if (spec.equals("ringingFirst")) {
            b.notified(1, "待办1", 0, now - min);
            b.ring(2, "待办2", 0, now - 20 * min);
            List<StrongAlert.Entry> l = StrongAlert.recent(b, todos, now, win);
            assertEquals(2, l.get(0).todoId);
            assertEquals(1, l.get(1).todoId);
        } else if (spec.equals("doneExcluded")) {
            b.ring(1, "待办1", 0, now);
            todos.put(1L, todo(1, "待办1", true));
            assertTrue(StrongAlert.recent(b, todos, now, win).isEmpty());
            assertFalse(StrongAlert.completable(b, todos, 1, now, win));
        } else if (spec.equals("deletedExcluded")) {
            b.ring(1, "待办1", 0, now);
            todos.remove(1L);
            assertTrue(StrongAlert.recent(b, todos, now, win).isEmpty());
            assertTrue(StrongAlert.recent(b, null, now, win).isEmpty());
        } else if (spec.equals("outsideWindow")) {
            b.notified(1, "待办1", 0, now - 31 * min);
            assertTrue(StrongAlert.recent(b, todos, now, win).isEmpty());
        } else if (spec.equals("edgeWindow")) {
            b.notified(1, "待办1", 0, now - 30 * min);
            assertEquals(1, StrongAlert.recent(b, todos, now, win).size());
        } else if (spec.equals("snoozedOld")) {
            b.ring(1, "待办1", 0, now - 100 * min);
            b.snooze(1, now - 99 * min, 10, true);
            assertEquals("稍后中的不论多久都算正在提醒", 1, StrongAlert.recent(b, todos, now, win).size());
        } else if (spec.equals("stoppedOld")) {
            b.ring(1, "待办1", 0, now - 100 * min);
            b.stop(1, now - 99 * min, StrongAlert.STOPPED);
            assertTrue(StrongAlert.recent(b, todos, now, win).isEmpty());
        } else if (spec.equals("completableOnlyListed")) {
            b.ring(1, "待办1", 0, now);
            assertTrue(StrongAlert.completable(b, todos, 1, now, win));
            assertFalse("不在最近提醒里的待办不能按 id 勾掉", StrongAlert.completable(b, todos, 3, now, win));
            assertFalse(StrongAlert.completable(b, todos, -1, now, win));
        } else if (spec.equals("twoRingingOrder")) {
            b.ring(3, "待办3", now + 5 * min, now - 2 * min);
            b.ring(2, "待办2", now + 1 * min, now - 2 * min);
            b.ring(4, "待办4", 0, now - 3 * min);
            List<StrongAlert.Entry> l = StrongAlert.recent(b, todos, now, win);
            assertEquals(4, l.get(0).todoId);
            assertEquals(2, l.get(1).todoId);
            assertEquals(3, l.get(2).todoId);
        } else if (spec.equals("notifiedRecent")) {
            b.notified(1, "待办1", 0, now - 10 * min);
            b.notified(2, "待办2", 0, now - 5 * min);
            List<StrongAlert.Entry> l = StrongAlert.recent(b, todos, now, win);
            assertEquals("普通提醒按最近提醒时刻倒序", 2, l.get(0).todoId);
        } else if (spec.equals("jsonFields")) {
            b.ring(1, "旧标题", 0, now);
            JSONArray a = new JSONArray(StrongAlert.recentJson(StrongAlert.recent(b, todos, now, win), todos));
            JSONObject o = a.getJSONObject(0);
            for (String k : new String[]{"id", "title", "due_at", "state", "reminded_at", "first_at", "strong", "snooze_until"}) assertTrue(k, o.has(k));
            assertEquals("标题取当前行", "待办1", o.getString("title"));
            assertEquals("ringing", o.getString("state"));
        } else if (spec.equals("missingTitleFromRow")) {
            b.ring(9, "记录本里的标题", 0, now);
            JSONArray a = new JSONArray(StrongAlert.recentJson(b.entries, new HashMap<Long, Row>()));
            assertEquals("记录本里的标题", a.getJSONObject(0).getString("title"));
        } else fail(spec);
    }
}
