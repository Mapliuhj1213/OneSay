package com.onesay.assistant.v2.pclink;

import com.onesay.assistant.v2.shortcut.ShortcutModel;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * [overnight-1119] 双端连通的手机侧：file_put 收 / 发（分块、幂等、校验、重名、路径）、shortcut_list / shortcut_run / operate_status 的开关和类别、
 * 交任务给电脑的口令、电脑回话变成一句人话。
 */
public class Link1119PhoneTest {

    static File tmp(String p) throws Exception {
        return Files.createTempDirectory(p).toFile();
    }

    static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static File write(File dir, String name, byte[] b) throws Exception {
        File f = new File(dir, name);
        FileOutputStream o = new FileOutputStream(f);
        o.write(b);
        o.close();
        return f;
    }

    // ================================================================== 文件

    @Test public void sendThenReceiveMultiChunkRoundTrips() throws Exception {
        File out = tmp("pc-out"), dest = tmp("pc-dest"), stage = tmp("pc-stage");
        byte[] data = random(PcFileRx.CHUNK * 2 + 12345, 7);
        File f = write(out, "视频 1.mp4", data);
        final PcFileRx rx = new PcFileRx(stage, PcFileRx.dirSink(dest));
        final List<Long> ticks = new ArrayList<Long>();
        PcLanClient.Result r = PcFileRx.send(f, null, new PcFileRx.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args) {
                assertEquals("file_put", cmd);
                assertNull("走协议的参数检查", PcRxPolicy.check(cmd, args));
                return rx.receive(PcJson.obj(PcJson.stringify(args)), "phone"); // 过一遍 JSON（数字变 Long）
            }
        }, new PcFileRx.Progress() {
            @Override public boolean tick(long done, long total) { ticks.add(done); return true; }
        });
        assertTrue(r.text, r.ok);
        File got = new File(dest, "视频 1.mp4");
        assertTrue(got.isFile());
        assertArrayEquals(data, Files.readAllBytes(got.toPath()));
        assertEquals(PcFileRx.sha256(f), r.data.get("sha256"));
        assertEquals(got.getAbsolutePath(), r.data.get("path"));
        assertEquals(Arrays.asList(0L, (long) PcFileRx.CHUNK, 2L * PcFileRx.CHUNK, (long) data.length), ticks);
        assertEquals("暂存目录不留临时文件", 0, stage.listFiles().length);
        // 同名再发一次：不覆盖，加「 (2)」
        assertTrue(PcFileRx.send(f, null, new PcFileRx.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args) { return rx.receive(args, "phone"); }
        }, null).ok);
        assertTrue(new File(dest, "视频 1 (2).mp4").isFile());
        assertArrayEquals(data, Files.readAllBytes(new File(dest, "视频 1.mp4").toPath()));
        assertEquals(2, rx.finished().size());
    }

    static Map<String, Object> chunk(String id, String name, long size, long seq, long total, byte[] b, String sha) {
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("xfer", id);
        a.put("name", name);
        a.put("size", size);
        a.put("seq", seq);
        a.put("total", total);
        a.put("b64", PcB64.encode(b));
        if (sha != null) a.put("sha256", sha);
        return a;
    }

    @Test public void receiveIsIdempotentOrderedAndVerified() throws Exception {
        File dest = tmp("pc-dest2"), stage = tmp("pc-stage2");
        PcFileRx rx = new PcFileRx(stage, PcFileRx.dirSink(dest));
        byte[] a = "hello ".getBytes("UTF-8"), b = "world".getBytes("UTF-8");
        File whole = write(tmp("w"), "x", "hello world".getBytes("UTF-8"));
        String sha = PcFileRx.sha256(whole);
        // 没有开头
        assertFalse(rx.receive(chunk("abcd1", "a.txt", 11, 1, 2, b, sha), "").ok);
        assertTrue(rx.receive(chunk("abcd1", "a.txt", 11, 0, 2, a, null), "").ok);
        assertTrue("重发同一块幂等", rx.receive(chunk("abcd1", "a.txt", 11, 0, 2, a, null), "").ok);
        PcLanClient.Result fin = rx.receive(chunk("abcd1", "a.txt", 11, 1, 2, b, sha), "");
        assertTrue(fin.text, fin.ok);
        assertEquals(Boolean.TRUE, fin.data.get("done"));
        assertEquals("hello world", new String(Files.readAllBytes(new File(dest, "a.txt").toPath()), "UTF-8"));
        assertTrue("最后一块重发：回「收过了」不重复落盘", rx.receive(chunk("abcd1", "a.txt", 11, 1, 2, b, sha), "").ok);
        assertEquals(1, dest.listFiles().length);
        // 校验不对：删掉，不落盘
        PcLanClient.Result bad = rx.receive(chunk("abcd2", "b.txt", 5, 0, 1, b, "00ff"), "");
        assertFalse(bad.ok);
        assertTrue(bad.text, bad.text.contains("校验不对"));
        assertFalse(new File(dest, "b.txt").exists());
        // 乱序：要求第几块
        assertTrue(rx.receive(chunk("abcd3", "c.txt", 11, 0, 3, a, null), "").ok);
        PcLanClient.Result skip = rx.receive(chunk("abcd3", "c.txt", 11, 2, 3, b, null), "");
        assertFalse(skip.ok);
        assertEquals(1L, skip.data.get("expect"));
        // 比说好的大
        PcLanClient.Result big = rx.receive(chunk("abcd4", "d.txt", 3, 0, 1, b, null), "");
        assertFalse(big.ok);
        // 编号 / 大小不对
        assertFalse(rx.receive(chunk("../x", "e.txt", 3, 0, 1, b, null), "").ok);
        assertFalse(rx.receive(chunk("abcd5", "e.txt", PcFileRx.MAX_SIZE + 1, 0, 1, b, null), "").ok);
    }

    @Test public void namesCannotEscapeOrHide() throws Exception {
        assertEquals("passwd", PcFileRx.safeName("../../etc/passwd"));
        assertEquals("x.apk", PcFileRx.safeName("C:\\Users\\a\\x.apk"));
        assertEquals("_bashrc", PcFileRx.safeName(".bashrc"));
        assertEquals("文件", PcFileRx.safeName(".."));
        assertEquals("a_b.txt", PcFileRx.safeName("a:b.txt"));
        File dest = tmp("pc-dest3");
        PcFileRx rx = new PcFileRx(tmp("st3"), PcFileRx.dirSink(dest));
        byte[] b = "1".getBytes("UTF-8");
        assertTrue(rx.receive(chunk("abcd6", "../../evil.sh", 1, 0, 1, b, null), "").ok);
        assertTrue(new File(dest, "evil.sh").isFile());
        assertEquals(1, dest.listFiles().length);
    }

    @Test public void emptyFileWorks() throws Exception {
        File dest = tmp("pc-dest4");
        final PcFileRx rx = new PcFileRx(tmp("st4"), PcFileRx.dirSink(dest));
        File f = write(tmp("o4"), "空.txt", new byte[0]);
        PcLanClient.Result r = PcFileRx.send(f, null, new PcFileRx.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args) { return rx.receive(args, ""); }
        }, null);
        assertTrue(r.text, r.ok);
        assertEquals(0, new File(dest, "空.txt").length());
    }

    @Test public void sendStopsAndSaysWhichChunkFailed() throws Exception {
        File f = write(tmp("o5"), "a.bin", random(PcFileRx.CHUNK + 1, 3));
        final int[] calls = {0};
        PcLanClient.Result r = PcFileRx.send(f, "a.bin", new PcFileRx.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args) {
                calls[0]++;
                return ((Number) args.get("seq")).intValue() == 0 ? new PcLanClient.Result(true, "", null, "")
                        : new PcLanClient.Result(false, "电脑不在线", null, "offline");
            }
        }, null);
        assertFalse(r.ok);
        assertTrue(r.text, r.text.contains("第 2 / 2 块"));
        assertEquals("失败的那块重试一次", 3, calls[0]);
        assertEquals("offline", r.fail);
    }

    // ================================================================== 开关 / 类别

    static final class Stub implements PcRx.Handler {
        final Set<String> on = new HashSet<String>();
        final List<String> ran = new ArrayList<String>();

        @Override public boolean enabled(String cmd) { return on.contains(cmd); }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> args) {
            ran.add(cmd);
            return new PcLanClient.Result(true, "好", null, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { }
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> x = new TreeMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) x.put((String) kv[i], kv[i + 1]);
        return x;
    }

    @Test public void newCommandsFollowTheirToggles() {
        assertTrue("收文件默认开（只往自己的下载文件夹放新文件）", PcRxPolicy.defaultOn("file_put"));
        assertFalse("运行快捷指令默认关", PcRxPolicy.defaultOn("shortcut_run"));
        assertFalse("列快捷指令跟着运行的开关", PcRxPolicy.defaultOn("shortcut_list"));
        assertFalse("查按目标操作进展跟着按目标操作", PcRxPolicy.defaultOn("operate_status"));
        assertEquals("shortcut_run", PcRxPolicy.toggleOf("shortcut_list"));
        assertEquals("operate", PcRxPolicy.toggleOf("operate_status"));
        assertEquals("[rel-1123-b] +1：看手机上装了哪些 App", 20, PcRxPolicy.ALL.length);
        Stub h = new Stub();
        assertFalse(PcRx.handle(h, "shortcut_list", m()).ok);
        assertTrue(PcRx.handle(h, "shortcut_list", m()).text.contains("运行手机上的快捷指令"));
        h.on.add("shortcut_run");
        assertTrue(PcRx.handle(h, "shortcut_list", m()).ok);
        assertFalse("要给名字", PcRx.handle(h, "shortcut_run", m()).ok);
        assertTrue(PcRx.handle(h, "shortcut_run", m("name", "回家模式")).ok);
        assertFalse(PcRx.handle(h, "operate_status", m()).ok);
        h.on.add("operate");
        assertTrue(PcRx.handle(h, "operate_status", m()).ok);
        assertFalse("分块不全", PcRx.handle(h, "file_put", m("xfer", "abcd")).ok);
        h.on.add("file_put");
        assertTrue(PcRx.handle(h, "file_put", m("xfer", "abcd", "seq", 0L, "b64", "")).ok);
        assertEquals(Arrays.asList("shortcut_list", "shortcut_run", "operate_status", "file_put"), h.ran);
        // caps：跟着开关的两条一起报
        @SuppressWarnings("unchecked")
        List<Object> caps = (List<Object>) PcRx.handle(h, "caps", null).data.get("caps");
        assertTrue(caps.toString(), caps.containsAll(Arrays.asList("file_put", "shortcut_run", "shortcut_list", "operate", "operate_status")));
        @SuppressWarnings("unchecked")
        List<Object> known = (List<Object>) PcRx.handle(h, "caps", null).data.get("known");
        assertTrue(known.containsAll(Arrays.asList("shortcut_list", "operate_status")));
    }

    @Test public void newCommandsHaveCategoriesAndNamesWithoutInternalIds() {
        assertEquals(LinkPolicy.Cat.QUERY, LinkPolicy.phoneCat("shortcut_list"));
        assertEquals(LinkPolicy.Cat.QUERY, LinkPolicy.phoneCat("operate_status"));
        assertEquals(LinkPolicy.Cat.WORKSPACE_WRITE, LinkPolicy.phoneCat("file_put"));
        assertEquals(LinkPolicy.Cat.APP_OPERATE, LinkPolicy.phoneCat("shortcut_run"));
        for (String c : new String[]{"file_put", "shortcut_run"}) {
            LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.phoneCat(c), new LinkPolicy.Facts());
            assertEquals(c, LinkPolicy.Verdict.ALLOW, d.verdict);
            assertFalse(PcRxPolicy.label(c).matches(".*[a-z_]{4,}.*"));
            assertFalse(PcRxPolicy.sub(c).isEmpty());
        }
        assertEquals(LinkPolicy.Cat.QUERY, LinkPolicy.pcCat("pc_task_status"));
        assertEquals(LinkPolicy.Cat.HARMLESS, LinkPolicy.pcCat("pc_task"));
        assertEquals(LinkPolicy.Cat.WORKSPACE_WRITE, LinkPolicy.pcCat("file_put"));
    }

    // ================================================================== 快捷指令

    static ShortcutModel.Shortcut sc(long id, String name, int steps, boolean on, String... alias) {
        ShortcutModel.Shortcut s = new ShortcutModel.Shortcut();
        s.id = id;
        s.name = name;
        s.enabled = on;
        for (String a : alias) s.aliases.add(a);
        for (int i = 0; i < steps; i++) {
            ShortcutModel.Step st = new ShortcutModel.Step("clock.alarm.set", new org.json.JSONObject());
            st.title = "第" + (i + 1) + "步";
            s.steps.add(st);
        }
        return s;
    }

    @Test public void shortcutsListAndFind() {
        List<ShortcutModel.Shortcut> all = Arrays.asList(sc(1, "回家模式", 3, true, "到家了"), sc(2, "晚安", 2, true), sc(3, "晚安音乐", 1, true), sc(4, "旧的", 1, false), sc(5, "空的", 0, true));
        List<Object> v = PcShortcuts.view(all);
        assertEquals(5, v.size());
        @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) v.get(0);
        assertEquals("回家模式", first.get("name"));
        assertEquals("3 步", first.get("steps"));
        assertEquals(Arrays.asList("第1步", "第2步", "第3步"), first.get("step_titles"));
        assertEquals(1L, PcShortcuts.find(all, "回家模式").hit.id);
        assertEquals("别名", 1L, PcShortcuts.find(all, "到家了").hit.id);
        assertEquals("全等优先于包含", 2L, PcShortcuts.find(all, "晚安").hit.id);
        assertEquals(1L, PcShortcuts.find(all, "回家").hit.id);
        assertTrue(PcShortcuts.find(all, "安").why.contains("说全名"));
        assertTrue(PcShortcuts.find(all, "旧的").why.contains("停用"));
        assertTrue(PcShortcuts.find(all, "空的").why.contains("没有步骤"));
        assertTrue(PcShortcuts.find(all, "不存在").why.contains("没有叫"));
        assertTrue(PcShortcuts.find(new ArrayList<ShortcutModel.Shortcut>(), "x").why.contains("还没有"));
        assertEquals("执行快捷指令#1（回家模式）", ShortcutModel.runCommand(1, "回家模式"));
    }

    // ================================================================== 手机 → 电脑任务

    @Test public void taskVoiceCommands() {
        assertEquals(PcVoice.Kind.TASK, PcVoice.of("让电脑把下载文件夹里的安装包整理一下"));
        assertEquals("把下载文件夹里的安装包整理一下", PcVoice.taskGoal("让电脑把下载文件夹里的安装包整理一下。"));
        assertEquals("查一下明天上海天气", PcVoice.taskGoal("电脑帮我查一下明天上海天气"));
        assertEquals("用 github 拉 mika 的安装包", PcVoice.taskGoal("交给电脑：用 github 拉 mika 的安装包"));
        assertEquals("压缩桌面上的照片", PcVoice.taskGoal("用电脑帮我压缩桌面上的照片"));
        assertEquals("打开记事本", PcVoice.taskGoal("叫电脑打开记事本"));
        StringBuilder longGoal = new StringBuilder("让电脑");
        for (int i = 0; i < 30; i++) longGoal.append("整理");
        assertEquals("长目标也认", PcVoice.Kind.TASK, PcVoice.of(longGoal.toString()));
        // 窄口令仍按原样
        assertEquals(PcVoice.Kind.LOCK, PcVoice.of("让电脑锁屏"));
        assertEquals(PcVoice.Kind.SHOT, PcVoice.of("电脑截图"));
        assertEquals(PcVoice.Kind.STATUS, PcVoice.of("电脑在线吗"));
        assertEquals(PcVoice.Kind.POWER, PcVoice.of("让电脑关机"));
        assertNull("关机不走任务", PcVoice.taskGoal("让电脑关机"));
        // 问句 / 普通聊天不收
        assertEquals(PcVoice.Kind.NONE, PcVoice.of("用电脑怎么截长图"));
        assertNull(PcVoice.taskGoal("让电脑怎么连手机"));
        assertNull(PcVoice.taskGoal("电脑帮我能不能装这个吗"));
        assertEquals(PcVoice.Kind.NONE, PcVoice.of("电脑多少钱"));
        assertEquals(PcVoice.Kind.NONE, PcVoice.of("我想买个电脑"));
        assertEquals(PcVoice.Kind.NONE, PcVoice.of("在电脑上看电影"));
        // 查进展
        assertEquals(PcVoice.Kind.TASK_STATUS, PcVoice.of("电脑任务做完了吗"));
        assertEquals(PcVoice.Kind.TASK_STATUS, PcVoice.of("电脑那边怎么样了？"));
        assertEquals(PcVoice.Kind.TASK_STATUS, PcVoice.of("交给电脑的任务做完了吗"));
    }

    @Test public void taskRepliesBecomeOneLine() {
        assertTrue(PcTaskAndroid.line(new PcLanClient.Result(false, "", null, "offline"), true).contains("电脑不在线"));
        assertTrue(PcTaskAndroid.line(new PcLanClient.Result(false, "", null, "auth"), true).contains("重新扫码"));
        assertEquals("电脑收到了，开始做：x", PcTaskAndroid.line(new PcLanClient.Result(true, "电脑收到了，开始做：x", null, ""), true));
        Map<String, Object> d = m("reason", "denied_by_level", "reason_zh", "沙箱档不允许");
        assertEquals("不做（沙箱档不允许）", PcTaskAndroid.line(new PcLanClient.Result(false, "不做", d, ""), true));
        List<Object> steps = new ArrayList<Object>();
        for (int i = 1; i <= 8; i++) steps.add("✓ 第" + i + "步");
        String s = PcTaskAndroid.line(new PcLanClient.Result(true, "电脑任务「整理」完成", m("step_lines", steps), ""), false);
        assertTrue(s, s.startsWith("电脑任务「整理」完成\n…前面还有 2 步\n✓ 第3步"));
        assertTrue(s.endsWith("✓ 第8步"));
    }
}
