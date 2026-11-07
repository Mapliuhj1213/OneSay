package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.backend.ExecutorBackend;
import com.onesay.assistant.v2.executor.backend.IntentBackend;
import com.onesay.assistant.v2.executor.backend.MailBackend;
import com.onesay.assistant.v2.executor.backend.AlarmBackend;
import com.onesay.assistant.v2.executor.backend.ProbeBackend;
import com.onesay.assistant.v2.executor.backend.ShellBackend;
import com.onesay.assistant.v2.executor.backend.VisionBackend;
import com.onesay.assistant.v2.executor.backend.ExecRequest;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** P1-7 / P0-4 / P0-8：执行流水线（假 shell + 假应用解析，不碰 Android）。 */
public class UnifiedExecutorTest {

    /** 记录命令；按前缀返回预设输出。 */
    static final class FakeShell implements ShellRunner {
        final List<String> cmds = new ArrayList<String>();
        final Map<String, ShellExecutor.ShellResponse> replies = new HashMap<String, ShellExecutor.ShellResponse>();

        @Override public ShellExecutor.ShellResponse execute(String command) {
            cmds.add(command);
            for (Map.Entry<String, ShellExecutor.ShellResponse> e : replies.entrySet()) {
                if (command.startsWith(e.getKey())) return e.getValue();
            }
            return new ShellExecutor.ShellResponse(0, "", false, false);
        }
    }

    static final class FakeApps implements AppResolver {
        final List<String> launched = new ArrayList<String>();
        @Override public String resolveComponent(String appName) {
            return "微信".equals(appName) ? "com.tencent.mm/.ui.LauncherUI" : null;
        }
        @Override public boolean launchComponent(String component) {
            launched.add(component);
            return true;
        }
    }

    static UnifiedExecutor exec(FakeShell sh, FakeApps apps) {
        return new UnifiedExecutor(null, sh, apps, null);
    }

    static Capability brightness() {
        return new Capability.Builder("settings.brightness.set", "设置亮度")
                .schema(new ParamsSchema().addInteger("level", true, 0, 255, null))
                .cmd("settings put system screen_brightness {level}")
                .check("settings get system screen_brightness", "{level}")
                .reply("亮度已调到 {level}").build();
    }

    @Test public void verifiedOnlyWhenReadbackMatches() throws Exception {
        FakeShell sh = new FakeShell();
        sh.replies.put("settings get", new ShellExecutor.ShellResponse(0, "120", false, false));
        ExecutionResult ok = exec(sh, new FakeApps()).execute(brightness(), new JSONObject().put("level", 120));
        assertTrue(ok.ok);
        assertEquals(ExecutionResult.Verification.VERIFIED, ok.verification);
        assertTrue(ok.verified);
        assertEquals("settings put system screen_brightness 120", sh.cmds.get(0));
        // [r2/uxcompare] 亮度回执按回读值换成百分比（ControlFeedback）：120/255 ≈ 47%
        assertEquals("亮度已调到 47%", ok.userMessage);

        sh.replies.put("settings get", new ShellExecutor.ShellResponse(0, "10", false, false));
        ExecutionResult bad = exec(sh, new FakeApps()).execute(brightness(), new JSONObject().put("level", 120));
        assertFalse(bad.ok);
        assertEquals(ErrorCode.VERIFY_FAILED, bad.code);
        assertEquals(ExecutionResult.Verification.FAILED, bad.verification);
    }

    @Test public void noCheckMeansUnverified() throws Exception {
        Capability open = new Capability.Builder("browser.open", "打开网页").executor("intent")
                .cmd("am start -a android.intent.action.VIEW -d {url}").build();
        FakeShell sh = new FakeShell();
        ExecutionResult r = exec(sh, new FakeApps()).execute(open, new JSONObject().put("url", "https://a.com/?x=1&y=2"));
        assertTrue(r.ok);
        assertEquals("旧版这里会报 verified=true", ExecutionResult.Verification.UNVERIFIED, r.verification);
        assertFalse(r.verified);
        assertEquals("am start -a android.intent.action.VIEW -d 'https://a.com/?x=1&y=2'", sh.cmds.get(0));
    }

