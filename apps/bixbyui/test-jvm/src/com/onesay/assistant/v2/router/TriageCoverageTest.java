package com.onesay.assistant.v2.router;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * ★ fix/triage-fast：分诊覆盖矩阵回归（以后审计的基线）。
 *
 * <p>语料 {@code test-jvm/fixtures/triage_corpus.tsv}（≥300 句，scripts/gen_corpus.py 生成）每行：句子 / 分组 / 目标。
 * 目标写了「层级|签名」的句子（用户点名的高频指令）必须严格命中；「-」的句子只记录（打印矩阵），不强制。
 * 模型见 {@link RouteLayerModel}（逐行复刻 shouldUseAgentLoop + cascade）。
 */
public class TriageCoverageTest {

    static final class Row {
        final String utt, group, target;

        Row(String u, String g, String t) {
            utt = u;
            group = g;
            target = t;
        }
    }

    static List<Row> rows = new ArrayList<Row>();
    static RouteLayerModel model;

    static File root() {
        return new File(System.getProperty("bixby.root", "."));
    }

    @BeforeClass public static void load() throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(root(), "test-jvm/fixtures/triage_corpus.tsv")), "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("#") || line.trim().length() == 0) continue;
            String[] f = line.split("\t");
            rows.add(new Row(f[0], f[1], f[2]));
        }
        br.close();
        model = new RouteLayerModel(true);
    }

    @Test public void corpusIsBigEnough() {
        assertTrue("语料 ≥300 句，实际 " + rows.size(), rows.size() >= 300);
        int strict = 0;
        for (Row r : rows) if (!"-".equals(r.target)) strict++;
        assertTrue("强制目标 ≥250 句，实际 " + strict, strict >= 250);
        assertTrue("修后代码里必须有分诊第 0 层", model.fast());
    }

    @Test public void everyTargetHitsExpectedLayerAndTool() {
        StringBuilder miss = new StringBuilder();
        int n = 0;
        for (Row r : rows) {
            if ("-".equals(r.target)) continue;
            String[] t = r.target.split("\\|");
            RouteLayerModel.Result res = model.predict(r.utt);
            boolean ok = res.layer.name().equals(t[0]) && ("*".equals(t[1]) || res.sign.equals(t[1]));
            if (!ok) {
                n++;
                miss.append("\n  [").append(r.group).append("] ").append(r.utt).append(" 期望 ").append(r.target)
                        .append(" 实际 ").append(res.layer).append('|').append(res.sign).append(" #").append(res.rule);
            }
        }
        assertEquals("快路径覆盖回归失败：" + miss, 0, n);
    }

    /** 用户点名的几类：零 LLM、不进 AgentLoop、不碰 GUI；清空后台绝不是「打开应用管理」。 */
    @Test public void namedCommandsNeverTouchLlmOrGui() {
        String[] named = {"锁屏", "锁定屏幕", "把屏幕锁了", "清空后台应用", "清空后台", "把后台都关了", "返回桌面", "回到主屏幕",
                "截屏", "下一首", "暂停", "音量调大", "亮度调到最大", "打开wifi", "关蓝牙", "开手电筒", "打开勿扰", "打开飞行模式",
                "打开定位", "开热点", "打开nfc", "打开微信", "播放", "设个7点的闹钟", "定个10分钟的计时器", "打电话给妈妈", "拍照", "静音",
                "重启手机", "关机"};
        for (String q : named) {
            RouteLayerModel.Result r = model.predict(q);
            assertEquals(q + " → " + r, RouteLayerModel.Layer.L0, r.layer);
            assertFalse(q, r.sign.startsWith("settings.page.apps"));
            assertTrue(q + " ≤300 ms 预期", r.ms <= 300);
        }
        assertEquals("sys.recents.clear", model.predict("清空后台应用").sign);
        assertEquals("screen.lock", model.predict("锁屏").sign);
    }

    /** 综合任务：一句多意图拆成零 LLM 计划（L1 规则层），子目标按子句。 */
    @Test public void compoundSplitsIntoSubgoals() {
        RouteLayerModel.Result r = model.predict("把亮度调低、开勿扰、10分钟后提醒我");
        assertEquals(RouteLayerModel.Layer.L1, r.layer);
        assertEquals("settings.brightness.down+dnd.on+clock.timer.set", r.sign);
        assertEquals("0c-compound", r.rule);
        assertEquals("connectivity.wifi.on+connectivity.bt.on", model.predict("打开wifi和蓝牙").sign);
        // 需要先看结果 / 选人 / 进第三方 App 的复合句：仍交给 AgentLoop（或 GUI），不硬拆
        assertEquals(RouteLayerModel.Layer.AGENT, model.predict("截个图然后发给张三").layer);
        assertEquals(RouteLayerModel.Layer.AGENT, model.predict("看看电量够不够，不够就开省电").layer);
    }

    /** 矩阵汇总（打印到日志；平均预期耗时 = 各层经验值）。修前数字由 scripts/model.sh 在 6f37a7a 上跑出，见 TRIAGE-FAST.md。 */
    @Test public void printMatrix() {
        Map<RouteLayerModel.Layer, Integer> c = new LinkedHashMap<RouteLayerModel.Layer, Integer>();
        for (RouteLayerModel.Layer l : RouteLayerModel.Layer.values()) c.put(l, 0);
        long ms = 0;
        for (Row r : rows) {
            RouteLayerModel.Result res = model.predict(r.utt);
            c.put(res.layer, c.get(res.layer) + 1);
            ms += res.ms;
        }
        StringBuilder sb = new StringBuilder("[COVERAGE] 修后 " + rows.size() + " 句：");
        for (Map.Entry<RouteLayerModel.Layer, Integer> e : c.entrySet()) {
            sb.append(e.getKey().name()).append('=').append(e.getValue())
                    .append(String.format(" (%.1f%%) ", 100.0 * e.getValue() / rows.size()));
        }
        sb.append(" 平均预期 ").append(ms / rows.size()).append(" ms");
        System.out.println(sb);
        int fast = c.get(RouteLayerModel.Layer.L0) + c.get(RouteLayerModel.Layer.L1);
        assertTrue("零 LLM（L0+L1）占比应 ≥85%，实际 " + fast + "/" + rows.size(), fast * 100 >= rows.size() * 85);
    }
}
