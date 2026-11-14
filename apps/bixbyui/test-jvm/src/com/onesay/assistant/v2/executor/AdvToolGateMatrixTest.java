package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 工具门（ToolGate.check）全矩阵：能力签名 × 副作用 × 风险 × 是否要求确认 × 是否读过外部内容（污点）。
 * 每一格按 ToolGate / ConsentPolicy 注释写明的规则断言：
 * <ol>
 *   <li>污点 + ROOT → 拒绝；</li>
 *   <li>污点 + 发送 / 分享 / 提交类签名（RiskLabel.outboundSign）→ 必须确认；</li>
 *   <li>污点 + 本机只读（isLocalReadOnly）→ 放行；</li>
 *   <li>污点 + 破坏 / 外发 / 读隐私 / 持久写 / 设备控制（非新建闹钟计时器）→ 必须确认；</li>
 *   <li>不污点：风险 ≥ 2 且要确认 → 确认，否则放行；</li>
 *   <li>任何情况下，静态声明「风险 ≥ 2 且要确认」的能力不得被放行（「闸门只能升级，不能反向」）。</li>
 * </ol>
 * 签名不含 phone.call / sms.send（号码分级另测）。BENIGN_CREATE（新建闹钟 / 计时器）的「污点下放行」只对
 * 静态不要求确认的能力成立；静态要求确认的那几格在 AdvGateTaintTest（2026-11-14 已修），这里跳过。
 */
@RunWith(Parameterized.class)
public class AdvToolGateMatrixTest {

    static final String[] SIGNS = {
            // 发送 / 分享 / 提交类
            "mail.send", "wechat.share", "form.submit", "chat.send_message",
            // 新建闹钟 / 计时器
            "clock.alarm.set", "clock.timer.set",
            // 普通动作
            "settings.brightness.set", "file.delete", "screen.read",
            // 远程 MCP / 内置连接器
            "conn.myserver.list_items", "conn.google.calendar_list",
            // 看着像发送但不是（ask.reply 豁免；sender / sharepoint 不是独立的词）
            "ask.reply", "contacts.sender_list", "sharepoint.open"
    };
    /** 风险 / 是否要求确认的四种组合：只读、低风险但标了确认、高风险没标确认、高风险要确认。 */
    static final int[][] RC = {{0, 0}, {1, 1}, {2, 0}, {2, 1}};

    @Parameterized.Parameters(name = "{index}: {0} {1} r{2} c{3} t{4}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        for (String s : SIGNS)
            for (SideEffect e : SideEffect.values())
                for (int[] rc : RC)
                        for (int t = 0; t < 2; t++) {
                            int r = rc[0], c = rc[1];
                            boolean confirm = c == 1, tainted = t == 1;
                            boolean benign = ToolGate.BENIGN_CREATE.contains(s) && e != SideEffect.ROOT && e != SideEffect.DESTRUCTIVE && e != SideEffect.PUBLIC_COMMS;
                            if (benign && tainted && r >= 2 && confirm && !com.onesay.assistant.v2.phone.RiskLabel.outboundSign(s)) continue; // 见 AdvGateTaintTest
                            String k = s + "|" + e + "|" + r + "|" + c + "|" + t;
                            if (!seen.add(k)) throw new IllegalStateException("dup " + k);
                            out.add(new Object[]{s, e, r, confirm, tainted});
                        }
        return out;
    }

    private final String sign;
    private final SideEffect effect;
    private final int risk;
    private final boolean confirm, tainted;

    public AdvToolGateMatrixTest(String sign, SideEffect effect, int risk, boolean confirm, boolean tainted) {
        this.sign = sign;
        this.effect = effect;
        this.risk = risk;
        this.confirm = confirm;
        this.tainted = tainted;
    }

    @Test public void gate() throws Exception {
        Capability cap = new Capability.Builder(sign, "adv").domain(sign.startsWith("conn.") ? "conn" : "adv")
                .risk(risk, confirm).sideEffect(effect).cmd("echo adv").build();
        ToolGate.Decision got = ToolGate.check(cap, new JSONObject().put("x", 1), tainted).decision;
        boolean base = risk >= 2 && confirm;
        boolean outbound = com.onesay.assistant.v2.phone.RiskLabel.outboundSign(sign);
        ToolGate.Decision want;
        if (tainted) {
            if (effect == SideEffect.ROOT) want = ToolGate.Decision.DENY;
            else if (outbound) want = ToolGate.Decision.REQUIRE_CONFIRM;
            else if (ConsentPolicy.isLocalReadOnly(cap)) want = ToolGate.Decision.ALLOW;
            else if (ToolGate.isBenignCreate(cap)) want = ToolGate.Decision.ALLOW;
            else if (effect == SideEffect.DESTRUCTIVE || effect == SideEffect.PUBLIC_COMMS || effect == SideEffect.PRIVATE_DATA_READ
                    || effect == SideEffect.PERSISTENT_WRITE || effect == SideEffect.DEVICE_CONTROL) want = ToolGate.Decision.REQUIRE_CONFIRM;
            else want = base ? ToolGate.Decision.REQUIRE_CONFIRM : ToolGate.Decision.ALLOW;
        } else {
            want = base ? ToolGate.Decision.REQUIRE_CONFIRM : ToolGate.Decision.ALLOW;
        }
        assertEquals(sign + "/" + effect + "/r" + risk + "/c" + confirm + "/t" + tainted, want, got);
        if (base) assertNotEquals("静态要确认的能力不能被放行", ToolGate.Decision.ALLOW, got);
        if (tainted && outbound && effect != SideEffect.ROOT) assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, got);
    }
}
