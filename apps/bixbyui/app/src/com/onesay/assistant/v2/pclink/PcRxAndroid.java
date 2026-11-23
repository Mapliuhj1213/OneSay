package com.onesay.assistant.v2.pclink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * [pclink v2] 电脑 → 手机的 Android 一侧：常连循环的环境（开关、Wi-Fi、组播锁、配对信息）和每条指令的真正动作。
 *
 * <ul>
 *   <li>找手机：闹钟音频流（USAGE_ALARM）+ 临时把闹钟音量调到最大，静音 / 振动模式也响（「完全勿扰」会挡住，系统限制）；
 *       同时振动、点亮屏幕、发一条带「停止」的通知；到时 / 电脑发 stop / 点通知都会停，并把闹钟音量还原。
 *       （用闹钟流最大音量找手机的做法参考 Home Assistant 安卓伴侣 App 的 alarm_stream_max，代码自己写的。）</li>
 *   <li>打开网址 / 打开 App / 点亮屏幕 / 媒体音量 / 写剪贴板 / 弹通知 / 电量状态。</li>
 * </ul>
 * 日志只打指令名、结果、长度，不打网址、剪贴板、通知内容。
 */
public final class PcRxAndroid implements PcRx.Env, PcRx.Handler, PcRx.Guard, PcRx.Asker {

    static final String TAG = "PcLink";
    public static final String K_RX = "rx_enabled", K_RX_LOG = "rx_log";
    /** [approve-not-block] 「以后这类不用问」记住的类型（只收 phone.*）；敏感剪贴板 60 秒清除（可选，默认关）。 */
    public static final String K_APPROVE_KINDS = "rx_approve_kinds", K_CLIP_AUTOCLEAR = "rx_clip_autoclear";
    static final String CH = "pclink_from_pc", ACTION_STOP = "com.onesay.assistant.v2.pclink.RING_STOP";
    static final int NID_RING = 47821, NID_MSG = 47822;

    private static PcRx.Loop loop;
    private static Thread thread;
    private static volatile String state = PcRx.S_OFF, detail = "";

    private final Context app;
    private WifiManager.MulticastLock mlock;

    private PcRxAndroid(Context app) {
        this.app = app;
        PcConfirmActivity.install(app); // 手机屏幕上的确认卡（本机卡 + 电脑发来的卡）
    }

    private static boolean sNonceAttached;

    /** ★ sec-1123 M6：电脑发来的报文的一次性编号记进本机（只存摘要，时间窗过了就清），断线重连、App 重启后旧报文也不能重放。 */
    static synchronized void attachNonceStore(Context app) {
        if (sNonceAttached) return;
        sNonceAttached = true;
        final android.content.SharedPreferences sp = app.getSharedPreferences("pclink_nonce", Context.MODE_PRIVATE);
        PcProtocol.attachNonceStore(new PcProtocol.NonceStore() {
            @Override public String load() {
                return sp.getString("seen", "");
            }

            @Override public void save(String data) {
                sp.edit().putString("seen", data).commit();
            }
        });
    }

    // ================================================================== 生命周期

    /** 进程起来 / 配对 / 开关变了时调用：该跑就起（已在跑就叫它马上重试）。 */
    public static synchronized void ensure(Context c) {
        Context app = c.getApplicationContext();
        if (loop != null && thread != null && thread.isAlive()) {
            loop.wake();
            return;
        }
        PcRxAndroid env = new PcRxAndroid(app);
        attachNonceStore(app); // ★ sec-1123 M6：防重放的一次性编号落盘（跨会话 / 重启）
        // ★ app-whitelist-1116：常连会话里电脑推来的白名单要能合并（状态先初始化，提醒走通知）
        com.onesay.assistant.v2.whitelist.WhitelistAndroid.state(app);
        final Context fa = app;
        PcRx.WL_TELL = new PcRx.WhitelistTell() {
            @Override public void added(java.util.List<com.onesay.assistant.v2.whitelist.AppWhitelist.Entry> e) {
                com.onesay.assistant.v2.whitelist.WhitelistAndroid.tellAdded(fa, e);
            }
        };
        if (!env.wanted()) return; // 不需要就不起线程
        loop = new PcRx.Loop(env);
        thread = com.onesay.assistant.v2.util.Threads.start("pclink-rx", loop); // 非守护；loop.stop() 后自己退出
        Log.i(TAG, "[PCLINK] 常连通道启动");
    }

    public static synchronized void stopAll() {
        if (loop != null) loop.stop();
        loop = null;
        thread = null;
    }

    public static boolean rxOn(Context c) {
        return PcLink.sp(c).getBoolean(K_RX, PcRxPolicy.MASTER_DEFAULT);
    }

    public static void setRx(Context c, boolean on) {
        PcLink.sp(c).edit().putBoolean(K_RX, on).apply();
        if (on) ensure(c);
        else if (loop != null) loop.wake();
    }

    public static boolean cmdOn(Context c, String cmd) {
        return PcLink.sp(c).getBoolean("rx_" + cmd, PcRxPolicy.defaultOn(cmd));
    }

    public static void setCmd(Context c, String cmd, boolean on) {
        PcLink.sp(c).edit().putBoolean("rx_" + cmd, on).apply();
    }

    /** 手机工作区：getExternalFilesDir("workspace")（App 专属存储，卸载即删，不需要存储权限）；拿不到退到内部存储。 */
    public static java.io.File workspaceDir(Context c) {
        java.io.File d = null;
        try {
            d = c.getExternalFilesDir("workspace");
        } catch (Throwable ignored) {
        }
        if (d == null) d = new java.io.File(c.getFilesDir(), "workspace");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }


