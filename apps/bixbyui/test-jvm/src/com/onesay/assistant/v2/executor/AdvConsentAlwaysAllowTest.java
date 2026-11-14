package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 找茬 · 「始终允许」（控制台 › 权限与审批里的「以后这类不用问」）：写入 / 撤销 / 绕过。
 * 能力 × 用户动作序列（不点 / 带契约写入 / 只按签名写入 / 写入后撤销 / 撤销后再写入 / 重复写入后撤销一次）× 污点。断言（ConsentPolicy 注释）：
 * <ul>
 *   <li>红卡名单（恢复出厂、飞行模式、打电话）、通信类、对外发送（PUBLIC_COMMS）、远程 MCP 工具：不接受「始终允许」，卡上也不给这个键；</li>
 *   <li>按签名的旧写入口对通信类拒收；其余不可逆的即使残留在清单里，门禁也不放行；</li>
 *   <li>「始终允许」只对可信来源生效：读过外部内容（污点）时照样要确认；</li>
 *   <li>撤销立即生效，清单按名字排序、不重复。</li>
 * </ul>
 */
@RunWith(Parameterized.class)
public class AdvConsentAlwaysAllowTest {

    static final String[] ACTIONS = {"none", "allowCap", "allowSign", "allowCap+revoke", "allowSign+revoke", "revoke+allowCap", "allowCap+allowCap+revoke", "allowSign+allowCap"};

    /** {sign, sideEffect, domain, cmd, args}；除特别说明都是风险 2 + 要确认。 */
    static final String[][] CAPS = {
            {"sys.reboot", "DEVICE_CONTROL", "sys", "reboot", "{}"},
            {"settings.wifi.off", "DEVICE_CONTROL", "settings", "svc wifi disable", "{}"},
            {"note.create", "PERSISTENT_WRITE", "note", "echo note", "{}"},
            {"conn.google.calendar_create", "PERSISTENT_WRITE", "conn", "echo", "{}"},
            {"conn.mail.inbox_archive", "PERSISTENT_WRITE", "conn", "echo", "{}"},
            {"sys.wipe", "DESTRUCTIVE", "sys", "wipe", "{}"},
            {"connectivity.airplane.on", "DEVICE_CONTROL", "net", "cmd connectivity airplane-mode enable", "{}"},
            {"phone.call", "PUBLIC_COMMS", "phone", "am start", "{\"number\":\"13800138000\"}"},
            {"sms.send", "PUBLIC_COMMS", "phone", "am start", "{\"number\":\"10086\",\"text\":\"hi\"}"},
            {"mail.send", "PUBLIC_COMMS", "mail", "echo", "{}"},
            {"conn.myserver.write_note", "PERSISTENT_WRITE", "conn", "echo", "{}"},
            {"conn.github.create_issue", "PUBLIC_COMMS", "conn", "echo", "{}"},
            {"conn.evil.read_everything", "PRIVATE_DATA_READ", "conn", "echo", "{}"},
            {"app.uninstall", "DESTRUCTIVE", "app", "pm uninstall {pkg}", "{}"},
            {"file.delete", "DESTRUCTIVE", "file", "rm {path}", "{}"},
            {"cache.clear", "PERSISTENT_WRITE", "sys", "pm clear x", "{}"},
    };

    @Parameterized.Parameters(name = "{index}: {0} {2} t{3}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        for (int i = 0; i < CAPS.length; i++)
            for (String a : ACTIONS)
                for (int t = 0; t < 2; t++) out.add(new Object[]{CAPS[i][0], i, a, t == 1});
        return out;
    }

    private final int idx;
    private final String action;
    private final boolean tainted;

    public AdvConsentAlwaysAllowTest(String sign, int idx, String action, boolean tainted) {
        this.idx = idx;
        this.action = action;
        this.tainted = tainted;
    }

