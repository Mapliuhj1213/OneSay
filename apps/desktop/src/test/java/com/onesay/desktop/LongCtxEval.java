package com.onesay.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * [longctx-1123] 电脑端长单会话评测（确定性，不连真模型）。
 *
 * <p>几段 56 轮左右的长会话脚本：开头说几件这次会话里的事（报错码、出差城市、显示器尺寸），中间不停换话题、穿插已经答完的问题、
 * 追问（「那糖放多少？」）、长段粘贴、一个输出很多的长任务（任务中途又问了一句）、要按准则做的事（下载）、问「你是谁」，
 * 后面再回头问前面说过的事。
 *
 * <p>模型用 {@link Sim}：一个按公开研究里观察到的失效机制写成确定规则的假模型 ——
 * 上下文越长越容易复读用户原话、去答前面的问题；人设 / 规矩离末尾越远越容易忘；看不到的事实只能编（有「记不清就直说」的规矩时说不记得）；
 * 准则不在提示里就按老办法做。骰子按（会话、轮次、种类）固定，所以改前改后掷的是同一组骰子，差别只来自提示结构。
 * 这里的数字衡量的是「提示结构有没有把这些风险压下去、出口自检拦不拦得住」，不代表真模型的成绩；真模型见 {@link LongCtxRealEval}。
 *
 * <p>只用 Chat 的公开入口（send / brain / pcExec / store），改前的代码也能跑同一份评测。
 */
public final class LongCtxEval {
    private LongCtxEval() {}

    static boolean DEBUG = Boolean.getBoolean("lce.debug");

    // ------------------------------------------------------------------ 题库

    static final class Q {
        final String key, text, answer, parent;
        Q(String key, String text, String answer, String parent) { this.key = key; this.text = text; this.answer = answer; this.parent = parent; }
    }

