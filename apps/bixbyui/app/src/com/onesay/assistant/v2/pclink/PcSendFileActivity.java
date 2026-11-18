package com.onesay.assistant.v2.pclink;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * [overnight-1119] 系统分享菜单里的「OneSay · 发到电脑」：别的 App 分享一个或几个文件（照片、文档、安装包…）过来 → 分块发到电脑，
 * 电脑放在 OneSay 工作区「来自手机」（不自动打开、不执行）。分享的是一段文字时存成 .txt 发过去。
 * 控制台「发文件到电脑」也走这里（{@link #ACTION_PICK}：先弹系统选文件）。透明页面，交出去就关，进度看通知栏。
 */
public final class PcSendFileActivity extends Activity {

    public static final String ACTION_PICK = "com.onesay.assistant.v2.pclink.SEND_FILE_PICK";
    static final int REQ_PICK = 4711;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!PcLink.active(this)) {
            Toast.makeText(this, "需要连上电脑：先在 OneSay「电脑联动」里扫码配对", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Intent in = getIntent();
        if (in != null && ACTION_PICK.equals(in.getAction())) {
            Intent p = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(Intent.createChooser(p, "选要发到电脑的文件"), REQ_PICK);
            } catch (Exception e) {
                Toast.makeText(this, "这台手机没有选文件的界面", Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }
        List<Uri> uris = uris(in);
        if (uris.isEmpty()) {
            CharSequence t = in == null ? null : in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null && t.length() > 0) {
                sendText(t.toString());
            } else {
                Toast.makeText(this, "没有可发的文件", Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }
        send(uris);
        finish();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            List<Uri> l = new ArrayList<Uri>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri u = data.getClipData().getItemAt(i).getUri();
                    if (u != null) l.add(u);
                }
            } else if (data.getData() != null) {
                l.add(data.getData());
            }
            if (!l.isEmpty()) send(l);
        }
        finish();
    }

    @SuppressWarnings("deprecation")
    static List<Uri> uris(Intent in) {
        List<Uri> l = new ArrayList<Uri>();
        if (in == null) return l;
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            Object u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u instanceof Uri) l.add((Uri) u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> x = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (x != null) for (Uri u : x) if (u != null) l.add(u);
        }
        return l;
    }

    void send(final List<Uri> uris) {
        final android.content.Context app = getApplicationContext();
        Toast.makeText(this, uris.size() == 1 ? "正在发到电脑，进度看通知栏" : "正在把 " + uris.size() + " 个文件发到电脑，进度看通知栏", Toast.LENGTH_SHORT).show();
        sendNext(app, uris, 0);
    }

    /** 一个一个发（同一时间只占一条连接）。 */
    static void sendNext(final android.content.Context app, final List<Uri> uris, final int i) {
        if (i >= uris.size()) return;
        PcFileAndroid.sendUri(app, uris.get(i), null, new PcFileAndroid.Done() {
            @Override public void done(final PcLanClient.Result r) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        if (!r.ok) Toast.makeText(app, r.text, Toast.LENGTH_LONG).show();
                    }
                });
                if (r.ok || r.reached()) sendNext(app, uris, i + 1); // 电脑不在线就不接着试了
            }
        });
    }

    void sendText(final String text) {
        final android.content.Context app = getApplicationContext();
        Toast.makeText(this, "把这段文字存成文本发到电脑", Toast.LENGTH_SHORT).show();
        com.onesay.assistant.v2.util.Threads.start("pclink-text-send", new Runnable() {
            @Override public void run() {
                java.io.File f = new java.io.File(app.getCacheDir(), "to_pc-text-" + System.currentTimeMillis() + ".txt");
                try {
                    java.io.OutputStream o = new java.io.FileOutputStream(f);
                    try {
                        o.write(text.getBytes("UTF-8"));
                    } finally {
                        o.close();
                    }
                    String name = "手机分享-" + new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".txt";
                    PcFileAndroid.sendFile(app, f, name);
                } catch (Exception ignored) {
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        });
    }
}
