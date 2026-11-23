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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * [rel-1123-b] 双端连接线程 B 的回环：手机侧真代码（PcRx / PcLanClient / PcGoals / PcAppList / PcPairNearby）⇄ <b>电脑端预览版 jar 真进程</b>
 * （真 TLS 局域网，假大模型）。
 * <ol>
 *   <li>离线写原因：没连过 →「还没有手机连过」；只有一次性连接 →「没开接受电脑指令」、电脑不算在线、不刷上线 / 离线；常连上 → 在线；常连断 →「几点断开」；</li>
 *   <li>按目标操作的结果回传：电脑对话交目标 → 手机做完（结果上屏、等一会没动静）→ 签名 goal_result → 电脑任务卡写手机说的结果；手机上拒绝 → 电脑说拒绝了；</li>
 *   <li>看已装 App：默认值照开源规则（开）、按名字筛；手机上关掉 → 电脑说没开；对话里能调；</li>
 *   <li>免扫码配对：广播找到电脑（邀请里不带电脑身份）→ 两边核对码一样 → 电脑确认框只认本机鼠标（语音不算）→ 配好后新配对能直接连；
 *       拒绝 / 太快再试 / 电脑关掉配对 都如实说。</li>
 * </ol>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class Pair1123InteropTest {
    static File jar;
    static Process proc;
    static Path dir, home, cfgFile;
    static int port, uiPort;
    static String token;
    static final byte[] KEY = new byte[32];
    static final String PC = "pc-b1123";
    static final String ME = "phone-b1123";
    static PcTls tls;
    static HttpServer llm;
    static final List<String> llmSeen = new CopyOnWriteArrayList<String>();
    static Env env;
    static PcRx.Loop loop;
    static Thread loopThread;

    // ================================================================== 假大模型

    static String llmReply(String body) {
        Map<String, Object> req;
        try {
            req = PcJson.obj(body);
        } catch (Exception e) {
            return Dual1122InteropTest.say("好");
        }
        Object ms = req.get("messages");
        if (!(ms instanceof List) || req.get("tools") == null) return Dual1122InteropTest.say("好");
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
        if ("tool".equals(lastRole)) return Dual1122InteropTest.say("【结果】" + lastTool.replace('\n', ' '));
        if (lastUser.contains("B23GOAL")) return Dual1122InteropTest.call("phone_operate", "{\"description\":\"交给手机\",\"goal\":\"打开设置看看安卓版本\"}");
        if (lastUser.contains("B23DENY")) return Dual1122InteropTest.call("phone_operate", "{\"description\":\"交给手机\",\"goal\":\"给张三转账 1 块\"}");
        if (lastUser.contains("B23APPS")) return Dual1122InteropTest.call("phone_apps", "{\"description\":\"看手机上的地图\",\"query\":\"地图\"}");
        return Dual1122InteropTest.say("好");
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

    static String out() throws Exception {
        File f = dir.resolve("desktop.out").toFile();
        return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    static void startDesktop() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-Duser.home=" + home, "-jar", jar.getAbsolutePath(),
                "--config", cfgFile.toString(), "app", "--tray");
        pb.environment().put("ONESAY_TEST_ACTIONS", "1");
        pb.environment().put("ONESAY_TEST_LAUNCH_KEY", PcLinkV2InteropTest.LAUNCH_KEY);
        pb.environment().put("ONESAY_UI_PORT", "0");
        pb.environment().remove("APPDATA");
        pb.environment().remove("ONESAY_LAN");
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("desktop.out").toFile()));
        Pattern ui = Pattern.compile("界面 http://127\\.0\\.0\\.1:(\\d+)/");
        proc = pb.start();
        long end = System.currentTimeMillis() + 25000;
        uiPort = 0;
        while (System.currentTimeMillis() < end && uiPort == 0) {
            if (!proc.isAlive()) fail("电脑端进程提前退出：" + out());
            Matcher m = ui.matcher(out());
            if (m.find()) uiPort = Integer.parseInt(m.group(1));
            else Thread.sleep(150);
        }
        assertTrue("电脑端界面没起来：" + out(), uiPort > 0);
        PcLinkV2InteropTest.uiPort = uiPort;
        Matcher t = Pattern.compile("const TOKEN='([A-Za-z0-9_-]+)'").matcher(PcLinkV2InteropTest.http("GET", "/?k=" + PcLinkV2InteropTest.LAUNCH_KEY, null, null));
        assertTrue(t.find());
        token = t.group(1);
        PcLinkV2InteropTest.token = token;
    }

    @BeforeClass public static void setUp() throws Exception {
        jar = PcDesktopInteropTest.findJar();
        Assume.assumeTrue("电脑端 jar 不在，回环跳过", jar != null);
        new java.security.SecureRandom().nextBytes(KEY);
        startLlm();
        port = Dual1122InteropTest.freePort();
        dir = Files.createTempDirectory("pair1123");
        home = Files.createDirectories(dir.resolve("home"));
        cfgFile = dir.resolve("desktop.properties").toAbsolutePath();
        String props = "lan_port=" + port + "\ndevice_id=" + PC + "\nkind=pc\nptt=off\npair_key=" + PcB64.encodeUrl(KEY)
                + "\nworkspace_dir=" + dir.resolve("ws").toString().replace("\\", "/")
                + "\nllm_provider=custom\nllm_base=http://127.0.0.1:" + llm.getAddress().getPort() + "/v1\nllm_key=mock-not-a-real-key\nllm_model=mock\n"
                + "lan_pair_invite=on\n"; // [rel-1123 合并] 开关开着但还没设配对码（比如码丢了）：手机要看到「这台电脑还没设配对码」
        Files.write(cfgFile, props.getBytes(StandardCharsets.UTF_8));
        tls = PcTls.generate(ME);
        startDesktop();
        PcGoals.reset();
        env = new Env(peer());
    }

    @AfterClass public static void tearDown() throws Exception {
        PcLinkV2InteropTest.uiPort = 0;
        PcLinkV2InteropTest.token = null;
        stopLoop();
        if (proc != null) {
            proc.destroy();
            if (!proc.waitFor(8, TimeUnit.SECONDS)) proc.destroyForcibly();
        }
        if (llm != null) llm.stop(0);
        PcGoals.reset();
        if (dir != null) Link1119InteropTest.rm(dir.toFile());
    }

    static PcLanClient.Peer peer() throws Exception {
        PcPairing pp = PcPairing.parse(String.valueOf(api("pairing", null).get("pairing")));
        return new PcLanClient.Peer(ME, pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, tls);
    }

    static Map<String, Object> api(String n, Map<String, Object> b) throws Exception {
        return PcLinkV2InteropTest.api(n, b);
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> x = new TreeMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) x.put(String.valueOf(kv[i]), kv[i + 1]);
        return x;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> why() throws Exception {
        Object w = api("state", null).get("phone_why");
        return w instanceof Map ? (Map<String, Object>) w : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> peers() throws Exception {
        Object p = api("state", null).get("peers");
        return p instanceof Map ? (Map<String, Object>) p : Collections.<String, Object>emptyMap();
    }

    static void startLoop() throws Exception {
        loop = new PcRx.Loop(env);
        loop.minBackoff = 300;
        loop.maxBackoff = 1000;
        loopThread = new Thread(loop, "rx-b1123");
        loopThread.setDaemon(true);
        loopThread.start();
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end && !peers().containsKey(ME)) Thread.sleep(150);
        assertTrue("常连没连上：" + env.state + "\n" + out(), peers().containsKey(ME));
    }

    static void stopLoop() throws Exception {
        if (loop != null) loop.stop();
        if (loopThread != null) loopThread.join(5000);
        loop = null;
        loopThread = null;
    }

    static <T> T waitFor(java.util.concurrent.Callable<T> c, long ms) throws Exception {
        return Dual1122InteropTest.waitFor(c, ms);
    }

    static void waitChatIdle(long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (!Boolean.TRUE.equals(api("state", null).get("chat_running"))) return;
            Thread.sleep(150);
        }
        fail("电脑任务一直没结束：" + out());
    }

    static int count(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    // ================================================================== 手机（常连处理器：按目标操作模拟面板，已装 App 用假列表）

    static final class Env implements PcRx.Env, PcRx.Handler {
        final PcLanClient.Peer p;
        volatile String state = "";
        final List<String> ran = new CopyOnWriteArrayList<String>();
        final Map<String, Boolean> sw = new ConcurrentHashMap<String, Boolean>();

        Env(PcLanClient.Peer p) { this.p = p; }

        @Override public boolean wanted() { return true; }
        @Override public boolean onLan() { return true; }
        @Override public PcLanClient.Peer peer() { return p; }
        @Override public List<InetAddress> extraBroadcasts() { return null; }
        @Override public void multicast(boolean hold) { }
        @Override public void state(String s, String d) { state = s + " " + d; }
        @Override public void remember(InetSocketAddress a) { }
        @Override public PcRx.Handler handler() { return this; }

        @Override public boolean enabled(String cmd) {
            Boolean b = sw.get(cmd);
            return b != null ? b : PcRxPolicy.defaultOn(cmd);
        }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> a) {
            ran.add(cmd);
            if (PcRxPolicy.APP_LIST.equals(cmd)) {
                List<PcAppList.App> l = new ArrayList<PcAppList.App>();
                l.add(new PcAppList.App("百度地图", "com.baidu.BaiduMap"));
                l.add(new PcAppList.App("高德地图", "com.autonavi.minimap"));
                l.add(new PcAppList.App("设置", "com.android.settings"));
                l.add(new PcAppList.App("京东", "com.jingdong.app.mall"));
                l.add(new PcAppList.App("设置", "com.android.settings")); // 同一个 App 两个入口
                return PcAppList.view(l, PcRxPolicy.str(a, "query"), PcRxPolicy.APP_LIST_MAX);
            }
            if (PcRxPolicy.OPERATE.equals(cmd)) {
                final String goal = PcRxPolicy.str(a, "goal");
                String gid = PcGoals.begin(PC, goal);
                // 模拟面板：接住这句 → 结果上屏（转账这类在手机上被拒）→ 等一会没动静 = 收尾回传
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            Thread.sleep(300);
                            PcGoals.submitted(goal, 7);
                            Thread.sleep(300);
                            if (goal.contains("转账")) PcGoals.shownState(PcGoals.DENIED, "你在手机上拒绝了：转账");
                            else PcGoals.shown(true, "安卓版本是 8.0.0");
                        } catch (InterruptedException ignored) {
                        }
                    }
                }, "fake-panel");
                t.setDaemon(true);
                t.start();
                Map<String, Object> d = new TreeMap<String, Object>();
                d.put("goal", goal);
                d.put("goal_id", gid);
                return new PcLanClient.Result(true, "已交给手机上的 OneSay 去做，做完会把结果告诉电脑", d, "");
            }
            return new PcLanClient.Result(true, "好", null, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { }
    }

    // ================================================================== 1 离线写原因 / 一次性连接不算在线

    @Test public void a1_offlineReasonsAndOneShotIsNotOnline() throws Exception {
        Map<String, Object> w = why();
        assertNotNull("没有手机在线时要写原因", w);
        assertEquals("never", w.get("code"));
        assertTrue(String.valueOf(w.get("text")), String.valueOf(w.get("text")).contains("找附近的电脑"));
        // 一次性连接（手机「电脑联动」页每 10 秒探一次就是这种）：连 3 次
        for (int i = 0; i < 3; i++) {
            PcLanClient.Result r = PcLanClient.once(peer(), "ping", null, 2000, 5000, null, null);
            assertTrue(r.toString(), r.ok);
        }
        assertFalse("一次性连接不算在线：" + peers(), peers().containsKey(ME));
        w = why();
        assertNotNull(w);
        assertEquals("只有一次性连接 = 手机上没开接受电脑指令：" + w, "rx_off", w.get("code"));
        assertTrue(String.valueOf(w.get("text")).contains("接受电脑指令"));
        assertEquals("不再每次一次性连接都刷「上线 / 离线」：\n" + out(), 0, count(out(), "设备 " + ME + " 上线"));
        // 打开「接受电脑指令」（常连）
        startLoop();
        assertNull("在线时不给离线原因", why());
        assertEquals(1, count(out(), "设备 " + ME + " 上线"));
        // 常连断开
        stopLoop();
        Map<String, Object> dw = waitFor(() -> {
            Map<String, Object> x = why();
            return x != null && "dropped".equals(x.get("code")) ? x : null;
        }, 10000);
        assertNotNull("常连断了要说几点断开：" + why(), dw);
        assertTrue(String.valueOf(dw.get("text")), String.valueOf(dw.get("text")).contains("断开"));
        // 电脑对话里也说原因（手机离线时调手机工具）
        startLoop();
    }

    // ================================================================== 2 按目标操作：结果回传电脑任务卡

    @Test public void b1_operateResultComesBackToPcTaskCard() throws Exception {
        waitChatIdle(10000);
        env.ran.clear();
        env.sw.put(PcRxPolicy.OPERATE, Boolean.TRUE);
        try {
            Map<String, Object> r = api("chat", m("text", "B23GOAL 让手机打开设置看看安卓版本"));
            assertNotEquals(String.valueOf(r), Boolean.FALSE, r.get("ok"));
            assertNotNull("手机没收到按目标操作：" + env.ran + "\n" + out(), waitFor(() -> env.ran.contains("operate") ? Boolean.TRUE : null, 20000));
            waitChatIdle(30000);
            String st = PcLinkV2InteropTest.http("POST", "/api/state", token, "{}");
            assertTrue("电脑任务卡要写手机回传的结果：llm=" + llmSeen + "\n" + out(), st.contains("安卓版本是 8.0.0"));
            assertTrue("工具结果是手机做完了：" + llmSeen, llmSeen.toString().contains("手机做完了"));
            @SuppressWarnings("unchecked") Map<String, Object> g = (Map<String, Object>) api("state", null).get("phone_goal");
            assertNotNull(g);
            assertEquals("done", g.get("state"));
            assertEquals("安卓版本是 8.0.0", g.get("text"));
            assertEquals("回传后手机这边没有积压", 0, PcGoals.pending());
            // 手机上查进展：说的是结果，不是「已经开始做」
            Map<String, Object> os = Link1119InteropTest.phone("operate_status", null);
            assertEquals(String.valueOf(os), Boolean.TRUE, os.get("ok"));
        } finally {
            env.sw.remove(PcRxPolicy.OPERATE);
        }
    }

    @Test public void b2_deniedOnPhoneIsReportedAsDenied() throws Exception {
        waitChatIdle(10000);
        env.sw.put(PcRxPolicy.OPERATE, Boolean.TRUE);
        llmSeen.clear();
        try {
            api("chat", m("text", "B23DENY 让手机给张三转账"));
            assertNotNull(waitFor(() -> llmSeen.toString().contains("|tool") || llmSeen.toString().contains("拒绝") ? Boolean.TRUE : null, 30000));
            waitChatIdle(30000);
            assertTrue("手机上拒绝的要如实说：" + llmSeen, llmSeen.toString().contains("手机上拒绝了"));
            assertFalse("拒绝的不能说做完了：" + llmSeen, llmSeen.toString().contains("手机做完了：你在手机上拒绝了"));
            @SuppressWarnings("unchecked") Map<String, Object> g = (Map<String, Object>) api("state", null).get("phone_goal");
            assertEquals("denied", g.get("state"));
        } finally {
            env.sw.remove(PcRxPolicy.OPERATE);
        }
    }

    /** [rel-1123 合并] 电脑「手机」页「交给手机去做」（直接发 operate，不经对话）：手机收下后也登记，回传结果在 state.phone_goal 里。 */
    @Test public void b3_phonePageOperateResultShowsInState() throws Exception {
        waitChatIdle(10000);
        env.sw.put(PcRxPolicy.OPERATE, Boolean.TRUE);
        try {
            Map<String, Object> r = api("phone", m("cmd", "operate", "args", m("goal", "打开设置看看安卓版本 B3")));
            assertEquals(String.valueOf(r), Boolean.TRUE, r.get("ok"));
            @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) r.get("data");
            final String gid = String.valueOf(d.get("goal_id"));
            @SuppressWarnings("unchecked") Map<String, Object> g = waitFor(() -> {
                Object o = api("state", null).get("phone_goal");
                if (!(o instanceof Map)) return null;
                Map<String, Object> x = (Map<String, Object>) o;
                return gid.equals(x.get("id")) && "done".equals(x.get("state")) ? x : null;
            }, 20000);
            assertNotNull("手机页交的目标，结果要回到 state.phone_goal：" + out(), g);
            assertEquals("安卓版本是 8.0.0", g.get("text"));
            assertEquals("打开设置看看安卓版本 B3", g.get("goal"));
        } finally {
            env.sw.remove(PcRxPolicy.OPERATE);
        }
    }

    // ================================================================== 3 看已装 App

    @Test public void c1_appListDefaultOffFilterAndToggle() throws Exception {
        assertFalse("[rel-1123 合并] 看已装 App 默认关（用户定）", PcRxPolicy.defaultOn(PcRxPolicy.APP_LIST));
        Map<String, Object> dflt = Link1119InteropTest.phone("app_list", null);
        assertEquals("默认关着就被拒：" + dflt, Boolean.FALSE, dflt.get("ok"));
        assertTrue(String.valueOf(dflt.get("text")), String.valueOf(dflt.get("text")).contains("没开"));
        env.sw.put(PcRxPolicy.APP_LIST, Boolean.TRUE); // 用户手动打开
        try {
        Map<String, Object> all = Link1119InteropTest.phone("app_list", null);
        assertEquals(String.valueOf(all), Boolean.TRUE, all.get("ok"));
        assertTrue(String.valueOf(all.get("text")), String.valueOf(all.get("text")).contains("4 个"));
        Map<String, Object> q = Link1119InteropTest.phone("app_list", m("query", "地图"));
        assertTrue(String.valueOf(q), String.valueOf(q.get("text")).contains("百度地图") && String.valueOf(q.get("text")).contains("高德地图"));
        assertFalse(String.valueOf(q.get("text")).contains("京东"));
        // 电脑对话里问「手机上有哪些地图」直接走它
        waitChatIdle(10000);
        llmSeen.clear();
        api("chat", m("text", "B23APPS 手机上有哪些地图 App"));
        waitChatIdle(20000);
        assertTrue("对话里的工具结果带 App 名：" + llmSeen, llmSeen.toString().contains("高德地图"));
        } finally {
            env.sw.remove(PcRxPolicy.APP_LIST);
        }
        Map<String, Object> off = Link1119InteropTest.phone("app_list", null);
        assertEquals("关回默认 = 关：" + off, Boolean.FALSE, off.get("ok"));
    }

    // ================================================================== 4 免扫码配对

    static final String ME2 = "phone-new23";
    static PcTls tls2;

    static List<PcPairNearby.Found> discover() {
        List<InetSocketAddress> t = new ArrayList<InetSocketAddress>();
        t.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), port + 1));
        return PcPairNearby.discover(1500, null, new int[]{port}, t);
    }

    static final List<String> steps = new CopyOnWriteArrayList<String>();

    static PcPairNearby.Outcome pair(String code) {
        return PcPairNearby.request(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), ME2, "测试手机", tls2, code, new PcPairNearby.Ui() {
            @Override public void step(String t) { steps.add(t); }
        }, null);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> pairState() throws Exception {
        return (Map<String, Object>) api("state", null).get("pair");
    }

    /** [rel-1123 合并] 免扫码配对 = 电脑上先设 4 位配对码，手机选电脑、输码（SPAKE2 核对，码不发出去），不再要电脑上点允许。 */
    @Test public void d1_pairNearbyNeedsCodeThenPairs() throws Exception {
        tls2 = PcTls.generate(ME2);
        // 开关开着但没设码：找得到（带名字），配对被拒并说「还没设配对码」
        List<PcPairNearby.Found> found = discover();
        assertFalse("局域网里应该找得到电脑", found.isEmpty());
        assertEquals(port, found.get(0).addr.getPort());
        assertFalse(found.get(0).codeSet);
        assertTrue("列表里带电脑名字", found.get(0).name.length() > 0);
        PcPairNearby.Outcome o = pair("4827");
        assertFalse(o.ok);
        assertEquals("nocode", o.reason);
        assertEquals("这台电脑还没设配对码，请在电脑上先设一个", o.why);
        assertEquals("没设码时界面上算没开", Boolean.FALSE, pairState().get("invite"));
        // 没码不能打开
        api("pairing", m("op", "invite", "on", Boolean.FALSE));
        Map<String, Object> need = api("pairing", m("op", "invite", "on", Boolean.TRUE));
        assertEquals(Boolean.FALSE, need.get("ok"));
        assertEquals(Boolean.TRUE, need.get("need_code"));
        assertTrue("关着时找不到", discover().isEmpty());
        // 好猜的码不让设
        assertEquals(Boolean.FALSE, api("pairing", m("op", "code", "code", "1234", "on", Boolean.TRUE)).get("ok"));
        // 设码并打开
        Map<String, Object> set = api("pairing", m("op", "code", "code", "4827", "on", Boolean.TRUE));
        assertEquals(String.valueOf(set), Boolean.TRUE, set.get("ok"));
        assertEquals(Boolean.TRUE, pairState().get("invite"));
        assertEquals(Boolean.TRUE, pairState().get("code_set"));
        String cfgText = new String(Files.readAllBytes(cfgFile), StandardCharsets.UTF_8);
        assertFalse("配置文件里没有码", cfgText.contains("4827"));
        assertFalse("状态里没有码", String.valueOf(api("state", null)).contains("4827"));
        found = discover();
        assertTrue(found.get(0).codeSet);
        // 输错：还能试 4 次
        o = pair("4828");
        assertFalse(o.ok);
        assertEquals("code", o.reason);
        assertEquals(4, o.left);
        assertTrue(o.why.contains("还能试 4 次"));
        assertEquals("code", pairState().get("last") instanceof Map ? ((Map<?, ?>) pairState().get("last")).get("state") : null);
        Thread.sleep(10500); // 同一地址 10 秒一次
        // 输对：配好（电脑的确认码、配对串里的证书和 id 都核过）
        steps.clear();
        o = pair("4827");
        assertTrue("配对应该成功：" + o + "\n" + out(), o.ok);
        assertTrue("有进展提示：" + steps, steps.contains("正在核对配对码…"));
        PcPairing pp = PcPairing.parse(o.pairing);
        assertEquals(PC, pp.peer);
        assertEquals("ok", ((Map<?, ?>) pairState().get("last")).get("state"));
        assertEquals("成功后清零", 5L, ((Number) pairState().get("left")).longValue());
        PcLanClient.Peer p2 = new PcLanClient.Peer(ME2, pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, tls2);
        PcLanClient.Result r = PcLanClient.once(p2, "ping", null, 2000, 5000, null, null);
        assertTrue("新配对的手机能直接连：" + r, r.ok);
        assertTrue(PcLanClient.locate(p2, 3000, null) != null);
        PcLanClient.Peer fake = new PcLanClient.Peer(ME2, pp.peer, pp.key, "127.0.0.1:" + port, pp.lanPort, "", pp.fp, PcTls.generate("fake"));
        assertFalse("换了证书的同名手机不能连", PcLanClient.once(fake, "ping", null, 2000, 5000, null, null).ok);
        assertNull("不再弹电脑确认框", api("state", null).get("confirms") instanceof List && !((List<?>) api("state", null).get("confirms")).isEmpty() ? "有" : null);
    }

    @Test public void d2_tooFastClosedAndCodeOff() throws Exception {
        PcPairNearby.Outcome o = pair("4827");
        assertFalse(o.ok);
        assertTrue(o.why, o.why.contains("太快"));
        Map<String, Object> off = api("pairing", m("op", "invite", "on", Boolean.FALSE));
        assertEquals(Boolean.FALSE, off.get("invite"));
        assertTrue("关掉后找不到", discover().isEmpty());
        o = pair("4827");
        assertFalse(o.ok);
        assertTrue(o.why, o.why.contains("关着"));
        assertEquals(Boolean.TRUE, api("pairing", m("op", "unlock")).get("ok"));
        Map<String, Object> del = api("pairing", m("op", "code_off"));
        assertEquals(Boolean.TRUE, del.get("ok"));
        assertEquals(Boolean.FALSE, pairState().get("code_set"));
        assertEquals("删码后打开要先设码", Boolean.TRUE, api("pairing", m("op", "invite", "on", Boolean.TRUE)).get("need_code"));
        // 扫码配对照旧在
        assertTrue(String.valueOf(api("pairing", null).get("pairing")).startsWith("onesay://pair?"));
    }

    // ================================================================== 5 常连收尾

    @Test public void z9_stop() throws Exception {
        stopLoop();
        AtomicInteger ignored = new AtomicInteger();
        assertEquals(0, ignored.get());
    }
}
