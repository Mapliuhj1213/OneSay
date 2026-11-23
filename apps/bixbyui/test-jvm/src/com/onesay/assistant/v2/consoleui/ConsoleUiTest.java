package com.onesay.assistant.v2.consoleui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.router.ConversationMemory;
import com.onesay.assistant.v2.ui.chat.ChatWindowPolicy;
import com.onesay.assistant.v2.ui.chat.ConsoleCardCopy;
import com.onesay.assistant.v2.ui.console.ConsoleChips;
import com.onesay.assistant.v2.ui.console.ConsoleHomeModel;
import com.onesay.assistant.v2.ui.console.ConsolePalette;
import com.onesay.assistant.v2.ui.console.ConsoleStatus;
import com.onesay.assistant.v2.ui.console.ConsoleTheme;
import com.onesay.assistant.v2.ui.console.ConsoleToday;
import com.onesay.assistant.v2.ui.console.PcEntry;
import com.onesay.assistant.v2.ui.wake.ListenScreenPolicy;
import com.onesay.assistant.v2.ui.wake.ListenScreenPolicy.Action;
import com.onesay.assistant.v2.ui.wake.ListenScreenPolicy.Button;
import com.onesay.assistant.v2.ui.wake.WakeStage.Stage;
import com.onesay.assistant.v2.ui.wake.WakeUiSettings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * ★ feat/console-ui（设计稿 console-v1）：对话控制台 —— 状态标签映射、「今天」块空数据隐藏、建议点击发送、
 * 全屏聆听三按钮行为、所有入口都进全屏聆听、对话窗第一轮就展开、卡片状态字、配色、文案、接线。
 */
public class ConsoleUiTest {

    static String src(String rel) {
        try {
            java.io.File f = new java.io.File(System.getProperty("bixby.root", "."), rel);
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    static final String V2 = "app/src/com/onesay/assistant/v2/";
    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    private static ConsoleStatus.Snapshot okSnap() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        s.done = true;
        s.wakeRunning = Boolean.TRUE;
        s.micReady = true;
        s.micPermission = Boolean.TRUE;
        s.asrConfigured = Boolean.TRUE;
        s.asrKeyInvalid = Boolean.FALSE;
        s.overlayOk = Boolean.TRUE;
        s.rootOk = Boolean.TRUE;
        s.llmConfigured = Boolean.TRUE;
        s.appListPermMissing = Boolean.FALSE;
        return s;
    }

    private static ConsoleChips.Pc pc(String state, String name, Boolean online) {
        ConsoleChips.Pc p = new ConsoleChips.Pc();
        p.state = state;
        p.name = name;
        p.online = online;
        return p;
    }

    // ================================================================== 状态标签

    @Test public void wakeChipGreenOnlyWhenReallyRunning() {
        ConsoleChips.Chip c = ConsoleChips.wake(okSnap());
        assertEquals("随叫随到", c.label);
        assertEquals(ConsoleStatus.Level.OK, c.level);
        assertFalse("绿的只看不点", c.clickable());

        ConsoleStatus.Snapshot s = okSnap();
        s.wakeRunning = Boolean.FALSE;
        c = ConsoleChips.wake(s);
        assertEquals(ConsoleStatus.Level.BAD, c.level);
        assertEquals("红的点了直接拉起服务", ConsoleChips.Action.START_WAKE, c.action);

        s = okSnap();
        s.micReady = false;
        assertEquals(ConsoleStatus.Level.WARN, ConsoleChips.wake(s).level);

        s = okSnap();
        s.paused = true;
        c = ConsoleChips.wake(s);
        assertEquals("暂停 = 灰", ConsoleStatus.Level.OFF, c.level);
        assertFalse(c.clickable());
    }

    @Test public void unknownNeverGreen() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();   // 什么都没测到
        for (ConsoleChips.Chip c : ConsoleChips.build(s, pc(PcEntry.PAIRED, "pc-office", null), null)) {
            assertTrue("查不到绝不写绿：" + c, c.level != ConsoleStatus.Level.OK);
        }
        s.done = true;
        assertEquals("唤醒状态查不到", ConsoleChips.wake(s).label);
        assertEquals(ConsoleStatus.Level.UNKNOWN, ConsoleChips.wake(s).level);
    }

