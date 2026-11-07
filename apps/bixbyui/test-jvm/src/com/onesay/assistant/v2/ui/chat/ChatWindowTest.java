package com.onesay.assistant.v2.ui.chat;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** ★ ui-K1 全屏对话窗：何时展开、形变几何、Markdown 简析、会话历史 → 列表项、追问建议、挂接点。 */
public class ChatWindowTest {

    private static final ChatWindowPolicy.Trigger WAKE = ChatWindowPolicy.Trigger.WAKE;
    private static final ChatWindowPolicy.Trigger SUBMIT = ChatWindowPolicy.Trigger.SUBMIT;

    // ------------------------------------------------------------------ 何时展开

    @Test public void firstRoundOfSessionKeepsOriginalCard() {
        assertEquals(ChatWindowPolicy.Decision.CARD, ChatWindowPolicy.decide(WAKE, true, true, false, 0, false, false));
        assertEquals(ChatWindowPolicy.Decision.CARD, ChatWindowPolicy.decide(SUBMIT, true, true, false, 0, true, true));
    }

    @Test public void secondTimeInSameSessionExpands() {
        assertEquals("面板还在", ChatWindowPolicy.Decision.EXPAND, ChatWindowPolicy.decide(WAKE, true, true, false, 1, true, true));
        assertEquals("面板收了但会话没超时", ChatWindowPolicy.Decision.EXPAND, ChatWindowPolicy.decide(WAKE, true, true, false, 1, false, true));
        assertEquals("打字接着问", ChatWindowPolicy.Decision.EXPAND, ChatWindowPolicy.decide(SUBMIT, true, true, false, 2, true, false));
        assertEquals("续说", ChatWindowPolicy.Decision.EXPAND,
                ChatWindowPolicy.decide(ChatWindowPolicy.Trigger.CONTINUE_LISTEN, true, true, false, 1, true, true));
        assertEquals("已经展开就保持", ChatWindowPolicy.Decision.EXPAND, ChatWindowPolicy.decide(WAKE, true, true, true, 0, false, false));
    }

    @Test public void expiredSessionResetsAndOldUiNeverExpands() {
        assertEquals(ChatWindowPolicy.Decision.RESET_CARD, ChatWindowPolicy.decide(WAKE, true, true, false, 3, false, false));
        assertEquals("旧界面", ChatWindowPolicy.Decision.CARD, ChatWindowPolicy.decide(WAKE, false, true, false, 3, true, true));
        assertEquals("wrap 窗", ChatWindowPolicy.Decision.CARD, ChatWindowPolicy.decide(WAKE, true, false, false, 3, true, true));
    }

    @Test public void islandMorphOnlyOutsideChat() {
        assertTrue(ChatWindowPolicy.allowIslandMorph(false));
        assertFalse(ChatWindowPolicy.allowIslandMorph(true));
    }

    // ------------------------------------------------------------------ 形变

    @Test public void morphGeometryInterpolatesCardToFullScreen() {
        float[] card = {42, 1200, 1038, 2000}, full = {0, 0, 1080, 2100};
        assertArrayEquals(card, ChatWindowPolicy.lerpRect(card, full, 0f), 1e-3f);
        assertArrayEquals(full, ChatWindowPolicy.lerpRect(card, full, 1f), 1e-3f);
        assertArrayEquals(new float[]{21, 600, 1059, 2050}, ChatWindowPolicy.lerpRect(card, full, 0.5f), 1e-3f);
        assertEquals(102f, ChatWindowPolicy.radius(102f, 0f), 1e-3f);
        assertEquals(0f, ChatWindowPolicy.radius(102f, 1f), 1e-3f);
        assertEquals(0xFF808080, ChatWindowPolicy.lerpColor(0xFF000000, 0xFFFFFFFF, 0.5f));
    }

