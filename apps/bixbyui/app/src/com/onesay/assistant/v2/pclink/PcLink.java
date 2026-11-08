package com.onesay.assistant.v2.pclink;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.notice.IslandNotice;
import com.onesay.assistant.v2.security.SecretPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [pclink] 电脑联动的 Android 胶水：配对信息（密钥经 {@link SecretPrefs} 存 Keystore 密文）、下令（后台单线程）、
 * 提醒转发、截图落盘。协议本身在纯 Java 的 {@link PcLanClient} / {@link PcProtocol}。
 *
 * <p>没配对 = 什么都不做、不联网。配对后也只在：控制台「电脑联动」页开着时轮询在线状态（10 秒一次，页面关掉 / 退到后台就停）、
 * 用户点按钮或说口令时、提醒中枢有新提醒要转发时 —— 才去局域网找电脑，用完即断，不常驻连接。
 * 日志只打类别 / 结果 / 长度，不打密钥、配对串、提醒内容。
 */
public final class PcLink {

    static final String TAG = "PcLink";
    public static final String PREFS = "bixby_pclink";
    public static final String KILL = "pclink.off";
    static final String K_ME = "me", K_PEER = "peer", K_KEY = "pair_key", K_HOSTS = "lan_hosts", K_PORT = "lan_port", K_LAST = "lan_last",
            K_NAME = "pc_name", K_PAIRED_AT = "paired_at", K_LAST_SHOT = "last_shot", K_LAST_SHOT_URI = "last_shot_uri";
    /** v2：电脑证书指纹（配对串 fp，空 = v1 明文）；本机 TLS 私钥（SecretPrefs 加密）与证书。 */
    static final String K_FP = "pc_fp", K_TLS_KEY = "tls_key", K_TLS_CERT = "tls_cert";
    public static final String K_FWD_REMINDER = "fwd_reminder", K_FWD_AUTO = "fwd_auto", K_FWD_RAIN = "fwd_rain";

    /** 总开关（插件默认开；没配对时本来就什么都不做）。 */
    public static final FeatureSwitch SWITCH = new FeatureSwitch(PREFS, "enabled", true, KILL);

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(com.onesay.assistant.v2.util.Threads.namedFactory("pclink"));
    private static final PcForward.Dedup DEDUP = new PcForward.Dedup();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PcLink() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ================================================================== 配对信息

    public static boolean paired(Context c) {
        SharedPreferences s = sp(c);
        return s.getString(K_PEER, "").length() > 0 && s.getString(K_KEY, "").length() > 0;
    }

    public static boolean active(Context c) {
        return SWITCH.on(c) && paired(c);
    }

    public static String peerId(Context c) {
        return sp(c).getString(K_PEER, "");
    }

    /** 本机 id（第一次用时生成 phone-xxxxxx，之后不变；解除配对也保留）。 */
    public static String me(Context c) {
        SharedPreferences s = sp(c);
        String me = s.getString(K_ME, "");
        if (!PcPairing.validId(me)) {
            byte[] b = new byte[3];
            new SecureRandom().nextBytes(b);
            me = String.format(Locale.ROOT, "phone-%02x%02x%02x", b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF);
            s.edit().putString(K_ME, me).apply();
        }
        return me;
    }

    public static String pcName(Context c) {
        String n = sp(c).getString(K_NAME, "");
        return n.length() > 0 ? n : peerId(c);
    }

    public static long pairedAt(Context c) {
        return sp(c).getLong(K_PAIRED_AT, 0);
    }

    /** 保存配对（不合法抛 IllegalArgumentException，消息可直接给人看）。 */
    public static PcPairing pair(Context c, String raw) {
        PcPairing p = PcPairing.parse(raw);
        me(c);
        SharedPreferences.Editor e = sp(c).edit();
        SecretPrefs.put(e, K_KEY, PcB64.encodeUrl(p.key));
        e.putString(K_PEER, p.peer).putString(K_HOSTS, p.lanHosts).putInt(K_PORT, p.lanPort).putString(K_LAST, "").putString(K_NAME, "")
                .putString(K_FP, p.fp).putLong(K_PAIRED_AT, System.currentTimeMillis()).commit();
        if (p.fp.length() > 0) myTls(c); // 本机证书第一次要用时生成（之后不变）
        Log.i(TAG, "[PCLINK] 已配对 " + p.redacted());
        PcRxAndroid.ensure(c);
        return p;
    }

    public static void unpair(Context c) {
        sp(c).edit().remove(K_KEY).remove(K_PEER).remove(K_HOSTS).remove(K_PORT).remove(K_LAST).remove(K_NAME).remove(K_PAIRED_AT).remove(K_FP).commit();
        PcRxAndroid.ensure(c); // 让常连循环看到「没配对」后自己停
        Log.i(TAG, "[PCLINK] 已解除配对");
    }

    /** 电脑证书指纹（v1 配对为空串）。 */
    public static String pcFp(Context c) {
        return sp(c).getString(K_FP, "");
    }