    @Test public void pcChipStates() {
        ConsoleChips.Chip on = ConsoleChips.pc(pc(PcEntry.PAIRED, "pc-office", Boolean.TRUE));
        assertEquals("已连电脑 pc-office", on.label);
        assertEquals(ConsoleStatus.Level.OK, on.level);
        ConsoleChips.Chip off = ConsoleChips.pc(pc(PcEntry.PAIRED, "pc-office", Boolean.FALSE));
        assertEquals(ConsoleStatus.Level.WARN, off.level);
        assertEquals("不在线（黄）点了去电脑联动页", ConsoleChips.Action.PC_PAGE, off.action);
        ConsoleChips.Chip finding = ConsoleChips.pc(pc(PcEntry.PAIRED, "pc-office", null));
        assertEquals("还在找 = 灰，不写已连", ConsoleStatus.Level.UNKNOWN, finding.level);
        assertFalse(finding.label.contains("已连"));
        assertEquals(ConsoleStatus.Level.BAD, ConsoleChips.pc(pc(PcEntry.KEY_LOST, "x", null)).level);
        assertEquals(ConsoleStatus.Level.OFF, ConsoleChips.pc(pc(PcEntry.UNPAIRED, "", null)).level);
        assertEquals(ConsoleStatus.Level.OFF, ConsoleChips.pc(pc(PcEntry.OFF, "", null)).level);
        assertNull("被停用的不显示", ConsoleChips.pc(pc(PcEntry.KILLED, "", null)));
        String longName = ConsoleChips.pc(pc(PcEntry.PAIRED, "DESKTOP-ABCDEFGHIJKLMN", Boolean.TRUE)).label;
        assertTrue("长电脑名截短：" + longName, longName.endsWith("…") && longName.length() <= "已连电脑 ".length() + 14);
    }

    @Test public void locationAndVoiceAndPermChips() {
        ConsoleChips.Chip loc = ConsoleChips.location(Boolean.FALSE);
        assertEquals("定位没开", loc.label);
        assertEquals(ConsoleStatus.Level.WARN, loc.level);
        assertEquals(ConsoleChips.Action.LOCATION_SETTINGS, loc.action);
        assertNull(ConsoleChips.location(Boolean.TRUE));
        assertNull("查不到不显示", ConsoleChips.location(null));

        ConsoleStatus.Snapshot s = okSnap();
        s.micPermission = Boolean.FALSE;
        List<ConsoleChips.Chip> v = ConsoleChips.voice(s);
        assertEquals(1, v.size());
        assertEquals(ConsoleChips.Action.MIC_SETTINGS, v.get(0).action);
        assertNull("录音已经单独报了，权限那枚不重复", ConsoleChips.perm(s));

        s = okSnap();
        s.asrConfigured = Boolean.FALSE;
        assertEquals(ConsoleChips.Action.ASR_KEY, ConsoleChips.voice(s).get(0).action);

        s = okSnap();
        assertNull("全好 = 没有权限标签", ConsoleChips.perm(s));
        s.overlayOk = Boolean.FALSE;
        ConsoleChips.Chip p = ConsoleChips.perm(s);
        assertNotNull(p);
        assertEquals(ConsoleStatus.Level.BAD, p.level);
        assertEquals(ConsoleChips.Action.PERM_PAGE, p.action);
    }

    @Test public void onlyYellowAndRedAreClickableAndOrderIsStable() {
        ConsoleStatus.Snapshot s = okSnap();
        s.overlayOk = Boolean.FALSE;
        List<ConsoleChips.Chip> l = ConsoleChips.build(s, pc(PcEntry.PAIRED, "pc-office", Boolean.TRUE), Boolean.FALSE);
        assertEquals("[已连电脑 pc-office/OK/NONE, 随叫随到/OK/NONE, 权限没配好/BAD/PERM_PAGE, 定位没开/WARN/LOCATION_SETTINGS]", l.toString());
        for (ConsoleChips.Chip c : l) {
            boolean warnBad = c.level == ConsoleStatus.Level.WARN || c.level == ConsoleStatus.Level.BAD;
            assertEquals(c.toString(), warnBad, c.clickable());
        }
    }

    @Test public void paletteLevelColors() {
        ConsolePalette d = ConsolePalette.DARK;
        assertEquals(0xFF0E0F12, d.bg);
        assertEquals(0xFF1A1C21, d.card);
        assertEquals(0xFF0A5BFF, d.blue);
        assertEquals("苔绿只表示开着", 0xFF84AE64, d.levelColor(ConsoleStatus.Level.OK));
        assertEquals(d.yellow, d.levelColor(ConsoleStatus.Level.WARN));
        assertEquals(d.red, d.levelColor(ConsoleStatus.Level.BAD));
        assertEquals(d.gray, d.levelColor(ConsoleStatus.Level.OFF));
        assertEquals(d.gray, d.levelColor(ConsoleStatus.Level.UNKNOWN));
        for (ConsolePalette p : new ConsolePalette[]{ConsolePalette.DARK, ConsolePalette.LIGHT}) {
            assertTrue("正文对底色够清楚", ConsoleTheme.contrast(p.text, p.bg) >= 7.0);
            assertTrue("次级字对卡片", ConsoleTheme.contrast(p.sub, p.card) >= 4.5);
            assertTrue("蓝底白字", ConsoleTheme.contrast(p.onBlue, p.blue) >= 4.5);
        }
        assertFalse(ConsolePalette.of(false).dark);
        assertTrue(ConsolePalette.of(true).dark);
    }

