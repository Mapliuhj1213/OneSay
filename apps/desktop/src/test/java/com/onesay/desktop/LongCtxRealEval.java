package com.onesay.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [longctx-1123] 可选：用真模型跑同一份长会话评测（{@link LongCtxEval} 的脚本），给出复读 / 偏题 / 遗忘 / 幻觉 / 人设 / 规矩 / 准则的粗略数字。
 *
 * <p>不进自动测试，不带任何 Key。要跑的人自己在环境变量里给（只在本进程内存里用，不写文件、不进日志）：
 * <pre>
 *   ONESAY_EVAL_URL   接口地址（OpenAI 兼容的 /chat/completions 完整地址）
 *   ONESAY_EVAL_KEY   你自己的 Key
 *   ONESAY_EVAL_MODEL 模型名
 *   java -cp 编译目录 com.onesay.desktop.LongCtxRealEval [几段会话，默认 1] [结果 json 路径]
 * </pre>
 * 想看改前的数字：把这个文件和 LongCtxEval.java 拷到改前的源码里一起编译再跑（只用 Chat 的公开入口，两边都能编）。
 * 打分是关键词规则，比假模型粗：答对 = 用了标准答案里的数字 / 英文关键词或和标准答案明显相像；答偏 = 用了前面某题的关键词却没有这题的。
 */
