package com.onesay.assistant.v2.assist;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

/**
 * ★ r2/sysux P2-1：默认助理必须声明的 RecognitionService（AOSP O 缺它整个助理无效）。
 * 最小实现：第三方 App 通过系统默认识别器调用时回 ERROR_RECOGNIZER_BUSY（我们的麦克风常听，不对外提供识别）。
 * 以后要做「系统语音输入」再接 AsrRouter。
 */
public class OneSayRecognitionService extends RecognitionService {
    private static final String TAG = "BixbyVIS";

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        Log.i(TAG, "[RS] 第三方请求系统语音识别 → 暂不提供（ERROR_RECOGNIZER_BUSY）");
        try { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); } catch (RemoteException ignore) {}
    }

    @Override
    protected void onCancel(Callback listener) {}

    @Override
    protected void onStopListening(Callback listener) {}
}