    private static PcTls sTls;

    /** 本机 TLS 证书：没有就生成（私钥经 SecretPrefs 加密保存）。解不开（换机还原 / Keystore 清过）就重新生成——电脑会因指纹不同拒绝，界面会提示怎么办。 */
    static synchronized PcTls myTls(Context c) {
        if (sTls != null) return sTls;
        SharedPreferences s = sp(c);
        PcTls t = PcTls.load(SecretPrefs.get(s, K_TLS_KEY), s.getString(K_TLS_CERT, ""));
        if (t == null) {
            t = PcTls.generate("onesay-" + me(c));
            SharedPreferences.Editor e = s.edit();
            SecretPrefs.put(e, K_TLS_KEY, t.keyB64());
            e.putString(K_TLS_CERT, t.certB64()).commit();
            Log.i(TAG, "[PCLINK] 生成本机证书 " + PcTls.shortFp(t.fp));
        }
        sTls = t;
        return t;
    }

    /** 下令用的身份；没配对 / 密钥解不开返回 null。 */
    static PcLanClient.Peer peer(Context c) {
        SharedPreferences s = sp(c);
        String peer = s.getString(K_PEER, "");
        if (peer.length() == 0) return null;
        String k = SecretPrefs.get(s, K_KEY);
        if (k.length() == 0) return null;
        byte[] key;
        try {
            key = PcB64.decode(k);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (key.length < 32) return null;
        String fp = s.getString(K_FP, "");
        return new PcLanClient.Peer(me(c), peer, key, s.getString(K_HOSTS, ""), s.getInt(K_PORT, PcPairing.DEFAULT_PORT), s.getString(K_LAST, ""),
                fp, fp.length() > 0 ? myTls(c) : null);
    }

    /** 配对了但密钥解不开（换机还原 / Keystore 被清）。 */
    public static boolean keyLost(Context c) {
        return paired(c) && peer(c) == null;
    }

    public static boolean fwdOn(Context c, String kind) {
        String k = PcForward.KIND_REMINDER.equals(kind) ? K_FWD_REMINDER : PcForward.KIND_AUTO.equals(kind) ? K_FWD_AUTO : K_FWD_RAIN;
        return sp(c).getBoolean(k, true);
    }

    public static void setFwd(Context c, String key, boolean on) {
        sp(c).edit().putBoolean(key, on).apply();
    }

    // ================================================================== 下令

    public interface Done {
        void done(PcLanClient.Result r);
    }

    /** Wi-Fi 的广播地址（部分 ROM 的 NetworkInterface 不给 broadcast）。 */
    static List<InetAddress> wifiBroadcast(Context c) {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo d = wm == null ? null : wm.getDhcpInfo();
            if (d != null && d.ipAddress != 0 && d.netmask != 0) {
                int bc = (d.ipAddress & d.netmask) | ~d.netmask;
                byte[] q = new byte[4];
                for (int i = 0; i < 4; i++) q[i] = (byte) ((bc >> (i * 8)) & 0xFF);
                out.add(InetAddress.getByAddress(q));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 同步下令（必须在后台线程）。没配对 / 关着返回 failure。 */
    public static PcLanClient.Result runNow(Context c, String cmd, Map<String, Object> args, long findMs, int timeoutMs) {
        Context app = c.getApplicationContext();
        if (!SWITCH.on(app)) return failure("off", "电脑联动已关闭");
        PcLanClient.Peer p = peer(app);
        if (p == null) return failure("unpaired", paired(app) ? "配对信息解不开了，请重新扫码配对" : "还没配对电脑");
        InetSocketAddress[] found = new InetSocketAddress[1];
        PcLanClient.Result r = PcLanClient.once(p, cmd, args, findMs, timeoutMs, wifiBroadcast(app), found);
        if (found[0] != null && r.reached()) {
            String last = found[0].getAddress().getHostAddress() + ":" + found[0].getPort();
            if (!last.equals(p.lanLast)) sp(app).edit().putString(K_LAST, last).apply();
        }
        Log.i(TAG, "[PCLINK] " + cmd + " → " + (r.ok ? "ok" : "fail") + (r.fail.length() > 0 ? "(" + r.fail + ")" : "") + " textLen=" + r.text.length());
        return r;
    }

    static PcLanClient.Result failure(String fail, String text) {
        return new PcLanClient.Result(false, text, null, fail);
    }

    /** 后台下令，结果回主线程。 */
    public static void run(final Context c, final String cmd, final Map<String, Object> args, final int timeoutMs, final Done done) {
        final Context app = c.getApplicationContext();
        EXEC.execute(new Runnable() {
            @Override public void run() {
                PcLanClient.Result r;
                try {
                    r = runNow(app, cmd, args, 2000, timeoutMs);
                } catch (Throwable t) {
                    r = failure("error", "出错：" + t.getClass().getSimpleName());
                }
                final PcLanClient.Result fr = r;
                if (done != null) MAIN.post(new Runnable() {
                    @Override public void run() { done.done(fr); }
                });
            }
        });
    }

    /** 在线探测（页面轮询用）：ping；第一次在线时顺带取电脑名（info）。后台线程调用。 */
    public static PcLanClient.Result probe(Context c) {
        Context app = c.getApplicationContext();
        PcLanClient.Result r = runNow(app, "ping", null, 1500, 4000);
        if (r.ok && sp(app).getString(K_NAME, "").length() == 0) {
            PcLanClient.Result info = runNow(app, "info", null, 1500, 4000);
            Object host = info.ok ? info.data.get("host") : null;
            if (host != null && String.valueOf(host).length() > 0) sp(app).edit().putString(K_NAME, String.valueOf(host)).apply();
        }
        return r;
    }

    // ================================================================== 提醒转发

    /** 提醒中枢新出现的提醒（任意线程）：电脑在线就发 notify，离线丢弃、不排队。 */
    public static void onNoticesSurfaced(Context c, List<IslandNotice> surfaced) {
        if (surfaced == null || surfaced.isEmpty()) return;
        final Context app = c.getApplicationContext();
        if (!active(app)) return;
        for (final IslandNotice n : surfaced) {
            final String kind = PcForward.kindOf(n);
            if (kind == null || !fwdOn(app, kind)) continue;
            if (!DEDUP.first(n.dedupKey)) continue;
            final Map<String, Object> args = PcForward.args(kind, n);
            EXEC.execute(new Runnable() {
                @Override public void run() {
                    try {
                        PcLanClient.Result r = runNow(app, "notify", args, 1500, 5000);
                        Log.i(TAG, "[PCLINK] 转发提醒 kind=" + kind + " → " + (r.ok ? "已送达" : r.fail.equals("offline") ? "电脑离线，丢弃" : "失败(" + r.fail + ")"));
                    } catch (Throwable t) {
                        Log.w(TAG, "[PCLINK] 转发异常: " + t.getClass().getSimpleName());
                    }
                }
            });
        }
    }

    // ================================================================== 截图

    /** 截图结果存成 JPEG：有存储权限 → 相册 Pictures/OneSay（并入媒体库，可分享）；否则 App 私有图片目录。返回文件，失败 null。 */
    public static File saveShot(Context c, PcLanClient.Result r) {
        Context app = c.getApplicationContext();
        Object b64 = r.data.get("b64");
        if (b64 == null) return null;
        byte[] jpg;
        try {
            jpg = PcB64.decode(String.valueOf(b64));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String name = "pc-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".jpg";
        File dir;
        boolean pub = app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (pub) dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "OneSay");
        else dir = new File(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "pclink");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            dir = new File(app.getFilesDir(), "pclink");
            dir.mkdirs();
            pub = false;
        }
        File f = new File(dir, name);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(jpg);
        } catch (Exception e) {
            Log.w(TAG, "[PCLINK] 截图写文件失败: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
        final SharedPreferences s = sp(app);
        s.edit().putString(K_LAST_SHOT, f.getAbsolutePath()).putString(K_LAST_SHOT_URI, "").apply();
        if (pub) {
            MediaScannerConnection.scanFile(app, new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"},
                    new MediaScannerConnection.OnScanCompletedListener() {
                        @Override public void onScanCompleted(String path, Uri uri) {
                            if (uri != null && path.equals(s.getString(K_LAST_SHOT, ""))) s.edit().putString(K_LAST_SHOT_URI, uri.toString()).apply();
                        }
                    });
        }
        Log.i(TAG, "[PCLINK] 截图已存 " + (pub ? "相册/OneSay" : "私有目录") + " bytes=" + jpg.length);
        return f;
    }

    public static File lastShot(Context c) {
        String p = sp(c).getString(K_LAST_SHOT, "");
        if (p.length() == 0) return null;
        File f = new File(p);
        return f.isFile() ? f : null;
    }

    /** 上次截图在媒体库里的 content:// 地址（可分享）；没有返回 null。 */
    public static Uri lastShotUri(Context c) {
        String u = sp(c).getString(K_LAST_SHOT_URI, "");
        return u.length() == 0 ? null : Uri.parse(u);
    }

    public static String status(Context c) {
        if (!SWITCH.on(c)) return "off";
        if (!paired(c)) return "unpaired";
        return "paired peer=" + peerId(c) + " me=" + me(c) + (keyLost(c) ? " keyLost" : "") + (pcFp(c).length() > 0 ? " tls=" + PcTls.shortFp(pcFp(c)) : " tls=none")
                + " rx=" + PcRxAndroid.rxOn(c) + (PcRxAndroid.up() ? "/up" : "") + " hosts=" + sp(c).getString(K_HOSTS, "").split(",").length
                + " fwd=" + fwdOn(c, PcForward.KIND_REMINDER) + "/" + fwdOn(c, PcForward.KIND_AUTO) + "/" + fwdOn(c, PcForward.KIND_RAIN);
    }
}
