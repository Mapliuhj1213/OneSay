package com.onesay.desktop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 设置文件读写（Config#load / save：坏值回落默认，旧三档映射两轴，approve_kinds / shortcut_approvals / net_allow 读进来就过滤）、
 * 档位不一致时按旧三档（Config#mode）、启动参数（Main：--config 在哪都行、子命令区分大小写、autostart 只在 Windows）、
 * 单实例探测（App#ping：只有 /ping 回 200 且正文是 onesay 才算已开着；App#uiPort 的环境 / 属性解析）。
 */
@RunWith(Parameterized.class)
public class AdvConfigTest {

    static final String H64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** {文件内容, 字段=期望;字段=期望…}。 */
    static final String[][] LOAD = {{"", "lan=47820;sb=workspace_write;ap=untrusted;plugin=true;kinds="},
            {"lan_port=abc", "lan=47820"}, {"lan_port= 1234 ", "lan=1234"}, {"lan_port=99999", "lan=99999;lanOn=false"}, {"lan_port=-5", "lan=-5;lanOn=false"},
            {"lan_port=0", "lan=0;lanOn=false"}, {"pc_sandbox=garbage", "sb=workspace_write"}, {"pc_sandbox=FULL_ACCESS", "sb=full_access;level=full"},
            {"pc_sandbox=完全访问", "sb=full_access"}, {"pc_approval=NEVER", "ap=never"}, {"pc_approval=从不", "ap=never"},
            {"pc_level=full", "sb=full_access;ap=on_request;level=full"}, {"pc_level=read_only", "sb=read_only;ap=untrusted;level=read_only"},
            {"pc_level=nonsense", "sb=workspace_write;ap=untrusted;level=auto"}, {"pc_level=full\npc_sandbox=read_only", "sb=read_only;ap=on_request;level=read_only"},
            {"pc_level=只读", "sb=read_only"}, {"plugin_enabled=OFF", "plugin=false"}, {"plugin_enabled=0", "plugin=true"}, {"plugin_enabled=", "plugin=true"},
            {"plugin_encoded=off", "encoded=false"}, {"approve_kinds=pc.shell_exec,PC.POWER,bogus,pc.shell_exec,;;phone.system_app", "kinds=pc.shell_exec,pc.power,phone.system_app"},
            {"approve_kinds=*", "kinds="}, {"approve_kinds=pc.shell_exec pc.url；pc.setting", "kinds=pc.shell_exec,pc.url,pc.setting"},
            {"shortcut_approvals=a.ps1=" + H64 + ",bad=xyz,=abc,b.ps1=" + H64.substring(1), "appr=a.ps1=" + H64},
            {"shortcut_approvals=%E4%B8%AD.ps1=" + H64.toUpperCase(), "appr=%E4%B8%AD.ps1=" + H64},
            {"shortcut_approvals=C%3A%5Cx%5CRun.PS1=" + H64, "appr=run.ps1=" + H64},
            {"net_allow=example.com, bad entry,*.x.org,a@b", "net=example.com,bad,entry,x.org"}, {"net_allow=https://A.COM/x;;b.com.", "net=a.com,b.com"},
            {"media_quality=4k", "mq=best"}, {"media_quality=720P", "mq=720"}, {"ptt=off", "ptt=false"}, {"lan_plain=OFF", "plain=false"},
            {"asr_mode=local", "asr=local"}, {"asr_mode=cloud", "asr=auto"}, {"asr_pref=bogus", "asr=auto"}, {"asr_pref=LOCAL\nasr_mode=cloud", "asr=local"},
            {"\u0001\u0002=\u0003\nfoo", "lan=47820;sb=workspace_write"}, {"=value\n#comment\n!also comment\nkeyonly", "lan=47820"},
            {"lan_port=1\nlan_port=2", "lan=2"}, {"lan_port=1234\r\npc_approval=never\r\n", "lan=1234;ap=never"},
            {"trusted_domains=\\u4f8b\\u5b50.cn", "trusted=例子.cn"}, {"pc_sandbox=full_acc", "sb=workspace_write"}, {"pc_sandbox = read_only", "sb=read_only"},
            {"pc_sandbox:full_access", "sb=full_access"}, {"  pc_approval  never", "ap=never"}, {"lan_port=12\\\n34", "lan=1234"}};

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("config");
        for (String[] l : LOAD) r.add("load [" + l[0] + "]", "load", l[0], l[1]);
        String[][] rt = {{"trusted", "例子.测试,a=b:c#d!e"}, {"relayToken", "tok=:#!\\ 中"}, {"workspaceDir", "C:\\用户\\工作 区\\x"}, {"mediaDir", "a\nb\tc"},
                {"apps", "记事本=C:\\Windows\\notepad.exe;计算器=calc.exe"}, {"pairKey", ""}, {"approveKinds", "pc.power,pc.url"},
                {"shortcutApprovals", "a%2Cb%3Dc.ps1=" + H64}, {"deviceId", "pc-中文"}, {"llmKey", "sk-" + "x".repeat(40)}, {"pttSkip", "mstsc.exe, 游戏.exe"}};
        for (String[] x : rt) r.add("roundtrip " + x[0], "roundtrip", x[0], x[1]);
        for (LinkPolicy.Mode m : AdvSupport.allModes()) r.add("mode save/load " + m, "modesave", m.toString(), null);
        String[][] mix = {{"full_access", "never", "read_only", "read_only/untrusted"}, {"read_only", "untrusted", "read_only", "read_only/untrusted"},
                {"workspace_write", "never", "full", "full_access/on_request"}, {"full_access", "untrusted", "full", "full_access/untrusted"},
                {"read_only", "never", "auto", "workspace_write/untrusted"}, {"workspace_write", "on_request", "auto", "workspace_write/on_request"}};
        for (String[] x : mix) r.add("mode mix " + String.join("/", x), "modemix", String.join("|", x), null);
        r.add("save perms", "perms", null, null);
        // 启动参数
        String[][] argv = {{"version", "version"}, {"--config|$CFG|version", "version"}, {"version|--config|$CFG", "version"}, {"--config", "usage"},
                {"VERSION|--config|$CFG", "usage"}, {"bogus|--config|$CFG", "usage"}, {"autostart|--config|$CFG", "notwin"}, {"autostart|on|--config|$CFG", "notwin"},
                {"--config|$MISSING|version", "version"}};
        for (String[] a : argv) r.add("argv [" + a[0] + "]", "argv", a[0], a[1]);
        // 单实例探测
        for (String s : new String[]{"none", "onesay", "other", "404", "onesay-trailing"}) r.add("ping " + s, "ping", s, null);
        String[][] port = {{"abc", "47830"}, {" 123 ", "123"}, {"0", "0"}, {"", "47830"}, {"65536x", "47830"}};
        for (String[] p : port) r.add("uiPort [" + p[0] + "]", "uiport", p[0], p[1]);
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public String in;
    @Parameterized.Parameter(3) public String want;

