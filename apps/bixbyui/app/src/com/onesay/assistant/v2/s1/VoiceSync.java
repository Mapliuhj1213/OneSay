package com.onesay.assistant.v2.s1;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.onesay.assistant.v2.tts.VoiceSyncPolicy;
import com.onesay.assistant.v2.tts.VoiceSyncSession;
import com.onesay.assistant.v2.tts.VoiceTemplate;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.TtsSpeaker;

/**
 * ★ arch/voice-sync：{@link VoiceSyncSession} 的 Android 接线（只经 {@link S1Hooks} 调进来，AgentLoop 内部不动）。
 * <ul>
 *   <li>出声：{@link TtsSpeaker#playCue}（一次性提示，不进回答状态机；stopAll / 结果模板 / 新提示都会停掉它）；</li>
 *   <li>静音条件：设置（{@link TtsSpeaker#replyWillSpeak}）/ 收音窗开着 / 通话中 / 媒体在播；</li>
 *   <li>字幕：岛（{@link IslandController#caption}，不算一步）+ 面板状态行（{@link FloatingSheetService#voiceCaption}）；</li>
 *   <li>定时：主线程 Handler。</li>
 * </ul>
 * 同一时间只有一个前台任务会话；新任务开始时旧会话 end。
 */
public final class VoiceSync {

    private static final String TAG = "VoiceSync";

    private VoiceSync() {}

    private static volatile VoiceSyncSession sCur;
    /** 懒建（JVM 单测里 S1Hooks 会走到这里但不会真的用到 Looper）。 */
    private static Handler sMainH;

    private static synchronized Handler main() {
        if (sMainH == null) sMainH = new Handler(Looper.getMainLooper());
        return sMainH;
    }
    /** 连续没听清计数（新唤醒没听清 → 第 1/2/≥3 次换句）。 */
    private static int sMissCount;
    private static long sMissAt;

    public static VoiceSyncSession current() {
        return sCur;
    }

    /** 任务开始（主线程，S1Hooks.onTaskStart）。 */
    static void taskStart(final FloatingSheetService svc, String command, boolean islandNow) {
        VoiceSyncSession old = sCur;
        if (old != null) old.end();
        final Context app = svc.getApplicationContext();
        VoiceSyncSession s = new VoiceSyncSession(new VoiceSyncSession.Out() {
            @Override public boolean playCue(VoiceTemplate t, Runnable done) {
                return TtsSpeaker.get(app).playCue(t, done);
            }

            @Override public String silenceReason() {
                return VoiceSync.silenceReason(app);
            }

            @Override public void caption(final String text) {
                IslandController isl = S1Hooks.island();
                if (isl != null) isl.caption(text);
                main().post(new Runnable() {
                    @Override public void run() {
                        FloatingSheetService fs = FloatingSheetService.getInstance();
                        if (fs != null) fs.voiceCaption(text);
                    }
                });
            }

            @Override public void log(String msg) {
                Log.i(TAG, msg);
            }
        }, new VoiceSyncSession.Scheduler() {
            @Override public Object schedule(Runnable r, long delayMs) {
                main().postDelayed(r, delayMs);
                return r;
            }

            @Override public void cancel(Object token) {
                if (token instanceof Runnable) main().removeCallbacks((Runnable) token);
            }
        });
        sCur = s;
        int est = islandNow ? VoiceSyncPolicy.estimateSteps(command) : 1;
        Log.i(TAG, "[VOICE-SYNC] 任务开始 islandNow=" + islandNow + " 估计 " + est + " 步");
        s.start(islandNow, est);
    }

    /** 当前回合的静音原因（null = 可以出声）。 */
    public static String silenceReason(Context ctx) {
        boolean setting = TtsSpeaker.replyWillSpeak(ctx);
        boolean speaking = com.onesay.assistant.v2.wake.WakeService.isCaptureOpen();
        boolean inCall = false, media = false;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int m = am.getMode();
                inCall = m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE;
                media = am.isMusicActive();
            }
        } catch (Throwable ignored) {
        }
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) inCall = true;
        } catch (Throwable ignored) {
        }
        return VoiceSyncPolicy.silenceReason(setting, speaking, inCall, media);
    }

    /** 任务结束（主线程，S1Hooks.onTaskEnded）。 */
    static void taskEnded() {
        VoiceSyncSession s = sCur;
        if (s != null) s.end();
    }

    /** 新唤醒没听清：第几次（60 s 内连续累加）。主线程。 */
    public static VoiceTemplate nextMiss() {
        long now = System.currentTimeMillis();
        sMissCount = VoiceSyncPolicy.nextMissCount(sMissCount, sMissAt, now);
        sMissAt = now;
        return VoiceTemplate.forMiss(sMissCount);
    }

    /** 拿到一条指令：没听清计数清零。 */
    public static void commandReceived() {
        sMissCount = 0;
        sMissAt = 0;
    }
}