    @Test public void appLaunchGoesThroughIntentBackend() throws Exception {
        Capability launch = new Capability.Builder("app.launch", "打开应用").executor("intent")
                .schema(new ParamsSchema().addString("app", true, null))
                .cmd("am start -n {component}").reply("已为您打开 {app}").build();
        FakeShell sh = new FakeShell();
        FakeApps apps = new FakeApps();
        ExecutionResult r = exec(sh, apps).execute(launch, new JSONObject().put("app", "微信"));
        assertTrue(r.ok);
        assertEquals(1, apps.launched.size());
        assertTrue("app.launch 不走 shell", sh.cmds.isEmpty());
        ExecutionResult nf = exec(sh, apps).execute(launch, new JSONObject().put("app", "不存在的应用"));
        assertEquals(ErrorCode.NOT_FOUND_APP, nf.code);
    }

    /** [roles] 角色层下发的 app.launch 带包名：按包名解析（「美团」不落到美团外卖），没装 → NOT_FOUND_APP 不回退标签。 */
    @Test public void appLaunchByPackageFromRole() throws Exception {
        Capability launch = new Capability.Builder("app.launch", "打开应用").executor("intent")
                .schema(new ParamsSchema().addString("app", true, null))
                .cmd("am start -n {component}").reply("已为您打开 {app}").build();
        final java.util.Map<String, String> byLabelOrPkg = new java.util.HashMap<String, String>();
        byLabelOrPkg.put("美团", "com.sankuai.meituan.takeoutnew/.Main");          // 标签模糊匹配会命中外卖（旧 bug）
        byLabelOrPkg.put("com.sankuai.meituan", "com.sankuai.meituan/.MainActivity");
        final List<String> launched = new ArrayList<String>();
        AppResolver apps = new AppResolver() {
            @Override public String resolveComponent(String appName) { return byLabelOrPkg.get(appName); }
            @Override public boolean launchComponent(String component) { launched.add(component); return true; }
        };
        FakeShell sh = new FakeShell();
        ExecutionResult r = new UnifiedExecutor(null, sh, apps, null).execute(launch, new JSONObject().put("app", "美团").put("pkg", "com.sankuai.meituan").put("role", "taxi"));
        assertTrue(r.ok);
        assertEquals("com.sankuai.meituan/.MainActivity", launched.get(0));
        ExecutionResult nf = new UnifiedExecutor(null, sh, apps, null).execute(launch, new JSONObject().put("app", "滴滴").put("pkg", "com.sdu.didi.psnger").put("role", "taxi"));
        assertEquals("角色层：包没装 → NOT_FOUND_APP（面板出安装提示），不回退标签", ErrorCode.NOT_FOUND_APP, nf.code);
        ExecutionResult legacy = new UnifiedExecutor(null, sh, apps, null).execute(launch, new JSONObject().put("app", "美团").put("pkg", "com.not.installed"));
        assertTrue("非角色来源（无 role）包名解析不到时仍按标签，行为不变", legacy.ok);
        assertEquals(1 + 1, launched.size());
    }

    @Test public void shellFailuresMapToCodes() throws Exception {
        Capability c = new Capability.Builder("x.y", "测试").cmd("echo hi").build();
        FakeShell sh = new FakeShell();
        sh.replies.put("echo", new ShellExecutor.ShellResponse(-1, "守护未就绪", false, true));
        assertEquals(ErrorCode.CHANNEL_DOWN, exec(sh, new FakeApps()).execute(c, null).code);
        sh.replies.put("echo", new ShellExecutor.ShellResponse(124, "", true, false));
        assertEquals(ErrorCode.CHANNEL_TIMEOUT, exec(sh, new FakeApps()).execute(c, null).code);
        sh.replies.put("echo", new ShellExecutor.ShellResponse(1, "java.lang.SecurityException: Permission Denial", false, false));
        assertEquals(ErrorCode.PERMISSION_DENIED, exec(sh, new FakeApps()).execute(c, null).code);
        sh.replies.put("echo", new ShellExecutor.ShellResponse(2, "boom", false, false));
        assertEquals(ErrorCode.EXEC_FAILED, exec(sh, new FakeApps()).execute(c, null).code);
    }

