package com.onesay.assistant.v2.ui.chat;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ fix/chat-vision-1124 第 3 条：聊天里的图固定在发出它的那条消息上。
 *
 * <p>根因：发图时 ChatWindowController.onSubmit 先把图放进「手上的图」mLiveThumb，然后才走到「正在解析」那一轮边界；
 * 边界冻结上一轮时 freezeLive 有一行「手上有图、上一轮没图 → 挂给上一轮」，出结果时 onFinal 又有一行「手上有图 → 挂给当前这一轮」。
 * 图于是挂到了恰好在屏上的那条（上一条 / 最新一条）消息上。现在图按轮次绑：发出时记成「等着绑」，只给之后新建的那一轮。
 */
public class ChatImageBind1124Test {

    static final Object IMG = new Object() {
        @Override public String toString() { return "IMG"; }
    };

    /** 走一遍：上一轮文字问答在屏上 → 发图（边界 → 新一轮出结果）→ 再问一句文字。 */
    @Test public void imageStaysOnItsOwnRound() {
        ChatTranscript t = new ChatTranscript();
        t.upsert("你好", "你好呀", null, 0);              // 第 1 轮（文字），还在屏上
        t.attachOutgoing(IMG);                            // 带图发出第 2 条
        assertSame("屏上显示的是等着绑的这张", IMG, t.liveImage());
        t.freeze();                                       // 「正在解析」边界：冻结第 1 轮
        assertNull("上一轮不拿新图", t.rounds().get(0).image);
        assertSame("冻结后图还在屏上等它自己那一轮", IMG, t.liveImage());
        ChatTranscript.Round r2 = t.upsert("这是什么花", "是月季", null, 0);
        assertSame("图绑在发出它的那条消息上", IMG, r2.image);
        assertNull(t.pendingImage());
        t.upsert("这是什么花", "是月季，花期 5 到 10 月", null, 0);   // 同一轮再出结果（流式终稿）
        assertSame(IMG, r2.image);
        t.freeze();                                       // 下一条文字消息的边界
        ChatTranscript.Round r3 = t.upsert("那它要怎么养", "多晒太阳", null, 0);
        assertNull("后面的消息不带图", r3.image);
        assertNull(t.liveImage());
        t.freeze();
        t.upsert("再来一句", "好", null, 0);
        t.freeze();
        List<ChatTranscript.Round> all = t.rounds();
        assertEquals(4, all.size());
        assertNull(all.get(0).image);
        assertSame(IMG, all.get(1).image);
        assertNull(all.get(2).image);
        assertNull(all.get(3).image);
        // 历史展开：图只在第 2 轮的问句旁边
        int withImage = 0;
        for (ChatTranscript.Item it : t.items()) if (it.kind == ChatTranscript.Kind.USER && it.round.image != null) withImage++;
        assertEquals(1, withImage);
    }

    /** 第一条就是图（屏上没有上一轮）。 */
    @Test public void firstMessageImage() {
        ChatTranscript t = new ChatTranscript();
        t.attachOutgoing(IMG);
        assertNull("没有可冻结的", t.freeze());
        ChatTranscript.Round r = t.upsert("看看这张图", "一只猫", null, 0);
        assertSame(IMG, r.image);
    }

    /** 会话超时清旧历史：刚发出的图照样跟着它那一轮；用户关掉对话 = 全清。 */
    @Test public void resetKeepsOutgoingCloseDropsIt() {
        ChatTranscript t = new ChatTranscript();
        t.upsert("旧的", "旧", null, 0);
        t.freeze();
        t.attachOutgoing(IMG);
        t.clearKeepOutgoing();
        assertSame(IMG, t.upsert("看看这张图", "一只猫", null, 0).image);
        t.attachOutgoing(IMG);
        t.clear();
        assertNull(t.pendingImage());
        assertNull(t.upsert("你好", "你好", null, 0).image);
    }

    /** 控制器那几行：不再把手上的图挂给当前 / 被冻结的一轮；发图时按轮次记下；发图那次没出结果又开始新请求 → 撤掉。 */
    @Test public void controllerWiring() throws Exception {
        String c = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/ui/chat/ChatWindowController.java").toPath()), StandardCharsets.UTF_8);
        assertFalse("onFinal 不再把手上的图挂给当前轮", c.contains("if (mLiveThumb != null) r.image = mLiveThumb;"));
        assertFalse("冻结时不再把手上的图挂给上一轮", c.contains("if (mLiveThumb != null && live.image == null) live.image = mLiveThumb;"));
        assertTrue(c.contains("mTranscript.attachOutgoing(mPendingThumb);"));
        assertTrue(c.contains("mTranscript.clearKeepOutgoing();"));
        int rs = c.indexOf("public void onRequestStart()");
        assertTrue(c.substring(rs, c.indexOf("private int mOutgoingStarts", rs)).contains("mTranscript.attachOutgoing(null);"));
    }
}
