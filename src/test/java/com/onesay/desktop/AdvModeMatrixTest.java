package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 三条硬规则（LinkPolicy 类说明「能批就不拦」）在 9 种档位（沙箱 × 审批）× 插件开 / 关 × 「以后这类不用问」全记住 / 没记住
 * 下都一样：提权拒绝、要管理员只生成脚本、自授权 / 远程批准 / 付款最后一下拒绝、远程不能卸载 / 关掉 OneSay 自己。
 * 确认框一次都不弹，记住的类型也不会被改动。
 */
@RunWith(Parameterized.class)
public class AdvModeMatrixTest {

    /** {指令, 参数名, 参数值, 期望原因码}。 */
    static final String[][] HARD = {
            {"sys_exec", "command", "Start-Process cmd -Verb RunAs", LinkPolicy.R_PLUGIN_BLOCKED},
            {"sys_exec", "command", "runas /user:Administrator cmd", LinkPolicy.R_PLUGIN_BLOCKED},
            {"sys_exec", "command", "reg add HKLM\\Software\\x /v a /d 1", LinkPolicy.R_NEEDS_ADMIN_SCRIPT},
            {"sys_exec", "command", "bcdedit /set testsigning on", LinkPolicy.R_NEEDS_ADMIN_SCRIPT},
            {"sys_exec", "command", "icacls C:\\x /grant Everyone:F", LinkPolicy.R_NEEDS_ADMIN_SCRIPT},
            {"sys_exec", "command", "netsh advfirewall set allprofiles state off", LinkPolicy.R_NEEDS_ADMIN_SCRIPT},
            {"sys_exec", "command", "notepad desktop.properties", LinkPolicy.R_SELF_GRANT_BLOCKED},
            {"plugin_off", "x", "1", LinkPolicy.R_SELF_GRANT_BLOCKED},
            {"approve", "id", "1", LinkPolicy.R_VOICE_APPROVAL_BLOCKED},
            {"pay_confirm", "amount", "9.9", LinkPolicy.R_PAY_USER_TAP},
            {"uninstall_app", "name", "OneSay", LinkPolicy.R_SELF_GRANT_BLOCKED},
            {"app_close", "name", "OneSay", LinkPolicy.R_SELF_GRANT_BLOCKED}};

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("matrix");
        for (String[] h : HARD)
            for (LinkPolicy.Mode mode : AdvSupport.allModes())
                for (boolean plugin : new boolean[]{true, false})
                    for (boolean all : new boolean[]{false, true})
                        r.add(h[0] + " " + h[2] + " @" + mode + (plugin ? " 插件开" : " 插件关") + (all ? " 全记住" : ""), h, mode, plugin, all);
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String[] hard;
    @Parameterized.Parameter(2) public LinkPolicy.Mode mode;
    @Parameterized.Parameter(3) public boolean plugin;
    @Parameterized.Parameter(4) public boolean all;

    static final Map<String, PcGuard> GUARDS = new HashMap<>();

    static synchronized PcGuard guard(LinkPolicy.Mode mode, boolean plugin, boolean all) throws Exception {
        String k = mode + "/" + plugin + "/" + all;
        PcGuard g = GUARDS.get(k);
        if (g == null) {
            Config c = AdvSupport.cfg(mode);
            c.pluginEnabled = plugin;
            if (all) c.approveKinds = String.join(",", AdvSupport.ALL_KINDS);
            g = new PcGuard(c, null);
            GUARDS.put(k, g);
        }
        return g;
    }

    @Test public void hardRuleHolds() throws Exception {
        PcGuard g = guard(mode, plugin, all);
        AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.REMEMBER);
        g.confirmer = st;
        String before = String.join(",", g.rememberedKinds());
        PcGuard.Outcome o = g.check("phone-a", hard[0], AdvSupport.m(hard[1], hard[2]));
        assertNotNull(name, o.reply);
        assertNull(name, o.pass);
        assertEquals(name, hard[3], AdvSupport.reason(o));
        assertEquals("硬规则不弹确认", 0, st.asked.size());
        assertEquals("硬规则不是按「以后这类不用问」放行的", "", o.rememberedKind);
        assertEquals("记住的类型没被动过", before, String.join(",", g.rememberedKinds()));
        assertEquals("回复带当前档位", mode.sandbox.id, o.reply.data.get("sandbox"));
        assertEquals(mode.approval.id, o.reply.data.get("approval"));
    }
}
