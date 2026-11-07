package com.onesay.clock.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.clock.L;
import com.onesay.clock.Prefs;
import com.onesay.clock.content.Cities;
import com.onesay.clock.core.BriefMeta;
import com.onesay.clock.core.Flags;
import com.onesay.clock.core.Shell;
import com.onesay.clock.gen.Briefer;
import com.onesay.clock.gen.GenService;
import com.onesay.clock.sched.Sched;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 设置：播报（基线）/ 闹钟 / 新增功能（默认全关）/ 权限 / 关于。 */
public class SettingsActivity extends Activity {
    private Prefs p;
    private View rowKey, rowVoice, rowStatus, rowCity, rowPregen, rowVolKey, rowAuto, rowBirthday, rowQwKey, rowQwHost, rowRain;
    private MediaPlayer mp;
    private final Handler h = new Handler();

    int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        p = new Prefs(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(24));
        col.addView(Ui.header(this, "设置", "OneSay Clock " + Ui.version(this)));

        col.addView(Ui.section(this, "智能播报"));
        LinearLayout c1 = Ui.card(this);
        rowPregen = Ui.add(c1, Ui.row(this, "提前准备", "", null, new View.OnClickListener() { public void onClick(View v) { pickPregen(); } }));
        flagRow(c1, Flags.NEWS_MISS_LINE, "新闻取不到时说一句", "「今天的新闻暂时没取到。」关闭时直接跳到结束语");
        flagRow(c1, Flags.X_ROOT_WEATHER, "使用手机天气", "读三星天气 App 的数据（与天气 App 一致）；读不到时改用网络天气");
        rowCity = Ui.add(c1, Ui.row(this, "网络天气位置", "", null, new View.OnClickListener() { public void onClick(View v) { pickCity(); } }));
        rowRain = flagRow(c1, Flags.X_RAIN_LINE, "播报降雨提醒", "");
        rowQwKey = Ui.add(c1, Ui.row(this, "和风天气 API Key", "", null, new View.OnClickListener() { public void onClick(View v) { keys(); } }));
        rowQwHost = Ui.add(c1, Ui.row(this, "和风天气 API Host", "", null, new View.OnClickListener() { public void onClick(View v) { keys(); } }));
        rowKey = Ui.add(c1, Ui.row(this, "云端语音 Key（百炼 DashScope）", "", null, new View.OnClickListener() { public void onClick(View v) { keys(); } }));
        rowVoice = Ui.add(c1, Ui.row(this, "云端语音音色 ID", "", null, new View.OnClickListener() { public void onClick(View v) { keys(); } }));
        rowStatus = Ui.add(c1, Ui.row(this, "立即准备并试听", "", null, new View.OnClickListener() { public void onClick(View v) { genAndPlay(); } }));
        col.addView(c1, Ui.cardLp(this));

        col.addView(Ui.section(this, "闹钟"));
        LinearLayout c2 = Ui.card(this);
        flagRow(c2, Flags.VIBRATE, "振动", null);
        flagRow(c2, Flags.MIN_VOL, "最低音量保护", "闹钟音量低于 30% 时临时调高，响完还原");
        rowVolKey = Ui.add(c2, Ui.row(this, "音量键", "", null, new View.OnClickListener() { public void onClick(View v) { pickVolKey(); } }));
        rowAuto = Ui.add(c2, Ui.row(this, "响铃没人管就自动停", "", null, new View.OnClickListener() { public void onClick(View v) { pickAuto(); } }));
        col.addView(c2, Ui.cardLp(this));

