package com.onesay.assistant.v2.notice;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 灵动岛智能化的 Android 胶水：五个新来源都经 {@link NoticeHooks#post} 进同一个 {@link NoticeCenter}
 * （去重 / 每日上限 / 免打扰 / 全屏 / 面板让位规则全部照旧，没有旁路）。
 * <ul>
 *   <li>PRIVACY：录音回调 + 相机可用性回调（事件驱动，不轮询）→ 去抖 1.5 s → root 读一次 dumpsys 归属包名 → 过滤自己；</li>
 *   <li>CALL：PhoneStateListener → {@link CallNotices.Machine}；动作走 TelecomManager（Android 9：endCall / acceptRingingCall），
 *       没权限或低版本退 root {@code input keyevent}；静音 / 免提走 AudioManager；</li>
 *   <li>NOTIFY / ERRAND：EventNotificationListener 转来 → {@link NotifyClassifier}；</li>
 *   <li>SYSTEM：BATTERY_CHANGED / HEADSET_PLUG / A2DP 连接广播 → {@link SystemStatusNotices}。</li>
 * </ul>
 * 日志只记类型 / 包名 / 去重键，不记通知正文、号码、验证码。
 */
public final class IslandSmartHooks {

    private IslandSmartHooks() {}

    static final String TAG = "IslandSmart";

    private static volatile boolean sStarted;
    private static Handler sH;
    private static Context sApp;

    // ---- PRIVACY 状态 ----
    private static final PrivacyNotices.SessionCache SESSIONS = new PrivacyNotices.SessionCache();
    private static final Map<String, Long> MIC_ON = new HashMap<String, Long>();   // 包 → 开始时刻（已上岛）
    private static final Map<String, Long> CAM_ON = new HashMap<String, Long>();
    private static final Set<String> CAM_IDS_BUSY = new LinkedHashSet<String>();
    /** 调试注入（只 debug 包的 NoticeTestDriver 设）：假装这些包在用麦克风 / 相机（不走系统回调和 root）。 */
    public static final Set<String> sFakeMic = new LinkedHashSet<String>(), sFakeCam = new LinkedHashSet<String>();
    private static volatile String sPrivacyNote = "";

    // ---- CALL 状态 ----
    private static final CallNotices.Machine CALL = new CallNotices.Machine();
    private static volatile boolean sMuted, sSpeaker;

    // ---- SYSTEM 状态 ----
    private static final SystemStatusNotices.Battery BATTERY = new SystemStatusNotices.Battery();

    // ---- NOTIFY：通知 key 哈希 → 岛上那条的去重键（通知被移除时撤） ----
    private static final Map<String, String> NL_KEYS = new HashMap<String, String>();

    // ================================================================== 启动

    /** 面板服务 onCreate（NoticeHooks.onServiceCreate 里调）：登记回调 / 广播。重复调用无副作用。 */
    public static synchronized void start(Context ctx) {
        if (sStarted) return;
        sStarted = true;
        sApp = ctx.getApplicationContext();
        HandlerThread ht = new HandlerThread("island-smart");
        ht.start();
        sH = new Handler(ht.getLooper());
        NoticeHooks.register(SOURCE);
        registerPrivacy();
        registerCall();
        registerSystem();
        Log.i(TAG, "[SMART] 已登记：麦克风 / 相机回调、电话状态、电池 / 耳机广播；通知筛选经 EventNotificationListener（需通知使用权="
                + listenerEnabled(sApp) + "）");
    }

    static boolean on(String key) {
        Context app = sApp;
        return app != null && NoticeSettings.master(app) && NoticeSettings.flag(app, key);
    }

    /** 系统设置里打开了我们的通知使用权没有（控制台提示用）。 */
    public static boolean listenerEnabled(Context ctx) {
        try {
            String s = android.provider.Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(ctx.getPackageName() + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================== 来源（动作回调）

    /** 只用来接 CUSTOM / INLINE 动作（通话），不定时 poll。 */
    static final NoticeSource SOURCE = new NoticeSource() {
        @Override public String id() { return CallNotices.TAG; }
        @Override public IslandNotice.Source kind() { return IslandNotice.Source.CALL; }
        @Override public String switchKey() { return null; }
        @Override public long nextPollAt(long now) { return -1L; }
        @Override public List<IslandNotice> poll(long now) { return null; }
        @Override public boolean onAction(IslandNotice n, NoticeAction a) {
            return callAction(a == null ? "" : a.arg);
        }
    };

    // ================================================================== PRIVACY

    private static void registerPrivacy() {
        final Context app = sApp;
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            am.registerAudioRecordingCallback(new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    final List<Integer> ids = new ArrayList<Integer>();
                    if (configs != null) for (AudioRecordingConfiguration c : configs) ids.add(c.getClientAudioSessionId());
                    schedulePrivacy("mic", ids);
                }
            }, sH);
        } catch (Throwable t) {
            Log.w(TAG, "[SMART] 录音回调登记失败: " + t.getClass().getSimpleName());
        }
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            cm.registerAvailabilityCallback(new android.hardware.camera2.CameraManager.AvailabilityCallback() {
                @Override public void onCameraAvailable(String id) {
                    synchronized (CAM_IDS_BUSY) { CAM_IDS_BUSY.remove(id); }
                    schedulePrivacy("cam", null);
                }

                @Override public void onCameraUnavailable(String id) {
                    synchronized (CAM_IDS_BUSY) { CAM_IDS_BUSY.add(id); }
                    schedulePrivacy("cam", null);
                }
            }, sH);
        } catch (Throwable t) {
            Log.w(TAG, "[SMART] 相机回调登记失败: " + t.getClass().getSimpleName());
        }
    }

    private static volatile List<Integer> sMicSessions = new ArrayList<Integer>();
    private static final Runnable MIC_EVAL = new Runnable() { @Override public void run() { evalMic(); } };
    private static final Runnable CAM_EVAL = new Runnable() { @Override public void run() { evalCam(); } };

    /** 回调来了：去抖 1.5 s 再判断（App 一闪而过的探测、相机预热不打扰）。 */
    static void schedulePrivacy(String which, List<Integer> sessions) {
        if (sH == null) return;
        if ("mic".equals(which)) {
            if (sessions != null) sMicSessions = sessions;
            sH.removeCallbacks(MIC_EVAL);
            sH.postDelayed(MIC_EVAL, PrivacyNotices.DEBOUNCE_MS);
        } else {
            sH.removeCallbacks(CAM_EVAL);
            sH.postDelayed(CAM_EVAL, PrivacyNotices.DEBOUNCE_MS);
        }
    }

    /** 当前录音的包（去掉自己）。没见过的会话才 dumpsys；root 不可用 → 认不出 → 当作没有（不显示，见类注释）。 */
    static Set<String> micPackages() {
        List<Integer> ids = sMicSessions;
        if (!ids.isEmpty() && SESSIONS.needsLookup(ids)) {
            String out = rootRead("dumpsys audio | grep -E 'RecordActivityMonitor|session:[0-9]+ -- source' | head -n 40");
            if (out != null) SESSIONS.learn(PrivacyNotices.parseAudio(out));
            if (SESSIONS.hasUnknown(ids)) SESSIONS.learn(policySessions());   // ★ integrate：三星 Android 9 没有 RecordActivityMonitor 段
            if (SESSIONS.hasUnknown(ids)) sPrivacyNote = "有录音会话认不出归属（root 不可用或 dumpsys 格式不认识），不显示";
        }
        Set<String> s = PrivacyNotices.others(SESSIONS.packagesOf(ids));
        synchronized (sFakeMic) { s.addAll(sFakeMic); }
        return s;
    }

    static Set<String> camPackages() {
        Set<String> s = new LinkedHashSet<String>();
        boolean busy;
        synchronized (CAM_IDS_BUSY) { busy = !CAM_IDS_BUSY.isEmpty(); }
        if (busy) {
            String out = rootRead("dumpsys media.camera | grep -E 'Active Camera Clients|Client [Pp]ackage|^\\]' | head -n 20");
            if (out == null) sPrivacyNote = "相机被占用但认不出是谁（root 不可用），不显示";
            else s.addAll(PrivacyNotices.others(PrivacyNotices.parseCamera(out)));
        }
        synchronized (sFakeCam) { s.addAll(sFakeCam); }
        return s;
    }

    static void evalMic() {
        evalPrivacy(PrivacyNotices.Kind.MIC, MIC_ON, on(NoticeSettings.K_SRC_PRIVACY) ? micPackages() : new LinkedHashSet<String>());
    }

    static void evalCam() {
        evalPrivacy(PrivacyNotices.Kind.CAM, CAM_ON, on(NoticeSettings.K_SRC_PRIVACY) ? camPackages() : new LinkedHashSet<String>());
    }

    static void evalPrivacy(PrivacyNotices.Kind k, Map<String, Long> live, Set<String> now) {
        Context app = sApp;
        if (app == null) return;
        PrivacyNotices.Diff d;
        synchronized (live) { d = PrivacyNotices.diff(new LinkedHashSet<String>(live.keySet()), now); }
        if (d.empty()) return;
        long t = System.currentTimeMillis();
        for (String p : d.stopped) {
            Long since;
            synchronized (live) { since = live.remove(p); }
            if (since != null) retract(app, PrivacyNotices.keyOf(k, p, since));
            Log.i(TAG, "[SMART] " + k + " 停止使用 " + p);
        }
        for (String p : d.started) {
            synchronized (live) { live.put(p, t); }
            NoticeHooks.post(app, PrivacyNotices.notice(k, p, labelOf(app, p), t));
            Log.i(TAG, "[SMART] " + k + " 开始使用 " + p);
        }
    }

    /** 读 root 一次（只读命令，走 executePoll：通道忙就跳过，1.5 s 上限）；BUSY 时 2 s 后再试一次。失败 → null。 */
    static String rootRead(String cmd) {
        try {
            com.onesay.assistant.v2.executor.ShellExecutor sh = new com.onesay.assistant.v2.executor.ShellExecutor(sApp);
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r = sh.executePoll(cmd);
            if (r != null && r.isBusy) {
                try { Thread.sleep(2000L); } catch (InterruptedException ignored) { }
                r = sh.executePoll(cmd);
            }
            if (r == null || !r.isSuccess()) return null;
            return r.output;
        } catch (Throwable t) {
            return null;
        }
    }

    static String labelOf(Context app, String pkg) {
        try {
            PackageManager pm = app.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    // ================================================================== CALL

    private static void registerCall() {
        final Context app = sApp;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    TelephonyManager tm = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
                    tm.listen(new PhoneStateListener() {
                        @Override public void onCallStateChanged(final int state, final String number) {
                            sH.post(new Runnable() {
                                @Override public void run() { onPhoneState(toMachine(state), number); }
                            });
                        }
                    }, PhoneStateListener.LISTEN_CALL_STATE);
                } catch (Throwable t) {
                    Log.w(TAG, "[SMART] 电话状态登记失败（READ_PHONE_STATE？）: " + t.getClass().getSimpleName());
                }
            }
        });
    }

    static int toMachine(int tmState) {
        return tmState == TelephonyManager.CALL_STATE_RINGING ? CallNotices.RINGING
                : tmState == TelephonyManager.CALL_STATE_OFFHOOK ? CallNotices.OFFHOOK : CallNotices.IDLE;
    }

    /** 一次电话状态（也是 debug 注入入口；number 可空）。 */
    public static void onPhoneState(int state, String number) {
        Context app = sApp;
        if (app == null) return;
        String who = number == null || number.length() == 0 ? "" : CallNotices.display(contactName(app, number), number,
                NoticeSettings.flag(app, NoticeSettings.K_REVEAL_NUMBER));
        CallNotices.Step s;
        synchronized (CALL) { s = CALL.on(state, who, System.currentTimeMillis()); }
        if (state != CallNotices.OFFHOOK) { sMuted = false; sSpeaker = false; }
        NoticeCenter c = NoticeHooks.restored(app);
        NoticeCenter.Tick t = new NoticeCenter.Tick();
        int n = 0;
        for (String p : s.retract) n += c.retractPrefix(IslandNotice.Source.CALL, p, NoticeCenter.NOTE_STATE_ENDED, t);
        if (n > 0) NoticeHooks.present(app, t);
        if (s.post != null && on(NoticeSettings.K_SRC_CALL)) NoticeHooks.post(app, s.post);
        Log.i(TAG, "[SMART] 电话状态 → " + (state == CallNotices.RINGING ? "响铃" : state == CallNotices.OFFHOOK ? "通话中" : "空闲")
                + "（撤 " + n + "，发 " + (s.post == null ? "-" : s.post.dedupKey) + "）");
    }

    static String contactName(Context app, String number) {
        android.database.Cursor cur = null;
        try {
            Uri u = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            cur = app.getContentResolver().query(u, new String[]{android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return null;
    }

    /** 通话动作（主线程或任意线程）。返回是否处理了。 */
    static boolean callAction(final String what) {
        final Context app = sApp;
        if (app == null || sH == null) return false;
        sH.post(new Runnable() {
            @Override public void run() {
                try {
                    doCallAction(app, what);
                } catch (Throwable t) {
                    Log.w(TAG, "[SMART] 通话动作 " + what + " 失败: " + t.getClass().getSimpleName());
                }
            }
        });
        return true;
    }

    static void doCallAction(Context app, String what) {
        android.telecom.TelecomManager tm = (android.telecom.TelecomManager) app.getSystemService(Context.TELECOM_SERVICE);
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (CallNotices.A_ANSWER.equals(what)) {
            try {
                tm.acceptRingingCall();
                Log.i(TAG, "[SMART] 接听 → TelecomManager.acceptRingingCall");
            } catch (SecurityException se) {
                boolean ringing = false;
                try {
                    ringing = ((TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE)).getCallState() == TelephonyManager.CALL_STATE_RINGING;
                } catch (Throwable ignored) {
                }
                if (ringing) rootKey(5);   // KEYCODE_CALL（只在真在响铃时）
                Log.i(TAG, "[SMART] 接听 → 没有 ANSWER_PHONE_CALLS，退 root keyevent CALL");
            }
        } else if (CallNotices.A_END.equals(what)) {
            boolean ok = false;
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    ok = tm.endCall();
                } catch (SecurityException ignored) {
                }
            }
            // 没在通话时不发 ENDCALL 键（三星上它等于电源键，会熄屏）——debug 注入的假来电就是这种情况
            boolean live = false;
            try {
                live = ((TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE)).getCallState() != TelephonyManager.CALL_STATE_IDLE;
            } catch (Throwable ignored) {
            }
            if (!ok && live) rootKey(6);   // KEYCODE_ENDCALL
            Log.i(TAG, "[SMART] 挂断 → " + (ok ? "TelecomManager.endCall" : live ? "root keyevent ENDCALL" : "没有进行中的通话，什么都不做"));
        } else if (CallNotices.A_MUTE.equals(what)) {
            boolean want = !am.isMicrophoneMute();
            am.setMicrophoneMute(want);
            sMuted = am.isMicrophoneMute();
            Log.i(TAG, "[SMART] 静音 → 要 " + want + " 实际 " + sMuted);
            if (sMuted != want) inCallScreen(tm, "系统不让第三方切静音，已打开通话界面");
            repostCall(app);
        } else if (CallNotices.A_SPEAKER.equals(what)) {
            boolean want = !am.isSpeakerphoneOn();
            am.setSpeakerphoneOn(want);
            sSpeaker = am.isSpeakerphoneOn();
            Log.i(TAG, "[SMART] 免提 → 要 " + want + " 实际 " + sSpeaker);
            if (sSpeaker != want) inCallScreen(tm, "系统不让第三方切免提，已打开通话界面");
            repostCall(app);
        } else if ("calllog".equals(what)) {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setType(android.provider.CallLog.Calls.CONTENT_TYPE);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(it);
        }
    }

    static void inCallScreen(android.telecom.TelecomManager tm, String why) {
        try {
            tm.showInCallScreen(false);
            Log.i(TAG, "[SMART] " + why);
        } catch (Throwable ignored) {
        }
    }

    /** 静音 / 免提改了：同一个去重键重发，按钮文字跟着变（DUP_UPDATED，不重新提醒）。 */
    static void repostCall(Context app) {
        long since;
        String who;
        synchronized (CALL) {
            if (CALL.state() != CallNotices.OFFHOOK) return;
            since = CALL.offhookAt();
            who = CALL.who();
        }
        NoticeHooks.post(app, CallNotices.inCall(who, since, sMuted, sSpeaker));
    }

    static void rootKey(int code) {
        try {
            new com.onesay.assistant.v2.executor.ShellExecutor(sApp).execute("input keyevent " + code);
        } catch (Throwable ignored) {
        }
    }

    // ================================================================== NOTIFY / ERRAND

    /** EventNotificationListener.onNotificationPosted 最先调（在事件任务白名单过滤之前）。任意线程。 */
    public static void onNotificationPosted(Context ctx, StatusBarNotification sbn) {
        try {
            Context app = ctx.getApplicationContext();
            if (sbn == null || !NoticeSettings.master(app)) return;
            boolean notify = NoticeSettings.flag(app, NoticeSettings.K_SRC_NOTIFY), errand = NoticeSettings.flag(app, NoticeSettings.K_SRC_ERRAND);
            if (!notify && !errand) return;
            String pkg = sbn.getPackageName();
            if (pkg == null || PrivacyNotices.OWN_PACKAGES.contains(pkg)) return;
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            Bundle x = n.extras;
            if (x == null) return;
            NotifyClassifier.Notif in = new NotifyClassifier.Notif();
            in.pkg = pkg;
            in.label = labelOf(app, pkg);
            CharSequence t = x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence b = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (b == null) b = x.getCharSequence(Notification.EXTRA_TEXT);
            in.title = t == null ? "" : t.toString();
            in.text = b == null ? "" : b.toString();
            in.category = n.category == null ? "" : n.category;
            in.template = x.getString(Notification.EXTRA_TEMPLATE, "");
            in.ongoing = sbn.isOngoing();
            in.when = n.when;
            in.usesChronometer = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false);
            in.chronoCountDown = x.getBoolean("android.chronometerCountDown", false);
            in.keyHash = Integer.toHexString(sbn.getKey() == null ? 0 : sbn.getKey().hashCode());
            onNotif(app, in);
        } catch (Throwable t) {
            Log.w(TAG, "[SMART] 处理通知异常: " + t.getClass().getSimpleName());
        }
    }

    /** 分类并上岛（也是 debug 注入入口）。返回分类结果（null = 不上岛）。 */
    public static NotifyClassifier.Result onNotif(Context app, NotifyClassifier.Notif in) {
        NotifyClassifier.Prefs p = prefs(app);
        NotifyClassifier.Result r = NotifyClassifier.classify(in, p, System.currentTimeMillis());
        if (r == null) return null;
        synchronized (NL_KEYS) {
            NL_KEYS.put(in.keyHash, r.notice.dedupKey);
            if (NL_KEYS.size() > 200) NL_KEYS.remove(NL_KEYS.keySet().iterator().next());
        }
        NoticeHooks.post(app, r.notice);
        Log.i(TAG, "[SMART] 通知上岛 " + r.type + " " + in.pkg + " → " + r.notice.dedupKey);
        return r;
    }

    static NotifyClassifier.Prefs prefs(Context app) {
        NotifyClassifier.Prefs p = new NotifyClassifier.Prefs();
        boolean notify = NoticeSettings.flag(app, NoticeSettings.K_SRC_NOTIFY);
        p.otp = notify && NoticeSettings.flag(app, NoticeSettings.K_N_OTP);
        p.voip = notify && NoticeSettings.flag(app, NoticeSettings.K_N_VOIP);
        p.alarm = notify && NoticeSettings.flag(app, NoticeSettings.K_N_ALARM);
        p.nav = notify && NoticeSettings.flag(app, NoticeSettings.K_N_NAV);
        p.music = notify && NoticeSettings.flag(app, NoticeSettings.K_N_MUSIC);
        p.whitelist = notify ? NoticeSettings.whitelist(app) : new java.util.HashSet<String>();
        p.errand = NoticeSettings.flag(app, NoticeSettings.K_SRC_ERRAND);
        p.revealOtp = NoticeSettings.flag(app, NoticeSettings.K_REVEAL_OTP);
        p.revealContent = NoticeSettings.flag(app, NoticeSettings.K_REVEAL_CONTENT);
        return p;
    }

    /** EventNotificationListener.onNotificationRemoved：撤掉对应那条（验证码除外）。 */
    public static void onNotificationRemoved(Context ctx, StatusBarNotification sbn) {
        try {
            if (sbn == null) return;
            onNotifRemoved(ctx.getApplicationContext(), Integer.toHexString(sbn.getKey() == null ? 0 : sbn.getKey().hashCode()));
        } catch (Throwable ignored) {
        }
    }

    public static void onNotifRemoved(Context app, String keyHash) {
        String k;
        synchronized (NL_KEYS) { k = NL_KEYS.remove(keyHash); }
        if (k == null) return;
        if (!(NotifyClassifier.retractOnRemoved(k) || k.startsWith("errand:"))) return;
        retract(app, k);
    }

    // ================================================================== SYSTEM

    private static void registerSystem() {
        final Context app = sApp;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_HEADSET_PLUG);
        f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent it) {
                    final Intent copy = new Intent(it);
                    copy.putExtra("bixby.initialSticky", isInitialStickyBroadcast());
                    sH.post(new Runnable() {
                        @Override public void run() { onSystem(copy); }
                    });
                }
            }, f, null, sH);
        } catch (Throwable t) {
            Log.w(TAG, "[SMART] 系统广播登记失败: " + t.getClass().getSimpleName());
        }
    }

    static void onSystem(Intent it) {
        Context app = sApp;
        if (app == null || it == null) return;
        String a = it.getAction();
        long now = System.currentTimeMillis();
        if (Intent.ACTION_BATTERY_CHANGED.equals(a)) {
            int lv = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), sc = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int st = it.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            boolean charging = it.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
                    || st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL;
            onBattery(lv < 0 || sc <= 0 ? -1 : lv * 100 / sc, charging);
        } else if (Intent.ACTION_HEADSET_PLUG.equals(a)) {
            if (it.getIntExtra("state", 0) == 1 && !isInitialStickyBroadcast(it)) onHeadset(it.getStringExtra("name"), false);
        } else if ("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(a)) {
            if (it.getIntExtra("android.bluetooth.profile.extra.STATE", -1) == 2) {   // STATE_CONNECTED
                String name = "";
                try {
                    android.bluetooth.BluetoothDevice d = it.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                    if (d != null) name = d.getName();
                } catch (Throwable ignored) {
                }
                onHeadset(name, true);
            }
        }
    }

    /** 粘性 HEADSET_PLUG 的首次投递（服务刚起来）不算「刚插上」。 */
    static boolean isInitialStickyBroadcast(Intent it) {
        return it.getBooleanExtra("bixby.initialSticky", false);
    }

    /** 电量（也是 debug 注入入口）。 */
    public static void onBattery(int level, boolean charging) {
        Context app = sApp;
        if (app == null) return;
        IslandNotice n;
        synchronized (BATTERY) { n = BATTERY.on(level, charging, NoticeSettings.lowBattery(app), System.currentTimeMillis()); }
        if (n != null && on(NoticeSettings.K_SRC_SYSTEM)) NoticeHooks.post(app, n);
    }

    public static void onHeadset(String name, boolean bt) {
        Context app = sApp;
        if (app == null || !on(NoticeSettings.K_SRC_SYSTEM)) return;
        NoticeHooks.post(app, SystemStatusNotices.headset(name, bt, System.currentTimeMillis()));
    }

    // ================================================================== 公共

    static void retract(Context app, String key) {
        NoticeCenter.Tick t = new NoticeCenter.Tick();
        if (NoticeHooks.restored(app).retract(key, NoticeCenter.NOTE_STATE_ENDED, t)) {
            NoticeHooks.present(app, t);
            NoticeHooks.reschedule(app, "retract");
        }
    }

    /** 复制（验证码）：写剪贴板；2 分钟后剪贴板里还是它就清掉。内容不进日志。 */
    static void copyToClipboard(final Context app, final String text) {
        if (text == null || text.length() == 0) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    final ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("验证码", text));
                    android.widget.Toast.makeText(app, "已复制验证码", android.widget.Toast.LENGTH_SHORT).show();
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                ClipData c = cm.getPrimaryClip();
                                if (c != null && c.getItemCount() > 0 && text.contentEquals(String.valueOf(c.getItemAt(0).getText()))) {
                                    cm.setPrimaryClip(ClipData.newPlainText("", ""));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }, 120000L);
                } catch (Throwable t) {
                    Log.w(TAG, "[SMART] 复制失败: " + t.getClass().getSimpleName());
                }
            }
        });
    }

    /** 打开 App（arg = 包名）或它的应用信息页（arg = settings:包名）。 */
    static void openApp(Context app, String arg) {
        try {
            Intent it;
            if (arg.startsWith("settings:")) {
                it = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + arg.substring(9)));
            } else {
                it = app.getPackageManager().getLaunchIntentForPackage(arg);
                if (it == null) return;
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "[SMART] 打开 " + arg + " 失败: " + t.getClass().getSimpleName());
        }
    }

    /** 调试：注入「某包开始 / 停止用麦克风 / 相机」（不走系统回调和 root）。 */
    public static void injectPrivacy(String which, String pkg, boolean on) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // integrate（同 r2/fixes 9）：debug 驱动专用，release 里是空操作
        Set<String> s = "cam".equals(which) ? sFakeCam : sFakeMic;
        synchronized (s) {
            if (on) s.add(pkg);
            else s.remove(pkg);
        }
        if ("cam".equals(which)) evalCam();
        else evalMic();
    }

    /** ★ integrate：dumpsys media.audio_policy 的会话 → uid → 包名（PackageManager）。认不出的 uid 不放进去。 */
    static Map<Integer, String> policySessions() {
        Map<Integer, String> r = new java.util.LinkedHashMap<Integer, String>();
        String out = rootRead("dumpsys media.audio_policy | grep -E -- '- session:|- owner uid:|Inputs dump' | head -n 60");
        Context app = sApp;
        if (out == null || app == null) return r;
        for (Map.Entry<Integer, Integer> e : PrivacyNotices.parsePolicyInputs(out).entrySet()) {
            try {
                String[] pk = app.getPackageManager().getPackagesForUid(e.getValue());
                if (pk != null && pk.length > 0) r.put(e.getKey(), pk[0]);
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    /** 调试：真实读一次 root dumpsys（看解析结果，不上岛）。包名可以进日志（不是隐私内容）。 */
    public static String probePrivacy() {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // integrate（同 r2/fixes 9）
        String a = rootRead("dumpsys audio | grep -E 'RecordActivityMonitor|session:[0-9]+ -- source' | head -n 40");
        String c = rootRead("dumpsys media.camera | grep -E 'Active Camera Clients|Client [Pp]ackage|^\\]' | head -n 20");
        return "audio=" + (a == null ? "root 不可用" : PrivacyNotices.parseAudio(a)) + " policy=" + policySessions() + " camera=" + (c == null ? "root 不可用" : PrivacyNotices.parseCamera(c));
    }

    public static String status() {
        StringBuilder sb = new StringBuilder("smart started=" + sStarted);
        synchronized (MIC_ON) { sb.append(" mic=").append(MIC_ON.keySet()); }
        synchronized (CAM_ON) { sb.append(" cam=").append(CAM_ON.keySet()); }
        synchronized (CALL) { sb.append(" call=").append(CALL.state()); }
        sb.append(" muted=").append(sMuted).append(" speaker=").append(sSpeaker);
        synchronized (BATTERY) { sb.append(" battery=").append(BATTERY.level()).append(BATTERY.charging() ? "+" : ""); }
        synchronized (NL_KEYS) { sb.append(" nlTracked=").append(NL_KEYS.size()); }
        if (sPrivacyNote.length() > 0) sb.append(" note=").append(sPrivacyNote);
        if (sApp != null) sb.append(" listener=").append(listenerEnabled(sApp));
        return sb.toString();
    }
}
