package com.onesay.clock.ring;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;

import com.onesay.clock.App;
import com.onesay.clock.L;
import com.onesay.clock.Prefs;
import com.onesay.clock.StateBus;
import com.onesay.clock.core.Alarm;
import com.onesay.clock.core.BriefMeta;
import com.onesay.clock.core.Flags;
import com.onesay.clock.core.RingPlan;
import com.onesay.clock.core.Validity;
import com.onesay.clock.core.VibePattern;
import com.onesay.clock.core.WeatherBgm;
import com.onesay.clock.content.Weather;
import com.onesay.clock.gen.Briefer;
import com.onesay.clock.sched.Sched;

import java.io.File;
import java.util.TimeZone;

/**
 * 响铃服务：一切声音都走 USAGE_ALARM（跟闹钟音量，P0-1），由 core.RingPlan 决定放什么、出事落到哪：
 * 成品有效 → 直接播 brief.wav（PCM WAV 不用解码，起播快）；无效 → 先放铃声，同时现拼开场，拼好再播；
 * 播完 / 出错 → 铃声；铃声打不开 → 代码合成提示音。超时（默认 10 分钟没人管）自动停止，等同点停止（0.1.5 起，不再自动贪睡）。preview=true（调试拉起响铃页）全程不出声、不振动。
 */
public class RingService extends Service {
    public static final String A_RING = "ring", A_STOP = "stop", A_SNOOZE = "snooze", A_REVIB = "revib";
    public static final String DONE = "com.onesay.clock.RING_DONE";
    /** 9302 是 GenService 的前台通知，自动停止通知必须另用一个 id（否则 GenService stopForeground 会把它一起清掉）。 */
    static final int NOTI = 9301;
    public static final int NOTI_AUTO_STOP = 9303;

    private final Handler h = new Handler();
    private RingPlan.Mode mode = RingPlan.Mode.DONE;
    private MediaPlayer voice, tone, music;
    private float musicLevel = -1f;
    private String bgmAsset;
    private AudioTrack beep;
    private Vibrator vib;
    private PowerManager.WakeLock wl;
    private int id, restoreVol = -1;
    private long at;
    private boolean preview, snooze;
    private Alarm alarm;
    private static volatile boolean ringing;

    public static boolean isRinging() { return ringing; }

    public static void start(Context c, int id, long at, boolean snooze, boolean brief, boolean preview) {
        Intent i = new Intent(c, RingService.class).setAction(A_RING).putExtra("id", id).putExtra("at", at)
                .putExtra("snooze", snooze).putExtra("brief", brief).putExtra("preview", preview);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void action(Context c, String act, int minutes) {
        c.startService(new Intent(c, RingService.class).setAction(act).putExtra("min", minutes));
    }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        String a = in == null ? A_STOP : String.valueOf(in.getAction());
        if (A_RING.equals(a)) ring(in);
        else if (A_STOP.equals(a)) finishRing(false, 0);
        else if (A_SNOOZE.equals(a)) finishRing(true, in.getIntExtra("min", 0));
        else if (A_REVIB.equals(a)) { if (ringing && vib != null) vibrate(); }   // UI 触感后恢复响铃波形（只在本来就在振时）
        return START_NOT_STICKY;
    }