    @Test public void decelerationCurveIsMonotonicAndFrontLoaded() {
        float prev = -1f;
        for (int i = 0; i <= 10; i++) {
            float v = ChatWindowPolicy.decelerate(i / 10f, ChatWindowPolicy.DECEL_FACTOR);
            assertTrue(v >= prev);
            prev = v;
        }
        assertEquals(0f, ChatWindowPolicy.decelerate(0f, 1.6f), 1e-6f);
        assertEquals(1f, ChatWindowPolicy.decelerate(1f, 1.6f), 1e-6f);
        assertTrue("减速：前半段走完大半", ChatWindowPolicy.decelerate(0.5f, 1.6f) > 0.75f);
    }

    @Test public void durationsRespectSystemAnimationSwitch() {
        assertTrue(ChatWindowPolicy.duration(false, true) >= 300 && ChatWindowPolicy.duration(false, true) <= 350);
        assertTrue(ChatWindowPolicy.duration(false, false) > 0);
        assertEquals("关闭动画 → 直接到终态", 0L, ChatWindowPolicy.duration(true, true));
        assertEquals(0L, ChatWindowPolicy.duration(true, false));
    }

    // ------------------------------------------------------------------ Markdown

    @Test public void markdownBoldAndBulletsLikeScreenshot() {
        ChatMarkdown.Doc d = ChatMarkdown.parse("截至2026年11月3日收盘：\n\n- **上证A股指数**：3783.02，下跌0.83%\n- **深证成指**：10863.12\n\n\n\n市场整体偏弱。");
        assertEquals("截至2026年11月3日收盘：\n\n上证A股指数：3783.02，下跌0.83%\n深证成指：10863.12\n\n市场整体偏弱。", d.text);
        assertEquals(2, d.bullets.size());
        assertEquals("上证A股指数：3783.02，下跌0.83%", sub(d.text, d.bullets.get(0)));
        assertEquals(2, d.bold.size());
        assertEquals("上证A股指数", sub(d.text, d.bold.get(0)));
        assertEquals("深证成指", sub(d.text, d.bold.get(1)));
    }

    @Test public void markdownHeadingsCodeNumberedAndStreamingStars() {
        ChatMarkdown.Doc d = ChatMarkdown.parse("## 结论\n用 `adb` 查。\n1. 第一步\n2. 第二步\n还在写**半截");
        assertEquals("结论\n用 adb 查。\n1. 第一步\n2. 第二步\n还在写半截", d.text);
        assertEquals(1, d.headings.size());
        assertEquals("结论", sub(d.text, d.headings.get(0)));
        assertTrue("编号列表保留编号、不加圆点", d.bullets.isEmpty());
        assertEquals("* 列表也认", 1, ChatMarkdown.parse("* 一项").bullets.size());
        assertEquals("", ChatMarkdown.parse(null).text);
        assertEquals("分隔线丢掉", "上\n下", ChatMarkdown.parse("上\n---\n下").text);
    }

    @Test public void plainTextForCopyShareSpeak() {
        assertEquals("要点：\n• 甲\n• 乙", ChatMarkdown.plain("**要点**：\n- 甲\n- 乙"));
    }

    // ------------------------------------------------------------------ 会话历史 → 列表项

    @Test public void transcriptUpsertFreezeAndItems() {
        ChatTranscript t = new ChatTranscript();
        t.upsert("今天天气怎么样", "朝阳区今天多云。", "L2_LLM · ask.reply", 1800);
        t.upsert("", "朝阳区今天多云，24~28°C。", null, 0);   // 同一轮再出结果 = 覆盖，问句保留
        assertEquals(1, t.size());
        assertEquals(0, t.items().size());   // 还没冻结：屏上那一轮由卡片原 View 显示
        t.freeze();
        t.upsert("今天股市的行情怎么样", "截至收盘…", "L2_LLM · ask.reply", 4200);
        t.setSuggestions(Arrays.asList("股市明天会涨吗？", "哪些股票值得投资？"));
        t.freeze();
        List<ChatTranscript.Item> it = t.items();
        assertEquals(ChatTranscript.Kind.USER, it.get(0).kind);
        assertEquals("今天天气怎么样", it.get(0).round.question);
        assertEquals("朝阳区今天多云，24~28°C。", it.get(1).round.answer);
        assertEquals(ChatTranscript.Kind.FOOTER, it.get(2).kind);
        assertEquals(1800, it.get(2).round.latencyMs);
        assertEquals(ChatTranscript.Kind.SUGGESTIONS, it.get(it.size() - 1).kind);
        assertEquals(7, it.size());
        assertTrue("建议只挂在最后一条", it.get(it.size() - 1).last);
    }

