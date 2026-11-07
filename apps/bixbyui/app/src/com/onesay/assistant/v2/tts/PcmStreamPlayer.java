package com.onesay.assistant.v2.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ★ r2/cloud-tts（第一阶段）：16-bit 单声道 PCM 流式播放（AudioTrack MODE_STREAM）。
 *
 * <ul>
 *   <li>WebSocket 读线程只 {@link #write} 进有界队列（不阻塞，官方 FAQ：回调里不要做耗时操作），独立写线程喂 AudioTrack。</li>
 *   <li>音频属性与系统 TTS 一致：USAGE_ASSISTANT + CONTENT_TYPE_SPEECH。</li>
 *   <li>音频焦点：selfFocus=true（没有 sysux 焦点状态机时）begin 时申请 GAIN_TRANSIENT_MAY_DUCK、播完 / stop 时归还；
 *       selfFocus=false 时不碰焦点，由 WakeService 的 AssistAudioFocus 在 onAudible → fireStart 时统一申请（见 SpeechProgressGate）。</li>
 *   <li>进度：AudioTrack 初始化成功、play() 之前回调 {@link PlaybackListener#onAudible()}；线程收尾回调
 *       {@link PlaybackListener#onFinished(boolean, String)}（stopped = 被 stop()）。</li>
 *   <li>开头先垫 {@link #LEAD_SILENCE_MS} 静音：给成功应答音（ack_done.wav）让位，与系统 TTS 的 250ms 空白一致。</li>
 *   <li>{@link #stop()}：pause + flush + release，立即无声（barge-in）。</li>
 * </ul>
 * 一个实例只播一句；用完即弃。
 */
public final class PcmStreamPlayer implements CloudUtterance.Sink {

    private static final String TAG = "PcmStreamPlayer";
    static final int LEAD_SILENCE_MS = 250;
    /** 队列上限：每段约 100–200ms 音频，400 段足够一句话；满了丢最新（不阻塞读线程）。 */
    static final int QUEUE_CAP = 400;
    private static final byte[] EOS = new byte[0];

    private final Context mApp;
    private final LinkedBlockingQueue<byte[]> mQueue = new LinkedBlockingQueue<byte[]>(QUEUE_CAP);
    private volatile AudioTrack mTrack;
    private volatile boolean mStopped;
    private volatile boolean mPlaying;
    private volatile int mDropped;
    private Object mFocusReq;
    private final Runnable mOnComplete;
    private final PlaybackListener mListener;
    private final boolean mSelfFocus;
    private volatile String mErr;

    /** ★ 综合包：播放进度（任意线程回调）。 */
    public interface PlaybackListener {
        /** 即将真正出声（AudioTrack.play 之前）。 */
        void onAudible();

        /** 播放线程收尾。stopped = 被 {@link #stop()}；err = 失败原因（正常播完为 null）。 */
        void onFinished(boolean stopped, String err);
    }

    public PcmStreamPlayer(Context ctx, Runnable onComplete) {
        mApp = ctx.getApplicationContext();
        mOnComplete = onComplete;
        mListener = null;
        mSelfFocus = true;
    }

    public PcmStreamPlayer(Context ctx, PlaybackListener listener, boolean selfFocus) {
        mApp = ctx.getApplicationContext();
        mOnComplete = null;
        mListener = listener;
        mSelfFocus = selfFocus;
    }

    public boolean isPlaying() {
        return mPlaying && !mStopped;
    }

    @Override
    public void begin(final int sampleRate) {
        if (mStopped) return;
        mPlaying = true;
        com.onesay.assistant.v2.util.Threads.start("tts-pcm", new Runnable() {
            @Override
            public void run() {
                playLoop(sampleRate);
            }
        });
    }

    @Override
    public void write(byte[] pcm) {
        if (mStopped || pcm == null || pcm.length == 0) return;
        if (!mQueue.offer(pcm)) mDropped++;
    }

    @Override
    public void end() {
        if (mStopped) return;
        mQueue.offer(EOS);
    }

    @Override
    public void stop() {
        if (mStopped) return;
        mStopped = true;
        mQueue.clear();
        mQueue.offer(EOS);
        AudioTrack t = mTrack;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (Throwable ignore) {
            }
        }
    }

    private void playLoop(int sampleRate) {
        AudioTrack t = null;
        long written = 0;
        try {
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufBytes = Math.max(minBuf, sampleRate / 5 * 2);   // ≥ 200ms
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(com.onesay.assistant.v2.ui.TtsSpeaker.playbackUsage(com.onesay.assistant.v2.ui.TtsSpeaker.commRoute()))   // ★ ui-K2：相机对话走通话路由
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            t = new AudioTrack(attrs, fmt, bufBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (t.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack 初始化失败");
            mTrack = t;
            if (mStopped) return;
            if (mSelfFocus) requestFocus();
            // ★ 综合包：真正出声处 → fireStart（WakeService 先申请 duck 焦点、开打断检测，再 play；开头 250ms 静音兜底）
            if (mListener != null) {
                try {
                    mListener.onAudible();
                } catch (Throwable ignore) {
                }
            }
            t.play();
            byte[] lead = new byte[sampleRate * LEAD_SILENCE_MS / 1000 * 2];
            t.write(lead, 0, lead.length);
            while (!mStopped) {
                byte[] b = mQueue.poll(30, TimeUnit.SECONDS);
                if (b == null || b == EOS) break;
                int off = 0;
                while (off < b.length && !mStopped) {
                    int n = t.write(b, off, b.length - off);
                    if (n <= 0) throw new IllegalStateException("AudioTrack.write=" + n);
                    off += n;
                }
                written += b.length;
            }
            if (!mStopped) {
                // 等缓冲播完再停。★ r2/voice-templates 真机：先 stop() 再等播放头，Note 8 上播放头到不了终点，
                //   每句都白等满 3 s 兜底（1.6 s 的模板 4.6 s 才报播完，续听窗晚开 3 s）→ 改成边播边等播放头走到终点再 stop()。
                long frames = (lead.length + written) / 2;
                long deadline = System.currentTimeMillis() + frames * 1000L / sampleRate + 1500;
                while (!mStopped && t.getPlaybackHeadPosition() < frames && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                if (!mStopped) t.stop();
            }
        } catch (Throwable e) {
            mErr = String.valueOf(e);
            Log.w(TAG, "[TTS] PCM 播放失败: " + e);
        } finally {
            mPlaying = false;
            if (t != null) {
                try {
                    t.release();
                } catch (Throwable ignore) {
                }
            }
            mTrack = null;
            abandonFocus();
            if (mDropped > 0) Log.w(TAG, "[TTS] 播放队列满，丢了 " + mDropped + " 段");
            if (mOnComplete != null) {
                try {
                    mOnComplete.run();
                } catch (Throwable ignore) {
                }
            }
            if (mListener != null) {
                try {
                    mListener.onFinished(mStopped, mErr);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private void requestFocus() {
        try {
            AudioManager am = (AudioManager) mApp.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                AudioFocusRequest r = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .build();
                am.requestAudioFocus(r);
                mFocusReq = r;
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                mFocusReq = Boolean.TRUE;
            }
        } catch (Throwable ignore) {
        }
    }

    private void abandonFocus() {
        Object r = mFocusReq;
        mFocusReq = null;
        if (r == null) return;
        try {
            AudioManager am = (AudioManager) mApp.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26 && r instanceof AudioFocusRequest) am.abandonAudioFocusRequest((AudioFocusRequest) r);
            else am.abandonAudioFocus(null);
        } catch (Throwable ignore) {
        }
    }
}
