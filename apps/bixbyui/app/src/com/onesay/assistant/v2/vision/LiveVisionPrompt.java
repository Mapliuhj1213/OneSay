package com.onesay.assistant.v2.vision;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * ★ 11-04 相机对话：发给 qwen3-vl 的请求体与答复整形（纯函数，JVM 单测）。
 *
 * <p>上下文：系统提示 + 最近几轮的文字问答（不带旧图）+ 本轮「最新一帧 + 这句话」。图只走 data: URL（内存里的 base64），不经任何文件。
 */
public final class LiveVisionPrompt {

    private LiveVisionPrompt() { }

    /** 帧长边（像素）。 */
    public static final int TARGET_LONG_EDGE = 768;
    public static final int JPEG_QUALITY = 80;
    /** 念出来的最多字数（屏幕字幕显示全文）。 */
    public static final int SPOKEN_MAX = 300;

    public static final String SYSTEM =
            "你是手机上的实时相机助手。用户正举着手机摄像头给你看东西，并用语音和你对话；每轮附上的图片就是用户此刻镜头里的画面。"
            + "根据画面回答，像面对面聊天一样口语化、简短：一般一两句话、不超过 60 个字，先给结论；不要用 Markdown、列表、编号、表情或链接。"
            + "看不清、太暗或对不上焦就直说，并告诉用户怎么拍（靠近一点 / 开手电 / 换个角度）。"
            + "认不出就说认不出，不要编造品牌、型号、价格或数字；用户追问时结合前面的对话理解「这个」「它」指什么。";

    /** 按长边缩放到 ≤ longEdge（不放大），保持比例，至少 1px。 */
    public static int[] targetSize(int w, int h, int longEdge) {
        if (w <= 0 || h <= 0) return new int[]{longEdge, longEdge};
        int le = Math.max(w, h);
        if (le <= longEdge) return new int[]{w, h};
        double k = (double) longEdge / le;
        return new int[]{Math.max(1, (int) Math.round(w * k)), Math.max(1, (int) Math.round(h * k))};
    }

    /** OpenAI 兼容 chat/completions 请求体。history 每项 {问, 答}。 */
    public static JSONObject payload(String model, List<String[]> history, String jpegBase64, String question) throws JSONException {
        JSONObject p = new JSONObject();
        p.put("model", model);
        JSONArray msgs = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM);
        msgs.put(sys);
        if (history != null) {
            for (String[] qa : history) {
                if (qa == null || qa.length < 2 || qa[0] == null || qa[1] == null) continue;
                JSONObject u = new JSONObject();
                u.put("role", "user");
                u.put("content", qa[0]);
                msgs.put(u);
                JSONObject a = new JSONObject();
                a.put("role", "assistant");
                a.put("content", qa[1]);
                msgs.put(a);
            }
        }
        JSONObject cur = new JSONObject();
        cur.put("role", "user");
        JSONArray content = new JSONArray();
        if (jpegBase64 != null && jpegBase64.length() > 0) {
            JSONObject img = new JSONObject();
            img.put("type", "image_url");
            JSONObject url = new JSONObject();
            url.put("url", "data:image/jpeg;base64," + jpegBase64);
            img.put("image_url", url);
            content.put(img);
        }
        JSONObject txt = new JSONObject();
        txt.put("type", "text");
        txt.put("text", question == null || question.trim().length() == 0 ? "这是什么？帮我看看" : question.trim());
        content.put(txt);
        cur.put("content", content);
        msgs.put(cur);
        p.put("messages", msgs);
        p.put("max_tokens", 300);
        return p;
    }

    /** 从答复 JSON 取正文；没有 → null。 */
    public static String answerOf(String respBody) {
        try {
            JSONObject j = new JSONObject(respBody);
            JSONArray ch = j.optJSONArray("choices");
            if (ch == null || ch.length() == 0) return null;
            JSONObject m = ch.getJSONObject(0).optJSONObject("message");
            String a = m == null ? null : m.optString("content", null);
            return a == null || a.trim().length() == 0 ? null : a.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 念 / 显示用：去掉 Markdown 记号与列表符，合并空白。 */
    public static String spoken(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("(?m)^\\s*(?:[-*•]|\\d+[.)、])\\s+", "")
                .replaceAll("[*#`_>]+", "")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }
}