    @Test public void emptyRoundIsDroppedAndHistoryCapped() {
        ChatTranscript t = new ChatTranscript(3);
        t.upsert("", "", null, 0);
        assertNull(t.freeze());
        assertEquals(0, t.size());
        for (int i = 0; i < 5; i++) {
            t.upsert("问" + i, "答" + i, null, 0);
            t.freeze();
        }
        assertEquals(3, t.size());
        assertEquals("问2", t.rounds().get(0).question);
        t.clear();
        assertEquals(0, t.size());
    }

    @Test public void feedbackToggles() {
        ChatTranscript t = new ChatTranscript();
        ChatTranscript.Round r = t.upsert("q", "a", null, 0);
        assertEquals(1, t.toggleFeedback(r.seq, 1));
        assertEquals(-1, t.toggleFeedback(r.seq, -1));
        assertEquals("再点同一个 = 取消", 0, t.toggleFeedback(r.seq, -1));
    }

    @Test public void suggestionsReuseChipLabelsDedupedAndCapped() {
        List<String> s = ChatTranscript.suggestionsFrom(Arrays.asList(" 导航到 公司 ", "导航到 公司", "", null, "复制取件码", "a", "b", "c"));
        assertEquals(Arrays.asList("导航到 公司", "复制取件码", "a", "b"), s);
        assertTrue(ChatTranscript.suggestionsFrom(null).isEmpty());
    }

    // ------------------------------------------------------------------ 图片问答 / 详情

    @Test public void imagePromptAndQuestion() {
        assertTrue(ChatWindowPolicy.imagePrompt("这是什么花").endsWith("用户问题：这是什么花"));
        assertTrue(ChatWindowPolicy.imagePrompt("").contains("这张图里是什么"));
        assertEquals("看看这张图", ChatWindowPolicy.imageQuestion("  "));
        assertEquals("翻译一下", ChatWindowPolicy.imageQuestion("翻译一下"));
    }

    @Test public void detailLineShowsRouteLatencyChars() {
        assertEquals("路由：L2_LLM · ask.reply\n耗时：4.2 s\n字数：12", ChatWindowPolicy.detailLine("L2_LLM · ask.reply", 4210, 12));
        assertEquals("路由：—\n耗时：—\n字数：0", ChatWindowPolicy.detailLine(null, 0, -1));
        assertEquals("850 ms", ChatWindowPolicy.formatMs(850));
    }

    // ------------------------------------------------------------------ 挂接点（源码存在性 + 行尾）