    // ================================================================== 今天

    private static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(SH);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    @Test public void todayAllEmptyHidesWholeBlock() {
        long now = at(2026, 11, 23, 23, 20);
        assertTrue(ConsoleToday.rows(new ConsoleToday.Input(), now, SH).isEmpty());
        ConsoleToday.Input in = new ConsoleToday.Input();
        in.openTodos = 0;
        in.pcRunning = 0;
        assertTrue("零也不显示", ConsoleToday.rows(in, now, SH).isEmpty());
        assertTrue(ConsoleToday.rows(null, now, SH).isEmpty());
        in.nextAlarmAt = now + 3 * 24 * 3600_000L;
        assertTrue("三天后的闹钟不算今天", ConsoleToday.rows(in, now, SH).isEmpty());
        in.nextAlarmAt = now - 1000L;
        assertTrue("已经过了的不显示", ConsoleToday.rows(in, now, SH).isEmpty());
    }

    @Test public void todayRowsRealDataOnly() {
        long now = at(2026, 11, 23, 23, 20);
        ConsoleToday.Input in = new ConsoleToday.Input();
        in.nextAlarmAt = at(2026, 11, 24, 7, 0);
        in.openTodos = 2;
        in.pcRunning = 1;
        assertEquals("[闹钟=明天 07:00, 待办=2 件没做, 电脑任务=1 件进行中]", ConsoleToday.rows(in, now, SH).toString());
        in = new ConsoleToday.Input();
        in.openTodos = 3;
        assertEquals("没数据的行不显示", "[待办=3 件没做]", ConsoleToday.rows(in, now, SH).toString());
        in = new ConsoleToday.Input();
        in.nextAlarmAt = at(2026, 11, 24, 6, 5);
        assertEquals("[闹钟=今天 06:05]", ConsoleToday.rows(in, at(2026, 11, 24, 1, 0), SH).toString());
    }

    // ================================================================== 问候 / 建议 / 输入

    @Test public void greetingByHourNoMadeUpName() {
        assertEquals("早上好", ConsoleHomeModel.greeting(8));
        assertEquals("中午好", ConsoleHomeModel.greeting(12));
        assertEquals("下午好", ConsoleHomeModel.greeting(15));
        assertEquals("晚上好", ConsoleHomeModel.greeting(23));
        assertEquals("夜深了", ConsoleHomeModel.greeting(2));
    }

    @Test public void suggestionTapSendsExactSentence() {
        final List<String> sent = new ArrayList<String>();
        ConsoleHomeModel.Sender out = new ConsoleHomeModel.Sender() {
            @Override public void send(String text) {
                sent.add(text);
            }
        };
        List<ConsoleHomeModel.Suggestion> l = ConsoleHomeModel.suggestions(true);
        assertEquals(4, l.size());
        assertEquals("[明早 7 点叫我/定闹钟, 看看屏幕上是什么/识屏, 把这张图发到电脑/交给电脑, 半小时后提醒喝水/提醒]", l.toString());
        for (ConsoleHomeModel.Suggestion s : l) assertTrue(ConsoleHomeModel.tapSuggestion(s, out));
        assertEquals("点了就当作用户发出这句（原话）", "[明早 7 点叫我, 看看屏幕上是什么, 把这张图发到电脑, 半小时后提醒喝水]", sent.toString());
        List<ConsoleHomeModel.Suggestion> noPc = ConsoleHomeModel.suggestions(false);
        assertEquals(4, noPc.size());
        for (ConsoleHomeModel.Suggestion s : noPc) assertFalse("没连电脑不摆「交给电脑」", s.tag.contains("电脑"));
        assertFalse(ConsoleHomeModel.tapSuggestion(null, out));
    }

    @Test public void submitTrimsAndDropsEmpty() {
        final List<String> sent = new ArrayList<String>();
        ConsoleHomeModel.Sender out = new ConsoleHomeModel.Sender() {
            @Override public void send(String text) {
                sent.add(text);
            }
        };
        assertFalse(ConsoleHomeModel.submit("   ", out));
        assertFalse(ConsoleHomeModel.submit(null, out));
        assertTrue(ConsoleHomeModel.submit("  明天天气  ", out));
        assertEquals("[明天天气]", sent.toString());
        assertTrue(ConsoleHomeModel.showSend("a"));
        assertFalse(ConsoleHomeModel.showSend(" "));
    }

