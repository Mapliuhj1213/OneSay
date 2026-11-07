package com.onesay.assistant.v2.mikaplug;

import com.onesay.assistant.plug.api.PlugRegistry;
import com.onesay.assistant.plug.skills.core.Skill;
import com.onesay.assistant.plug.skills.core.SkillIndex;
import com.onesay.assistant.plug.skills.core.SkillMatcher;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * ★ skill-pack：宿主侧——assets 里每份技能的格式（工具名必须在真实工具表里）、样例句命中、插件开关与零副作用、
 * 提示词注入形状、草稿只在开关开时写。
 */
public class SkillPackTest {

    @After public void tearDown() {
        MikaPlugs.clearOverrides();
        SkillPack.reset(null);
    }

    /** AgentLoop 能看到的全部工具签名：注册表 + 常驻 / 探针 / 点界面 / 识屏指代 / 记忆检索 / run_command。 */
    static Set<String> agentTools() {
        Set<String> s = new HashSet<String>();
        for (Capability c : CapabilityRegistry.getInstance().listCapabilities()) s.add(c.sign);
        s.addAll(Arrays.asList(com.onesay.assistant.v2.agent.WebTools.SIGN_SEARCH, com.onesay.assistant.v2.agent.WebTools.SIGN_READ, com.onesay.assistant.v2.agent.WebTools.SIGN_RESEARCH,
                com.onesay.assistant.v2.agent.LoopTools.SIGN_TODO, com.onesay.assistant.v2.agent.LoopTools.SIGN_ASK, com.onesay.assistant.v2.agent.LoopTools.SIGN_END,
                com.onesay.assistant.v2.agenttools.ToolTiers.SIGN_GUI_OPERATE, com.onesay.assistant.v2.agenttools.ToolTiers.SIGN_GUI_ENTRY,
                com.onesay.assistant.v2.probe.ProbeEngine.SIGN_APPS, com.onesay.assistant.v2.probe.ProbeEngine.SIGN_APP_INTENTS, com.onesay.assistant.v2.probe.ProbeEngine.SIGN_SHELL_READ,
                com.onesay.assistant.v2.s2.S2Capabilities.SIGN_SEARCH, RootCmd.SIGN,
                com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.SIGN_CONTEXT, com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.SIGN_NAV,
                com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.SIGN_CALENDAR, com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.SIGN_OPEN_URL,
                com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.SIGN_COPY));
        return s;
    }

    /** 前门直达（L0 规则 / S1–S3），不是 AgentLoop 工具：技能里不许当工具写。 */
    static final String[] FRONT_DOOR_ONLY = {"errand.run", "image.save", "screen.summary", "s1.taxi.to", "s2.nav.app", "s3.task.add", "s3.todo.add",
            "s2.memory.add", "s2.ledger.add", "plan.multi", "clarify.ask"};

    static Map<String, String> files() {
        return AssetMd.load(null, "skill_");
    }

    @Test public void assetsAreValidSkills() {
        Map<String, String> fs = files();
        assertTrue("技能数 15–25：" + fs.size(), fs.size() >= 15 && fs.size() <= 25);
        SkillIndex idx = new SkillIndex();
        for (Map.Entry<String, String> e : fs.entrySet()) assertNotNull("解析失败：" + e.getKey() + " " + idx.problems(), idx.add(e.getKey(), e.getValue()));
        assertTrue("无告警：" + idx.problems(), idx.problems().isEmpty());
        Set<String> tools = agentTools();
        assertTrue("工具表应很大：" + tools.size(), tools.size() > 100);
        for (String f : FRONT_DOOR_ONLY) assertFalse("前门工具不该在 AgentLoop 工具表里：" + f, tools.contains(f));
        Pattern bt = Pattern.compile("`([a-z_][a-z0-9_]*(?:\\.[a-z0-9_*]+)*)`");
        SkillMatcher m = new SkillMatcher(idx, null);
        for (Skill s : idx.skills()) {
            assertEquals(s.source, "skill_" + s.id + ".md");
            assertTrue(s.id + " 要有说明", s.description.length() > 0);
            assertTrue(s.id + " 要有工具", !s.tools.isEmpty());
            assertTrue(s.id + " 要有关键词或正则", !s.keywords.isEmpty() || !s.regex.isEmpty());
            assertTrue(s.id + " 要有样例句", s.examples.size() >= 2);
            assertTrue(s.id + " budget ≤ 700", s.budget <= 700);
            for (String t : s.tools) assertTrue(s.id + " requires.tools 不存在：" + t, tools.contains(t));
            for (String c : s.conflicts) assertNotNull(s.id + " 冲突对象不存在：" + c, idx.byId(c));
            Matcher mm = bt.matcher(s.body);
            while (mm.find()) {
                String t = mm.group(1);
                if (t.endsWith(".*") || t.startsWith("*")) continue;
                if (!t.contains(".") && !t.contains("_")) continue;   // 普通英文词
                assertTrue(s.id + " 正文提到的工具不存在：" + t, tools.contains(t));
            }
            for (String f : FRONT_DOOR_ONLY) assertFalse(s.id + " 正文把前门入口当工具：" + f, s.body.contains("`" + f + "`"));
            String sec = m.render(s);
            assertNotNull(s.id, sec);
            assertTrue(s.id + " 正文没被截断（预算够）", !sec.endsWith("…"));
            assertTrue(s.id + " 带来源", sec.contains("（来源 " + s.source + "）"));
            for (String p : s.paths) assertTrue(s.id + " paths 只能 agent / ask", p.equals("agent") || p.equals("ask"));
        }
    }