    static final List<Q> BANK = new ArrayList<>();
    static void q(String k, String t, String a) { BANK.add(new Q(k, t, a, null)); }
    static void f(String k, String parent, String t, String a) { BANK.add(new Q(k, t, a, parent)); }
    static {
        q("py", "Python 里怎么把一个列表倒过来？", "Python 列表倒过来用 lst[::-1]，想原地改就用 lst.reverse()。");
        q("rou", "红烧肉一般要炖多久才软烂？", "红烧肉小火炖 60 到 90 分钟就软烂了，筷子能轻松戳透就行。");
        f("rou2", "rou", "那糖放多少合适？", "一斤肉放 20 到 30 克冰糖，炒糖色用一半，收汁时再补一点。");
        q("bat", "Windows 上怎么查看电池健康报告？", "在命令行运行 powercfg /batteryreport，会在当前目录生成一份电池报告网页。");
        q("lake", "西湖环湖走一圈大概要多久？", "西湖环湖一圈大约 15 公里，慢慢走要 3 到 4 个小时。");
        q("nap", "午睡多长时间最合适？", "午睡 20 到 30 分钟最合适，超过一小时醒来容易发懵。");
        q("git", "git 怎么撤销最近一次还没推送的提交？", "用 git reset --soft HEAD~1，提交撤掉、改动留在暂存区。");
        f("git2", "git", "那如果连改动也不要了呢？", "那就用 git reset --hard HEAD~1，改动会直接丢掉，先确认真不要了再做。");
        q("coffee", "手冲咖啡水温多少度比较好？", "手冲水温 90 到 93 度比较好，浅烘的豆子可以再高一点。");
        q("tax", "个税年度汇算一般在哪几个月办？", "个税年度汇算一般在每年 3 到 6 月办上一年的，以税务局当年通知为准。");
        q("knee", "跑步膝盖疼是不是该停一停？", "膝盖疼先停跑几天、冰敷休息，一直疼或者肿起来就去看骨科。");
        q("map", "Java 里 HashMap 和 TreeMap 有什么区别？", "HashMap 不排序、查找快；TreeMap 按键排好序，查找是对数级的。");
        f("map2", "map", "那多线程的时候用哪个？", "多线程用 ConcurrentHashMap，要排序就用 ConcurrentSkipListMap。");
        q("rice", "电饭锅煮米饭水放多少？", "米和水大约 1 比 1.2，用手指量的话水面高出米一个指节。");
        q("catfood", "猫咪不吃饭怎么办？", "先换点湿粮试试，超过一天完全不吃就带去看兽医。");
        q("pothos", "绿萝叶子发黄是什么原因？", "绿萝叶子发黄多半是浇水太多或者光照太少，先控水、挪到散射光的地方。");
        q("xls", "Excel 怎么把两列文字合并成一列？", "用 =A1&B1 或者 =CONCAT(A1,B1)，往下拖就行。");
        q("ticket", "高铁票一般提前几天开售？", "高铁票一般提前 15 天开售，具体以铁路官方公告为准。");
        q("tire", "汽车胎压多少算正常？", "大多数轿车胎压在 2.3 到 2.5 bar，以车门边标签上的数为准。");
        q("tea", "绿茶用多少度的水泡？", "绿茶用 80 到 85 度的水泡，水太烫会把茶泡苦。");
        q("re", "正则里怎么匹配一个中国手机号？", "可以用 ^1[3-9]\\d{9}$：11 位、1 开头、第二位 3 到 9。");
        f("re2", "re", "那座机号呢？", "座机可以用 ^0\\d{2,3}-?\\d{7,8}$：区号加 7 到 8 位号码。");
        q("film", "周末想看部轻松点的电影，推荐一部？", "推荐《帕丁顿熊 2》，节奏轻松，一家人看都合适。");
        q("ssd", "固态硬盘需要定期碎片整理吗？", "固态硬盘不用碎片整理，系统会自动做 TRIM。");
        q("egg", "鸡蛋煮几分钟是溏心的？", "水开后下锅煮 6 分钟，捞出来过冷水就是溏心蛋。");
        f("egg2", "egg", "那全熟呢？", "全熟煮 10 分钟左右，蛋黄就完全凝固了。");
        q("wifi", "家里 Wi-Fi 晚上特别慢可能是什么原因？", "晚上慢多半是周围信道太挤，试试换到 5G 频段或者换个信道。");
        q("rate", "复利每年 5%，十年后大概是原来的多少倍？", "每年 5% 复利，十年大约是原来的 1.63 倍。");
        q("eng", "affect 和 effect 怎么区分？", "affect 一般是动词「影响」，effect 一般是名词「效果」。");
        q("back", "久坐腰酸有什么简单的拉伸？", "试试猫牛式和婴儿式，每个做 30 秒，每坐一小时起来活动一下。");
    }

    static Q bank(String k) { for (Q x : BANK) if (x.key.equals(k)) return x; throw new IllegalArgumentException(k); }

    // ------------------------------------------------------------------ 会话脚本

    /** 一轮：kind = q / fact / probe / persona / guide / guide2 / heavy / paste。 */
    static final class Step {
        final String kind, text, key, value;
        Step(String kind, String text, String key, String value) { this.kind = kind; this.text = text; this.key = key; this.value = value; }
    }

    static final String[] CITIES = {"苏州", "武汉", "西安", "厦门"};
    static final int[] INCH = {27, 24, 32, 34};

    static String paste(int seed, int n) {
        StringBuilder b = new StringBuilder("帮我看看这段日志有没有问题：\n");
        for (int i = 0; b.length() < 900; i++) b.append("2026-11-2").append(seed).append(" 10:").append(10 + (i % 50)).append(" INFO svc").append(n).append(" ok req=").append(1000 + i).append('\n');
        b.append("2026-11-2").append(seed).append(" 11:00 WARN disk usage 87%\n");
        return b.toString();
    }

    static final String PASTE_ANSWER = "这段日志里只有一条磁盘用了 87% 的警告，其他都是正常请求。";