    private void ring(Intent in) {
        if (ringing) stopAudio();                    // 新的一次覆盖旧的（不会两路同时响）
        id = in.getIntExtra("id", 0);
        at = in.getLongExtra("at", System.currentTimeMillis());
        snooze = in.getBooleanExtra("snooze", false);
        preview = in.getBooleanExtra("preview", false);
        boolean brief = in.getBooleanExtra("brief", false);
        Prefs p = new Prefs(this);
        alarm = p.alarm(id);
        ringing = true;
        wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bc:ring");
        wl.acquire(30 * 60_000L);
        BriefMeta m = Briefer.load(this);
        Validity.Verdict v = Validity.check(m, Briefer.wav(this).length(), at, System.currentTimeMillis(), TimeZone.getDefault());
        mode = RingPlan.start(snooze, brief, v);
        Intent ui = new Intent(this, RingActivity.class).putExtra("id", id).putExtra("at", at).putExtra("mode", mode.name())
                .putExtra("preview", preview).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent full = PendingIntent.getActivity(this, 1, ui, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, App.CH_ALARM) : new Notification.Builder(this);
        b.setSmallIcon(com.onesay.clock.R.drawable.ic_stat_alarm).setContentTitle(alarm != null && alarm.label.length() > 0 ? alarm.label : "闹钟")
                .setContentText(preview ? "响铃页预览（静音）" : "点按打开").setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setOnlyAlertOnce(true).setSound(null).setVibrate(null)
                .setContentIntent(full).setVisibility(Notification.VISIBILITY_PUBLIC);   // 0.1.4：不挂 fullScreenIntent（静默渠道不弹横幅），响铃页由下面 startActivity 直接拉起
        startForeground(NOTI, b.build());
        try { startActivity(ui); } catch (Throwable t) { L.w(this, "RING 拉起页面失败 " + t); }
        L.i(this, "RING id=" + id + " snooze=" + snooze + " brief=" + brief + " verdict=" + v + " mode=" + mode + " preview=" + preview);
        StateBus.publish(this, "RINGING", mode.name(), m);
        if (preview) { h.postDelayed(autoEnd, 120_000L); return; }
        protectVolume(p);
        if (p.flag(Flags.VIBRATE) && (alarm == null || alarm.vibrate)) vibrate();
        int dbgTmo = com.onesay.clock.BuildMode.RELEASE ? 0 : p.i(Flags.DBG_TIMEOUT_SEC, 0);   // release 不认调试覆盖
        long tmo = RingPlan.timeoutMs(p.i(Flags.AUTO_SNOOZE_MIN, Flags.AUTO_SNOOZE_MIN_DEF), dbgTmo);
        h.postDelayed(timeout, tmo);
        L.i(this, "RING 超时自动停止 " + (tmo / 1000) + " 秒后" + (dbgTmo > 0 ? "（调试覆盖）" : ""));
        enter(mode);
    }

    private final Runnable timeout = new Runnable() { public void run() { ev(RingPlan.Ev.TIMEOUT); } };
    private final Runnable autoEnd = new Runnable() { public void run() { finishRing(false, 0); } };

    private void ev(RingPlan.Ev e) {
        RingPlan.Mode n = RingPlan.next(mode, e);
        L.i(this, "RING ev=" + e + " " + mode + "→" + n);
        if (n == mode) return;
        mode = n;
        enter(n);
    }

    private void enter(RingPlan.Mode m) {
        switch (m) {
            case BRIEF: {
                final File wav = Briefer.wav(this);
                if (startMusic()) {
                    // v1 编排：配乐独奏 1.5 s → 人声从 0 渐强开口，配乐同时让位到垫底
                    h.postDelayed(new Runnable() {
                        public void run() {
                            if (mode != RingPlan.Mode.BRIEF) return;
                            if (music != null) ramp(music, musicLevel, WeatherBgm.BED, WeatherBgm.DUCK_MS, true);
                            playVoice(wav, true);
                        }
                    }, WeatherBgm.LEAD_MS);
                } else {
                    playVoice(wav, false);
                }
                break;
            }
            case MUSIC:
                if (music == null) { L.w(this, "MUSIC 配乐没起来 → 铃声兜底"); h.post(new Runnable() { public void run() { ev(RingPlan.Ev.MUSIC_ERROR); } }); break; }
                ramp(music, musicLevel, WeatherBgm.FULL, 1200L, true);
                L.i(this, "MUSIC 播报完毕，继续循环同一首配乐 " + bgmAsset + "（" + WeatherBgm.label(bgmAsset) + "）");
                StateBus.publish(this, "PLAYING", "bgm:" + bgmAsset, null);
                break;
            case LIVE:
                startTone(true);
                new Thread(new Runnable() {
                    public void run() {
                        final File f = Briefer.live(RingService.this, at);
                        h.post(new Runnable() {
                            public void run() {
                                if (mode != RingPlan.Mode.LIVE) return;
                                if (f == null) { ev(RingPlan.Ev.LIVE_FAILED); return; }
                                stopTone();
                                playVoice(f, false);
                            }
                        });
                    }
                }, "bc-live").start();
                break;
            case RINGTONE:
                stopVoice();
                stopMusic();
                if (tone == null) startTone(false);
                break;
            case BEEP:
                stopVoice();
                stopMusic();
                stopTone();
                startBeep();
                break;
            case AUTO_STOP:
                finishRing(false, 0, true);                       // 0.1.5：等同用户点停止（清贪睡、Sched.dismissed 排下一次）
                break;
            default:
                break;
        }
    }

    private static AudioAttributes attrs(int content) {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(content).build();
    }