    /** 每份技能自己的样例句都应该把它选进 Top-2（模式识别的回归网）。 */
    @Test public void examplesSelectTheirSkill() {
        SkillIndex idx = SkillPack.index(null);
        SkillMatcher m = new SkillMatcher(idx, null);
        List<String> miss = new ArrayList<String>();
        for (Skill s : idx.skills()) for (String ex : s.examples) {
            SkillMatcher.Input in = new SkillMatcher.Input(ex);
            in.path = s.forPath("agent") ? "agent" : "ask";
            SkillMatcher.Selection sel = m.select(in);
            if (!sel.ids().contains(s.id)) miss.add(s.id + "←「" + ex + "」→" + sel.ids());
            assertTrue(ex + " 总长 " + sel.chars, sel.chars <= 1500);
        }
        assertTrue("样例没命中：" + miss, miss.isEmpty());
    }

    /** 真实说法 → 期望技能（含不该命中的闲聊）。 */
    @Test public void realisticUtterances() {
        SkillMatcher m = new SkillMatcher(SkillPack.index(null), null);
        String[][] cases = {
                {"给老婆发微信说我今晚加班", "msg_send"},
                {"用QQ告诉张三明天不去了", "msg_send"},
                {"给10086发个短信", "sms_send"},
                {"打电话给我妈", "phone_call"},
                {"明天早上六点半叫我起床", "alarm_timer"},
                {"二十分钟后叫我", "alarm_timer"},
                {"把周五下午的会加到日历", "reminder_schedule"},
                {"导航去最近的加油站", "nav_taxi"},
                {"打车去虹桥机场", "nav_taxi"},
                {"我的快递到哪了", "express_track"},
                {"帮我在京东买一箱牛奶", "shop_pay"},
                {"把亮度调到一半", "settings_toggle"},
                {"打开勿扰模式", "settings_toggle"},
                {"截个图", "screen_vision"},
                {"放一首周杰伦的歌", "media_control"},
                {"帮我查一下今天的新闻", "web_research"},
                {"把这张图片保存下来", "file_save"},
                {"我饿了用日语怎么说", "translate"},
                {"帮我把这个报名表填一下", "form_fill"},
                {"帮我下载高德地图", "app_manage"},
                {"先截图然后发到微信", "long_task"},
                {"刚才怎么没反应，再试一次", "failure_recovery"},
                {"看看有没有新邮件", "mail"},
                {"我上次让你记的车位号是多少", "memory_notes"},
                {"三星应用商店连不上网，别的app都好好的", "device_diag"},
                {"微信一打开就闪退怎么办", "device_diag"},
                {"手机最近耗电特别快还发烫", "device_diag"},
                {"为什么浏览器上不了网", "device_diag"},
        };
        List<String> bad = new ArrayList<String>();
        for (String[] c : cases) {
            List<String> got = m.select(new SkillMatcher.Input(c[0])).ids();
            if (!got.contains(c[1])) bad.add(c[0] + " → " + got + "（应含 " + c[1] + "）");
        }
        for (String chat : new String[]{"你好", "讲个笑话", "今天心情不错", "你是谁", "谢谢"}) {
            List<String> got = m.select(new SkillMatcher.Input(chat)).ids();
            if (!got.isEmpty()) bad.add(chat + " 不该加载 → " + got);
        }
        List<String> buy = m.select(new SkillMatcher.Input("我买的快递到哪了")).ids();
        if (buy.contains("shop_pay")) bad.add("「我买的快递到哪了」不该进购物：" + buy);
        assertTrue(bad.toString(), bad.isEmpty());
    }

    /** 用户原则（2026-11-02）：「不懂的先搜索再动手，特别是设备诊断类」——两份技能的关键句不许被删。 */
    @Test public void searchFirstPrinciple() {
        SkillIndex idx = SkillPack.index(null);
        Skill diag = idx.byId("device_diag"), lt = idx.byId("long_task");
        assertNotNull(diag);
        for (String must : new String[]{"复述症状", "单 App", "已知案例", "本地诊断知识库", "先 `web.search`", "不是指令", "只读检查", "dumpsys connectivity", "private_dns_mode", "下一步"})
            assertTrue("device_diag 缺「" + must + "」", diag.body.contains(must));
        // ★ int/1102：Tailscale / VPN 已知案例只保留在 DiagKnowledge（本地诊断先接住），技能里不再重复
        assertFalse("已知案例只保留一处", diag.body.contains("Tailscale"));
        assertTrue(lt.body.contains("没把握") && lt.body.contains("`web.search`"));
        assertTrue(lt.tools.contains("web.search"));
        // 诊断句由设备诊断接手，不进「助手自己失败」的恢复技能
        List<String> got = new SkillMatcher(idx, null).select(new SkillMatcher.Input("为什么三星应用商店打不开")).ids();
        assertTrue(got.toString(), got.contains("device_diag") && !got.contains("failure_recovery"));
    }

