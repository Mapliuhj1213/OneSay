package com.onesay.assistant.v2.city;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.onesay.assistant.v2.city.HomeCityPolicy.Source;
import com.onesay.assistant.v2.city.HomeCityPolicy.SysCity;
import com.onesay.assistant.v2.search.WeatherQuery;

/** [lane C 第 2 项] 常用城市：手动 > 系统天气 > 定位 > 空（定位未授权 = 旧行为）。 */
public class HomeCityPolicyTest {

    @Test public void manualWins() {
        HomeCityPolicy.Result r = HomeCityPolicy.decide("佛山", "广州", "深圳", true);
        assertEquals("佛山", r.city);
        assertEquals(Source.MANUAL, r.source);
    }

    @Test public void sysWeatherBeforeLocation() {
        HomeCityPolicy.Result r = HomeCityPolicy.decide("  ", "广州", "深圳市", true);
        assertEquals("广州", r.city);
        assertEquals(Source.SYS_WEATHER, r.source);
    }

    @Test public void locationWhenNoSysWeather() {
        HomeCityPolicy.Result r = HomeCityPolicy.decide("", "", "北京市", true);
        assertEquals("北京市", r.city);
        assertEquals(Source.LOCATION, r.source);
    }

    @Test public void noPermissionFallsBackToOldBehaviour() {
        HomeCityPolicy.Result r = HomeCityPolicy.decide(null, null, "北京市", false);
        assertEquals("", r.city);
        assertEquals(Source.NONE, r.source);
        assertEquals("旧行为：不补城市", "今天天气怎么样", WeatherQuery.withHomeCity("今天天气怎么样", r.city));
    }

    @Test public void sysWeatherStillUsedWithoutLocationPermission() {
        assertEquals(Source.SYS_WEATHER, HomeCityPolicy.decide("", "广州", "", false).source);
    }

    @Test public void endToEndWeatherQuery() {
        String city = HomeCityPolicy.decide("", "佛山", "", false).city;
        assertEquals("佛山 明天会下雨吗", WeatherQuery.withHomeCity("明天会下雨吗", city));
        assertEquals("北京明天会下雨吗", WeatherQuery.withHomeCity("北京明天会下雨吗", city));
    }

    @Test public void selectedCityFirstThenOrder() {
        List<SysCity> rows = Arrays.asList(
                new SysCity("k3", "深圳", 3),
                new SysCity("k1", "广州", 1),
                new SysCity("k2", "佛山, 北京市", 2));
        assertEquals(Arrays.asList("佛山", "广州", "深圳"), HomeCityPolicy.orderSysCities(rows, "k2"));
        assertEquals("选中 key 对不上：按 ORDER", "广州", HomeCityPolicy.pickSysCity(rows, "zz"));
        assertEquals("广州", HomeCityPolicy.pickSysCity(rows, null));
        assertEquals("", HomeCityPolicy.pickSysCity(new ArrayList<SysCity>(), "k1"));
        assertEquals("", HomeCityPolicy.pickSysCity(null, null));
    }

    @Test public void cleanRejectsPlaceholders() {
        assertEquals("", HomeCityPolicy.clean("我的位置"));
        assertEquals("", HomeCityPolicy.clean("Current location"));
        assertEquals("佛山", HomeCityPolicy.clean(" 佛山，广东 "));
        assertEquals("", HomeCityPolicy.clean("0123456789abcdef0123456789abcdef"));
        List<SysCity> rows = Arrays.asList(new SysCity("a", "我的位置", 1), new SysCity("b", "佛山", 2));
        assertEquals("占位名跳过", "佛山", HomeCityPolicy.pickSysCity(rows, "a"));
    }

    @Test public void cacheTtl() {
        long now = 10L * 3600L * 1000L;
        assertFalse("手动城市不用解析", HomeCityPolicy.needResolve("佛山", 0L, true, now));
        assertTrue(HomeCityPolicy.needResolve("", 0L, true, now));
        assertFalse(HomeCityPolicy.needResolve("", now - 3600L * 1000L, false, now));
        assertTrue(HomeCityPolicy.needResolve("", now - HomeCityPolicy.CACHE_TTL_MS, false, now));
        assertTrue("空结果 30 min 后重试", HomeCityPolicy.needResolve("", now - HomeCityPolicy.NEGATIVE_TTL_MS, true, now));
        assertFalse(HomeCityPolicy.needResolve("", now - 60000L, true, now));
        assertTrue("时钟回拨", HomeCityPolicy.needResolve("", now + 5000L, false, now));
    }

    @Test public void parseReverseGeocoders() {
        String bdc = "{\"latitude\":39.98,\"countryName\":\"中华人民共和国\",\"principalSubdivision\":\"北京市\",\"city\":\"北京市\",\"locality\":\"海淀區\"}";
        assertEquals("北京市", HomeCityPolicy.parseBigDataCloud(bdc));
        assertEquals("海淀區", HomeCityPolicy.parseBigDataCloud("{\"city\":\"\",\"locality\":\"海淀區\"}"));
        assertEquals("", HomeCityPolicy.parseBigDataCloud("not json"));
        String nomi = "{\"name\":\"中关村街道\",\"address\":{\"suburb\":\"中关村街道\",\"district\":\"海淀区\",\"city\":\"北京市\",\"state\":\"北京市\"}}";
        assertEquals("北京市", HomeCityPolicy.parseNominatim(nomi));
        assertEquals("昌平区", HomeCityPolicy.parseNominatim("{\"address\":{\"town\":\"昌平区\"}}"));
        assertEquals("", HomeCityPolicy.parseNominatim("{}"));
    }

    @Test public void copyCommandNeverTouchesShm() {
        String c = HomeCityPolicy.copyCommand("/data/user/0/com.onesay.assistant/files/wdb/");
        assertTrue(c.startsWith("cat '/data/data/com.sec.android.daemonapp/databases/WeatherClock' > '/data/user/0/com.onesay.assistant/files/wdb/WeatherClock'"));
        assertTrue(c.contains("WeatherClock-wal"));
        assertFalse("不复制 -shm（SIGBUS）", c.contains("-shm"));
        assertFalse("不用 cp（root 新建文件标签不对）", c.contains("cp "));
        assertTrue(c.contains("SZ "));
        String q = HomeCityPolicy.copyCommand("/x/it's");
        assertTrue("单引号转义", q.contains("'/x/it'\\''s/WeatherClock'"));
    }

    @Test public void parseCopyEcho() {
        long[] sz = HomeCityPolicy.parseCopyEcho("SZ 4096 716800\n");
        assertEquals(4096L, sz[0]);
        assertEquals(716800L, sz[1]);
        assertNull(HomeCityPolicy.parseCopyEcho("cat: permission denied"));
        assertNull(HomeCityPolicy.parseCopyEcho(null));
        assertNull(HomeCityPolicy.parseCopyEcho("SZ x y"));
    }
}