        col.addView(Ui.section(this, "新增功能（默认关闭，叠加在三星原版之后）"));
        LinearLayout c3 = Ui.card(this);
        flagRow(c3, Flags.FIRST_ONLY, "每天只播报一次", "当天已有闹钟响过后，之后的智能播报闹钟只响铃（三星原版每个播报闹钟都播）");
        flagRow(c3, Flags.X_UMBRELLA, "下雨提醒带伞", "降水概率 ≥ 50% 时多说一句");
        flagRow(c3, Flags.X_BIRTHDAY, "生日祝福", null);
        rowBirthday = Ui.add(c3, Ui.row(this, "生日日期", "", null, new View.OnClickListener() { public void onClick(View v) { editBirthday(); } }));
        flagRow(c3, Flags.X_NEWS_TOUTIAO, "新闻源：今日头条热榜", "默认关；非官方公开接口，打开后参与选源");
        flagRow(c3, Flags.X_NEWS_BILIBILI, "新闻源：B 站热搜", "默认关；非官方公开接口，打开后参与选源");
        flagRow(c3, Flags.X_NEWS_TENCENT, "新闻源：腾讯新闻", "默认关；打开后腾讯要闻参与选源");
        flagRow(c3, Flags.X_LLM, "AI 改写新闻标题", "本版未接入");
        flagRow(c3, Flags.X_EDGE, "Edge 云端朗读", "本版未接入（音色不一致）");
        col.addView(c3, Ui.cardLp(this));

