package com.onesay.assistant.v2.rain;

import com.onesay.assistant.v2.notice.IslandNotice;
import com.onesay.assistant.v2.notice.NoticeCenter;
import com.onesay.assistant.v2.notice.NoticePolicy;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ feat/rain-minutely：分钟级降水的解析 / 分析 / 语音 / 岛 / 节奏。夹具 test-jvm/fixtures/rain/：
 * real-* 是 2026-11-06 19:25 CST 从和风 /v7/minutely/5m 抓的真实响应（已去掉 Key / Host、fxLink 换成首页）；synthetic-* 由真实样本改 precip 得来。
 */
public class RainMinutelyTest {

    static String fx(String name) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), "test-jvm/fixtures/rain/" + name).toPath()), "UTF-8");
    }

    static RainForecast f(String name) throws Exception {
        return RainForecast.parse(fx(name));
    }

    /** 2026-11-06 19:25 +08:00（第一个点）。 */
    static final long T0 = RainForecast.parseTime("2026-11-06T19:25+08:00");
    static final long MIN = 60000L;

    // ------------------------------------------------------------------ 解析

    @Test public void parsesRealIntermittentSample() throws Exception {
        RainForecast fc = f("qweather-real-intermittent-20261106-1925.json");
        assertEquals("间歇性降雨还将持续90分钟", fc.summary);
        assertEquals(24, fc.points.size());
        assertEquals(T0, fc.points.get(0).at);
        assertEquals(T0 + 5 * MIN, fc.points.get(1).at);
        assertEquals(0.12, fc.points.get(0).precip, 1e-9);
        assertFalse(fc.points.get(0).snow);
        assertFalse(fc.points.get(7).wet());   // 20:00 0.00
    }

    @Test public void parseTimeHandlesOffsetsAndZ() {
        assertEquals(RainForecast.parseTime("2026-11-06T11:25Z"), T0);
        assertEquals(RainForecast.parseTime("2026-11-06T19:25:00+08:00"), T0);
        assertEquals(RainForecast.parseTime("2026-11-06T19:25+0800"), T0);
        assertEquals(RainForecast.parseTime("2026-11-06T19:25"), T0);   // 没时区按 +08:00
        assertEquals(0, RainForecast.parseTime("Fri 7:25 PM"));
        assertEquals(0, RainForecast.parseTime(null));
    }

    @Test public void errorResponses() throws Exception {
        RainForecast.BadResponse e = RainForecast.parseError(400, fx("qweather-real-error-400-outside-china.json"));
        assertTrue(e.noCoverage);
        assertTrue(e.getMessage().contains("只覆盖中国"));
        assertFalse(RainForecast.parseError(401, "{}").noCoverage);
        assertTrue(RainForecast.parseError(401, "{}").getMessage().contains("key"));
        try {
            RainForecast.parse("{\"code\":\"402\"}");
            fail();
        } catch (RainForecast.BadResponse ok) {
            assertTrue(ok.getMessage().contains("402"));
        }
        try {
            RainForecast.parse("<html>");
            fail();
        } catch (RainForecast.BadResponse ok) {
            assertFalse(ok.noCoverage);
        }
    }

    // ------------------------------------------------------------------ 分析

    @Test public void realIntermittentIsRainingAndStopsFor10Min() throws Exception {
        RainAnalysis a = RainAnalysis.of(f("qweather-real-intermittent-20261106-1925.json"), T0 + 2 * MIN);
        assertEquals(RainAnalysis.State.STOPS, a.state);
        assertEquals(33, a.stopInMin);   // 20:00 第一个干点；19:27 起算向上取整
        assertTrue(a.intermittent);
        assertEquals("小雨", a.intensity());
        assertEquals(a.horizonMin, a.lastWetEndMin);   // 最后一个点（21:20）还在下
        assertEquals(T0 + 35 * MIN, a.eventAt);
        assertEquals(24, a.bars.length);
    }

    @Test public void dropsPastPointsAsTimeMovesOn() throws Exception {
        RainForecast fc = f("qweather-real-intermittent-20261106-1925.json");
        // 20:02：落在 20:00 那个干点 → 现在没下，20:10 又开始
        RainAnalysis a = RainAnalysis.of(fc, T0 + 37 * MIN);
        assertEquals(RainAnalysis.State.STARTS, a.state);
        assertEquals(8, a.startInMin);
        assertEquals(17, a.bars.length);
        // 全部过期 → null（数据太旧）
        assertNull(RainAnalysis.of(fc, T0 + 121 * MIN));
    }

    @Test public void realDryAndSyntheticCases() throws Exception {
        RainAnalysis dry = RainAnalysis.of(f("qweather-real-dry-20261106-1925.json"), T0);
        assertEquals(RainAnalysis.State.DRY, dry.state);
        assertEquals(-1, dry.changeInMin());
        RainAnalysis st = RainAnalysis.of(f("qweather-synthetic-starts-20261106-1925.json"), T0);
        assertEquals(RainAnalysis.State.STARTS, st.state);
        assertEquals(15, st.startInMin);
        assertEquals(45, st.stopInMin);
        assertFalse(st.intermittent);
        RainAnalysis sp = RainAnalysis.of(f("qweather-synthetic-stops-20261106-1925.json"), T0);
        assertEquals(RainAnalysis.State.STOPS, sp.state);
        assertEquals(40, sp.stopInMin);
        assertFalse(sp.intermittent);
        RainAnalysis sn = RainAnalysis.of(f("qweather-synthetic-snow-continues-20261106-1925.json"), T0);
        assertEquals(RainAnalysis.State.CONTINUES, sn.state);
        assertTrue(sn.snow);
        assertEquals("雪", sn.what());
        assertEquals("中雪", sn.intensity());   // 0.20 × 12 = 2.4 mm/h（雪：<1 小、<3 中、≥3 大）
    }

    @Test public void barLevels() {
        assertEquals("0", RainAnalysis.barLevels(new double[]{0}));
        assertEquals("3", RainAnalysis.barLevels(new double[]{0.06}));
        assertEquals("8", RainAnalysis.barLevels(new double[]{0.5}));
        assertEquals("8", RainAnalysis.barLevels(new double[]{3.0}));
        assertEquals("", RainAnalysis.barLevels(null));
    }

    // ------------------------------------------------------------------ 语音：哪些问句

    @Test public void classifiesRainQuestions() {
        assertEquals(RainVoice.Q.WILL, RainVoice.classify("会下雨吗", ""));
        assertEquals(RainVoice.Q.WILL, RainVoice.classify("等会会不会下雨？", ""));
        assertEquals(RainVoice.Q.WILL, RainVoice.classify("外面下雨了吗", ""));
        assertEquals(RainVoice.Q.WILL, RainVoice.classify("是不是要下雨了", ""));
        assertEquals(RainVoice.Q.WILL, RainVoice.classify("一会儿会下雪吗", ""));
        assertEquals(RainVoice.Q.WHEN_START, RainVoice.classify("什么时候下雨", ""));
        assertEquals(RainVoice.Q.WHEN_START, RainVoice.classify("还有多久下雨", ""));
        assertEquals(RainVoice.Q.WHEN_START, RainVoice.classify("几分钟后会下雨", ""));
        assertEquals(RainVoice.Q.WHEN_STOP, RainVoice.classify("雨什么时候停", ""));
        assertEquals(RainVoice.Q.WHEN_STOP, RainVoice.classify("雨还要下多久啊", ""));
        assertEquals(RainVoice.Q.WHEN_STOP, RainVoice.classify("雨啥时候停", ""));
        assertEquals(RainVoice.Q.WHEN_STOP, RainVoice.classify("雨停了吗", ""));
        assertEquals(RainVoice.Q.WHEN_STOP, RainVoice.classify("什么时候停雨", ""));
        assertEquals(RainVoice.Q.UMBRELLA, RainVoice.classify("要带伞吗", ""));
        assertEquals(RainVoice.Q.UMBRELLA, RainVoice.classify("出门需要带伞吗？", ""));
        assertEquals(RainVoice.Q.UMBRELLA, RainVoice.classify("用不用带把伞", ""));
        assertEquals(RainVoice.Q.GENERAL, RainVoice.classify("天气怎么样", ""));
        assertEquals(RainVoice.Q.GENERAL, RainVoice.classify("现在天气如何", ""));
        assertEquals(RainVoice.Q.GENERAL, RainVoice.classify("帮我看看外面天气怎么样", ""));
        // 本地城市名照样接
        assertEquals(RainVoice.Q.WILL, RainVoice.classify("佛山会下雨吗", "佛山"));
        assertEquals(RainVoice.Q.WHEN_STOP, RainVoice.classify("佛山市的雨什么时候停", "佛山"));
    }

    @Test public void leavesOtherQuestionsToOldPath() {
        assertNull(RainVoice.classify("北京会下雨吗", "佛山"));        // 别的城市
        assertNull(RainVoice.classify("明天会下雨吗", ""));            // 超出 2 小时
        assertNull(RainVoice.classify("今天天气怎么样", ""));          // 日预报交给联网问答
        assertNull(RainVoice.classify("今晚会下雨吗", ""));
        assertNull(RainVoice.classify("周末会下雨吗", ""));
        assertNull(RainVoice.classify("下午三点会下雨吗", ""));
        assertNull(RainVoice.classify("提醒我下雨带伞", ""));          // 办事句
        assertNull(RainVoice.classify("下雨天适合听什么歌", ""));
        assertNull(RainVoice.classify("下雨用英语怎么说", ""));
        assertNull(RainVoice.classify("天气预报说下周有台风", ""));
        assertNull(RainVoice.classify("帮我买一把伞", ""));
        assertNull(RainVoice.classify("", ""));
        assertNull(RainVoice.classify(null, ""));
    }

    // ------------------------------------------------------------------ 语音：怎么说

    @Test public void answersPlainChinese() throws Exception {
        RainForecast fc = f("qweather-real-intermittent-20261106-1925.json");
        RainAnalysis a = RainAnalysis.of(fc, T0 + 2 * MIN);
        String s = RainVoice.answer(RainVoice.Q.WHEN_STOP, a, fc.summary, null);
        assertTrue(s, s.startsWith("现在在下小雨，大概 33 分钟后停，不过之后还会再下。"));
        assertTrue(s, s.endsWith("间歇性降雨还将持续90分钟。"));
        String u = RainVoice.answer(RainVoice.Q.UMBRELLA, a, fc.summary, null);
        assertTrue(u, u.startsWith("现在在下小雨，要带伞"));

        RainForecast d = f("qweather-real-dry-20261106-1925.json");
        RainAnalysis dry = RainAnalysis.of(d, T0);
        assertEquals("未来两小时不会下雨。", RainVoice.answer(RainVoice.Q.WILL, dry, d.summary, null));
        assertEquals("未来两小时不会下雨，不用带伞。", RainVoice.answer(RainVoice.Q.UMBRELLA, dry, d.summary, null));
        assertEquals("现在没在下雨，未来两小时也不会下。", RainVoice.answer(RainVoice.Q.WHEN_STOP, dry, d.summary, null));
        RainVoice.Now now = RainVoice.Now.parse(fx("qweather-real-now-20261106.json"));
        assertEquals("现在 26 度，阴。未来两小时不会下雨。", RainVoice.answer(RainVoice.Q.GENERAL, dry, d.summary, now));

        RainForecast st = f("qweather-synthetic-starts-20261106-1925.json");
        String w = RainVoice.answer(RainVoice.Q.WHEN_START, RainAnalysis.of(st, T0), st.summary, null);
        assertEquals("大概 15 分钟后开始下小雨，下一会儿就停。", w);   // summary 的 15 / 30 都已说到或在一句里 → 不念
        String um = RainVoice.answer(RainVoice.Q.UMBRELLA, RainAnalysis.of(st, T0), st.summary, null);
        assertTrue(um, um.contains("出门记得带伞"));

        RainForecast sp = f("qweather-synthetic-stops-20261106-1925.json");
        assertEquals("现在在下小雨，大概 40 分钟后停。", RainVoice.answer(RainVoice.Q.WHEN_STOP, RainAnalysis.of(sp, T0), sp.summary, null));

        RainForecast sn = f("qweather-synthetic-snow-continues-20261106-1925.json");
        String snow = RainVoice.answer(RainVoice.Q.WHEN_STOP, RainAnalysis.of(sn, T0), sn.summary, null);
        assertTrue(snow, snow.startsWith("现在在下中雪，未来两小时都不会停。"));
    }

    @Test public void failuresAreHonest() {
        assertTrue(RainVoice.failure(RainVoice.Fail.NO_COVERAGE).contains("只覆盖国内"));
        assertTrue(RainVoice.failure(RainVoice.Fail.NETWORK).contains("网络出错"));
        assertTrue(RainVoice.failure(RainVoice.Fail.NO_LOCATION).contains("位置"));
        assertTrue(RainVoice.failure(RainVoice.Fail.NO_KEY).contains("key"));
        assertTrue(RainVoice.failure(null).contains("网络"));
        for (RainVoice.Fail x : RainVoice.Fail.values()) assertFalse(RainVoice.failure(x).contains("qweatherapi"));
    }

    // ------------------------------------------------------------------ 节奏

    @Test public void pollCadence() throws Exception {
        RainAnalysis dry = RainAnalysis.of(f("qweather-real-dry-20261106-1925.json"), T0);
        RainAnalysis wet = RainAnalysis.of(f("qweather-real-intermittent-20261106-1925.json"), T0);
        RainAnalysis starts15 = RainAnalysis.of(f("qweather-synthetic-starts-20261106-1925.json"), T0);
        assertEquals(15 * MIN, RainPolicy.nextDelayMs(dry, true, 0, false));
        assertEquals(15 * MIN, RainPolicy.nextDelayMs(null, true, 0, false));
        assertEquals(5 * MIN, RainPolicy.nextDelayMs(wet, true, 0, false));
        assertEquals(5 * MIN, RainPolicy.nextDelayMs(starts15, true, 0, false));
        assertEquals(30 * MIN, RainPolicy.nextDelayMs(wet, false, 0, false));   // 息屏降频
        // 45 分钟后才开始 → 不加密
        RainAnalysis starts45 = RainAnalysis.of(f("qweather-synthetic-starts-20261106-1925.json"), T0 - 30 * MIN);
        assertEquals(45, starts45.startInMin);
        assertEquals(15 * MIN, RainPolicy.nextDelayMs(starts45, true, 0, false));
        // 退避
        assertEquals(5 * MIN, RainPolicy.nextDelayMs(null, true, 1, false));
        assertEquals(10 * MIN, RainPolicy.nextDelayMs(null, true, 2, false));
        assertEquals(40 * MIN, RainPolicy.nextDelayMs(null, false, 4, false));
        assertEquals(60 * MIN, RainPolicy.nextDelayMs(null, true, 9, false));
        assertEquals(60 * MIN, RainPolicy.nextDelayMs(null, true, 1, true));
    }

    @Test public void monthlyEstimateFitsFreeQuota() {
        assertEquals(2400, RainPolicy.monthlyEstimate(16, 0, 30));
        assertEquals(6240, RainPolicy.monthlyEstimate(16, 16, 30));
        assertTrue(RainPolicy.monthlyEstimate(24, 24, 31) < 50000);
    }

    // ------------------------------------------------------------------ 岛

    @Test public void islandOnlyForChangesWithin60Min() throws Exception {
        RainPolicy.Episode ep = new RainPolicy.Episode();
        RainForecast st = f("qweather-synthetic-starts-20261106-1925.json");
        IslandNotice n = RainPolicy.notice(RainAnalysis.of(st, T0 + 3 * MIN), st.summary, ep, T0 + 3 * MIN);
        assertNotNull(n);
        assertEquals("12 分钟后下雨", n.title);
        assertEquals(IslandNotice.Source.WEATHER, n.source);
        assertEquals("rain", n.sourceTag);
        assertEquals("15分钟后开始下雨，30分钟后雨停", n.summary);
        assertEquals(24, n.bars.length());
        assertTrue(n.dedupKey.startsWith("rain:start:"));
        assertEquals(T0 + 15 * MIN + 10 * MIN, n.expireAt);
        // 同一阵：分钟数变了、键不变（中枢就地更新，不再提醒一次）
        IslandNotice n2 = RainPolicy.notice(RainAnalysis.of(st, T0 + 10 * MIN), st.summary, ep, T0 + 10 * MIN);
        assertEquals(n.dedupKey, n2.dedupKey);
        assertEquals("5 分钟后下雨", n2.title);
        // 开始下了 → 变成「停」，换一阵
        IslandNotice n3 = RainPolicy.notice(RainAnalysis.of(st, T0 + 20 * MIN), st.summary, ep, T0 + 20 * MIN);
        assertEquals("雨约 25 分钟后停", n3.title);
        assertTrue(n3.dedupKey.startsWith("rain:stop:"));
        // 停了 → 不放（同步撤下 = 自动收起）
        assertNull(RainPolicy.notice(RainAnalysis.of(st, T0 + 50 * MIN), st.summary, ep, T0 + 50 * MIN));
        // 一直下 / 一直不下 → 不放
        RainForecast sn = f("qweather-synthetic-snow-continues-20261106-1925.json");
        assertNull(RainPolicy.notice(RainAnalysis.of(sn, T0), sn.summary, ep, T0));
        RainForecast d = f("qweather-real-dry-20261106-1925.json");
        assertNull(RainPolicy.notice(RainAnalysis.of(d, T0), d.summary, ep, T0));
        // 70 分钟后才下 → 超出 60 分钟，不放
        assertNull(RainPolicy.notice(RainAnalysis.of(st, T0 - 55 * MIN), st.summary, ep, T0 - 55 * MIN));
    }

    @Test public void islandTitles() throws Exception {
        RainForecast st = f("qweather-synthetic-starts-20261106-1925.json");
        assertEquals("马上要下雨了", RainPolicy.title(RainAnalysis.of(st, T0 + 15 * MIN - 30000L)));
        RainForecast sp = f("qweather-synthetic-stops-20261106-1925.json");
        assertEquals("雨约 40 分钟后停", RainPolicy.title(RainAnalysis.of(sp, T0)));
        assertEquals("雨马上停", RainPolicy.title(RainAnalysis.of(sp, T0 + 39 * MIN + 30000L)));
    }

    /** 端到端：经提醒中枢 sync —— 新一阵上岛；状态变了就地更新；结束同步撤下；用户点掉的那一阵不再出现，下一阵照常。 */
    @Test public void throughNoticeCenter() throws Exception {
        final long[] clock = {T0 + 3 * MIN};
        NoticeCenter c = new NoticeCenter(null, new NoticeCenter.Clock() {
            @Override public long now() { return clock[0]; }
        });
        NoticePolicy.Env env = new NoticePolicy.Env();
        env.quietEnabled = false;
        RainPolicy.Episode ep = new RainPolicy.Episode();
        RainForecast st = f("qweather-synthetic-starts-20261106-1925.json");
        NoticeCenter.Tick t = c.sync(IslandNotice.Source.WEATHER, "rain", list(RainPolicy.notice(RainAnalysis.of(st, clock[0]), st.summary, ep, clock[0])), env);
        assertEquals(1, t.surfaced.size());
        assertTrue(t.alert);
        clock[0] = T0 + 8 * MIN;
        t = c.sync(IslandNotice.Source.WEATHER, "rain", list(RainPolicy.notice(RainAnalysis.of(st, clock[0]), st.summary, ep, clock[0])), env);
        assertFalse(t.changed());
        assertEquals("7 分钟后下雨", c.active().get(0).title);
        // 用户点掉「开始」这一阵
        String startKey = c.active().get(0).dedupKey;
        c.dismiss(startKey, new NoticeCenter.Tick());
        clock[0] = T0 + 10 * MIN;
        t = c.sync(IslandNotice.Source.WEATHER, "rain", list(RainPolicy.notice(RainAnalysis.of(st, clock[0]), st.summary, ep, clock[0])), env);
        assertTrue(c.active().isEmpty());
        // 下起来了 → 「停」是新的一阵，照常上岛
        clock[0] = T0 + 20 * MIN;
        t = c.sync(IslandNotice.Source.WEATHER, "rain", list(RainPolicy.notice(RainAnalysis.of(st, clock[0]), st.summary, ep, clock[0])), env);
        assertEquals(1, c.active().size());
        assertEquals("雨约 25 分钟后停", c.active().get(0).title);
        assertEquals(24 - 4, c.active().get(0).bars.length());
        // 停了 → 同步撤下
        clock[0] = T0 + 50 * MIN;
        t = c.sync(IslandNotice.Source.WEATHER, "rain", list(RainPolicy.notice(RainAnalysis.of(st, clock[0]), st.summary, ep, clock[0])), env);
        assertEquals(1, t.gone.size());
        assertTrue(c.active().isEmpty());
    }

    @Test public void noticeBarsSurviveJsonRoundTrip() {
        IslandNotice n = new IslandNotice(IslandNotice.Source.WEATHER, "rain:start:1", IslandNotice.Priority.NORMAL, "12 分钟后下雨", "x");
        n.bars = "003358";
        IslandNotice back = IslandNotice.fromJson(n.toJson());
        assertEquals("003358", back.bars);
        assertEquals(IslandNotice.Source.WEATHER, back.source);
        assertEquals("003358", n.copy().bars);
        assertTrue(NoticePolicy.cap(null, IslandNotice.Source.WEATHER) >= 10);
    }

    static List<IslandNotice> list(IslandNotice n) {
        if (n == null) return Collections.emptyList();
        List<IslandNotice> l = new ArrayList<IslandNotice>();
        l.add(n);
        return l;
    }
}