    @Test public void micTapVsHold() {
        assertEquals("点一下：接着听", ConsoleHomeModel.MicRelease.TAP_KEEP_LISTENING, ConsoleHomeModel.onRelease(120, true));
        assertEquals("按住说完松手：马上识别", ConsoleHomeModel.MicRelease.HOLD_FINISH, ConsoleHomeModel.onRelease(1500, true));
        assertEquals("按住没说话就松手：停掉不发", ConsoleHomeModel.MicRelease.HOLD_CANCEL, ConsoleHomeModel.onRelease(1500, false));
    }

    @Test public void historyRowsSkipSummaryAndTruncate() {
        ConversationMemory m = ConversationMemory.newForTest(new ConversationMemory.Clock() {
            @Override public long now() {
                return 1000L;
            }
        });
        StringBuilder longA = new StringBuilder();
        for (int i = 0; i < 60; i++) longA.append('字');
        m.record("明早 7 点叫我", "好了，明早 7 点响。");
        m.record("讲个长的", longA.toString());
        List<String[]> rows = ConsoleHomeModel.historyRows(m.snapshot());
        assertEquals(2, rows.size());
        assertEquals("明早 7 点叫我", rows.get(0)[0]);
        assertTrue(rows.get(1)[1].endsWith("…"));
        assertTrue(rows.get(1)[1].length() <= ConsoleHomeModel.HISTORY_PREVIEW);
        List<ConversationMemory.Turn> withSummary = new ArrayList<ConversationMemory.Turn>(m.snapshot());
        assertEquals(2, ConsoleHomeModel.historyRows(withSummary).size());
        assertTrue(ConsoleHomeModel.historyRows(null).isEmpty());
    }

    // ================================================================== 全屏聆听（③）

    @Test public void listenScreenShowsForEveryListening() {
        assertTrue("开着 + 新界面 + 全屏窗 + 在听 → 盖上", ListenScreenPolicy.shows(true, true, true, Stage.LISTENING, false, false));
        assertFalse("设置关掉 = 原来的聆听卡片", ListenScreenPolicy.shows(false, true, true, Stage.LISTENING, false, false));
        assertFalse("旧界面没有", ListenScreenPolicy.shows(true, false, true, Stage.LISTENING, false, false));
        assertFalse("wrap 窗没有全屏", ListenScreenPolicy.shows(true, true, false, Stage.LISTENING, false, false));
        assertFalse("说完了（在想）→ 收掉进对话", ListenScreenPolicy.shows(true, true, true, Stage.THINKING, true, false));
        assertFalse(ListenScreenPolicy.shows(true, true, true, Stage.RESULT, true, false));
        assertFalse(ListenScreenPolicy.shows(true, true, true, Stage.TEXT, true, false));
        assertTrue("这一轮盖过、出错 → 留着说原因", ListenScreenPolicy.shows(true, true, true, Stage.ERROR, true, false));
        assertFalse("出错画面点了停止 → 收掉", ListenScreenPolicy.shows(true, true, true, Stage.ERROR, true, true));
        assertFalse("没盖过的出错（打字出错等）不盖", ListenScreenPolicy.shows(true, true, true, Stage.ERROR, false, false));
        assertTrue("停止后收起：跟着整窗淡出，不露小卡片", ListenScreenPolicy.shows(true, true, true, Stage.DISMISSING, true, false));
        assertFalse("对话窗收起不盖", ListenScreenPolicy.shows(true, true, true, Stage.DISMISSING, false, false));
        assertTrue("默认开", WakeUiSettings.CONSOLE_DEFAULT);
    }

    @Test public void threeButtons() {
        assertEquals("重说 = 作废这句重新听", Action.RESTART_LISTEN, ListenScreenPolicy.action(Button.REDO, Stage.LISTENING, false));
        assertEquals(Action.RESTART_LISTEN, ListenScreenPolicy.action(Button.REDO, Stage.ERROR, true));
        assertEquals("停止 = 停收音不发", Action.STOP_LISTEN, ListenScreenPolicy.action(Button.STOP, Stage.LISTENING, false));
        assertEquals(Action.STOP_LISTEN, ListenScreenPolicy.action(Button.STOP, Stage.LISTENING, true));
        assertEquals("出错画面 + 有对话 → 回对话", Action.CLOSE_TO_CHAT, ListenScreenPolicy.action(Button.STOP, Stage.ERROR, true));
        assertEquals("出错画面 + 没对话 → 收起", Action.CLOSE_PANEL, ListenScreenPolicy.action(Button.STOP, Stage.ERROR, false));
        assertEquals("打字 = 进对话窗打字", Action.TYPE, ListenScreenPolicy.action(Button.TYPE, Stage.LISTENING, false));
        assertEquals("右上键盘同打字", Action.TYPE, ListenScreenPolicy.action(Button.KEYBOARD, Stage.LISTENING, false));
        assertEquals("左上返回同停止", Action.STOP_LISTEN, ListenScreenPolicy.action(Button.BACK, Stage.LISTENING, false));
        assertEquals(Action.NONE, ListenScreenPolicy.action(Button.NONE, Stage.LISTENING, false));
        assertEquals(Action.NONE, ListenScreenPolicy.action(null, Stage.LISTENING, false));
        assertEquals("收起中不再接按钮", Action.NONE, ListenScreenPolicy.action(Button.STOP, Stage.DISMISSING, false));
    }

