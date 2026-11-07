package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.MissingAppIntercept;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** ★ audit-J 2（Gemini 真机 UNINST-01/02）：点名的常见 App 未装 → 路由层本地下发 app.launch{app,pkg} → NOT_FOUND_APP + 大白话 + 安装卡。 */
public class AuditJMissingAppTest {

    static final class Inst implements MissingAppIntercept.Installed {
        final Set<String> pkgs = new HashSet<String>();
        final Set<String> labels = new HashSet<String>();
        @Override public boolean pkgInstalled(String pkg) { return pkgs.contains(pkg); }
        @Override public boolean labelResolves(String appName) { return labels.contains(appName); }
    }

    @Test public void openMissingAppGivesLaunchWithPkgNoRole() throws Exception {
        Inst none = new Inst();
        for (String q : new String[]{"打开抖音", "帮我打开抖音", "启动抖音", "开一下抖音", "打开抖音app", "打开抖音。"}) {
            Invocation inv = MissingAppIntercept.plan(q, none);
            assertNotNull(q, inv);
            assertEquals("app.launch", inv.sign);
            assertEquals("抖音", inv.args.optString("app"));
            assertEquals("com.ss.android.ugc.aweme", inv.args.optString("pkg"));
            assertFalse("不带 role（role 会让执行器跳过标签回退）", inv.args.has("role"));
            assertEquals(q.trim(), inv.args.optString(MissingAppIntercept.ARG_RESUME));
            assertEquals("手机上还没装抖音，点下面的安装卡去商店装，装好我接着帮你办。", inv.args.optString(MissingAppIntercept.ARG_REPLY));
        }
    }

    @Test public void searchInMissingAppCarriesTerm() {
        Inst none = new Inst();
        Invocation inv = MissingAppIntercept.plan("在小红书搜穿搭", none);
        assertNotNull(inv);
        assertEquals("小红书", inv.args.optString("app"));
        assertEquals("com.xingin.xhs", inv.args.optString("pkg"));
        String r = inv.args.optString(MissingAppIntercept.ARG_REPLY);
        assertTrue(r, r.contains("穿搭") && r.contains("没装小红书") && r.contains("安装卡"));
        for (String q : new String[]{"用小红书搜一下穿搭", "去小红书上找穿搭", "在小红书里搜索穿搭"}) {
            Invocation i2 = MissingAppIntercept.plan(q, none);
            assertNotNull(q, i2);
            assertTrue(q, i2.args.optString(MissingAppIntercept.ARG_REPLY).contains("穿搭"));
        }
    }

    @Test public void installedOrUnknownNotIntercepted() {
        Inst inst = new Inst();
        inst.pkgs.add("com.ss.android.ugc.aweme");
        assertNull("包已装 → 照旧", MissingAppIntercept.plan("打开抖音", inst));
        Inst variant = new Inst();
        variant.labels.add("抖音");
        assertNull("标签解析得到（装了变体）→ 照旧", MissingAppIntercept.plan("打开抖音", variant));
        Inst none = new Inst();
        assertNull("不认得 → 照旧进 AgentLoop", MissingAppIntercept.plan("打开某某工具", none));
        assertNull("设置项不是 App", MissingAppIntercept.plan("打开蓝牙", none));
        assertNull(MissingAppIntercept.plan("打开手电筒", none));
        assertNull(MissingAppIntercept.plan("抖音是什么", none));
    }

    @Test public void executorReturnsNotFoundWithPlainReply() throws Exception {
        Capability launch = new Capability.Builder("app.launch", "打开应用").executor("intent")
                .schema(new ParamsSchema().addString("app", true, null))
                .cmd("am start -n {component}").reply("已为您打开 {app}").build();
        Invocation inv = MissingAppIntercept.plan("打开抖音", new Inst());
        UnifiedExecutorTest.FakeShell sh = new UnifiedExecutorTest.FakeShell();
        UnifiedExecutorTest.FakeApps apps = new UnifiedExecutorTest.FakeApps();
        ExecutionResult r = UnifiedExecutorTest.exec(sh, apps).execute(launch, new JSONObject(inv.args.toString()));
        assertEquals(ErrorCode.NOT_FOUND_APP, r.code);
        assertEquals("手机上还没装抖音，点下面的安装卡去商店装，装好我接着帮你办。", r.userMessage);
        assertTrue(apps.launched.isEmpty());
        assertEquals("onExecFailed 能拿到 app 名", "抖音", r.args == null ? new JSONObject(inv.args.toString()).optString("app") : r.args.optString("app"));
        ExecutionResult plain = UnifiedExecutorTest.exec(sh, apps).execute(launch, new JSONObject().put("app", "不存在的应用"));
        assertTrue("没带大白话时原文不变", plain.userMessage.startsWith("抱歉，手机上没有找到应用"));
    }

    @Test public void routerAndHooksWired() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String ur = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/src/com/onesay/assistant/v2/router/UnifiedRouter.java")), "UTF-8");
        assertTrue(ur.contains("if (missingAppPlan(query) != null) return false;"));
        assertTrue(ur.contains("[ROUTE-APPMISS]"));
        String rh = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/src/com/onesay/assistant/v2/roles/RoleHooks.java")), "UTF-8");
        assertTrue("点安装登记 InstallWait（续办句 = 原话）", rh.contains("InstallWaitHooks.register(svc, resume, resume"));
    }
}