public final class LongCtxRealEval {
    private LongCtxRealEval() {}

    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.:\\[\\]\\-~%/]*");

    static List<String> keys(String answer) {
        List<String> l = new ArrayList<>();
        Matcher m = TOKEN.matcher(answer);
        while (m.find()) if (m.group().length() >= 2 || m.group().matches("\\d")) l.add(m.group().toLowerCase(java.util.Locale.ROOT));
        return l;
    }

    static boolean onTopic(String reply, LongCtxEval.Q q) {
        String r = reply.toLowerCase(java.util.Locale.ROOT);
        for (String k : keys(q.answer)) if (r.contains(k)) return true;
        return Chat.related(reply, q.answer) >= 0.12;
    }

    static String norm(String s) { return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", ""); }

    /** 复读：答复几乎就是用户这句原话（自带一份，不依赖改后的代码，改前的源码里也能编）。 */
    static boolean echoes(String reply, String latest) {
        String r = norm(reply), l = norm(latest);
        return l.length() >= 6 && r.contains(l) && r.length() <= l.length() * 3 / 2 + 12;
    }

    public static void main(String[] a) throws Exception {
        String url = System.getenv("ONESAY_EVAL_URL"), key = System.getenv("ONESAY_EVAL_KEY"), model = System.getenv("ONESAY_EVAL_MODEL");
        if (url == null || key == null || model == null || key.isBlank()) {
            System.err.println("没给 ONESAY_EVAL_URL / ONESAY_EVAL_KEY / ONESAY_EVAL_MODEL，不跑（这个脚本只用你自己的 Key，什么都不保存）");
            System.exit(2);
        }
        Llm llm = new Llm(url, key, model);
        int sessions = a.length > 0 ? Integer.parseInt(a[0]) : 1;
        Map<String, Long> n = new LinkedHashMap<>();
        for (String k : new String[] {"scored", "echo", "off", "wrong", "probe", "probe_bad", "fabricated", "persona", "persona_bad", "rule_bad", "guide", "guide_bad", "selfcheck"}) n.put(k, 0L);
        for (int seed = 0; seed < sessions; seed++) {
            Path dir = Files.createTempDirectory("lcr" + seed);
            Store s = new Store(dir.resolve("state.json"));
            s.setMemory(LongCtxEval.seededMemory(), 1);
            Chat ch = new Chat(new Config(dir.resolve("c.properties")), s, new Main.TestActions(), List::of, x -> { if (x.contains("出口自检")) n.merge("selfcheck", 1L, Long::sum); });
            List<String> used = new ArrayList<>();
            ch.brain = (msgs, tools) -> llm.turn(msgs, tools, 0.3, 120);
            ch.pcExec = (cmd, args) -> { used.add(cmd + " " + args.getOrDefault("command", "")); return LongCtxEval.exec(cmd, args); };
            List<LongCtxEval.Step> sc = LongCtxEval.script(seed);
            List<LongCtxEval.Q> past = new ArrayList<>();
            List<String> pastQ = new ArrayList<>(), pastA = new ArrayList<>();
            for (int t = 0; t < sc.size(); t++) {
                LongCtxEval.Step st = sc.get(t);
                used.clear();
                Map<String, Object> m = ch.send(st.text, "text");
                String reply = "task".equals(m.get("via")) ? String.valueOf(m.getOrDefault("answer", "")) : String.valueOf(m.getOrDefault("text", ""));
                n.merge("scored", 1L, Long::sum);
                if (echoes(Outcomes.stripTag(reply), st.text)) n.merge("echo", 1L, Long::sum);
                if (reply.contains("**") || reply.matches("(?s)^\\s*[A-Za-z].*") || reply.contains("\n#")) n.merge("rule_bad", 1L, Long::sum);
                switch (st.kind) {
                    case "q": {
                        LongCtxEval.Q q = LongCtxEval.bank(st.key);
                        if (!onTopic(reply, q)) {
                            boolean off = false;
                            for (LongCtxEval.Q p : past) if (!p.key.equals(q.key) && onTopic(reply, p)) off = true;
                            n.merge(off ? "off" : "wrong", 1L, Long::sum);
                        }
                        past.add(q);
                        break;
                    }
                    case "probe":
                        n.merge("probe", 1L, Long::sum);
                        if (!reply.contains(st.value)) {
                            n.merge("probe_bad", 1L, Long::sum);
                            if (!reply.matches("(?s).*(没记住|不记得|记不清|没说过|不确定|查不到|没查到|没找到).*")) n.merge("fabricated", 1L, Long::sum);
                        }
                        break;
                    case "persona":
                        n.merge("persona", 1L, Long::sum);
                        if (!reply.contains("OneSay")) n.merge("persona_bad", 1L, Long::sum);
                        break;
                    case "guide": case "guide2": {
                        n.merge("guide", 1L, Long::sum);
                        boolean dl = false;
                        for (String u : used) if (u.startsWith("net_download")) dl = true;
                        if (!dl) n.merge("guide_bad", 1L, Long::sum);
                        break;
                    }
                    default:
                }
                pastQ.add(st.text);
                pastA.add(reply);
                System.err.println("第 " + (seed + 1) + " 段 第 " + (t + 1) + " 轮 好了");
            }
        }
        Map<String, Object> r = new LinkedHashMap<>(n);
        long sc = Math.max(1, n.get("scored"));
        r.put("复读率", LongCtxEval.pct(n.get("echo"), sc));
        r.put("偏题率", LongCtxEval.pct(n.get("off"), sc));
        r.put("答错（不像任何一题）", LongCtxEval.pct(n.get("wrong"), sc));
        r.put("遗忘率", LongCtxEval.pct(n.get("probe_bad"), Math.max(1, n.get("probe"))));
        r.put("幻觉率", LongCtxEval.pct(n.get("fabricated"), Math.max(1, n.get("probe"))));
        r.put("人设失效率", LongCtxEval.pct(n.get("persona_bad"), Math.max(1, n.get("persona"))));
        r.put("硬规则违规率", LongCtxEval.pct(n.get("rule_bad"), sc));
        r.put("准则丢失率", LongCtxEval.pct(n.get("guide_bad"), Math.max(1, n.get("guide"))));
        for (Map.Entry<String, Object> e : r.entrySet()) System.out.println(e.getKey() + " = " + e.getValue());
        if (a.length > 1) Files.writeString(Path.of(a[1]), Json.stringify(r));
    }
}
