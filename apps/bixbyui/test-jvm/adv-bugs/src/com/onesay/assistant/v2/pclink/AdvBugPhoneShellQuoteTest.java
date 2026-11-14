package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * [已知缺陷 P1] 手机作为被控端时，系统命令（sys_exec）的授权红线只对原文做正则（LinkPolicy.classifyShell 的手机分支、PcRx.PM_APP_OP），
 * 不像电脑分支那样先 normalizeCommand。shell 会把引号 / 反斜杠去掉再执行，所以 'pm' grant、p''m grant、pm "uninstall" 这类写法
 * 被分成普通 SHELL_EXEC，手机端直接执行（PcRx：phoneLegacy 对 SHELL_EXEC 放行）——绕过「给 App 授权只生成脚本」「远程不能卸载 / 停用 OneSay 自己」两条硬规则。
 * 前提：手机上打开了「接受电脑指令」和「系统命令」（开源版默认都关），且是已配对的电脑发来。
 * 期望（类注释 / PcRxPolicy.sub：「给 App 授权的只生成脚本，不执行」）：这些写法与 pm grant 同样处理。
 */
@RunWith(Parameterized.class)
public class AdvBugPhoneShellQuoteTest {

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> data() {
        String[][] rows = {
                {"'pm' grant com.x android.permission.CAMERA", "grant"}, {"p''m grant com.x android.permission.CAMERA", "grant"},
                {"p\\m grant com.x android.permission.CAMERA", "grant"}, {"pm \"grant\" com.x android.permission.CAMERA", "grant"},
                {"pm gr''ant com.x android.permission.CAMERA", "grant"}, {"app''ops set com.x CAMERA allow", "grant"},
{"d''pm set-device-owner com.x/.D", "grant"},
                {"pm 'uninstall' com.onesay.assistant", "self"}, {"'pm' disable-user com.onesay.assistant", "self"}, {"pm clear 'com.onesay.assistant'", "self"},
        };
        List<Object[]> out = new ArrayList<Object[]>();
        for (String[] r : rows) out.add(new Object[]{r[0], r[1]});
        return out;
    }

    private final String cmd, kind;

    public AdvBugPhoneShellQuoteTest(String cmd, String kind) {
        this.cmd = cmd;
        this.kind = kind;
    }

    @Test public void quotedRedLine() {
        final List<String> ran = new ArrayList<String>();
        class H implements PcRx.Handler, PcRx.Guard {
            public boolean enabled(String c) { return true; }
            public PcLanClient.Result run(String c, Map<String, Object> a) { ran.add(c); return new PcLanClient.Result(true, "ran", null, ""); }
            public void record(String c, boolean ok, String t) { }
            public void appFacts(String c, Map<String, Object> a, LinkPolicy.Facts f) {
                if (PcRxPolicy.str(a, "package").startsWith("com.onesay.assistant")) f.appSelf = true;
            }
            public File workspace() { return null; }
            public String script(String c, Map<String, Object> a, String why) { return "scripts/x.sh"; }
        }
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("command", cmd);
        PcLanClient.Result r = PcRx.handle(new H(), PcRxPolicy.SYS_EXEC, a);
        assertFalse("[" + cmd + "] 被直接执行了（" + LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, cmd) + "）", r.ok);
        assertTrue(ran.isEmpty());
        assertEquals(kind.equals("grant") ? LinkPolicy.R_SCRIPT_GENERATED : LinkPolicy.R_SELF_GRANT_BLOCKED, r.data.get("reason"));
    }
}
