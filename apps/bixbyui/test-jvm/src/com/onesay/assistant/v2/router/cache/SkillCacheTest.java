package com.onesay.assistant.v2.router.cache;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** P0-5 / P1-8：模板化技能缓存策略（内存存储 + 假能力表 + 假时钟）。 */
public class SkillCacheTest {

    static final class FakeOracle implements SkillCache.CapabilityOracle {
        final Map<String, String> ver = new HashMap<String, String>();
        final Map<String, Integer> risk = new HashMap<String, Integer>();
        FakeOracle add(String sign, int r) { ver.put(sign, "v1:" + sign); risk.put(sign, r); return this; }
        @Override public String versionOf(String sign) { return ver.get(sign); }
        @Override public int risk(String sign) { Integer r = risk.get(sign); return r == null ? Integer.MAX_VALUE : r; }
        @Override public boolean validate(String sign, JSONObject args) {
            // 模拟 Schema：亮度 0..255
            if ("settings.brightness.set".equals(sign)) {
                long l = args.optLong("level", -1);
                return l >= 0 && l <= 255;
            }
            return true;
        }
    }

    static final class FakeClock implements SkillCache.Clock {
        long t = 1_000_000L;
        @Override public long now() { return t; }
    }

    FakeOracle oracle;
    FakeClock clock;
    InMemorySkillStore store;
    SkillCache cache;

    @Before public void setUp() {
        oracle = new FakeOracle()
                .add("settings.brightness.set", 1).add("settings.brightness.down", 0).add("settings.brightness.up", 0)
                .add("clock.alarm.set", 1).add("clock.timer.set", 1).add("app.launch", 1)
                .add("connectivity.bt.on", 1).add("connectivity.wifi.off", 1).add("battery.saver.on", 1)
                .add("battery.level", 0).add("settings.rotation.off", 1).add("app.uninstall", 2)
                .add("notes.new.text", 1);
        clock = new FakeClock();
        store = new InMemorySkillStore();
        cache = new SkillCache(store, "e1", oracle, clock);
    }

