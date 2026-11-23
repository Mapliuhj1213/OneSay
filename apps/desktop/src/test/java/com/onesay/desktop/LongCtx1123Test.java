package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** [longctx-1123] 电脑端长单会话可靠性：六条各自单独测 + 评测集前后对比。 */
public class LongCtx1123Test {

    static Path tmp() throws Exception { return Files.createTempDirectory("lc1123"); }

    static Llm.Turn say(String t) { return new Llm.Turn(t, List.of(), Llm.msg("assistant", t)); }

    static Llm.Turn call(String id, String name, String args) { return new Llm.Turn(null, List.of(new Llm.ToolCall(id, name, args)), Llm.msg("assistant", "")); }

    static Chat chat(Store s, ChatAgent.Brain b) throws Exception {
        Chat ch = new Chat(new Config(tmp().resolve("c.properties")), s, new Main.TestActions(), List::of, x -> { });
        ch.brain = b;
        ch.pcExec = (cmd, a) -> Actions.Result.ok("做完了", new TreeMap<>(Map.of("output", "done", "exit", 0L)));
        return ch;
    }

    static String last(List<Map<String, Object>> msgs) { return String.valueOf(msgs.get(msgs.size() - 1).get("content")); }

    static String joined(List<Map<String, Object>> msgs) {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> m : msgs) b.append(m.get("role")).append(':').append(m.get("content")).append('\n');
        return b.toString();
    }

    static Map<String, Object> userMsg(Store s, String text) {
        for (Map<String, Object> m : s.list("chat")) if ("user".equals(m.get("role")) && text.equals(m.get("text"))) return m;
        return null;
    }

    /** 按问题给固定回答的假模型，记下每轮看到的消息。 */
    static final class Echoless implements ChatAgent.Brain {
        final List<List<Map<String, Object>>> seen = new ArrayList<>();
        @Override public Llm.Turn next(List<Map<String, Object>> m, List<Map<String, Object>> t) {
            seen.add(new ArrayList<>(m));
            return say("【完成】\n好的，答了：" + LongCtxEval.BANK.get(seen.size() % LongCtxEval.BANK.size()).answer);
        }
    }

    // ------------------------------------------------------------------ 1. 评测集：改前改后对比

    @Test public void evalLongSessionsBeatBaseline() throws Exception {
        Map<String, Object> r = LongCtxEval.run();
        // 改前（1ee84af，同一份评测、同一组骰子）：复读 15.6%、偏题 25.4%、遗忘 80%、幻觉 5%、人设失效 100%、硬规则违规 21%、工具输出峰值 40027 字、已答的旧问题又答 4 次
        long scored = (Long) r.get("scored");
        assertEquals("这几段会话每轮都交给了模型（没被当成固定指令）", (long) (Long) r.get("turns"), scored);
        assertTrue("复读 " + r.get("复读率"), (Long) r.get("echo") * 100 <= scored * 2);
        assertTrue("偏题 " + r.get("偏题率"), (Long) r.get("off") * 100 <= scored * 2);
        assertEquals("遗忘 " + r.get("遗忘率"), 0L, (long) (Long) r.get("probe_bad"));
        assertEquals("幻觉 " + r.get("幻觉率"), 0L, (long) (Long) r.get("fabricated"));
        assertEquals("人设 " + r.get("人设失效率"), 0L, (long) (Long) r.get("persona_bad"));
        assertTrue("硬规则 " + r.get("硬规则违规率"), (Long) r.get("rule_bad") * 100 <= scored * 6);
        assertEquals("准则 " + r.get("准则丢失率"), 0L, (long) (Long) r.get("guide_bad"));
        assertTrue("自检误判 " + r.get("自检误判率"), (Long) r.get("nudge_false") * 100 <= scored);
        assertEquals("已经答完的（任务中途问的）不再被当成待答又答一遍", 0L, (long) (Long) r.get("queued_reask"));
        assertEquals("任务中途问的那句在任务里答了", 4L, (long) (Long) r.get("queued_ok"));
        assertTrue("长任务的工具输出峰值压下来了：" + r.get("tool_chars_peak"), (Long) r.get("tool_chars_peak") <= ChatAgent.TOOL_BUDGET + 2000);
        assertEquals("长任务的原始输出不进之后的主对话", 0L, (long) (Long) r.get("heavy_in_main"));
        assertTrue("56 轮的会话自动换过段", (Long) r.get("relays") >= 4);
    }

    // ------------------------------------------------------------------ 2. 摘要写状态

    @Test public void stateIsFactsAndResolvedNotANarrative() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        Echoless eb = new Echoless();
        Chat ch = chat(s, eb);
        ch.send("我这周在调蓝牙配对，报错码是 E1047。", "text");
        for (int i = 0; i < 12; i++) ch.send(LongCtxEval.BANK.get(i).text, "text");
        ch.send("绿茶用多少度的水泡？", "text");
        List<Map<String, Object>> ctx = ch.context();
        String all = joined(ctx), state = "";
        for (Map<String, Object> m : ctx) if (String.valueOf(m.get("content")).contains("这段对话前面部分的状态")) state = String.valueOf(m.get("content"));
        assertFalse("窗口外的部分写成状态", state.isEmpty());
        assertTrue("事实留原话", state.contains("我这周在调蓝牙配对，报错码是 E1047。"));
        assertTrue("已答的标已答", state.contains("」已答"));
        assertTrue(state.contains("都已经答过"));
        for (String w : new String[] {"然后", "接着", "首先", "用户问了", "我回答"}) assertFalse("不写流水账：" + w, state.contains(w));
        // 窗口里：最近 3 轮原样，再往前的助手回答只留一句要点
        assertTrue(all.contains("（之前的回答，要点）"));
        assertTrue("最新一句在最后", last(eb.seen.get(eb.seen.size() - 1)).endsWith("绿茶用多少度的水泡？"));
    }

    @Test public void factDetection() {
        assertTrue(LongCtx.isFact("我下周三坐高铁去苏州出差。"));
        assertTrue(LongCtx.isFact("我的显示器是 27 寸的，接在 HDMI 2 口上。"));
        assertFalse(LongCtx.isFact("我出差是去哪个城市来着？"));
        assertFalse(LongCtx.isFact("帮我下载 7-Zip 的安装包到下载文件夹。"));
        assertFalse("长段粘贴不算", LongCtx.isFact(LongCtxEval.paste(0, 9)));
        assertFalse(LongCtx.isFact("绿茶用多少度的水泡？"));
    }

    // ------------------------------------------------------------------ 3. 每条用户消息记账

    @Test public void everyUserMessageIsBookkept() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        AtomicInteger n = new AtomicInteger();
        Chat ch = chat(s, (m, t) -> {
            int k = n.incrementAndGet();
            if (k == 2) throw new Exception("大模型超时");
            return say("【完成】\n答了第 " + k + " 次");
        });
        ch.send("Python 里怎么把一个列表倒过来？", "text");
        assertEquals(LongCtx.DONE, userMsg(s, "Python 里怎么把一个列表倒过来？").get("ask"));
        ch.send("红烧肉一般要炖多久才软烂？", "text");
        assertEquals("出错没答上：还是待答", LongCtx.OPEN, userMsg(s, "红烧肉一般要炖多久才软烂？").get("ask"));
        // 下一轮：末尾写着前面还有一句没答上；已经答完的不在待答里
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        ch.brain = (m, t) -> { seen.add(new ArrayList<>(m)); return say("【完成】\n好"); };
        ch.send("午睡多长时间最合适？", "text");
        String end = last(seen.get(0));
        assertTrue(end.contains("前面还没答上的") && end.contains("红烧肉一般要炖多久才软烂？"));
        assertFalse("已结的不当待答", end.contains("Python 里怎么把一个列表倒过来？"));
        assertEquals(LongCtx.DONE, userMsg(s, "午睡多长时间最合适？").get("ask"));
        // 固定指令：当场办完
        Store s2 = new Store(tmp().resolve("state.json"));
        Chat c2 = chat(s2, (m, t) -> say("不该叫到我"));
        c2.send("锁屏", "text");
        assertEquals(LongCtx.DONE, userMsg(s2, "锁屏").get("ask"));
    }

    @Test public void unfinishedTaskIsPartialAndQueuedIsResolved() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        AtomicInteger n = new AtomicInteger();
        Chat[] box = new Chat[1];
        Chat ch = chat(s, (m, t) -> {
            int k = n.incrementAndGet();
            if (k == 1) return call("c1", "run_command", "{\"description\":\"看日志\",\"command\":\"Get-ChildItem D:\\\\logs\"}");
            if (k == 2) { box[0].send("顺便问一下，汽车胎压多少算正常？", "text"); return call("c2", "run_command", "{\"description\":\"再看\",\"command\":\"Get-ChildItem D:\\\\logs\\\\a\"}"); }
            return say("【完成】\n看完了。另外胎压一般 2.3 到 2.5 bar。");
        });
        box[0] = ch;
        ch.send("帮我把 D 盘 logs 目录扫一遍", "text");
        assertEquals(LongCtx.DONE, userMsg(s, "帮我把 D 盘 logs 目录扫一遍").get("ask"));
        assertEquals("任务中途问的、交给了模型的：任务结束一起已结", LongCtx.DONE, userMsg(s, "顺便问一下，汽车胎压多少算正常？").get("ask"));
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        ch.brain = (m, t) -> { seen.add(new ArrayList<>(m)); return say("【完成】\n80 到 85 度。"); };
        ch.send("绿茶用多少度的水泡？", "text");
        assertTrue("下一轮里标明这句已经在任务里答过", joined(seen.get(0)).contains("顺便问一下，汽车胎压多少算正常？\n（OneSay 程序标注：这句是任务进行中发来的，已经在那个任务的答复里答过）"));
        // 停在批准上 / 到时长：没做完（不当待答，「继续」接着做）
        Store s3 = new Store(tmp().resolve("state.json"));
        Chat c3 = chat(s3, (m, t) -> call("x", "run_command", "{\"description\":\"删旧文件\",\"command\":\"Remove-Item x\"}"));
        c3.pcExec = (cmd, a) -> new Actions.Result(false, "电脑上拒绝了", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_DENIED)));
        c3.send("把旧文件删掉", "text");
        assertEquals(LongCtx.PARTIAL, userMsg(s3, "把旧文件删掉").get("ask"));
        assertTrue("没做完的不在「还没答上」里", c3.openAsks().isEmpty());
    }

    // ------------------------------------------------------------------ 4. 长期记忆按需检索、原文可查

    @Test public void longMemoryIsRetrievedOnDemand() throws Exception {
        String mem = LongCtxEval.seededMemory();
        String pick = LongCtx.memoryFor(mem, "我家猫叫什么名字？", 8);
        assertTrue(pick.contains("用户的猫叫团子"));
        assertFalse("不相关的不塞进来", pick.contains("用户不吃香菜"));
        assertTrue("其余的用 recall 查", pick.contains("recall"));
        assertEquals("记忆不长就整份给", "- 喜欢简短回答", LongCtx.memoryFor("- 喜欢简短回答", "随便", 8));
        Store s = new Store(tmp().resolve("state.json"));
        s.setMemory(mem, 1);
        Chat ch = chat(s, new Echoless());
        ch.send("我家猫叫什么名字？", "text");
        String sp = ch.systemPrompt();
        assertTrue(sp.contains("用户的猫叫团子"));
        assertFalse(sp.contains("用户的公司在浦东"));
    }

    @Test public void recallFindsOriginalTextIncludingArchive() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        s.setMemory("- 用户的猫叫团子", 1);
        Chat ch = chat(s, new Echoless());
        ch.send("我这周在调蓝牙配对，报错码是 E1047。", "text");
        ch.askId = ""; // 不在答题中（答题时 askId 是正在问的那句，自己不算）
        Actions.Result r = ch.recall(Map.of("query", "蓝牙 报错码"));
        assertTrue(r.ok);
        assertTrue("原话", String.valueOf(r.data.get("output")).contains("报错码是 E1047"));
        assertTrue(String.valueOf(r.data.get("output")).contains("用户说"));
        // 挤出 MAX_CHAT 的消息进存档，照样查得到原文
        for (int i = 0; i < Store.MAX_CHAT + 5; i++) s.add("chat", Map.of("role", "assistant", "text", "填充 " + i), Store.MAX_CHAT);
        assertNull("原消息已经被挤出对话", userMsg(s, "我这周在调蓝牙配对，报错码是 E1047。"));
        assertTrue(Files.exists(ch.archiveFile()));
        r = ch.recall(Map.of("query", "蓝牙 报错码"));
        assertTrue(String.valueOf(r.data.get("output")).contains("E1047"));
        assertTrue(String.valueOf(r.data.get("output")).contains("较早"));
        // 手机交来的任务只查长期记忆
        ch.origin = "phone1";
        r = ch.recall(Map.of("query", "蓝牙 报错码"));
        assertFalse(String.valueOf(r.data.get("output")).contains("E1047"));
        assertTrue(ch.recall(Map.of("query", "猫")).data.get("output").toString().contains("团子"));
        ch.origin = "";
        // 清空对话：存档一起清
        ch.clearArchive();
        assertFalse(Files.exists(ch.archiveFile()));
        // recall 是只读、在对话里做的（不走电脑执行链），卡上的说法是中文
        assertEquals("recall", ChatAgent.map(new Llm.ToolCall("1", "recall", "{\"description\":\"查\",\"query\":\"蓝牙\"}"))[0]);
        assertTrue(ChatAgent.describe("recall", Map.of("query", "蓝牙")).startsWith("查以前的对话"));
        assertTrue(ChatAgent.toolNames().contains("recall"));
    }

    @Test public void recallToolIsUsedInsideATask() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        Chat ch = chat(s, (m, t) -> { seen.add(new ArrayList<>(m)); return n.incrementAndGet() == 1 ? call("r", "recall", "{\"description\":\"查以前说过的\",\"query\":\"出差 城市\"}") : say("【完成】\n你说过去苏州出差。"); });
        ch.pcExec = (cmd, a) -> { throw new AssertionError("recall 不该走电脑执行链"); };
        s.add("chat", Map.of("role", "user", "text", "我下周三坐高铁去苏州出差。", "ask", "done"), Store.MAX_CHAT);
        s.add("chat", Map.of("role", "assistant", "text", "好的，记下了。"), Store.MAX_CHAT);
        ch.send("我出差是去哪个城市来着？", "text");
        assertTrue(last(seen.get(1)).contains("苏州"));
    }

    // ------------------------------------------------------------------ 5. 重活隔离

    @Test public void heavyTaskOutputsStayOutOfTheMainContext() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        AtomicInteger n = new AtomicInteger();
        long[] peak = {0};
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        Chat ch = chat(s, (m, t) -> {
            seen.add(new ArrayList<>(m));
            peak[0] = Math.max(peak[0], ChatAgent.toolChars(m));
            int k = n.incrementAndGet();
            if (k <= 8) return call("h" + k, "run_command", "{\"description\":\"扫第" + k + "批\",\"command\":\"Get-Content D:\\\\logs\\\\p" + k + ".log\"}");
            return say("【完成】\n报错最多的是 net 模块。");
        });
        ch.pcExec = (cmd, a) -> Actions.Result.ok("命令执行完了", new TreeMap<>(Map.of("output", ("HEAVYLOG " + a.get("command") + "\n").repeat(200), "exit", 0L)));
        ch.send("帮我把 D 盘 logs 目录里的日志扫一遍，看看哪个模块报错最多。", "text");
        assertTrue("任务循环里的工具输出不超预算：" + peak[0], peak[0] <= ChatAgent.TOOL_BUDGET + 8000);
        ch.send("绿茶用多少度的水泡？", "text");
        String next = joined(seen.get(seen.size() - 1));
        assertFalse("下一轮主对话里没有长任务的原始输出", next.contains("HEAVYLOG"));
        assertTrue("只有结论", next.contains("报错最多的是 net 模块"));
    }

    @Test public void toolBudgetCompaction() {
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (int i = 0; i < 6; i++) msgs.add(ChatAgent.toolMsg("c" + i, Json.stringify(Map.of("ok", true, "output", "R" + i + "x".repeat(6000)))));
        assertTrue(ChatAgent.toolChars(msgs) > ChatAgent.TOOL_BUDGET);
        ChatAgent.compactToolResults(msgs, 2, 1200);
        assertTrue(ChatAgent.toolChars(msgs) <= ChatAgent.TOOL_BUDGET);
        assertTrue("最近两个原样", String.valueOf(msgs.get(5).get("content")).length() > 6000);
    }

    // ------------------------------------------------------------------ 6. 最新一句放最后标出来 + 出口自检

    @Test public void latestIsMarkedLastWithRules() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        Echoless b = new Echoless();
        Chat ch = chat(s, b);
        ch.send("Python 里怎么把一个列表倒过来？", "text");
        ch.send("绿茶用多少度的水泡？", "text");
        List<Map<String, Object>> m = b.seen.get(1);
        String end = last(m);
        assertEquals("user", m.get(m.size() - 1).get("role"));
        assertTrue("最新一句原样放在最后", end.endsWith("\n绿茶用多少度的水泡？"));
        assertTrue(end.contains("【用户最新一句：只回答 / 只照做这一句】"));
        assertTrue("核心人设和硬规矩在末尾短短再说一次", end.contains("你是 OneSay") && end.contains("简体中文") && end.contains("不用 markdown"));
        assertTrue(end.contains("都已经答过"));
        assertTrue("规矩短", end.length() < 600);
        for (int i = 0; i < m.size() - 1; i++) assertFalse("只有最后一条带标记", String.valueOf(m.get(i).get("content")).contains("【用户最新一句"));
        assertFalse("存进对话的是原话", String.valueOf(userMsg(s, "绿茶用多少度的水泡？").get("text")).contains("OneSay"));
    }

    @Test public void selfCheckCatchesEchoAndOffTopicAndRetriesOnce() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        Chat ch = chat(s, (m, t) -> { seen.add(new ArrayList<>(m)); return say("【完成】\nPython 列表倒过来用 lst[::-1]，想原地改就用 lst.reverse()。"); });
        ch.send("Python 里怎么把一个列表倒过来？", "text");
        assertEquals("答得对不打回", 1, seen.size());
        // 答偏：用户问绿茶，模型又答 Python
        seen.clear();
        AtomicInteger n = new AtomicInteger();
        ch.brain = (m, t) -> { seen.add(new ArrayList<>(m)); return n.incrementAndGet() == 1 ? say("【完成】\nPython 列表倒过来用 lst[::-1]，想原地改就用 lst.reverse()。") : say("【完成】\n绿茶用 80 到 85 度的水泡。"); };
        Map<String, Object> card = ch.send("绿茶用多少度的水泡？", "text");
        assertEquals(2, seen.size());
        assertTrue(last(seen.get(1)).contains("答的是前面已经答过的") && last(seen.get(1)).contains("只针对用户最新这一句：「绿茶用多少度的水泡？」"));
        assertTrue(String.valueOf(card.get("answer")).contains("80 到 85 度"));
        // 复读：最多重答一次（第二次还复读也不再打回，不死循环）
        seen.clear();
        ch.brain = (m, t) -> { seen.add(new ArrayList<>(m)); return say("你是说「午睡多长时间最合适？」对吧？"); };
        ch.send("午睡多长时间最合适？", "text");
        assertEquals(2, seen.size());
        assertTrue(last(seen.get(1)).contains("原样复述"));
    }

    @Test public void selfCheckDoesNotFlagNormalAnswersOrFollowUps() {
        List<String> qs = new ArrayList<>(), as = new ArrayList<>();
        int bad = 0;
        for (LongCtxEval.Q q : LongCtxEval.BANK) {
            String why = ReplyCheck.why(q.answer, q.text, qs, as);
            if (why != null) { bad++; System.err.println("误判：" + q.text + " → " + why); }
            qs.add(q.text);
            as.add(q.answer);
        }
        assertEquals("题库里 30 个正常回答（含追问）一个都不误判", 0, bad);
        assertNull("确认类短回应不算答偏", ReplyCheck.why("好的，记下了。", "我的显示器是 27 寸的，接在 HDMI 2 口上。", List.of("我下周三坐高铁去苏州出差。"), List.of("好的，记下了。")));
        assertNull("用户要求复述不算复读", ReplyCheck.why("我下周三坐高铁去苏州出差", "把这句话重复一遍：我下周三坐高铁去苏州出差", List.of(), List.of()));
        assertNull("又问了一遍同一个问题", ReplyCheck.why("红烧肉小火炖 60 到 90 分钟就软烂了。", "红烧肉要炖多久才软烂来着", List.of("红烧肉一般要炖多久才软烂？"), List.of("红烧肉小火炖 60 到 90 分钟就软烂了，筷子能轻松戳透就行。")));
        assertEquals("echo", ReplyCheck.why("绿茶用多少度的水泡？", "绿茶用多少度的水泡？", List.of(), List.of()));
        assertEquals("leak", ReplyCheck.why("（OneSay 程序提示，不是用户说的话）规矩：…", "你好啊朋友", List.of(), List.of()));
        assertTrue(ReplyCheck.why("我这周在调蓝牙配对，报错码是 E1047", "今天天气怎么样呀朋友", List.of("我这周在调蓝牙配对，报错码是 E1047。"), List.of("好的")).startsWith("echo_old"));
        String nudge = ReplyCheck.nudge("off:红烧肉一般要炖多久才软烂？", "绿茶用多少度的水泡？");
        assertTrue(nudge.startsWith("（OneSay 内部提示）"));
        for (String bad2 : new String[] {"Bixby", "三星", "小布", "recall", "selfCheck"}) assertFalse(nudge.contains(bad2));
    }

    // ------------------------------------------------------------------ 准则按需加载 + 已加载清单

    @Test public void guidesLoadOnDemandAndSurviveCompactionAndRelay() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        Chat ch = chat(s, (m, t) -> { seen.add(new ArrayList<>(m)); return say("【完成】\n好的。"); });
        ch.send("Python 里怎么把一个列表倒过来？", "text");
        String sp = String.valueOf(seen.get(0).get(0).get("content"));
        assertFalse("没沾边的准则不加载", sp.contains("不要用 Invoke-WebRequest / curl 下载大文件"));
        assertFalse(sp.contains("date_verified"));
        assertTrue("留一句索引", sp.contains("专门的做法（") && sp.contains("下载文件用 download 工具"));
        assertTrue("核心还在", sp.contains("run_command") && sp.contains("不要只给教程") && sp.contains("【需要你操作】") && sp.contains("不要用 markdown 符号"));
        // 沾边就加载，记进已加载清单
        ch.send("帮我下载 7-Zip 的安装包到下载文件夹。", "text");
        sp = String.valueOf(seen.get(1).get(0).get("content"));
        assertTrue(sp.contains("不要用 Invoke-WebRequest / curl 下载大文件"));
        assertTrue(ch.sessionGuides().contains("download"));
        // 下一句不沾边：照样在（已加载清单）
        ch.send("再来一个，换成 VLC 的。", "text");
        sp = String.valueOf(seen.get(2).get(0).get("content"));
        assertTrue(sp.contains("不要用 Invoke-WebRequest / curl 下载大文件"));
        assertTrue("末尾写着已加载清单", last(seen.get(2)).contains("已加载的做法准则：下载和安装"));
        // 第一次用到某类工具：准则跟着结果过去，并进清单
        seen.clear();
        ch.brain = (m, t) -> { seen.add(new ArrayList<>(m)); return n.incrementAndGet() == 1 ? call("s", "reminder", "{\"description\":\"看提醒\",\"action\":\"list\"}") : say("【完成】\n看了。"); };
        ch.reminders = null;
        ch.send("看看我那些东西", "text");
        assertTrue(String.valueOf(seen.get(1).get(seen.get(1).size() - 1).get("content")).contains("OneSay 准则（第一次用到"));
        assertTrue(ch.sessionGuides().contains("reminder"));
        // 自动换段之后清单还在
        ch.newSession(true);
        assertTrue(ch.sessionGuides().containsAll(List.of("download", "reminder")));
        // 手动「新对话」：干净的一段，重新按需加载
        assertTrue(ch.newChat().ok);
        assertTrue(ch.sessionGuides().isEmpty());
        // 老的静态版本照旧全量（其他地方 / 测试用）
        String full = ChatAgent.systemPrompt("n", "w", "h", "");
        for (ChatGuides.G g : ChatGuides.ALL) assertTrue(g.id, full.contains(g.text.trim().substring(0, 20)));
    }

    @Test public void stepToGuideMapping() {
        assertEquals("download", ChatGuides.byStep("net_download", ""));
        assertEquals("download", ChatGuides.byStep("sys_exec", "Invoke-WebRequest https://x/a.exe -OutFile C:\\a.exe"));
        assertEquals("web", ChatGuides.byStep("sys_exec", "(Invoke-WebRequest https://x -UseBasicParsing).Content"));
        assertEquals("phone", ChatGuides.byStep("phone_status", ""));
        assertEquals("screen", ChatGuides.byStep("android_ui", ""));
        assertNull(ChatGuides.byStep("sys_exec", "Get-ChildItem C:\\"));
        assertTrue(ChatGuides.byWords("明天下午三点提醒我开会").contains("reminder"));
        assertTrue(ChatGuides.byWords("帮我截个图看看屏幕").contains("screen"));
    }

    // ------------------------------------------------------------------ 换段：自动带接力摘要 / 手动新对话

    @Test public void longSessionRollsOverWithRelayAndManualNewChatIsClean() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        Chat ch = chat(s, (m, t) -> { seen.add(new ArrayList<>(m)); return say("【完成】\n好的。"); });
        ch.send("我这周在调蓝牙配对，报错码是 E1047。", "text");
        for (int i = 0; i < LongCtx.SESSION_TURNS - 1; i++) ch.send(LongCtxEval.BANK.get(i % LongCtxEval.BANK.size()).text, "text");
        assertEquals(0, count(s, "session"));
        ch.send("绿茶用多少度的水泡？", "text");
        assertEquals("第 " + (LongCtx.SESSION_TURNS + 1) + " 句自动换段", 1, count(s, "session"));
        Map<String, Object> mk = marker(s);
        assertEquals("auto", mk.get("kind"));
        assertTrue(LongCtx.strList(mk.get("facts")).contains("我这周在调蓝牙配对，报错码是 E1047。"));
        assertFalse("界面上的分隔线说人话", String.valueOf(mk.get("text")).matches(".*(session|relay|auto).*"));
        String all = joined(seen.get(seen.size() - 1));
        assertTrue("接力摘要带着前面的事实", all.contains("报错码是 E1047"));
        assertTrue(all.contains("自动换了一段"));
        assertFalse("上一段的原话不逐字进来", all.contains("user:Python 里怎么把一个列表倒过来？"));
        // 「继续」不触发换段
        assertFalse(Intent.isContinuation("绿茶用多少度的水泡？"));
        // 手动新对话：干净
        assertTrue(ch.newChat().ok);
        ch.send("你好，今天想聊点别的", "text");
        all = joined(seen.get(seen.size() - 1));
        assertFalse(all.contains("E1047"));
        assertFalse(all.contains("绿茶"));
        assertEquals("manual", marker(s).get("kind"));
    }

    static int count(Store s, String via) { int n = 0; for (Map<String, Object> m : s.list("chat")) if (via.equals(m.get("via"))) n++; return n; }

    static Map<String, Object> marker(Store s) { Map<String, Object> r = null; for (Map<String, Object> m : s.list("chat")) if ("session".equals(m.get("via"))) r = m; return r; }

    @Test public void continueAcrossRelayStillGetsTheFullTaskRecord() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        AtomicInteger n = new AtomicInteger();
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        Chat ch = chat(s, (m, t) -> { seen.add(new ArrayList<>(m)); return n.incrementAndGet() == 1 ? call("c", "run_command", "{\"description\":\"查 MuMu\",\"command\":\"Get-ChildItem C:\\\\MuMu\"}") : say("【完成】\n好"); });
        ch.pcExec = (cmd, a) -> new Actions.Result(false, "电脑上拒绝了", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_TIMEOUT)));
        ch.send("帮我看看 MuMu 装在哪然后装个游戏", "text");
        ch.newSession(true);
        ch.send("继续", "text");
        String all = joined(seen.get(seen.size() - 1));
        assertTrue("换段前的任务照样完整给", all.contains("执行命令：Get-ChildItem C:\\MuMu"));
    }

    @Test public void manualNewChatDoesNotResumeTheOldTask() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        AtomicInteger n = new AtomicInteger();
        Chat ch = chat(s, (m, t) -> n.incrementAndGet() == 1 ? call("c", "run_command", "{\"description\":\"查 MuMu\",\"command\":\"Get-ChildItem C:\\\\MuMu\"}") : say("【完成】\n好"));
        ch.pcExec = (cmd, a) -> new Actions.Result(false, "电脑上拒绝了", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_TIMEOUT)));
        ch.send("帮我看看 MuMu 装在哪然后装个游戏", "text");
        assertTrue(ch.continuing());
        assertTrue(ch.newChat().ok);
        assertFalse("手动新对话之后，「继续」不接旧任务", ch.continuing());
        Map<String, Object> r = ch.send("继续", "text");
        assertEquals("cmd", r.get("via"));
    }

    // ------------------------------------------------------------------ 界面

    @Test public void uiHasNewChatAndSessionDivider() throws Exception {
        String html = new String(Files.readAllBytes(Paths.get("src/main/resources/onesay/ui/index.html")), StandardCharsets.UTF_8);
        assertTrue(html.contains("onclick=\"chatNew()\">新对话</span>"));
        assertTrue(html.contains("api('chat_new',{})"));
        assertTrue(html.contains("x.via==='session'"));
        String app = new String(Files.readAllBytes(Paths.get("src/main/java/com/onesay/desktop/App.java")), StandardCharsets.UTF_8);
        assertTrue(app.contains("ui.on(\"chat_new\""));
        assertTrue("清空对话连存档一起清", app.contains("chat.clearArchive()"));
        for (String f : new String[] {"LongCtx.java", "ReplyCheck.java", "ChatGuides.java"}) {
            String src = new String(Files.readAllBytes(Paths.get("src/main/java/com/onesay/desktop/" + f)), StandardCharsets.UTF_8);
            for (String bad : new String[] {"Bixby", "三星", "小布", "DeX"}) assertFalse(f + " 里有 " + bad, src.contains(bad));
        }
        assertNotNull(new Chat(new Config(tmp().resolve("c.properties")), new Store(tmp().resolve("s.json")), new Main.TestActions(), List::of, x -> { }).newChat());
    }
}
