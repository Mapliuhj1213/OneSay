package com.onesay.assistant.v2.pclink;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * [pclink] 链接下载的 Android 胶水：选清晰度的小对话框（分享菜单 / 控制台共用）、发 media_get、通知栏显示电脑上的下载进度、
 * 完成后可选传回手机（≤ 5 MB，存到 Movies / Music 下的 OneSay；没有存储权限就存 App 私有目录）。
 * 电脑没连上：只提示「需要连上电脑」，手机本地不跑 yt-dlp。日志只打类别 / 结果，不打链接内容。
 */
public final class PcMediaAndroid {

    static final String TAG = "PcMedia";
    static final int NID_MEDIA = 47880;
    static final long POLL_MS = 2000L, FOLLOW_MAX_MS = 3L * 3600_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PcMediaAndroid() {}

    /** 选清晰度 / 仅音频 / 是否传回手机，然后发给电脑。url 为 null 时先让你粘贴链接。 */
    public static void chooser(final Activity act, String url, final Runnable after) {
        final Context app = act.getApplicationContext();
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("https://…");
        if (url != null) et.setText(url);
        box.addView(et);
        final RadioGroup rg = new RadioGroup(act);
        final String[][] opts = {{PcMedia.Q_BEST, "", "最佳清晰度"}, {PcMedia.Q_1080, "", "1080p"}, {PcMedia.Q_720, "", "720p"},
                {PcMedia.Q_BEST, "m4a", "仅音频（m4a）"}, {PcMedia.Q_BEST, "mp3", "仅音频（mp3）"}};
        for (int i = 0; i < opts.length; i++) {
            RadioButton rb = new RadioButton(act);
            rb.setId(1000 + i);
            rb.setText(opts[i][2]);
            rg.addView(rb);
        }
        rg.check(1000);
        box.addView(rg);
        final CheckBox back = new CheckBox(act);
        back.setText("下载完传回手机（5 MB 以内；大了留在电脑上）");
        box.addView(back);
        TextView tip = new TextView(act);
        tip.setText("在电脑上用 yt-dlp 下载到电脑的下载文件夹。第一次要你在电脑或这台手机弹出的确认卡上点一下。带 DRM 的内容下载不了。");
        tip.setTextSize(12);
        box.addView(tip);
        new AlertDialog.Builder(act).setTitle("下到电脑").setView(box)
                .setPositiveButton("下载", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String u = PcMedia.tidy(et.getText().toString());
                        String bad = u == null ? "没找到 http / https 链接" : PcMedia.checkUrl(u);
                        if (bad != null) {
                            Toast.makeText(app, bad, Toast.LENGTH_LONG).show();
                        } else {
                            int k = Math.max(0, Math.min(opts.length - 1, rg.getCheckedRadioButtonId() - 1000));
                            start(app, u, opts[k][0], opts[k][1], back.isChecked());
                        }
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { if (after != null) after.run(); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) { if (after != null) after.run(); }
                }).show();
    }

    /** 后台：先看电脑在不在 → 发 media_get（电脑上可能要确认，多等一会儿）→ 跟进度直到结束。 */
    public static void start(final Context c, final String url, final String quality, final String audio, final boolean fetchBack) {
        final Context app = c.getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("pclink-media", new Runnable() {
            @Override public void run() {
                try {
                    runSync(app, url, quality, audio, fetchBack);
                } catch (Throwable t) {
                    Log.w(TAG, "[PCLINK] 链接下载异常: " + t.getClass().getSimpleName());
                    note(app, 0, "下到电脑", "出错了：" + t.getClass().getSimpleName(), -1, false);
                }
            }
        });
    }

    /** 同步版（必须在后台线程）：返回给人看的第一句话（发出去了 / 需要连上电脑 / 被拒）。进度和结果走通知。 */
    public static String runSync(final Context app, String url, String quality, String audio, final boolean fetchBack) {
        if (!PcLink.active(app)) return toast(app, "需要连上电脑：先在「电脑联动」里扫码配对");
        PcLanClient.Result ping = PcLink.runNow(app, "ping", null, 1500, 4000);
        if (!ping.reached()) {
            String t = "auth".equals(ping.fail) ? "电脑拒绝了连接，配对可能失效了，请重新扫码" : "需要连上电脑：局域网里没找到 " + PcLink.pcName(app) + "（电脑开着 OneSay、和手机在同一个 Wi-Fi 吗？）";
            note(app, 0, "下到电脑", t, -1, false);
            return toast(app, t);
        }
        note(app, 0, "下到电脑", "已发给电脑，可能要你在电脑或手机弹出的确认卡上点一下…", -1, true);
        PcLanClient.Result r = PcLink.runNow(app, PcMedia.GET, PcMedia.args(url, quality, audio), 2000, PcLinkUi.MAY_CONFIRM_WAIT_MS);
        if (!r.ok) {
            Object why = r.data.get("reason_zh");
            String t = (r.text.length() > 0 ? r.text : "电脑没接") + (why != null && !r.text.contains(String.valueOf(why)) ? "（" + why + "）" : "");
            note(app, 0, "下到电脑：没开始", t, -1, false);
            Log.i(TAG, "[PCLINK] media_get → fail " + r.fail);
            return toast(app, t);
        }
        Object idObj = r.data.get("id");
        final int id = idObj instanceof Number ? ((Number) idObj).intValue() : -1;
        cancelNote(app, 0);
        Log.i(TAG, "[PCLINK] media_get → 已排队 #" + id);
        if (id < 0) return toast(app, r.text);
        PcMedia.follow(new PcMedia.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args, int timeoutMs) {
                return PcLink.runNow(app, cmd, args, 1500, timeoutMs);
            }
        }, new PcMedia.Sink() {
            @Override public void progress(int i, String title, int percent, String line) {
                note(app, i, title.length() > 0 ? title : "下到电脑", line, percent, true);
            }

            @Override public void done(int i, String title, boolean ok, String text) {
                note(app, i, (ok ? "已下到电脑：" : "下到电脑：") + (title.length() > 0 ? title : ""), text, -1, false);
            }
        }, new PcMedia.Saver() {
            @Override public String save(String name, byte[] data, boolean audioFile) { return saveFile(app, name, data, audioFile); }
        }, id, fetchBack, FOLLOW_MAX_MS, POLL_MS, new PcMedia.Sleeper() {
            @Override public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
        });
        return r.text;
    }

    static String toast(final Context app, final String t) {
        MAIN.post(new Runnable() {
            @Override public void run() { Toast.makeText(app, t, Toast.LENGTH_LONG).show(); }
        });
        return t;
    }

    /** 进度通知：percent ≥ 0 显示进度条，-1 不显示；ongoing = 进行中（不能划掉）。 */
    @SuppressWarnings("deprecation")
    static void note(Context app, int id, String title, String text, int percent, boolean ongoing) {
        try {
            Notification.Builder b = PcRxAndroid.builder(app).setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true);
            if (percent >= 0) b.setProgress(100, percent, false);
            if (!ongoing) b.setSmallIcon(android.R.drawable.stat_sys_download_done);
            PcRxAndroid.channel(app).notify(NID_MEDIA + (id < 0 ? 0 : id % 50), b.build());
        } catch (Throwable t) {
            Log.w(TAG, "[PCLINK] 通知失败: " + t.getClass().getSimpleName());
        }
    }

    static void cancelNote(Context app, int id) {
        try {
            PcRxAndroid.channel(app).cancel(NID_MEDIA + id % 50);
        } catch (Throwable ignored) {
        }
    }

    /** 传回来的文件：有存储权限 → Movies/OneSay 或 Music/OneSay（并入媒体库）；否则 App 私有目录。返回给人看的位置，失败 null。 */
    static String saveFile(Context app, String name, byte[] data, boolean audio) {
        String n = PcMedia.safeName(name);
        boolean pub = app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        String type = audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES;
        File dir = pub ? new File(Environment.getExternalStoragePublicDirectory(type), "OneSay") : new File(app.getExternalFilesDir(type), "pclink");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            dir = new File(app.getFilesDir(), "pclink");
            dir.mkdirs();
            pub = false;
        }
        File f = new File(dir, n);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(data);
        } catch (Exception e) {
            Log.w(TAG, "[PCLINK] 存传回的文件失败: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
        if (pub) MediaScannerConnection.scanFile(app, new String[]{f.getAbsolutePath()}, null, null);
        Log.i(TAG, "[PCLINK] 传回手机 bytes=" + data.length + (pub ? " 公共目录" : " 私有目录"));
        return pub ? (audio ? "音乐" : "视频") + "/OneSay/" + n : "App 私有目录 " + f.getAbsolutePath();
    }
}