    /** 按天气路由选曲（三星天气优先的缓存天气；没有就用成品里的天气键），循环播放，走闹钟流。失败 → false。 */
    private boolean startMusic() {
        stopMusic();
        android.content.res.AssetFileDescriptor afd = null;
        try {
            Weather.Facts w = Weather.cached(this, Weather.CACHE_OK_MS);
            BriefMeta m = Briefer.load(this);
            String asset;
            if (w != null) asset = WeatherBgm.route(true, w.desc, w.key, w.temp);
            else if (m != null && m.wxKey.length() > 0) asset = WeatherBgm.route(true, "", m.wxKey, m.temp);
            else asset = WeatherBgm.route(false, null, null, Double.NaN);
            afd = getAssets().openFd(WeatherBgm.DIR + asset);
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(attrs(AudioAttributes.CONTENT_TYPE_MUSIC));
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.setLooping(true);
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer x, int wh, int e) {
                    L.w(RingService.this, "MUSIC error " + wh + "/" + e);
                    stopMusic();
                    ev(RingPlan.Ev.MUSIC_ERROR);
                    return true;
                }
            });
            mp.prepare();
            mp.setVolume(WeatherBgm.START, WeatherBgm.START);
            mp.start();
            music = mp;
            musicLevel = WeatherBgm.START;
            bgmAsset = asset;
            L.i(this, "MUSIC start " + asset + "（" + WeatherBgm.label(asset) + "）天气=" + (w != null ? w.src + " " + w.desc + " " + w.temp
                    : (m != null ? "成品 " + m.wxKey : "无（默认曲目）")) + " 开场 " + WeatherBgm.START + " →垫底 " + WeatherBgm.BED + " →播完 " + WeatherBgm.FULL);
            return true;
        } catch (Throwable t) {
            L.w(this, "MUSIC 打不开 " + t);
            stopMusic();
            return false;
        } finally {
            if (afd != null) try { afd.close(); } catch (Throwable ignored) { }
        }
    }

    private void stopMusic() {
        if (music != null) { try { music.stop(); } catch (Throwable ignored) { } music.release(); music = null; }
        musicLevel = -1f;
    }

    /** 音量斜坡（50 ms 一步）；isMusic 时同步记 musicLevel；同一路的新斜坡让旧的作废。 */
    private void ramp(final MediaPlayer mp, final float from, final float to, final long ms, final boolean isMusic) {
        if (mp == null) return;
        final int gen = isMusic ? ++musicGen : ++voiceGen;
        final long t0 = System.currentTimeMillis();
        final float a = from < 0 ? to : from;
        h.post(new Runnable() {
            public void run() {
                if ((isMusic ? music : voice) != mp || gen != (isMusic ? musicGen : voiceGen)) return;
                float k = ms <= 0 ? 1f : Math.min(1f, (System.currentTimeMillis() - t0) / (float) ms);
                float v = a + (to - a) * k;
                try { mp.setVolume(v, v); } catch (Throwable ignored) { return; }
                if (isMusic) musicLevel = v;
                if (k < 1f) h.postDelayed(this, 50);
            }
        });
    }
    private int musicGen, voiceGen;

    private void playVoice(File f, final boolean withBed) {
        stopVoice();
        try {
            final long t0 = System.currentTimeMillis();
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(attrs(AudioAttributes.CONTENT_TYPE_SPEECH));
            mp.setDataSource(f.getPath());
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer x) { ev(RingPlan.Ev.VOICE_DONE); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer x, int w, int e) { L.w(RingService.this, "VOICE error " + w + "/" + e); ev(RingPlan.Ev.VOICE_ERROR); return true; }
            });
            mp.prepare();
            if (withBed) mp.setVolume(0f, 0f);
            mp.start();
            voice = mp;
            if (withBed) {
                ramp(mp, 0f, 1f, WeatherBgm.VOICE_FADE_IN_MS, false);
                final long dur = mp.getDuration();
                if (dur > WeatherBgm.VOICE_FADE_IN_MS + 1500L) {
                    final long tail = WeatherBgm.tailMs(dur);
                    h.postDelayed(new Runnable() {
                        public void run() {
                            if (voice != mp || mode != RingPlan.Mode.BRIEF) return;
                            ramp(mp, 1f, WeatherBgm.VOICE_TAIL_FLOOR, tail, false);        // v1：人声尾段轻收
                            if (music != null) ramp(music, musicLevel, WeatherBgm.FULL, tail + 500L, true);   // 配乐同窗推上来接棒
                        }
                    }, Math.max(0L, dur - tail));
                }
            }
            L.i(this, "VOICE start " + f.getName() + " prepare=" + (System.currentTimeMillis() - t0) + "ms dur=" + mp.getDuration() + "ms");
            StateBus.publish(this, "PLAYING", f.getName(), Briefer.load(this));
        } catch (Throwable t) {
            L.w(this, "VOICE 打不开 " + t);
            h.post(new Runnable() { public void run() { ev(RingPlan.Ev.VOICE_ERROR); } });
        }
    }

    private void startTone(final boolean underLive) {
        try {
            Uri u = null;
            if (alarm != null && alarm.sound.length() > 0) u = Uri.parse(alarm.sound);
            if (u == null) u = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) throw new IllegalStateException("no alarm uri");
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(attrs(AudioAttributes.CONTENT_TYPE_SONIFICATION));
            mp.setDataSource(this, u);
            mp.setLooping(true);
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer x, int w, int e) { L.w(RingService.this, "TONE error " + w + "/" + e); ev(RingPlan.Ev.RINGTONE_ERROR); return true; }
            });
            mp.prepare();
            mp.setVolume(0.25f, 0.25f);
            mp.start();
            tone = mp;
            fadeIn(mp, underLive ? 3000 : 5000);
            L.i(this, "TONE start " + u);
        } catch (Throwable t) {
            L.w(this, "TONE 打不开 " + t);
            h.post(new Runnable() { public void run() { ev(RingPlan.Ev.RINGTONE_ERROR); } });
        }
    }

    private void fadeIn(final MediaPlayer mp, final long ms) {
        final long t0 = System.currentTimeMillis();
        h.post(new Runnable() {
            public void run() {
                if (tone != mp) return;
                float f = Math.min(1f, 0.25f + 0.75f * (System.currentTimeMillis() - t0) / (float) ms);
                try { mp.setVolume(f, f); } catch (Throwable ignored) { return; }
                if (f < 1f) h.postDelayed(this, 100);
            }
        });
    }

    /** 最后一道：代码合成的 880/660 Hz 提示音（不依赖任何文件），循环。 */
    private void startBeep() {
        try {
            int sr = 22050, n = sr;   // 1 秒一循环：两声 150 ms
            short[] s = new short[n];
            for (int i = 0; i < n; i++) {
                double t = i / (double) sr;
                boolean on = t < 0.15 || (t > 0.3 && t < 0.45);
                double f = t < 0.2 ? 880 : 660;
                s[i] = on ? (short) (0.6 * 32767 * Math.sin(2 * Math.PI * f * t)) : 0;
            }
            AudioTrack at2 = new AudioTrack(attrs(AudioAttributes.CONTENT_TYPE_SONIFICATION),
                    new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    n * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
            at2.write(s, 0, n);
            at2.setLoopPoints(0, n, -1);
            at2.play();
            beep = at2;
            L.i(this, "BEEP start");
        } catch (Throwable t) {
            L.e(this, "BEEP 失败（只剩振动）", t);
            vibrate();
        }
    }

    private void vibrate() {
        try {
            vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            long[] pat = VibePattern.choose(systemVibePattern());
            vib.vibrate(pat, VibePattern.REPEAT, attrs(AudioAttributes.CONTENT_TYPE_SONIFICATION));
            L.i(this, "VIB 波形 " + java.util.Arrays.toString(pat) + " repeat=" + VibePattern.REPEAT);
        } catch (Throwable ignored) { }
    }

    /** 系统「振动模式」：default_vibration_pattern → PersonalVibrationProvider 行的 effect id → 框架 config_vibeCommon*；读不到 → null。 */
    private long[] systemVibePattern() {
        android.database.Cursor c = null;
        try {
            String uri = android.provider.Settings.System.getString(getContentResolver(), "default_vibration_pattern");
            int id = VibePattern.BASIC_CALL_ID;
            if (uri != null && uri.startsWith("content://")) {
                c = getContentResolver().query(Uri.parse(uri), null, null, null, null);
                if (c != null && c.moveToFirst()) { int i = c.getColumnIndex("vibration_pattern"); if (i >= 0) id = c.getInt(i); }
            }
            String name = VibePattern.resName(id);
            if (name == null) return null;
            android.content.res.Resources sys = android.content.res.Resources.getSystem();
            int rid = sys.getIdentifier(name, "array", "android");
            if (rid == 0) return null;
            int[] a = sys.getIntArray(rid);
            long[] r = new long[a.length];
            for (int k = 0; k < a.length; k++) r[k] = a[k];
            L.i(this, "VIB 系统振动模式 id=" + id + " " + name);
            return r;
        } catch (Throwable t) {
            L.w(this, "VIB 读系统振动模式失败 " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
    }

    private void protectVolume(Prefs p) {
        if (!p.flag(Flags.MIN_VOL)) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM), cur = am.getStreamVolume(AudioManager.STREAM_ALARM);
        int floor = (int) Math.ceil(max * 0.3);
        if (cur < floor) {
            try { am.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0); restoreVol = cur; L.i(this, "VOL 闹钟音量 " + cur + "→" + floor + "（最低保护）"); }
            catch (Throwable ignored) { }
        }
    }

    private void stopVoice() { if (voice != null) { try { voice.stop(); } catch (Throwable ignored) { } voice.release(); voice = null; } }

    private void stopTone() { if (tone != null) { try { tone.stop(); } catch (Throwable ignored) { } tone.release(); tone = null; } }

    private void stopAudio() {
        stopVoice();
        stopTone();
        stopMusic();
        if (beep != null) { try { beep.stop(); } catch (Throwable ignored) { } beep.release(); beep = null; }
        if (vib != null) { vib.cancel(); vib = null; }
        h.removeCallbacks(timeout);
        h.removeCallbacks(autoEnd);
        if (restoreVol >= 0) {
            try { ((AudioManager) getSystemService(AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_ALARM, restoreVol, 0); } catch (Throwable ignored) { }
            restoreVol = -1;
        }
    }

    private void finishRing(boolean doSnooze, int minutes) { finishRing(doSnooze, minutes, false); }

    /** @param minutes 贪睡分钟（≤0 = 用闹钟自己的设定）；autoStop = 超时没人管自动停止（只影响日志和一条静默通知，收尾与点停止完全相同） */
    private void finishRing(boolean doSnooze, int minutes, boolean autoStop) {
        if (!ringing) { stopSelf(); return; }
        stopAudio();
        ringing = false;
        mode = RingPlan.Mode.DONE;
        if (!preview) {
            if (doSnooze && (alarm == null || alarm.snoozeOn)) Sched.snooze(this, id, minutes > 0 ? minutes : (alarm != null ? alarm.snoozeMin : 5));
            else Sched.dismissed(this, id);
        }
        L.i(this, "RING_END id=" + id + (doSnooze ? " snooze=" + minutes : autoStop ? " stop(auto-timeout)" : " stop") + (preview ? " (preview)" : ""));
        StateBus.publish(this, "DONE", doSnooze ? "snooze" : "stop", null);
        sendBroadcast(new Intent(DONE).setPackage(getPackageName()));
        if (wl != null && wl.isHeld()) wl.release();
        stopForeground(true);
        if (autoStop && !preview) notifyAutoStopped();
        stopSelf();
    }

    /** 超时停止后在下拉栏留一条静默通知（走静默渠道 bc_alarm_quiet，不响不振不弹横幅；点按清除，不跳转）。 */
    private void notifyAutoStopped() {
        try {
            int hh, mm;
            if (alarm != null) { hh = alarm.hour; mm = alarm.minute; }
            else { java.util.Calendar cal = java.util.Calendar.getInstance(); cal.setTimeInMillis(at); hh = cal.get(java.util.Calendar.HOUR_OF_DAY); mm = cal.get(java.util.Calendar.MINUTE); }
            String title = RingPlan.autoStopTitle(hh, mm);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, App.CH_ALARM) : new Notification.Builder(this);
            b.setSmallIcon(com.onesay.clock.R.drawable.ic_stat_alarm).setContentTitle(title)
                    .setContentText(alarm != null && alarm.label.length() > 0 ? alarm.label + " · 响铃没人操作" : "响铃没人操作")
                    .setAutoCancel(true).setOnlyAlertOnce(true).setSound(null).setVibrate(null).setPriority(Notification.PRIORITY_LOW)
                    .setShowWhen(true).setWhen(System.currentTimeMillis()).setVisibility(Notification.VISIBILITY_PUBLIC);
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTI_AUTO_STOP, b.build());
            L.i(this, "NOTI " + title);
        } catch (Throwable t) { L.w(this, "NOTI 自动停止通知失败 " + t); }
    }

    @Override public void onDestroy() { stopAudio(); ringing = false; super.onDestroy(); }

    @Override public IBinder onBind(Intent i) { return null; }
}
