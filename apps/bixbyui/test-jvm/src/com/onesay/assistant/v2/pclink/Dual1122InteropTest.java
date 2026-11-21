package com.onesay.assistant.v2.pclink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * [dual-1122] 明天（11-22）真机双端互通前的沙箱回环：手机侧真代码（PcLanClient / PcRx / PcFileRx / PcConfirm）
 * 对 <b>电脑端预览版 jar 的真进程</b>（flavor=preview，真 TLS 局域网协议），大模型换成本机假服务（OpenAI 兼容，按剧本回工具调用）。
 * <p>和真手机一样：<b>同一个本机名、同一张手机证书</b>既保持常连（PcRx.Loop），又用一次性连接（PcLanClient.once）下令——
 * 以前的互通测试是分开两个名字测的，没覆盖「一次性连接把常连顶掉 → 结果推不回手机」这种情况。
 * <ol>
 *   <li>配对：电脑配对串（v2 带证书指纹）→ 手机解析 → UDP 发现（端口 +1）→ TLS 常连；</li>
 *   <li>手机 → 电脑任务：成功路径，电脑真走大模型工具循环写文件，结果经常连推回手机通知，手机也能查；</li>
 *   <li>审批卡：手机交来的任务里有要批准的一步 → 电脑界面待确认列表里出现，同时推到手机确认卡；手机上拒 → 不执行、结果如实推回；</li>
 *   <li>电脑 → 手机任务：电脑对话（假大模型调 phone_operate）→ 手机收到 operate，电脑只说「已开始」；</li>
 *   <li>开关默认关：按手机真实默认值（按目标操作 / 运行快捷指令关、收文件开），电脑说「手机上没开」，手机什么都不执行；</li>
 *   <li>快捷指令：打开后能列、能跑；</li>
 *   <li>文件：手机 → 电脑 5 MB 多块字节一致；校验不对 / 跳块被拒、不落半截；电脑 → 手机 3 MB 多块字节一致；</li>
 *   <li>断线重连：电脑进程退出 → 手机常连掉线 → 同一配置重启 → 手机自己连回来，指令照常（证书没变，不用重配）；</li>
 *   <li>预览版默认端口：配置里不写端口时配对串带 47920（正式版 47820 不受影响）。</li>
 * </ol>
 * 电脑端 jar 同 {@link PcDesktopInteropTest}（ONESAY_DESKTOP_JAR，缺 jar 整类跳过）。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class Dual1122InteropTest {
    static File jar;
    static Process proc;
    static Path dir, home, cfgFile;
    static int port, uiPort;
    static String token;
    static final byte[] KEY = new byte[32];
    static final String PC = "pc-dual";
    static final String ME = "phone-dual";
    static PcTls tls;
    static HttpServer llm;
    static final List<String> llmSeen = new CopyOnWriteArrayList<String>();
    static Env env;
    static PcRx.Loop loop;
    static Thread loopThread;
    static File phoneDl;
    static final List<PcConfirm.Card> phoneCards = new CopyOnWriteArrayList<PcConfirm.Card>();

    // ================================================================== 假大模型（按目标里的暗号回剧本）

    static String llmReply(String body) {
        Map<String, Object> req;
        try {
            req = PcJson.obj(body);
        } catch (Exception e) {
            return say("好");
        }
        Object ms = req.get("messages");
        if (!(ms instanceof List) || req.get("tools") == null) return say("好");
        @SuppressWarnings("unchecked") List<Object> msgs = (List<Object>) ms;
        String lastRole = "", lastUser = "", lastTool = "";
        for (Object o : msgs) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            lastRole = String.valueOf(m.get("role"));
            if ("user".equals(lastRole)) lastUser = String.valueOf(m.get("content"));
            if ("tool".equals(lastRole)) lastTool = String.valueOf(m.get("content"));
        }
        llmSeen.add(lastRole + "|" + lastUser + "|" + lastTool);
        if ("tool".equals(lastRole)) {
            if (lastUser.contains("DUALS1")) return say("【完成】\n已经在电脑工作区写好 notes/from-phone.txt。");
            if (lastUser.contains("DUALS2")) return say("【没做成】\n这一步没有批准，没执行：" + clip(lastTool));
            if (lastUser.contains("DUALS3")) return say("已交给手机上的 OneSay 去做，结果看手机屏幕。");
            return say("好了。");
        }
        if (lastUser.contains("DUALS1")) return call("write_file", "{\"description\":\"写一份笔记\",\"path\":\"notes/from-phone.txt\",\"text\":\"手机交来的任务\"}");
        if (lastUser.contains("DUALS2")) return call("run_command", "{\"description\":\"在系统目录里新建一个文件\",\"command\":\"Set-Content -Path C:\\\\Windows\\\\dual-1122.txt -Value x\"}");
        if (lastUser.contains("DUALS3")) return call("phone_operate", "{\"description\":\"交给手机\",\"goal\":\"打开设置看看安卓版本\"}");
        return say("好");
    }

    static String clip(String s) { return s.length() > 80 ? s.substring(0, 80) : s; }

    static String say(String text) {
        Map<String, Object> msg = new TreeMap<String, Object>();
        msg.put("role", "assistant");
        msg.put("content", text);
        return wrap(msg, "stop");
    }

    static final AtomicInteger CALL_SEQ = new AtomicInteger();

    static String call(String name, String args) {
        Map<String, Object> fn = new TreeMap<String, Object>();
        fn.put("name", name);
        fn.put("arguments", args);
        Map<String, Object> tc = new TreeMap<String, Object>();
        tc.put("id", "call_" + CALL_SEQ.incrementAndGet());
        tc.put("type", "function");
        tc.put("function", fn);
        List<Object> l = new ArrayList<Object>();
        l.add(tc);
        Map<String, Object> msg = new TreeMap<String, Object>();
        msg.put("role", "assistant");
        msg.put("content", "");
        msg.put("tool_calls", l);
        return wrap(msg, "tool_calls");
    }

    static String wrap(Map<String, Object> msg, String finish) {
        Map<String, Object> ch = new TreeMap<String, Object>();
        ch.put("index", 0L);
        ch.put("message", msg);
        ch.put("finish_reason", finish);
        List<Object> chs = new ArrayList<Object>();
        chs.add(ch);
        Map<String, Object> r = new TreeMap<String, Object>();
        r.put("id", "mock");
        r.put("object", "chat.completion");
        r.put("choices", chs);
        return PcJson.stringify(r);
    }

    static void startLlm() throws Exception {
        llm = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        llm.createContext("/", (HttpExchange x) -> {
            InputStream in = x.getRequestBody();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            byte[] out = llmReply(new String(b.toByteArray(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, out.length);
            OutputStream o = x.getResponseBody();
            o.write(out);
            o.close();
        });
        llm.start();
    }

    // ================================================================== 电脑端预览版进程

    static void startDesktop() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-Duser.home=" + home, "-jar", jar.getAbsolutePath(),
                "--config", cfgFile.toString(), "app", "--tray");
        pb.environment().put("ONESAY_TEST_ACTIONS", "1");
        pb.environment().put("ONESAY_TEST_LAUNCH_KEY", PcLinkV2InteropTest.LAUNCH_KEY);
        pb.environment().put("ONESAY_UI_PORT", "0");
        pb.environment().remove("APPDATA");
        pb.environment().remove("ONESAY_LAN");
        File log = dir.resolve("desktop.out").toFile();
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        Pattern ui = Pattern.compile("界面 http://127\\.0\\.0\\.1:(\\d+)/");
        int before = 0;
        for (Matcher m0 = ui.matcher(out()); m0.find(); ) before++;
        proc = pb.start();
        long end = System.currentTimeMillis() + 25000;
        uiPort = 0;
        while (System.currentTimeMillis() < end && uiPort == 0) {
            if (!proc.isAlive()) fail("电脑端进程提前退出：" + out());
            Matcher m = ui.matcher(out());
            int k = 0, last = 0;
            while (m.find()) { k++; last = Integer.parseInt(m.group(1)); }
            if (k > before) uiPort = last;
            else Thread.sleep(150);
        }
        assertTrue("电脑端界面没起来：" + out(), uiPort > 0);
        PcLinkV2InteropTest.uiPort = uiPort;
        Matcher t = Pattern.compile("const TOKEN='([A-Za-z0-9_-]+)'").matcher(PcLinkV2InteropTest.http("GET", "/?k=" + PcLinkV2InteropTest.LAUNCH_KEY, null, null));
        assertTrue(t.find());
        token = t.group(1);
        PcLinkV2InteropTest.token = token;
    }

    static void stopDesktop() throws Exception {
        if (proc != null) {
            proc.destroy();
            if (!proc.waitFor(8, TimeUnit.SECONDS)) proc.destroyForcibly();
            proc.waitFor(5, TimeUnit.SECONDS);
        }
    }

    static String out() throws Exception {
        File f = dir.resolve("desktop.out").toFile();
        return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    static int freePort() throws Exception {
        for (int i = 0; i < 50; i++) {
            ServerSocket s = new ServerSocket(0);
            int p = s.getLocalPort();
            s.close();
            try {
                java.net.DatagramSocket d = new java.net.DatagramSocket(p + 1);
                d.close();
                return p;
            } catch (Exception ignored) {
            }
        }
        throw new IllegalStateException("找不到空端口");
    }

    @BeforeClass public static void setUp() throws Exception {
        jar = PcDesktopInteropTest.findJar();
        Assume.assumeTrue("电脑端 jar 不在，双端回环跳过", jar != null);
        new java.security.SecureRandom().nextBytes(KEY);
        startLlm();
        port = freePort();
        dir = Files.createTempDirectory("dual1122");
        home = Files.createDirectories(dir.resolve("home"));
        Path ws = dir.resolve("ws");
        cfgFile = dir.resolve("desktop.properties").toAbsolutePath();
        String props = "lan_port=" + port + "\ndevice_id=" + PC + "\nkind=pc\nptt=off\npair_key=" + PcB64.encodeUrl(KEY)
                + "\nworkspace_dir=" + ws.toString().replace("\\", "/")
                + "\nllm_provider=custom\nllm_base=http://127.0.0.1:" + llm.getAddress().getPort() + "/v1\nllm_key=mock-not-a-real-key\nllm_model=mock\n";
        Files.write(cfgFile, props.getBytes(StandardCharsets.UTF_8));
        tls = PcTls.generate(ME);
        startDesktop();
        phoneDl = Files.createTempDirectory("dual-phone-dl").toFile();
        PcConfirm.reset();
        PcConfirm.ui = new PcConfirm.Ui() {
            @Override public void open(PcConfirm.Card c) { phoneCards.add(c); }
            @Override public void close(PcConfirm.Card c) { }
        };
        env = new Env(peer(), phoneDl);
        loop = new PcRx.Loop(env);
        loop.minBackoff = 300;
        loop.maxBackoff = 1000;
        loopThread = new Thread(loop, "rx-dual");
        loopThread.setDaemon(true);
        loopThread.start();
        assertTrue("常连没连上：" + env.state + " / " + out(), waitUp(12000));
    }

    @AfterClass public static void tearDown() throws Exception {
        PcLinkV2InteropTest.uiPort = 0;
        PcLinkV2InteropTest.token = null;
        PcConfirm.ui = null;
        PcConfirm.reset();
        if (env != null) env.wanted = false;
        if (loop != null) loop.stop();
        if (loopThread != null) loopThread.join(5000);
        stopDesktop();
        if (llm != null) llm.stop(0);
        if (dir != null) Link1119InteropTest.rm(dir.toFile());
        if (phoneDl != null) Link1119InteropTest.rm(phoneDl);
    }

    static boolean waitUp(long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (!env.state.startsWith(PcRx.S_UP) && System.currentTimeMillis() < end) Thread.sleep(100);
        if (!env.state.startsWith(PcRx.S_UP)) return false;
        // 电脑那边也认到这台手机了（phoneTarget 非空）
        end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (ME.equals(String.valueOf(PcLinkV2InteropTest.api("state", null).get("phone")))) return true;
            Thread.sleep(150);
        }
        return false;
    }

    /** 真手机的样子：同一个本机名、同一张证书（常连和一次性连接共用）。 */
    static PcLanClient.Peer peer() throws Exception {
        PcPairing pp = PcPairing.parse(String.valueOf(PcLinkV2InteropTest.api("pairing", null).get("pairing")));
        return new PcLanClient.Peer(ME, pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, tls);
    }

    static PcLanClient.Result once(String cmd, Map<String, Object> args, int timeoutMs) throws Exception {
        return PcLanClient.once(peer(), cmd, args, 2000, timeoutMs, null, null);
    }

    static Map<String, Object> phone(String cmd, Map<String, Object> args) throws Exception {
        return Link1119InteropTest.phone(cmd, args);
    }

    static Map<String, Object> m(String k, Object v) {
        Map<String, Object> x = new TreeMap<String, Object>();
        x.put(k, v);
        return x;
    }

    // ================================================================== 手机（常连处理器，开关按手机真实默认值）

    static final class Env implements PcRx.Env, PcRx.Handler {
        final PcLanClient.Peer p;
        final PcFileRx rx;
        volatile boolean wanted = true;
        volatile String state = "";
        final List<String> states = new CopyOnWriteArrayList<String>();
        final List<String> ran = new CopyOnWriteArrayList<String>();
        final List<Map<String, Object>> notes = new CopyOnWriteArrayList<Map<String, Object>>();
        final Map<String, Boolean> sw = new ConcurrentHashMap<String, Boolean>();

        Env(PcLanClient.Peer p, File dest) throws Exception {
            this.p = p;
            rx = new PcFileRx(Files.createTempDirectory("stage").toFile(), PcFileRx.dirSink(dest));
        }

        @Override public boolean wanted() { return wanted; }
        @Override public boolean onLan() { return true; }
        @Override public PcLanClient.Peer peer() { return p; }
        @Override public List<InetAddress> extraBroadcasts() { return null; }
        @Override public void multicast(boolean hold) { }
        @Override public void state(String s, String d) { state = s + " " + d; states.add(state); }
        @Override public void remember(InetSocketAddress a) { }
        @Override public PcRx.Handler handler() { return this; }

        /** 和 PcRxAndroid.enabled 一样：没改过就用 PcRxPolicy.defaultOn。 */
        @Override public boolean enabled(String cmd) {
            Boolean b = sw.get(cmd);
            return b != null ? b : PcRxPolicy.defaultOn(cmd);
        }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> a) {
            ran.add(cmd);
            if (PcRxPolicy.FILE_PUT.equals(cmd)) return rx.receive(a, PC);
            if (PcRxPolicy.NOTIFY.equals(cmd)) {
                notes.add(new TreeMap<String, Object>(a));
                return new PcLanClient.Result(true, "已在手机上弹通知", null, "");
            }
            Map<String, Object> d = new TreeMap<String, Object>();
            if (PcRxPolicy.SHORTCUT_LIST.equals(cmd)) {
                List<Object> l = new ArrayList<Object>();
                Map<String, Object> s = new TreeMap<String, Object>();
                s.put("name", "回家模式");
                s.put("steps", "3 步");
                l.add(s);
                d.put("shortcuts", l);
                return new PcLanClient.Result(true, "手机上有 1 条快捷指令", d, "");
            }
            if (PcRxPolicy.SHORTCUT_RUN.equals(cmd)) return new PcLanClient.Result(true, "手机开始执行快捷指令「" + a.get("name") + "」", null, "");
            if (PcRxPolicy.OPERATE.equals(cmd)) return new PcLanClient.Result(true, "已交给手机上的 OneSay 去做", m("goal", a.get("goal")), "");
            if (PcRxPolicy.OPERATE_STATUS.equals(cmd)) return new PcLanClient.Result(true, "刚才交给手机的目标已经在手机上开始做，结果看手机屏幕", null, "");
            if (PcRxPolicy.STATUS.equals(cmd)) {
                d.put("battery", 80L);
                return new PcLanClient.Result(true, "电量 80%", d, "");
            }
            return new PcLanClient.Result(true, "好", d, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { }
    }

    static <T> T waitFor(java.util.concurrent.Callable<T> c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            T v = c.call();
            if (v != null) return v;
            Thread.sleep(100);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> pcConfirms() throws Exception {
        Object c = PcLinkV2InteropTest.api("state", null).get("confirms");
        return c instanceof List ? (List<Object>) c : Collections.emptyList();
    }

    static void waitChatIdle(long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (!Boolean.TRUE.equals(PcLinkV2InteropTest.api("state", null).get("chat_running"))) return;
            Thread.sleep(150);
        }
        fail("电脑任务一直没结束：" + out());
    }

    // ================================================================== 1 配对 + 发现

    @Test public void a1_pairingStringDiscoveryAndTls() throws Exception {
        PcPairing pp = PcPairing.parse(String.valueOf(PcLinkV2InteropTest.api("pairing", null).get("pairing")));
        assertEquals(PC, pp.peer);
        assertEquals(port, pp.lanPort);
        assertTrue("预览版配对串也带证书指纹（v2，加密）", pp.fp.length() > 0);
        // 走 Tailscale：把配对串里 lan= 换成（或加上）电脑的 Tailscale 地址，手机照样认（明文逗号 / 冒号都行）
        String raw = String.valueOf(PcLinkV2InteropTest.api("pairing", null).get("pairing"));
        String ts = raw.replaceFirst("lan=[^&]*", "lan=127.0.0.1:" + port + ",100.101.102.103:" + port);
        PcPairing tp = PcPairing.parse(ts);
        assertTrue(tp.lanHosts, tp.lanHosts.contains("100.101.102.103:" + port) && tp.lanHosts.contains("127.0.0.1:" + port));
        assertEquals(pp.fp, tp.fp);
        // 只写 Tailscale 地址（UDP 发现走不通时直接探 healthz）：用 127.0.0.1 代替，发现照样找得到
        InetSocketAddress viaHint = PcLanClient.locate(new PcLanClient.Peer(ME, tp.peer, tp.key, "127.0.0.1:" + port, tp.lanPort, "", tp.fp, tls), 3000, null);
        assertNotNull(viaHint);
        // UDP 发现（端口 +1）在 127.0.0.1 上找得到电脑
        InetSocketAddress a = PcLanClient.locate(peer(), 3000, null);
        assertNotNull("UDP 发现没找到电脑", a);
        assertEquals(port, a.getPort());
        PcLanClient.Result r = once("ping", null, 5000);
        assertTrue(r.toString(), r.ok);
        assertTrue("常连是加密的：" + env.state, env.state.contains("加密") && !env.state.contains("未加密"));
    }

    // ================================================================== 2 手机 → 电脑任务（成功 + 推回）

    @Test public void b1_phoneTaskSucceedsAndResultIsPushedBack() throws Exception {
        env.notes.clear();
        PcLanClient.Result r = once("pc_task", m("goal", "DUALS1 在电脑上写一份笔记"), 15000);
        assertTrue(r + " / " + out(), r.ok);
        assertTrue(r.text, r.text.contains("电脑收到了"));
        // 一次性连接用的是同一个本机名：常连不能被它顶掉，结果要能推回来
        Map<String, Object> note = waitFor(() -> env.notes.isEmpty() ? null : env.notes.get(0), 20000);
        assertNotNull("结果没推回手机：states=" + env.states + " llm=" + llmSeen + "\n" + out(), note);
        assertEquals("电脑任务完成", note.get("title"));
        assertTrue(String.valueOf(note.get("text")), String.valueOf(note.get("text")).contains("写好"));
        File f = dir.resolve("ws").resolve("notes").resolve("from-phone.txt").toFile();
        assertTrue("电脑工作区里真写了文件", f.isFile());
        PcLanClient.Result st = once("pc_task_status", null, 8000);
        assertTrue(st.toString(), st.ok);
        assertEquals("done", st.data.get("state"));
        assertTrue(env.state, env.state.startsWith(PcRx.S_UP));
    }

    // ================================================================== 3 审批卡（电脑 + 手机），手机上拒

    @Test public void c1_approvalCardShowsOnPcAndPhoneAndDenyIsReported() throws Exception {
        waitChatIdle(10000);
        env.notes.clear();
        phoneCards.clear();
        PcLanClient.Result r = once("pc_task", m("goal", "DUALS2 在系统目录写个文件"), 15000);
        assertTrue(r + " / " + out(), r.ok);
        List<Object> cs = waitFor(() -> { List<Object> l = pcConfirms(); return l.isEmpty() ? null : l; }, 15000);
        assertNotNull("电脑上没出现确认卡：llm=" + llmSeen + "\n" + out(), cs);
        String c0 = String.valueOf(cs.get(0));
        PcConfirm.Card pc = waitFor(() -> phoneCards.isEmpty() ? null : phoneCards.get(0), 8000);
        assertNotNull("手机上没出现确认卡：" + c0, pc);
        assertEquals(PcConfirm.KIND_PC, pc.kind);
        assertTrue(pc.detail, pc.detail.length() > 0);
        // 手机上点拒绝
        assertEquals(PcConfirm.Answer.ACCEPTED, PcConfirm.answer(pc.id, false, false, false, PcConfirm.Input.TOUCH));
        Map<String, Object> note = waitFor(() -> env.notes.isEmpty() ? null : env.notes.get(0), 20000);
        assertNotNull("拒绝后结果没推回手机：" + out(), note);
        assertFalse("没批准不能说完成：" + note, "电脑任务完成".equals(note.get("title")));
        assertTrue("电脑上的卡收起了", waitFor(() -> pcConfirms().isEmpty() ? Boolean.TRUE : null, 8000) != null);
        assertTrue(String.valueOf(note), String.valueOf(note.get("title")).startsWith("电脑任务"));
        System.out.println("[dual] 拒绝后推回手机的通知：" + note);
    }

    // ================================================================== 4 开关默认关被拒（按手机真实默认值）

    @Test public void d1_defaultOffTogglesAreRefused() throws Exception {
        env.ran.clear();
        assertFalse(PcRxPolicy.defaultOn(PcRxPolicy.OPERATE));
        assertFalse(PcRxPolicy.defaultOn(PcRxPolicy.SHORTCUT_RUN));
        assertTrue("收文件默认开", PcRxPolicy.defaultOn(PcRxPolicy.FILE_PUT));
        assertFalse("总开关「接受电脑指令」默认关", PcRxPolicy.MASTER_DEFAULT);
        for (String cmd : new String[]{"operate", "operate_status", "shortcut_list", "shortcut_run"}) {
            Map<String, Object> a = new TreeMap<String, Object>();
            if (cmd.equals("operate")) a.put("goal", "打开设置");
            if (cmd.equals("shortcut_run")) a.put("name", "回家模式");
            Map<String, Object> x = phone(cmd, a);
            assertEquals(cmd + " " + x, Boolean.FALSE, x.get("ok"));
            assertTrue(cmd + " " + x.get("text"), String.valueOf(x.get("text")).contains("没开"));
        }
        assertTrue("手机什么都没执行：" + env.ran, env.ran.isEmpty());
    }

    // ================================================================== 5 电脑 → 手机任务（对话里交目标，只回已开始）

    @Test public void e1_pcChatHandsGoalToPhone() throws Exception {
        waitChatIdle(10000);
        env.ran.clear();
        env.sw.put(PcRxPolicy.OPERATE, Boolean.TRUE);
        try {
            Map<String, Object> r = PcLinkV2InteropTest.api("chat", m("text", "DUALS3 让手机打开设置看看安卓版本"));
            assertNotEquals(String.valueOf(r), Boolean.FALSE, r.get("ok"));
            assertNotNull("手机没收到按目标操作：" + r + " " + env.ran + " llm=" + llmSeen + "\n" + out(),
                    waitFor(() -> env.ran.contains("operate") ? Boolean.TRUE : null, 20000));
            waitChatIdle(20000);
            // 电脑只说交过去了 / 已开始，不编造结果
            String chat = PcLinkV2InteropTest.http("POST", "/api/state", token, "{}");
            assertTrue(chat.contains("已交给手机"));
            // 再查进展：手机如实说已开始
            Map<String, Object> os = phone("operate_status", null);
            assertEquals(String.valueOf(os), Boolean.TRUE, os.get("ok"));
            assertTrue(String.valueOf(os.get("text")).contains("开始做"));
        } finally {
            env.sw.remove(PcRxPolicy.OPERATE);
        }
    }

    // ================================================================== 6 快捷指令（打开后）

    @Test public void f1_shortcutsAfterTurningOn() throws Exception {
        env.ran.clear();
        env.sw.put(PcRxPolicy.SHORTCUT_RUN, Boolean.TRUE);
        try {
            Map<String, Object> l = phone("shortcut_list", null);
            assertEquals(String.valueOf(l), Boolean.TRUE, l.get("ok"));
            assertTrue(String.valueOf(l.get("data")).contains("回家模式"));
            Map<String, Object> run = phone("shortcut_run", m("name", "回家模式"));
            assertEquals(String.valueOf(run), Boolean.TRUE, run.get("ok"));
            assertTrue(env.ran.contains("shortcut_run"));
        } finally {
            env.sw.remove(PcRxPolicy.SHORTCUT_RUN);
        }
    }

    // ================================================================== 7 文件：大文件分块、校验失败、双向

    static byte[] rnd(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test public void g1_bigFilePhoneToPcInChunks() throws Exception {
        byte[] data = rnd(10 * PcFileRx.CHUNK + 12345, 21); // 11 块，约 5 MB
        File src = new File(dir.toFile(), "大文件-手机.bin");
        FileOutputStream o = new FileOutputStream(src);
        o.write(data);
        o.close();
        final PcLanClient.Peer p = peer();
        final AtomicInteger calls = new AtomicInteger();
        PcLanClient.Result r = PcFileRx.send(src, null, new PcFileRx.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args) {
                calls.incrementAndGet();
                return PcLanClient.once(p, cmd, args, 1500, 30000, null, null);
            }
        }, null);
        assertTrue(r + " / " + out(), r.ok);
        assertEquals("每块一次，没重试", 11, calls.get());
        File got = Link1119InteropTest.find(dir.resolve("ws").toFile(), "大文件-手机.bin");
        assertNotNull(got);
        assertEquals("来自手机", got.getParentFile().getName());
        assertArrayEquals(data, Files.readAllBytes(got.toPath()));
        assertTrue("常连没被一次性连接顶掉：" + env.state, env.state.startsWith(PcRx.S_UP));
    }

    @Test public void g2_badChecksumAndGapAreRejectedWithoutHalfFile() throws Exception {
        byte[] data = rnd(2 * PcFileRx.CHUNK + 99, 33);
        File src = new File(dir.toFile(), "坏校验.bin");
        FileOutputStream o = new FileOutputStream(src);
        o.write(data);
        o.close();
        final PcLanClient.Peer p = peer();
        // 最后一块的 SHA-256 改掉一位
        PcLanClient.Result r = PcFileRx.send(src, null, new PcFileRx.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args) {
                Map<String, Object> a = new TreeMap<String, Object>(args);
                Object s = a.get("sha256");
                if (s != null) a.put("sha256", (String.valueOf(s).charAt(0) == '0' ? "1" : "0") + String.valueOf(s).substring(1));
                return PcLanClient.once(p, cmd, a, 1500, 30000, null, null);
            }
        }, null);
        assertFalse("校验不对必须失败：" + r, r.ok);
        assertTrue(r.text, r.text.contains("失败"));
        assertNull("校验不对不能落盘：", Link1119InteropTest.find(dir.resolve("ws").toFile(), "坏校验.bin"));
        // 跳块（直接发第 2 块）
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("xfer", "gap" + System.nanoTime());
        a.put("name", "跳块.bin");
        a.put("size", (long) data.length);
        a.put("seq", 1L);
        a.put("total", 3L);
        a.put("b64", PcB64.encode(java.util.Arrays.copyOf(data, PcFileRx.CHUNK)));
        PcLanClient.Result g = once("file_put", a, 10000);
        assertFalse("跳块必须拒：" + g, g.ok);
        assertNull(Link1119InteropTest.find(dir.resolve("ws").toFile(), "跳块.bin"));
    }

    @Test public void g3_pcToPhoneFileInChunks() throws Exception {
        byte[] data = rnd(6 * PcFileRx.CHUNK + 7, 44); // 7 块，约 3 MB
        File src = new File(dir.toFile(), "给手机-大.pdf");
        FileOutputStream o = new FileOutputStream(src);
        o.write(data);
        o.close();
        Map<String, Object> sb = new TreeMap<String, Object>();
        sb.put("op", "send_path");
        sb.put("path", src.getAbsolutePath());
        Map<String, Object> started = PcLinkV2InteropTest.api("xfer", sb);
        assertEquals(String.valueOf(started), Boolean.TRUE, started.get("ok"));
        final File got = new File(phoneDl, "给手机-大.pdf");
        assertNotNull("手机没收到：" + env.ran + " " + out(), waitFor(() -> got.isFile() ? Boolean.TRUE : null, 60000));
        assertArrayEquals(data, Files.readAllBytes(got.toPath()));
    }

    // ================================================================== 8 断线重连

    @Test public void h1_reconnectAfterPcRestart() throws Exception {
        waitChatIdle(10000);
        assertTrue(env.state.startsWith(PcRx.S_UP));
        stopDesktop();
        assertNotNull("电脑退出后手机没发现掉线：" + env.states,
                waitFor(() -> env.state.startsWith(PcRx.S_UP) ? null : Boolean.TRUE, 15000));
        startDesktop();
        assertTrue("电脑重启后手机没自己连回来：" + env.states + "\n" + out(), waitUp(20000));
        env.ran.clear();
        env.sw.put(PcRxPolicy.OPERATE, Boolean.TRUE);
        try {
            Map<String, Object> x = phone("operate", m("goal", "打开设置"));
            assertEquals(String.valueOf(x), Boolean.TRUE, x.get("ok"));
        } finally {
            env.sw.remove(PcRxPolicy.OPERATE);
        }
        PcLanClient.Result r = once("ping", null, 5000);
        assertTrue("证书没变，旧配对继续能用：" + r, r.ok);
    }

    // ================================================================== 9 预览版默认端口

    @Test public void i1_previewDefaultPortIs47920() throws Exception {
        boolean free;
        try {
            ServerSocket s = new ServerSocket(47920);
            s.close();
            java.net.DatagramSocket d = new java.net.DatagramSocket(47921);
            d.close();
            free = true;
        } catch (Exception e) {
            free = false;
        }
        Assume.assumeTrue("47920 / 47921 被占，跳过", free);
        Path d2 = Files.createTempDirectory("dual1122-def");
        Process p2 = null;
        try {
            Path c2 = d2.resolve("desktop.properties");
            Files.write(c2, ("device_id=pc-def\nkind=pc\nptt=off\npair_key=" + PcB64.encodeUrl(KEY) + "\nworkspace_dir=" + d2.resolve("ws") + "\n").getBytes(StandardCharsets.UTF_8));
            String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-Duser.home=" + d2, "-jar", jar.getAbsolutePath(), "--config", c2.toString(), "app", "--tray");
            pb.environment().put("ONESAY_TEST_ACTIONS", "1");
            pb.environment().put("ONESAY_TEST_LAUNCH_KEY", PcLinkV2InteropTest.LAUNCH_KEY);
            pb.environment().put("ONESAY_UI_PORT", "0");
            pb.environment().remove("APPDATA");
            pb.environment().remove("ONESAY_LAN");
            pb.redirectErrorStream(true).redirectOutput(d2.resolve("out.txt").toFile());
            p2 = pb.start();
            long end = System.currentTimeMillis() + 20000;
            boolean listening = false;
            while (System.currentTimeMillis() < end && !listening) {
                String o = new String(Files.readAllBytes(d2.resolve("out.txt")), StandardCharsets.UTF_8);
                listening = o.contains("界面 http://");
                if (!listening) Thread.sleep(200);
            }
            assertTrue(listening);
            String conf = new String(Files.readAllBytes(c2), StandardCharsets.UTF_8);
            assertFalse("预览版不应该默认用正式版端口：" + conf, conf.contains("lan_port=47820"));
            PcLanClient.Peer pp = new PcLanClient.Peer("phone-def", "pc-def", KEY, "127.0.0.1:47920", 47920, "", "", null);
            InetSocketAddress a = null;
            for (int i = 0; i < 10 && a == null; i++) a = PcLanClient.locate(pp, 1500, null);
            assertNotNull("预览版默认端口 47920 / 发现 47921 上找不到电脑", a);
            assertEquals(47920, a.getPort());
            assertTrue("47920 上的局域网服务能答 healthz", PcLanClient.healthz(new InetSocketAddress("127.0.0.1", 47920), 2000));
            System.out.println("[dual] 预览版默认端口：局域网 47920 / 发现 47921 OK");
        } finally {
            if (p2 != null) {
                p2.destroy();
                if (!p2.waitFor(5, TimeUnit.SECONDS)) p2.destroyForcibly();
            }
            Link1119InteropTest.rm(d2.toFile());
        }
    }

}