    @Test public void hooksPresentAndLineEndingsKept() throws Exception {
        byte[] svcBytes = Files.readAllBytes(new File(root(), "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").toPath());
        String svc = new String(svcBytes, StandardCharsets.UTF_8);
        assertEquals("FloatingSheetService 保持 CRLF", count(svc, "\n"), count(svc, "\r\n"));
        assertTrue(svc.contains("ChatWindowPolicy.allowIslandMorph(mSheetView.chatActive())"));
        assertTrue(svc.contains("mSheetView.setChatServiceHost(new ChatServiceHost());"));
        assertTrue(svc.contains(": com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.imagePrompt(question);"));
        assertTrue(svc.contains("ScreenVisionEngine.getInstance(this).recognizeImage(path, prompt,"));
        assertTrue("通话 = 现有相机对话", svc.contains("onCameraModeRequested();   // 用户指定"));
        String view = new String(Files.readAllBytes(new File(root(), "app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java").toPath()), StandardCharsets.UTF_8);
        assertFalse("FloatingSheetView 保持 LF", view.contains("\r\n"));
        assertTrue(view.contains("chatBoundary(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.Trigger.WAKE)"));
        assertTrue(view.contains("if (chat) mChat.styleLive();"));
        assertTrue(view.contains("mChat.collapse(new Runnable()"));
        String mf = new String(Files.readAllBytes(new File(root(), "app/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
        int i = mf.indexOf("com.onesay.assistant.v2.ui.chat.ChatImagePickActivity");
        assertTrue(i > 0);
        assertTrue("选图跳板页不导出", mf.substring(i, mf.indexOf("/>", i)).contains("android:exported=\"false\""));
    }

    private static String sub(String s, int[] r) {
        return s.substring(r[0], r[1]);
    }

    private static int count(String s, String k) {
        int n = 0, i = 0;
        while ((i = s.indexOf(k, i)) >= 0) {
            n++;
            i += k.length();
        }
        return n;
    }

    private static File root() {
        String r = System.getProperty("bixby.root");
        return new File(r == null ? "." : r);
    }

    // ------------------------------------------------------------------ 真机反馈（ef92480）A / B

    @Test public void micButtonIsRecordToggleAndNeverCollapses() {
        com.onesay.assistant.v2.ui.wake.WakeStage.Stage L = com.onesay.assistant.v2.ui.wake.WakeStage.Stage.LISTENING;
        assertEquals("录音中、没开口 → 只停、留在窗里", ChatWindowPolicy.MicAction.STOP_STAY, ChatWindowPolicy.micTap(L, false));
        assertEquals("录音中、说了话 → 提交", ChatWindowPolicy.MicAction.FINISH, ChatWindowPolicy.micTap(L, true));
        assertEquals(ChatWindowPolicy.MicAction.START, ChatWindowPolicy.micTap(com.onesay.assistant.v2.ui.wake.WakeStage.Stage.RESULT, false));
        assertEquals("打字态点麦克风也开录", ChatWindowPolicy.MicAction.START, ChatWindowPolicy.micTap(com.onesay.assistant.v2.ui.wake.WakeStage.Stage.TEXT, false));
        assertEquals(ChatWindowPolicy.MicAction.START, ChatWindowPolicy.micTap(com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ERROR, false));
        assertEquals(ChatWindowPolicy.MicAction.NONE, ChatWindowPolicy.micTap(com.onesay.assistant.v2.ui.wake.WakeStage.Stage.THINKING, false));
        // 停录音：对话窗开着（上一轮已冻结进历史、卡片上没有结果）也绝不收起
        assertFalse(ChatWindowPolicy.dismissOnStopListening(true, false));
        assertFalse(ChatWindowPolicy.dismissOnStopListening(false, true));
        assertTrue("新唤醒卡片什么都没有：照旧收起", ChatWindowPolicy.dismissOnStopListening(false, false));
    }

    @Test public void rewakeInChatWindowStartsNewRecording() {
        assertTrue(ChatWindowPolicy.rewakeNeedsCapture(true, false));
        assertFalse("捕获窗已开：不重复开", ChatWindowPolicy.rewakeNeedsCapture(true, true));
        assertFalse("不在对话窗：照旧", ChatWindowPolicy.rewakeNeedsCapture(false, false));
        assertTrue(ChatWindowPolicy.REWAKE_CHECK_MS > 0 && ChatWindowPolicy.REWAKE_CHECK_MS <= 600);
    }

    @Test public void rewakeAndStopWiring() throws Exception {
        String svc = new String(Files.readAllBytes(new File(root(), "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(svc.contains("ChatWindowPolicy.dismissOnStopListening(chat, mSheetView.hasVisibleResult())"));
        assertTrue(svc.contains("if (mUseV2 && newWake && mSheetView.chatActive()) chatRewake();"));
        assertTrue(svc.contains("WakeService.requestContinueListening();"));
        String view = new String(Files.readAllBytes(new File(root(), "app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(view.contains("ChatWindowPolicy.micTap(mStage.stage()"));
        assertTrue(view.contains("public void chatPrepareRewake()"));
        String ws = new String(Files.readAllBytes(new File(root(), "app/src/com/onesay/assistant/v2/wake/WakeService.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(ws.contains("public static boolean captureOpen()"));
    }
}