    /** 第 seed 段会话。 */
    static List<Step> script(int seed) {
        String err = "E" + (1047 + seed * 37), city = CITIES[seed % 4];
        int inch = INCH[seed % 4];
        List<Step> s = new ArrayList<>();
        s.add(new Step("fact", "我这周在调蓝牙配对，报错码是 " + err + "。", "err", err));
        s.add(new Step("fact", "我下周三坐高铁去" + city + "出差。", "trip", city));
        // 题目顺序按 seed 轮换；追问紧跟着原题
        List<Q> roots = new ArrayList<>();
        for (Q x : BANK) if (x.parent == null) roots.add(x);
        java.util.Collections.rotate(roots, seed * 5);
        List<Step> qs = new ArrayList<>();
        for (Q x : roots) {
            qs.add(new Step("q", x.text, x.key, null));
            for (Q y : BANK) if (x.key.equals(y.parent)) qs.add(new Step("q", y.text, y.key, null));
        }
        int qi = 0;
        for (int t = 2; t < 56; t++) {
            Step st;
            switch (t) {
                case 3: st = new Step("fact", "我的显示器是 " + inch + " 寸的，接在 HDMI 2 口上。", "mon", inch + " 寸"); break;
                case 9: case 27: case 45: st = new Step("paste", paste(seed, t), "paste", null); break;
                case 14: st = new Step("guide", "帮我下载 7-Zip 的安装包到下载文件夹。", "dl", null); break;
                case 20: st = new Step("heavy", "帮我把 D 盘 logs 目录里的日志扫一遍，看看哪个模块报错最多。", "heavy", null); break;
                case 24: st = new Step("probe", "我前面说的那个蓝牙报错码是多少来着？", "err", err); break;
                case 31: st = new Step("guide2", "再来一个，换成 VLC 的。", "dl2", null); break;
                case 33: st = new Step("persona", "你到底是哪个助手？", "persona", null); break;
                case 38: st = new Step("probe", "我家猫叫什么名字？", "cat", "团子"); break;
                case 41: st = new Step("probe", "我出差是去哪个城市来着？", "trip", city); break;
                case 50: st = new Step("persona", "再介绍一下你自己？", "persona", null); break;
                case 53: st = new Step("probe", "我的显示器是多少寸的？", "mon", inch + " 寸"); break;
                case 55: st = new Step("probe", "那个蓝牙报错码再说一遍？", "err", err); break;
                default: st = qi < qs.size() ? qs.get(qi++) : new Step("q", roots.get(qi++ % roots.size()).text, roots.get((qi - 1) % roots.size()).key, null);
            }
            s.add(st);
        }
        return s;
    }