        col.addView(Ui.section(this, "其他"));
        LinearLayout c4 = Ui.card(this);
        Ui.add(c4, Ui.row(this, "权限与引导", "电池优化、通知、定位", null, new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, OnboardActivity.class)); }
        }));
        Ui.add(c4, Ui.row(this, "关于", "com.onesay.clock " + Ui.version(this) + "· 开源版不带预合成语音件，播报用你配置的云端音色现合成并缓存", null, null));
        col.addView(c4, Ui.cardLp(this));
        sv.addView(col);
        setContentView(sv);
        refresh();
    }

    View flagRow(LinearLayout card, final String key, String title, String sub) {
        boolean ni = Flags.notImplemented(key);
        Switch s = Ui.sw(this, p.flag(key), new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                p.setFlag(key, on);
                if (Flags.FIRST_ONLY.equals(key)) Sched.reschedule(SettingsActivity.this, "flag");
                refresh();
            }
        });
        s.setEnabled(!ni);
        View r = Ui.add(card, Ui.row(this, title, sub, s, null));
        if (sub != null) Ui.setSubColor(r, Ui.T2);
        if (ni) r.setAlpha(0.45f);
        return r;
    }

    void refresh() {
        String vo = p.cosyVoice();
        Ui.setSub(rowVoice, vo.length() == 0 ? "未配置：播报只响铃 / 配乐" : "已配置（" + p.cosyModel() + "）");
        Ui.setSubColor(rowVoice, vo.length() == 0 ? Ui.WARN : Ui.ACCENT);
        String k = p.bailianKey();
        Ui.setSub(rowKey, k.length() == 0 ? "未配置：新闻将不朗读（开场和天气照常）" : "已配置（" + k.length() + " 位）");
        Ui.setSubColor(rowKey, k.length() == 0 ? Ui.WARN : Ui.ACCENT);
        String qk = p.qweatherKey(), qh = p.qweatherHost();
        Ui.setSub(rowQwKey, qk.length() == 0 ? "未配置：不播报降雨提醒" : "已配置（" + qk.length() + " 位）");
        Ui.setSubColor(rowQwKey, qk.length() == 0 ? Ui.WARN : Ui.ACCENT);
        Ui.setSub(rowQwHost, qh.length() == 0 ? "未配置：和风控制台「设置」里的 API Host" : "已配置");
        Ui.setSubColor(rowQwHost, qh.length() == 0 ? Ui.WARN : Ui.ACCENT);
        String rw = p.rainUnavailable();
        Ui.setSub(rowRain, rw == null ? "天气后多说一句：现在在下 / N 分钟后下 / 今天某个时段可能有雨" : rw);
        Ui.setSubColor(rowRain, rw == null ? Ui.T2 : Ui.WARN);
        Ui.setSub(rowPregen, "响铃前 " + p.i(Flags.PREGEN_MIN, Flags.PREGEN_MIN_DEF) + " 分钟");
        String city = p.s(Flags.CITY, "");
        Ui.setSub(rowCity, (p.i(Flags.WX_MODE, 0) == 0 ? "自动定位" : "固定城市") + (city.length() > 0 ? "（备用城市：" + city + "）" : "（未选备用城市）"));
        String[] vk = {"无", "稍后提醒", "停止"};
        Ui.setSub(rowVolKey, vk[Math.max(0, Math.min(2, p.i(Flags.VOLUME_KEY, 0)))]);
        Ui.setSub(rowAuto, "响 " + p.i(Flags.AUTO_SNOOZE_MIN, Flags.AUTO_SNOOZE_MIN_DEF) + " 分钟没人管就自动停（不会再稍后提醒）");
        String bd = p.s(Flags.BIRTHDAY, "");
        Ui.setSub(rowBirthday, bd.length() == 0 ? "未设置" : bd);
        BriefMeta m = Briefer.load(this);
        Ui.setSub(rowStatus, m == null ? "还没有准备过播报" : "上次：" + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(m.generatedAt))
                + " · 用时 " + (m.genMs / 100) / 10.0 + " 秒 · 时长 " + m.durationMs / 1000 + " 秒" + (m.degraded.isEmpty() ? "" : " · " + m.degraded));
    }

    void pickPregen() {
        final int[] ch = Flags.PREGEN_CHOICES;
        String[] it = new String[ch.length];
        int sel = 0;
        for (int i = 0; i < ch.length; i++) { it[i] = "响铃前 " + ch[i] + " 分钟"; if (ch[i] == p.i(Flags.PREGEN_MIN, Flags.PREGEN_MIN_DEF)) sel = i; }
        new AlertDialog.Builder(this).setTitle("提前准备").setSingleChoiceItems(it, sel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { p.setI(Flags.PREGEN_MIN, ch[w]); Sched.reschedule(SettingsActivity.this, "pregen"); refresh(); d.dismiss(); }
        }).show();
    }

    void pickCity() {
        final String[] it = new String[Cities.LIST.length + 1];
        it[0] = "自动定位（推荐）";
        for (int i = 0; i < Cities.LIST.length; i++) it[i + 1] = Cities.LIST[i][0];
        new AlertDialog.Builder(this).setTitle("天气位置（定位失败时用所选城市）").setItems(it, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) p.setI(Flags.WX_MODE, 0);
                else { p.setS(Flags.CITY, it[w]); p.setI(Flags.WX_MODE, p.i(Flags.WX_MODE, 0)); }
                refresh();
            }
        }).show();
    }

    void pickVolKey() {
        new AlertDialog.Builder(this).setTitle("响铃时按音量键").setSingleChoiceItems(new String[]{"无", "稍后提醒", "停止"}, p.i(Flags.VOLUME_KEY, 0),
                new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { p.setI(Flags.VOLUME_KEY, w); refresh(); d.dismiss(); } }).show();
    }

    void pickAuto() {
        final int[] ch = Flags.AUTO_STOP_CHOICES;
        String[] it = new String[ch.length];
        for (int i = 0; i < ch.length; i++) it[i] = "响 " + ch[i] + " 分钟";
        new AlertDialog.Builder(this).setTitle("响多久没人管就自动停").setItems(it, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { p.setI(Flags.AUTO_SNOOZE_MIN, ch[w]); refresh(); }
        }).show();
    }

    void editBirthday() {
        final EditText e = new EditText(this);
        e.setHint("MM-dd，例如 10-18");
        e.setText(p.s(Flags.BIRTHDAY, ""));
        new AlertDialog.Builder(this).setTitle("生日").setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                String s = e.getText().toString().trim();
                if (s.matches("(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])") || s.length() == 0) p.setS(Flags.BIRTHDAY, s);
                else Toast.makeText(SettingsActivity.this, "格式应为 MM-dd", Toast.LENGTH_SHORT).show();
                refresh();
            }
        }).setNegativeButton("取消", null).show();
    }

    /** 和风天气 API Key / API Host（0.1.6）：同百炼 key，只存本机私有 prefs，日志只记长度。 */
    /** 开源版：所有 Key / 服务地址 / 音色统一在「API 密钥」面板里配（FLAG_SECURE、加密存储、测试按钮）。 */
    void keys() { startActivity(new Intent(this, KeysActivity.class)); }

    void editQw(final boolean host) {
        final EditText e = new EditText(this);
        e.setHint(host ? "例如 abcxyz.re.qweatherapi.com" : "粘贴和风天气 API Key");
        if (host) e.setText(p.qweatherHost());
        else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle(host ? "和风天气 API Host" : "和风天气 API Key")
                .setMessage(host ? "每个和风账号有自己的 API Host（控制台 › 设置）。" : "只用于播报降雨提醒（分钟级降水 + 逐小时预报）。保存在本机私有存储，不会上传别处。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = e.getText().toString();
                        if (host) {
                            p.setQweatherHost(v);
                            if (v.trim().length() > 0 && p.qweatherHost().length() == 0) Toast.makeText(SettingsActivity.this, "Host 格式不对", Toast.LENGTH_SHORT).show();
                            L.i(SettingsActivity.this, "QWHOST set ok=" + (p.qweatherHost().length() > 0));
                        } else {
                            p.setQweatherKey(v);
                            L.i(SettingsActivity.this, "QWKEY set len=" + p.qweatherKey().length());
                        }
                        refresh();
                    }
                }).setNegativeButton("取消", null).show();
    }

    /** 开源版：音色 ID 与模型由用户填写（例如自己在百炼控制台复刻 / 设计的音色，或模型支持的系统音色）。 */
    void editVoice() {
        final EditText e = new EditText(this);
        e.setHint("音色 ID");
        e.setText(p.cosyVoice());
        final EditText mo = new EditText(this);
        mo.setHint("模型（默认 " + Flags.COSY_MODEL_DEF + "）");
        mo.setText(p.cosyModel());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(e);
        box.addView(mo);
        new AlertDialog.Builder(this).setTitle("云端语音音色").setMessage("用于合成播报的各个片段和新闻标题，结果缓存在本机。")
                .setView(box).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        p.setS(Flags.COSY_VOICE, e.getText().toString().trim());
                        String m = mo.getText().toString().trim();
                        p.setS(Flags.COSY_MODEL, m.length() == 0 ? Flags.COSY_MODEL_DEF : m);
                        refresh();
                    }
                }).setNegativeButton("取消", null).show();
    }

    void editKey() {
        final EditText e = new EditText(this);
        e.setHint("粘贴百炼 API Key");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("云端语音 Key").setMessage("用于云端语音合成（需同时填写音色 ID）。保存在本机私有存储，不会上传别处。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { p.setBailianKey(e.getText().toString()); L.i(SettingsActivity.this, "KEY set len=" + p.bailianKey().length()); refresh(); }
                }).setNegativeButton("取消", null).show();   // 开源版：去掉「从晨间播报（v1）导入」（私有旧 App，root 读其 prefs）
    }

    void genAndPlay() {
        Toast.makeText(this, "正在准备播报…", Toast.LENGTH_SHORT).show();
        final long t0 = System.currentTimeMillis();
        GenService.start(this, t0, "manual");
        h.postDelayed(new Runnable() {
            public void run() {
                BriefMeta m = Briefer.load(SettingsActivity.this);
                if (m != null && m.generatedAt >= t0) { refresh(); play(); return; }
                if (System.currentTimeMillis() - t0 < 70_000) h.postDelayed(this, 800);
                else Toast.makeText(SettingsActivity.this, "准备失败，详见日志", Toast.LENGTH_SHORT).show();
            }
        }, 800);
    }

    void play() {
        try {
            if (mp != null) mp.release();
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(Briefer.wav(this).getPath());
            mp.prepare();
            mp.start();
        } catch (Throwable t) { Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onDestroy() { if (mp != null) mp.release(); super.onDestroy(); }
}
