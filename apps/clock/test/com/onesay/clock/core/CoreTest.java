package com.onesay.clock.core;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.*;

/** 纯 Java 核心的 JVM 测试：调度、贪睡、失败回落、模板文案、停顿表、开关默认值、拼接、新闻、缓存、Shell 超时。 */
public class CoreTest {
    static int pass, fail;
    static final List<String> FAILS = new ArrayList<String>();

    static void ok(String name, boolean c) {
        if (c) pass++; else { fail++; FAILS.add(name); System.out.println("  FAIL " + name); }
    }

    static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (!c) System.out.println("  want: " + want + "\n  got : " + got);
        ok(name, c);
    }

    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    static long t(int y, int mo, int d, int h, int mi, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    static Alarm al(int id, int h, int m, int days) {
        Alarm a = new Alarm(); a.id = id; a.hour = h; a.minute = m; a.days = days; return a;
    }

    public static void main(String[] args) throws Exception {
        bgm(); vibe(); flags(); zhnum(); alarmMath(); snooze(); briefFirst(); ringPlan(); timeoutStop(); validity(); template(); bankManifest();
        pauses(); pcm(); assemble(); alertUi(); news(); picker(); janitor(); state(); shell(); weather(); alarmJson(); haptics(); alarmApi();
        System.out.println("CoreTest " + pass + "/" + (pass + fail) + (fail == 0 ? " ALL PASS" : " FAILED: " + FAILS));
        System.exit(fail == 0 ? 0 : 1);
    }

    static void bgm() {
        eq("bgm 局部多云→fog_cloudy", "fog_cloudy.ogg", WeatherBgm.route(true, "局部多云", "duoyun", 28));
        eq("bgm 雷阵雨→thunder（雷先于雨）", "thunder.ogg", WeatherBgm.route(true, "雷阵雨", "leizhenyu", 25));
        eq("bgm 小雨→rain", "rain.ogg", WeatherBgm.route(true, "小雨", "xiaoyu", 20));
        eq("bgm 雨夹雪→snow", "snow.ogg", WeatherBgm.route(true, "雨夹雪", "yujiaxue", 0));
        eq("bgm 雾霾→fog_cloudy", "fog_cloudy.ogg", WeatherBgm.route(true, "雾霾", "wumai", 10));
        eq("bgm 大风→windy_cold", "windy_cold.ogg", WeatherBgm.route(true, "大风", "dafeng", 15));
        eq("bgm 冬天晴 5℃→sunny（v1 09-26 修）", "sunny_clear_hot_warm.ogg", WeatherBgm.route(true, "晴", "qinglang", 5));
        eq("bgm 认不出+5℃→windy_cold", "windy_cold.ogg", WeatherBgm.route(true, "??", null, 5));
        eq("bgm 认不出+30℃→sunny", "sunny_clear_hot_warm.ogg", WeatherBgm.route(true, "??", null, 30));
        eq("bgm 认不出+20℃→默认", "default_sound.ogg", WeatherBgm.route(true, "??", null, 20));
        eq("bgm 网络天气 wmo 描述→按天气键译回", "rain.ogg", WeatherBgm.route(true, "wmo61", "xiaoyu", 20));
        eq("bgm 读不到天气→v1 默认曲目", "default_sound.ogg", WeatherBgm.route(false, null, null, Double.NaN));
        eq("bgm v1 混音参数", "0.8/0.25/0.55/1200/1500/300/0.35",
                WeatherBgm.START + "/" + WeatherBgm.BED + "/" + WeatherBgm.FULL + "/" + WeatherBgm.DUCK_MS + "/" + WeatherBgm.LEAD_MS + "/" + WeatherBgm.VOICE_FADE_IN_MS + "/" + WeatherBgm.VOICE_TAIL_FLOOR);
        eq("bgm 尾段窗口 41s→3000", 3000L, WeatherBgm.tailMs(41000)); eq("bgm 尾段窗口 2s→800", 800L, WeatherBgm.tailMs(2000));
        String[] all = {"default_sound.ogg", "fog_cloudy.ogg", "rain.ogg", "snow.ogg", "sunny_clear_hot_warm.ogg", "thunder.ogg", "windy_cold.ogg"};
        boolean have = true;
        for (String a : all) if (!new java.io.File(System.getProperty("bc.assets", "app/assets"), WeatherBgm.DIR + a).isFile()) have = false;
        // 开源版：天气配乐（原厂曲目）不随仓库分发，assets/bgm 可以为空；缺曲目时 RingService 走 MUSIC_ERROR → 铃声兜底。
        boolean none = !new java.io.File(System.getProperty("bc.assets", "app/assets"), WeatherBgm.DIR).isDirectory();
        ok("bgm 7 首曲目要么都在、要么整目录不带（开源版）", have || none);
        eq("bgm 开源版：配乐打不开 → 铃声", RingPlan.Mode.RINGTONE, RingPlan.next(RingPlan.Mode.MUSIC, RingPlan.Ev.MUSIC_ERROR));
    }

    static void vibe() {
        eq("vibe Basic call = [0, 1500, 2000]", "[0, 1500, 2000]", java.util.Arrays.toString(VibePattern.BASIC_CALL));
        eq("vibe 从头整段重复", 0, VibePattern.REPEAT);
        eq("vibe 50035 → config_vibeCommonK", "config_vibeCommonK", VibePattern.resName(50035));
        eq("vibe 50025 → A", "config_vibeCommonA", VibePattern.resName(50025));
        ok("vibe 越界 → null", VibePattern.resName(50046) == null && VibePattern.resName(0) == null);
        eq("vibe 系统读不到 → Basic call", "[0, 1500, 2000]", java.util.Arrays.toString(VibePattern.choose(null)));
        eq("vibe 系统读到 → 跟随", "[0, 250, 250, 250]", java.util.Arrays.toString(VibePattern.choose(new long[]{0, 250, 250, 250})));
        ok("vibe 坏数组 → Basic call", VibePattern.choose(new long[]{0, -1}).length == 3 && VibePattern.choose(new long[]{0, 0}).length == 3);
        ok("vibe 不是旧的 600/600", VibePattern.BASIC_CALL[1] != 600L);
    }

    static void flags() {
        ok("flag firstOnly 默认关（0.1.3）", !Flags.FIRST_ONLY_DEF && !Flags.boolDefault(Flags.FIRST_ONLY));
        ok("flag ringAfter 默认开", Flags.boolDefault(Flags.RING_AFTER));
        ok("flag vibrate 默认开", Flags.boolDefault(Flags.VIBRATE));
        ok("flag minVol 默认开", Flags.boolDefault(Flags.MIN_VOL));
        for (String k : new String[]{Flags.NEWS_MISS_LINE, Flags.X_UMBRELLA, Flags.X_BIRTHDAY,
                Flags.X_LLM, Flags.X_EDGE})
            ok("flag " + k + " 新增功能默认关", !Flags.boolDefault(k));
        ok("flag 系统天气（三星天气适配）开源版默认关", !Flags.X_ROOT_WEATHER_DEF && !Flags.boolDefault(Flags.X_ROOT_WEATHER));
        ok("flag 开源版：腾讯新闻源默认关", !Flags.X_NEWS_TENCENT_DEF && !Flags.boolDefault(Flags.X_NEWS_TENCENT));
        ok("flag 开源版：头条 / B 站新闻源默认关", !Flags.X_NEWS_TOUTIAO_DEF && !Flags.boolDefault(Flags.X_NEWS_TOUTIAO)
                && !Flags.X_NEWS_BILIBILI_DEF && !Flags.boolDefault(Flags.X_NEWS_BILIBILI));
        ok("flag 开源版：音色 ID 默认空（不内置复刻音色）", Flags.COSY_VOICE_DEF.length() == 0);
        eq("flag 开源版：提供者缺省不写厂商", "网络", AlertUi.cpName(null));
        eq("endpoint 只收 https", "", Endpoint.clean("http://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"));
        ok("endpoint 默认值合法", Endpoint.clean(Flags.TTS_ENDPOINT_DEF).equals(Flags.TTS_ENDPOINT_DEF));
        eq("endpoint 拒绝带账号", "", Endpoint.clean("https://user:pw@evil.example/x"));
        eq("endpoint 拒绝查询串（Key 不能进 URL）", "", Endpoint.clean("https://a.example/x?key=1"));
        ok("endpoint http 给出原因", Endpoint.why("http://a.example/x").contains("https"));
        eq("endpoint 列音色地址同主机", "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/customization", Endpoint.voiceListUrl(Flags.TTS_ENDPOINT_DEF));
        eq("endpoint 非兼容地址不推列表", "", Endpoint.voiceListUrl("https://tts.example.com/v1/speech"));
        ok("flag 开源版：音色默认空 / 模型有默认", Flags.COSY_VOICE_DEF.isEmpty() && Flags.COSY_MODEL_DEF.length() > 0);
        ok("flag LLM 未接入", Flags.notImplemented(Flags.X_LLM) && Flags.notImplemented(Flags.X_EDGE));
        boolean hist = false;
        for (String k : Flags.ALL_BOOL) if (k.toLowerCase().contains("hist")) hist = true;
        ok("flag 历史上的今天已移除", !hist && !Flags.notImplemented("xHistory"));
        boolean dropH = false, dropW = false;
        for (String k : Flags.DROP_KEYS_V2) { if ("xHistory".equals(k)) dropH = true; if (Flags.X_ROOT_WEATHER.equals(k)) dropW = true; }
        ok("flag 迁移清掉 xHistory 和旧天气键", dropH && dropW && Flags.CFG_VER_NOW == 3);
        ok("flag umbrella 已实现", !Flags.notImplemented(Flags.X_UMBRELLA));
        eq("flag pregen 默认 10", 10, Flags.PREGEN_MIN_DEF);
        eq("flag 音量键默认无", 0, Flags.VOLUME_KEY_DEF);
        eq("flag 响铃自动停止默认 10 分钟", 10, Flags.AUTO_SNOOZE_MIN_DEF);
        ok("flag 自动停止可选里有 10 分钟", Arrays.binarySearch(Flags.AUTO_STOP_CHOICES, Flags.AUTO_SNOOZE_MIN_DEF) >= 0);
    }

    static void zhnum() {
        eq("zh 0", "零", ZhNum.n(0)); eq("zh 10", "十", ZhNum.n(10)); eq("zh 15", "十五", ZhNum.n(15));
        eq("zh 20", "二十", ZhNum.n(20)); eq("zh 46", "四十六", ZhNum.n(46));
        eq("zh -3", "零下三", ZhNum.deg(-3)); eq("zh hour 2", "两点", ZhNum.hour(2)); eq("zh hour 22", "二十二点", ZhNum.hour(22));
        eq("zh hour 0", "零点", ZhNum.hour(0));
        eq("zh min 0", "整，", ZhNum.minute(0)); eq("zh min 5", "零五分，", ZhNum.minute(5)); eq("zh min 46", "四十六分，", ZhNum.minute(46));
    }

    static void alarmMath() {
        long now = t(2026, 11, 5, 10, 0, SH);   // 周四
        eq("next 单次今天稍后", t(2026, 11, 5, 11, 30, SH), AlarmMath.next(al(1, 11, 30, 0), now, SH));
        eq("next 单次已过→明天", t(2026, 11, 6, 7, 0, SH), AlarmMath.next(al(1, 7, 0, 0), now, SH));
        eq("next 同一分钟不算（严格晚于）", t(2026, 11, 6, 10, 0, SH), AlarmMath.next(al(1, 10, 0, 0), now, SH));
        eq("next 工作日 周四 7:00 已过→周五", t(2026, 11, 6, 7, 0, SH), AlarmMath.next(al(1, 7, 0, 0x3E), now, SH));
        long fri = t(2026, 11, 6, 8, 0, SH);
        eq("next 工作日 周五之后→周一", t(2026, 11, 9, 7, 0, SH), AlarmMath.next(al(1, 7, 0, 0x3E), fri, SH));
        eq("next 只周日", t(2026, 11, 8, 9, 0, SH), AlarmMath.next(al(1, 9, 0, 1), now, SH));
        Alarm off = al(1, 11, 0, 0); off.on = false;
        eq("next 关着=0", 0L, AlarmMath.next(off, now, SH));
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        long before = t(2026, 10, 31, 12, 0, ny);
        long nx = AlarmMath.next(al(1, 7, 0, 0x7F), t(2026, 11, 1, 0, 30, ny), ny);
        Calendar c = Calendar.getInstance(ny); c.setTimeInMillis(nx);
        ok("next 跨夏令时仍是墙钟 7:00", c.get(Calendar.HOUR_OF_DAY) == 7 && c.get(Calendar.DAY_OF_MONTH) == 1);
        ok("next 时区换算 before", before > 0);
        List<Alarm> as = Arrays.asList(al(1, 7, 0, 0x7F), al(2, 6, 30, 0x7F), al(3, 6, 15, 0));
        as.get(2).on = false;
        AlarmMath.Event e = AlarmMath.earliest(as, null, t(2026, 11, 5, 5, 0, SH), SH);
        ok("earliest 取最早的开着的（P1-8）", e != null && e.alarmId == 2 && !e.snooze);
        eq("earliest 全关=null", null, AlarmMath.earliest(new ArrayList<Alarm>(), new SnoozeBook(), now, SH));
        eq("until 2h5m", "2 小时 5 分钟后响铃", AlarmMath.untilLabel(now + 125 * 60_000L, now));
        eq("until 全关", "所有闹钟均已关闭", AlarmMath.untilLabel(0, now));
    }

    static void snooze() {
        long now = t(2026, 11, 5, 7, 0, SH);
        SnoozeBook b = new SnoozeBook();
        b.put(1, now + 5 * 60_000L);
        List<Alarm> as = new ArrayList<Alarm>(Arrays.asList(al(1, 7, 0, 0x7F), al(2, 7, 3, 0x7F)));
        AlarmMath.Event e = AlarmMath.earliest(as, b, now, SH);
        ok("snooze 7:03 闹钟比 7:05 贪睡早", e.alarmId == 2 && !e.snooze);
        // 贪睡期间增删改其它闹钟、重排：贪睡记录不受影响（P0-2）
        as.add(al(3, 9, 0, 0)); as.remove(1);
        e = AlarmMath.earliest(as, b, now, SH);
        ok("snooze 改闹钟后贪睡仍是最早", e.snooze && e.alarmId == 1 && e.at == now + 5 * 60_000L);
        ok("snooze 到点核对 ±90s", b.matches(1, now + 5 * 60_000L + 30_000L));
        ok("snooze 别的 id 不匹配", !b.matches(2, now + 5 * 60_000L));
        SnoozeBook p = SnoozeBook.parse(b.serialize());
        ok("snooze 序列化往返", p.matches(1, now + 5 * 60_000L) && p.entries().size() == 1);
        b.put(1, now + 10 * 60_000L);
        eq("snooze 同 id 覆盖", 1, b.entries().size());
        b.put(4, now - 20 * 60_000L);
        eq("snooze purge 过期", 1, b.purge(now));
        ok("snooze 迟到 5 分钟仍有效", AlarmMath.earliest(new ArrayList<Alarm>(), SnoozeBook.parse("7:" + (now - 5 * 60_000L)), now, SH) != null);
        ok("snooze remove", b.remove(1) && b.entries().isEmpty());
        eq("snooze parse 垃圾", 0, SnoozeBook.parse("x,1:y,:3").entries().size());
    }

    static void briefFirst() {
        Alarm a = al(1, 7, 0, 0x7F);
        long at = t(2026, 11, 5, 7, 0, SH);
        int today = AlarmMath.dayKey(at, SH);
        // 0.1.3 根因锁定：调试闹钟 990 不记「当天已响」，也不受「每天一次」限制；默认不开「每天一次」（三星 isFirstAlarm = 非贪睡首响）
        ok("0.1.3 默认关每天一次", !Flags.FIRST_ONLY_DEF && !Flags.boolDefault(Flags.FIRST_ONLY));
        ok("0.1.3 默认设置下当天已响过仍播", AlarmMath.shouldBrief(a, at, today, Flags.FIRST_ONLY_DEF, SH));
        ok("0.1.3 测试闹钟 990 不记当天已响", !AlarmMath.recordsFiredDay(990, false));
        ok("0.1.3 示例闹钟 901 不记当天已响", !AlarmMath.recordsFiredDay(901, false));
        ok("0.1.3 用户闹钟记当天已响", AlarmMath.recordsFiredDay(1, false));
        ok("0.1.3 贪睡不记当天已响", !AlarmMath.recordsFiredDay(1, true));
        Alarm tst = al(990, 12, 27, 0); tst.brief = true;
        ok("0.1.3 测试闹钟开了每天一次也播", AlarmMath.shouldBrief(tst, at, today, true, SH));
        eq("0.1.3 原因 first-only", "first-only(lastFiredDay=" + today + ")", AlarmMath.briefReason(a, at, today, true, SH));
        eq("0.1.3 原因 brief", "brief", AlarmMath.briefReason(a, at, today, false, SH));
        ok("0.1.3 迁移 v2→v3 清 firstOnly 与 lastFiredDay", Flags.dropKeys(2).contains(Flags.FIRST_ONLY) && Flags.dropKeys(2).contains("lastFiredDay") && !Flags.dropKeys(2).contains("xHistory"));
        ok("0.1.3 迁移 v1→v3 两批都清", Flags.dropKeys(1).contains("xHistory") && Flags.dropKeys(1).contains("lastFiredDay"));
        ok("0.1.3 v3 不再清", Flags.dropKeys(3).isEmpty());
        // 复现 17:00 事故：调试闹钟中午响过（lastFiredDay=today）后，用户 17:00 播报闹钟在新默认下照播
        Alarm user = al(1, 17, 0, 0x3E); user.brief = true;
        ok("0.1.3 复现 17:00 事故→现在播报", AlarmMath.shouldBrief(user, t(2026, 11, 5, 17, 0, SH), today, Flags.FIRST_ONLY_DEF, SH));
        ok("first 当天没响过→播", AlarmMath.shouldBrief(a, at, today - 1, true, SH));
        ok("first 当天已响过→不播", !AlarmMath.shouldBrief(a, at, today, true, SH));
        ok("first 关掉开关→每次播", AlarmMath.shouldBrief(a, at, today, false, SH));
        a.brief = false;
        ok("first 非播报闹钟不播", !AlarmMath.shouldBrief(a, at, 0, false, SH));
        eq("dayKey", 20261105, today);
    }

    static void haptics() {
        // 默认映射：老式系统时钟的触感分布，用标准 VibrationEffect 实现
        HapticMap.Act g = HapticMap.active(HapticMap.Ev.SLIDE_GRAB), tr = HapticMap.active(HapticMap.Ev.SLIDE_TRIGGER);
        ok("hap 滑动按住 = 点击振动", g.kind == HapticMap.Kind.VIBRATE && g.code == HapticMap.VIB_CLICK && g.fallback == 1);
        ok("hap 滑动触发 = 点击振动", tr.kind == HapticMap.Kind.VIBRATE && tr.code == HapticMap.VIB_CLICK);
        ok("hap 点击振动 12 ms 满幅", HapticMap.oneShotMs(HapticMap.VIB_CLICK) == 12 && HapticMap.amplitude(HapticMap.VIB_CLICK) == 255);
        HapticMap.Act tk = HapticMap.active(HapticMap.Ev.TIME_TICK);
        ok("hap 滚轮每格 playSoundEffect(105)", tk.kind == HapticMap.Kind.SOUND_EFFECT && tk.code == 105);
        eq("hap 长按交给框架", HapticMap.Kind.FRAMEWORK, HapticMap.active(HapticMap.Ev.LONG_PRESS).kind);
        HapticMap.Ev[] none = {HapticMap.Ev.SWITCH_TOGGLE, HapticMap.Ev.DAY_TOGGLE, HapticMap.Ev.BUTTON_TAP, HapticMap.Ev.SNOOZE_TAP, HapticMap.Ev.SNOOZE_STEP, HapticMap.Ev.DELETE_CONFIRM};
        for (HapticMap.Ev e : none) eq("hap 旧版无振动 " + e, HapticMap.Kind.NONE, HapticMap.active(e).kind);
        ok("hap 触摸时振动=0 → 滑动不振", !HapticMap.allowed(g, 0));
        ok("hap 触摸时振动=1 → 滑动振", HapticMap.allowed(g, 1));
        ok("hap 滚轮音效由系统触摸音管", HapticMap.allowed(tk, 0));
        ok("hap NONE/FRAMEWORK 永不由 App 发", !HapticMap.allowed(HapticMap.active(HapticMap.Ev.SWITCH_TOGGLE), 1) && !HapticMap.allowed(HapticMap.active(HapticMap.Ev.LONG_PRESS), 1));
    }

    static void ringPlan() {
        eq("plan 贪睡→铃声（P1-6）", RingPlan.Mode.RINGTONE, RingPlan.start(true, true, Validity.Verdict.OK));
        eq("plan 不播报→铃声", RingPlan.Mode.RINGTONE, RingPlan.start(false, false, Validity.Verdict.OK));
        eq("plan 成品有效→播报", RingPlan.Mode.BRIEF, RingPlan.start(false, true, Validity.Verdict.OK));
        for (Validity.Verdict v : Validity.Verdict.values())
            if (v != Validity.Verdict.OK) eq("plan 成品" + v + "→现拼开场", RingPlan.Mode.LIVE, RingPlan.start(false, true, v));
        eq("plan 播完→继续循环天气配乐（0.1.1）", RingPlan.Mode.MUSIC, RingPlan.next(RingPlan.Mode.BRIEF, RingPlan.Ev.VOICE_DONE));
        eq("plan 配乐出错/没起来→铃声兜底", RingPlan.Mode.RINGTONE, RingPlan.next(RingPlan.Mode.MUSIC, RingPlan.Ev.MUSIC_ERROR));
        eq("plan 播报中配乐出错→人声照播", RingPlan.Mode.BRIEF, RingPlan.next(RingPlan.Mode.BRIEF, RingPlan.Ev.MUSIC_ERROR));
        eq("plan 配乐循环中人声事件迟到→仍是配乐", RingPlan.Mode.MUSIC, RingPlan.next(RingPlan.Mode.MUSIC, RingPlan.Ev.VOICE_DONE));
        eq("plan 配乐循环超时→自动停止（0.1.5）", RingPlan.Mode.AUTO_STOP, RingPlan.next(RingPlan.Mode.MUSIC, RingPlan.Ev.TIMEOUT));
        eq("plan 配乐循环中贪睡", RingPlan.Mode.DONE, RingPlan.next(RingPlan.Mode.MUSIC, RingPlan.Ev.USER_SNOOZE));
        eq("plan 现拼开场播完→铃声（成品缺失兜底）", RingPlan.Mode.RINGTONE, RingPlan.next(RingPlan.Mode.LIVE, RingPlan.Ev.VOICE_DONE));
        eq("plan 播放出错→铃声", RingPlan.Mode.RINGTONE, RingPlan.next(RingPlan.Mode.BRIEF, RingPlan.Ev.VOICE_ERROR));
        eq("plan 现拼失败→铃声", RingPlan.Mode.RINGTONE, RingPlan.next(RingPlan.Mode.LIVE, RingPlan.Ev.LIVE_FAILED));
        eq("plan 铃声打不开→合成提示音", RingPlan.Mode.BEEP, RingPlan.next(RingPlan.Mode.RINGTONE, RingPlan.Ev.RINGTONE_ERROR));
        eq("plan 铃声超时→自动停止（0.1.5）", RingPlan.Mode.AUTO_STOP, RingPlan.next(RingPlan.Mode.RINGTONE, RingPlan.Ev.TIMEOUT));
        eq("plan 用户停止", RingPlan.Mode.DONE, RingPlan.next(RingPlan.Mode.BEEP, RingPlan.Ev.USER_STOP));
        // 穷举：除用户操作和超时外，任何事件序列都不会落到无声
        boolean never = true;
        RingPlan.Ev[] sys = {RingPlan.Ev.VOICE_DONE, RingPlan.Ev.VOICE_ERROR, RingPlan.Ev.RINGTONE_ERROR, RingPlan.Ev.LIVE_FAILED, RingPlan.Ev.MUSIC_ERROR};
        for (RingPlan.Mode m : new RingPlan.Mode[]{RingPlan.Mode.BRIEF, RingPlan.Mode.LIVE, RingPlan.Mode.MUSIC, RingPlan.Mode.RINGTONE, RingPlan.Mode.BEEP})
            for (RingPlan.Ev a : sys) for (RingPlan.Ev b : sys) for (RingPlan.Ev c : sys)
                if (!RingPlan.audible(RingPlan.next(RingPlan.next(RingPlan.next(m, a), b), c))) never = false;
        ok("plan 穷举 64×4 序列永不静音", never);
    }

    /** 收尾模拟（与 Sched.snooze / Sched.dismissed 对贪睡记录的处理相同）：返回收尾后的下一次排程。 */
    static AlarmMath.Event endRing(List<Alarm> as, SnoozeBook b, int id, RingPlan.Ev e, long now, int snoozeMin) {
        if (RingPlan.endFor(e) == RingPlan.End.SNOOZE) b.put(id, now + snoozeMin * 60_000L);
        else b.remove(id);
        return AlarmMath.earliest(as, b, now, SH);
    }

    static void timeoutStop() {
        // 0.1.5（用户 11-06 定）：响 10 分钟没人管 → 自动停止，等同点停止；不再自动贪睡
        for (RingPlan.Mode m : new RingPlan.Mode[]{RingPlan.Mode.BRIEF, RingPlan.Mode.LIVE, RingPlan.Mode.MUSIC, RingPlan.Mode.RINGTONE, RingPlan.Mode.BEEP})
            eq("stop 超时→AUTO_STOP " + m, RingPlan.Mode.AUTO_STOP, RingPlan.next(m, RingPlan.Ev.TIMEOUT));
        ok("stop AUTO_STOP 无声（终点）", !RingPlan.audible(RingPlan.Mode.AUTO_STOP));
        eq("stop AUTO_STOP 之后再来任何系统事件仍停着", RingPlan.Mode.AUTO_STOP, RingPlan.next(RingPlan.Mode.AUTO_STOP, RingPlan.Ev.VOICE_DONE));
        eq("stop AUTO_STOP 之后再超时仍停着", RingPlan.Mode.AUTO_STOP, RingPlan.next(RingPlan.Mode.AUTO_STOP, RingPlan.Ev.TIMEOUT));
        eq("stop 超时收尾=停止", RingPlan.End.STOP, RingPlan.endFor(RingPlan.Ev.TIMEOUT));
        eq("stop 用户停止收尾=停止", RingPlan.End.STOP, RingPlan.endFor(RingPlan.Ev.USER_STOP));
        eq("stop 手动贪睡仍是贪睡", RingPlan.End.SNOOZE, RingPlan.endFor(RingPlan.Ev.USER_SNOOZE));
        eq("stop 手动贪睡→DONE（行为不变）", RingPlan.Mode.DONE, RingPlan.next(RingPlan.Mode.RINGTONE, RingPlan.Ev.USER_SNOOZE));
        boolean noSnooze = true;
        for (RingPlan.Ev e : RingPlan.Ev.values()) if (e != RingPlan.Ev.USER_SNOOZE && RingPlan.endFor(e) == RingPlan.End.SNOOZE) noSnooze = false;
        ok("stop 只有手动贪睡会写贪睡记录", noSnooze);
        // 超时毫秒：默认 10 分钟；调试覆盖秒数优先；设置 0/负数夹到 1 分钟
        eq("stop 超时 10 分钟", 600_000L, RingPlan.timeoutMs(10, 0));
        eq("stop 超时 30 分钟", 1_800_000L, RingPlan.timeoutMs(30, 0));
        eq("stop 调试覆盖 60 秒", 60_000L, RingPlan.timeoutMs(10, 60));
        eq("stop 设置 0 夹到 1 分钟", 60_000L, RingPlan.timeoutMs(0, 0));
        // 通知文案
        eq("stop 通知 17:01", "闹钟 17:01 已自动停止", RingPlan.autoStopTitle(17, 1));
        eq("stop 通知 7:05", "闹钟 7:05 已自动停止", RingPlan.autoStopTitle(7, 5));
        eq("stop 通知 0:00", "闹钟 0:00 已自动停止", RingPlan.autoStopTitle(0, 0));
        // 停止后下次排程：工作日 17:01，周五 11-06 17:01 响、17:11 超时停 → 下一次是周一 11-09 17:01（不是 17:16 的贪睡）
        List<Alarm> as = new ArrayList<Alarm>(Arrays.asList(al(1, 17, 1, 0x3E)));
        long fri1711 = t(2026, 11, 6, 17, 11, SH);
        AlarmMath.Event e = endRing(as, new SnoozeBook(), 1, RingPlan.Ev.TIMEOUT, fri1711, 5);
        ok("stop 超时停止后下次=周一 11-09 17:01 正点", e != null && !e.snooze && e.alarmId == 1 && e.at == t(2026, 11, 9, 17, 1, SH));
        // 贪睡再响后又超时：残留的贪睡记录也清掉
        SnoozeBook b = new SnoozeBook(); b.put(1, fri1711 + 60_000L);
        e = endRing(as, b, 1, RingPlan.Ev.TIMEOUT, fri1711, 5);
        ok("stop 贪睡再响超时→清贪睡、下次仍是周一正点", e != null && !e.snooze && b.entries().isEmpty() && e.at == t(2026, 11, 9, 17, 1, SH));
        // 别的闹钟的贪睡不受影响
        b = new SnoozeBook(); b.put(2, fri1711 + 3 * 60_000L);
        as.add(al(2, 17, 0, 0x7F));
        e = endRing(as, b, 1, RingPlan.Ev.TIMEOUT, fri1711, 5);
        ok("stop 只清自己的贪睡，别的闹钟贪睡照常", e != null && e.snooze && e.alarmId == 2 && e.at == fri1711 + 3 * 60_000L);
        as.remove(1);
        // 手动贪睡仍可：17:05 点贪睡 5 分钟 → 17:10 再响
        long fri1705 = t(2026, 11, 6, 17, 5, SH);
        e = endRing(as, new SnoozeBook(), 1, RingPlan.Ev.USER_SNOOZE, fri1705, 5);
        ok("stop 手动贪睡→17:10 再响", e != null && e.snooze && e.alarmId == 1 && e.at == t(2026, 11, 6, 17, 10, SH));
        // 旧 bug 回归：连续 14 轮超时也不会出现任何贪睡（0.1.4 会每 15 分钟循环）
        b = new SnoozeBook(); boolean loop = false; long now = t(2026, 11, 6, 17, 11, SH);
        for (int i = 0; i < 14; i++) { e = endRing(as, b, 1, RingPlan.Ev.TIMEOUT, now, 5); if (e == null || e.snooze) loop = true; now += 15 * 60_000L; }
        ok("stop 不再自动贪睡循环", !loop && b.entries().isEmpty());
        // 单次闹钟（onFired 已把它关掉）：超时停止后没有下一次
        Alarm once = al(5, 8, 0, 0); once.on = false;
        e = endRing(new ArrayList<Alarm>(Arrays.asList(once)), new SnoozeBook(), 5, RingPlan.Ev.TIMEOUT, t(2026, 11, 6, 8, 10, SH), 5);
        ok("stop 单次闹钟超时停止后不再响", e == null);
    }

    static void validity() {
        long at = t(2026, 11, 5, 7, 0, SH), now = at;
        BriefMeta m = new BriefMeta();
        m.forAlarmAt = at; m.generatedAt = at - 10 * 60_000L; m.day = AlarmMath.dayKey(at, SH);
        eq("valid OK", Validity.Verdict.OK, Validity.check(m, 50_000, at, now, SH));
        eq("valid 无成品", Validity.Verdict.NO_BRIEF, Validity.check(null, 50_000, at, now, SH));
        eq("valid 无音频", Validity.Verdict.NO_AUDIO, Validity.check(m, 100, at, now, SH));
        eq("valid 别的闹钟（旧稿）", Validity.Verdict.WRONG_ALARM, Validity.check(m, 50_000, at + 3600_000L, now, SH));
        BriefMeta y = BriefMeta.fromJson(m.toJson());
        y.day = 20261104;
        eq("valid 日期对不上", Validity.Verdict.WRONG_DAY, Validity.check(y, 50_000, at, now, SH));
        eq("valid 太旧", Validity.Verdict.TOO_OLD, Validity.check(m, 50_000, 0, now + 7 * 3600_000L, SH));
        BriefMeta f = BriefMeta.fromJson(m.toJson()); f.generatedAt = now + 3600_000L;
        eq("valid 来自未来", Validity.Verdict.FROM_FUTURE, Validity.check(f, 50_000, at, now, SH));
        eq("valid 试听只看新旧", Validity.Verdict.OK, Validity.check(m, 50_000, 0, now, SH));
        ok("meta json 往返", BriefMeta.fromJson(m.toJson()).forAlarmAt == at);
        ok("meta 坏 json=null", BriefMeta.fromJson("{oops") == null);
    }

    static List<String> five() {
        return Arrays.asList("重庆公安局局长被查", "董事长落马案连审四天", "山东被顶替上大学者否认卖学籍", "温州农民夫妻跳舞成名", "西安商铺门头被指不吉利");
    }

    static Template.Wx wx(double t, double lo, double hi, String key) {
        Template.Wx w = new Template.Wx(); w.ok = true; w.temp = t; w.tmin = lo; w.tmax = hi; w.key = key; return w;
    }

    static void template() {
        List<Template.Piece> ps = Template.pieces(22, 46, wx(25, 22, 26, "duoyun"), five(), "tencent", null);
        String txt = Template.text(ps);
        String want = "嘿，你好，现在是本地时间二十二点四十六分，室外温度为二十五摄氏度，最低温度为二十二度，最高温度为二十六度，今天是多云天气。\n"
                + "我们来看看由腾讯新闻提供的消息吧。\n重庆公安局局长被查。\n董事长落马案连审四天。\n山东被顶替上大学者否认卖学籍。\n温州农民夫妻跳舞成名。\n西安商铺门头被指不吉利。\n"
                + "目前就是这样了，一天好心情。";
        eq("tpl 三星参考逐字", want, txt);
        int news = 0;
        for (Template.Piece p : ps) if (p.isNews()) news++;
        eq("tpl 只有 5 条新闻是变量件", 5, news);
        String no = Template.text(Template.pieces(7, 5, wx(-3, -8, 2, "qinglang"), new ArrayList<String>(), "tencent", null));
        eq("tpl 没新闻→开场直接接收尾", "嘿，你好，现在是本地时间七点零五分，室外温度为零下三摄氏度，最低温度为零下八度，最高温度为二度，今天是晴朗天气。\n目前就是这样了，一天好心情。", no);
        Template.Opts o = new Template.Opts(); o.newsMissLine = true;
        ok("tpl 开关：新闻取不到说一句", Template.text(Template.pieces(7, 0, null, null, null, o)).contains("今天的新闻暂时没取到。"));
        ok("tpl 默认不说那句", !Template.text(Template.pieces(7, 0, null, null, null, null)).contains("暂时没取到"));
        ok("tpl 不足 3 条新闻跳过", !Template.text(Template.pieces(7, 0, null, five().subList(0, 2), "tencent", null)).contains("消息吧"));
        ok("tpl 3 条够了", Template.text(Template.pieces(7, 0, null, five().subList(0, 3), "tencent", null)).contains("由腾讯新闻提供"));
        eq("tpl 无天气只念时间", "嘿，你好，现在是本地时间七点整，\n目前就是这样了，一天好心情。", Template.text(Template.pieces(7, 0, null, null, null, null)));
        ok("tpl 越界温度不念", !Template.text(Template.pieces(7, 0, wx(55, 30, 56, "qinglang"), null, null, null)).contains("摄氏度"));
        ok("tpl 头条来源", Template.text(Template.pieces(7, 0, null, five(), "toutiao", null)).contains("由今日头条提供"));
        ok("tpl 未知来源念网络", Template.text(Template.pieces(7, 0, null, five(), "weibo", null)).contains("由网络提供"));
        ok("tpl 阴天措辞", Template.text(Template.pieces(7, 0, wx(10, 8, 12, "yin"), null, null, null)).contains("今天是阴天。"));
        String def = Template.text(Template.pieces(7, 0, wx(10, 8, 12, "xiaoyu"), five(), "tencent", null));
        ok("tpl 新增功能默认不出现", !def.contains("带伞") && !def.contains("生日"));
        Template.Wx rain = wx(10, 8, 12, "xiaoyu"); rain.rainProb = 80;
        Template.Opts x = new Template.Opts(); x.umbrella = true; x.birthdayToday = true;
        String ext = Template.text(Template.pieces(7, 0, rain, five(), "tencent", x));
        ok("tpl 开关：带伞/生日叠加在天气后、新闻前", ext.indexOf("带伞") > ext.indexOf("天气") && ext.indexOf("生日") < ext.indexOf("消息吧"));
        rain.rainProb = 20;
        ok("tpl 降水低不提醒带伞", !Template.text(Template.pieces(7, 0, rain, null, null, x)).contains("带伞"));
        for (int h = 0; h < 24; h++) for (int m = 0; m < 60; m++)
            for (Template.Piece p : Template.pieces(h, m, null, null, null, null))
                if (p.isBank() && Bank.text(p.bank) == null) { ok("tpl 件缺失 " + p.bank, false); return; }
        ok("tpl 全部 1440 个时刻的件都在库里", true);
    }

    static void bankManifest() throws Exception {
        // 朗读表（assets/phrases/zh-CN.json）是唯一文案来源：件数、键、读法与旧版逐字一致
        Map<String, Object[]> all = Bank.all();
        ok("bank 有 278 件", all.size() == 278);
        eq("朗读表 h02", "两点", Bank.text("h02"));
        eq("朗读表 m00", "整，", Bank.text("m00"));
        eq("朗读表 m05", "零五分，", Bank.text("m05"));
        eq("朗读表 tc-3", "零下三摄氏度，", Bank.text("tc-3"));
        eq("朗读表 td25", "二十五度，", Bank.text("td25"));
        eq("朗读表 阴天句", "今天是阴天。", Bank.text("wx_yin"));
        eq("朗读表 小雨句", "今天是小雨天气。", Bank.text("wx_xiaoyu"));
        ok("朗读表 新闻引导语速", Math.abs(Bank.rate("lead_toutiao") - 0.80) < 1e-9);
        ok("朗读表 固定句语速", Math.abs(Bank.rate("greet") - 0.90) < 1e-9);
        int bad = 0;
        for (int h = 0; h < 24; h++) if (!ZhNum.hour(h).equals(Bank.text(Bank.hourKey(h)))) bad++;
        for (int m = 0; m < 60; m++) if (!ZhNum.minute(m).equals(Bank.text(Bank.minuteKey(m)))) bad++;
        for (int t = Bank.T_MIN; t <= Bank.T_MAX; t++) {
            if (!(ZhNum.deg(t) + "摄氏度，").equals(Bank.text(Bank.tempCKey(t)))) bad++;
            if (!(ZhNum.deg(t) + "度，").equals(Bank.text(Bank.tempDKey(t)))) bad++;
        }
        eq("朗读表 数字读法与 ZhNum 一致", 0, bad);
        eq("朗读表 降雨句尾", "出门记得带伞。", Bank.rain("tail"));
    }

    static void pauses() {
        List<Template.Piece> ps = Template.pieces(22, 46, wx(25, 22, 26, "duoyun"), five(), "tencent", null);
        List<Integer> gaps = new ArrayList<Integer>();
        for (Template.Piece p : ps) if (p.isGap()) gaps.add(p.gapMs);
        List<Integer> want = Arrays.asList(700, 70, 10, 650, 70, 650, 70, 720, 70, 650, 570, 550, 950, 950, 950, 950, 1000, 600);
        eq("pause 停顿序列 = 原版实测表", want, gaps);
        eq("pause 条目间 950（0.1.1 重测）", 950, Pauses.ITEM);
        eq("pause 收尾→祝福 600（0.1.1 重测）", 600, Pauses.BLESS);
        eq("pause 末条→收尾 1000（0.1.1 重测）", 1000, Pauses.TO_CLOSER);
    }

    static short[] tone(int ms, double amp) {
        short[] s = new short[PcmJoin.SR * ms / 1000];
        for (int i = 0; i < s.length; i++) s[i] = (short) (amp * 32767 * Math.sin(2 * Math.PI * 440 * i / PcmJoin.SR));
        return s;
    }

    static void pcm() {
        short[] pad = new short[PcmJoin.SR / 2];
        short[] tn = tone(300, 0.3);
        short[] all = new short[pad.length * 2 + tn.length];
        System.arraycopy(tn, 0, all, pad.length, tn.length);
        short[] tr = PcmJoin.trim(all);
        ok("pcm 去首尾静音", Math.abs(tr.length - tn.length) < PcmJoin.SR / 50);
        short[] q = tone(300, 0.05);
        PcmJoin.normalize(q, -18);
        ok("pcm 归一到 -18±1", Math.abs(PcmJoin.activeDb(q) + 18) < 1.0);
        List<short[]> parts = Arrays.asList(tone(100, 0.3), null, tone(100, 0.3));
        PcmJoin.Out o = PcmJoin.join(parts, Arrays.asList(490, 0, 0));
        eq("pcm 拼接时长 = 头20+100+490+100+尾400", 1110L, o.durationMs);
        ok("pcm wav 头", PcmJoin.isWav(o.wav));
        short[] back = PcmJoin.fromWav(o.wav);
        ok("pcm wav 往返", back != null && back.length * 1000L / PcmJoin.SR == 1110L);
        ok("pcm 全缺=null", PcmJoin.join(Arrays.asList((short[]) null), Arrays.asList(0)) == null);
        ok("pcm 重采样 16k→24k", PcmJoin.resample(new short[16000], 16000, 24000).length == 24000);
    }

    static void assemble() {
        List<Template.Piece> ps = Template.pieces(7, 0, null, five(), "tencent", null);
        Assemble.Result r = Assemble.run(ps, new Assemble.Source() {
            public short[] pcm(Template.Piece p) { return p.isNews() && p.text.startsWith("温州") ? null : tone(200, 0.2); }
        });
        ok("asm 产出 wav", r.out != null && PcmJoin.isWav(r.out.wav));
        eq("asm 缺件计数", 1, r.missing);
        ok("asm 记下缺的件", r.missingKeys.get(0).startsWith("温州"));
    }

    static void alertUi() {
        eq("alert 贪睡 5 只能加", 10, AlertUi.snoozeStep(5, true)); eq("alert 5 减不动", 5, AlertUi.snoozeStep(5, false));
        eq("alert 60 加不动", 60, AlertUi.snoozeStep(60, true)); eq("alert 60 减", 55, AlertUi.snoozeStep(60, false));
        ok("alert −/+ 端点置灰", !AlertUi.minusEnabled(5) && !AlertUi.plusEnabled(60) && AlertUi.plusEnabled(5));
        eq("alert 起始夹到 5–60", 5, AlertUi.snoozeStart(1)); eq("alert 文案", "10 分钟后提醒", AlertUi.snoozeLabel(10));
        Calendar c = Calendar.getInstance(SH); c.setTimeInMillis(t(2026, 6, 14, 22, 46, SH));
        eq("alert 日期格式", "6月 14日, 周日", AlertUi.dateLabel(c)); eq("alert 时间格式", "22:46", AlertUi.timeLabel(c));
        ok("alert 拖到 0.95 直径解除", AlertUi.dismissReached(100, 105) && !AlertUi.dismissReached(99, 105));
        float[] h = AlertUi.halo(0); ok("alert 光晕起点 0.6", Math.abs(h[0] - 0.6f) < 1e-3);
        eq("alert 闹钟名默认", "闹钟", AlertUi.alarmName(" "));
    }

    static void news() {
        eq("news 去栏目前缀", "如何对抗焦虑", NewsMerge.clean("夜读丨如何对抗焦虑？"));
        eq("news 去【】与 emoji", "台风来了 注意防范", NewsMerge.clean("【突发】台风来了\uD83C\uDF00 注意防范！"));
        eq("news 去网址", "看这里", NewsMerge.clean("看这里 https://x.cn/a"));
        String longT = "这是一条很长很长的新闻标题，里面有很多很多的字，一直说一直说一直说，还没有说完还要继续说下去";
        ok("news 超长截到逗号前", NewsMerge.clean(longT).length() <= NewsMerge.MAX_LEN && !NewsMerge.clean(longT).endsWith("，"));
        ok("news 近似", NewsMerge.jaccard("马兴瑞被提起公诉", "检察机关依法对马兴瑞涉嫌受贿案提起公诉") > 0.2);
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("tencent", Arrays.asList("甲甲甲甲一", "乙乙乙乙二", "丙丙丙丙三", "丁丁丁丁四", "戊戊戊戊五", "戊戊戊戊五"));
        m.put("toutiao", Arrays.asList("头条一号新闻", "头条二号新闻"));
        NewsMerge.Result r = NewsMerge.pick(m, 5);
        ok("news 腾讯够 5 条→腾讯", r.sourceKey.equals("tencent") && r.titles.size() == 5);
        m.put("tencent", Arrays.asList("甲甲甲甲一", "乙乙乙乙二"));
        m.put("toutiao", Arrays.asList("甲甲甲甲一", "头条二号新闻", "头条三号新闻", "头条四号新闻", "头条五号新闻"));
        r = NewsMerge.pick(m, 5);
        eq("news 头条够→头条", "toutiao", r.sourceKey);
        m.put("toutiao", Arrays.asList("甲甲甲甲一", "头条二号新闻"));
        m.put("bilibili", Arrays.asList("哔哩一号热搜"));
        r = NewsMerge.pick(m, 5);
        ok("news 都不够→合并去重念网络", r.sourceKey.equals("net") && r.titles.size() == 4);
        m.clear();
        eq("news 全空", "", NewsMerge.pick(m, 5).sourceKey);
    }

    static void picker() {
        final long[] clock = {0};
        NewsPicker.Clock c = new NewsPicker.Clock() { public long now() { return clock[0]; } };
        List<String> pool = Arrays.asList("a1", "a2", "a3", "a4", "a5", "a6", "a7");
        NewsPicker.Result r = NewsPicker.pick(pool, new NewsPicker.Voice() {
            public boolean ensure(String t) { return !t.equals("a2"); }
        }, 1000, c);
        eq("pick 单条失败顺延下一条", Arrays.asList("a1", "a3", "a4", "a5", "a6"), r.chosen);
        r = NewsPicker.pick(pool, new NewsPicker.Voice() { public boolean ensure(String t) { return t.equals("a1") || t.equals("a2"); } }, 1000, c);
        ok("pick 不足 3 条整段跳过（不用本机 TTS）", r.skipped && r.chosen.isEmpty());
        r = NewsPicker.pick(pool, new NewsPicker.Voice() {
            public boolean ensure(String t) { clock[0] += 400; return true; }
        }, 1000, c);
        ok("pick 到截止时间就停", r.chosen.size() == 3);
        clock[0] = 0;
        r = NewsPicker.pick(pool, new NewsPicker.Voice() { public boolean ensure(String t) { throw new RuntimeException("x"); } }, 1000, c);
        ok("pick 合成抛异常当失败", r.skipped && r.failed == 5 + 2);
    }

    static void janitor() {
        long now = 100L * 86400_000L;
        List<CacheJanitor.F> fs = new ArrayList<CacheJanitor.F>();
        for (int i = 0; i < 10; i++) fs.add(new CacheJanitor.F("f" + i, 100, now - (10 - i) * 1000L));
        List<String> v = CacheJanitor.victims(fs, new HashSet<String>(Arrays.asList("f0")), now, 7, 1 << 20);
        eq("janitor 超件数从旧删，保护今天用到的", Arrays.asList("f1", "f2", "f3"), v);
        v = CacheJanitor.victims(fs, null, now, 100, 550);
        eq("janitor 超字节", 5, v.size());
        fs.add(new CacheJanitor.F("old", 1, now - 40L * 86400_000L));
        ok("janitor 超 30 天删", CacheJanitor.victims(fs, null, now, 100, 1 << 20).contains("old"));
    }

    static void state() {
        BriefMeta m = new BriefMeta(); m.city = "北京"; m.news.add("一"); m.news.add("二"); m.weatherLine = "今天是多云天气。";
        Map<String, Object> x = StateMsg.extras("READY", "", m, "/data/x/brief.json", 1L);
        ok("state 字段与 v2 同格式", x.keySet().containsAll(Arrays.asList("state", "detail", "briefPath", "title", "l1", "l2", "l3", "generatedAt", "forAlarmAt")));
        eq("state 标题", "北京 今日播报", x.get("title"));
        eq("state action 新包名", "com.onesay.clock.BRIEF_STATE", StateMsg.ACTION);
        ok("state json", StateMsg.json(x).contains("\"state\":\"READY\""));
    }

    static void shell() {
        Shell.R r = Shell.run(new String[]{"sh", "-c", "echo hi; echo err 1>&2"}, 3000);
        ok("shell 合并输出", r.ok && r.out.contains("hi") && r.out.contains("err"));
        long t0 = System.currentTimeMillis();
        r = Shell.run(new String[]{"sh", "-c", "sleep 5"}, 300);
        ok("shell 超时 destroy（P1-2）", r.timedOut && !r.ok && System.currentTimeMillis() - t0 < 2500);
        r = Shell.run(new String[]{"/no/such/bin"}, 300);
        ok("shell 不存在不抛", !r.ok);
        r = Shell.run(new String[]{"sh", "-c", "head -c 200000 /dev/zero | tr '\\0' a; exit 3"}, 5000);
        ok("shell 大输出不死锁", r.code == 3 && r.out.length() == 200000);
    }

    static void weather() {
        eq("wmo 0 晴朗", "qinglang", WeatherCode.fromWmo(0)); eq("wmo 2 多云", "duoyun", WeatherCode.fromWmo(2));
        eq("wmo 3 阴", "yin", WeatherCode.fromWmo(3)); eq("wmo 95 雷阵雨", "leizhenyu", WeatherCode.fromWmo(95));
        eq("wmo 未知", null, WeatherCode.fromWmo(42));
        eq("desc 小雨转阴", "xiaoyu", WeatherCode.fromDesc("小雨转阴")); eq("desc 局部多云", "duoyun", WeatherCode.fromDesc("局部多云"));
        eq("desc 雨夹雪", "yujiaxue", WeatherCode.fromDesc("雨夹雪"));
        eq("icon 多云不是雨（0.1.1 修）", "cloudy", WeatherCode.icon("duoyun", 12));
        eq("icon 阴", "cloudy", WeatherCode.icon("yin", 12));
        eq("icon 小雨", "rain", WeatherCode.icon("xiaoyu", 12)); eq("icon 阵雨", "rain", WeatherCode.icon("zhenyu", 12));
        eq("icon 有雾", "fog", WeatherCode.icon("youwu", 12)); eq("icon 雨夹雪", "snow", WeatherCode.icon("yujiaxue", 12));
        eq("icon 雷阵雨", "thunderstorm", WeatherCode.icon("leizhenyu", 12)); eq("icon 晴夜", "clear", WeatherCode.icon("qinglang", 23));
        String[] keys = {"yujiaxue","leizhenyu","baoyu","dayu","zhongyu","zhenyu","xiaoyu","xiayu","daxue","xiaoxue","xiaxue","wumai","youwu","shachen","duoyun","yin","qinglang","dafeng"};
        boolean allIcon = true; for (String k : keys) if (WeatherCode.icon(k, 12) == null) allIcon = false;
        ok("icon 18 个天气键都有图标", allIcon);
        eq("twc 26 阴", "yin", WeatherCode.fromTwc(26)); eq("twc 20 雾", "youwu", WeatherCode.fromTwc(20));
        ok("celsius 摄氏原样", WeatherCode.celsius(28.0, 1) == 28.0);
        ok("celsius 华氏换算", WeatherCode.celsius(82.0, 0) == 28.0);
        ok("celsius NaN", Double.isNaN(WeatherCode.celsius(Double.NaN, 1)));
        for (int c = 0; c < 100; c++) { String k = WeatherCode.fromWmo(c); if (k != null && Bank.wxWord(k) == null) ok("wmo 键不在库 " + c, false); }
        ok("wmo 全部映射在库里", true);
        eq("icon 夜里晴=月亮", "clear", WeatherCode.icon("qinglang", 22)); eq("icon 小雨", "rain", WeatherCode.icon("xiaoyu", 8));
        eq("icon 有雾", "fog", WeatherCode.icon("youwu", 8)); eq("icon 雨夹雪", "snow", WeatherCode.icon("yujiaxue", 8));
    }

    static void alarmJson() throws Exception {
        Alarm a = al(3, 6, 45, 0x3E); a.label = "上班"; a.brief = false; a.snoozeMin = 10;
        List<Alarm> back = Alarm.listFromJson(Alarm.listToJson(Arrays.asList(a)));
        ok("alarm json 往返", back.size() == 1 && back.get(0).minute == 45 && back.get(0).label.equals("上班") && !back.get(0).brief);
        eq("alarm 坏 json", 0, Alarm.listFromJson("[{").size());
        eq("days 工作日", "工作日", Alarm.daysLabel(0x3E)); eq("days 每天", "每天", Alarm.daysLabel(0x7F));
        eq("days 一三五", "每周一三五", Alarm.daysLabel(0x2A)); eq("days 单次", "只响一次", Alarm.daysLabel(0));
        Alarm d = Alarm.fromJson(new org.json.JSONObject("{\"id\":1}"));
        ok("alarm 默认：播报开、贪睡 5 分钟", d.brief && d.snoozeMin == 5 && d.on);
    }

    static void alarmApi() throws Exception {
        List<Alarm> base = new ArrayList<Alarm>(Arrays.asList(al(1, 6, 30, 0x3E), al(990, 8, 0, 0)));
        AlarmApi.Result r = AlarmApi.apply(base, AlarmApi.A_SET, -1, 7, 5, 0, false, "起床\u202E\n");
        ok("api set 新建", r.code == AlarmApi.RC_OK && r.changed && r.lastId == 2 && r.alarms.size() == 3);
        eq("api set 不改入参", 2, base.size());
        Alarm n = null; for (Alarm a : r.alarms) if (a.id == 2) n = a;
        ok("api set 字段", n != null && n.hour == 7 && n.minute == 5 && n.days == 0 && n.on && n.brief && "起床".equals(n.label));
        base.get(0).on = false;
        r = AlarmApi.apply(base, AlarmApi.A_SET, -1, 6, 30, 0x3E, false, null);
        ok("api set 同时刻同重复 → 打开原闹钟不重复建", r.code == AlarmApi.RC_OK && r.lastId == 1 && r.alarms.size() == 2 && r.alarms.get(0).on);
        eq("api set 坏时刻", AlarmApi.RC_BAD_ARGS, AlarmApi.apply(base, AlarmApi.A_SET, -1, 24, 0, 0, false, "").code);
        eq("api set 没给分钟", AlarmApi.RC_BAD_ARGS, AlarmApi.apply(base, AlarmApi.A_SET, -1, 7, -1, 0, false, "").code);
        r = AlarmApi.apply(base, AlarmApi.A_TOGGLE, 1, -1, -1, 0, true, null);
        ok("api toggle", r.code == AlarmApi.RC_OK && r.changed && r.alarms.get(0).on);
        eq("api toggle 找不到", AlarmApi.RC_NOT_FOUND, AlarmApi.apply(base, AlarmApi.A_TOGGLE, 5, -1, -1, 0, true, null).code);
        eq("api 调试闹钟不能经接口改", AlarmApi.RC_BAD_ARGS, AlarmApi.apply(base, AlarmApi.A_CANCEL, 990, -1, -1, 0, false, null).code);
        r = AlarmApi.apply(base, AlarmApi.A_CANCEL, 1, -1, -1, 0, false, null);
        ok("api cancel", r.code == AlarmApi.RC_OK && r.alarms.size() == 1 && r.alarms.get(0).id == 990);
        eq("api 未知动作", AlarmApi.RC_UNKNOWN, AlarmApi.apply(base, "com.onesay.clock.action.X", 1, 1, 1, 0, false, null).code);
        ok("api known", AlarmApi.known(AlarmApi.A_QUERY) && !AlarmApi.known(null) && !AlarmApi.known("com.onesay.clock.DUMP"));
        long now = t(2026, 11, 7, 8, 0, SH);
        org.json.JSONObject d = new org.json.JSONObject(AlarmApi.dump(Arrays.asList(al(1, 6, 30, 0), al(990, 9, 0, 0)), 1, now, SH));
        ok("api dump 不含调试闹钟", d.getInt("count") == 1 && d.getJSONArray("alarms").length() == 1);
        org.json.JSONObject o = d.getJSONArray("alarms").getJSONObject(0);
        ok("api dump 字段（OneSay 卡片格式）", o.getInt("h") == 6 && o.getInt("m") == 30 && o.getInt("d") == 0 && o.getBoolean("on")
                && "smart".equals(o.getString("r")) && o.getLong("next") == t(2026, 11, 8, 6, 30, SH) && d.getInt("lastId") == 1 && d.getLong("ts") == now);
        List<Alarm> full = new ArrayList<Alarm>(); for (int i = 1; i <= 899; i++) full.add(al(i, 7, 0, 0));
        eq("api 普通 id 用完不进调试段", -1, AlarmApi.nextId(full));
        eq("api 权限名", "com.onesay.clock.permission.ALARM_CONTROL", AlarmApi.PERMISSION);
    }
}