    @Test public void gateWithTokenEndToEnd() throws Exception {
        Capability reboot = new Capability.Builder("sys.reboot.test", "重启").risk(2, true)
                .sideEffect(SideEffect.DEVICE_CONTROL).cmd("reboot").build();
        FakeShell sh = new FakeShell();
        UnifiedExecutor ex = exec(sh, new FakeApps());
        JSONObject args = new JSONObject();
        // 无令牌 → 挂起，不执行
        assertEquals(ErrorCode.RISK_GATED, ex.execute(reboot, args).code);
        assertEquals(ErrorCode.RISK_GATED, ex.execute(reboot, args, null, false).code);
        assertTrue(sh.cmds.isEmpty());
        // 弹卡 → 批准 → 执行一次
        ExecutionGate.Decision d = ExecutionGate.evaluate(reboot, args, false);
        assertTrue(d.needsConfirm());
        ExecutionGate.approve(d.token);
        assertTrue(ex.execute(reboot, args, d.token, false).ok);
        assertEquals(1, sh.cmds.size());
        // 重放 → 拒绝
        assertEquals(ErrorCode.RISK_GATED, ex.execute(reboot, args, d.token, false).code);
        assertEquals(1, sh.cmds.size());
    }

    @Test public void schemaAndUnknownCapability() throws Exception {
        FakeShell sh = new FakeShell();
        UnifiedExecutor ex = exec(sh, new FakeApps());
        assertEquals(ErrorCode.UNKNOWN_CAPABILITY, ex.execute(null, null).code);
        assertEquals(ErrorCode.SCHEMA_INVALID, ex.execute(brightness(), new JSONObject().put("level", 999)).code);
        Capability weird = new Capability.Builder("custom.unknown", "未知自定义").executor("custom").build();
        ExecutionResult r = ex.execute(weird, null);
        assertEquals("custom 能力不再返回占位假成功", ErrorCode.UNKNOWN_CAPABILITY, r.code);
        assertTrue(sh.cmds.isEmpty());
    }

    @Test public void backendRoutingMatchesLegacyOrder() throws Exception {
        UnifiedExecutor ex = exec(new FakeShell(), new FakeApps());
        assertEquals(ProbeBackend.class, pick(ex, "probe.apps", "probe", null).getClass());
        assertEquals(AlarmBackend.class, pick(ex, "clock.alarm.set", "alarmbridge", null).getClass());
        assertEquals(VisionBackend.class, pick(ex, "vision.screen.understand", "custom", null).getClass());
        assertEquals(MailBackend.class, pick(ex, "mail.inbox.latest", "custom", null).getClass());
        assertEquals(IntentBackend.class, pick(ex, "app.launch", "intent", "a/.B").getClass());
        assertEquals("app.launch 没解析出组件 → shell（与旧版一致）",
                ShellBackend.class, pick(ex, "app.launch", "intent", null).getClass());
        assertEquals("其它 intent 能力是 am start 模板 → shell",
                ShellBackend.class, pick(ex, "browser.open", "intent", null).getClass());
        assertEquals(ShellBackend.class, pick(ex, "settings.x", "shell", null).getClass());
        assertNull(pick(ex, "custom.x", "custom", null));
    }

    private static ExecutorBackend pick(UnifiedExecutor ex, String sign, String type, String component) {
        Capability c = new Capability.Builder(sign, "t").executor(type).build();
        return ex.pickBackend(c, new ExecRequest(c, new JSONObject(), component, null, null, null, null, null));
    }

    @Test public void cleanMarkdownDelegates() {
        assertEquals("标题 粗体", UnifiedExecutor.cleanMarkdown("# 标题 **粗体**"));
    }
}
