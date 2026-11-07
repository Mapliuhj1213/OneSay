package com.onesay.assistant.v2.errand;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ 11-04 D：购物搜索词 = 品类 / 型号；价格成筛选条件；无型号走推荐。 */
public class ShopQueryTest {

    @Test public void ergonomicChairUnder1000() {
        String raw = "我想买一张1000以内的人体工学椅，有没有推荐";
        ShopQuery q = ShopQuery.parse(raw);
        assertEquals("人体工学椅", q.keyword);
        assertEquals(1000, q.priceMax);
        assertEquals(0, q.priceMin);
        assertTrue(q.recommend);
        assertEquals("人体工学椅 推荐", q.searchQuery());
        assertEquals("≤1000 元", q.constraint());
        assertTrue(ShopQuery.valid(q.keyword, raw));
    }

    @Test public void chineseNumbersAndRanges() {
        assertEquals(1000, ShopQuery.parse("一千块以下的机械键盘").priceMax);
        assertEquals("机械键盘", ShopQuery.parse("一千块以下的机械键盘").keyword);
        assertEquals(2750, ShopQuery.parse("两千五左右的显示器").priceMax);
        ShopQuery r = ShopQuery.parse("500到800的蓝牙耳机推荐一下");
        assertEquals(500, r.priceMin);
        assertEquals(800, r.priceMax);
        assertEquals("蓝牙耳机", r.keyword);
        assertEquals(3000, ShopQuery.parse("预算3k的手机有什么推荐").priceMax);
        assertEquals(1500, ShopQuery.parse("不超过1.5千的扫地机器人").priceMax);
        assertEquals(Long.valueOf(1200), ShopQuery.cn("一千二百"));
        assertEquals(Long.valueOf(2500), ShopQuery.cn("两千五"));
        assertEquals(Long.valueOf(50000), ShopQuery.cn("五万"));
        assertEquals(Long.valueOf(300), ShopQuery.cn("三百"));
    }

    @Test public void modelMeansCompareNotRecommend() {
        ShopQuery q = ShopQuery.parse("帮我买雷蛇巴塞利斯蛇v3");
        assertEquals("雷蛇巴塞利斯蛇v3", q.keyword);
        assertFalse(q.recommend);
        assertEquals("雷蛇巴塞利斯蛇v3", q.searchQuery());
        assertEquals(0, q.priceMax);
        assertFalse(ShopQuery.parse("iPhone 15 哪个渠道最便宜").recommend);
    }

    @Test public void modelKeywordAcceptedOnlyIfClean() {
        String raw = "我想买一张1000以内的人体工学椅，有没有推荐";
        assertEquals("用户机上原样的错词被拒、规则重抽", "人体工学椅 推荐", ShopQuery.accept("张一千块以内的人体工学椅 有没有推荐", raw));
        assertEquals("人体工学椅", ShopQuery.accept("人体工学椅", raw));
        assertEquals("价格词剥掉", "人体工学椅", ShopQuery.accept("人体工学椅 1000以内", raw));
        assertFalse(ShopQuery.valid("我想买一张1000以内的人体工学椅有没有推荐", raw));
        assertFalse(ShopQuery.valid("椅", raw));
        assertFalse(ShopQuery.valid(null, raw));
    }
}
