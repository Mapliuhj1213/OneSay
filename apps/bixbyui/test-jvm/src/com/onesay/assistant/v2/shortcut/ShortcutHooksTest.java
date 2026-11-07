package com.onesay.assistant.v2.shortcut;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.AgentLoop;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 宿主适配（ShortcutHooks.ports）：确认 / 执行都转给 AgentLoop 的同一个宿主回调（面板确认卡 / 岛上确认卡），
 * 无人值守（定时）没有 ask_user → 留空参数按取消处理；内部动作「加日程」的参数解析；接线点都在源码里。
 */
public class ShortcutHooksTest {

    static final class Host implements AgentLoop.GatedCallback {
        final List<String> calls = new ArrayList<String>();
        AgentLoop.ConfirmVerdict verdict = AgentLoop.ConfirmVerdict.ALLOW;

        @Override public ExecutionResult execute(Capability cap, JSONObject args) {
            calls.add("execute2:" + cap.sign);
            return ExecutionResult.success(cap, args, "ok", true, "");
        }

        @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason) {
            calls.add("confirm3:" + cap.sign);
            return verdict;
        }

        @Override public ExecutionResult execute(Capability cap, JSONObject args, ExecutionGate.ConfirmToken token, boolean tainted) {
            calls.add("execute4:" + cap.sign + ":" + (token != null) + ":" + tainted);
            return ExecutionResult.success(cap, args, "ok", true, "");
        }

        @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason, int tier, boolean tainted) {
            calls.add("confirm5:" + cap.sign + ":" + tier + ":" + tainted);
            return verdict;
        }
    }

    static Capability sms() {
        return new Capability.Builder("sms.send", "发短信").domain("test").sideEffect(SideEffect.PUBLIC_COMMS).risk(2, true).executor("intent").build();
    }

    @Test public void confirmAndExecuteGoThroughTheSameHostCallback() throws Exception {
        Host h = new Host();
        ShortcutRunner.Ports p = ShortcutHooks.ports(null, h);
        assertTrue(p.confirm(sms(), new JSONObject(), "x", 1, true));
        h.verdict = AgentLoop.ConfirmVerdict.DENY;
        assertFalse(p.confirm(sms(), new JSONObject(), "x", 2, false));
        assertEquals("[confirm5:sms.send:1:true, confirm5:sms.send:2:false]", h.calls.toString());
        assertNull("无人值守回调没有 ask_user：留空参数 = 取消", p.ask("几点？", "hour"));
        assertFalse(p.cancelled());
        p.announce("正在执行快捷指令");   // 宿主不收事件也不能出错
    }

    @Test public void eventArgsParsed() throws Exception {
        TimeZone tz = TimeZone.getTimeZone("Asia/Shanghai");
        long now = 1794137400000L;   // 2026-11-08 19:30 +08
        Map<String, Object> m = ShortcutActions.eventRow(new JSONObject("{\"title\":\"复诊\",\"date\":\"tomorrow\",\"time\":\"09:30\"}"), now, tz);
        java.util.Calendar k = java.util.Calendar.getInstance(tz);
        k.setTimeInMillis((Long) m.get("start_at"));
        assertEquals(9, k.get(java.util.Calendar.DAY_OF_MONTH));
        assertEquals(9, k.get(java.util.Calendar.HOUR_OF_DAY));
        assertEquals(30, k.get(java.util.Calendar.MINUTE));
        assertEquals(0, m.get("all_day"));
        Map<String, Object> d = ShortcutActions.eventRow(new JSONObject("{\"title\":\"年会\",\"date\":\"2026-12-31\"}"), now, tz);
        assertEquals(1, d.get("all_day"));
        try {
            ShortcutActions.eventRow(new JSONObject("{\"title\":\"x\",\"time\":\"25:00\"}"), now, tz);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            ShortcutActions.eventRow(new JSONObject("{}"), now, tz);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    static String src(String rel) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(new java.io.File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    @Test public void wiredIntoRouterSheetAndPlannerObserver() throws Exception {
        String ur = src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        assertTrue(ur.contains("ShortcutHooks.matches(mContext, query)"));
        assertTrue(ur.contains("ShortcutHooks.tryRun(mContext, query, cb"));
        assertTrue(ur.contains("ShortcutHooks.rememberAgentRun(query, r)"));
        // 快捷指令命中排在密钥检查之前（快捷指令本身不需要模型）
        int a = ur.indexOf("ShortcutHooks.matches(mContext, query)"), b = ur.indexOf("if (!mL2.hasApiKey()) return false;");
        assertTrue(a > 0 && b > a);
        assertTrue(src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").contains("ShortcutHooks.onAgentResultShown(FloatingSheetService.this, command)"));
        String ph = src("app/src/com/onesay/assistant/v2/planner/PlannerHooks.java");
        assertTrue(ph.contains("ShortcutHooks.onPlannerChanged(app)"));
        assertTrue(ph.contains("ShortcutHooks.pushCatalog(app)"));
        // 宿主胶水不自己批准令牌（approve 只在 ShortcutRunner 里、在确认卡返回允许之后）
        assertFalse(src("app/src/com/onesay/assistant/v2/shortcut/ShortcutHooks.java").contains("ExecutionGate.approve("));
        assertFalse(src("app/src/com/onesay/assistant/v2/shortcut/ShortcutFlow.java").contains("ExecutionGate.approve("));
        // 后台线程走项目线程工具
        assertFalse(src("app/src/com/onesay/assistant/v2/shortcut/ShortcutHooks.java").contains("new Thread("));
        // 定时（无人值守）：内部动作先过 S3Runner 的同一道权限闸；失败按拒绝归类；权限清单认得「执行快捷指令#id」且在任何定时任务前装上
        String sh = src("app/src/com/onesay/assistant/v2/shortcut/ShortcutHooks.java");
        assertTrue(sh.contains("PermissionGuard.Preflight) cb).preflight(cap, args, token)"));
        assertTrue(sh.indexOf("preflight(cap, args, token)") < sh.indexOf("return internal(ctx, cap, args);"));
        assertTrue(sh.contains("ShortcutSafety.unattendedVerdict(exec(cap, args, token, tainted))"));
        String s3 = src("app/src/com/onesay/assistant/v2/s3/S3Runner.java");
        assertTrue(s3.contains("com.onesay.assistant.v2.jobs.PermissionGuard.Preflight {"));
        int pre = s3.indexOf("ExecutionResult blockedHere = preflight(cap, args, token);");
        assertTrue(pre > 0 && pre < s3.indexOf("UnifiedExecutor.getInstance(mCtx).execute(cap, args, token, tainted)"));
        int inst = ph.indexOf("ShortcutHooks.installJobPerms(app)");
        assertTrue(inst > 0 && inst < ph.indexOf("Threads.start(\"planner-init\""));
    }
}
