package com.onesay.assistant.plug.skills;

import com.onesay.assistant.plug.api.ModelPort;
import com.onesay.assistant.plug.skill.core.SkillLearner;
import com.onesay.assistant.plug.skills.core.LlmPicker;
import com.onesay.assistant.plug.skills.core.MiniYaml;
import com.onesay.assistant.plug.skills.core.RuleDrafter;
import com.onesay.assistant.plug.skills.core.Skill;
import com.onesay.assistant.plug.skills.core.SkillDrafter;
import com.onesay.assistant.plug.skills.core.SkillIndex;
import com.onesay.assistant.plug.skills.core.SkillMatcher;
import com.onesay.assistant.plug.skills.core.SkillSession;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** ★ skill-pack：技能核心（纯 Java）——解析、打分、Top-K、预算、多轮保持 / 卸载、冲突、坏 YAML 容错、LLM 兜底解析、草稿。 */
public class SkillCoreTest {

    static String md(String id, String head, String body) {
        return "---\nid: " + id + "\nname: 技能" + id + "\n" + head + "\n---\n" + body + "\n";
    }

    static SkillIndex idx(String... files) {
        SkillIndex i = new SkillIndex();
        for (int k = 0; k + 1 < files.length; k += 2) i.add(files[k], files[k + 1]);
        return i;
    }

    static final String WECHAT = md("wx", "priority: 70\nbudget: 400\ntriggers:\n  keywords: [发微信, 微信告诉]\n  regex: [\"(给|跟).{1,8}发(个)?微信\"]\n"
            + "  packages: [com.tencent.mm]\n  screen: [按住 说话]\n  examples: [给妈妈发微信说我到家了]\n  exclude: [朋友圈]\n"
            + "requires:\n  tools: [phone.operate, gui.unfold]\nconflicts: [sms]", "1. 先凑齐收件人和正文。\n2. 发送前确认。");
    static final String SMS = md("sms", "priority: 60\ntriggers:\n  keywords: [发短信, 发微信, 老王]\nrequires:\n  tools: [sms.send]", "用 sms.send。");
    static final String ALARM = md("alarm", "triggers:\n  keywords: [闹钟, 叫醒]\n  intents: [clock]\n  regex: [\"\\d+分钟后叫我\"]", "用 clock.alarm.set。");
    static final String NAV = md("nav", "triggers:\n  keywords: [导航, 打车]\n  intents: [map]", "导航正文");

    // ------------------------------------------------------------------ MiniYaml