    @Test public void buttonGeometryAndHitTest() {
        int w = 1080, h = 2220, inset = 126;
        float d = 2.75f;
        for (Button b : new Button[]{Button.REDO, Button.STOP, Button.TYPE, Button.BACK, Button.KEYBOARD}) {
            float[] c = ListenScreenPolicy.button(b, w, h, d, inset);
            assertEquals("点圆心就是这颗：" + b, b, ListenScreenPolicy.hit(c[0], c[1], w, h, d, inset));
        }
        float[] stop = ListenScreenPolicy.button(Button.STOP, w, h, d, inset);
        float[] redo = ListenScreenPolicy.button(Button.REDO, w, h, d, inset);
        float[] type = ListenScreenPolicy.button(Button.TYPE, w, h, d, inset);
        assertEquals("停止在正中", w / 2f, stop[0], 0.01f);
        assertTrue("重说在左、打字在右", redo[0] < stop[0] && stop[0] < type[0]);
        assertTrue("三颗都在导航栏上面", stop[1] + stop[2] < h - inset);
        assertTrue("停止钮最大", stop[2] > redo[2]);
        assertEquals("屏幕中间空白处不是按钮", Button.NONE, ListenScreenPolicy.hit(w / 2f, h / 2f, w, h, d, inset));
        assertTrue("圆球跟音量变大", ListenScreenPolicy.orbRadius(d, 1f) > ListenScreenPolicy.orbRadius(d, 0f));
        assertEquals("音量超界夹住", ListenScreenPolicy.orbRadius(d, 1f), ListenScreenPolicy.orbRadius(d, 5f), 0.001f);
    }

    @Test public void liveText() {
        String[] t = ListenScreenPolicy.liveText(null, null, Stage.LISTENING);
        assertEquals("", t[0]);
        assertEquals(ListenScreenPolicy.HINT, t[1]);
        t = ListenScreenPolicy.liveText("半小时后提醒我喝水", null, Stage.LISTENING);
        assertEquals("边说边出字", "半小时后提醒我喝水", t[0]);
        assertEquals("…", t[1]);
        t = ListenScreenPolicy.liveText("x", null, Stage.ERROR);
        assertTrue(t[0].length() > 0);
        assertEquals("出错说原因", "没有网络", ListenScreenPolicy.liveText("x", "没有网络", Stage.ERROR)[0]);
    }

    @Test public void allEntriesGoIntoListenScreen() {
        // 所有「开始听」都汇到 WakeService.requestWake → 卡片窗 LISTENING；全屏聆听就盖在 LISTENING 上，所以入口不用一个个改
        String entries = src(V2 + "entry/Entries.java");
        assertTrue("语音唤起 = summonVoice", entries.contains("summonVoice(app, source);"));
        assertTrue("summonVoice → requestWake", entries.contains("WakeService.requestWake(source, false);"));
        assertTrue("快捷开关经跳板 → Entries.fire", src(V2 + "entry/SummonTileService.java").contains("EntryLaunchActivity"));
        assertTrue("小部件经跳板 → Entries.fire", src(V2 + "entry/SummonWidgetProvider.java").contains("EntryLaunchActivity"));
        assertTrue(src(V2 + "entry/EntryLaunchActivity.java").contains("Entries.fire("));
        assertTrue("默认助理（长按电源）", src(V2 + "assist/OneSaySession.java").contains("Entries.summonVoice("));
        assertTrue("锁屏助理", src(V2 + "assist/OneSayVoiceInteractionService.java").contains("Entries.summonVoice("));
        assertTrue("首页麦克风", src(V2 + "ui/console/ConsoleHomePage.java").contains("Entries.summonVoice(c.act, \"console-home\")"));
        String fsv = src(V2 + "ui/FloatingSheetView.java");
        assertTrue("阶段一变就判断盖不盖", fsv.contains("lsTrack(t);"));
        assertTrue("画在最上面", fsv.contains("if (listenScreenActive()) {   // ★ feat/console-ui：全屏聆听盖在最上面"));
        assertTrue("接走所有点按", fsv.contains("if (listenScreenActive()) {   // ★ feat/console-ui：全屏聆听接走所有点按"));
        assertTrue("盖着时整窗收触摸", fsv.contains("if (listenScreenActive()) return null;"));
        assertTrue("判断只看阶段，不看是哪个入口", fsv.contains("ListenScreenPolicy.shows(mConsoleOn, mV2, mV2FullWindow, mStage.stage(), mLsRound, mLsClosed)"));
    }

