package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 找茬 · 电脑联动执行策略（LinkPolicy.decide）：被控端 × 沙箱档 × 审批策略 × 类别 × 事实。
 * 电脑：9 种两轴组合 × 全部类别 × {默认事实, 安全插件拦下}；手机（不走两轴）：全部类别 × 6 种事实。
 * 断言来自类注释与 decide() 注释：
 * <ul>
 *   <li>三条硬规则：远程 / 语音批准 → voice_approval_blocked；付款最后一下 → pay_needs_user_tap；自授权 → self_grant_blocked；
 *       给自己授权 → 只生成脚本；远程卸载 / 关掉 OneSay 自己 → self_grant_blocked；任何档位、任何事实都一样；</li>
 *   <li>「从不询问」下电脑端永远不出确认卡；需要管理员的只生成脚本；只读档查询以外全拒；</li>
 *   <li>确认卡都有可记住的动作类型（电脑 pc.* / 手机 phone.*，快捷指令除外），「以后这类不用问」只把 CONFIRM 变成带提醒的 ALLOW，
 *       拒绝 / 只生成脚本 / 放行原样不动；</li>
 *   <li>电脑端默认事实下逐类别的结论（矩阵）。</li>
 * </ul>
 */
@RunWith(Parameterized.class)
public class AdvLinkPolicyMatrixTest {

    static final String[] PHONE_FACTS = {"default", "appSelf", "appBlocked", "appSystem", "urlRiskyIp", "urlCredentials"};

    @Parameterized.Parameters(name = "{index}: {0} {1} {2} {3} {4}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        for (LinkPolicy.Sandbox sb : LinkPolicy.Sandbox.values())
            for (LinkPolicy.Approval ap : LinkPolicy.Approval.values())
                for (LinkPolicy.Cat c : LinkPolicy.Cat.values())
                    for (String f : new String[]{"default", "plugin"}) out.add(new Object[]{"PC", sb, ap, c, f});
        for (LinkPolicy.Cat c : LinkPolicy.Cat.values())
            for (String f : PHONE_FACTS) out.add(new Object[]{"PHONE", LinkPolicy.Sandbox.WORKSPACE_WRITE, LinkPolicy.Approval.UNTRUSTED, c, f});
        return out;
    }

    private final LinkPolicy.Target target;
    private final LinkPolicy.Sandbox sb;
    private final LinkPolicy.Approval ap;
    private final LinkPolicy.Cat cat;
    private final String factsName;

    public AdvLinkPolicyMatrixTest(String target, LinkPolicy.Sandbox sb, LinkPolicy.Approval ap, LinkPolicy.Cat cat, String facts) {
        this.target = LinkPolicy.Target.valueOf(target);
        this.sb = sb;
        this.ap = ap;
        this.cat = cat;
        this.factsName = facts;
    }

    LinkPolicy.Facts facts() {
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        if (factsName.equals("plugin")) f.pluginBlocked = "想提权";
        if (factsName.equals("appSelf")) f.appSelf = true;
        if (factsName.equals("appBlocked")) f.appBlocked = true;
        if (factsName.equals("appSystem")) f.appSystem = true;
        if (factsName.equals("urlRiskyIp")) f.url = LinkPolicy.checkUrl("http://192.168.1.1/", null);
        if (factsName.equals("urlCredentials")) f.url = LinkPolicy.checkUrl("https://u:p@example.com/", null);
        return f;
    }

    static final List<String> ALL_KINDS = Arrays.asList("pc.setting", "pc.power", "pc.uninstall", "pc.script_run", "pc.shell_exec", "pc.outside_write",
            "pc.url", "pc.url.risky", "pc.clipboard", "pc.clipboard.command", "pc.clipboard.sensitive", "pc.blocked_app.open", "pc.blocked_app.uninstall",
            "pc.blocked_app.operate", "pc.system_app", "pc.workspace.protected", "phone.blocked_app.open", "phone.blocked_app.uninstall", "phone.system_app",
            "phone.url.risky", "pc.media_download", "pc.media_tool_install");

