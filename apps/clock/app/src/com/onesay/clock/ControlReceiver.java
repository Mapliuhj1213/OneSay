package com.onesay.clock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.onesay.clock.core.Alarm;
import com.onesay.clock.core.AlarmMath;
import com.onesay.clock.core.BriefMeta;
import com.onesay.clock.core.Flags;
import com.onesay.clock.gen.Briefer;
import com.onesay.clock.gen.GenService;
import com.onesay.clock.ring.RingService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.TimeZone;

/**
 * 调试入口（exported=false：只有 root 的 `am broadcast -n com.onesay.clock/.ControlReceiver` 能到，P1-7）。
 *   -a com.onesay.clock.DUMP          → files/dump.json（闹钟、贪睡、下次排程、开关、成品摘要、keyLen；不含 key）
 *   -a com.onesay.clock.GEN [--el at]  → 立即生成（默认 forAlarmAt = 现在）
 *   -a com.onesay.clock.RING_PREVIEW   → 拉起响铃页（静音、不振动、不动排程；--es mode BRIEF|LIVE|RINGTONE）
 *   -a com.onesay.clock.RING_STOP      → 结束响铃/预览
 *   -a com.onesay.clock.DEMO_ALARMS / DEMO_CLEAR → 加/删 3 个「关闭状态」的示例闹钟（截图用，不排程）
 *   -a com.onesay.clock.LIVE_TEST      → 只做「成品失效时的现拼开场」，记耗时，不播放
 *   -a com.onesay.clock.IMPORT_KEY     → 读 files/import.key（root 放进来）存为百炼 key，随即删除文件；日志只记长度
 *   -a com.onesay.clock.IMPORT_QWEATHER_KEY → 读 files/import.qweather.key（和风 API Key）和可选的 files/import.qweather.host（API Host），
 *                                    存进私有 prefs，随即删除两个文件；日志只记长度 / 是否有 host（0.1.6）
 *   -a com.onesay.clock.RAIN_TEST [--el at]  → 按当前设置算一次降雨提醒句（判定时刻默认现在），只记日志不合成（0.1.6）
 *                                    debug 包：files/rain_fake_minutely.json（+ 可选 rain_fake_hourly.json）存在时用注入数据，不联网不需要 key；
 *                                    release 包不看注入文件
 *   -a com.onesay.clock.WX_TEST        → 按当前设置取一次天气（三星天气优先），写 files/wx.json 并记日志
 *   -a com.onesay.clock.TEST_ALARM [--ei inSec 180] [--ei pregenSec 0] [--ei snoozeSec 0] [--ei timeoutSec 0] [--ez resetFirst false]
 *                                    → 建/改真实测试闹钟 id 990「测试闹钟」（播报开、只响一次），时刻 = 现在 + inSec 向上取整到分；
 *                                      pregenSec/snoozeSec/timeoutSec > 0 时临时覆盖提前生成、贪睡间隔、响铃自动停止（秒）；resetFirst 清「当天已响」
 *                                      （timeoutSec 是全局覆盖，对所有闹钟生效，测完必须 TEST_CLEAR）
 *   -a com.onesay.clock.TEST_CLEAR     → 删测试闹钟 990 及其贪睡、清三个调试覆盖、删降雨注入文件、撤掉「已自动停止」通知、重排
 *   -a com.onesay.clock.BRIEF_DELETE   → 删掉成品（brief.wav/json），模拟「成品失效」
 * release 包（BuildMode.RELEASE）：TEST_ALARM / DEMO_ALARMS / BRIEF_DELETE 直接忽略（只记日志）；TEST_CLEAR / DEMO_CLEAR 只做清理，保留；
 *   pregen/snooze/timeout 三个调试覆盖在 Sched / RingService 里也不再读取。
 */