    @Test public void registryDefaultsAndZeroSideEffectWhenOff() {
        PlugRegistry rel = MikaPlugs.build(false), dbg = MikaPlugs.build(true);
        assertFalse(rel.spec(MikaPlugs.SKILLPACK).defaultOn);
        assertTrue(dbg.spec(MikaPlugs.SKILLPACK).defaultOn);
        assertFalse(dbg.spec(MikaPlugs.SKILLPACK_LLM).defaultOn);
        assertFalse(dbg.spec(MikaPlugs.SKILL).defaultOn);
        assertEquals(Arrays.asList(MikaPlugs.SKILLPACK), dbg.spec(MikaPlugs.SKILLPACK_LLM).deps);
        // JVM = release 口径：关 → 不读 assets、返回 null
        SkillPack.reset(null);
        assertNull(SkillPack.forAgent(null, "给老婆发微信说我今晚加班", null, null, false, true));
        assertNull(SkillPack.forAsk(null, "翻译一下你好", true));
        assertFalse("插件关时连索引都不加载", SkillPack.indexLoaded());
        assertEquals("null 块原样返回", "SYS", SkillPack.append("SYS", null));
        // 调试命令认顶层 id
        List<String[]> c = MikaPlugs.parseCommand(dbg, "skillpack=on,skillpack.llm=off,guard=off");
        assertArrayEquals(new String[]{"skillpack", "on"}, c.get(0));
        assertArrayEquals(new String[]{"skillpack.llm", "off"}, c.get(1));
        assertArrayEquals(new String[]{"mika.guard", "off"}, c.get(2));
    }

    @Test public void agentInjectionShapeAndToolGate() throws Exception {
        MikaPlugs.override(MikaPlugs.SKILLPACK, true);
        JSONArray tools = new JSONArray();
        tools.put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", "gui__unfold")));
        tools.put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", "ask_user")));
        String b = SkillPack.forAgent(null, "给老婆发微信说我今晚加班", Arrays.asList("app"), tools, false, true);
        assertNotNull(b);
        assertTrue(b, b.startsWith(SkillMatcher.HEADER) && b.contains("（来源 skill_msg_send.md）"));
        assertTrue(b.length() <= 1500);
        String sys = SkillPack.append("SYS", b);
        assertTrue(sys.startsWith("SYS\n\n【技能"));
        // 追问保持（同一会话）
        String f = SkillPack.forAgent(null, "改成九点", null, tools, false, false);
        assertNotNull("追问保持", f);
        assertTrue(f.contains("skill_msg_send.md"));
        // 新会话：清掉
        assertNull(SkillPack.forAgent(null, "改成九点", null, tools, false, true));
        // 本轮工具表里没有点界面工具 → 发微信技能不加载
        JSONArray none = new JSONArray().put(new JSONObject().put("function", new JSONObject().put("name", "web__search")));
        String n = SkillPack.forAgent(null, "给老婆发微信说我今晚加班", null, none, false, true);
        assertTrue(n == null || !n.contains("skill_msg_send.md"));
        // 自由问答只挑 ask 技能
        String a = SkillPack.forAsk(null, "我饿了用日语怎么说", true);
        assertNotNull(a);
        assertTrue(a.contains("skill_translate.md"));
        assertNull(SkillPack.forAsk(null, "给老婆发微信说我今晚加班", true));
        assertTrue(SkillPack.dryRun(null, "打电话给我妈").contains("phone_call"));
    }

    @Test public void draftsOnlyWhenSwitchOn() throws Exception {
        List<String> shapes = SkillPack.stepShapes(Arrays.asList("app.launch", "phone.operate"),
                Arrays.asList(new JSONObject().put("app", "微信"), new JSONObject().put("goal", "发给 13812345678").put("app", "微信")));
        assertEquals("app.launch(app)", shapes.get(0));
        assertFalse("不带参数值", shapes.toString().contains("13812345678") || shapes.toString().contains("微信"));
        assertNull("没有 Context / 开关关 → 不写", SkillPack.afterRun(null, "x", shapes, true, true));
        File tmp = new File(java.nio.file.Files.createTempDirectory("skdraft").toFile(), "skill_drafts");
        assertNull("一步不写", SkillPack.writeDraft(tmp, "x", shapes.subList(0, 1)));
        File f = SkillPack.writeDraft(tmp, "把地址发给张三", shapes);
        assertNotNull(f);
        assertEquals("skill_drafts", f.getParentFile().getName());
        String body = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        assertTrue(body.contains("status: draft"));
        assertNull("草稿不被索引接收", new SkillIndex().add(f.getName(), body));
        // AgentLoop 侧的门：成功 + debug 包 + mika.skilllearn 开（默认关）才调 afterRun —— 见 AgentLoop.run 的条件
        assertFalse(MikaPlugs.build(true).spec(MikaPlugs.SKILL).defaultOn);
    }
}