    @Test public void decide() {
        LinkPolicy.Facts f = facts();
        LinkPolicy.Decision d = LinkPolicy.decide(target, new LinkPolicy.Mode(sb, ap), cat, f);
        String at = target + "/" + sb + "/" + ap + "/" + cat + "/" + factsName + " → " + d;
        assertNotNull(at, d);
        // ---- 硬规则
        if (cat == LinkPolicy.Cat.REMOTE_APPROVAL) { assertHard(at, d, LinkPolicy.Verdict.DENY, LinkPolicy.R_VOICE_APPROVAL_BLOCKED); return; }
        if (cat == LinkPolicy.Cat.PAY_FINAL) { assertHard(at, d, LinkPolicy.Verdict.DENY, LinkPolicy.R_PAY_USER_TAP); return; }
        if (target == LinkPolicy.Target.PC && factsName.equals("plugin")) { assertHard(at, d, LinkPolicy.Verdict.DENY, LinkPolicy.R_PLUGIN_BLOCKED); return; }
        if (cat == LinkPolicy.Cat.SELF_AUTHORIZE) { assertHard(at, d, LinkPolicy.Verdict.DENY, LinkPolicy.R_SELF_GRANT_BLOCKED); return; }
        if (cat == LinkPolicy.Cat.SELF_GRANT) { assertEquals(at, LinkPolicy.Verdict.SCRIPT, d.verdict); assertHardTail(at, d); return; }
        if (f.appSelf && (cat == LinkPolicy.Cat.UNINSTALL_APP || cat == LinkPolicy.Cat.APP_OPERATE || cat == LinkPolicy.Cat.SYSTEM_APP_OP)) {
            assertHard(at, d, LinkPolicy.Verdict.DENY, LinkPolicy.R_SELF_GRANT_BLOCKED);
            return;
        }
        if (cat == LinkPolicy.Cat.UNKNOWN) { assertHard(at, d, LinkPolicy.Verdict.DENY, LinkPolicy.R_UNKNOWN); return; }
        // ---- 通用
        if (d.verdict == LinkPolicy.Verdict.CONFIRM && cat != LinkPolicy.Cat.SHORTCUT) {
            assertTrue(at + " 确认卡要有动作类型", d.rememberable());
            assertTrue(at, d.kind.startsWith(target == LinkPolicy.Target.PC ? "pc." : "phone."));
            LinkPolicy.Decision r = LinkPolicy.remembered(d, ALL_KINDS);
            assertEquals(at, LinkPolicy.Verdict.ALLOW, r.verdict);
            assertTrue(r.remembered);
            assertTrue(r.headsUp);
            assertEquals(d.kind, r.kind);
            assertSame("没记住这一类就原样", d, LinkPolicy.remembered(d, new ArrayList<String>()));
        } else {
            assertSame(at + " 非确认结论不受「以后这类不用问」影响", d, LinkPolicy.remembered(d, ALL_KINDS));
            if (d.verdict != LinkPolicy.Verdict.CONFIRM) assertEquals(at + " 只有确认卡带动作类型", "", d.kind);
        }
        if (target == LinkPolicy.Target.PHONE) { phone(at, d, f); return; }
        if (ap == LinkPolicy.Approval.NEVER) assertNotEquals(at + " 从不询问不出确认卡", LinkPolicy.Verdict.CONFIRM, d.verdict);
        pc(at, d);
    }

    static void assertHard(String at, LinkPolicy.Decision d, LinkPolicy.Verdict v, String reason) {
        assertEquals(at, v, d.verdict);
        assertEquals(at, reason, d.reason);
        assertHardTail(at, d);
    }

    static void assertHardTail(String at, LinkPolicy.Decision d) {
        assertFalse(at + " 硬规则不可记住", d.rememberable());
        assertSame(at + " 记住任何类型都绕不过硬规则", d, LinkPolicy.remembered(d, ALL_KINDS));
    }

    void phone(String at, LinkPolicy.Decision d, LinkPolicy.Facts f) {
        LinkPolicy.Verdict want = LinkPolicy.Verdict.ALLOW;
        String kind = "";
        if (cat == LinkPolicy.Cat.SYSTEM_APP_OP || ((cat == LinkPolicy.Cat.UNINSTALL_APP || cat == LinkPolicy.Cat.APP_OPERATE) && f.appSystem)) {
            want = LinkPolicy.Verdict.CONFIRM; kind = "phone.system_app";
        } else if ((cat == LinkPolicy.Cat.OPEN_APP || cat == LinkPolicy.Cat.UNINSTALL_APP || cat == LinkPolicy.Cat.APP_OPERATE) && f.appBlocked) {
            want = LinkPolicy.Verdict.CONFIRM; kind = cat == LinkPolicy.Cat.OPEN_APP ? "phone.blocked_app.open" : "phone.blocked_app.uninstall";
        } else if (cat == LinkPolicy.Cat.OPEN_URL && factsName.equals("urlCredentials")) {
            want = LinkPolicy.Verdict.CONFIRM; kind = "phone.url.risky";
        }
        assertEquals(at, want, d.verdict);
        assertEquals(at, kind, d.kind);
    }