    @Test public void yamlSubset() {
        MiniYaml.Result r = MiniYaml.parseDocument("---\na: 1\nb: [x, \"y, z\", 'w']\nc:\n  - p\n  - \"q: r\"\nd:\n  e: [1, 2]\n  f: hi\n  g:\n    - k1\n    - k2\n# 注释\n---\n正文");
        assertTrue(r.errors.toString(), r.errors.isEmpty());
        assertEquals("1", r.map.get("a"));
        assertEquals(Arrays.asList("x", "y, z", "w"), r.map.get("b"));
        assertEquals(Arrays.asList("p", "q: r"), r.map.get("c"));
        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) r.map.get("d");
        assertEquals(Arrays.asList("1", "2"), d.get("e"));
        assertEquals("hi", d.get("f"));
        assertEquals(Arrays.asList("k1", "k2"), d.get("g"));
        assertEquals("正文", r.body);
    }

    @Test public void yamlErrorsDoNotThrow() {
        assertFalse(MiniYaml.parseDocument("---\na: [x, y\n---\nb").errors.isEmpty());
        assertFalse(MiniYaml.parseDocument("---\nnot yaml line\n---\nb").errors.isEmpty());
        assertFalse(MiniYaml.parseDocument("---\na: \"open\n---\nb").errors.isEmpty());
        assertFalse(MiniYaml.parseDocument("---\na: 1\nno end").errors.isEmpty());
        assertFalse(MiniYaml.parseDocument("no front matter").hasFrontMatter);
        assertFalse(MiniYaml.parseDocument("---\n  indented: 1\n---\nb").errors.isEmpty());
        assertFalse(MiniYaml.parseDocument(null).hasFrontMatter);
    }

    @Test public void badFilesAreSkippedOthersStillLoad() {
        SkillIndex i = idx("skill_wx.md", WECHAT,
                "skill_bad.md", "---\nid: bad\ntriggers:\n  keywords: [a, b\n---\n正文",
                "skill_noid.md", "---\nname: x\n---\n正文",
                "skill_Bad.md", md("Bad", "", "正文"),
                "skill_empty.md", md("empty", "triggers:\n  keywords: [x]", ""),
                "skill_dup.md", md("wx", "", "又一个"),
                "skill_rx.md", md("rx", "triggers:\n  regex: [\"(unclosed\", \"ok\\d\"]\nweird: 1", "正文"),
                "skill_draft_x.md", md("draft_x", "status: draft", "草稿"),
                "skill_name.md", md("other", "triggers:\n  keywords: [y]", "正文"));
        assertEquals(Arrays.asList("wx", "rx", "other"), ids(i.skills()));
        String p = i.problems().toString();
        assertTrue(p, p.contains("skill_bad.md") && p.contains("skill_noid.md") && p.contains("skill_Bad.md") && p.contains("skill_empty.md"));
        assertTrue(p, p.contains("id 重复") && p.contains("草稿") && p.contains("正则坏了") && p.contains("未知键：weird") && p.contains("文件名应为 skill_other.md"));
        assertEquals("坏正则跳过、好正则保留", 1, i.byId("rx").regex.size());
    }

    // ------------------------------------------------------------------ 打分

    @Test public void triggerScoring() {
        SkillIndex i = idx("skill_wx.md", WECHAT);
        SkillMatcher m = new SkillMatcher(i, null);
        Skill wx = i.byId("wx");
        assertEquals(3, m.score(wx, in("发微信")).score);
        assertEquals(4, m.score(wx, in("微信告诉")).score);                  // ≥4 字关键词 +4
        SkillMatcher.Scored s = m.score(wx, in("给 老王 发个微信，说晚点到"));    // 去空白标点后正则命中
        assertTrue(s.reason, s.reason.contains("re:") && s.score >= 5);
        SkillMatcher.Input fg = in("帮我回一下");
        fg.foregroundPkg = "com.tencent.mm";
        fg.screenTexts = Arrays.asList("按住 说话", "张三");
        SkillMatcher.Scored f = m.score(wx, fg);
        assertEquals(f.reason, 5, f.score);                                   // 前台 +3、屏幕特征 +2
        SkillMatcher.Scored z = m.score(wx, in("给妈妈发个微信说我到家啦"));
        assertTrue(z.reason, z.reason.contains("fuzzy:"));
        assertEquals("排除词归零", 0, m.score(wx, in("发微信朋友圈")).score);
        assertEquals(0, m.score(wx, in("今天天气怎么样")).score);
        SkillMatcher.Input it = in("什么都不像");
        it.intents = Arrays.asList("CLOCK", "map");
        assertEquals(2, m.score(idx("skill_alarm.md", ALARM).byId("alarm"), it).score);
    }

    @Test public void thresholdTopKAndOrder() {
        SkillIndex i = idx("skill_wx.md", WECHAT, "skill_alarm.md", ALARM, "skill_nav.md", NAV,
                "skill_four.md", md("four", "triggers:\n  keywords: [叫醒, 导航]", "正文"));
        SkillMatcher m = new SkillMatcher(i, null);
        SkillMatcher.Selection none = m.select(in("今天天气"));
        assertTrue(none.isEmpty());
        assertEquals("", none.block);
        SkillMatcher.Selection one = m.select(in("闹钟"));
        assertTrue("单个 3 分关键词不到阈值 5", one.isEmpty());
        SkillMatcher.Selection sel = m.select(in("叫醒我的闹钟然后导航打车"));
        assertEquals(2, sel.picked.size());
        assertTrue(sel.log.toString(), sel.log.toString().contains("drop:topk"));
        assertTrue(sel.block.startsWith(SkillMatcher.HEADER));
        for (String l : sel.log) assertTrue(l, l.startsWith("[SKILL] "));
    }

    @Test public void requiresToolsAndRoot() {
        SkillIndex i = idx("skill_wx.md", WECHAT, "skill_r.md", md("r", "triggers:\n  keywords: [静默安装, 安装]\nrequires:\n  root: true", "正文"));
        SkillMatcher m = new SkillMatcher(i, null);
        SkillMatcher.Input a = in("给妈妈发微信");
        a.tools = Arrays.asList("web.search");
        SkillMatcher.Selection s = m.select(a);
        assertTrue(s.isEmpty());
        assertTrue(s.log.toString().contains("skip:no_tools"));
        a.tools = Arrays.asList("gui.unfold");
        assertEquals(Arrays.asList("wx"), m.select(a).ids());
        SkillMatcher.Input r = in("静默安装一下");
        r.root = Boolean.FALSE;
        assertTrue(m.select(r).log.toString().contains("skip:no_root"));
        r.root = null;
        assertEquals(Arrays.asList("r"), m.select(r).ids());
    }

    @Test public void conflictsKeepHigherScore() {
        SkillIndex i = idx("skill_wx.md", WECHAT, "skill_sms.md", SMS);
        SkillMatcher m = new SkillMatcher(i, null);
        SkillMatcher.Selection s = m.select(in("给老王发微信"));
        assertEquals(Arrays.asList("wx"), s.ids());
        assertTrue(s.log.toString(), s.log.toString().contains("sms") && s.log.toString().contains("drop:conflict:wx"));
    }

    @Test public void budgetTruncationAndTotal() {
        StringBuilder big = new StringBuilder();
        for (int k = 0; k < 120; k++) big.append("第").append(k).append("行：很长很长的说明文字。\n");
        SkillIndex i = idx("skill_a.md", md("a", "budget: 900\ntriggers:\n  keywords: [测试预算, 测试]", big.toString()),
                "skill_b.md", md("b", "budget: 900\ntriggers:\n  keywords: [测试预算, 测试]", big.toString()),
                "skill_c.md", md("c", "budget: 50\ntriggers:\n  keywords: [小预算]", big.toString()));
        SkillMatcher m = new SkillMatcher(i, null);
        SkillMatcher.Selection s = m.select(in("测试预算"));
        assertTrue("总长 ≤ 1500：" + s.chars, s.chars <= 1500);
        assertEquals("第二个只剩约 560 字，仍能放", 2, s.picked.size());
        assertTrue(s.block.contains("…"));
        assertTrue("标题标明来源", s.block.contains("（来源 skill_a.md）"));
        String[] secs = s.block.split("\n### ");
        assertTrue(secs[1].length() <= 900);
        SkillMatcher.Config tight = new SkillMatcher.Config();
        tight.totalBudget = 700;
        SkillMatcher.Selection t = new SkillMatcher(i, tight).select(in("测试预算"));
        assertEquals(1, t.picked.size());
        assertTrue(t.log.toString().contains("drop:budget"));
        assertTrue(t.chars <= 700);
        assertEquals("budget 小于 80 按 80", 80, i.byId("c").budget);
        assertNotNull(m.render(i.byId("a")));
    }

    // ------------------------------------------------------------------ 多轮

    @Test public void sessionHoldsFollowUpsAndUnloadsOnTopicSwitch() {
        SkillIndex i = idx("skill_wx.md", WECHAT, "skill_alarm.md", ALARM, "skill_nav.md", NAV);
        SkillMatcher m = new SkillMatcher(i, null);
        SkillSession ss = new SkillSession();
        long t = 1000000;
        assertEquals(Arrays.asList("wx"), m.select(in("给老婆发微信说我晚点回"), ss, t).ids());
        SkillMatcher.Selection f = m.select(in("改成九点"), ss, t + 5000);
        assertEquals("短追问保持", Arrays.asList("wx"), f.ids());
        assertTrue(f.log.toString().contains("hold:follow_up"));
        SkillMatcher.Selection sw = m.select(in("定个明天早上七点的闹钟叫醒我"), ss, t + 9000);
        assertEquals("换话题：新技能进，旧技能卸", Arrays.asList("alarm"), sw.ids());
        assertTrue(sw.log.toString(), sw.log.toString().contains("wx 0 unload:topic_switch"));
        SkillMatcher.Selection lng = m.select(in("顺便介绍一下北京有哪些好玩的博物馆和公园适合周末带孩子去"), ss, t + 12000);
        assertEquals("以「顺便」开头算追问", Arrays.asList("alarm"), lng.ids());
        SkillMatcher.Selection unrelated = m.select(in("介绍一下北京有哪些好玩的博物馆和公园适合周末带孩子"), ss, t + 15000);
        assertTrue("长句、无命中、不像追问 → 卸载", unrelated.isEmpty());
        assertTrue(ss.loaded().isEmpty());
    }

    @Test public void sessionStaleAfterTurnsOrTtl() {
        SkillIndex i = idx("skill_wx.md", WECHAT);
        SkillMatcher m = new SkillMatcher(i, null);
        SkillSession ss = new SkillSession();
        long t = 0;
        m.select(in("给老王发微信"), ss, t);
        assertEquals(1, m.select(in("好的"), ss, t + 1000).picked.size());
        assertEquals(1, m.select(in("再改一下"), ss, t + 2000).picked.size());
        assertEquals(1, m.select(in("嗯"), ss, t + 3000).picked.size());
        SkillMatcher.Selection st = m.select(in("对"), ss, t + 4000);
        assertTrue("连续 3 轮只靠保持 → 第 4 轮 stale", st.isEmpty());
        assertTrue(st.log.toString().contains("unload:stale"));
        m.select(in("给老王发微信"), ss, t + 10000);
        assertTrue("超过 TTL 不算追问", m.select(in("好的"), ss, t + 10000 + 6 * 60 * 1000L).isEmpty());
        m.select(in("给老王发微信"), ss, t + 20000000);
        ss.reset();
        assertTrue(m.select(in("好的"), ss, t + 20001000).isEmpty());
    }

    @Test public void heldSkillStillCountsPartialScore() {
        SkillIndex i = idx("skill_wx.md", WECHAT, "skill_alarm.md", ALARM);
        SkillMatcher m = new SkillMatcher(i, null);
        SkillSession ss = new SkillSession();
        m.select(in("给老王发微信"), ss, 0);
        SkillMatcher.Input fg = in("这次换成用户刚才说的那句完整的话发过去吧谢谢你啦");
        fg.foregroundPkg = "com.tencent.mm";   // 3 分 < 阈值，但 ≥ 保持阈值 2
        SkillMatcher.Selection s = m.select(fg, ss, 1000);
        assertEquals(Arrays.asList("wx"), s.ids());
        assertTrue(s.log.toString().contains("hold:pkg:"));
    }

    // ------------------------------------------------------------------ LLM 兜底 / 草稿

    @Test public void llmPickerParsesOnlyKnownIds() {
        SkillIndex i = idx("skill_wx.md", WECHAT, "skill_alarm.md", ALARM);
        final String[] reply = {"alarm"};
        final String[] seen = {null};
        ModelPort fake = new ModelPort() {
            @Override public String complete(String system, String user, int maxTokens, int timeoutMs) {
                seen[0] = system;
                return reply[0];
            }
        };
        assertEquals("alarm", LlmPicker.pick(fake, i, "嗯那个", "agent", 100).id);
        assertTrue(seen[0].contains("wx：") && seen[0].contains("alarm："));
        reply[0] = " wx\n因为…";
        assertEquals("wx", LlmPicker.pick(fake, i, "x", "agent", 100).id);
        reply[0] = "none";
        assertNull(LlmPicker.pick(fake, i, "x", "agent", 100));
        reply[0] = "rm_rf";
        assertNull(LlmPicker.pick(fake, i, "x", "agent", 100));
        reply[0] = null;
        assertNull(LlmPicker.pick(fake, i, "x", "agent", 100));
        assertNull("ask 路径没有这些技能", LlmPicker.pick(fake, i, "x", "ask", 100));
        assertNull(LlmPicker.pick(null, i, "x", "agent", 100));
    }

    @Test public void drafterRedactsAndDraftIsNeverLoaded() {
        SkillLearner.Episode e = new SkillLearner.Episode("把 13812345678 和 a@b.com 发给张三 https://x.cn/p?q=1", "微信", "done",
                Arrays.asList("app.launch(app)", "phone.operate(goal,app)"));
        SkillDrafter.Draft d = new RuleDrafter().draft(e, 1730000000000L);
        assertNotNull(d);
        assertTrue(d.fileName.startsWith("skill_draft_"));
        assertFalse(d.markdown, d.markdown.contains("13812345678") || d.markdown.contains("a@b.com") || d.markdown.contains("x.cn"));
        assertTrue(d.markdown.contains("status: draft") && d.markdown.contains("tools: [app.launch, phone.operate]"));
        SkillIndex i = new SkillIndex();
        assertNull("草稿永不进索引", i.add(d.fileName, d.markdown));
        assertEquals(0, i.size());
        assertTrue(i.problems().toString().contains("草稿"));
        // 去掉 draft 后是一份格式合法的技能（证明生成的头部能被解析）
        assertNotNull(i.add(d.fileName, d.markdown.replace("status: draft", "status: active")));
        assertNull("失败任务不沉淀", new RuleDrafter().draft(new SkillLearner.Episode("x", null, "partial", Arrays.asList("a", "b")), 1));
        assertNull("一步不沉淀", new RuleDrafter().draft(new SkillLearner.Episode("x", null, "done", Arrays.asList("a")), 1));
        assertNull(SkillDrafter.NONE.draft(e, 1));
    }

    @Test public void pathsFilter() {
        SkillIndex i = idx("skill_t.md", md("t", "paths: [agent, ask]\ntriggers:\n  keywords: [翻译一下, 翻译]", "正文"), "skill_wx.md", WECHAT);
        SkillMatcher m = new SkillMatcher(i, null);
        SkillMatcher.Input a = in("翻译一下 给老王发个微信");
        a.path = "ask";
        assertEquals(Arrays.asList("t"), m.select(a).ids());
        a.path = "agent";
        assertEquals(2, m.select(a).picked.size());
    }

    /** core 只依赖 java.* 与 plug.api / plug.skill（PLUGIN-SPEC 第 1 条；qc.sh 之外在全量 JVM 里也守一道）。 */
    @Test public void coreIsPureJava() throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/plug/skills/core");
        java.io.File[] fs = dir.listFiles();
        assertNotNull(dir.toString(), fs);
        assertTrue(fs.length >= 8);
        for (java.io.File f : fs) {
            String src = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            for (String line : src.split("\n")) if (line.startsWith("import ")) {
                boolean ok = line.startsWith("import java.") || line.startsWith("import com.onesay.assistant.plug.api.")
                        || line.startsWith("import com.onesay.assistant.plug.skill.core.") || line.startsWith("import com.onesay.assistant.plug.skills.core.")
                        || line.startsWith("import static org.junit");
                assertTrue(f.getName() + " 依赖越界：" + line, ok);
            }
            assertFalse(f.getName(), src.contains("com.onesay.assistant.v2") || src.contains("android."));
        }
    }

    static SkillMatcher.Input in(String q) {
        return new SkillMatcher.Input(q);
    }

    static List<String> ids(List<Skill> l) {
        java.util.ArrayList<String> o = new java.util.ArrayList<String>();
        for (Skill s : l) o.add(s.id);
        return o;
    }
}
