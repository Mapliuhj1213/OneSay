package com.onesay.clock.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.onesay.clock.L;
import com.onesay.clock.Prefs;
import com.onesay.clock.core.Endpoint;
import com.onesay.clock.core.Flags;
import com.onesay.clock.voice.CosyVoice;
import com.onesay.clock.voice.PhraseVoice;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 「API 密钥」面板（OneSay 开源版）：每个服务一行，显示状态（未配置 / 已配置 / 测试通过 / 失败）、粘贴框（密文显示、
 * 保存后不回显全文）、测试按钮、一句话说明解锁什么、「去申请」跳官方控制台；标明必填 / 可选。
 * 窗口与对话框都加 FLAG_SECURE（不进最近任务截图、不能截屏）；保存后若剪贴板里就是这把 Key 则清空剪贴板；日志只记长度。
 */
public class KeysActivity extends Activity {
    static final String URL_DASHSCOPE = "https://bailian.console.aliyun.com/";
    static final String URL_QWEATHER = "https://console.qweather.com/";

    private Prefs p;
    private View rowTtsKey, rowEndpoint, rowVoice, rowModel, rowTtsTest, rowQwKey, rowQwHost, rowQwTest, rowPrebuild;
    private volatile String prebuildStatus = "";
    private volatile PhraseVoice prebuilding;
    private String ttsStatus = "", qwStatus = "";
    private final Handler h = new Handler();
    private MediaPlayer mp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        Ui.window(this);
        p = new Prefs(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Ui.dp(this, 24));
        col.addView(Ui.header(this, "API 密钥", "Key 只存在本机（系统密钥库加密），只发往你填写的服务地址"));

        col.addView(Ui.section(this, "云端语音（必填 · 未配置则不朗读）"));
        LinearLayout c1 = Ui.card(this);
        rowEndpoint = Ui.add(c1, Ui.row(this, "服务地址", "", null, click(new Runnable() { public void run() { editEndpoint(); } })));
        rowTtsKey = Ui.add(c1, Ui.row(this, "API Key", "", null, click(new Runnable() { public void run() { editSecret(true); } })));
        rowVoice = Ui.add(c1, Ui.row(this, "音色", "", null, click(new Runnable() { public void run() { pickVoice(); } })));
        rowModel = Ui.add(c1, Ui.row(this, "模型", "", null, click(new Runnable() { public void run() { editModel(); } })));
        rowTtsTest = Ui.add(c1, Ui.row(this, "测试连通性", "", null, click(new Runnable() { public void run() { testTts(); } })));
        Ui.add(c1, Ui.row(this, "试听", "用所选音色合成一句示例", null, click(new Runnable() { public void run() { preview(); } })));
        rowPrebuild = Ui.add(c1, Ui.row(this, "预合成朗读表", "", null, click(new Runnable() { public void run() { prebuild(); } })));
        Ui.add(c1, Ui.row(this, "去申请", "阿里云百炼控制台（DashScope 兼容接口；也可填其他兼容服务）", null,
                click(new Runnable() { public void run() { open(URL_DASHSCOPE); } })));
        col.addView(c1, Ui.cardLp(this));

        col.addView(Ui.section(this, "和风天气（可选 · 降雨提醒）"));
        LinearLayout c2 = Ui.card(this);
        rowQwKey = Ui.add(c2, Ui.row(this, "API Key", "", null, click(new Runnable() { public void run() { editSecret(false); } })));
        rowQwHost = Ui.add(c2, Ui.row(this, "API Host", "", null, click(new Runnable() { public void run() { editQwHost(); } })));
        rowQwTest = Ui.add(c2, Ui.row(this, "测试", "", null, click(new Runnable() { public void run() { testQw(); } })));
        Ui.add(c2, Ui.row(this, "去申请", "和风天气控制台：创建项目后得到 API Key 和 API Host", null,
                click(new Runnable() { public void run() { open(URL_QWEATHER); } })));
        col.addView(c2, Ui.cardLp(this));

