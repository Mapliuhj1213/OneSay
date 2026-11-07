package com.onesay.clock.gen;

import android.content.Context;

import com.onesay.clock.L;
import com.onesay.clock.Prefs;
import com.onesay.clock.StateBus;
import com.onesay.clock.content.News;
import com.onesay.clock.content.Weather;
import com.onesay.clock.core.AlarmMath;
import com.onesay.clock.core.Assemble;
import com.onesay.clock.core.Bank;
import com.onesay.clock.core.BriefMeta;
import com.onesay.clock.core.Flags;
import com.onesay.clock.core.NewsMerge;
import com.onesay.clock.core.NewsPicker;
import com.onesay.clock.core.Template;
import com.onesay.clock.voice.PhraseVoice;
import com.onesay.clock.voice.NewsVoice;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 一轮生成（全程互斥，整轮截止 60 秒）：天气 ∥ 新闻（12 秒）→ 新闻件（音色 C，缓存优先，单条失败顺延，35 秒）
 * → 三星模板件序列 → 件库 + 新闻件拼接 → brief.wav + brief.json 原子提交。
 * 现拼开场（响铃时成品无效）也走这里的 {@link #live}：只用件库与缓存天气，不联网，几十毫秒。
 */
public final class Briefer {
    private Briefer() { }

    public static final long DEADLINE_MS = 60_000L, GATHER_MS = 12_000L, NEWS_VOICE_MS = 35_000L;
    private static final Object RUN = new Object();

    public static File dir(Context c) { File d = new File(c.getFilesDir(), "brief"); d.mkdirs(); return d; }

    public static File wav(Context c) { return new File(dir(c), "brief.wav"); }

    public static File json(Context c) { return new File(dir(c), "brief.json"); }

    public static BriefMeta load(Context c) {
        try { return BriefMeta.fromJson(new String(com.onesay.clock.content.Http.readAll(new FileInputStream(json(c))), "UTF-8")); }
        catch (Exception e) { return null; }
    }

    public static Template.Opts opts(Prefs p, long at, TimeZone tz) {
        Template.Opts o = new Template.Opts();
        o.newsMissLine = p.flag(Flags.NEWS_MISS_LINE);
        o.umbrella = p.flag(Flags.X_UMBRELLA);
        String bd = p.s(Flags.BIRTHDAY, "");
        if (p.flag(Flags.X_BIRTHDAY) && bd.length() == 5) {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd", Locale.US);
            f.setTimeZone(tz);
            o.birthdayToday = bd.equals(f.format(new Date(at)));
        }
        return o;
    }

    /** @return 成品元数据；null = 正在跑（互斥）或彻底失败 */
    public static BriefMeta generate(final Context c, final long forAlarmAt, String why) {
        synchronized (RUN) {
            final long t0 = System.currentTimeMillis();
            final TimeZone tz = TimeZone.getDefault();
            Prefs p = new Prefs(c);
            StateBus.publish(c, "GENERATING", why, null);
            BriefMeta m = new BriefMeta();
            m.forAlarmAt = forAlarmAt;
            m.day = AlarmMath.dayKey(forAlarmAt, tz);
            ExecutorService ex = Executors.newFixedThreadPool(3);
            try {
                final Future<Weather.Facts> fw = ex.submit(new Callable<Weather.Facts>() { public Weather.Facts call() { return Weather.get(c); } });
                Future<NewsMerge.Result> fn = ex.submit(new Callable<NewsMerge.Result>() { public NewsMerge.Result call() { return News.fetch(c, GATHER_MS - 1000); } });
                final NewsVoice nv = new NewsVoice(c, p.bailianKey(), p.cosyModel(), p.cosyVoice());
                // 降雨提醒句（0.1.6）：等天气拿到坐标 → 和风分钟级 + 逐小时 → 判定 → 合成（音色 C，同新闻件缓存）。
                // 和新闻并行；任何失败 / 超时都只是不说这句，不拖整轮（最晚等到新闻件做完）。
                Future<String> fr = rainEnabled(c, p) ? ex.submit(new Callable<String>() {
                    public String call() { return rainLine(c, fw, nv, forAlarmAt, t0); }
                }) : null;
                Weather.Facts w = quiet(fw, GATHER_MS);
                NewsMerge.Result nr = quiet(fn, Math.max(500, GATHER_MS - (System.currentTimeMillis() - t0)));
                long tGather = System.currentTimeMillis() - t0;
                if (w == null || !w.ok) { m.degrade("no_weather"); w = Weather.cached(c, Weather.CACHE_OK_MS); }
                // 新闻件
                List<String> spokenPool = new ArrayList<String>();
                if (nr != null) for (String t : nr.titles) spokenPool.add(t);
                long t1 = System.currentTimeMillis();
                NewsPicker.Result pk = NewsPicker.pick(spokenPool, new NewsPicker.Voice() {
                    public boolean ensure(String title) { return nv.ensure(title + "。", 15_000); }
                }, Math.min(t0 + DEADLINE_MS - 8000, t1 + NEWS_VOICE_MS), new NewsPicker.Clock() {
                    public long now() { return System.currentTimeMillis(); }
                });
                long tVoice = System.currentTimeMillis() - t1;
                if (nr == null || nr.titles.isEmpty()) m.degrade("no_news");
                else if (pk.skipped) m.degrade(p.bailianKey().length() == 0 ? "news_skipped_no_key"
                        : p.cosyVoice().length() == 0 ? "news_skipped_no_voice" : "news_skipped_voice_fail");
                // 模板 + 拼接
                Calendar cal = Calendar.getInstance(tz);
                cal.setTimeInMillis(forAlarmAt);
                Template.Wx wx = w == null ? null : w.toWx();
                Template.Opts op = opts(p, forAlarmAt, tz);
                String rain = fr == null ? null : quiet(fr, rainWait(t0, System.currentTimeMillis()));
                op.rainLine = rain;
                List<Template.Piece> ps = Template.pieces(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), wx,
                        pk.chosen, nr == null ? null : nr.sourceKey, op);
                long t2 = System.currentTimeMillis();
                Assemble.Result ar = assemble(c, ps, nv);
                long tJoin = System.currentTimeMillis() - t2;
                if (ar.out == null) { StateBus.publish(c, "ERROR", "assemble_failed", null); L.w(c, "GEN 拼接失败"); return null; }
                if (ar.missing > 0) m.degrade("missing_" + ar.missing);
                m.script = Template.text(ps);
                m.news.addAll(pk.chosen);
                m.rainLine = rain == null ? "" : rain;
                m.newsSource = pk.chosen.isEmpty() ? "" : Bank.sourceName(nr.sourceKey);
                if (w != null && w.ok) {
                    m.city = w.city; m.temp = w.temp; m.wxKey = w.key == null ? "" : w.key;
                    m.weatherLine = w.key == null || Bank.wxLine(w.key) == null ? "" : Bank.wxLine(w.key);
                }
                m.durationMs = ar.out.durationMs;
                m.generatedAt = System.currentTimeMillis();
                m.genMs = m.generatedAt - t0;
                commit(c, m, ar.out.wav);
                int cleaned = nv.janitor();
                L.i(c, "GEN_DONE why=" + why + " total=" + m.genMs + "ms gather=" + tGather + " voice=" + tVoice + " join=" + tJoin
                        + " news=" + pk.chosen.size() + "/" + spokenPool.size() + "(hit=" + nv.hits + " synth=" + nv.synth + " fail=" + nv.fails
                        + ") wx=" + (w == null ? "none" : w.src) + " audio=" + m.durationMs + "ms pieces=" + ar.pieces
                        + " degraded=" + m.degraded + " cleaned=" + cleaned + " keyLen=" + p.bailianKey().length()
                        + " rain=" + (fr == null ? "off" : (rain == null ? "none" : "said")));
                StateBus.publish(c, "READY", m.degraded.toString(), m);
                return m;
            } catch (Throwable t) {
                L.e(c, "GEN_FAIL", t);
                StateBus.publish(c, "ERROR", t.getClass().getSimpleName(), null);
                return null;
            } finally {
                ex.shutdownNow();
            }
        }
    }

    /** 降雨句在新闻件做完后最多再等多久：至少等到开工后 30 秒或再等 4 秒（取晚的），但绝不越过整轮截止前 8 秒。 */
    static final long RAIN_WAIT_MS = 4_000L, RAIN_UNTIL_MS = 30_000L;

    static long rainWait(long t0, long now) {
        long want = Math.max(RAIN_WAIT_MS, t0 + RAIN_UNTIL_MS - now);
        return Math.max(1, Math.min(want, t0 + DEADLINE_MS - 8000 - now));
    }

    static boolean rainEnabled(Context c, Prefs p) {
        if (com.onesay.clock.content.QWeather.fakePresent(c)) return p.flag(Flags.X_RAIN_LINE);   // debug 注入：不需要 key
        return p.rainUnavailable() == null;
    }

    /** 降雨句（已确认有音频才返回；任何失败 → null = 不说）。 */
    static String rainLine(Context c, Future<Weather.Facts> fw, NewsVoice nv, long alarmAt, long t0) {
        try {
            Weather.Facts w = null;
            try { w = fw.get(Math.max(500, GATHER_MS - (System.currentTimeMillis() - t0)), TimeUnit.MILLISECONDS); }   // 不 cancel：主线程也在等它
            catch (Exception ignored) { }
            if (w == null || !w.ok) w = Weather.cached(c, Weather.CACHE_OK_MS);
            double[] ll = Weather.rainCoords(c, w);
            long budget = Math.max(1000, GATHER_MS + 3000 - (System.currentTimeMillis() - t0));
            com.onesay.clock.content.QWeather.Outcome o = com.onesay.clock.content.QWeather.fetch(c, ll, alarmAt, budget);
            if (o.result == null || o.result.line == null) return null;
            String line = o.result.line;
            long left = t0 + DEADLINE_MS - 10_000 - System.currentTimeMillis();
            if (left < 1500) { L.w(c, "RAIN 没时间合成，跳过"); return null; }
            if (!nv.ensure(line, (int) Math.min(15_000, left))) { L.w(c, "RAIN 合成失败，跳过（" + o.result.kind + "）"); return null; }
            return line;
        } catch (Throwable t) {
            L.w(c, "RAIN 失败，跳过 " + t.getClass().getSimpleName());
            return null;
        }
    }

    static Assemble.Result assemble(final Context c, List<Template.Piece> ps, final NewsVoice nv) {
        return assemble(c, ps, nv, new PhraseVoice(c, true));
    }

    /** 朗读表件走本地语音缓存（「预合成朗读表」已合成的直接用）；缺件且在线就现合成并缓存；变量件（新闻 / 降雨句）走 NewsVoice。 */
    static Assemble.Result assemble(final Context c, List<Template.Piece> ps, final NewsVoice nv, final PhraseVoice pv) {
        return Assemble.run(ps, new Assemble.Source() {
            public short[] pcm(Template.Piece p) {
                if (!p.isBank()) return nv == null ? null : nv.pcm(p.text);
                short[] s = pv.pcm(p.bank);
                if (s == null && pv.online() && pv.ensure(p.bank, 15_000)) s = pv.pcm(p.bank);
                return s;
            }
        });
    }

    /**
     * 现拼开场（不联网）：时间 + 3 小时内的缓存天气 + 收尾，写到 cache/live.wav。新闻不念（没有成品就没有新闻件）。
     */
    public static File live(Context c, long alarmAt) {
        long t0 = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(alarmAt > 0 ? alarmAt : t0);
        Weather.Facts w = Weather.cached(c, Weather.CACHE_OK_MS);
        Template.Opts o = opts(new Prefs(c), cal.getTimeInMillis(), tz);
        o.newsMissLine = false;
        List<Template.Piece> ps = Template.pieces(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), w == null ? null : w.toWx(), null, null, o);
        Assemble.Result r = assemble(c, ps, new NewsVoice(c, ""), new PhraseVoice(c, false));   // 只读本地缓存，不联网
        if (r.out == null) return null;
        File f = new File(c.getCacheDir(), "live.wav");
        try {
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(r.out.wav); } finally { out.close(); }
        } catch (Exception e) { return null; }
        L.i(c, "LIVE 现拼开场 " + (System.currentTimeMillis() - t0) + "ms audio=" + r.out.durationMs + "ms wx=" + (w != null));
        return f;
    }

    static void commit(Context c, BriefMeta m, byte[] wav) throws Exception {
        File d = dir(c);
        File tw = new File(d, "brief.wav.tmp"), tj = new File(d, "brief.json.tmp");
        FileOutputStream o = new FileOutputStream(tw);
        try { o.write(wav); } finally { o.close(); }
        o = new FileOutputStream(tj);
        try { o.write(m.toJson().getBytes("UTF-8")); } finally { o.close(); }
        json(c).delete();                       // 先撤身份，再换音频，最后挂新身份：任何中断都不会出现「旧身份配新音频」
        if (!tw.renameTo(wav(c))) throw new Exception("rename wav");
        if (!tj.renameTo(json(c))) throw new Exception("rename json");
    }

    static <T> T quiet(Future<T> f, long ms) {
        try { return f.get(Math.max(1, ms), TimeUnit.MILLISECONDS); } catch (Exception e) { f.cancel(true); return null; }
    }
}
