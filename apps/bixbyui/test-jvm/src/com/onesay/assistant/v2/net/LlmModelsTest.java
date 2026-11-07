package com.onesay.assistant.v2.net;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ int/1102 模型迁移：deepseek-chat（2026-07-24 停用）→ deepseek-flash，显式关思考；模型名可配；源码里不再有散落的 "deepseek-chat"。
 */
public class LlmModelsTest {

    @After public void reset() {
        LlmModels.setChat(null);
    }

    @Test public void defaultIsFlashWithThinkingOff() throws Exception {
        assertEquals("deepseek-flash", LlmModels.DEEPSEEK_DEFAULT);
        assertEquals("deepseek-flash", LlmModels.chat());
        JSONObject b = LlmClient.body(LlmModels.DEEPSEEK_DEFAULT, 0.0, 40, LlmClient.messages("s", "u"), null, false);
        assertEquals("deepseek-flash", b.getString("model"));
        assertEquals("disabled", b.getJSONObject("thinking").getString("type"));
        // 旧名传进来也换掉（老代码 / 老配置兼容）
        JSONObject legacy = LlmClient.body("deepseek-chat", 0.0, 40, LlmClient.messages("s", "u"), null, false);
        assertEquals("deepseek-flash", legacy.getString("model"));
        // 非 DeepSeek 模型原样、不加 thinking
        JSONObject q = LlmClient.body("qwen3-vl-plus", 0.0, 40, LlmClient.messages("s", "u"), null, false);
        assertEquals("qwen3-vl-plus", q.getString("model"));
        assertFalse(q.has("thinking"));
        // 已显式给了 thinking 的不动
        JSONObject on = new JSONObject().put("model", "deepseek-flash").put("thinking", new JSONObject().put("type", "enabled"));
        assertEquals("enabled", LlmModels.prepare(on).getJSONObject("thinking").getString("type"));
    }

    @Test public void configurable() throws Exception {
        assertEquals("deepseek-v4-pro", LlmModels.setChat("deepseek-v4-pro"));
        JSONObject b = LlmClient.body(LlmModels.DEEPSEEK_DEFAULT, 0.0, 40, LlmClient.messages("s", "u"), null, false);
        assertEquals("deepseek-v4-pro", b.getString("model"));
        assertEquals("disabled", b.getJSONObject("thinking").getString("type"));
        assertEquals("旧名回缺省", "deepseek-flash", LlmModels.setChat("deepseek-chat"));
        assertEquals("非法回缺省", "deepseek-flash", LlmModels.setChat("bad model;rm"));
        assertEquals("空回缺省", "deepseek-flash", LlmModels.setChat(""));
    }

    @Test public void prepareJsonRewritesWireBody() throws Exception {
        String wire = "{\"model\":\"deepseek-chat\",\"messages\":[]}";
        JSONObject o = new JSONObject(LlmModels.prepareJson(wire));
        assertEquals("deepseek-flash", o.getString("model"));
        assertEquals("disabled", o.getJSONObject("thinking").getString("type"));
        String qwen = "{\"model\":\"qwen3-asr-flash\",\"input\":{}}";
        assertSame("与 DeepSeek 无关的请求体逐字不变", qwen, LlmModels.prepareJson(qwen));
        assertEquals("not json", LlmModels.prepareJson("not json"));
    }

    @Test public void windows() {
        assertEquals(1048576, com.onesay.assistant.v2.router.ctx.ModelWindows.window(LlmModels.chat()));
        assertTrue(com.onesay.assistant.v2.router.ctx.ModelWindows.verified(LlmModels.chat()));
        assertEquals("百炼模型页核实：262,144", 262144, com.onesay.assistant.v2.router.ctx.ModelWindows.window("qwen3-vl-plus"));
        assertTrue(com.onesay.assistant.v2.router.ctx.ModelWindows.verified("qwen3-vl-plus"));
        assertEquals("未核实的保留 32k", 32768, com.onesay.assistant.v2.router.ctx.ModelWindows.window("qwen-plus"));
    }

    /** 源码里不再散落 "deepseek-chat" 字面量（只允许 LlmModels 的旧名常量与 ModelWindows 兼容表）。 */
    @Test public void noScatteredLegacyLiteral() throws Exception {
        List<String> bad = new ArrayList<String>();
        scan(new File(System.getProperty("bixby.root", "."), "app/src"), bad);
        assertTrue("仍有散落的 \"deepseek-chat\"：" + bad, bad.isEmpty());
    }

    private static void scan(File f, List<String> bad) throws Exception {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) scan(k, bad);
            return;
        }
        if (!f.getName().endsWith(".java")) return;
        if (f.getName().equals("LlmModels.java") || f.getName().equals("ModelWindows.java")) return;
        String s = new String(Files.readAllBytes(f.toPath()), "UTF-8");
        if (s.contains("\"deepseek-chat\"")) bad.add(f.getName());
    }
}