    void pc(String at, LinkPolicy.Decision d) {
        boolean ro = sb == LinkPolicy.Sandbox.READ_ONLY, full = sb == LinkPolicy.Sandbox.FULL_ACCESS, never = ap == LinkPolicy.Approval.NEVER;
        switch (cat) {
            case QUERY: assertEquals(at, LinkPolicy.Verdict.ALLOW, d.verdict); return;
            case SYSTEM_A:
            case NET_SEC_SETTING:
                assertEquals(at, LinkPolicy.Verdict.SCRIPT, d.verdict); assertEquals(at, LinkPolicy.R_NEEDS_ADMIN_SCRIPT, d.reason); return;
            default: break;
        }
        if (ro) { assertEquals(at, LinkPolicy.Verdict.DENY, d.verdict); assertEquals(at, LinkPolicy.R_DENIED_BY_LEVEL, d.reason); return; }
        switch (cat) {
            case READ_PRIVATE: case HARMLESS: case OPEN_APP: case WORKSPACE_WRITE:
                assertEquals(at, LinkPolicy.Verdict.ALLOW, d.verdict); return;
            case APP_OPERATE:
                if (full) assertEquals(at, LinkPolicy.Verdict.ALLOW, d.verdict);
                else { assertEquals(at, LinkPolicy.Verdict.DENY, d.verdict); assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, d.reason); }
                return;
            case SYSTEM_APP_OP:
                if (never) { assertEquals(at, LinkPolicy.Verdict.DENY, d.verdict); assertEquals(LinkPolicy.R_SYSTEM_APP, d.reason); }
                else { assertEquals(at, LinkPolicy.Verdict.CONFIRM, d.verdict); assertEquals("pc.system_app", d.kind); }
                return;
            case OPEN_URL:
                assertEquals(at, LinkPolicy.Verdict.DENY, d.verdict); assertEquals(LinkPolicy.R_UNTRUSTED_URL, d.reason); return;
            case CLIPBOARD_WRITE:
                assertEquals(at, LinkPolicy.Verdict.DENY, d.verdict); assertEquals(LinkPolicy.R_CLIPBOARD_REJECTED, d.reason); return;
            case SHORTCUT:
                assertEquals(at, LinkPolicy.Verdict.DENY, d.verdict); assertEquals(LinkPolicy.R_UNKNOWN, d.reason); return;
            case OUTSIDE_WRITE:
                if (full) assertEquals(at, LinkPolicy.Verdict.ALLOW, d.verdict);
                else if (never) { assertEquals(at, LinkPolicy.Verdict.DENY, d.verdict); assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, d.reason); }
                else { assertEquals(at, LinkPolicy.Verdict.CONFIRM, d.verdict); assertEquals("pc.outside_write", d.kind); }
                return;
            default: break;
        }
        String kind = cat == LinkPolicy.Cat.SIMPLE_SETTING ? "pc.setting" : cat == LinkPolicy.Cat.POWER ? "pc.power" : cat == LinkPolicy.Cat.UNINSTALL_APP ? "pc.uninstall"
                : cat == LinkPolicy.Cat.RUN_WS_SCRIPT ? "pc.script_run" : cat == LinkPolicy.Cat.SHELL_EXEC ? "pc.shell_exec" : cat == LinkPolicy.Cat.MEDIA_DOWNLOAD ? "pc.media_download" : null;
        assertNotNull(at + " 漏了类别", kind);
        // needsApproval：分类 / 按需 → 确认；从不询问 → 只有完全访问直接做，其余拒
        if (!never) { assertEquals(at, LinkPolicy.Verdict.CONFIRM, d.verdict); assertEquals(at, kind, d.kind); }
        else if (full) assertEquals(at, LinkPolicy.Verdict.ALLOW, d.verdict);
        else { assertEquals(at, LinkPolicy.Verdict.DENY, d.verdict); assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, d.reason); }
    }
}