    @Test public void listenButtonsUseIconsNotChinese() {
        String ls = src(V2 + "ui/wake/ListenScreen.java");
        assertTrue("重说 = 循环箭头", ls.contains("ConsoleGlyph.REDO"));
        assertTrue("停止 = 红圆白 X", ls.contains("Button.STOP, w, h, bottomInset, RED, 0xFFFFFFFF, ConsoleGlyph.CLOSE"));
        assertTrue("打字 = 键盘", ls.contains("Button.TYPE, w, h, bottomInset, CARD, SUB, ConsoleGlyph.KEYBOARD"));
        assertFalse("画布上不写汉字当图标", ls.contains("drawText("+ "\""));
        String home = src(V2 + "ui/console/ConsoleHomePage.java");
        for (String g : new String[]{"ConsoleGlyph.MENU", "ConsoleGlyph.GEAR", "ConsoleGlyph.PLUS", "ConsoleGlyph.MIC", "ConsoleGlyph.PLANE"}) {
            assertTrue("首页按钮用图形：" + g, home.contains(g));
        }
        assertFalse("不用 ☰ ⚙ ＋ 字符当图标", home.contains("\"☰\"") || home.contains("\"⚙\"") || home.contains("\"＋\""));
    }

    // ================================================================== 对话窗（②）

    @Test public void consoleExpandsFromFirstRound() {
        ChatWindowPolicy.Trigger sub = ChatWindowPolicy.Trigger.SUBMIT, wake = ChatWindowPolicy.Trigger.WAKE, cont = ChatWindowPolicy.Trigger.CONTINUE_LISTEN;
        assertEquals("第一轮提交就进对话窗", ChatWindowPolicy.Decision.EXPAND, ChatWindowPolicy.decide(sub, true, true, false, 0, false, false, true));
        assertEquals(ChatWindowPolicy.Decision.EXPAND, ChatWindowPolicy.decide(cont, true, true, false, 0, true, true, true));
        assertEquals("新唤醒本身不展开（全屏聆听盖着，说完再展开）", ChatWindowPolicy.Decision.CARD, ChatWindowPolicy.decide(wake, true, true, false, 0, false, false, true));
        assertEquals(ChatWindowPolicy.Decision.EXPAND, ChatWindowPolicy.decide(wake, true, true, true, 3, true, true, true));
        assertEquals("超时的旧会话先清，再进对话窗", ChatWindowPolicy.Decision.RESET_EXPAND, ChatWindowPolicy.decide(sub, true, true, false, 2, false, false, true));
        assertEquals(ChatWindowPolicy.Decision.RESET_CARD, ChatWindowPolicy.decide(wake, true, true, false, 2, false, false, true));
        assertEquals("旧界面没有对话窗", ChatWindowPolicy.Decision.CARD, ChatWindowPolicy.decide(sub, false, true, false, 0, false, false, true));
        assertEquals(ChatWindowPolicy.Decision.CARD, ChatWindowPolicy.decide(sub, true, false, false, 0, false, false, true));
        // 关掉 = 原规则逐条一致
        for (ChatWindowPolicy.Trigger t : ChatWindowPolicy.Trigger.values()) {
            for (int r = 0; r < 3; r++) {
                for (int m = 0; m < 8; m++) {
                    boolean active = (m & 1) != 0, panel = (m & 2) != 0, mem = (m & 4) != 0;
                    assertEquals(ChatWindowPolicy.decide(t, true, true, active, r, panel, mem),
                            ChatWindowPolicy.decide(t, true, true, active, r, panel, mem, false));
                }
            }
        }
        assertEquals(ChatWindowPolicy.Decision.CARD, ChatWindowPolicy.decide(sub, true, true, false, 0, false, false));
    }

    @Test public void chatColorsAndTitle() {
        assertEquals(0xFF0E0F12, ChatWindowPolicy.bg(true));
        assertEquals(ChatWindowPolicy.BG, ChatWindowPolicy.bg(false));
        assertEquals("用户蓝气泡", 0xFF0A5BFF, ChatWindowPolicy.bubble(true));
        assertEquals(ChatWindowPolicy.BUBBLE, ChatWindowPolicy.bubble(false));
        assertEquals("今晚", ChatWindowPolicy.sessionTitle(21));
        assertEquals("下午", ChatWindowPolicy.sessionTitle(15));
        assertEquals("今早", ChatWindowPolicy.sessionTitle(7));
    }