    static Path tmp(String content) throws Exception {
        Path p = Files.createTempFile("advcfg", ".properties");
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    @Test public void config() throws Exception {
        switch (kind) {
            case "load": load(); break;
            case "roundtrip": roundtrip(); break;
            case "modesave": {
                String[] p = in.split("/");
                Config c = new Config(tmp(""));
                c.setMode(LinkPolicy.Mode.of(p[0], p[1]));
                c.save();
                Config b = Config.load(c.file);
                assertEquals(name, in, b.mode().toString());
                assertEquals(c.level(), b.level());
                break;
            }
            case "modemix": {
                String[] p = in.split("\\|");
                Config c = new Config(null);
                c.pcSandbox = p[0];
                c.pcApproval = p[1];
                c.pcLevel = p[2];
                assertEquals(name, p[3], c.mode().toString());
                break;
            }
            case "perms": {
                Config c = new Config(tmp(""));
                c.llmKey = "secret";
                c.save();
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(c.file)));
                assertTrue(new String(Files.readAllBytes(c.file), StandardCharsets.UTF_8).startsWith("#OneSay desktop - contains secrets, do not share"));
                break;
            }
            case "argv": argv(); break;
            case "ping": ping(); break;
            case "uiport": {
                String old = System.getProperty("onesay.ui.port");
                try {
                    System.setProperty("onesay.ui.port", in);
                    assertEquals(name, Integer.parseInt(want), App.uiPort());
                } finally {
                    if (old == null) System.clearProperty("onesay.ui.port"); else System.setProperty("onesay.ui.port", old);
                }
                break;
            }
            default: throw new AssertionError(kind);
        }
    }

    void load() throws Exception {
        Config c = Config.load(tmp(in));
        for (String kv : want.split(";")) {
            int eq = kv.indexOf('=');
            String k = kv.substring(0, eq), v = kv.substring(eq + 1);
            String got;
            switch (k) {
                case "lan": got = String.valueOf(c.lanPort); break;
                case "lanOn": got = String.valueOf(c.lanEnabled()); break;
                case "sb": got = c.mode().sandbox.id; break;
                case "ap": got = c.mode().approval.id; break;
                case "level": got = c.pcLevel; break;
                case "plugin": got = String.valueOf(c.pluginEnabled); break;
                case "encoded": got = String.valueOf(c.pluginEncoded); break;
                case "kinds": got = c.approveKinds; break;
                case "appr": got = c.shortcutApprovals; break;
                case "net": got = c.netAllow; break;
                case "mq": got = c.mediaQuality; break;
                case "ptt": got = String.valueOf(c.pttEnabled); break;
                case "plain": got = String.valueOf(c.lanPlain); break;
                case "asr": got = c.asrPref; break;
                case "trusted": got = c.trusted; break;
                default: throw new AssertionError(k);
            }
            assertEquals(name + " · " + k, v, got);
        }
    }

    void roundtrip() throws Exception {
        Config c = new Config(tmp(""));
        switch (in) {
            case "trusted": c.trusted = want; break;
            case "relayToken": c.relayToken = want; break;
            case "workspaceDir": c.workspaceDir = want; break;
            case "mediaDir": c.mediaDir = want; break;
            case "apps": c.apps = want; break;
            case "pairKey": c.pairKey = new byte[]{0, -1, 1, -128, 127, 63, 62, 61}; break;
            case "approveKinds": c.approveKinds = want; break;
            case "shortcutApprovals": c.shortcutApprovals = want; break;
            case "deviceId": c.deviceId = want; break;
            case "llmKey": c.llmKey = want; break;
            case "pttSkip": c.pttSkip = want; break;
            default: throw new AssertionError(in);
        }
        c.save();
        Config b = Config.load(c.file);
        switch (in) {
            case "trusted": assertEquals(want, b.trusted); break;
            case "relayToken": assertEquals(want.trim(), b.relayToken); break;
            case "workspaceDir": assertEquals(want, b.workspaceDir); break;
            case "mediaDir": assertEquals(want, b.mediaDir); break;
            case "apps": assertEquals("C:\\Windows\\notepad.exe", b.appMap().get("记事本")); assertEquals("calc.exe", b.appMap().get("计算器")); break;
            case "pairKey": assertArrayEquals(c.pairKey, b.pairKey); break;
            case "approveKinds": assertEquals(want, b.approveKinds); break;
            case "shortcutApprovals":
                assertEquals(want, b.shortcutApprovals);
                assertEquals(H64, LinkPolicy.parseApprovals(b.shortcutApprovals).get("a,b=c.ps1"));
                break;
            case "deviceId": assertEquals(want, b.deviceId); break;
            case "llmKey": assertEquals(want, b.llmKey); break;
            case "pttSkip": assertEquals(want, b.pttSkip); break;
            default: throw new AssertionError(in);
        }
    }

    void argv() throws Exception {
        Path dir = Files.createTempDirectory("advargv");
        Path cfg = dir.resolve("a.properties"), cfg2 = dir.resolve("b.properties"), missing = dir.resolve("no/such/c.properties");
        String[] a = in.split("\\|");
        for (int i = 0; i < a.length; i++) a[i] = a[i].replace("$CFG2", cfg2.toString()).replace("$CFG", cfg.toString()).replace("$MISSING", missing.toString());
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        String home = System.getProperty("user.home");
        try {
            System.setOut(new PrintStream(buf, true, "UTF-8"));
            System.setProperty("user.home", dir.toString()); // 没给 --config 时读的默认配置也落在临时目录
            Main.main(a);
        } finally {
            System.setOut(old);
            System.setProperty("user.home", home);
        }
        String out = buf.toString("UTF-8");
        switch (want) {
            case "version": assertEquals(name, "OneSay Desktop " + Main.VERSION, out.trim()); break;
            case "usage": assertTrue(name + "：" + out, out.contains("通用：--config <文件>")); break;
            case "notwin": assertEquals(name, "仅 Windows 支持", out.trim()); break;
            default: throw new AssertionError(want);
        }
        assertFalse("只读配置、不建文件", Files.exists(cfg) || Files.exists(cfg2) || Files.exists(missing));
    }

    void ping() throws Exception {
        if (in.equals("none")) {
            int free;
            try (ServerSocket s = new ServerSocket(0)) { free = s.getLocalPort(); }
            assertFalse("没人在听：不算已开着", App.ping(free));
            return;
        }
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/ping", ex -> {
            byte[] body = (in.equals("onesay") ? "onesay" : in.equals("onesay-trailing") ? "onesay\n" : "other").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(in.equals("404") ? 404 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        srv.start();
        try {
            assertEquals(name, in.equals("onesay"), App.ping(srv.getAddress().getPort()));
        } finally {
            srv.stop(0);
        }
    }
}
