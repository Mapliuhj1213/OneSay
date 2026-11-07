package com.onesay.assistant.v2.r2;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.util.LogText;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * r2/fixes 第 13 项：release 包日志不打用户原文（只记长度 / 只列参数键名）；调试声纹音频落盘只在 debug 包。
 * JVM 按 release 口径编译（IS_DEBUG=false），所以 LogText 的行为就是 release 包的行为。
 */
public class R2ReleaseLogPrivacyTest {

    static final String ROOT = System.getProperty("bixby.root", ".");

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(ROOT, "app/src/com/onesay/assistant/v2/" + rel + ".java").toPath()), StandardCharsets.UTF_8);
    }

    @Test public void releaseLogTextIsLengthOnly() throws Exception {
        assertFalse(com.onesay.assistant.v2.BuildVariant.IS_DEBUG);
        String said = "给 13800138000 发短信说我晚点到";
        String u = LogText.u(said);
        assertEquals("‹" + said.length() + "字›", u);
        assertFalse(u.contains("138") || u.contains("短信"));
        assertEquals("null", LogText.u(null));
        String a = LogText.args(new JSONObject().put("to", "13800138000").put("body", "我晚点到"));
        assertTrue(a, a.contains("to=…") && a.contains("body=…"));
        assertFalse(a, a.contains("138") || a.contains("晚点"));
        String a2 = LogText.args("{\"q\":\"附近的医院\"}");
        assertEquals("{q=…}", a2);
        assertFalse(LogText.args("不是 JSON 的原文").contains("原文"));
    }

    /** 改过的文件里，Log 调用不再直接拼这些装着用户原文的变量。 */
    static final String[] FILES = {
            "wake/WakeService", "router/UnifiedRouter", "router/AgentLoop", "router/L1SkillRouter", "router/L2LLMCompiler",
            "router/SlotFiller", "ui/FloatingSheetService", "imgsave/ImageSaveController", "imgsave/ImageSaveHooks",
            "errand/ErrandHooks", "jobs/JobsHooks", "s2/S2Hooks", "search/WebSearchClient",
            "vision/screenref/ScreenRefHooks", "executor/ShellExecutor",
    };

    @Test public void noRawUserTextInLogCalls() throws Exception {
        Pattern raw = Pattern.compile("\\+\\s*(query|command|text|raw|content|askAnswer|directAnswer|intent|prompt|res\\.rawBody)\\b\\s*([+):]|$)");
        StringBuilder bad = new StringBuilder();
        for (String f : FILES) {
            String[] lines = src(f).split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].matches(".*Log\\.[divwe]\\(.*")) continue;
                Matcher m = raw.matcher(lines[i]);
                if (m.find()) bad.append(f).append(':').append(i + 1).append(' ').append(lines[i].trim()).append('\n');
            }
        }
        assertEquals("Log 里直接拼用户原文：\n" + bad, 0, bad.length());
        // 模型自述 / 工具观测走 truncateForLog，release 只记长度
        String al = src("router/AgentLoop");
        int t = al.indexOf("private static String truncateForLog(String s, int max) {");
        assertTrue(al.substring(t, t + 400).contains("if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return com.onesay.assistant.v2.util.LogText.len(s);"));
    }

    @Test public void voiceprintAudioDumpIsDebugOnly() throws Exception {
        String w = src("wake/WakeService");
        assertTrue(Pattern.compile("private void dumpVpDiagnostic\\(short\\[\\] pcm, String kw\\) \\{\\s*\\n\\s*if \\(!com\\.onesay\\.assistant\\.v2\\.BuildVariant\\.IS_DEBUG\\) return;").matcher(w).find());
        assertTrue(Pattern.compile("public void setDiagnoseVp\\(boolean on\\) \\{\\s*\\n\\s*if \\(!com\\.onesay\\.assistant\\.v2\\.BuildVariant\\.IS_DEBUG\\) return;").matcher(w).find());
        assertTrue(w.contains("if (com.onesay.assistant.v2.BuildVariant.IS_DEBUG && mDiagnoseVp) {"));
        // 只有这一处写 wav
        int n = 0, i = -1;
        while ((i = w.indexOf("writeWav16kMono(", i + 1)) >= 0) n++;
        assertEquals("定义 + 一处调用", 2, n);
    }
}