        sv.addView(col);
        setContentView(sv);
        refresh();
    }

    @Override protected void onDestroy() {
        PhraseVoice pb = prebuilding;
        if (pb != null) pb.cancel();
        if (mp != null) { try { mp.release(); } catch (Throwable ignored) { } mp = null; }
        super.onDestroy();
    }

    static View.OnClickListener click(final Runnable r) {
        return new View.OnClickListener() { public void onClick(View v) { r.run(); } };
    }

    void refresh() {
        String k = p.bailianKey(), ep = p.ttsEndpoint(), vo = p.cosyVoice();
        Ui.setSub(rowEndpoint, ep.length() == 0 ? "未配置（只收 https:// 地址）" : ep);
        status(rowTtsKey, k.length() == 0 ? "未配置：不朗读" : "已配置（" + k.length() + " 位）", k.length() > 0);
        status(rowVoice, vo.length() == 0 ? "未选择：点这里拉取音色列表或手动填写" : vo, vo.length() > 0);
        Ui.setSub(rowModel, p.cosyModel());
        status(rowTtsTest, ttsStatus.length() == 0 ? "还没测试" : ttsStatus, ttsStatus.startsWith("测试通过"));
        String qk = p.qweatherKey(), qh = p.qweatherHost();
        status(rowQwKey, qk.length() == 0 ? "未配置：不播降雨提醒" : "已配置（" + qk.length() + " 位）", qk.length() > 0);
        status(rowQwHost, qh.length() == 0 ? "未配置" : qh, qh.length() > 0);
        status(rowQwTest, qwStatus.length() == 0 ? "还没测试" : qwStatus, qwStatus.startsWith("测试通过"));
        if (prebuildStatus.length() > 0) status(rowPrebuild, prebuildStatus, prebuildStatus.startsWith("完成"));
        else {
            int have = new PhraseVoice(this, false).cachedCount(), all = com.onesay.clock.core.Bank.all().size();
            status(rowPrebuild, "已缓存 " + have + "/" + all + " 件；全部合成后闹钟不联网也能报时报天气", have == all && all > 0);
        }
    }

    /** 服务地址 / 模型 / 音色变了：旧音色的缓存作废；第一次选好音色时主动问一次要不要预合成。 */
    void voiceConfigChanged(boolean voiceWasEmpty) {
        prebuildStatus = "";
        PhraseVoice.invalidateAll(this);
        refresh();
        if (voiceWasEmpty && p.cosyVoice().length() > 0 && p.bailianKey().length() > 0 && !p.flag("phrasePrebuildOffered")) {
            getSharedPreferences("bc", MODE_PRIVATE).edit().putBoolean("phrasePrebuildOffered", true).apply();
            secure(new AlertDialog.Builder(this).setTitle("预合成朗读表？")
                    .setMessage("用这个音色把报时、温度、天气、收尾等固定句一次合成到本机（约 " + com.onesay.clock.core.Bank.all().size()
                            + " 件，后台进行）。之后闹钟播报的固定部分不需要联网。")
                    .setPositiveButton("开始", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { prebuild(); } })
                    .setNegativeButton("以后再说", null).show());
        }
    }

    void prebuild() {
        if (p.bailianKey().length() == 0 || p.cosyVoice().length() == 0 || p.ttsEndpoint().length() == 0) { toast("先配好服务地址、Key 和音色"); return; }
        if (prebuilding != null) { toast("正在合成…"); return; }
        final PhraseVoice pv = new PhraseVoice(this, true);
        prebuilding = pv;
        prebuildStatus = "准备中…";
        refresh();
        new Thread(new Runnable() {
            public void run() {
                int fails = pv.prebuild(new PhraseVoice.Progress() {
                    public void on(final int done, final int total, final int f) {
                        if (done % 5 != 0 && done != total) return;
                        h.post(new Runnable() { public void run() { prebuildStatus = "合成中 " + done + "/" + total + (f > 0 ? "（失败 " + f + "）" : ""); if (!isFinishing()) refresh(); } });
                    }
                });
                final int ff = fails;
                h.post(new Runnable() {
                    public void run() {
                        prebuilding = null;
                        prebuildStatus = ff == 0 ? "完成：朗读表已全部缓存" : "部分失败（" + ff + " 件），再点一次只补缺的";
                        if (!isFinishing()) refresh();
                    }
                });
            }
        }, "keys-prebuild").start();
    }

    void status(View row, String s, boolean good) {
        Ui.setSub(row, s);
        Ui.setSubColor(row, good ? Ui.ACCENT : Ui.WARN);
    }

    AlertDialog secure(AlertDialog d) {
        if (d.getWindow() != null) d.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        return d;
    }

    void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("没有可打开网页的应用"); }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /** 保存后：剪贴板里正好是这把 Key 就清掉（能做到的范围内）。 */
    void clearClipIf(String saved) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return;
            CharSequence t = cd.getItemAt(0).coerceToText(this);
            if (t != null && t.toString().trim().equals(saved)) cm.setPrimaryClip(ClipData.newPlainText("", ""));
        } catch (Throwable ignored) { }
    }

    void editSecret(final boolean tts) {
        final EditText e = new EditText(this);
        e.setHint(tts ? "粘贴云端语音 API Key" : "粘贴和风天气 API Key");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setLongClickable(true);
        secure(new AlertDialog.Builder(this).setTitle(tts ? "云端语音 Key" : "和风天气 Key")
                .setMessage("保存后不再显示全文；留空保存 = 删除。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = e.getText().toString().trim();
                        if (tts) { p.setBailianKey(v); ttsStatus = ""; } else { p.setQweatherKey(v); qwStatus = ""; }
                        e.setText("");
                        if (v.length() > 0) clearClipIf(v);
                        L.i(KeysActivity.this, (tts ? "KEY" : "QW_KEY") + " set len=" + v.length());
                        refresh();
                    }
                }).setNegativeButton("取消", null).show());
    }

    void editEndpoint() {
        final EditText e = new EditText(this);
        e.setText(p.ttsEndpoint().length() == 0 ? Flags.TTS_ENDPOINT_DEF : p.ttsEndpoint());
        secure(new AlertDialog.Builder(this).setTitle("云端语音服务地址").setMessage("DashScope 兼容的语音合成接口，必须 https://。Key 只会发往这个地址的主机。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = e.getText().toString().trim();
                        String why = Endpoint.why(v);
                        if (why != null) { toast("没保存：" + why); return; }
                        p.setS(Flags.TTS_ENDPOINT, v); ttsStatus = ""; voiceConfigChanged(false);
                    }
                }).setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { p.setS(Flags.TTS_ENDPOINT, Flags.TTS_ENDPOINT_DEF); voiceConfigChanged(false); }
                }).setNegativeButton("取消", null).show());
    }

    void editModel() {
        final EditText e = new EditText(this);
        e.setText(p.cosyModel());
        secure(new AlertDialog.Builder(this).setTitle("模型").setMessage("音色必须属于这个模型（预置音色见服务商文档）。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String m = e.getText().toString().trim();
                        p.setS(Flags.COSY_MODEL, m.length() == 0 ? Flags.COSY_MODEL_DEF : m); voiceConfigChanged(false);
                    }
                }).setNegativeButton("取消", null).show());
    }

    void editQwHost() {
        final EditText e = new EditText(this);
        e.setHint("例如 abcxyz.re.qweatherapi.com");
        e.setText(p.qweatherHost());
        secure(new AlertDialog.Builder(this).setTitle("和风天气 API Host").setView(e)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { p.setQweatherHost(e.getText().toString()); qwStatus = ""; refresh(); }
                }).setNegativeButton("取消", null).show());
    }

    /** 音色：拉取列表（DashScope 兼容接口列出本账号的自定义音色）+ 手动填写（预置音色或不支持列表的服务）。 */
    void pickVoice() {
        final String[] it = {"拉取音色列表", "手动填写音色 ID", "清除"};
        secure(new AlertDialog.Builder(this).setTitle("音色").setItems(it, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) fetchVoices();
                else if (w == 1) manualVoice();
                else { p.setS(Flags.COSY_VOICE, ""); voiceConfigChanged(false); }
            }
        }).show());
    }

    void manualVoice() {
        final EditText e = new EditText(this);
        e.setHint("音色 ID（例如服务商文档里的预置音色）");
        e.setText(p.cosyVoice());
        secure(new AlertDialog.Builder(this).setTitle("手动填写音色 ID").setView(e)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { boolean was = p.cosyVoice().length() == 0; p.setS(Flags.COSY_VOICE, e.getText().toString().trim()); voiceConfigChanged(was); }
                }).setNegativeButton("取消", null).show());
    }

    void fetchVoices() {
        final String ep = p.ttsEndpoint(), key = p.bailianKey();
        if (ep.length() == 0 || key.length() == 0) { toast("先填服务地址和 API Key"); return; }
        toast("正在拉取…");
        new Thread(new Runnable() {
            public void run() {
                List<String> vs = null; String err = null;
                try { vs = CosyVoice.listVoices(ep, key, 12_000); } catch (Exception e) { err = e.getMessage(); }
                final List<String> fv = vs; final String fe = err;
                h.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (fe != null) { toast("拉取失败：" + fe); return; }
                        if (fv == null || fv.isEmpty()) { toast("账号里没有自定义音色；预置音色请手动填写"); manualVoice(); return; }
                        final String[] arr = fv.toArray(new String[0]);
                        secure(new AlertDialog.Builder(KeysActivity.this).setTitle("选择音色").setItems(arr, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { boolean was = p.cosyVoice().length() == 0; p.setS(Flags.COSY_VOICE, arr[w]); voiceConfigChanged(was); }
                        }).show());
                    }
                });
            }
        }, "keys-voices").start();
    }

    void testTts() {
        final String ep = p.ttsEndpoint(), key = p.bailianKey();
        if (ep.length() == 0 || key.length() == 0) { ttsStatus = "失败：服务地址或 Key 未配置"; refresh(); return; }
        ttsStatus = "测试中…"; refresh();
        new Thread(new Runnable() {
            public void run() {
                String s;
                try {
                    // 有音色：真合成一句（最可靠）；没音色：用列音色接口验证 Key
                    if (p.cosyVoice().length() > 0) {
                        byte[] b = new CosyVoice(ep, key, p.cosyModel(), p.cosyVoice()).synth("测试。", 1.0, 15_000);
                        s = b != null && b.length > 1000 ? "测试通过" : "失败：没拿到音频";
                    } else {
                        CosyVoice.listVoices(ep, key, 12_000);
                        s = "测试通过（Key 有效；还需选择音色）";
                    }
                } catch (Exception e) { s = "失败：" + e.getMessage(); }
                final String fs = s;
                h.post(new Runnable() { public void run() { ttsStatus = fs; if (!isFinishing()) refresh(); } });
            }
        }, "keys-tts-test").start();
    }

    void preview() {
        final String ep = p.ttsEndpoint(), key = p.bailianKey(), vo = p.cosyVoice();
        if (ep.length() == 0 || key.length() == 0 || vo.length() == 0) { toast("先配好服务地址、Key 和音色"); return; }
        toast("合成中…");
        new Thread(new Runnable() {
            public void run() {
                String err = null; File f = new File(getCacheDir(), "voice_preview.wav");
                try {
                    byte[] b = new CosyVoice(ep, key, p.cosyModel(), vo).synth("早上好，现在是七点整，今天多云。", 1.0, 20_000);
                    FileOutputStream o = new FileOutputStream(f);
                    try { o.write(b); } finally { o.close(); }
                } catch (Exception e) { err = e.getMessage(); }
                final String fe = err; final File ff = f;
                h.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (fe != null) { toast("试听失败：" + fe); return; }
                        try {
                            if (mp != null) mp.release();
                            mp = new MediaPlayer();
                            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
                            mp.setDataSource(ff.getPath());
                            mp.prepare();
                            mp.start();
                        } catch (Exception e) { toast("播放失败"); }
                    }
                });
            }
        }, "keys-preview").start();
    }

    void testQw() {
        final String key = p.qweatherKey(), host = p.qweatherHost();
        if (key.length() == 0 || host.length() == 0) { qwStatus = "失败：Key 或 Host 未配置"; refresh(); return; }
        qwStatus = "测试中…"; refresh();
        new Thread(new Runnable() {
            public void run() {
                String s;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://" + host + "/v7/weather/now?location=116.41,39.92").openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(8000); c.setReadTimeout(10000);
                    c.setRequestProperty("X-QW-Api-Key", key);
                    c.setRequestProperty("Accept-Encoding", "identity");
                    int code = c.getResponseCode();
                    s = code == 200 ? "测试通过" : (code == 401 || code == 403 ? "失败：Key 无效或无权限" : "失败：HTTP " + code);
                    c.disconnect();
                } catch (Exception e) { s = "失败：" + e.getClass().getSimpleName(); }
                final String fs = s;
                h.post(new Runnable() { public void run() { qwStatus = fs; if (!isFinishing()) refresh(); } });
            }
        }, "keys-qw-test").start();
    }
}
