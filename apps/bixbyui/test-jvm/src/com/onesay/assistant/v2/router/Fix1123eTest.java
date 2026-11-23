package com.onesay.assistant.v2.router;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.onesay.assistant.v2.events.EventPickHint;
import com.onesay.assistant.v2.ui.ErrorStatePolicy;
import com.onesay.assistant.v2.ui.chat.ChatWindowPolicy;
import com.onesay.assistant.v2.ui.console.DevMode;
import com.onesay.assistant.v2.ui.wake.WakeStage.Stage;
import com.onesay.assistant.v2.wake.MicEngineRecovery;

/** ★ 1123e：魅族真机第 3 轮的 bug（录音引擎重建 / 常驻输入条 / 输入框 / 定位与蓝牙 / 去服务商名 / 电脑名 / 计时器标签 / 开发者模式）。 */
public class Fix1123eTest {

    private static String src(String rel) throws Exception {
        String p = "app/src/com/onesay/assistant/v2/" + rel;
        File f = new File(p);
        if (!f.exists()) f = new File("apps/bixbyui/" + p);
        if (!f.exists()) f = new File("../" + p);
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    // ---------------------------------------------------------------- 1 录音引擎坏了唤醒时重建（退避）

    @Test public void engineBackoff() {
        assertEquals(0L, MicEngineRecovery.backoffMs(0));
        assertEquals(1000L, MicEngineRecovery.backoffMs(1));
        assertEquals(2000L, MicEngineRecovery.backoffMs(2));
        assertEquals(4000L, MicEngineRecovery.backoffMs(3));
        assertEquals("封顶 30 秒", 30000L, MicEngineRecovery.backoffMs(20));
    }

    @Test public void engineRebuildDecision() {
        assertFalse("引擎好着不建", MicEngineRecovery.shouldRebuild(true, false, false, 10000, 0, 0));
        assertFalse("只是还在启动（没坏）不建", MicEngineRecovery.shouldRebuild(false, false, false, 10000, 0, 0));
        assertTrue("坏了第一次立刻建", MicEngineRecovery.shouldRebuild(false, true, false, 10000, 0, 0));
        assertFalse("正在重建不重复建", MicEngineRecovery.shouldRebuild(false, true, true, 10000, 0, 0));
        assertFalse("失败 2 次后 1.5 s 内不建", MicEngineRecovery.shouldRebuild(false, true, false, 11500, 10000, 2));
        assertTrue("2 s 到了再建", MicEngineRecovery.shouldRebuild(false, true, false, 12000, 10000, 2));
        assertTrue("时钟回拨照建", MicEngineRecovery.shouldRebuild(false, true, false, 5000, 10000, 5));
    }

    @Test public void engineMessagesPlain() {
        for (boolean p : new boolean[]{true, false}) {
            String m = MicEngineRecovery.userMessage(p) + MicEngineRecovery.inputHint(p);
            assertTrue(m, m.contains("打字"));
            assertFalse("不露内部错误", m.contains("AudioRecord"));
            assertFalse(m.contains("啥") || m.contains("咋") || m.contains("呗"));
        }
        assertTrue(MicEngineRecovery.userMessage(false).contains("麦克风权限"));
    }

    @Test public void engineWiring() throws Exception {
        String w = src("wake/WakeService.java");
        assertTrue("排队时触发重建", w.contains("maybeRebuildEngine(\"唤醒 \""));
        assertTrue("失败记标记", w.contains("sEngineFailed = true;"));
        assertTrue("超时不再只在面板在屏时提示 → 开打字卡片", w.contains("FloatingSheetService.openForTyping(w, msg"));
        assertFalse("旧的静默提示去掉了", w.contains("语音引擎还在启动，这次没能开始收音，请稍后再按一次\""));
        assertTrue("默认打字唤起", w.contains("WakeUiSettings.typeFirst(this)") && w.contains("if (typeFirstOpens(source, conversation)) return;"));
    }

    // ---------------------------------------------------------------- 2 卡片底部常驻「输入框 + 麦克风」

    @Test public void barAlwaysOnCard() {
        for (Stage s : new Stage[]{Stage.LISTENING, Stage.THINKING, Stage.ANSWERING, Stage.RESULT, Stage.ERROR, Stage.TEXT, Stage.TASK, Stage.CONFIRM}) {
            assertTrue("唤起后底栏常驻：" + s, ChatWindowPolicy.cardBarVisible(s, true, false));
        }
        for (Stage s : new Stage[]{Stage.IDLE, Stage.HANDOFF, Stage.ISLAND, Stage.DISMISSING}) {
            assertFalse("没卡片时不挂：" + s, ChatWindowPolicy.cardBarVisible(s, true, false));
        }
        assertFalse("对话窗开着归对话窗", ChatWindowPolicy.cardBarVisible(Stage.LISTENING, true, true));
        assertFalse("旧界面不变", ChatWindowPolicy.cardBarVisible(Stage.LISTENING, false, false));
        assertEquals(ChatWindowPolicy.HINT_LISTENING, ChatWindowPolicy.barHint(true, null));
        assertEquals(ChatWindowPolicy.HINT_IDLE, ChatWindowPolicy.barHint(false, ""));
        assertEquals("提示优先", "没有麦克风权限，先打字…", ChatWindowPolicy.barHint(true, "没有麦克风权限，先打字…"));
    }

    @Test public void errorKeepsCardAndFocusesInput() {
        ErrorStatePolicy.Plan p = ErrorStatePolicy.plan(ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE, true, false, false, false);
        assertTrue("旧规则仍 2.5 s 收", p.hideAfterMs > 0);
        ErrorStatePolicy.Plan k = ErrorStatePolicy.keepCard(p);
        assertTrue(k.apply);
        assertEquals("新界面出错不自动收起", -1L, k.hideAfterMs);
        assertEquals("错误那句留着", -1L, k.neutralAfterMs);
        assertTrue(ErrorStatePolicy.focusInputOnError(k, false, false, false));
        assertFalse("确认卡未决不抢焦点", ErrorStatePolicy.focusInputOnError(k, true, false, false));
        assertFalse("任务在跑不抢", ErrorStatePolicy.focusInputOnError(k, false, true, false));
        ErrorStatePolicy.Plan off = ErrorStatePolicy.plan(ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE, false, false, false, false);
        assertFalse("面板不在屏上照旧不弹", ErrorStatePolicy.keepCard(off).apply);
    }

    @Test public void typingWiring() throws Exception {
        String v = src("ui/FloatingSheetView.java"), s = src("ui/FloatingSheetService.java");
        assertTrue(v.contains("public void openTyping(String msg, String hint)"));
        assertTrue(v.contains("public void focusInputForTyping()"));
        assertTrue("聆听中底栏照样挂（麦克风换波点）", v.contains("st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.LISTENING);"));
        assertTrue(s.contains("public static void openForTyping(Context ctx, final String msg, final String hint)"));
        assertTrue("新界面出错不收", s.contains("ErrorStatePolicy.keepCard(plan)"));
        assertFalse("文案不再指向 ⌨ 小图标", src("asr/AsrFailure.java").contains("「⌨ 文字」"));
        String d = src("ui/console/DesignPages.java"), u = src("ui/wake/WakeUiSettings.java");
        assertTrue("设置里有开关", d.contains("\"默认打字唤起\""));
        assertTrue("默认关", u.contains("TYPE_FIRST_DEFAULT = false"));
    }

    // ---------------------------------------------------------------- 3 输入框字不显示 / 残留

    @Test public void inputBoxVisibleAndNoResidue() throws Exception {
        String b = src("ui/chat/ChatBottomBar.java"), v = src("ui/FloatingSheetView.java");
        assertTrue("输入框高度随字长（不再 MATCH_PARENT）", b.contains("pill.addView(mEdit, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));"));
        assertFalse(b.contains("pill.addView(mEdit, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));"));
        assertTrue("新唤醒清掉上一轮残留", v.contains("mChat.bar().setText(\"\"); mChat.bar().setNoticeHint(null);"));
        assertTrue("输入法重连到这个框", v.contains("imm.restartInput(edit)"));
    }

    // ---------------------------------------------------------------- 4/5/6 定位总开关 / 蓝牙权限 / 页面内提示

    @Test public void wifiLocationSwitchFirst() {
        java.util.List<String> empty = Collections.<String>emptyList();
        assertEquals("定位关着、已有权限 → 去开定位，不申请权限",
                EventPickHint.Fix.OPEN_LOCATION_SETTINGS, EventPickHint.fix(true, empty, false, true, false, true, true, false));
        assertEquals("已连 Wi‑Fi 但名字是 unknown ssid 也算空", EventPickHint.Fix.OPEN_LOCATION_SETTINGS,
                EventPickHint.fix(true, Arrays.asList("<unknown ssid>"), false, true, false, true, true, false));
        assertEquals(EventPickHint.Fix.REQUEST_LOCATION, EventPickHint.fix(true, empty, true, false, false, true, true, false));
        assertEquals("拒过 → 去设置", EventPickHint.Fix.OPEN_APP_SETTINGS, EventPickHint.fix(true, empty, true, false, true, true, true, false));
        assertEquals(EventPickHint.Fix.NONE, EventPickHint.fix(true, empty, true, true, false, true, true, false));
        assertEquals(EventPickHint.WIFI_LOCATION_OFF, EventPickHint.fixHint(true, empty, EventPickHint.Fix.OPEN_LOCATION_SETTINGS));
        assertEquals(EventPickHint.WIFI_EMPTY, EventPickHint.fixHint(true, empty, EventPickHint.Fix.NONE));
        assertNull(EventPickHint.fixHint(true, Arrays.asList("602_5G"), EventPickHint.Fix.NONE));
        assertEquals("去打开定位", EventPickHint.fixButton(EventPickHint.Fix.OPEN_LOCATION_SETTINGS));
    }

    @Test public void btPermissionDeniedGoesToSettings() {
        java.util.List<String> empty = Collections.<String>emptyList();
        assertEquals(EventPickHint.Fix.TURN_ON_BT, EventPickHint.fix(false, empty, true, true, false, false, false, false));
        assertEquals("第一次弹系统框", EventPickHint.Fix.REQUEST_BT, EventPickHint.fix(false, empty, true, true, false, true, false, false));
        assertEquals("拒过 → 去设置", EventPickHint.Fix.OPEN_APP_SETTINGS, EventPickHint.fix(false, empty, true, true, false, true, false, true));
        String h = EventPickHint.fixHint(false, empty, EventPickHint.Fix.OPEN_APP_SETTINGS);
        assertTrue(h, h.contains("到设置给「附近的设备」权限"));
        assertEquals("去设置", EventPickHint.fixButton(EventPickHint.Fix.OPEN_APP_SETTINGS));
        assertEquals(EventPickHint.BT_EMPTY, EventPickHint.fixHint(false, empty, EventPickHint.Fix.NONE));
    }

    @Test public void pickHintInPageNotToast() throws Exception {
        String e = src("events/EventConsole.java");
        assertFalse("不再用会被授权框挡住的 toast", e.contains("host.toast(hint)"));
        assertTrue("页面内提示框", e.contains(".setMessage(hint)"));
        assertTrue("定位开关跳转", e.contains("ACTION_LOCATION_SOURCE_SETTINGS"));
        assertTrue("应用权限页跳转", e.contains("ACTION_APPLICATION_DETAILS_SETTINGS"));
        int req = e.indexOf("requestPermissions(");
        assertTrue("申请权限只在用户点「允许」后", req > e.indexOf("private static void applyPickFix("));
    }

    // ---------------------------------------------------------------- 7 设置页不出服务商名

    @Test public void settingsNoProviderNames() throws Exception {
        String[] files = {"ui/console/AdvancedPage.java", "ui/console/StatusPage.java", "ui/console/Console.java", "s3/S3Engine.java",
                "s3/S3Hooks.java", "net/MainModelEndpoint.java", "ui/KeysActivity.java", "ui/LlmProvidersActivity.java"};
        String[] bad = {"DeepSeek", "百炼", "QQ邮箱", "DashScope）", "阿里云百炼", "通义千问（"};
        for (String f : files) {
            for (String line : src(f).split("\n")) {
                String t = line.trim();
                if (t.startsWith("//") || t.startsWith("*") || t.contains("Log.") || t.contains("SafeLog")) continue;
                // 只看字符串字面量
                StringBuilder lit = new StringBuilder();
                boolean in = false;
                for (int i = 0; i < t.length(); i++) {
                    char ch = t.charAt(i);
                    if (ch == '"' && (i == 0 || t.charAt(i - 1) != '\\')) { in = !in; if (!in) lit.append('|'); continue; }
                    if (in) lit.append(ch);
                }
                for (String b : bad) assertFalse(f + " 用户可见处有「" + b + "」：" + t, lit.toString().contains(b));
            }
        }
    }

    // ---------------------------------------------------------------- 8 电脑名同一来源

    @Test public void pcNameSingleSource() throws Exception {
        String u = src("pclink/PcLinkUi.java");
        assertTrue("探测到真名后详情页标题跟着改", u.contains("row.setTitle(name);"));
        assertFalse("不再露配对内部 ID", u.contains("\"已配对 \" + p.peer"));
        assertTrue(src("kernel/ConsoleHost.java").contains("void setTitle(String title);"));
    }

    // ---------------------------------------------------------------- 9 计时器带标签、回答不抢先

    @Test public void timerLabel() {
        assertEquals("喝水", FastIntents.timerLabel("1分钟后提醒我喝水"));
        assertEquals("喝水", FastIntents.timerLabel("一分钟后提醒我去喝水"));
        assertEquals("关火", FastIntents.timerLabel("10分钟后叫我关火。"));
        assertNull(FastIntents.timerLabel("5分钟后提醒我"));
        assertNull(FastIntents.timerLabel("倒计时5分钟"));
        Long s = FastIntents.timerSeconds("1分钟后提醒我喝水");
        assertNotNull(s);
        assertEquals(60L, s.longValue());
    }

    @Test public void timerReplyHonest() {
        String r = com.onesay.assistant.v2.executor.backend.TimerIntentBackend.handedReply(60, "喝水");
        assertTrue(r, r.contains("喝水") && r.contains("交给时钟") && r.contains("点「打开」"));
        assertFalse("不说已开始 / 已设好", r.contains("已开始") || r.contains("已设好"));
        assertFalse(com.onesay.assistant.v2.capability.caps.ClockCaps.TIMER_HANDED_REPLY.contains("已启动"));
    }

    // ---------------------------------------------------------------- 10 开发者模式（系统设置式）

    @Test public void devModeLikeSystem() throws Exception {
        DevMode g = new DevMode();
        long t = 1000;
        assertNull("第 1 下不提示", DevMode.hint(g.tap(t, false)));
        assertNull("第 2 下不提示", DevMode.hint(g.tap(t += 200, false)));
        assertEquals("第 3 下起提示", "再点 4 次进入开发者模式", DevMode.hint(g.tap(t += 200, false)));
        assertEquals("再点 3 次进入开发者模式", DevMode.hint(g.tap(t += 200, false)));
        g.tap(t += 200, false);
        g.tap(t += 200, false);
        int left = g.tap(t += 200, false);
        assertEquals("共 7 次", 0, left);
        assertEquals("已进入开发者模式", DevMode.hint(left));
        assertTrue("开着才出开关行", DevMode.showSwitch(true));
        assertFalse(DevMode.showSwitch(false));
        assertNull("调试页右上角不再有「关闭」", DevMode.developerPageRightAction());
        assertEquals("已退出开发者模式", DevMode.CLOSED);
        String d = src("ui/console/DesignPages.java");
        assertTrue("「高级」里一行开发者模式开关", d.contains("k.switchRow(gx, DevMode.SWITCH_TITLE"));
        assertFalse(src("ui/console/DeveloperPage.java").contains("return \"关闭\";"));
    }
}
