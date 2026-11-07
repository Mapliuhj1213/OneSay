package com.onesay.assistant.v2.vision;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** ★ 11-04 相机对话：qwen3-vl 请求体（系统提示 + 历史文字 + 本轮帧与问题）、帧尺寸、答复整形。 */
public class LiveVisionPromptTest {

    @Test public void targetSizeLongEdge768KeepsAspect() {
        assertArrayEquals(new int[]{576, 768}, LiveVisionPrompt.targetSize(1080, 1440, 768));
        assertArrayEquals(new int[]{768, 432}, LiveVisionPrompt.targetSize(1920, 1080, 768));
        assertArrayEquals("小图不放大", new int[]{640, 480}, LiveVisionPrompt.targetSize(640, 480, 768));
        assertArrayEquals(new int[]{768, 768}, LiveVisionPrompt.targetSize(0, 0, 768));
    }

    @Test public void payloadHasSystemHistoryAndCurrentImageLast() throws Exception {
        List<String[]> h = new ArrayList<String[]>();
        h.add(new String[]{"这是什么", "一只马克杯。"});
        JSONObject p = LiveVisionPrompt.payload("qwen3-vl-plus", h, "QUJD", "它能进微波炉吗");
        assertEquals("qwen3-vl-plus", p.getString("model"));
        JSONArray m = p.getJSONArray("messages");
        assertEquals(4, m.length());
        assertEquals("system", m.getJSONObject(0).getString("role"));
        assertEquals("这是什么", m.getJSONObject(1).getString("content"));
        assertEquals("assistant", m.getJSONObject(2).getString("role"));
        JSONArray cur = m.getJSONObject(3).getJSONArray("content");
        assertEquals("image_url", cur.getJSONObject(0).getString("type"));
        assertEquals("data:image/jpeg;base64,QUJD", cur.getJSONObject(0).getJSONObject("image_url").getString("url"));
        assertEquals("它能进微波炉吗", cur.getJSONObject(1).getString("text"));
        assertTrue("只有本轮带图", m.getJSONObject(1).get("content") instanceof String);
    }

    @Test public void payloadWithoutFrameOrQuestion() throws Exception {
        JSONObject p = LiveVisionPrompt.payload("m", null, null, "  ");
        JSONArray cur = p.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertEquals(1, cur.length());
        assertEquals("这是什么？帮我看看", cur.getJSONObject(0).getString("text"));
    }

    @Test public void answerParsingAndSpokenCleanup() {
        assertEquals("一只杯子", LiveVisionPrompt.answerOf("{\"choices\":[{\"message\":{\"content\":\" 一只杯子 \"}}]}"));
        assertNull(LiveVisionPrompt.answerOf("{\"choices\":[]}"));
        assertNull(LiveVisionPrompt.answerOf("not json"));
        assertEquals("这是**马克杯**".replace("**", ""), LiveVisionPrompt.spoken("这是**马克杯**"));
        assertEquals("红色 蓝色", LiveVisionPrompt.spoken("- 红色\n- 蓝色"));
        assertEquals("标题", LiveVisionPrompt.spoken("## 标题"));
    }

    @Test public void systemPromptAsksForShortSpokenHonestAnswers() {
        assertTrue(LiveVisionPrompt.SYSTEM.contains("不超过 60 个字"));
        assertTrue(LiveVisionPrompt.SYSTEM.contains("不要编造"));
        assertTrue(LiveVisionPrompt.SYSTEM.contains("不要用 Markdown"));
    }
}
