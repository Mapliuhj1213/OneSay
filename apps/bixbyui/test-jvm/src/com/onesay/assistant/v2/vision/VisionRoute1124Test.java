package com.onesay.assistant.v2.vision;

import com.onesay.assistant.v2.net.LlmModels;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ fix/chat-vision-1124 第 2 条：看图（聊天发图 / 截图识屏 / 相机画面）一律走主模型原生视觉、看主模型 Key；
 * 另配的看图模型只留给视频全量抽帧，没配它的 Key 不挡看图。
 */
public class VisionRoute1124Test {

    @After public void reset() {
        LlmModels.setChat(null);
    }

    static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/" + rel).toPath()), StandardCharsets.UTF_8);
    }

    static JSONObject imageReq(String model) throws Exception {
        JSONObject req = new JSONObject();
        req.put("model", model);
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", "这张图里是什么"));
        content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:image/jpeg;base64,AAAA")));
        req.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
        return req;
    }

    @Test public void pathByPurpose() {
        assertEquals(VisionRoute.Path.MAIN_MODEL_NATIVE, VisionRoute.pathFor(VisionRoute.Purpose.IMAGE));
        assertEquals(VisionRoute.Path.MAIN_MODEL_NATIVE, VisionRoute.pathFor(VisionRoute.Purpose.SCREEN));
        assertEquals(VisionRoute.Path.MAIN_MODEL_NATIVE, VisionRoute.pathFor(VisionRoute.Purpose.CAMERA_FRAME));
        assertEquals("只有视频全量抽帧走另配的看图模型", VisionRoute.Path.VL_MODEL, VisionRoute.pathFor(VisionRoute.Purpose.VIDEO_FRAMES));
    }

    @Test public void noVlKeyDoesNotBlockImage() {
        // 只填了主模型 Key、没填看图模型 Key：聊天发图 / 识屏 / 相机都能用
        for (VisionRoute.Purpose p : new VisionRoute.Purpose[] {VisionRoute.Purpose.IMAGE, VisionRoute.Purpose.SCREEN, VisionRoute.Purpose.CAMERA_FRAME}) {
            assertTrue(p.name(), VisionRoute.usable(VisionRoute.pathFor(p), "sk-main", null));
            assertTrue(p.name(), VisionRoute.usable(VisionRoute.pathFor(p), "sk-main", "  "));
            assertFalse("主模型 Key 也没有才用不了", VisionRoute.usable(VisionRoute.pathFor(p), "", "vl-key"));
        }
        assertFalse(VisionRoute.usable(VisionRoute.Path.VL_MODEL, "sk-main", null));
        assertTrue(VisionRoute.usable(VisionRoute.Path.VL_MODEL, null, "vl-key"));
    }

    @Test public void nativeModelSwapsOnlyNonVisionDeepSeek() {
        assertEquals("deepseek-flash", VisionRoute.nativeModel("deepseek-v4-pro"));
        assertEquals("deepseek-flash", VisionRoute.nativeModel("DeepSeek-V4-Pro"));
        assertEquals("deepseek-flash", VisionRoute.nativeModel("deepseek-flash"));
        assertEquals("deepseek-v4-flash-vision-exp", VisionRoute.nativeModel("deepseek-v4-flash-vision-exp"));
        assertEquals("deepseek-flash", VisionRoute.nativeModel(null));
        assertEquals("deepseek-flash", VisionRoute.nativeModel("  "));
        assertEquals("别家模型照用户选的发", "qwen3-vl-plus", VisionRoute.nativeModel("qwen3-vl-plus"));
        assertEquals("Qwen/Qwen2.5-VL-7B-Instruct", VisionRoute.nativeModel("Qwen/Qwen2.5-VL-7B-Instruct"));
        assertFalse(VisionRoute.deepSeekSupportsVision("deepseek-v4-pro"));
        assertTrue(VisionRoute.deepSeekSupportsVision("deepseek-flash"));
    }

    @Test public void hasImageDetectsBlocks() throws Exception {
        assertTrue(VisionRoute.hasImage(imageReq("deepseek-flash")));
        JSONObject text = new JSONObject().put("model", "x")
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "你好")));
        assertFalse(VisionRoute.hasImage(text));
        assertFalse(VisionRoute.hasImage(null));
        assertFalse(VisionRoute.hasImage(new JSONObject()));
    }

    @Test public void prepareSwapsModelOnlyWithImage() throws Exception {
        assertEquals("deepseek-v4-pro", LlmModels.setChat("deepseek-v4-pro"));
        // 带图：主模型是 v4-pro（不能看图）→ 这一次换 deepseek-flash，并照旧关思考
        JSONObject r = LlmModels.prepare(imageReq(LlmModels.DEEPSEEK_DEFAULT));
        assertEquals("deepseek-flash", r.getString("model"));
        assertEquals("disabled", r.getJSONObject("thinking").getString("type"));
        // 纯文字：照旧用主模型
        JSONObject t = new JSONObject().put("model", LlmModels.DEEPSEEK_DEFAULT)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "你好")));
        assertEquals("deepseek-v4-pro", LlmModels.prepare(t).getString("model"));
        // 字符串版同口径（LlmClient 发送前走这里）
        assertEquals("deepseek-flash", new JSONObject(LlmModels.prepareJson(imageReq(LlmModels.DEEPSEEK_DEFAULT).toString())).getString("model"));
        // 主模型本身能看图 → 原样
        LlmModels.setChat("deepseek-flash");
        assertEquals("deepseek-flash", LlmModels.prepare(imageReq(LlmModels.DEEPSEEK_DEFAULT)).getString("model"));
        // 别家模型（主模型地址改成别家）→ 照用户选的发
        LlmModels.setChat("qwen3-vl-plus");
        assertEquals("qwen3-vl-plus", LlmModels.prepare(imageReq(LlmModels.DEEPSEEK_DEFAULT)).getString("model"));
    }

    /** 聊天发图 / 识屏 / 相机 / 自动操作看图那几处都只看主模型 Key，不再看另配的看图（语音同一把）Key。 */
    @Test public void imagePathsReadMainKeyNotVlKey() throws Exception {
        String avail = read("vision/VisionAvailability.java");
        assertTrue(avail.contains("L2LLMCompiler.mainApiKey(ctx)"));
        assertFalse(avail.contains("getDashScopeKey"));
        String eng = read("vision/ScreenVisionEngine.java");
        assertFalse("识屏 / 看图不再拿看图模型 Key", eng.contains("getDashScopeKey"));
        assertTrue(eng.contains("preflightError(mainKey())"));
        assertFalse("看图不再直连看图模型地址", eng.contains("HttpClient.execute("));
        String live = read("vision/LiveVisionClient.java");
        assertFalse(live.contains("getDashScopeKey"));
        assertTrue(live.contains("L2LLMCompiler.mainApiKey(ctx)"));
        String fast = read("phone/FastScreenDriver.java");
        int d = fast.indexOf("@Override public String describeScreen()");
        assertTrue(d > 0);
        String body = fast.substring(d, fast.indexOf("recognizeScreenSync", d));
        assertFalse("自动操作识屏前不再看看图模型 Key", body.contains("getDashScopeKey"));
        assertTrue(body.contains("VisionAvailability.usable(mApp)"));
        // 聊天发图入口没有另外的看图 Key 闸门
        String fss = read("ui/FloatingSheetService.java");
        int a = fss.indexOf("private void chatAskWithImage(final String question, final String path, final boolean screen)");
        assertTrue(a > 0);
        String ask = fss.substring(a, fss.indexOf("private static void deleteQuietly", a));
        assertFalse(ask.contains("getDashScopeKey"));
        assertFalse(ask.contains("VisionAvailability"));
    }

    @Test public void userTextsPlain() {
        List<String> all = new ArrayList<String>();
        all.add(VisionAvailability.NO_KEY_TEXT);
        all.add(VisionAvailability.NO_KEY_USER);
        all.add(LiveVisionClient.NO_KEY);
        for (String s : all) {
            assertTrue(s, s.contains("主模型"));
            for (String bad : new String[] {"DashScope", "百炼", "阿里", "DeepSeek", "deepseek", "qwen", "VL", "啥", "咋", "呗"}) {
                assertFalse(s + " 含 " + bad, s.contains(bad));
            }
        }
    }
}
