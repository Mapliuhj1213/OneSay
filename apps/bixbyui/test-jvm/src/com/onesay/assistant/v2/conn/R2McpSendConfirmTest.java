package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.conn.mcp.McpServerConfig;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ExecutionGate;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** r2/fixes 第 2 项：远程 MCP / 连接器发信工具没有「始终允许」，每封单独确认。 */
public class R2McpSendConfirmTest {

    private String revokeMe;

    @After public void tearDown() {
        if (revokeMe != null) ConsentPolicy.revoke(revokeMe);
    }

    @Test public void sendLikeToolsAreAtLeastComms() {
        ToolAllowlist a = new ToolAllowlist()
                .allow("send_email", ToolEffect.WRITE)
                .allow("gmail_send", ToolEffect.READ)
                .allow("postMessage", ToolEffect.WRITE)
                .allow("reply", ToolEffect.WRITE)
                .allow("sendmail", ToolEffect.WRITE)
                .allow("create_mail_draft", ToolEffect.WRITE)
                .allow("search_mail", ToolEffect.READ)
                .allow("get_posts", ToolEffect.READ)
                .allow("create_page", ToolEffect.WRITE)
                .allow("admin_list", ToolEffect.WRITE)
                .allow("delete_message", ToolEffect.DESTRUCTIVE);
        assertEquals(ToolEffect.COMMS, a.effectFor("send_email", null));
        assertEquals("白名单写 read 也挡不住发信", ToolEffect.COMMS, a.effectFor("gmail_send", null));
        assertEquals(ToolEffect.COMMS, a.effectFor("postMessage", null));
        assertEquals(ToolEffect.COMMS, a.effectFor("reply", null));
        assertEquals(ToolEffect.COMMS, a.effectFor("sendmail", null));
        assertEquals("带副作用 + 邮件名词", ToolEffect.COMMS, a.effectFor("create_mail_draft", null));
        assertEquals("读邮件仍是只读", ToolEffect.READ, a.effectFor("search_mail", null));
        assertEquals(ToolEffect.READ, a.effectFor("get_posts", null));
        assertEquals(ToolEffect.WRITE, a.effectFor("create_page", null));
        assertEquals("admin 里的 dm 不算", ToolEffect.WRITE, a.effectFor("admin_list", null));
        assertEquals(ToolEffect.DESTRUCTIVE, a.effectFor("delete_message", null));
    }

    @Test public void remoteMcpToolsNeverAlwaysAllowed() throws Exception {
        Capability page = ConnCapabilities.of(new ConnTool("docs", "create_page", "建页面", null, ToolEffect.WRITE));
        Capability send = ConnCapabilities.of(new ConnTool("gm", "send_email", "发信", null, ToolEffect.COMMS));
        Capability del = ConnCapabilities.of(new ConnTool("gm", "delete_message", "删信", null, ToolEffect.DESTRUCTIVE));
        Capability gcal = ConnCapabilities.of(new ConnTool("google", "calendar_create", "建日程", null, ToolEffect.WRITE));
        Capability mailSend = ConnCapabilities.of(new ConnTool("mail", "send_draft", "发草稿", null, ToolEffect.COMMS));

        assertTrue(ConsentPolicy.isRemoteConnTool(page));
        assertFalse(ConsentPolicy.isRemoteConnTool(gcal));
        assertFalse(ConsentPolicy.acceptsAlwaysAllow(page));
        assertFalse(ConsentPolicy.acceptsAlwaysAllow(send));
        assertFalse(ConsentPolicy.acceptsAlwaysAllow(del));
        assertFalse("内置发信也不行", ConsentPolicy.acceptsAlwaysAllow(mailSend));
        assertTrue("内置连接器的普通写操作照旧可以", ConsentPolicy.acceptsAlwaysAllow(gcal));
        assertFalse(ConsentPolicy.offersAlwaysAllow(send, false));
        assertTrue(ConsentPolicy.offersAlwaysAllow(gcal, false));
        assertEquals(SideEffect.PUBLIC_COMMS, send.sideEffect);

        // 旧清单里残留了远程工具的签名（或旧路径写入），gate 也不认
        revokeMe = page.sign;
        ConsentPolicy.allowAlways(page.sign);
        ExecutionGate.Decision d = ExecutionGate.evaluate(page, new JSONObject(), false);
        assertTrue("每次都要确认", d.needsConfirm());
        assertNotNull(d.token);
        ExecutionGate.reject(d.token);

        // 带契约的写入直接拒收
        ConsentPolicy.revoke(page.sign);
        ConsentPolicy.allowAlways(send);
        assertFalse(ConsentPolicy.isAlwaysAllowed(send.sign));
    }

    @Test public void mcpCannotTakeBuiltinIds() {
        for (String id : new String[]{"google", "mail", "tavily"}) {
            McpServerConfig c = new McpServerConfig(id, "x", "https://example.com/mcp", "auto", null, null, true, null);
            assertNotNull(id, c.validate());
        }
        assertNull(new McpServerConfig("docs", "x", "https://example.com/mcp", "auto", null, null, true, null).validate());
    }

    @Test public void cardHidesAlwaysButtonViaPolicy() throws Exception {
        String v = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java")), "UTF-8");
        assertFalse("不再只按通信类判断", v.contains("mBtnAlways.setVisibility(com.onesay.assistant.v2.executor.CommsRisk.isComms(cap.sign)"));
        assertTrue(v.contains("ConsentPolicy.acceptsAlwaysAllow(cap)"));
        String s = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java")), "UTF-8");
        assertFalse("「始终允许」写入走带契约的重载", s.contains("ConsentPolicy.allowAlways(cap.sign)") || s.contains("ConsentPolicy.allowAlways(pending.cap.sign)"));
    }
}
