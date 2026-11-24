package com.onesay.assistant.v2.vision;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * ★ fix/chat-vision-1124：看图走哪条路（纯 Java，JVM 可测）。
 *
 * <h3>用户定的规矩（2026-11-07 定，2026-11-24 第四次重申）</h3>
 * 聊天里的图片、截图识屏、识图、相机对话的画面 —— 一律交给<b>主模型自己看</b>（原生视觉），用主模型那把 Key；
 * 另配的看图模型（VL）只在「视频全量抽帧」时才用；没配 VL 的 Key 绝不能挡住看图。
 *
 * <h3>官方依据（2026-11-24 核对）</h3>
 * <ul>
 *   <li>https://api-docs.deepseek.com/guides/vision ：「The deepseek-flash model accepts images alongside text」；
 *       走 OpenAI 兼容 Chat Completions，{@code content} 是块数组，图片块 {@code {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}}；
 *       支持 JPEG / PNG / GIF / WebP；旧名 {@code deepseek-v4-flash-vision-exp} 仍收、由最新 Flash 处理。</li>
 *   <li>https://api-docs.deepseek.com/quick_start/pricing ：Vision 一行 deepseek-flash「✓」，deepseek-v4-pro「Not supported」。</li>
 * </ul>
 * 所以：主模型是 DeepSeek 官方地址时，主模型本身能看图（deepseek-flash 系）就用它；主模型选的是不能看图的 deepseek-v4-pro，
 * 带图这一次换成同一地址、同一把 Key 的 deepseek-flash。主模型改成了别家地址（用户自己选的），就照用户选的模型发。
 */
public final class VisionRoute {

    private VisionRoute() {}

    /** 官方文档里能收图的模型名（DeepSeek 官方地址）。 */
    public static final String DEEPSEEK_VISION_MODEL = "deepseek-flash";

    /** 用途：普通图片 / 截图 / 识屏 / 相机画面 = 主模型原生视觉；只有视频全量抽帧走另配的看图模型。 */
    public enum Purpose { IMAGE, SCREEN, CAMERA_FRAME, VIDEO_FRAMES }

    /** 走哪条路。 */
    public enum Path { MAIN_MODEL_NATIVE, VL_MODEL }

    public static Path pathFor(Purpose p) {
        return p == Purpose.VIDEO_FRAMES ? Path.VL_MODEL : Path.MAIN_MODEL_NATIVE;
    }

    /** DeepSeek 官方模型名里能收图的（deepseek-flash 及其旧名；v4-pro 不行）。 */
    public static boolean deepSeekSupportsVision(String model) {
        if (model == null) return false;
        String m = model.trim().toLowerCase(Locale.ROOT);
        return m.equals("deepseek-flash") || m.equals("deepseek-v4-flash") || m.equals("deepseek-v4-flash-vision-exp")
                || m.startsWith("deepseek-v4.1-flash");
    }

    /** 是 DeepSeek 自家的模型名（deepseek-…）。 */
    public static boolean isDeepSeekModel(String model) {
        return model != null && model.trim().toLowerCase(Locale.ROOT).startsWith("deepseek");
    }

    /**
     * 带图请求实际用的模型名。
     *
     * @param chatModel 当前主对话模型
     * @return DeepSeek 系模型不能看图 → {@link #DEEPSEEK_VISION_MODEL}；其余原样（别家地址照用户选的发）
     */
    public static String nativeModel(String chatModel) {
        String m = chatModel == null ? "" : chatModel.trim();
        if (m.length() == 0) return DEEPSEEK_VISION_MODEL;
        if (isDeepSeekModel(m) && !deepSeekSupportsVision(m)) return DEEPSEEK_VISION_MODEL;
        return m;
    }

    /** 请求体里有没有图片块（OpenAI 格式 image_url，或 file 块）。 */
    public static boolean hasImage(JSONObject req) {
        if (req == null) return false;
        JSONArray msgs = req.optJSONArray("messages");
        if (msgs == null) return false;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            JSONArray c = m.optJSONArray("content");
            if (c == null) continue;
            for (int j = 0; j < c.length(); j++) {
                JSONObject part = c.optJSONObject(j);
                if (part == null) continue;
                String t = part.optString("type", "");
                if ("image_url".equals(t) || "input_image".equals(t)) return true;
            }
        }
        return false;
    }

    /** 看图需要的 Key：主模型原生视觉只看主模型 Key；VL 只看 VL Key。 */
    public static boolean usable(Path path, String mainKey, String vlKey) {
        return path == Path.VL_MODEL ? !VisionAvailability.keyMissing(vlKey) : !VisionAvailability.keyMissing(mainKey);
    }
}
