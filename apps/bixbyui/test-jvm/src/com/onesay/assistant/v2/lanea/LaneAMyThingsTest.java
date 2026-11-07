package com.onesay.assistant.v2.lanea;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.jobs.DailyDigest;
import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.MyThingsExport;
import com.onesay.assistant.v2.jobs.Perm;
import com.onesay.assistant.v2.jobs.PermissionAnalyzer;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.TimeZone;

/** lane-a 第 6 项：「我的事」导出（今日待办 + 未读重要邮件标题 → root 广播给晨间播报 v2）。 */
public class LaneAMyThingsTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    static JSONObject decode(String cmd) throws Exception {
        String b = cmd.substring(cmd.indexOf("--es payload ") + "--es payload ".length());
        return new JSONObject(new String(java.util.Base64.getDecoder().decode(b), "UTF-8"));
    }

    @Test public void payloadFormatV1() throws Exception {
        long now = at(2026, 10, 23, 6, 25);
        String json = MyThingsExport.payload(DailyDigest.Part.of(Arrays.asList("交周报", "取快递")),
                DailyDigest.Part.of(Arrays.asList("季度预算审批")), now, TZ);
        JSONObject o = new JSONObject(json);
        assertEquals(1, o.getInt("v"));
        assertEquals("my_things", o.getString("kind"));
        assertEquals("com.onesay.assistant", o.getString("source"));
        assertEquals("2026-10-23", o.getString("date"));
        assertEquals(now, o.getLong("generatedAt"));
        assertEquals("[\"交周报\",\"取快递\"]", o.getJSONArray("todos").toString());
        assertEquals("", o.getString("todoNote"));
        assertEquals("季度预算审批", o.getJSONArray("mails").getString(0));
        assertEquals("", o.getString("mailNote"));
        // 跨时区：UTC 22:30 = 上海次日 6:30，日期按本机时区
        assertEquals("2026-10-24", new JSONObject(MyThingsExport.payload(DailyDigest.Part.of(Arrays.<String>asList()),
                DailyDigest.Part.of(Arrays.<String>asList()), at(2026, 10, 24, 6, 30), TZ)).getString("date"));
    }

    @Test public void notesWhenUnavailableAndLimits() throws Exception {
        JSONObject o = new JSONObject(MyThingsExport.payload(DailyDigest.Part.note("没授权读取"), DailyDigest.Part.note("还没接入"), 1L, TZ));
        assertEquals(0, o.getJSONArray("todos").length());
        assertEquals("没授权读取", o.getString("todoNote"));
        assertEquals("还没接入", o.getString("mailNote"));
        assertEquals("暂时读不到", new JSONObject(MyThingsExport.payload(null, null, 1L, TZ)).getString("todoNote"));
        JSONObject many = new JSONObject(MyThingsExport.payload(
                DailyDigest.Part.of(Arrays.asList("1", "2", "3", "4", "5", "6", "7")),
                DailyDigest.Part.of(Arrays.asList("a", "b", "c", "d", "这是一个非常非常非常非常非常非常非常非常非常非常长的邮件标题超过三十个字")), 1L, TZ));
        assertEquals("待办最多 5 条", 5, many.getJSONArray("todos").length());
        assertEquals("邮件标题最多 3 条", 3, many.getJSONArray("mails").length());
        JSONObject dirty = new JSONObject(MyThingsExport.payload(DailyDigest.Part.of(Arrays.asList("  ", "a\u202Eb\nc")),
                DailyDigest.Part.of(Arrays.asList("这是一个非常非常非常非常非常非常非常非常非常非常长的邮件标题超过三十个字")), 1L, TZ));
        assertEquals("空条目丢掉、控制 / 双向覆盖字符去掉", "[\"abc\"]", dirty.getJSONArray("todos").toString());
        assertTrue("每条 ≤ 30 字", dirty.getJSONArray("mails").getString(0).length() <= 30);
    }

    @Test public void commandIsShellSafeWhateverTheTitles() throws Exception {
        String evil = "'; rm -rf / ; $(reboot) `id` \" && am start x | sh\n--es payload hijack";
        String cmd = MyThingsExport.amCommand(MyThingsExport.payload(DailyDigest.Part.of(Arrays.asList(evil)),
                DailyDigest.Part.of(Arrays.asList(evil, "正常标题")), 5L, TZ));
        assertTrue(cmd.startsWith("am broadcast -n com.onesay.clock/.ControlReceiver -a com.onesay.clock.MY_THINGS --ei v 1 --es payload "));
        String tail = cmd.substring(cmd.indexOf("--es payload ") + 13);
        assertTrue("payload 只有 Base64 字符", tail.matches("[A-Za-z0-9+/]+={0,2}"));
        assertFalse(cmd.contains(";") || cmd.contains("$") || cmd.contains("`") || cmd.contains("'") || cmd.contains("|") || cmd.contains("\n"));
        JSONObject back = decode(cmd);
        assertEquals("解码回来内容不丢（换行这类控制字符已清掉）", "正常标题", back.getJSONArray("mails").getString(1));
        assertTrue(back.getJSONArray("todos").getString(0).startsWith("'; rm -rf"));
        // 自写 Base64 与 JDK 一致（覆盖 0/1/2 字节填充）
        for (String s : new String[]{"", "a", "ab", "abc", "中文 🙂"}) {
            String m = MyThingsExport.amCommand(s);
            assertEquals(java.util.Base64.getEncoder().encodeToString(s.length() == 0 ? "{}".getBytes("UTF-8") : s.getBytes("UTF-8")),
                    m.substring(m.indexOf("--es payload ") + 13));
        }
    }

    @Test public void templateAndPermissions() {
        JobStore.Job j = MyThingsExport.newJob(6, 30);
        assertEquals(MyThingsExport.TEMPLATE_ID, j.templateId);
        assertEquals(6, j.hour);
        assertEquals(30, j.minute);
        assertFalse("导出不出声", j.speak);
        assertEquals("权限卡只勾读待办 + 读邮件", EnumSet.of(Perm.READ_TODO, Perm.READ_MAIL), PermissionAnalyzer.forTemplate(MyThingsExport.TEMPLATE_ID));
        assertEquals("已发给晨间播报 v2：待办 2 条，邮件标题 没授权读取",
                MyThingsExport.summary(DailyDigest.Part.of(Arrays.asList("a", "b")), DailyDigest.Part.note("没授权读取")));
    }

    @Test public void wiring() {
        String hooks = KernelWiring.src("app/src/com/onesay/assistant/v2/jobs/JobsHooks.java");
        int i = hooks.indexOf("static void exportMyThings(");
        assertTrue(hooks.contains("MyThingsExport.TEMPLATE_ID.equals(r.templateId)") && i > 0);
        String body = hooks.substring(i, hooks.indexOf("static boolean allowed(", i));
        assertTrue("每项取数过权限闸", body.contains("todos(app, g)") && body.contains("mails(g)"));
        assertTrue("只留历史不打扰", body.contains("ob.worth = 0") && body.contains("\"LOW\""));
        assertTrue("发不出去走失败", body.contains("e.finish(r, false"));
        assertFalse("不把带邮件标题的命令打进日志", body.contains("Log.i(TAG, \"[JOBS] \" + cmd") || body.contains("+ cmd"));
        assertFalse("OneSay 不直写时钟的文件", hooks.contains("com.onesay.clock/files") || hooks.contains("/data/data/com.onesay.clock"));
        String cb = KernelWiring.plugin("CallbackPlugin");
        assertTrue("控制台内置模板里能添加（走权限卡）", cb.contains("MyThingsExport.newJob(6, 30)"));
    }

    @Test public void listHelperRoundTrip() throws Exception {
        JSONArray a = new JSONObject(MyThingsExport.payload(DailyDigest.Part.of(Arrays.asList("x")), null, 1L, TZ)).getJSONArray("todos");
        assertEquals("x", a.getString(0));
    }
}
