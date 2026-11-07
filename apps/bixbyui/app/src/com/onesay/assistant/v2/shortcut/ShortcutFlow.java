package com.onesay.assistant.v2.shortcut;

import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [shortcut] 一次快捷指令的完整流程（纯 Java，JVM 全测）：
 *
 * <pre>
 *   逐步执行（ShortcutRunner）
 *     ├─ 全部成功 → 「已执行」
 *     ├─ 用户取消 / 没确认 / 安全策略拒绝 → 如实停下，不转正常模式、不改快捷指令
 *     └─ 某步失败（含能力不可用）→ 自动转正常模式（普通 AgentLoop）把剩下的做完
 *           ├─ 正常模式做完 → 用「已做成的步骤 + 正常模式实际调用的步骤」更新快捷指令（旧版本进历史，可撤销），结果里说明已更新
 *           │                 （正常模式用到了不能回放的工具，如点界面 / 查网页 → 不更新，如实说明）
 *           └─ 正常模式也失败 → 不更新
 * </pre>
 * 成败不由机器判定「对不对」：只报已执行；用户点「不对」由 {@link #markWrong} 记，连续两次标红。
 */
public final class ShortcutFlow {

    private ShortcutFlow() {}

    // ------------------------------------------------------------------ 正常模式端口

    /** 正常模式里实际调用过的一步。 */
    public static final class NormalStep {
        public final String tool;
        public final JSONObject args;
        public final boolean ok;

        public NormalStep(String tool, JSONObject args, boolean ok) {
            this.tool = tool;
            this.args = args;
            this.ok = ok;
        }
    }

    public static final class NormalResult {
        public boolean finished;
        public String answer = "";
        public String reason = "";
        public final List<NormalStep> steps = new ArrayList<NormalStep>();
    }

    /** 跑一遍普通 agent 流程（真机 = AgentLoop，同一个回调：确认卡、取消、岛上进度都照旧）。 */
    public interface Normal {
        NormalResult run(String prompt);
    }

    // ------------------------------------------------------------------ 结果

    public static final class Outcome {
        public long shortcutId, runId;
        public String status = ShortcutModel.SC_OK;
        /** 给用户的结果文字。 */
        public String text = "已执行";
        public boolean updated;
        public ShortcutRunner.Result run;
        public NormalResult normal;

        public boolean finished() {
            return ShortcutModel.SC_OK.equals(status) || ShortcutModel.SC_FALLBACK_OK.equals(status);
        }
    }

    public static final String DONE = "已执行";

    public static Outcome run(ShortcutModel.Shortcut sc, String trigger, ShortcutStore store, ShortcutRunner.Ports ports, Normal normal, long now) {
        Outcome o = new Outcome();
        o.shortcutId = sc.id;
        o.runId = store.beginRun(sc, trigger, now);
        ShortcutRunner.Result r = ShortcutRunner.run(sc, ports);
        o.run = r;
        String mode = "shortcut";
        if (r.ok()) {
            o.status = ShortcutModel.SC_OK;
            o.text = DONE;
        } else if (r.status == ShortcutRunner.Status.CANCELLED) {
            o.status = ShortcutModel.SC_CANCELLED;
            o.text = "已取消";
        } else if (r.status == ShortcutRunner.Status.REJECTED) {
            o.status = ShortcutModel.SC_DENIED;
            o.text = "已停下：" + r.reason;
        } else if (r.status == ShortcutRunner.Status.DENIED) {
            o.status = ShortcutModel.SC_DENIED;
            o.text = "已停下：" + r.reason;
        } else {
            mode = "normal";
            fallback(sc, r, store, ports, normal, o, now);
        }
        store.endRun(o.runId, sc.id, o.status, mode, r.summary(), o.text, r.failedIndex, o.updated, now);
        return o;
    }

    static void fallback(ShortcutModel.Shortcut sc, ShortcutRunner.Result r, ShortcutStore store, ShortcutRunner.Ports ports, Normal normal,
                         Outcome o, long now) {
        String failedLabel = r.failedIndex >= 0 && r.failedIndex < sc.steps.size()
                ? ShortcutRunner.label(ports.lookup(sc.steps.get(r.failedIndex).tool), sc.steps.get(r.failedIndex)) : "";
        NormalResult nr = normal == null ? null : normal.run(fallbackPrompt(sc, r));
        o.normal = nr;
        boolean anyOk = false;
        if (nr != null) for (NormalStep s : nr.steps) if (s.ok) anyOk = true;
        // 正常模式失败 = 没收工，或一步都没做成也没给出结论
        if (nr == null || !nr.finished || (!anyOk && (nr.answer == null || nr.answer.trim().length() == 0))) {
            o.status = ShortcutModel.SC_FALLBACK_FAIL;
            o.text = "没做完：第 " + (r.failedIndex + 1) + " 步「" + failedLabel + "」" + (r.reason.length() > 0 ? "（" + r.reason + "）" : "")
                    + "，正常模式也没做成" + (nr != null && nr.reason != null && nr.reason.length() > 0 ? "：" + nr.reason : "") + "。快捷指令没有改。";
            return;
        }
        o.status = ShortcutModel.SC_FALLBACK_OK;
        String head = nr.answer != null && nr.answer.trim().length() > 0 ? nr.answer.trim() : DONE;
        List<ShortcutModel.Step> newSteps = new ArrayList<ShortcutModel.Step>(r.doneSteps());
        String blocker = null;
        for (NormalStep s : nr.steps) {
            if (!s.ok) continue;
            String sign = sign(s.tool);
            if (skipSilently(sign)) continue;
            if (!replayable(sign, ports)) {
                blocker = sign;
                break;
            }
            ShortcutModel.Step st = new ShortcutModel.Step(sign, null);
            st.args = ShortcutSafety.sanitize(s.args, st.ask);
            newSteps.add(st);
        }
        String note;
        if (blocker != null) {
            note = "第 " + (r.failedIndex + 1) + " 步「" + failedLabel + "」没成功，已用正常模式做完；这次用到的「" + blocker + "」没法存进快捷指令，快捷指令没有改。";
        } else if (newSteps.isEmpty() || ShortcutModel.stepsJson(newSteps).equals(ShortcutModel.stepsJson(sc.steps))) {
            note = "第 " + (r.failedIndex + 1) + " 步「" + failedLabel + "」没成功，已用正常模式做完；做法和原来一样，快捷指令没有改。";
        } else {
            try {
                store.update(sc.id, ShortcutModel.replaceSteps(sc, newSteps, "第 " + (r.failedIndex + 1) + " 步失败后按正常模式更新", now));
                o.updated = true;
                note = "第 " + (r.failedIndex + 1) + " 步「" + failedLabel + "」没成功，已改用正常模式做完，并按这次的做法更新了快捷指令「" + sc.name + "」（可撤销）。";
            } catch (RuntimeException e) {
                note = "第 " + (r.failedIndex + 1) + " 步「" + failedLabel + "」没成功，已用正常模式做完；更新快捷指令没成功：" + e.getMessage();
            }
        }
        o.text = head + "\n" + note;
    }

    /** 给正常模式的指令：要做的全部步骤（带参数）、哪些已经做好、哪一步失败了。 */
    public static String fallbackPrompt(ShortcutModel.Shortcut sc, ShortcutRunner.Result r) {
        StringBuilder sb = new StringBuilder("帮我完成快捷指令「").append(sc.name).append("」，它依次要做：");
        for (int i = 0; i < sc.steps.size(); i++) {
            ShortcutModel.Step s = sc.steps.get(i);
            ShortcutRunner.StepRun run = i < r.runs.size() ? r.runs.get(i) : null;
            JSONObject args = run != null && run.args != null ? run.args : s.args;
            sb.append("\n").append(i + 1).append(". ").append(ShortcutRunner.label(null, s)).append("（").append(s.tool);
            String a = argsText(args);
            if (a.length() > 0) sb.append("，").append(a);
            sb.append("）");
            if (run != null && run.ok) sb.append(" —— 已经做好了，不要重复");
            else if (i == r.failedIndex) sb.append(" —— 这一步失败了").append(r.reason.length() > 0 ? "：" + r.reason : "").append("，换个办法完成");
        }
        sb.append("\n把没做好的做完，做完简短告诉我结果。");
        return sb.toString();
    }

    static String argsText(JSONObject args) {
        if (args == null) return "";
        StringBuilder sb = new StringBuilder();
        Iterator<?> it = args.keys();
        while (it.hasNext()) {
            String k = String.valueOf(it.next());
            if (args.isNull(k)) continue;
            sb.append(sb.length() > 0 ? "，" : "").append(k).append("=").append(args.opt(k));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 哪些步骤能存

    static final java.util.Set<String> META = new java.util.HashSet<String>(java.util.Arrays.asList(
            "task.todo", "ask_user", "end_not_done", "gui.unfold", "ask.reply", "chat.reply", "clarify"));

    static String sign(String tool) {
        if (tool == null) return "";
        String s = tool.trim();
        return s.indexOf("__") >= 0 ? CapabilityRegistry.toolNameToSign(s) : s;
    }

    /** 循环控制类 / 只读探针：不算步骤，存的时候直接略过。 */
    static boolean skipSilently(String sign) {
        return META.contains(sign) || sign.startsWith("probe.");
    }

    /** 能被快捷指令回放：注册表 / 内部动作里有、不是点界面 / 查网页 / 读屏幕这类 v1 不回放的。 */
    static boolean replayable(String sign, ShortcutRunner.Ports ports) {
        String s = sign.toLowerCase(Locale.ROOT);
        if (s.equals("phone.operate") || s.startsWith("web.") || s.startsWith("research.") || s.startsWith("screen.") || s.startsWith("vision.")) return false;
        return ports.lookup(sign) != null;
    }

    // ------------------------------------------------------------------ 对话里「存为快捷指令」

    public static final class Saved {
        public long id;
        /** 没存上的原因；存上为 null。 */
        public String error;
        public boolean existed;
    }

    /**
     * 把这次实际调用的工具与参数存成快捷指令：不弹窗、不追问。名字 = 这句话（截到 40 字），原话同时作触发词。
     * 只存成功的步骤；用到了 v1 不能回放的工具（点界面 / 查网页）就不存，返回原因。
     */
    public static Saved saveFromChat(String query, List<NormalStep> steps, ShortcutStore store, ShortcutRunner.Ports ports) {
        Saved out = new Saved();
        List<ShortcutModel.Step> keep = new ArrayList<ShortcutModel.Step>();
        if (steps != null) for (NormalStep s : steps) {
            if (!s.ok) continue;
            String sign = sign(s.tool);
            if (skipSilently(sign)) continue;
            if (!replayable(sign, ports)) {
                out.error = "这次用到了「" + sign + "」（点界面 / 查网页这类），这一版的快捷指令还存不了";
                return out;
            }
            ShortcutModel.Step st = new ShortcutModel.Step(sign, null);
            st.args = ShortcutSafety.sanitize(s.args, st.ask);
            keep.add(st);
        }
        if (keep.isEmpty()) {
            out.error = "这次没有可以存的步骤";
            return out;
        }
        if (keep.size() > ShortcutModel.MAX_STEPS) {
            out.error = "步骤太多（最多 " + ShortcutModel.MAX_STEPS + " 步）";
            return out;
        }
        String name = query == null ? "" : query.trim().replace('\n', ' ');
        if (name.length() > 40) {
            // 计划 App 的上限按 UTF-16 长度算（Validator / Shortcuts.checkName）；截在代理对中间就少截一个，不留半个表情
            int cut = Character.isHighSurrogate(name.charAt(39)) ? 39 : 40;
            name = name.substring(0, cut).trim();
        }
        if (ShortcutModel.normName(name).length() == 0) {
            out.error = "这句话没法当名字";
            return out;
        }
        ShortcutModel.Shortcut ex = store.byName(name);
        if (ex != null) {
            out.id = ex.id;
            out.existed = true;
            return out;
        }
        List<String> aliases = new ArrayList<String>();
        if (query != null && !query.trim().equals(name)) aliases.add(query.trim());
        out.id = store.insert(name, aliases, keep, "chat");
        return out;
    }

    /** 结果卡上点「不对」。返回 true = 这次之后被标红。 */
    public static boolean markWrong(long id, ShortcutStore store) {
        ShortcutModel.Shortcut s = store.get(id);
        if (s == null) return false;
        Map<String, Object> w = ShortcutModel.markWrong(s);
        store.update(id, w);
        Object f = w.get("flagged");
        return f != null && ((Number) f).intValue() != 0;
    }

    /** 撤销到上一版。返回 false = 没有可撤销的版本。 */
    public static boolean undo(long id, ShortcutStore store) {
        ShortcutModel.Shortcut s = store.get(id);
        if (s == null) return false;
        Map<String, Object> w = ShortcutModel.undo(s);
        if (w == null) return false;
        store.update(id, w);
        return true;
    }
}