    @Test public void cardStatusCopy() {
        ConsoleCardCopy.Status s = ConsoleCardCopy.confirm(false, false);
        assertEquals("要你确认/等你点头", s.toString());
        assertEquals("等你点头 = 黄", ChatWindowPolicy.CONSOLE_WARN, s.color);
        assertEquals("没做", ConsoleCardCopy.confirm(true, true).status);
        assertEquals(ChatWindowPolicy.CONSOLE_OFF, ConsoleCardCopy.confirm(true, false).color);
        ConsoleCardCopy.Status ok = ConsoleCardCopy.result(true, "设置闹钟，到点响铃");
        assertEquals("设置闹钟/已办好", ok.toString());
        assertEquals(ChatWindowPolicy.CONSOLE_OK, ok.color);
        ConsoleCardCopy.Status bad = ConsoleCardCopy.result(false, "system.set_alarm");
        assertEquals("像内部标识的不露出来", "办事结果/没办成", bad.toString());
        assertEquals(ChatWindowPolicy.CONSOLE_BAD, bad.color);
        assertEquals("办事结果", ConsoleCardCopy.result(true, null).label);
        assertTrue(ConsoleCardCopy.result(true, "一二三四五六七八九十一二三四五六七八九十").label.endsWith("…"));
    }

    @Test public void confirmLogicUntouched() {
        // 确认卡只换外观：按钮回调仍是原来那条决策通道（不点不执行）
        String fsv = src(V2 + "ui/FloatingSheetView.java");
        assertTrue(fsv.contains("mListener.onConfirmChoice(mPendingCap, mPendingArgs, mPendingTainted, choice);"));
        assertTrue(fsv.contains("mListener.onConfirmChoice(mPendingCap, mPendingArgs, mPendingTainted, CHOICE_ONCE);"));
        int styleAt = fsv.indexOf("private void consoleStyle()");
        int styleEnd = fsv.indexOf("private void setResultTextWidth");
        String style = fsv.substring(styleAt, styleEnd);
        assertFalse("样式里不碰决策", style.contains("onConfirmChoice") || style.contains("setOnClickListener"));
    }

    @Test public void bottomBarRulesKept() {
        String bar = src(V2 + "ui/chat/ChatBottomBar.java");
        assertTrue("只认键盘发送", bar.contains("if (ChatWindowPolicy.isSendAction(actionId))"));
        assertTrue("点麦克风只录音", bar.contains("mTypingIntent = false;   // 点麦克风 = 只录音"));
        String home = src(V2 + "ui/console/ConsoleHomePage.java");
        assertTrue("首页也只认键盘发送（收键盘不发）", home.contains("if (actionId == EditorInfo.IME_ACTION_SEND)"));
        int micAt = home.indexOf("private boolean onMicTouch");
        String mic = home.substring(micAt, home.indexOf("private void send()"));
        assertFalse("点麦克风不弹键盘", mic.contains("showSoftInput") || mic.contains("requestFocus"));
        assertTrue(mic.contains("hideIme();"));
    }

    @Test public void islandAndAppearSpringUntouched() {
        String fsv = src(V2 + "ui/FloatingSheetView.java");
        int a = fsv.indexOf("// ------------------------------------------------------------------ ★ feat/console-ui：全屏聆听");
        int b = fsv.indexOf("public interface V2Host {");
        String block = fsv.substring(a, b);
        assertFalse("全屏聆听不碰灵动岛", block.contains("S1Hooks") || block.contains("island") || block.contains("MotionSpec"));
    }

    // ================================================================== 首页 / 设置 / 文案

    @Test public void homeIsConsoleAndOldHomeMovedToSettings() {
        String con = src(V2 + "ui/console/Console.java");
        assertTrue(con.contains("nav.setRoot(new ConsoleHomePage(this));"));
        assertTrue("关掉 = 原来的首页（可回退）", con.contains("mHome = new HomePage(this);"));
        String home = src(V2 + "ui/console/ConsoleHomePage.java");
        assertTrue("⚙ = 原来首页整页（设置模式）", home.contains("c.push(new HomePage(c, true));"));
        assertTrue("☰ = 历史", home.contains("c.push(new HistoryPage(c));"));
        String old = src(V2 + "ui/console/HomePage.java");
        for (String g : new String[]{"\"权限配置\"", "\"基础设置\"", "\"插件\"", "\"高级\""}) assertTrue("分组不变：" + g, old.contains(g));
        assertTrue(src(V2 + "ui/console/DesignPages.java").contains("WakeUiSettings.setConsoleMode(c.act, on)"));
    }