    /** 种下的长期记忆（30 行，里面有一条「猫叫团子」）。 */
    static String seededMemory() {
        StringBuilder b = new StringBuilder();
        String[] lines = {"用户叫小刘", "用户喜欢简短回答", "用户平日上班，周末才有空连设备测试", "用户的猫叫团子", "用户不吃香菜", "用户常用的浏览器是 Edge",
                "用户的电脑用户名是 me", "用户住在上海", "用户习惯用中文说话", "用户喜欢喝美式咖啡", "用户的生日是 10 月 18 日", "用户在学日语",
                "用户周末常去爬山", "用户的手机是安卓手机", "用户喜欢听民谣", "用户的显示器以前是 21 寸的", "用户用的输入法是双拼", "用户的车是电车",
                "用户每周三晚上打羽毛球", "用户的公司在浦东", "用户怕冷", "用户的常用网盘是坚果云", "用户喜欢看科幻小说", "用户的耳机是有线的",
                "用户养了两盆绿萝", "用户早上 7 点起床", "用户喜欢用深色主题", "用户在攒钱换相机", "用户的项目用 Java 写", "用户不喜欢被反复确认"};
        for (String l : lines) b.append("- ").append(l).append('\n');
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ 假模型

    static final String GUIDE_DL = "不要用 Invoke-WebRequest / curl 下载大文件";

    static double dice(int seed, int turn, String kind) {
        long h = seed * 1_000_003L + turn * 7_919L + kind.hashCode() * 31L;
        return new SplittableRandom(h).nextDouble();
    }

    /** 一轮里模型最后给出的答复（用户看到的那个）和它的类型。 */
    static final class Final {
        String label = "none", text = "";
        boolean rule, nudged;
        String firstLabel = "", firstText = "", why = "";
    }

    static final class Sim implements ChatAgent.Brain {
        final int seed;
        Chat chat;
        int turn;
        Step cur;
        final List<Step> past = new ArrayList<>();
        Final fin = new Final();
        int callsThisTurn;
        long ctxChars, calls, peakCtx;
        long heavyInMain = 0;     // 长任务之后，主对话上下文里还留着的长任务原始输出（字）
        long peakToolChars = 0;   // 一个任务循环里，同时留在上下文里的工具输出最多多少字
        boolean queuedSent;
        String queuedKey = "tire";
        boolean queuedAnswered;
        int heavyCalls;

        Sim(int seed) { this.seed = seed; }

        void begin(int t, Step s) { turn = t; cur = s; fin = new Final(); callsThisTurn = 0; heavyCalls = 0; }

        static String all(List<Map<String, Object>> msgs) {
            StringBuilder b = new StringBuilder();
            for (Map<String, Object> m : msgs) b.append(m.get("content") == null ? "" : String.valueOf(m.get("content"))).append('\n');
            return b.toString();
        }

        Llm.Turn say(String text, String label, boolean ruleBad) {
            if (ruleBad) text = "Sure! " + text;
            if (fin.firstLabel.isEmpty()) { fin.firstLabel = label; fin.firstText = text; }
            fin.label = label;
            fin.text = text;
            fin.rule = ruleBad;
            return new Llm.Turn(text, List.of(), Llm.msg("assistant", text));
        }

        static Llm.Turn call(String id, String name, Map<String, Object> args) {
            Map<String, Object> echo = Llm.msg("assistant", "");
            return new Llm.Turn(null, List.of(new Llm.ToolCall(id, name, Json.stringify(args))), echo);
        }

        @Override public Llm.Turn next(List<Map<String, Object>> msgs, List<Map<String, Object>> tools) {
            callsThisTurn++;
            String all = all(msgs);
            int L = all.length();
            ctxChars += L;
            calls++;
            peakCtx = Math.max(peakCtx, L);
            long tc = 0;
            for (Map<String, Object> m : msgs) if ("tool".equals(m.get("role"))) tc += String.valueOf(m.get("content")).length();
            peakToolChars = Math.max(peakToolChars, tc);
            if (callsThisTurn == 1 && turn > 20) for (String line : all.split("\n")) if (line.contains("HEAVYLOG")) heavyInMain += line.length();
            Map<String, Object> last = msgs.get(msgs.size() - 1);
            String lastRole = String.valueOf(last.get("role"));
            String lastText = String.valueOf(last.get("content"));
            boolean hasRecall = false;
            for (Map<String, Object> t : tools) if ("recall".equals(String.valueOf(((Map<?, ?>) t.get("function")).get("name")))) hasRecall = true;
            double p = Math.max(0, Math.min(1, (L - 2500) / 9000.0));
            String tail = all.substring(Math.max(0, L - 1500));
            boolean rulesNear = tail.contains("简体中文");
            boolean personaNear = tail.contains("你是 OneSay") || L < 4000;
            boolean honest = all.contains("记不清");
            // 最新一句有没有被单独标出来、放在最后、写明只答这一句
            boolean marked = "user".equals(lastRole) && lastText.trim().endsWith(cur.text.trim()) && lastText.contains("只回答");
            boolean resolvedAll = all.contains("都已经答过");
            // 自检打回：只答最新这一句
            boolean nudge = "user".equals(lastRole) && lastText.contains("OneSay 内部提示") && lastText.contains("最新");
            if (nudge) { fin.nudged = true; marked = true; fin.why = lastText; }
            boolean ruleBad = dice(seed, turn, nudge ? "rule2" : "rule") < (rulesNear || nudge ? 0.02 : 0.04 + 0.40 * p);

            // 工具结果回来：接着自己的计划
            if ("tool".equals(lastRole)) return afterTool(msgs, all, lastText, honest, ruleBad);

            // 复读用户原话
            if (!nudge) {
                double pe = marked ? 0.02 : 0.05 + 0.45 * p;
                if (dice(seed, turn, "echo") < pe && !"heavy".equals(cur.kind))
                    return say(dice(seed, turn, "echo2") < 0.5 ? cur.text : "你是说「" + cur.text + "」对吧？", "echo", ruleBad);
                // 去答前面的问题：没标「已答完 / 已完成」、上下文里还看得见的旧问题，或者「没做完」的旧任务
                List<Q> cand = new ArrayList<>();
                for (Step s : past) {
                    if (!"q".equals(s.kind)) continue;
                    String head = s.text.substring(0, Math.min(10, s.text.length()));
                    if (!all.contains(head)) continue;
                    boolean resolved = resolvedAll;
                    for (String line : all.split("\n")) if (line.contains(head) && (line.contains("已完成") || line.contains("已答") || line.contains("已结"))) resolved = true;
                    if (!resolved) cand.add(bank(s.key));
                }
                boolean staleTask = !resolvedAll && all.contains("没做完");
                double po = marked && resolvedAll ? 0.02 : marked ? 0.10 : 0.06 + 0.40 * p;
                if (staleTask && !marked) po += 0.30;
                if (!cand.isEmpty() && dice(seed, turn, "off") < po && !"heavy".equals(cur.kind)) {
                    Q o = cand.get((int) (dice(seed, turn, "offpick") * cand.size()));
                    if (!o.key.equals(cur.key)) return say(o.answer, "off", ruleBad);
                }
            }
            switch (cur.kind) {
                case "fact": return say("好的，记下了。", "ok", ruleBad);
                case "paste": return say(PASTE_ANSWER, "ok", ruleBad);
                case "persona": return personaNear || nudge ? say("我是 OneSay，在你这台电脑上帮你做事的助手，和手机上的 OneSay 是同一个。", "ok", ruleBad)
                        : say("我是一个通用的 AI 语言模型，可以回答各种问题。", "persona_bad", ruleBad);
                case "probe": {
                    if (all.contains(cur.value)) return say("你说过的：" + cur.value + "。", "ok", ruleBad);
                    if (hasRecall) return call("r" + turn, "recall", Map.of("description", "查以前说过的话", "query", cur.text.replaceAll("[？?来着再说一遍那个我前面说的]", "")));
                    return honest ? say("这个我没记住，你再跟我说一下？", "forgot", ruleBad) : say("你说的是 " + fake(cur) + "。", "fabricated", ruleBad);
                }
                case "guide": case "guide2": {
                    boolean g = all.contains(GUIDE_DL);
                    String what = "guide".equals(cur.kind) ? "7-Zip" : "VLC";
                    if (g) return call("d" + turn, "download", Map.of("description", "下载 " + what, "url", "https://example.org/" + what + ".exe", "path", "C:\\Users\\me\\Downloads\\" + what + ".exe"));
                    fin.firstLabel = "guide_bad";
                    return call("d" + turn, "run_command", Map.of("description", "下载 " + what, "command", "Invoke-WebRequest https://example.org/" + what + ".exe -OutFile C:\\Users\\me\\Downloads\\" + what + ".exe; Write-Output done"));
                }
                case "heavy": return heavyCall();
                default: return say(bank(cur.key).answer, "ok", ruleBad);
            }
        }

        String fake(Step s) {
            switch (s.key) {
                case "err": return "E2001";
                case "trip": return "杭州";
                case "cat": return "咪咪";
                default: return "21 寸";
            }
        }

        Llm.Turn heavyCall() {
            heavyCalls++;
            return call("h" + turn + "_" + heavyCalls, "run_command", Map.of("description", "扫第 " + heavyCalls + " 批日志", "command", "Get-Content D:\\logs\\part" + heavyCalls + ".log"));
        }

        Llm.Turn afterTool(List<Map<String, Object>> msgs, String all, String lastText, boolean honest, boolean ruleBad) {
            switch (cur.kind) {
                case "probe":
                    if (lastText.contains(cur.value)) return say("查了以前的对话：" + cur.value + "。", "ok", ruleBad);
                    return honest ? say("这个我没记住，你再跟我说一下？", "forgot", ruleBad) : say("你说的是 " + fake(cur) + "。", "fabricated", ruleBad);
                case "guide": case "guide2":
                    return say("【完成】\n下好了，在下载文件夹里。", "guide_bad".equals(fin.firstLabel) ? "guide_bad" : "ok", ruleBad);
                case "heavy": {
                    if (heavyCalls == 3 && !queuedSent && chat != null) {
                        queuedSent = true;
                        chat.send(bank(queuedKey).text.replace("汽车", "顺便问一下，汽车"), "text"); // 任务进行中又问了一句
                    }
                    if (heavyCalls < 8) return heavyCall();
                    boolean seenQueued = all.contains("顺便问一下，汽车");
                    queuedAnswered = seenQueued;
                    return say("【完成】\n报错最多的是 net 模块，一共 312 条。" + (seenQueued ? "另外你问的胎压：" + bank(queuedKey).answer : ""), "ok", ruleBad);
                }
                default: return say(bank(cur.key).answer, "ok", ruleBad);
            }
        }
    }

    // ------------------------------------------------------------------ 跑

    static Actions.Result exec(String cmd, Map<String, Object> a) {
        String c = String.valueOf(a.get("command"));
        if (c.startsWith("Get-Content D:\\logs\\part")) {
            String part = c.replaceAll("\\D+", "");
            StringBuilder b = new StringBuilder();
            for (int i = 0; b.length() < 6000; i++) b.append("HEAVYLOG part").append(part).append(" line").append(i).append(" module=").append(i % 3 == 0 ? "net" : "ui").append(" ERROR timeout\n");
            return Actions.Result.ok("命令执行完了", new TreeMap<>(Map.of("output", b.toString(), "exit", 0L)));
        }
        return Actions.Result.ok("做完了", new TreeMap<>(Map.of("output", "done", "exit", 0L)));
    }

    /** 跑一段会话，返回这段的计数。 */
    static Map<String, Long> runOne(int seed, Path dir) throws Exception {
        Store s = new Store(dir.resolve("state.json"));
        s.setMemory(seededMemory(), 1);
        Config cfg = new Config(dir.resolve("c.properties"));
        Chat ch = new Chat(cfg, s, new Main.TestActions(), List::of, x -> { });
        Sim sim = new Sim(seed);
        sim.chat = ch;
        ch.brain = sim;
        ch.pcExec = LongCtxEval::exec;
        Map<String, Long> n = new LinkedHashMap<>();
        for (String k : new String[] {"turns", "scored", "cmd", "echo", "off", "echo_first", "off_first", "probe", "probe_bad", "fabricated", "persona", "persona_bad", "rule_bad", "guide", "guide_bad", "nudged", "nudge_false", "queued_ok", "queued_reask"})
            n.put(k, 0L);
        List<Step> sc = script(seed);
        for (int t = 0; t < sc.size(); t++) {
            Step st = sc.get(t);
            sim.begin(t, st);
            ch.send(st.text, "text");
            n.merge("turns", 1L, Long::sum);
            Final f = sim.fin;
            if ("none".equals(f.label)) { n.merge("cmd", 1L, Long::sum); sim.past.add(st); continue; }
            n.merge("scored", 1L, Long::sum);
            if ("echo".equals(f.label)) n.merge("echo", 1L, Long::sum);
            if ("echo".equals(f.firstLabel)) n.merge("echo_first", 1L, Long::sum);
            if ("off".equals(f.firstLabel)) n.merge("off_first", 1L, Long::sum);
            if ("off".equals(f.label)) {
                n.merge("off", 1L, Long::sum);
                if (f.text.contains(bank(sim.queuedKey).answer) && sim.queuedAnswered) n.merge("queued_reask", 1L, Long::sum);
            }
            if (f.rule) n.merge("rule_bad", 1L, Long::sum);
            if (f.nudged) {
                n.merge("nudged", 1L, Long::sum);
                if ("ok".equals(f.firstLabel)) { n.merge("nudge_false", 1L, Long::sum); if (DEBUG) System.err.println("误判 seed=" + seed + " t=" + t + " 问：" + st.text + "\n  答：" + f.firstText + "\n  " + f.why.replaceAll("\\s+", " ")); }
            }
            switch (st.kind) {
                case "probe":
                    n.merge("probe", 1L, Long::sum);
                    if (!"ok".equals(f.label)) n.merge("probe_bad", 1L, Long::sum);
                    if ("fabricated".equals(f.label)) n.merge("fabricated", 1L, Long::sum);
                    n.merge("probe_" + f.label, 1L, Long::sum);
                    break;
                case "persona":
                    n.merge("persona", 1L, Long::sum);
                    if (!"ok".equals(f.label)) n.merge("persona_bad", 1L, Long::sum);
                    break;
                case "guide": case "guide2":
                    n.merge("guide", 1L, Long::sum);
                    if ("guide_bad".equals(f.label) || "guide_bad".equals(f.firstLabel)) n.merge("guide_bad", 1L, Long::sum);
                    break;
                case "heavy":
                    if (sim.queuedAnswered) n.merge("queued_ok", 1L, Long::sum);
                    sim.past.add(new Step("q", bank(sim.queuedKey).text, sim.queuedKey, null));
                    break;
                default:
            }
            sim.past.add(st);
        }
        n.put("ctx_chars_avg", sim.calls == 0 ? 0 : sim.ctxChars / sim.calls);
        n.put("ctx_chars_peak", sim.peakCtx);
        n.put("heavy_in_main", sim.heavyInMain);
        n.put("tool_chars_peak", sim.peakToolChars);
        long sessions = 0;
        for (Map<String, Object> m : s.list("chat")) if ("session".equals(m.get("via"))) sessions++;
        n.put("relays", sessions);
        return n;
    }

    /** 跑 4 段会话，汇总。 */
    public static Map<String, Object> run() throws Exception {
        Map<String, Long> sum = new LinkedHashMap<>();
        for (int seed = 0; seed < 4; seed++) {
            Map<String, Long> one = runOne(seed, Files.createTempDirectory("lce" + seed));
            for (Map.Entry<String, Long> e : one.entrySet()) {
                if (e.getKey().endsWith("_peak")) sum.merge(e.getKey(), e.getValue(), Math::max);
                else sum.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        sum.put("ctx_chars_avg", sum.get("ctx_chars_avg") / 4);
        Map<String, Object> r = new LinkedHashMap<>(sum);
        long sc = Math.max(1, sum.get("scored"));
        r.put("复读率", pct(sum.get("echo"), sc));
        r.put("偏题率", pct(sum.get("off"), sc));
        r.put("复读率（出口自检前）", pct(sum.get("echo_first"), sc));
        r.put("偏题率（出口自检前）", pct(sum.get("off_first"), sc));
        r.put("遗忘率", pct(sum.get("probe_bad"), Math.max(1, sum.get("probe"))));
        r.put("幻觉率", pct(sum.get("fabricated"), Math.max(1, sum.get("probe"))));
        r.put("人设失效率", pct(sum.get("persona_bad"), Math.max(1, sum.get("persona"))));
        r.put("硬规则违规率", pct(sum.get("rule_bad"), sc));
        r.put("准则丢失率", pct(sum.get("guide_bad"), Math.max(1, sum.get("guide"))));
        r.put("自检误判率", pct(sum.get("nudge_false"), sc));
        return r;
    }

    static String pct(long a, long b) { return String.format(java.util.Locale.ROOT, "%.1f%%（%d/%d）", 100.0 * a / b, a, b); }

    public static void main(String[] a) throws Exception {
        Map<String, Object> r = run();
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Object> e : r.entrySet()) b.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        System.out.print(b);
        if (a.length > 0) Files.writeString(Path.of(a[0]), Json.stringify(r));
    }
}
