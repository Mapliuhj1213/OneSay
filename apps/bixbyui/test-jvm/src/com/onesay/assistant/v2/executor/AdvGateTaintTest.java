package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

/**
 * [2026-11-14 已修，原「已知缺陷 P2」：两处纵深防御缺口，现有内置能力不直接触发]
 * <ol>
 *   <li>monotonic：ToolGate 注释「闸门只能把直接跑升级成要确认或拒绝，不能反向」。但污点分支里 isBenignCreate（新建闹钟 / 计时器）
 *       排在「能力本身要确认就继承」之前：一个静态声明 risk=2 + confirm 的 clock.alarm.set / clock.timer.set，不污点时要确认，
 *       读过网页（污点）后反而直接放行。现在内置的这两个能力是 risk 0，所以只是潜在问题；以后谁把它们标成要确认就会出事。</li>
 *   <li>outbound：污点下「发送 / 分享 / 提交类一律确认」按 RiskLabel.outboundSign 认工具名，它只是一个小写正则（send/share/reply/forward/post/publish/submit），
 *       没用 OutboundJudge（「唯一一套对外发送判据」：同形字、dispatch / deliver / emit / relay / enqueue / push…）。远程 MCP 工具名
 *       ѕend_email（西里尔 ѕ）、dispatch_mail、deliver_message 在副作用被标成 NONE 时，污点下直接放行；send_email 则要确认。
 *       MCP 定级（ToolAllowlist）本身走 OutboundJudge，所以正常登记的工具会被标成外发、仍会确认 —— 这里是第二道闸门没跟上。</li>
 * </ol>
 */
@RunWith(Parameterized.class)
public class AdvGateTaintTest {

    @Parameterized.Parameters(name = "{index}: {0} {1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"monotonic", "clock.alarm.set"}, {"monotonic", "clock.timer.set"},
                {"outbound", "conn.x.\u0455end_email"}, {"outbound", "conn.x.dispatch_mail"}, {"outbound", "conn.x.deliver_message"},
                {"outbound", "conn.x.enqueue_sms"}, {"outbound", "conn.x.push_notification"}, {"outbound", "conn.x.ｓｅｎｄ_email"}});
    }

    private final String kind, sign;

    public AdvGateTaintTest(String kind, String sign) {
        this.kind = kind;
        this.sign = sign;
    }

    @Test public void gate() {
        if (kind.equals("monotonic")) {
            Capability cap = new Capability.Builder(sign, "adv").risk(2, true).sideEffect(SideEffect.DEVICE_CONTROL).cmd("echo").build();
            assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, ToolGate.check(cap, new JSONObject(), false).decision);
            assertNotEquals("污点下不能比不污点更宽", ToolGate.Decision.ALLOW, ToolGate.check(cap, new JSONObject(), true).decision);
        } else {
            Capability cap = new Capability.Builder(sign, "adv").domain("conn").risk(0, false).sideEffect(SideEffect.NONE).cmd("echo").build();
            assertTrue("OutboundJudge 认它是外发", com.onesay.assistant.v2.security.OutboundJudge.signLooksOutbound(sign));
            assertEquals("[" + sign + "] 读过外部内容后发送类必须确认", ToolGate.Decision.REQUIRE_CONFIRM, ToolGate.check(cap, new JSONObject(), true).decision);
        }
    }
}
