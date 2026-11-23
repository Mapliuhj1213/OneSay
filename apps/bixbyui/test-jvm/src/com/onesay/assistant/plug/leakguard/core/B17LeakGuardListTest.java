package com.onesay.assistant.plug.leakguard.core;

import com.onesay.assistant.v2.asr.AsrHotwords;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * ★ fix/1123-bug1 B17：输入侧「热词占比」判据把用户用顿号列 5 个热词的正常说法整句丢掉（t20k LEAK-IN-FALSE-POSITIVE 26 条）。
 * 修法：句子里本来就有分隔符（顿号 / 逗号 / 「和」……）时，占比判据的门槛跟连串判据一样要 ≥{@link PromptLeakGuard#RUN_MIN} 个不同热词；
 * 没有分隔符、连成一串的（回吐的典型样子）仍是 ≥{@link PromptLeakGuard#DENSE_MIN_WORDS} 个就拦。
 */
public class B17LeakGuardListTest {

    private PromptLeakGuard g;

    @Before public void setUp() {
        g = new PromptLeakGuard();
        g.setHotwordPrefix(AsrHotwords.HOTWORD_PROMPT_PREFIX);
        g.addHotwords(Arrays.asList(AsrHotwords.staticWords()));
    }

    /** t20k 26 条原样（random-app-list-input-5）。 */
    static final String[] T20K = {
            "自动旋转、省电模式、深色模式、股票、亮度", "备忘录、360搜索、微博、计算器、NFC", "google、携程、静音、免打扰、钉钉",
            "网易云音乐、飞行模式、汇率、百度地图、音量", "截图、WiFi、腾讯视频、汇率、爱奇艺", "拼多多、数据流量、百度地图、个人热点、比特币",
            "搜狗、微博、快手、GPS、12306", "天气、360搜索、快手、高德地图、航班", "日历、墨迹天气、护眼模式、WiFi、携程",
            "录屏、搜狗、bing、比特币、计算器", "比特币、个人热点、护眼模式、静音、WiFi", "深色模式、截图、备忘录、钉钉、GPS",
            "截图、知乎、音量、基金、google", "NFC、搜狗、数据流量、饿了么、快手", "知乎、google、计算器、天气、相册",
            "时钟、哔哩哔哩、高德地图、手电筒、夸克", "天气、爱奇艺、护眼模式、快手、WiFi", "以太坊、数据流量、截屏、天气、飞行模式",
            "抖音、深色模式、省电模式、设置、爱奇艺", "WiFi、QQ音乐、时钟、勿扰模式、基金", "护眼模式、爱奇艺、热点、数据流量、必应",
            "深色模式、闹钟、NFC、google、拼多多", "WiFi、应用商店、360搜索、股票、钉钉", "备忘录、亮度、小红书、录屏、护眼模式",
            "护眼模式、截图、数据流量、蓝牙、热点", "以太坊、网易云音乐、高铁、拼多多、墨迹天气",
    };

    @Test public void fiveListedHotwordsPass() {
        for (String s : T20K) {
            PromptLeakGuard.Verdict v = g.check(s);
            assertFalse(s + " → " + v.describe(), v.leak());
        }
    }

    @Test public void fiveListedWithOtherSeparatorsPass() {
        for (String s : new String[]{"备忘录，360搜索，微博，计算器，NFC", "备忘录 360搜索 微博 计算器 NFC", "备忘录、360搜索、微博、计算器和NFC"}) {
            assertFalse(s, g.check(s).leak());
        }
    }

    /** 拦截不放松：无分隔符的 5 个照旧拦；有分隔符的 ≥6 个照旧拦（连串或占比）；前缀照旧拦。 */
    @Test public void realLeaksStillCaught() {
        assertEquals(PromptLeakGuard.Reason.HOTWORD_DENSE, g.check("备忘录360搜索微博计算器NFC").reason);
        assertTrue(g.check("必应bing百度谷歌google搜狗夸克").leak());
        assertTrue(g.check("备忘录、360搜索、微博、计算器、NFC、钉钉").leak());
        assertTrue("分隔符只出现一处、其余连成串", g.check("微信支付宝淘宝、京东拼多多抖音").leak());
        assertTrue(g.check(AsrHotwords.HOTWORD_PROMPT_PREFIX + "备忘录、360搜索").leak());
        assertTrue("中间结果上屏阈值不变（4 个就不上屏）", g.checkPartial("备忘录、360搜索、微博、计算器、NFC").leak());
    }
}