    /** 控制台显示用：常连状态。 */
    public static String stateText() {
        return PcLinkText.rxState(state, detail); // [rel-1123-b] 离线写原因
    }

    public static boolean up() {
        return PcRx.S_UP.equals(state);
    }

    // ================================================================== Env

    @Override public boolean wanted() {
        return PcLink.active(app) && rxOn(app) && !PcLink.keyLost(app);
    }

    @Override public boolean onLan() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected() && (ni.getType() == ConnectivityManager.TYPE_WIFI || ni.getType() == ConnectivityManager.TYPE_ETHERNET);
        } catch (Throwable t) {
            return true;
        }
    }

    @Override public PcLanClient.Peer peer() {
        return PcLink.peer(app);
    }

    @Override public List<InetAddress> extraBroadcasts() {
        return PcLink.wifiBroadcast(app);
    }

    @Override public synchronized void multicast(boolean hold) {
        try {
            if (hold) {
                if (mlock == null) {
                    WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                    if (wm == null) return;
                    mlock = wm.createMulticastLock("onesay-pclink");
                    mlock.setReferenceCounted(false);
                }
                if (!mlock.isHeld()) mlock.acquire();
            } else if (mlock != null && mlock.isHeld()) {
                mlock.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "[PCLINK] 组播锁: " + t.getClass().getSimpleName());
        }
    }

    @Override public void state(String s, String d) {
        if (!s.equals(state) || !d.equals(detail)) Log.i(TAG, "[PCLINK] 常连 " + s + (PcRx.S_ERROR.equals(s) ? " " + d : ""));
        state = s;
        detail = d == null ? "" : d;
    }

    @Override public void remember(InetSocketAddress a) {
        String last = a.getAddress().getHostAddress() + ":" + a.getPort();
        SharedPreferences sp = PcLink.sp(app);
        if (!last.equals(sp.getString(PcLink.K_LAST, ""))) sp.edit().putString(PcLink.K_LAST, last).apply();
    }

    @Override public PcRx.Handler handler() {
        return this;
    }

    // ================================================================== Guard（红线要的手机侧事实；没有执行级别、不确认）

    static final int NID_SCRIPT = 47860;

    @Override public java.io.File workspace() {
        return workspaceDir(app);
    }

    @Override public void appFacts(String cmd, Map<String, Object> a, LinkPolicy.Facts f) {
        String pkg = PcRxPolicy.str(a, "package");
        String label = PcRxPolicy.str(a, "name");
        PackageManager pm = app.getPackageManager();
        if (pkg.length() == 0 && label.length() > 0) {
            ResolveInfo hit = findByName(pm, label);
            if (hit != null) {
                pkg = hit.activityInfo.packageName;
                a.put("package", pkg); // 之后按包名打开，判定和执行用同一个 App
            }
        }
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null) label = l.toString();
            f.appSystem = (ai.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Exception ignored) {
        }
        f.appLabel = label;
        // [approve-not-block] 自己（OneSay）：远程卸载 / 停用是硬规则（改自己的权限），打开不算
        f.appSelf = app.getPackageName().equals(pkg);
        f.appBlocked = f.appBlocked || LinkPolicy.blockedApp(pkg, label);
    }

    // ================================================================== Asker（手机确认卡、以后这类不用问、剪贴板清除）

    @Override public int ask(String title, String detail, String rememberKind) {
        PcConfirmActivity.install(app);
        return PcConfirm.ask(title, detail, rememberKind, LinkPolicy.CONFIRM_TIMEOUT_MS);
    }

    @Override public java.util.Collection<String> rememberedKinds() {
        return rememberedKinds(app);
    }

    @Override public void remember(String kind) {
        if (kind == null || !kind.startsWith("phone.") || !LinkPolicy.knownKind(kind)) return;
        java.util.List<String> k = rememberedKinds(app);
        if (!k.contains(kind)) k.add(kind);
        PcLink.sp(app).edit().putString(K_APPROVE_KINDS, LinkPolicy.formatKinds(k)).apply();
        Log.i(TAG, "[PCLINK] 以后这类不用问：" + kind);
    }

    @Override public boolean clipAutoClear() {
        return clipAutoClear(app);
    }

    public static java.util.List<String> rememberedKinds(Context c) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String k : LinkPolicy.parseKinds(PcLink.sp(c).getString(K_APPROVE_KINDS, ""))) if (k.startsWith("phone.")) out.add(k);
        return out;
    }

    /** 控制台「撤销」：没有这一类返回 false。 */
    public static boolean forget(Context c, String kind) {
        java.util.List<String> k = rememberedKinds(c);
        if (!k.remove(kind)) return false;
        PcLink.sp(c).edit().putString(K_APPROVE_KINDS, LinkPolicy.formatKinds(k)).apply();
        return true;
    }

    public static boolean clipAutoClear(Context c) {
        return PcLink.sp(c).getBoolean(K_CLIP_AUTOCLEAR, LinkPolicy.CLIP_AUTOCLEAR_DEFAULT);
    }

    public static void setClipAutoClear(Context c, boolean on) {
        PcLink.sp(c).edit().putBoolean(K_CLIP_AUTOCLEAR, on).apply();
    }

    @Override public String script(String cmd, Map<String, Object> a, String why) {
        String title, what, risk, command;
        if (PcRxPolicy.SYS_EXEC.equals(cmd)) {
            command = PcRxPolicy.str(a, "command");
            title = "system-command";
            what = "电脑请求在手机上执行系统命令（见下方）";
            risk = "系统命令可能改动设置、权限或数据；涉及授权的步骤 OneSay 永远不会自己执行";
        } else if (PcRxPolicy.SETTING.equals(cmd)) {
            String n = PcRxPolicy.str(a, "name"), v = PcRxPolicy.str(a, "value");
            command = settingShell(n, v);
            title = "setting-" + n;
            what = "电脑请求把手机设置「" + n + "」改成「" + v + "」";
            risk = "可能影响网络、安全或权限；请确认后再执行";
        } else if (PcRxPolicy.FILE_WRITE.equals(cmd)) {
            String path = PcRxPolicy.str(a, "path");
            String marker = "ONESAY_EOF_" + Long.toHexString(System.nanoTime());
            command = "cat > '" + path.replace("'", "'\\''") + "' <<'" + marker + "'\n" + String.valueOf(a.get("text")) + "\n" + marker;
            title = "write-file";
            what = "电脑请求写工作区以外的文件：" + path;
            risk = "会覆盖同名文件";
        } else {
            command = "# " + cmd + " " + LinkPolicy.oneLine(String.valueOf(a));
            title = cmd;
            what = "电脑请求：" + PcRxPolicy.label(cmd);
            risk = "未知";
        }
        LinkPolicy.Script sc = LinkPolicy.buildScript(LinkPolicy.ScriptKind.SH, title, what + "。" + why, risk, command, System.currentTimeMillis());
        String rel = LinkPolicy.writeScript(workspace(), sc);
        if (rel == null) return null;
        try {
            Notification n = builder(app).setSmallIcon(app.getApplicationInfo().icon).setContentTitle("已生成脚本，需你自己执行")
                    .setContentText(rel).setStyle(new Notification.BigTextStyle().bigText(what + "\n位置：OneSay 工作区 / " + rel)).setAutoCancel(true).build();
            channel(app).notify(NID_SCRIPT + (int) (System.currentTimeMillis() % 50), n);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "[PCLINK] 已生成脚本 " + cmd);
        return rel;
    }

    /** setting_set 对应的 shell 命令（生成脚本 / 通过特权通道执行网络安全设置时用）。 */
    static String settingShell(String name, String value) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        boolean on = isOn(value);
        String v = value.replaceAll("[^A-Za-z0-9_.\\-]", "");
        if ("airplane".equals(n)) return "cmd connectivity airplane-mode " + (on ? "enable" : "disable");
        if ("mobile_data".equals(n)) return "svc data " + (on ? "enable" : "disable");
        if ("nfc".equals(n)) return "svc nfc " + (on ? "enable" : "disable");
        if ("location".equals(n)) return "settings put secure location_mode " + (on ? "3" : "0");
        if ("private_dns".equals(n)) return on || v.length() == 0 ? "settings put global private_dns_mode " + (v.contains(".") ? "hostname; settings put global private_dns_specifier " + v : "opportunistic") : "settings put global private_dns_mode off";
        if ("proxy".equals(n)) return v.length() == 0 || !on && !v.contains(":") ? "settings put global http_proxy :0" : "settings put global http_proxy " + v;
        if ("adb".equals(n)) return "settings put global adb_enabled " + (on ? "1" : "0");
        if ("unknown_sources".equals(n)) return "settings put secure install_non_market_apps " + (on ? "1" : "0");
        if ("hotspot".equals(n)) return "cmd wifi " + (on ? "start-softap" : "stop-softap");
        if ("lock_screen".equals(n)) return "locksettings " + (on ? "set-disabled false" : "set-disabled true");
        return "# 设置 " + LinkPolicy.oneLine(name) + " = " + LinkPolicy.oneLine(value) + "（需要你自己在系统设置里操作）";
    }

    static boolean isOn(String v) {
        String x = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return x.equals("on") || x.equals("true") || x.equals("1") || x.equals("enable") || x.equals("开") || x.equals("打开");
    }

    // ================================================================== Handler

    @Override public boolean enabled(String cmd) {
        return cmdOn(app, cmd);
    }

    @Override public void record(String cmd, boolean ok, String text) {
        String line = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date()) + " " + PcRxPolicy.label(cmd) + (ok ? " ✓" : " ✗ " + clip(text, 40));
        SharedPreferences sp = PcLink.sp(app);
        String old = sp.getString(K_RX_LOG, "");
        StringBuilder b = new StringBuilder(line);
        String[] lines = old.split("\n");
        for (int i = 0; i < lines.length && i < 9; i++) if (lines[i].length() > 0) b.append('\n').append(lines[i]);
        sp.edit().putString(K_RX_LOG, b.toString()).apply();
        Log.i(TAG, "[PCLINK] 电脑指令 " + cmd + " → " + (ok ? "ok" : "fail"));
    }

    static String clip(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…";
    }

    @Override public PcLanClient.Result run(String cmd, Map<String, Object> a) {
        if (PcRxPolicy.FIND.equals(cmd)) {
            if ("stop".equals(PcRxPolicy.str(a, "action"))) return ok(Ring.stop(app) ? "已停止响铃" : "本来就没在响", null);
            int s = PcRxPolicy.ringSeconds(a);
            return Ring.start(app, s) ? ok("手机正在响（" + s + " 秒）", null) : fail("响不了：没有可用的铃声");
        }
        if (PcRxPolicy.OPEN_URL.equals(cmd)) {
            String u = PcVoice.normalizeUrl(PcRxPolicy.str(a, "url"));
            if (u == null) return fail("只能打开 http / https 网址");
            try {
                app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return ok("已在手机上打开", null);
            } catch (Exception e) {
                return fail("手机上没有能打开网址的应用");
            }
        }
        if (PcRxPolicy.OPEN_APP.equals(cmd)) return openApp(PcRxPolicy.str(a, "package"), PcRxPolicy.str(a, "name"));
        if (PcRxPolicy.OPERATE.equals(cmd)) return operate(PcRxPolicy.str(a, "goal"));
        if (PcRxPolicy.OPERATE_STATUS.equals(cmd)) return operateStatus();
        if (PcRxPolicy.APP_LIST.equals(cmd)) return appList(PcRxPolicy.str(a, "query")); // [rel-1123-b]
        if (PcRxPolicy.FILE_PUT.equals(cmd)) return PcFileAndroid.rx(app).receive(a, PcLink.pcName(app)); // [overnight-1119] 电脑发文件过来
        if (PcRxPolicy.SHORTCUT_LIST.equals(cmd)) return shortcutList();
        if (PcRxPolicy.SHORTCUT_RUN.equals(cmd)) return shortcutRun(PcRxPolicy.str(a, "name"));
        if (PcRxPolicy.SCREEN_ON.equals(cmd)) {
            try {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "onesay:pclink-screen");
                wl.acquire(3000);
                return ok("屏幕已点亮", null);
            } catch (Exception e) {
                return fail("点不亮：" + e.getClass().getSimpleName());
            }
        }
        if (PcRxPolicy.VOLUME.equals(cmd)) {
            try {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int p = ((Number) a.get("percent")).intValue();
                int idx = Math.round(p * max / 100f);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0);
                return ok("媒体音量 " + p + "%", null);
            } catch (Exception e) {
                return fail("调不了音量：" + e.getClass().getSimpleName());
            }
        }
        if (PcRxPolicy.CLIPBOARD.equals(cmd)) {
            final String t = String.valueOf(a.get("text"));
            final boolean sensitive = Boolean.TRUE.equals(a.get("_sensitive"));
            final boolean clear = sensitive && Boolean.TRUE.equals(a.get("_clear")); // 60 秒清除：可选设置，默认关
            final boolean[] done = {false};
            final CountDownLatch l = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData cd = ClipData.newPlainText("OneSay", t);
                        if (sensitive) Clip.markSensitive(cd);
                        Clip.set(cm, cd, clear);
                        done[0] = true;
                    } catch (Throwable ignored) {
                    }
                    l.countDown();
                }
            });
            try {
                l.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return done[0] ? ok("已放进手机剪贴板（" + t.length() + " 字）" + (clear ? "，标为敏感，60 秒后自动清除" : sensitive ? "，标为敏感" : ""), null) : fail("写不了剪贴板");
        }
        if (PcRxPolicy.FLASHLIGHT.equals(cmd)) return flashlight(!Boolean.FALSE.equals(a.get("on")));
        if (PcRxPolicy.SETTING.equals(cmd)) return setting(PcRxPolicy.str(a, "name"), PcRxPolicy.str(a, "value"), a);
        if (PcRxPolicy.FILE_WRITE.equals(cmd)) return fileWrite(PcRxPolicy.str(a, "path"), String.valueOf(a.get("text")), Boolean.TRUE.equals(a.get("_outside")));
        if (PcRxPolicy.FILE_LIST.equals(cmd)) return fileList();
        if (PcRxPolicy.UNINSTALL.equals(cmd)) return uninstall(PcRxPolicy.str(a, "package"));
        if (PcRxPolicy.SCRIPT_LIST.equals(cmd)) {
            Map<String, Object> d = new TreeMap<String, Object>();
            d.put("scripts", new java.util.ArrayList<Object>(LinkPolicy.listScripts(workspace())));
            return ok("工作区脚本", d);
        }
        if (PcRxPolicy.SCRIPT_RUN.equals(cmd)) return scriptRun(PcRxPolicy.str(a, "name"), PcRx.checkedScript());
        if (PcRxPolicy.SYS_EXEC.equals(cmd)) return shell(PcRxPolicy.str(a, "command"));
        if (PcRxPolicy.NOTIFY.equals(cmd)) {
            // [fix-1123-misc] 系统会把通知文字硬截到 1024 个 UTF-16 单位（可能切开表情 / 生僻字）：自己先截到 1000 个单位以内（不切开代理对）、末尾加「…」
            String title = com.onesay.assistant.v2.util.TextUtil.truncate(PcRxPolicy.str(a, "title"), PcRxPolicy.NOTIFY_TITLE_SHOW, "…"),
                    text = com.onesay.assistant.v2.util.TextUtil.truncate(PcRxPolicy.str(a, "text"), PcRxPolicy.NOTIFY_TEXT_SHOW, "…");
            try {
                NotificationManager nm = channel(app);
                Notification n = builder(app).setSmallIcon(app.getApplicationInfo().icon)
                        .setContentTitle(title.length() > 0 ? title : "电脑提醒").setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build();
                nm.notify(NID_MSG + (int) (System.currentTimeMillis() % 1000), n);
                return ok("已在手机上提醒", null);
            } catch (Exception e) {
                return fail("通知发不出：" + e.getClass().getSimpleName());
            }
        }
        if (PcRxPolicy.STATUS.equals(cmd)) return status();
        return fail("手机不支持「" + cmd + "」");
    }

    /**
     * [phone-tools-1118] 按目标操作：把这句目标交给手机上的 OneSay 面板（和在手机上说这句话走同一条规划执行链，
     * 那条链上打电话 / 发短信 / 付款 / 给别人发消息的确认卡照旧）。只负责交过去，不等做完；结果在手机屏幕上。
     */
    /** [overnight-1119] 最近一次电脑交来的目标（只在内存里，给 operate_status 回话）。 */
    static volatile String lastGoal = "";
    static volatile long lastGoalAt;

    PcLanClient.Result operateStatus() {
        Map<String, Object> d = new TreeMap<String, Object>();
        Map<String, Object> gs = PcGoals.status(); // [rel-1123-b] 有回传结果的就说结果
        if (gs != null) {
            String st = String.valueOf(gs.get("state"));
            String goal = clip(String.valueOf(gs.get("goal")), 40), text = String.valueOf(gs.get("text"));
            long min = gs.get("minutes_ago") instanceof Number ? ((Number) gs.get("minutes_ago")).longValue() : 0;
            String when = min == 0 ? "刚才" : min + " 分钟前";
            if (PcGoals.RUNNING.equals(st)) return ok(when + "交给手机的「" + goal + "」还在做（做完会把结果告诉电脑）", gs);
            return ok(when + "交给手机的「" + goal + "」" + goalStateZh(st) + (text.length() > 0 ? "：" + text : ""), gs);
        }
        if (lastGoalAt == 0) {
            d.put("state", "none");
            return ok("这次手机上的 OneSay 启动后，电脑还没交过目标", d);
        }
        long min = Math.max(0, (System.currentTimeMillis() - lastGoalAt) / 60000);
        d.put("state", "handed");
        d.put("goal", lastGoal);
        d.put("minutes_ago", min);
        return ok((min == 0 ? "刚才" : min + " 分钟前") + "交给手机的「" + clip(lastGoal, 40) + "」已经在手机上开始做；做到哪一步、结果怎样看手机屏幕（手机不把屏幕内容传回电脑）", d);
    }

    /** [overnight-1119] 手机上的快捷指令给电脑看。 */
    PcLanClient.Result shortcutList() {
        try {
            List<com.onesay.assistant.v2.shortcut.ShortcutModel.Shortcut> all = com.onesay.assistant.v2.shortcut.ShortcutHooks.forPc(app);
            if (all == null) return fail("手机上的快捷指令功能没开（控制台 › 快捷指令）");
            Map<String, Object> d = new TreeMap<String, Object>();
            d.put("shortcuts", PcShortcuts.view(all));
            return ok(all.isEmpty() ? "手机上还没有快捷指令" : "手机上有 " + all.size() + " 条快捷指令", d);
        } catch (Throwable t) {
            return fail("读不到手机上的快捷指令（计划 App 连不上）");
        }
    }

    /** [overnight-1119] 电脑让手机跑一条快捷指令：交给手机上的 OneSay 面板（和在手机上点「运行」同一条执行链，每一步照旧按手机的规则问你）。 */
    PcLanClient.Result shortcutRun(String name) {
        PcShortcuts.Pick p;
        try {
            List<com.onesay.assistant.v2.shortcut.ShortcutModel.Shortcut> all = com.onesay.assistant.v2.shortcut.ShortcutHooks.forPc(app);
            if (all == null) return fail("手机上的快捷指令功能没开（控制台 › 快捷指令）");
            p = PcShortcuts.find(all, name);
        } catch (Throwable t) {
            return fail("读不到手机上的快捷指令（计划 App 连不上）");
        }
        if (p.hit == null) return fail(p.why);
        PcLanClient.Result r = toPanel(com.onesay.assistant.v2.shortcut.ShortcutModel.runCommand(p.hit.id, p.hit.name));
        if (!r.ok) return r;
        Map<String, Object> d = new TreeMap<String, Object>();
        d.put("name", p.hit.name);
        d.put("steps", (long) p.hit.steps.size());
        return ok("手机开始执行快捷指令「" + p.hit.name + "」（" + p.hit.steps.size() + " 步；打电话 / 发消息 / 付款这类会在手机上问你）", d);
    }

    /** 把一句话交给手机上的 OneSay 面板（和在手机上说这句话一样）。 */
    PcLanClient.Result toPanel(String query) {
        try {
            Intent i = new Intent(app, com.onesay.assistant.v2.ui.FloatingSheetService.class)
                    .setAction(com.onesay.assistant.v2.ui.FloatingSheetService.ACTION_SHOW)
                    .putExtra(com.onesay.assistant.v2.ui.FloatingSheetService.EXTRA_QUERY, query);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
            return ok("", null);
        } catch (Exception e) {
            return fail("手机上的 OneSay 面板没起来：" + e.getClass().getSimpleName());
        }
    }

    PcLanClient.Result operate(String goal) {
        if (goal.length() == 0) return fail("没说要手机做什么");
        lastGoal = goal;
        lastGoalAt = System.currentTimeMillis();
        String gid = PcGoals.begin(PcLink.peerId(app), goal); // [rel-1123-b] 做完把结果回传电脑
        try {
            Intent i = new Intent(app, com.onesay.assistant.v2.ui.FloatingSheetService.class)
                    .setAction(com.onesay.assistant.v2.ui.FloatingSheetService.ACTION_SHOW)
                    .putExtra(com.onesay.assistant.v2.ui.FloatingSheetService.EXTRA_QUERY, goal);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
            Log.i(TAG, "[PCLINK] 电脑交来一个目标（" + goal.length() + " 字）→ 面板");
            Map<String, Object> d = new TreeMap<String, Object>();
            d.put("goal", goal);
            d.put("goal_id", gid);
            return ok("已交给手机上的 OneSay 去做（手机会自己规划执行，打电话 / 发短信 / 付款 / 给别人发消息会在手机上问你），做完会把结果告诉电脑", d);
        } catch (Exception e) {
            return fail("手机上的 OneSay 面板没起来：" + e.getClass().getSimpleName());
        }
    }

    static String goalStateZh(String st) {
        if (PcGoals.DONE.equals(st)) return "做完了";
        if (PcGoals.DENIED.equals(st)) return "你在手机上拒绝了";
        if (PcGoals.STOPPED.equals(st)) return "在手机上停掉了";
        if (PcGoals.TIMEOUT.equals(st)) return "做太久，停了";
        if (PcGoals.INTERRUPTED.equals(st)) return "被打断了";
        if (PcGoals.UNKNOWN.equals(st)) return "没留下结果";
        return "没做成";
    }

    /** [rel-1123-b] 手机上装了哪些 App：桌面启动项的名字 + 包名（不带图标），按名字排，最多 300 个；query 非空只列名字带这几个字的。 */
    PcLanClient.Result appList(String query) {
        try {
            PackageManager pm = app.getPackageManager();
            List<ResolveInfo> all = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            java.util.List<PcAppList.App> l = new java.util.ArrayList<PcAppList.App>();
            for (ResolveInfo ri : all) {
                if (ri.activityInfo == null) continue;
                CharSequence lb = ri.loadLabel(pm);
                l.add(new PcAppList.App(lb == null ? "" : lb.toString(), ri.activityInfo.packageName));
            }
            Log.i(TAG, "[PCLINK] 电脑要已装 App 列表（" + l.size() + " 个入口" + (query.length() > 0 ? "，带筛选" : "") + "）");
            return PcAppList.view(l, query, PcRxPolicy.APP_LIST_MAX);
        } catch (Exception e) {
            return fail("读不到手机上的 App 列表：" + e.getClass().getSimpleName());
        }
    }

    PcLanClient.Result openApp(String pkg, String name) {
        PackageManager pm = app.getPackageManager();
        Intent launch = null;
        String label = pkg;
        if (pkg.length() > 0) {
            launch = pm.getLaunchIntentForPackage(pkg);
        } else {
            ResolveInfo hit = findByName(pm, name);
            if (hit != null) {
                launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName);
                label = String.valueOf(hit.loadLabel(pm));
            }
        }
        if (launch == null) return fail("手机上没找到这个 App");
        try {
            app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return ok("已打开 " + label, null);
        } catch (Exception e) {
            return fail("打不开：" + e.getClass().getSimpleName());
        }
    }

    /** 按桌面上的名字找 App：先全等，再包含。 */
    static ResolveInfo findByName(PackageManager pm, String name) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(q, 0);
        ResolveInfo exact = null, part = null;
        String want = name.toLowerCase(Locale.ROOT).replace(" ", "");
        for (ResolveInfo ri : all) {
            CharSequence l = ri.loadLabel(pm);
            String s = l == null ? "" : l.toString().toLowerCase(Locale.ROOT).replace(" ", "");
            if (s.equals(want)) { exact = ri; break; }
            if (part == null && s.length() > 0 && (s.contains(want) || want.contains(s))) part = ri;
        }
        return exact != null ? exact : part;
    }

    PcLanClient.Result status() {
        Map<String, Object> d = new TreeMap<String, Object>();
        try {
            Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) d.put("battery", Long.valueOf(Math.round(level * 100f / scale)));
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                d.put("charging", Boolean.valueOf(st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL));
            }
        } catch (Exception ignored) {
        }
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            d.put("screen_on", Boolean.valueOf(pm.isInteractive()));
        } catch (Exception ignored) {
        }
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            int rm = am.getRingerMode();
            d.put("ringer", rm == AudioManager.RINGER_MODE_SILENT ? "静音" : rm == AudioManager.RINGER_MODE_VIBRATE ? "振动" : "响铃");
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max > 0) d.put("music_volume", Long.valueOf(Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)));
        } catch (Exception ignored) {
        }
        d.put("model", Build.MODEL);
        d.put("android", Build.VERSION.RELEASE);
        return ok("手机状态", d);
    }

    static PcLanClient.Result ok(String text, Map<String, Object> data) {
        return new PcLanClient.Result(true, text, data, "");
    }

    static PcLanClient.Result fail(String text) {
        return new PcLanClient.Result(false, text, null, "");
    }

    static NotificationManager channel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "电脑发来的", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null); // 找手机自己放声音；普通提醒不额外响
            nm.createNotificationChannel(ch);
        }
        return nm;
    }

    /** ★ app-whitelist-1116：普通提醒通知（白名单同步来新条目等），走「电脑发来的」渠道。 */
    public static Notification notice(Context c, String title, String text) {
        channel(c);
        return builder(c).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build();
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context c) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
    }

    // ================================================================== 加回来的 8 项的执行（开关 / 参数 / 红线已在 PcRx.handle 里查过）

    PcLanClient.Result flashlight(boolean on) {
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean has = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(has)) {
                    cm.setTorchMode(id, on);
                    return ok(on ? "手电筒已打开" : "手电筒已关闭", null);
                }
            }
            return fail("这台手机没有闪光灯");
        } catch (Exception e) {
            return fail("开不了手电筒：" + e.getClass().getSimpleName());
        }
    }

    PcLanClient.Result setting(String name, String value, Map<String, Object> a) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        boolean on = isOn(value);
        try {
            if ("volume".equals(n)) {
                Map<String, Object> m = new TreeMap<String, Object>();
                m.put("percent", Long.valueOf(clampInt(value, 0, 100)));
                return run(PcRxPolicy.VOLUME, m);
            }
            if ("brightness".equals(n) || "screen_timeout".equals(n)) {
                // 不会自己去申请「修改系统设置」权限：没给就说清楚，让用户自己去系统设置里开
                if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.System.canWrite(app)) return fail("手机没给 OneSay「修改系统设置」权限（不会自动申请，需要你在系统设置里手动打开）");
                if ("brightness".equals(n)) {
                    android.provider.Settings.System.putInt(app.getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    android.provider.Settings.System.putInt(app.getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS, Math.round(clampInt(value, 0, 100) * 255 / 100f));
                    return ok("亮度 " + clampInt(value, 0, 100) + "%", null);
                }
                int sec = clampInt(value, 15, 1800);
                android.provider.Settings.System.putInt(app.getContentResolver(), android.provider.Settings.System.SCREEN_OFF_TIMEOUT, sec * 1000);
                return ok("屏幕超时 " + sec + " 秒", null);
            }
            if ("dnd".equals(n)) {
                NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                if (!nm.isNotificationPolicyAccessGranted()) return fail("手机没给 OneSay「勿扰权限」（不会自动申请，需要你手动打开）");
                nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);
                return ok(on ? "勿扰已打开" : "勿扰已关闭", null);
            }
            if ("ringer".equals(n)) {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                String v = value.trim().toLowerCase(Locale.ROOT);
                int mode = v.startsWith("vib") || v.equals("振动") ? AudioManager.RINGER_MODE_VIBRATE : v.startsWith("sil") || v.equals("静音") ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
                am.setRingerMode(mode);
                return ok("铃声模式已改", null);
            }
            if ("wifi".equals(n)) {
                WifiManager wm = (WifiManager) app.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                @SuppressWarnings("deprecation")
                boolean r = wm.setWifiEnabled(on);
                return r ? ok(on ? "Wi-Fi 已打开" : "Wi-Fi 已关闭（电脑联动会断开）", null) : fail("改不了 Wi-Fi");
            }
            if ("bluetooth".equals(n)) {
                android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (ba == null) return fail("这台手机没有蓝牙");
                boolean r = on ? ba.enable() : ba.disable();
                return r ? ok(on ? "蓝牙正在打开" : "蓝牙正在关闭", null) : fail("改不了蓝牙");
            }
        } catch (Exception e) {
            return fail("改不了设置：" + e.getClass().getSimpleName());
        }
        // 网络 / 安全设置：开关开着就走特权通道执行对应的 shell 命令（分档之前的语义，不在手机上确认）
        return shell(settingShell(name, value));
    }

    static int clampInt(String v, int lo, int hi) {
        int x;
        try {
            x = (int) Math.round(Double.parseDouble(v.trim()));
        } catch (Exception e) {
            x = lo;
        }
        return Math.max(lo, Math.min(hi, x));
    }

    PcLanClient.Result fileWrite(String path, String text, boolean outside) {
        try {
            byte[] data = text.getBytes("UTF-8");
            if (!outside) {
                java.io.File f = LinkPolicy.writeInside(workspace(), path, data);
                return f == null ? fail("只能写工作区里的文件") : ok("已写入工作区 " + path + "（" + data.length + " 字节）", null);
            }
            // 工作区以外：只许共享存储根目录下的普通文件，不碰别的 App 的私有目录（Android/）
            java.io.File root = android.os.Environment.getExternalStorageDirectory();
            String rel = path;
            String rp = root.getCanonicalPath();
            if (rel.startsWith(rp + "/")) rel = rel.substring(rp.length() + 1);
            else if (rel.startsWith("/sdcard/")) rel = rel.substring(8);
            if (rel.toLowerCase(Locale.ROOT).startsWith("android/")) return fail("不写 Android/ 目录（别的 App 的数据）");
            java.io.File f = LinkPolicy.writeInside(root, rel, data);
            return f == null ? fail("路径不允许（只能是手机共享存储里的普通文件）") : ok("已写入 " + f.getPath() + "（" + data.length + " 字节）", null);
        } catch (Exception e) {
            return fail("写不了文件：" + e.getClass().getSimpleName());
        }
    }

    PcLanClient.Result fileList() {
        java.io.File ws = workspace();
        List<Object> out = new java.util.ArrayList<Object>();
        listInto(ws, "", out, 0);
        Map<String, Object> d = new TreeMap<String, Object>();
        d.put("files", out);
        d.put("workspace", "OneSay 工作区");
        return ok("工作区 " + out.size() + " 个文件", d);
    }

    static void listInto(java.io.File dir, String prefix, List<Object> out, int depth) {
        java.io.File[] fs = dir.listFiles();
        if (fs == null || depth > 4) return;
        java.util.Arrays.sort(fs);
        for (java.io.File f : fs) {
            if (out.size() >= 200) return;
            if (f.isDirectory()) listInto(f, prefix + f.getName() + "/", out, depth + 1);
            else out.add(prefix + f.getName());
        }
    }

    PcLanClient.Result uninstall(String pkg) {
        try {
            app.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (Exception e) {
            return fail("手机上没装这个 App");
        }
        try {
            // 系统卸载界面：最后一步仍由手机上的人点「确定」
            app.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return ok("已在手机上弹出系统卸载确认", null);
        } catch (Exception e) {
            return fail("弹不出卸载界面：" + e.getClass().getSimpleName());
        }
    }

    /**
     * ★ sec-1123 M2：跑的是 {@link PcRx#handle} 检查过的那份字节（{@link PcRx#checkedScript}）：先写进 App 私有目录（别的 App 读写不了），
     * 再让特权通道 sh 这份私有副本，跑完删掉。不再按文件名回外部存储重读（老系统上查完到运行之间可能被换掉）。
     */
    PcLanClient.Result scriptRun(String name, byte[] checked) {
        if (!name.toLowerCase(Locale.ROOT).endsWith(".sh")) return fail("手机上只能运行 .sh 脚本");
        if (checked == null) return fail("工作区 scripts/ 里没有这个脚本"); // 没查过的字节一律不跑（文件不在时 PcRx 不给字节）
        java.io.File dir = com.onesay.assistant.v2.util.PrivateScratch.dir(app, "pcrun");
        if (dir == null) return fail("手机上没有可用的私有目录，脚本没运行");
        java.io.File copy = new java.io.File(dir, "run_" + Long.toHexString(System.nanoTime()) + ".sh");
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(copy);
            try {
                out.write(checked);
            } finally {
                out.close();
            }
            return shell("sh " + com.onesay.assistant.v2.util.PrivateScratch.quote(copy.getPath()));
        } catch (Exception e) {
            return fail("脚本没运行：写不进私有目录（" + e.getClass().getSimpleName() + "）");
        } finally {
            try {
                copy.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 走特权通道执行（判定 / 确认已做完）；没有特权通道就说清楚。输出最多回 2000 字。 */
    PcLanClient.Result shell(String command) {
        try {
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r = new com.onesay.assistant.v2.executor.ShellExecutor(app).execute(command);
            if (r == null || r.isDown) return fail("手机上没有可用的特权通道（root 守护没在运行）");
            if (r.isDenied) return fail("特权通道拒绝：" + r.denyReason);
            if (r.isTimeout) return fail("执行超时");
            Map<String, Object> d = new TreeMap<String, Object>();
            d.put("rc", Long.valueOf(r.rc));
            d.put("output", clip(r.output, 2000));
            return r.isSuccess() ? ok("已执行", d) : new PcLanClient.Result(false, "执行失败（rc=" + r.rc + "）", d, "");
        } catch (Throwable t) {
            return fail("执行不了：" + t.getClass().getSimpleName());
        }
    }

    // ================================================================== 敏感剪贴板

    static final class Clip {
        private static final Handler H = new Handler(Looper.getMainLooper());
        private static int gen;
        private static boolean ours;
        private static ClipboardManager.OnPrimaryClipChangedListener lis;

        /** Android 13+ 的 EXTRA_IS_SENSITIVE（老系统忽略，常量直接写字符串，targetSdk 28 也能编）。 */
        static void markSensitive(ClipData cd) {
            try {
                android.os.PersistableBundle b = new android.os.PersistableBundle();
                b.putBoolean("android.content.extra.IS_SENSITIVE", true);
                cd.getDescription().setExtras(b);
            } catch (Throwable ignored) {
            }
        }

        /** 主线程调用。clear = 60 秒后清掉（敏感内容且开了「自动清除」时）——期间如果剪贴板被别人改过就不清。 */
        static void set(final ClipboardManager cm, ClipData cd, boolean clear) {
            if (lis == null) {
                lis = new ClipboardManager.OnPrimaryClipChangedListener() {
                    @Override public void onPrimaryClipChanged() {
                        if (ours) ours = false;
                        else gen++;
                    }
                };
                cm.addPrimaryClipChangedListener(lis);
            }
            ours = true;
            gen++;
            cm.setPrimaryClip(cd);
            if (!clear) return;
            final int mine = gen;
            H.postDelayed(new Runnable() {
                @Override public void run() {
                    if (gen != mine) return;
                    try {
                        ours = true;
                        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip();
                        else cm.setPrimaryClip(ClipData.newPlainText("", ""));
                        Log.i(TAG, "[PCLINK] 敏感剪贴板已清除");
                    } catch (Throwable ignored) {
                    }
                }
            }, LinkPolicy.CLIP_SENSITIVE_CLEAR_MS);
        }
    }

    // ================================================================== 找手机

    static final class Ring {
        private static MediaPlayer mp;
        private static int savedAlarm = -1;
        private static final Handler H = new Handler(Looper.getMainLooper());
        private static BroadcastReceiver stopRx;
        private static final Runnable AUTO_STOP = new Runnable() {
            @Override public void run() {
                if (appRef != null) stop(appRef);
            }
        };
        private static Context appRef;

        static synchronized boolean start(final Context app, int seconds) {
            stop(app);
            appRef = app;
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            Uri u = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (u == null) return false;
            try {
                savedAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            } catch (Exception e) {
                savedAlarm = -1; // 勿扰等原因改不了音量：照样按当前闹钟音量响
            }
            try {
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                mp.setDataSource(app, u);
                mp.setLooping(true);
                mp.prepare();
                mp.start();
            } catch (Exception e) {
                Log.w(TAG, "[PCLINK] 找手机放不了铃声: " + e.getClass().getSimpleName());
                release(app);
                return false;
            }
            try {
                Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(new long[]{0, 800, 600}, 0);
            } catch (Exception ignored) {
            }
            try {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "onesay:pclink-ring");
                wl.acquire(Math.min(seconds, 60) * 1000L);
            } catch (Exception ignored) {
            }
            try {
                if (stopRx == null) {
                    stopRx = new BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent i) { stop(c.getApplicationContext()); }
                    };
                    app.registerReceiver(stopRx, new IntentFilter(ACTION_STOP));
                }
                PendingIntent pi = PendingIntent.getBroadcast(app, 0, new Intent(ACTION_STOP).setPackage(app.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification n = builder(app).setSmallIcon(app.getApplicationInfo().icon).setContentTitle("电脑在找这台手机")
                        .setContentText("点这里停止响铃").setContentIntent(pi).setDeleteIntent(pi).setOngoing(true)
                        .setCategory(Notification.CATEGORY_ALARM).build();
                channel(app).notify(NID_RING, n);
            } catch (Exception ignored) {
            }
            H.removeCallbacks(AUTO_STOP);
            H.postDelayed(AUTO_STOP, seconds * 1000L);
            Log.i(TAG, "[PCLINK] 找手机：响铃 " + seconds + " 秒");
            return true;
        }

        static synchronized boolean stop(Context app) {
            boolean was = mp != null;
            H.removeCallbacks(AUTO_STOP);
            release(app);
            return was;
        }

        private static void release(Context app) {
            if (mp != null) {
                try { mp.stop(); } catch (Exception ignored) { }
                try { mp.release(); } catch (Exception ignored) { }
                mp = null;
            }
            try {
                Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.cancel();
            } catch (Exception ignored) {
            }
            if (savedAlarm >= 0) {
                try {
                    ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarm, 0);
                } catch (Exception ignored) {
                }
                savedAlarm = -1;
            }
            try {
                ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NID_RING);
            } catch (Exception ignored) {
            }
        }
    }
}