    @Test public void copyIsPlainAndBrandFree() {
        String[] files = {"ui/console/ConsoleHomeModel.java", "ui/console/ConsoleChips.java", "ui/console/ConsoleToday.java",
                "ui/console/ConsoleHomePage.java", "ui/console/HistoryPage.java", "ui/wake/ListenScreenPolicy.java",
                "ui/wake/ListenScreen.java", "ui/chat/ConsoleCardCopy.java"};
        String[] banned = {"啥", "咋", "呗", "Bixby", "bixby", "三星", "Samsung", "DeX", "小布", "Shizuku", "DeepSeek", "百炼", "阿里", "Google", "谷歌"};
        for (String f : files) {
            String s = strings(src(V2 + f));
            for (String w : banned) assertFalse(f + " 用户可见文字里出现「" + w + "」", s.contains(w));
        }
        List<String> labels = new ArrayList<String>();
        ConsoleStatus.Snapshot s = okSnap();
        s.wakeRunning = Boolean.FALSE;
        s.micPermission = Boolean.FALSE;
        s.asrConfigured = Boolean.FALSE;
        s.overlayOk = Boolean.FALSE;
        for (ConsoleChips.Chip c : ConsoleChips.build(s, pc(PcEntry.KEY_LOST, "x", null), Boolean.FALSE)) labels.add(c.label);
        for (String l : labels) assertFalse("标签不露内部码：" + l, l.matches(".*[A-Z_]{4,}.*"));
    }

    // ================================================================== 有字 → 纸飞机发送，清空 → 麦克风

    @Test public void sendButtonSwitchesWithText() {
        assertFalse("空 = 麦克风", ConsoleHomeModel.showSend(""));
        assertFalse(ConsoleHomeModel.showSend(null));
        assertFalse("只有空格 = 麦克风", ConsoleHomeModel.showSend("   "));
        assertFalse("全角空格 / 换行也算空白", ConsoleHomeModel.showSend("\u3000\n\t"));
        assertTrue("有字 = 发送", ConsoleHomeModel.showSend(" 明早叫我 "));
        // 首页与对话窗底栏同一个判断
        for (String t : new String[]{"", " ", "\u3000", "a", " 好 "}) {
            assertEquals(t, com.onesay.assistant.v2.ui.widget.SendMicPolicy.mode(t, false, false) == com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode.SEND,
                    ConsoleHomeModel.showSend(t));
        }
        final List<String> sent = new ArrayList<String>();
        ConsoleHomeModel.Sender out = new ConsoleHomeModel.Sender() {
            @Override public void send(String text) {
                sent.add(text);
            }
        };
        assertFalse("全角空格不发", ConsoleHomeModel.submit("\u3000\u3000", out));
        assertTrue(sent.isEmpty());
    }

    @Test public void sendIconIsPaperPlaneVector() {
        String g = src(V2 + "ui/console/ConsoleGlyph.java");
        assertTrue("有纸飞机矢量图标", g.contains("case PLANE:") && g.contains("path.close();"));
        String home = src(V2 + "ui/console/ConsoleHomePage.java");
        assertTrue("首页发送 = 纸飞机", home.contains("new ConsoleGlyph(ConsoleGlyph.PLANE, p.onBlue)"));
        assertTrue("首页按文字切换图标", home.contains("setSendMode(ConsoleHomeModel.showSend(") && home.contains("mMic.setImageDrawable(send ? mSendGlyph : mMicGlyph);"));
        assertTrue("首页有字时点按钮 = 发送", home.contains("if (mSendMode) {") && home.contains("if (a == MotionEvent.ACTION_UP) send();"));
        String bar = src(V2 + "ui/chat/ChatBottomBar.java");
        assertTrue("对话窗底栏发送 = 纸飞机", bar.contains("ConsoleGlyph.PLANE, 0xFFFFFFFF"));
        assertTrue("对话窗切换仍按 SendMicPolicy", bar.contains("mMic.setImageDrawable(send ? mSendIcon : stop ? mStopIcon : mMicIcon);"));
        assertFalse("发送钮不写汉字", home.contains("setText(\"发送\")") || bar.contains("setText(\"发送\")"));
    }

    /** 只取源码里的字符串字面量（注释里可以提别家名字作说明）。 */
    static String strings(String src) {
        StringBuilder b = new StringBuilder();
        boolean in = false, esc = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (!in) {
                if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                    int e = src.indexOf('\n', i);
                    if (e < 0) break;
                    i = e;
                    continue;
                }
                if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                    int e = src.indexOf("*/", i + 2);
                    if (e < 0) break;
                    i = e + 1;
                    continue;
                }
                if (c == '"') in = true;
                continue;
            }
            if (esc) {
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '"') {
                in = false;
                b.append('\n');
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }
}