    @After public void clean() {
        for (String s : ConsentPolicy.alwaysAllowedList()) ConsentPolicy.revoke(s);
    }

    static Capability cap(String[] c) {
        return new Capability.Builder(c[0], "adv").domain(c[2]).risk(2, true).sideEffect(SideEffect.valueOf(c[1])).cmd(c[3]).build();
    }

    @Test public void alwaysAllow() throws Exception {
        String[] c = CAPS[idx];
        Capability cap = cap(c);
        JSONObject args = new JSONObject(c[4]);
        String sign = c[0];
        boolean comms = CommsRisk.isComms(sign);
        boolean accepts = ConsentPolicy.acceptsAlwaysAllow(cap);
        // 规则本身（与动作无关）
        boolean expectAccepts = !(sign.equals("sys.wipe") || sign.equals("connectivity.airplane.on") || sign.equals("phone.call")
                || comms || cap.sideEffect == SideEffect.PUBLIC_COMMS || ConsentPolicy.isRemoteConnTool(cap));
        assertEquals("acceptsAlwaysAllow " + sign, expectAccepts, accepts);
        boolean remote = sign.startsWith("conn.") && !sign.startsWith("conn.google.") && !sign.startsWith("conn.mail.") && !sign.startsWith("conn.tavily.");
        assertEquals("远程 MCP 判定 " + sign, remote, ConsentPolicy.isRemoteConnTool(cap));
        if (tainted) assertFalse("污点下卡片不给「始终允许」", ConsentPolicy.offersAlwaysAllow(cap, true));
        if (!accepts) assertFalse("不接受的能力卡片上也不给这个键", ConsentPolicy.offersAlwaysAllow(cap, false));

        // 动作序列（模型：清单里有没有这个签名）
        boolean listed = false;
        for (String op : action.split("\\+")) {
            if (op.equals("none")) continue;
            if (op.equals("allowCap")) {
                ConsentPolicy.allowAlways(cap);
                if (accepts) listed = true;
            } else if (op.equals("allowSign")) {
                ConsentPolicy.allowAlways(sign);
                if (!comms) listed = true;
            } else if (op.equals("revoke")) {
                ConsentPolicy.revoke(sign);
                listed = false;
            }
        }
        assertEquals(action + " 清单", listed, ConsentPolicy.isAlwaysAllowed(sign));
        List<String> l = ConsentPolicy.alwaysAllowedList();
        assertEquals(listed ? 1 : 0, l.size());

        ConsentPolicy.Outcome o = ConsentPolicy.gate(cap, args, tainted);
        boolean shouldBypass = listed && accepts && !tainted;
        if (shouldBypass) {
            assertEquals(ToolGate.Decision.ALLOW, o.decision);
            assertTrue(o.viaAlwaysAllow);
        } else {
            assertNotEquals(sign + " " + action + " t" + tainted + " 不该被「始终允许」放行", ToolGate.Decision.ALLOW, o.decision);
            assertFalse(o.viaAlwaysAllow);
        }
        // 走统一入口也一样，且需要确认时一定签发令牌
        ExecutionGate.Decision d = ExecutionGate.evaluate(cap, args, tainted);
        assertEquals(o.decision, d.decision);
        if (d.needsConfirm()) {
            assertNotNull(d.token);
            // tierOf：只读永不红卡；其余污点 → 红卡；不可逆名单 / 删除类接口 → 红卡；否则黄卡
            boolean red = !ConsentPolicy.isReadOnly(cap) && (tainted || sign.equals("sys.wipe") || sign.equals("connectivity.airplane.on")
                    || sign.equals("phone.call") || com.onesay.assistant.v2.capability.InterfaceKind.of(cap) == com.onesay.assistant.v2.capability.InterfaceKind.Kind.DELETE);
            assertEquals(red ? ConsentPolicy.TIER_RED : ConsentPolicy.TIER_YELLOW, d.tier);
            ExecutionGate.reject(d.token);
        }
    }
}