public class ControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent in) {
        String a = String.valueOf(in.getAction());
        L.i(c, "CTRL " + a);
        // 开源版：导入密钥 / 降雨与天气测试也只在 debug 包可用（release 包不存在任何经广播写入或使用 Key 的入口）
        if (BuildMode.RELEASE && (a.endsWith(".TEST_ALARM") || a.endsWith(".DEMO_ALARMS") || a.endsWith(".BRIEF_DELETE")
                || a.endsWith(".IMPORT_KEY") || a.endsWith(".IMPORT_QWEATHER_KEY") || a.endsWith(".RAIN_TEST") || a.endsWith(".WX_TEST"))) {
            L.i(c, "CTRL release 包忽略调试命令 " + a); return;
        }
        if (a.endsWith(".DUMP")) dump(c);
        else if (a.endsWith(".GEN")) GenService.start(c, in.getLongExtra("at", System.currentTimeMillis()), "ctrl");
        else if (a.endsWith(".RING_PREVIEW")) preview(c, in.getStringExtra("mode"));
        else if (a.endsWith(".RING_STOP")) {
            // 进程刚被强行停止再拉起时服务并没在响，后台 startService 在 Android 8 会抛 IllegalStateException（0.1.3 真机复测踩到）
            if (RingService.isRinging()) {
                try { RingService.action(c, RingService.A_STOP, 0); } catch (IllegalStateException e) { L.w(c, "RING_STOP 后台不能起服务 " + e); }
            } else L.i(c, "RING_STOP 当前没有在响");
            c.sendBroadcast(new Intent(RingService.DONE).setPackage(c.getPackageName()));   // 也关掉静音预览页
        }
        else if (a.endsWith(".LIVE_TEST")) {
            final Context app = c.getApplicationContext();
            new Thread(new Runnable() { public void run() { Briefer.live(app, System.currentTimeMillis()); } }).start();
        }
        else if (a.endsWith(".IMPORT_KEY")) importKey(c);
        else if (a.endsWith(".IMPORT_QWEATHER_KEY")) importQweather(c);
        else if (a.endsWith(".RAIN_TEST")) {
            final Context app = c.getApplicationContext();
            final long at = in.getLongExtra("at", System.currentTimeMillis());
            new Thread(new Runnable() { public void run() {
                Prefs p = new Prefs(app);
                com.onesay.clock.content.Weather.Facts w = com.onesay.clock.content.Weather.cached(app, com.onesay.clock.content.Weather.CACHE_OK_MS);
                if (w == null) w = com.onesay.clock.content.Weather.get(app);
                double[] ll = com.onesay.clock.content.Weather.rainCoords(app, w);
                com.onesay.clock.content.QWeather.Outcome o = com.onesay.clock.content.QWeather.fetch(app, ll, at, 15_000);
                String why = p.rainUnavailable();
                L.i(app, "RAIN_TEST unavailable=" + (why == null ? "-" : why) + " fake=" + o.fake + " haveLoc=" + (ll != null)
                        + " result=" + (o.result == null ? "null" : o.result.toString()) + (o.why.length() == 0 ? "" : " why=" + o.why) + " " + o.ms + "ms");
            } }).start();
        }
        else if (a.endsWith(".DEMO_ALARMS")) demo(c, true);
        else if (a.endsWith(".DEMO_CLEAR")) demo(c, false);
        else if (a.endsWith(".WX_TEST")) {
            final Context app = c.getApplicationContext();
            new Thread(new Runnable() { public void run() {
                long t0 = System.currentTimeMillis();
                com.onesay.clock.content.Weather.Facts f = com.onesay.clock.content.Weather.get(app);
                L.i(app, "WX_TEST src=" + f.src + " ok=" + f.ok + " city=" + f.city + " temp=" + f.temp + " min=" + f.tmin + " max=" + f.tmax
                        + " desc=" + f.desc + " key=" + f.key + " rain=" + f.rain + " " + (System.currentTimeMillis() - t0) + "ms");
            } }).start();
        }
        else if (a.endsWith(".TEST_ALARM")) testAlarm(c, in);
        else if (a.endsWith(".TEST_CLEAR")) testClear(c);
        else if (a.endsWith(".BRIEF_DELETE")) {
            boolean w = Briefer.wav(c).delete(), j = Briefer.json(c).delete();
            L.i(c, "BRIEF_DELETE wav=" + w + " json=" + j);
        }
    }

    static final int TEST_ID = 990;

    static void testAlarm(Context c, Intent in) {
        Prefs p = new Prefs(c);
        int inSec = in.getIntExtra("inSec", 180);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(System.currentTimeMillis() + inSec * 1000L);
        if (cal.get(java.util.Calendar.SECOND) > 0 || cal.get(java.util.Calendar.MILLISECOND) > 0) cal.add(java.util.Calendar.MINUTE, 1);
        Alarm al = p.alarm(TEST_ID);
        if (al == null) { al = new Alarm(); al.id = TEST_ID; }
        al.hour = cal.get(java.util.Calendar.HOUR_OF_DAY); al.minute = cal.get(java.util.Calendar.MINUTE);
        al.days = 0; al.on = true; al.brief = true; al.label = "测试闹钟"; al.snoozeOn = true; al.snoozeMin = 5;
        p.put(al);
        p.setI(Flags.DBG_PREGEN_SEC, Math.max(0, in.getIntExtra("pregenSec", 0)));
        p.setI(Flags.DBG_SNOOZE_SEC, Math.max(0, in.getIntExtra("snoozeSec", 0)));
        p.setI(Flags.DBG_TIMEOUT_SEC, Math.max(0, in.getIntExtra("timeoutSec", 0)));
        if (in.getBooleanExtra("resetFirst", false)) p.setLastFiredDay(0);
        L.i(c, "TEST_ALARM id=" + TEST_ID + " at " + al.hour + ":" + (al.minute < 10 ? "0" : "") + al.minute
                + " pregenSec=" + p.i(Flags.DBG_PREGEN_SEC, 0) + " snoozeSec=" + p.i(Flags.DBG_SNOOZE_SEC, 0) + " timeoutSec=" + p.i(Flags.DBG_TIMEOUT_SEC, 0));
        com.onesay.clock.sched.Sched.rescheduleNow(c, "test-alarm");
    }

    static void testClear(Context c) {
        Prefs p = new Prefs(c);
        p.delete(TEST_ID);
        p.setI(Flags.DBG_PREGEN_SEC, 0);
        p.setI(Flags.DBG_SNOOZE_SEC, 0);
        p.setI(Flags.DBG_TIMEOUT_SEC, 0);
        boolean f1 = new File(c.getFilesDir(), com.onesay.clock.content.QWeather.FAKE_MIN).delete();
        boolean f2 = new File(c.getFilesDir(), com.onesay.clock.content.QWeather.FAKE_HOUR).delete();
        if (f1 || f2) L.i(c, "TEST_CLEAR 删掉降雨注入文件");
        try { ((android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(RingService.NOTI_AUTO_STOP); } catch (Throwable ignored) { }
        L.i(c, "TEST_CLEAR");
        com.onesay.clock.sched.Sched.rescheduleNow(c, "test-clear");
    }

    /** 截图用的示例闹钟：id 901–903，全部关闭（不排程、不会响）；DEMO_CLEAR 删除。 */
    static void demo(Context c, boolean add) {
        Prefs p = new Prefs(c);
        if (add) {
            Object[][] d = {{901, 6, 30, 0x3E, "起床", true}, {902, 7, 15, 0x7F, "", true}, {903, 22, 30, 0, "早点睡", false}};
            for (Object[] x : d) {
                Alarm al = new Alarm();
                al.id = (Integer) x[0]; al.hour = (Integer) x[1]; al.minute = (Integer) x[2]; al.days = (Integer) x[3];
                al.label = (String) x[4]; al.brief = (Boolean) x[5]; al.on = false;
                p.put(al);
            }
        } else {
            for (int id = 901; id <= 903; id++) p.delete(id);
        }
        com.onesay.clock.sched.Sched.rescheduleNow(c, add ? "demo-add" : "demo-clear");
        L.i(c, "DEMO " + (add ? "added 3 (all off)" : "cleared"));
    }

    static void preview(Context c, String mode) {
        Intent ui = new Intent(c, com.onesay.clock.ring.RingActivity.class).putExtra("id", -1).putExtra("preview", true)
                .putExtra("mode", mode == null ? "BRIEF" : mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(ui);
        L.i(c, "PREVIEW 响铃页（静音）mode=" + mode);
    }

    static void importKey(Context c) {
        File f = new File(c.getFilesDir(), "import.key");
        try {
            String k = new String(com.onesay.clock.content.Http.readAll(new FileInputStream(f)), "UTF-8").trim();
            if (k.length() > 0) new Prefs(c).setBailianKey(k);
            L.i(c, "KEY import len=" + k.length());
        } catch (Exception e) {
            L.w(c, "KEY import 失败 " + e.getClass().getSimpleName());
        } finally {
            f.delete();
        }
    }

    /** 和风 key（+ 可选 host）：root 放进 files/，读完立刻删；日志只记长度。 */
    static void importQweather(Context c) {
        File fk = new File(c.getFilesDir(), "import.qweather.key"), fh = new File(c.getFilesDir(), "import.qweather.host");
        Prefs p = new Prefs(c);
        try {
            if (fk.isFile()) {
                String k = new String(com.onesay.clock.content.Http.readAll(new FileInputStream(fk)), "UTF-8").trim();
                if (k.length() > 0) p.setQweatherKey(k);
                L.i(c, "QWKEY import len=" + k.length());
            } else L.i(c, "QWKEY import 没有 import.qweather.key");
            if (fh.isFile()) {
                String h = new String(com.onesay.clock.content.Http.readAll(new FileInputStream(fh)), "UTF-8").trim();
                if (h.length() > 0) p.setQweatherHost(h);
                L.i(c, "QWHOST import ok=" + (p.qweatherHost().length() > 0));
            }
        } catch (Exception e) {
            L.w(c, "QWKEY import 失败 " + e.getClass().getSimpleName());
        } finally {
            fk.delete();
            fh.delete();
        }
    }

    static void dump(Context c) {
        try {
            Prefs p = new Prefs(c);
            JSONObject o = new JSONObject();
            JSONArray as = new JSONArray();
            for (Alarm x : p.alarms()) as.put(x.toJson());
            o.put("alarms", as).put("snoozes", p.snoozes().serialize()).put("lastFiredDay", p.lastFiredDay());
            AlarmMath.Event e = AlarmMath.earliest(p.alarms(), p.snoozes(), System.currentTimeMillis(), TimeZone.getDefault());
            o.put("next", e == null ? "none" : e.toString());
            JSONObject fl = new JSONObject();
            for (String k : Flags.ALL_BOOL) fl.put(k, p.flag(k));
            fl.put(Flags.PREGEN_MIN, p.i(Flags.PREGEN_MIN, Flags.PREGEN_MIN_DEF));
            fl.put(Flags.AUTO_SNOOZE_MIN, p.i(Flags.AUTO_SNOOZE_MIN, Flags.AUTO_SNOOZE_MIN_DEF));
            o.put("flags", fl).put("keyLen", p.bailianKey().length())
                    .put("qwKeyLen", p.qweatherKey().length()).put("qwHostSet", p.qweatherHost().length() > 0)
                    .put("rainUnavailable", String.valueOf(p.rainUnavailable()))
                    .put("rainFake", com.onesay.clock.content.QWeather.fakePresent(c)).put("phrasesCached", new com.onesay.clock.voice.PhraseVoice(c, false).cachedCount()).put("phrasesTotal", com.onesay.clock.core.Bank.all().size());
            BriefMeta m = Briefer.load(c);
            if (m != null) o.put("brief", new JSONObject(m.toJson()));
            o.put("briefWavBytes", Briefer.wav(c).length());
            o.put("dbg", new JSONObject().put("pregenSec", p.i(Flags.DBG_PREGEN_SEC, 0)).put("snoozeSec", p.i(Flags.DBG_SNOOZE_SEC, 0))
                    .put("timeoutSec", p.i(Flags.DBG_TIMEOUT_SEC, 0)));
            com.onesay.clock.content.Weather.Facts wx = com.onesay.clock.content.Weather.cached(c, Long.MAX_VALUE / 4);
            if (wx != null) o.put("wxCached", new JSONObject().put("src", wx.src).put("city", wx.city).put("temp", wx.temp)
                    .put("desc", wx.desc).put("key", String.valueOf(wx.key)).put("at", wx.at));
            File out = new File(c.getFilesDir(), "dump.json");
            FileOutputStream w = new FileOutputStream(out);
            try { w.write(o.toString(2).getBytes("UTF-8")); } finally { w.close(); }
        } catch (Exception ex) {
            L.e(c, "DUMP 失败", ex);
        }
    }
}
