package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import org.junit.Test;

/** ★ fast-screen-v2：代理主循环（假 Driver + 脚本化 planner + 可编程宿主）。 */
public class PhoneAgentTest {

    /** 设置：主页（搜索框 + WLAN）→ 搜索结果 → WLAN 页（开关）。 */
    static FakeScreens.Phone settingsPhone() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("home", FakeScreens.screen("com.android.settings", "x.Settings").edit("搜索", "search").button("WLAN").button("蓝牙"));
        p.add("results", FakeScreens.screen("com.android.settings", "x.Search").edit("搜索", "search").button("WLAN 结果").button("返回"));
        p.add("wlan", FakeScreens.screen("com.android.settings", "x.Wifi").toggle("WLAN 开关", false).button("返回"));
        p.add("wlanOn", FakeScreens.screen("com.android.settings", "x.Wifi").toggle("WLAN 开关", true).button("返回"));
        p.on("home", "<enter>", "results").on("results", "WLAN 结果", "wlan").on("wlan", "WLAN 开关", "wlanOn").on("home", "WLAN", "wlan");
        return p;
    }

    @Test public void batchRunsSeveralStepsPerModelCall() {
        FakeScreens.Phone ph = settingsPhone();
        FakeScreens.Script pl = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:搜索}},{\"do\":\"type\",\"id\":{id:搜索},\"text\":\"WLAN\",\"enter\":true,\"expect\":\"WLAN 结果\"}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:WLAN 结果},\"expect\":\"WLAN 开关\"}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:WLAN 开关}}]}",
                "{\"done\":\"WLAN 已打开\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent.Result r = new PhoneAgent(ph, pl, h).run("打开 WLAN", null);
        assertEquals(r.trace.toString(), PhoneAgent.Status.DONE, r.status);
        assertEquals("WLAN 已打开", r.answer);
        assertEquals(4, r.modelCalls);
        assertEquals(4, r.actions);
        assertEquals("wlanOn", ph.cur);
        assertTrue(ph.log.contains("type WLAN"));
        assertTrue(ph.log.contains("key enter"));
        // 第二轮提示词带回了上一批的结果和界面差异
        assertTrue(pl.users.get(1), pl.users.get(1).contains("执行了 2/2 步"));
        assertTrue(pl.users.get(1), pl.users.get(1).contains("界面变化"));
    }

    @Test public void legacyOneStepExecutesOnlyFirstStepAndCapsAtEight() {
        FakeScreens.Phone ph = settingsPhone();
        String batch = "{\"steps\":[{\"do\":\"tap\",\"id\":{id:蓝牙}},{\"do\":\"tap\",\"id\":{id:蓝牙}}]}";
        String[] outs = new String[12];
        for (int i = 0; i < outs.length; i++) outs[i] = batch;
        FakeScreens.Script pl = new FakeScreens.Script(outs);
        PhoneAgent a = new PhoneAgent(ph, pl, new FakeScreens.Host());
        a.legacyOneStep = true;
        PhoneAgent.Result r = a.run("一直点蓝牙", null);
        assertEquals(PhoneAgent.Status.FAILED, r.status);
        assertTrue(r.reason, r.reason.contains("8 步"));
        assertEquals(8, r.modelCalls);
        assertEquals(8, r.actions);   // 每次只做第一步
        assertTrue(pl.systems.get(0).contains("steps 只能放 1 步"));
    }

    @Test public void batchNeedsFewerModelCallsThanLegacyForSameTask() {
        FakeScreens.Phone a = settingsPhone();
        FakeScreens.Script batch = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:搜索}},{\"do\":\"type\",\"id\":{id:搜索},\"text\":\"WLAN\",\"enter\":true,\"expect\":\"WLAN 结果\"}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:WLAN 结果},\"expect\":\"WLAN 开关\"}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:WLAN 开关}}]}", "{\"done\":\"好了\"}");
        PhoneAgent.Result rb = new PhoneAgent(a, batch, new FakeScreens.Host()).run("打开 WLAN", null);
        FakeScreens.Phone b = settingsPhone();
        FakeScreens.Script one = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:搜索}}]}",
                "{\"steps\":[{\"do\":\"type\",\"id\":{id:搜索},\"text\":\"WLAN\",\"enter\":true}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:WLAN 结果}}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:WLAN 开关}}]}", "{\"done\":\"好了\"}");
        PhoneAgent la = new PhoneAgent(b, one, new FakeScreens.Host());
        la.legacyOneStep = true;
        PhoneAgent.Result rl = la.run("打开 WLAN", null);
        assertEquals(PhoneAgent.Status.DONE, rb.status);
        assertEquals(PhoneAgent.Status.DONE, rl.status);
        assertEquals(4, rb.modelCalls);
        assertEquals(5, rl.modelCalls);
        assertEquals("wlanOn", a.cur);
        assertEquals("wlanOn", b.cur);
    }

    @Test public void sensitiveStepPausesAndDeniedStepIsNotExecuted() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("chat", FakeScreens.screen("com.tencent.mobileqq", "Chat").edit("输入消息", "input").button("发送"));
        FakeScreens.Script pl = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"type\",\"id\":{id:输入消息},\"text\":\"你好\"},{\"do\":\"tap\",\"id\":{id:发送}}]}",
                "{\"done\":\"已写好，没有发送\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        h.confirmAnswer = false;
        PhoneAgent.Result r = new PhoneAgent(ph, pl, h).run("给他说你好", null);
        assertEquals(1, h.confirms.size());
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("发送"));
        assertFalse(ph.log.toString(), ph.log.contains("tap 发送"));
        assertTrue(ph.log.contains("type 你好"));
        assertTrue(pl.users.get(1), pl.users.get(1).contains("被用户拒绝"));
        assertEquals(PhoneAgent.Status.DONE, r.status);
    }

    @Test public void sensitiveStepAllowedRunsOnce() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("chat", FakeScreens.screen("com.tencent.mobileqq", "Chat").button("删除"));
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:删除}}]}", "{\"done\":\"删了\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent.Result r = new PhoneAgent(ph, pl, h).run("删掉", null);
        assertEquals(1, h.confirms.size());
        assertTrue(ph.log.contains("tap 删除"));
        assertEquals(PhoneAgent.Status.DONE, r.status);
    }

    @Test public void navigationRowInSettingsDoesNotAskConfirmation() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("home", FakeScreens.screen("com.android.settings", "S").row("密码与安全", "锁屏、指纹"));
        ph.add("sec", FakeScreens.screen("com.android.settings", "Sec").button("屏幕锁定类型"));
        ph.on("home", "密码与安全 · 锁屏、指纹", "sec");
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:密码与安全},\"expect\":\"屏幕锁定类型\"}]}", "{\"done\":\"到了\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent.Result r = new PhoneAgent(ph, pl, h).run("打开密码与安全", null);
        assertEquals(0, h.confirms.size());
        assertEquals("sec", ph.cur);
        assertEquals(PhoneAgent.Status.DONE, r.status);
    }

    @Test public void takeoverMidBatchStopsRemainingStepsAndResumesWithFreshScreen() {
        FakeScreens.Phone ph = settingsPhone();
        FakeScreens.Script pl = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:蓝牙}},{\"do\":\"tap\",\"id\":{id:蓝牙}},{\"do\":\"tap\",\"id\":{id:蓝牙}}]}",
                "{\"done\":\"好\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        h.takeoverAtStep = 2;   // 「思考」后第一步执行时，用户按了返回键
        h.supplement = "用北京的";
        PhoneAgent.Result r = new PhoneAgent(ph, pl, h).run("点蓝牙三次", null);
        assertEquals(1, h.resumes.size());
        assertEquals("按了返回键", h.resumes.get(0));
        int taps = 0;
        for (String s : ph.log) if (s.equals("tap 蓝牙")) taps++;
        assertEquals(1, taps);   // 后两步没做
        assertTrue(pl.users.get(1), pl.users.get(1).contains("补充：用北京的"));
        assertEquals(PhoneAgent.Status.DONE, r.status);
    }

    @Test public void stopDuringTakeoverEndsAsStopped() {
        FakeScreens.Phone ph = settingsPhone();
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:蓝牙}},{\"do\":\"tap\",\"id\":{id:蓝牙}}]}");
        FakeScreens.Host h = new FakeScreens.Host();
        h.takeoverAtStep = 2;
        h.resumeWith = PhoneAgent.Resume.STOP;
        PhoneAgent.Result r = new PhoneAgent(ph, pl, h).run("x", null);
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
    }

    @Test public void stopRequestedEndsImmediately() {
        FakeScreens.Host h = new FakeScreens.Host();
        h.stop = true;
        FakeScreens.Script pl = new FakeScreens.Script("{\"done\":\"x\"}");
        PhoneAgent.Result r = new PhoneAgent(settingsPhone(), pl, h).run("x", null);
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
        assertEquals(0, pl.users.size());
    }

    @Test public void stuckAsksUserInsteadOfFailing() {
        FakeScreens.Phone ph = settingsPhone();
        String noop = "{\"steps\":[{\"do\":\"tap\",\"id\":{id:蓝牙}}]}";
        FakeScreens.Script pl = new FakeScreens.Script(noop, noop, noop, noop, noop, noop);
        FakeScreens.Host h = new FakeScreens.Host();
        h.resumeWith = PhoneAgent.Resume.STOP;
        PhoneAgent.Result r = new PhoneAgent(ph, pl, h).run("x", null);
        assertEquals(1, h.resumes.size());
        assertTrue(h.resumes.get(0), h.resumes.get(0).startsWith("好像卡住了"));
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
    }

    @Test public void modelCallLimitAsksToContinue() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        // 每点一次「下一页」界面都变（不算卡住），但任务一直没完
        for (int i = 0; i < 40; i++) {
            ph.add("p" + i, FakeScreens.screen("p", "a").button("下一页").text("第 " + i + " 页"));
            ph.on("p" + i, "下一页", "p" + (i + 1));
        }
        ph.add("p40", FakeScreens.screen("p", "a").button("下一页").text("末页"));
        String next = "{\"steps\":[{\"do\":\"tap\",\"id\":{id:下一页}}]}";
        String[] outs = new String[40];
        for (int i = 0; i < 40; i++) outs[i] = next;
        FakeScreens.Script pl = new FakeScreens.Script(outs);
        FakeScreens.Host h = new FakeScreens.Host();
        h.resumeWith = PhoneAgent.Resume.STOP;
        PhoneAgent.Result r = new PhoneAgent(ph, pl, h).run("翻到底", null);
        assertEquals(TaskBudget.DEF_MODEL_CALLS, r.modelCalls);
        assertTrue(h.resumes.get(0), h.resumes.get(0).endsWith("还要继续吗？"));
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
    }

    @Test public void unmetConditionHandsBackToModel() {
        FakeScreens.Phone ph = settingsPhone();
        FakeScreens.Script pl = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:蓝牙},\"expect\":\"不存在的字\",\"timeout_s\":1},{\"do\":\"tap\",\"id\":{id:WLAN}}]}",
                "{\"done\":\"放弃\"}");
        PhoneAgent.Result r = new PhoneAgent(ph, pl, new FakeScreens.Host()).run("x", null);
        assertFalse(ph.log.contains("tap WLAN"));
        assertTrue(pl.users.get(1), pl.users.get(1).contains("没等到「不存在的字」出现"));
        assertEquals(PhoneAgent.Status.DONE, r.status);
    }

    @Test public void unexpectedDialogInterruptsBatch() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("a", FakeScreens.screen("p", "a").button("拍照").button("相册"));
        ph.add("dlg", FakeScreens.screen("p", "a").text("要允许访问相机吗").button("允许").button("拒绝").button("拍照").button("相册"));
        ph.on("a", "拍照", "dlg");
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:拍照}},{\"do\":\"tap\",\"id\":{id:相册}}]}", "{\"done\":\"x\"}");
        new PhoneAgent(ph, pl, new FakeScreens.Host()).run("x", null);
        assertFalse(ph.log.contains("tap 相册"));
        assertTrue(pl.users.get(1), pl.users.get(1).contains("对话框"));
    }

    @Test public void collectScrollsAndDedupes() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("p1", FakeScreens.screen("p", "a").list().text("甲").text("乙"));
        ph.add("p2", FakeScreens.screen("p", "a").list().text("乙").text("丙"));
        ph.add("p3", FakeScreens.screen("p", "a").list().text("丙").text("关于手机"));
        ph.on("p1", "<scroll>", "p2").on("p2", "<scroll>", "p3");
        FakeScreens.Script pl = new FakeScreens.Script("{\"collect\":{\"until\":\"关于手机\",\"max_pages\":8}}", "{\"done\":\"甲乙丙\"}");
        new PhoneAgent(ph, pl, new FakeScreens.Host()).run("列出全部", null);
        String u = pl.users.get(1);
        assertTrue(u, u.contains("翻了 2 屏，看到了「关于手机」"));
        assertEquals(1, u.split("- 乙\n", -1).length - 1);
    }

    @Test public void askDeniedStops() {
        FakeScreens.Host h = new FakeScreens.Host();
        h.confirmAnswer = false;
        PhoneAgent.Result r = new PhoneAgent(settingsPhone(), new FakeScreens.Script("{\"ask\":\"要选哪一个？\"}"), h).run("x", null);
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
        assertEquals("要选哪一个？", h.confirms.get(0));
    }

    @Test public void plannerUnavailableFails() {
        PhoneAgent.Planner none = new PhoneAgent.Planner() {
            @Override public String next(String s, String u) {
                return null;
            }
        };
        PhoneAgent.Result r = new PhoneAgent(settingsPhone(), none, new FakeScreens.Host()).run("x", null);
        assertEquals(PhoneAgent.Status.FAILED, r.status);
        assertEquals("模型没有返回", r.reason);
    }

    @Test public void invalidOutputIsFedBack() {
        FakeScreens.Script pl = new FakeScreens.Script("胡言乱语", "{\"done\":\"好\"}");
        PhoneAgent.Result r = new PhoneAgent(settingsPhone(), pl, new FakeScreens.Host()).run("x", null);
        assertTrue(pl.users.get(1).contains("上一条输出无效"));
        assertEquals(PhoneAgent.Status.DONE, r.status);
    }

    /** 真机回归：期望文字在点之前就已经在屏上（「连接」摘要里有「蓝牙」），不能因此提前判满足、让后面的返回键在转场中被吞。 */
    @Test public void expectAlreadyOnScreenWaitsForRealChange() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("home", FakeScreens.screen("com.android.settings", "S").row("连接", "Wi-Fi， 蓝牙").button("显示"));
        ph.add("conn", FakeScreens.screen("com.android.settings", "Conn").toggle("蓝牙", false).button("返回"));
        ph.on("home", "连接 · Wi-Fi， 蓝牙", "conn").on("conn", "<back>", "home");
        ph.lagObserves = 3;
        FakeScreens.Script pl = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:连接},\"expect\":\"蓝牙\",\"timeout_s\":5},{\"do\":\"back\",\"expect\":\"显示\"}]}",
                "{\"done\":\"好\"}");
        new PhoneAgent(ph, pl, new FakeScreens.Host()).run("x", null);
        assertTrue(ph.log.toString(), ph.log.contains("key back"));
        assertFalse("返回键不能在转场中按下", ph.log.contains("key back(转场中)"));
        assertEquals("home", ph.cur);
    }

    /** 真机回归：点了但页面没开（第一下只是收起搜索框），屏上本来就有的「蓝牙」摘要不能算条件满足。 */
    @Test public void expectAlreadyOnScreenNeedsNewElementNotJustAnyChange() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("search", FakeScreens.screen("com.android.settings", "S").edit("搜索", "q").row("连接", "Wi-Fi， 蓝牙"));
        ph.add("home", FakeScreens.screen("com.android.settings", "S").row("连接", "Wi-Fi， 蓝牙").button("显示"));
        ph.on("search", "连接 · Wi-Fi， 蓝牙", "home");   // 第一下只收起搜索
        FakeScreens.Script pl = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:连接},\"expect\":\"蓝牙\",\"timeout_s\":2},{\"do\":\"back\"}]}",
                "{\"done\":\"好\"}");
        new PhoneAgent(ph, pl, new FakeScreens.Host()).run("x", null);
        assertFalse("没等到新页面就不该接着按返回", ph.log.toString().contains("key back"));
        assertTrue(pl.users.get(1), pl.users.get(1).contains("没等到「蓝牙」出现"));
    }

    @Test public void launchesStartPackageFirst() {
        FakeScreens.Phone ph = settingsPhone();
        FakeScreens.Host h = new FakeScreens.Host();
        new PhoneAgent(ph, new FakeScreens.Script("{\"done\":\"x\"}"), h).run("x", "com.android.settings");
        assertEquals("launch com.android.settings", ph.log.get(0));
        assertEquals("打开应用", h.steps.get(0));
    }

    // ★ fast-screen-v3：无人值守到 8 次调用直接收尾、注明「未完成」，不问用户
    @Test public void unattendedStopsAtEightCallsWithoutAsking() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        for (int i = 0; i < 20; i++) {
            ph.add("p" + i, FakeScreens.screen("p", "a").button("下一页").text("第 " + i + " 页"));
            ph.on("p" + i, "下一页", "p" + (i + 1));
        }
        ph.add("p20", FakeScreens.screen("p", "a").button("下一页").text("末页"));
        String next = "{\"steps\":[{\"do\":\"tap\",\"id\":{id:下一页}}]}";
        String[] outs = new String[20];
        for (int i = 0; i < 20; i++) outs[i] = next;
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent a = new PhoneAgent(ph, new FakeScreens.Script(outs), h);
        a.unattended = true;
        PhoneAgent.Result r = a.run("翻到底", null);
        assertEquals(TaskBudget.UNATTENDED_MODEL_CALLS, r.modelCalls);
        assertEquals(0, h.resumes.size());
        assertEquals(PhoneAgent.Status.FAILED, r.status);
        assertTrue(r.reason, r.reason.startsWith("未完成"));
    }
}
