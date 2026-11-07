package com.onesay.assistant.v2.vision;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** 识屏取消检查点（取消不上传）+ 普通入口也拦全黑 + 上云提示。 */
public class SecLogicVisionCancelTest {

    static final ScreenVisionEngine.Callback PLAIN = new ScreenVisionEngine.Callback() {
        @Override public void onProgress(String s) {}
        @Override public void onSuccess(String a) {}
        @Override public void onError(String e) {}
    };

    static ScreenVisionEngine.CancellableCallback cancellable(final boolean c) {
        return new ScreenVisionEngine.CancellableCallback() {
            @Override public boolean isCancelled() { return c; }
            @Override public void onProgress(String s) {}
            @Override public void onSuccess(String a) {}
            @Override public void onError(String e) {}
        };
    }

    @Test public void abort_when_generation_changes_or_callback_cancelled() {
        assertFalse(ScreenVisionEngine.shouldAbort(3, 3, PLAIN));
        assertTrue("面板收起 / ✕ 取消 → cancelInFlight 自增代次", ScreenVisionEngine.shouldAbort(3, 4, PLAIN));
        assertTrue(ScreenVisionEngine.shouldAbort(3, 3, cancellable(true)));
        assertFalse(ScreenVisionEngine.shouldAbort(3, 3, cancellable(false)));
        assertFalse(ScreenVisionEngine.shouldAbort(3, 3, null));
    }

    @Test public void checkpoints_sit_before_encode_and_before_post() throws Exception {
        String s = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/vision/ScreenVisionEngine.java").toPath()), StandardCharsets.UTF_8);
        int cp1 = s.indexOf("LOGIC-8 检查点①");
        int encode = s.indexOf("Base64.encodeToString(jpegBytes");
        int cp2 = s.indexOf("LOGIC-8 检查点②");
        int post = s.indexOf("com.onesay.assistant.v2.net.HttpClient.execute(");
        assertTrue(cp1 > 0 && cp1 < encode);
        assertTrue(cp2 > encode && cp2 < post);
        assertTrue("普通识屏入口开 rejectBlack", s.contains("recognizeInternal(userQuestion, null, 0, 0, null, null, true, callback);"));
        assertTrue(s.contains("UPLOAD_NOTICE"));
        String fss = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").toPath()), StandardCharsets.UTF_8);
        assertTrue("面板收起时作废在途识屏", fss.contains("ScreenVisionEngine.getInstance(this).cancelInFlight();"));
        assertTrue("全黑固定码换人话", fss.contains("ScreenVisionEngine.ERR_SECURE_BLACK.equals(errorMsg)"));
    }
}
