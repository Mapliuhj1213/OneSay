package com.onesay.assistant.v2.exec;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import com.onesay.assistant.v2.tts.VoiceTemplate;

/**
 * ★ r2/fast-screen-v2：执行模式的模板短句，直接用 r2/voice-templates 的 {@link VoiceTemplate}
 * （已完成 / 出错了，看屏幕 / 已取消 / 需要你确认，音色 c 预合成 asset，离线）。
 *
 * <p>只播一次性提示，不进回答状态机、不开续听。播放期间申请 GAIN_TRANSIENT_MAY_DUCK，播完 / 出错立即归还，
 * 音乐只被压低一下。
 */
public final class ExecVoice {

    private ExecVoice() {}

    private static final String TAG = "ExecMode";

    /** 执行结果 → 模板。ok=完成；stopped=用户停止（已取消）；其余=出错。 */
    public static VoiceTemplate forResult(boolean ok, boolean stopped) {
        if (ok) return VoiceTemplate.DONE;
        return stopped ? VoiceTemplate.CANCELLED : VoiceTemplate.ERROR;
    }

    public static void play(Context ctx, final VoiceTemplate t) {
        if (t == null) return;
        final AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        final AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        final AudioFocusRequest fr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(aa).build();
        try {
            AssetFileDescriptor afd = ctx.getAssets().openFd(t.asset);
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(aa);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            int g = am == null ? -1 : am.requestAudioFocus(fr);
            Log.i(TAG, "[FOCUS] 执行模式模板「" + t.text + "」申请 GAIN_TRANSIENT_MAY_DUCK → " + (g == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "granted" : String.valueOf(g)));
            MediaPlayer.OnCompletionListener done = new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    try {
                        m.release();
                    } catch (Throwable ignored) {
                    }
                    if (am != null) am.abandonAudioFocusRequest(fr);
                    Log.i(TAG, "[FOCUS] 执行模式模板「" + t.text + "」播完，归还音频焦点");
                }
            };
            mp.setOnCompletionListener(done);
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    if (am != null) am.abandonAudioFocusRequest(fr);
                    return false;
                }
            });
            mp.prepare();
            mp.start();
            Log.i(TAG, "[EXEC] 模板短句「" + t.text + "」→ " + t.asset);
        } catch (Throwable e) {
            if (am != null) am.abandonAudioFocusRequest(fr);
            Log.w(TAG, "[EXEC] 模板短句「" + t.text + "」播放失败: " + e);
        }
    }
}
