package com.onesay.assistant.v2.s2;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * [s2] round3 #2 免前缀记忆<b>留出集</b>（memory-implicit holdout，26 条）。
 *
 * <p>规则（{@link ImplicitNote}）写完后、在跑任何正负例之前一次性写好并冻结；<b>只打印、不设门槛、不据此调规则</b>
 * （用户要求：report but don't tune to it）。用来估计真机上「自然说法」的泛化能力，结果写进。
 * 正例 = 应该被记下（NOTE_ADD）；负例 = 不能被记下（handle 返回的不是 NOTE_ADD / NOTE_REFUSED）。
 */
public class S2ImplicitHoldoutTest {

    static final String[] POSITIVE = {
        "身份证放在床头柜第一层",
        "我把充电器落在公司了",
        "电动车停在小区东门",
        "备用钥匙在妈妈那",
        "雨伞挂门后面了",
        "我的工位是3楼A区12号",
        "行李箱寄存在酒店前台",
        "车停负二层C区",
        "药放在冰箱第二格",
        "眼镜搁沙发上了",
        "酒店房间号是1508",
        "护照锁保险箱里了",
        "我把耳机丢在车上了",
    };

    static final String[] NEGATIVE = {
        "把空调调到26度",
        "上海在哪个省",
        "我在开车",
        "明天在公司开会",
        "放一首周杰伦的歌",
        "停止播放",
        "长城在北京",
        "把这张照片存到相册",
        "蓝牙耳机连不上",
        "钥匙在哪",
        "导航到停车场",
        "车停好了吗",
        "帮我把闹钟放到七点",
    };

    static long now() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.clear();
        c.set(2026, 9, 8, 20, 0, 0);
        return c.getTimeInMillis();
    }

    @Test public void report() {
        S2Engine.Flags f = new S2Engine.Flags(true, true, false, true);
        StringBuilder fails = new StringBuilder();
        int pos = 0, neg = 0;
        for (String q : POSITIVE) {
            InMemoryMemoryStore st = new InMemoryMemoryStore();
            S2Engine.Result r = new S2Engine(st, st, TimeZone.getTimeZone("Asia/Shanghai")).handle(q, now(), f);
            if (r != null && r.type == S2Engine.Type.NOTE_ADD) pos++;
            else fails.append("  [漏记] ").append(q).append(" → ").append(r).append('\n');
        }
        for (String q : NEGATIVE) {
            InMemoryMemoryStore st = new InMemoryMemoryStore();
            S2Engine.Result r = new S2Engine(st, st, TimeZone.getTimeZone("Asia/Shanghai")).handle(q, now(), f);
            boolean stored = r != null && (r.type == S2Engine.Type.NOTE_ADD || r.type == S2Engine.Type.NOTE_REFUSED);
            if (!stored) neg++;
            else fails.append("  [误记] ").append(q).append(" → ").append(r).append('\n');
        }
        System.out.println("[IMPLICIT] 免前缀记忆留出集 正例 " + pos + "/" + POSITIVE.length + "，负例 " + neg + "/" + NEGATIVE.length
                + "，合计 " + (pos + neg) + "/" + (POSITIVE.length + NEGATIVE.length)
                + (fails.length() > 0 ? "\n" + fails : ""));
    }
}