    static JSONObject j(String s) {
        try { return new JSONObject(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static final SkillCache.Outcome OK = SkillCache.Outcome.success(false);
    static final SkillCache.Outcome OK_VERIFIED = SkillCache.Outcome.success(true);

    void verified(String utt, String sign, String args) {
        SkillCache.Record r = cache.recordSuccess(utt, "DO_ACT", sign, j(args), OK_VERIFIED, false);
        assertTrue(utt + " → " + r, r == SkillCache.Record.VERIFIED || r == SkillCache.Record.PROMOTED);
    }

    // ---------------- 规范化 ----------------

    @Test public void cacheKeyNormalization() {
        assertEquals("亮度调到{n}", CacheKey.of("把亮度调到三十").shape);
        assertEquals("[30]", CacheKey.of("把亮度调到三十").numbers.toString());
        assertEquals("打开蓝牙", CacheKey.of("请帮我打开一下蓝牙吧").literal);
        assertEquals("亮度调高一点", CacheKey.of("亮度调高一点").literal);   // "一点"=一些，不转数字
        assertEquals("7点叫醒我", CacheKey.of("七点叫醒我").literal);
        assertEquals("音量调到50%", CacheKey.of("音量调到百分之五十").literal);
        assertEquals("定个闹钟", CacheKey.of("定个闹钟").literal);
        assertEquals(120, CacheKey.parseCn("一百二十"));
        assertEquals(15, CacheKey.parseCn("十五"));
        assertEquals(2, CacheKey.parseCn("两"));
    }

    // ---------------- 审计 §8.4 用例 ----------------

    @Test public void slotGeneralization() throws Exception {
        assertEquals(SkillCache.Record.CANDIDATE,
                cache.recordSuccess("亮度调到30", "DO_ACT", "settings.brightness.set", j("{\"level\":30}"), OK, false));
        assertNull("candidate 不可命中", cache.lookup("亮度调到30", "DO_ACT"));
        assertEquals(SkillCache.Record.PROMOTED,
                cache.recordSuccess("亮度调到30", "DO_ACT", "settings.brightness.set", j("{\"level\":30}"), OK, false));
        SkillCache.Hit h = cache.lookup("亮度调到50", "DO_ACT");
        assertNotNull(h);
        assertEquals("settings.brightness.set", h.sign);
        assertEquals(50, h.args.getInt("level"));                       // 代入新槽值，不是回放 30
        assertTrue(h.generalized);
        assertEquals(30, cache.lookup("把亮度调到三十", "DO_ACT").args.getInt("level"));
        assertNull("Schema 越界不命中", cache.lookup("亮度调到900", "DO_ACT"));
    }

    @Test public void verifiedOrRiskZeroIsActiveImmediately() {
        assertEquals(SkillCache.Record.VERIFIED, cache.recordSuccess("天太黑了屏幕刺眼帮我调低点亮度", "DO_ACT",
                "settings.brightness.down", j("{}"), OK, false));     // risk 0
        assertNotNull(cache.lookup("天太黑了屏幕刺眼帮我调低点亮度", "DO_ACT"));
        assertEquals(SkillCache.Record.VERIFIED, cache.recordSuccess("开蓝牙", "DO_ACT",
                "connectivity.bt.on", j("{}"), OK_VERIFIED, false));  // 回读验证通过
        assertNotNull(cache.lookup("开蓝牙", "DO_ACT"));
    }

    @Test public void neverCacheInventedDefaults() {
        assertEquals(SkillCache.Record.REJ_INVENTED_ARG,
                cache.recordSuccess("定个闹钟", "DO_ACT", "clock.alarm.set", j("{\"hour\":7,\"minute\":0}"), OK_VERIFIED, false));
        assertNull(cache.lookup("定个闹钟", "DO_ACT"));
        assertEquals(SkillCache.Record.REJ_INVENTED_ARG,
                cache.recordSuccess("定个计时器", "DO_ACT", "clock.timer.set", j("{\"seconds\":300}"), OK_VERIFIED, false));
        assertEquals(SkillCache.Record.REJ_INVENTED_ARG,
                cache.recordSuccess("调亮一点", "DO_ACT", "settings.brightness.set", j("{\"level\":200}"), OK_VERIFIED, false));
        assertEquals("只有 0 默认值、没有任何落地参数也拒写", SkillCache.Record.REJ_INVENTED_ARG,
                cache.recordSuccess("计时器", "DO_ACT", "clock.timer.set", j("{\"seconds\":0}"), OK_VERIFIED, false));
    }

    @Test public void groundedTimeWithImplicitZeroMinuteIsOk() throws Exception {
        verified("七点叫醒我", "clock.alarm.set", "{\"hour\":7,\"minute\":0}");
        SkillCache.Hit h = cache.lookup("八点叫醒我", "DO_ACT");
        assertNotNull(h);
        assertEquals(8, h.args.getInt("hour"));
        assertEquals(0, h.args.getInt("minute"));
    }

    @Test public void neverCacheFailuresOrDenials() {
        assertEquals(SkillCache.Record.REJ_FAILED, cache.recordSuccess("打开某某", "DO_ACT", "app.launch",
                j("{\"app\":\"某某\"}"), SkillCache.Outcome.failed("NOT_FOUND_APP"), false));
        assertEquals(SkillCache.Record.REJ_FAILED, cache.recordSuccess("关掉wifi", "DO_ACT", "connectivity.wifi.off",
                j("{}"), SkillCache.Outcome.denied(), false));
        assertEquals("ok 但 code=VERIFY_FAILED 不算成功", SkillCache.Record.REJ_FAILED,
                cache.recordSuccess("关掉wifi", "DO_ACT", "connectivity.wifi.off", j("{}"),
                        new SkillCache.Outcome(true, "VERIFY_FAILED", false, false), false));
        assertNull(cache.lookup("打开某某", "DO_ACT"));
        assertNull(cache.lookup("关掉wifi", "DO_ACT"));
    }

    @Test public void labelMustMatch() {
        verified("怎么关自动旋转", "settings.rotation.off", "{}");
        assertNull("DO 条目不满足 ASK 查询", cache.lookup("怎么关自动旋转", "ASK"));
        cache.recordSuccess("什么是蓝牙", "ASK", "ask.reply", j("{\"text\":\"蓝牙是一种短距离无线通信技术。\"}"), OK, false);
        assertNotNull(cache.lookup("什么是蓝牙", "ASK"));
        assertNull("ASK 条目不满足 DO 查询", cache.lookup("什么是蓝牙", "DO_READ"));
        assertNull("MIXED 不走缓存", cache.lookup("什么是蓝牙", "MIXED"));
    }

    @Test public void doReadAndDoActShareGroup() {
        assertEquals(SkillCache.Record.VERIFIED,
                cache.recordSuccess("今天电量还剩多少", "DO_READ", "battery.level", j("{}"), OK, false));
        assertNotNull(cache.lookup("今天电量还剩多少", "DO_ACT"));
    }

    @Test public void epochChangeInvalidates() {
        verified("开蓝牙", "connectivity.bt.on", "{}");
        SkillCache next = new SkillCache(store, "e2", oracle, clock);
        assertNull(next.lookup("开蓝牙", "DO_ACT"));
        assertEquals("纪元不匹配的条目被删除", 0, store.count());
    }

    @Test public void capabilityChangeOrRemovalInvalidates() {
        verified("开蓝牙", "connectivity.bt.on", "{}");
        verified("开省电", "battery.saver.on", "{}");
        oracle.ver.put("connectivity.bt.on", "v2:changed-schema");
        oracle.ver.remove("battery.saver.on");
        assertNull(cache.lookup("开蓝牙", "DO_ACT"));
        assertNull(cache.lookup("开省电", "DO_ACT"));
        assertEquals(0, store.count());
    }

    @Test public void quarantineAfterTwoFailures() {
        verified("开省电", "battery.saver.on", "{}");
        SkillCache.Hit h = cache.lookup("开省电", "DO_ACT");
        cache.onReplayResult(h.key, h.grp, SkillCache.Outcome.failed("EXEC_FAILED"));
        assertNotNull("一次失败仍可用", cache.lookup("开省电", "DO_ACT"));
        cache.onReplayResult(h.key, h.grp, SkillCache.Outcome.failed("EXEC_FAILED"));
        assertNull(cache.lookup("开省电", "DO_ACT"));
        // 隔离后再有一次真成功 → 重新验证
        verified("开省电", "battery.saver.on", "{}");
        assertNotNull(cache.lookup("开省电", "DO_ACT"));
    }

    @Test public void userDenialOnReplayIsNotAFailure() {
        verified("开省电", "battery.saver.on", "{}");
        SkillCache.Hit h = cache.lookup("开省电", "DO_ACT");
        cache.onReplayResult(h.key, h.grp, SkillCache.Outcome.denied());
        cache.onReplayResult(h.key, h.grp, SkillCache.Outcome.denied());
        assertNotNull(cache.lookup("开省电", "DO_ACT"));
    }

    @Test public void negativeCacheForRefusals() {
        cache.recordRefusal("帮我订机票", "DO_ACT", "暂不支持订票", SkillCache.TTL_NEGATIVE);
        assertTrue(cache.isNegative("帮我订机票", "DO_ACT", clock.now()));
        assertEquals("暂不支持订票", cache.negativeReason("订机票", "DO_ACT"));   // 礼貌框架归一
        clock.t += 11 * 60_000L;
        assertFalse(cache.isNegative("帮我订机票", "DO_ACT", clock.now()));
    }

    @Test public void negativeBlocksPositiveLookupAndWrite() {
        cache.recordRefusal("开蓝牙", "DO_ACT", "x", SkillCache.TTL_NEGATIVE);
        assertEquals(SkillCache.Record.REJ_NEGATIVE, cache.recordSuccess("开蓝牙", "DO_ACT",
                "connectivity.bt.on", j("{}"), OK_VERIFIED, false));
    }

    @Test public void userCorrectionEvicts() {
        verified("调亮一点", "settings.brightness.up", "{}");
        assertNotNull(cache.lookup("调亮一点", "DO_ACT"));
        clock.t += 5_000;
        assertTrue(SkillCache.isCorrection("不对"));
        assertTrue(SkillCache.isCorrection("错了错了"));
        assertFalse(SkillCache.isCorrection("打开蓝牙"));
        assertTrue(cache.onUserCorrection());
        assertNull(cache.lookup("调亮一点", "DO_ACT"));
        assertEquals("纠错后 1 天内不再写入", SkillCache.Record.REJ_NEGATIVE, cache.recordSuccess("调亮一点", "DO_ACT",
                "settings.brightness.up", j("{}"), OK_VERIFIED, false));
    }

    @Test public void correctionOutsideWindowDoesNothing() {
        verified("调亮一点", "settings.brightness.up", "{}");
        clock.t += 60_000;
        assertFalse(cache.onUserCorrection());
        assertNotNull(cache.lookup("调亮一点", "DO_ACT"));
    }

    @Test public void timeSensitiveAndContextualNotCached() {
        assertEquals(SkillCache.Record.REJ_TIME_SENSITIVE, cache.recordSuccess("明天七点叫醒我", "DO_ACT",
                "clock.alarm.set", j("{\"hour\":7,\"minute\":0}"), OK_VERIFIED, false));
        assertEquals(SkillCache.Record.REJ_TIME_SENSITIVE, cache.recordSuccess("40分钟后叫醒我", "DO_ACT",
                "clock.alarm.set", j("{\"hour\":0,\"minute\":40}"), OK_VERIFIED, false));
        assertEquals(SkillCache.Record.REJ_TIME_SENSITIVE, cache.recordSuccess("今天天气怎么样", "ASK",
                "ask.reply", j("{\"text\":\"晴，18度\"}"), OK, false));
        assertEquals(SkillCache.Record.REJ_CONTEXT, cache.recordSuccess("我叫什么名字", "ASK",
                "ask.reply", j("{\"text\":\"你叫小明\"}"), OK, true));
        assertNull(cache.lookup("明天七点叫醒我", "DO_ACT"));
    }

    /** 旧 D-1（用户 14:25 拍板第 8 项）：确定性 DO_ACT 带会话历史也能写；指代上文 / ASK / DO_READ 照旧拒写。 */
    @Test public void deterministicDoActBypassesContextGate() {
        SkillCache.Record r = cache.recordSuccess("打开微信", "DO_ACT", "app.launch", j("{\"app\":\"微信\"}"), OK_VERIFIED, true);
        assertNotEquals(SkillCache.Record.REJ_CONTEXT, r);
        assertTrue("写进去了: " + r, r == SkillCache.Record.VERIFIED || r == SkillCache.Record.CANDIDATE || r == SkillCache.Record.PROMOTED);
        assertNotNull(cache.lookup("打开微信", "DO_ACT"));
        assertEquals(SkillCache.Record.REJ_CONTEXT, cache.recordSuccess("打开这个", "DO_ACT", "app.launch",
                j("{\"app\":\"微信\"}"), OK_VERIFIED, true));
        assertEquals(SkillCache.Record.REJ_CONTEXT, cache.recordSuccess("再打开一次微信", "DO_ACT", "app.launch",
                j("{\"app\":\"微信\"}"), OK_VERIFIED, true));
        assertEquals(SkillCache.Record.REJ_CONTEXT, cache.recordSuccess("我叫什么名字", "ASK", "ask.reply",
                j("{\"text\":\"你叫小明\"}"), OK, true));
        assertTrue(SkillCache.contextIndependentAct("把亮度调到30", "DO_ACT"));
        assertFalse(SkillCache.contextIndependentAct("亮度多少", "DO_READ"));
        assertFalse(SkillCache.contextIndependentAct("把它关了", "DO_ACT"));
        assertFalse(SkillCache.contextIndependentAct("改回去", "DO_ACT"));
        // 其它闸门照旧：失败不写
        assertEquals(SkillCache.Record.REJ_FAILED, cache.recordSuccess("打开某某", "DO_ACT", "app.launch",
                j("{\"app\":\"某某\"}"), SkillCache.Outcome.failed("EXEC_FAILED"), true));
    }

    @Test public void stableAskCachedWithTtlAndRefusalsNot() {
        assertEquals(SkillCache.Record.VERIFIED, cache.recordSuccess("介绍一下苏州园林", "ASK", "ask.reply",
                j("{\"text\":\"苏州园林是中国古典园林的代表。\"}"), OK, false));
        assertEquals("苏州园林是中国古典园林的代表。", cache.lookup("介绍一下苏州园林", "ASK").text());
        assertEquals(SkillCache.Record.REJ_REFUSAL, cache.recordSuccess("量子力学", "ASK", "ask.reply",
                j("{\"text\":\"网络暂时不可用，这个问题我先答不上来\"}"), OK, false));
        clock.t += SkillCache.TTL_ASK + 1;
        assertNull("ASK 3 天过期", cache.lookup("介绍一下苏州园林", "ASK"));
    }

    @Test public void askKeysDoNotGeneralizeNumbers() {
        cache.recordSuccess("一百的平方根是多少", "ASK", "ask.reply", j("{\"text\":\"10\"}"), OK, false);
        assertNull(cache.lookup("二百的平方根是多少", "ASK"));
    }

    @Test public void riskTwoAndUnknownCapNotCached() {
        assertEquals(SkillCache.Record.REJ_RISK, cache.recordSuccess("卸载微信", "DO_ACT", "app.uninstall",
                j("{\"app\":\"微信\"}"), OK_VERIFIED, false));
        assertEquals(SkillCache.Record.REJ_UNKNOWN_CAP, cache.recordSuccess("开飞船", "DO_ACT", "space.ship.on",
                j("{}"), OK_VERIFIED, false));
    }

    @Test public void textSlotGeneralizationForAppLaunch() throws Exception {
        verified("帮我打开支付宝", "app.launch", "{\"app\":\"支付宝\"}");
        SkillCache.Hit h = cache.lookup("打开高德地图", "DO_ACT");
        assertNotNull(h);
        assertEquals("高德地图", h.args.getString("app"));
        assertNull("吞掉第二件事的取值被拒", cache.lookup("打开蓝牙和wifi", "DO_ACT"));
    }

    @Test public void inventedFreeTextRejected() {
        assertEquals(SkillCache.Record.REJ_INVENTED_ARG, cache.recordSuccess("记个笔记", "DO_ACT", "notes.new.text",
                j("{\"content\":\"今天要买牛奶\"}"), OK_VERIFIED, false));
    }

    @Test public void wakeWordAndPolitenessNormalized() {
        verified("打开蓝牙", "connectivity.bt.on", "{}");
        assertNotNull(cache.lookup("请帮我打开一下蓝牙吧", "DO_ACT"));
        assertNotNull(cache.lookup("你能打开蓝牙吗", "DO_ACT"));
    }

    @Test public void unreferencedNumbersAreFixedConstraints() {
        verified("打开第2个闹钟", "connectivity.bt.on", "{}");   // 数字 2 未被参数引用 → 字面约束
        assertNotNull(cache.lookup("打开第2个闹钟", "DO_ACT"));
        assertNull(cache.lookup("打开第3个闹钟", "DO_ACT"));
    }

    @Test public void lruCapacity() {
        for (int i = 0; i < SkillCache.CAPACITY + 1; i++) {
            clock.t += 1;
            // 用不含数字的汉字造 501 个不同句子（含数字会被抽成同一个 {n} 模板）
            cache.recordSuccess("技能" + (char) (0x4e00 + 300 + i) + (char) (0x4e00 + 1300 + i) + "开关", "DO_ACT", "settings.brightness.up",
                    j("{}"), OK_VERIFIED, false);
        }
        assertEquals(SkillCache.CAPACITY, store.count());
        assertNull("最早写入、从未命中的那条被淘汰",
                store.get("技能" + (char) (0x4e00 + 300) + (char) (0x4e00 + 1300) + "开关", "DO"));
    }

    @Test public void selfTestUtterancesNeverWritten() {
        assertEquals(SkillCache.Record.REJ_SELFTEST, cache.recordSuccess("自测缓存优先探针", "DO_ACT",
                "settings.brightness.up", j("{}"), OK_VERIFIED, false));
    }

    @Test public void legacyMigrationPurgesSuspiciousEntries() {
        assertEquals(SkillCache.Record.REJ_INVENTED_ARG,
                cache.importLegacy("定个闹钟", "AGENT", "clock.alarm.set", j("{\"hour\":7,\"minute\":0}")));
        assertEquals(SkillCache.Record.REJ_INVENTED_ARG,
                cache.importLegacy("定个计时器", "AGENT", "clock.timer.set", j("{\"seconds\":300}")));
        assertEquals(SkillCache.Record.REJ_SELFTEST,
                cache.importLegacy("修复回归探针指令", "AGENT", "settings.brightness.up", j("{}")));
        assertEquals(SkillCache.Record.REJ_SELFTEST,
                cache.importLegacy("自测缓存优先探针", "AGENT", "settings.brightness.up", j("{}")));
        assertEquals(SkillCache.Record.REJ_UNKNOWN_CAP,
                cache.importLegacy("开飞船", "AGENT", "space.ship.on", j("{}")));
        assertTrue("旧库的问答条目一律丢弃",
                cache.importLegacy("比特币是什么", "ASK", "ask.reply", j("{\"text\":\"x\"}")).name().startsWith("REJ"));
        assertEquals(SkillCache.Record.CANDIDATE,
                cache.importLegacy("天太黑了屏幕刺眼帮我调低点亮度", "AGENT", "settings.brightness.down", j("{}")));
        assertNull("导入的是 candidate，未经一次真成功不命中",
                cache.lookup("天太黑了屏幕刺眼帮我调低点亮度", "DO_ACT"));
        // 下一次真执行成功即转正
        cache.recordSuccess("天太黑了屏幕刺眼帮我调低点亮度", "DO_ACT", "settings.brightness.down", j("{}"), OK, false);
        assertNotNull(cache.lookup("天太黑了屏幕刺眼帮我调低点亮度", "DO_ACT"));
    }

    @Test public void peekDoesNotCountHits() {
        verified("开蓝牙", "connectivity.bt.on", "{}");
        assertTrue(cache.peek("开蓝牙", "DO_ACT"));
        assertFalse(cache.peek("开蓝牙", "ASK"));
        assertEquals(0, store.get("开蓝牙", "DO").hitCount);
    }

    // ---------------- round3 #4：验收脚本口径 ----------------

    /**
     * 真机第 3 轮 cache 组假失败的两个根因，在 JVM 上钉住：
     * ① 风险 ≥1 且未回读确认的 DO：第 1 次成功 = CANDIDATE（不命中），第 2 次同映射成功 = PROMOTED，第 3 次才 HIT；
     * ② CA-5 的「不对」留下 1 天负缓存 → 之后 PUT 全是 REJ_NEGATIVE；clearNegative 只清负缓存、正缓存不动。
     */
    @Test public void promoteAfterTwoAndClearNegativeForAcceptance() {
        String q = "天太黑了屏幕刺眼帮我调低点亮度";
        oracle.add("display.dim", 1);   // 风险 1、执行层未回读确认（与真机亮度调节同口径）
        assertEquals(SkillCache.Record.CANDIDATE, cache.recordSuccess(q, "DO_ACT", "display.dim", j("{}"), OK, false));
        assertNull("候选不命中", cache.lookup(q, "DO_ACT"));
        assertEquals(SkillCache.Record.PROMOTED, cache.recordSuccess(q, "DO_ACT", "display.dim", j("{}"), OK, false));
        assertNotNull("第 3 次才命中", cache.lookup(q, "DO_ACT"));
        // 「不对」→ 淘汰 + 1 天负缓存
        assertTrue(cache.onUserCorrection());
        assertEquals(SkillCache.Record.REJ_NEGATIVE, cache.recordSuccess(q, "DO_ACT", "display.dim", j("{}"), OK, false));
        // 另一句的正缓存不受 clearNegative 影响
        verified("打开蓝牙", "connectivity.bt.on", "{}");
        assertTrue(cache.clearNegative(q) >= 1);
        assertEquals(0, cache.clearNegative(q));
        assertFalse(cache.isNegative(q, "DO_ACT", clock.now()));
        assertEquals(SkillCache.Record.CANDIDATE, cache.recordSuccess(q, "DO_ACT", "display.dim", j("{}"), OK, false));
        assertNotNull(cache.lookup("打开蓝牙", "DO_ACT"));
        assertEquals(0, cache.clearNegative(""));
    }

    /**
     * 真机第 4 轮 CA-1 假失败：测试句上一轮已转正，第 1 次直接 HIT、没有 PUT。
     * clearPositive 清掉这句话的正缓存（含候选），之后重新从 CANDIDATE 走起；负缓存与别的句子不动。
     */
    @Test public void clearPositiveForAcceptanceRound4() {
        String q = "天太黑了屏幕刺眼帮我调低点亮度";
        oracle.add("display.dim", 1);
        assertEquals(SkillCache.Record.CANDIDATE, cache.recordSuccess(q, "DO_ACT", "display.dim", j("{}"), OK, false));
        assertEquals(SkillCache.Record.PROMOTED, cache.recordSuccess(q, "DO_ACT", "display.dim", j("{}"), OK, false));
        assertNotNull("上一轮已转正 → 直接命中（CA-1 假失败的现场）", cache.lookup(q, "DO_ACT"));
        verified("打开蓝牙", "connectivity.bt.on", "{}");
        cache.recordRefusal("别的句子", "DO_ACT", "测试", 86400000L);
        assertEquals(1, cache.clearPositive(q));
        assertEquals("幂等", 0, cache.clearPositive(q));
        assertNull("清掉后不再命中", cache.lookup(q, "DO_ACT"));
        assertEquals("重新从候选开始 → CA-1 能看到 PUT", SkillCache.Record.CANDIDATE,
                cache.recordSuccess(q, "DO_ACT", "display.dim", j("{}"), OK, false));
        assertNotNull("别的句子的正缓存不动", cache.lookup("打开蓝牙", "DO_ACT"));
        assertTrue("负缓存不动", cache.isNegative("别的句子", "DO_ACT", clock.now()));
        // 只有候选（未转正）也能清
        assertEquals(1, cache.clearPositive(q));
        assertEquals(0, cache.clearPositive(""));
    }
}
