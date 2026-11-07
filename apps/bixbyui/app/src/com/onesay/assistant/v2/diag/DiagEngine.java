package com.onesay.assistant.v2.diag;

import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.ShellRunner;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [diag] 诊断与修复的纯 Java 核心（JVM 可测：读写都经 {@link ShellRunner}，现有能力的命令经 {@link CapResolver}）。
 *
 * <ul>
 *   <li>{@link #diagnose}：快照 + 症状规则 → 发现（引用当前值）+ 1~3 个修复芯片 + 一段中文说明；</li>
 *   <li>{@link #executeFix}：只执行表内修复（白名单守卫）；先读旧值、再写、再回读，生成撤销记录；</li>
 *   <li>{@link #undo}：按撤销记录写回旧值（同样过白名单守卫）。</li>
 * </ul>
 */
public final class DiagEngine {

    private DiagEngine() {}

    public static final int MAX_CHIPS = 3;
    public static final int MAX_FINDINGS = 4;

    /** 现有能力 → 已填好参数的 shell 命令（生产实现查 CapabilityRegistry + ShellCommand.fill；null = 没有该能力）。 */
    public interface CapResolver {
        String command(String sign, JSONObject args);
    }

    // =====================================================================
    // 诊断
    // =====================================================================

    public static final class Finding {
        public final String keyId;
        public final String text;
        public final List<String> fixIds;

        Finding(String keyId, String text, List<String> fixIds) {
            this.keyId = keyId;
            this.text = text;
            this.fixIds = fixIds;
        }
    }

    public static final class Chip {
        public final String fixId;
        public final String label;
        public final String before;
        public final String after;
        /** 确认卡上的一行：「开启智能亮屏：智能亮屏 关 → 开（10 分钟内可撤销）」。 */
        public final String summary;

        Chip(String fixId, String label, String before, String after, String summary) {
            this.fixId = fixId;
            this.label = label;
            this.before = before;
            this.after = after;
            this.summary = summary;
        }

        @Override public String toString() {
            return fixId + (before != null ? "(" + before + "→" + after + ")" : "");
        }
    }

    public static final class Report {
        public final String symptomId;
        public final String title;
        public final List<Finding> findings;
        public final List<Chip> chips;
        public final Map<String, String> snapshot;
        /** 读到 null 的键（这台机器上不存在 / 未读到）。 */
        public final List<String> missing;
        public final String text;
        public final boolean unknown;

        Report(String symptomId, String title, List<Finding> findings, List<Chip> chips, Map<String, String> snapshot,
               List<String> missing, String text, boolean unknown) {
            this.symptomId = symptomId;
            this.title = title;
            this.findings = findings;
            this.chips = chips;
            this.snapshot = snapshot;
            this.missing = missing;
            this.text = text;
            this.unknown = unknown;
        }

        @Override public String toString() {
            return "Report{" + symptomId + " findings=" + findings.size() + " chips=" + chips + " missing=" + missing + "}";
        }
    }

    /** 已知症状。 */
    public static Report diagnose(DiagKnowledge.Symptom s, Map<String, String> snap) {
        List<Finding> fs = evaluate(s.rules, snap);
        List<Chip> chips = chipsFor(fs, snap, s.fallbackFix);
        List<String> missing = missing(s.keyIds, snap);
        String text = compose(s.title, fs, snap, s.keyIds, missing, chips, s.normalHint, false);
        return new Report(s.id, s.title, fs, chips, snap, missing, text, false);
    }

    /**
     * 未知症状：对表里所有症状的规则求值，但只用「条件里的键全都读过」的规则（读过 = 在 keyIds 里）。
     */
    public static Report diagnoseKeys(String title, Collection<String> keyIds, Map<String, String> snap) {
        Set<String> read = new LinkedHashSet<String>(keyIds);
        List<DiagKnowledge.Rule> rs = new ArrayList<DiagKnowledge.Rule>();
        String fallback = null;
        for (DiagKnowledge.Symptom s : DiagKnowledge.SYMPTOMS) {
            boolean anyKey = false;
            for (DiagKnowledge.Rule r : s.rules) {
                boolean ok = true;
                for (DiagKnowledge.Cond c : r.conds) {
                    if (!read.contains(c.keyId)) { ok = false; break; }
                }
                if (ok) { rs.add(r); anyKey = true; }
            }
            if (anyKey && fallback == null) fallback = s.fallbackFix;
        }
        List<Finding> fs = dedupe(evaluate(rs, snap));
        List<Chip> chips = chipsFor(fs, snap, fallback);
        List<String> ids = new ArrayList<String>(read);
        List<String> missing = missing(ids, snap);
        String text = compose(title, fs, snap, ids, missing, chips, "知识表里没有完全对应的条目，以上是相关设置的当前值；如果问题和这些都无关，可能是某个 App 或硬件的原因。", true);
        return new Report("unknown", title, fs, chips, snap, missing, text, true);
    }

    static List<Finding> evaluate(List<DiagKnowledge.Rule> rules, Map<String, String> snap) {
        List<Finding> out = new ArrayList<Finding>();
        for (DiagKnowledge.Rule r : rules) {
            if (!r.fires(snap)) continue;
            String k1 = r.conds.size() > 0 ? r.conds.get(0).keyId : null;
            String k2 = r.conds.size() > 1 ? r.conds.get(1).keyId : null;
            String t = r.finding;
            if (k1 != null) t = t.replace("{v}", DiagSnapshot.human(k1, snap.get(k1)));
            if (k2 != null) t = t.replace("{v2}", DiagSnapshot.human(k2, snap.get(k2)));
            out.add(new Finding(r.primaryKey(), t, r.fixIds));
        }
        return out;
    }

    private static List<Finding> dedupe(List<Finding> in) {
        List<Finding> out = new ArrayList<Finding>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Finding f : in) if (seen.add(f.text)) out.add(f);
        return out;
    }

    static List<String> missing(Collection<String> keyIds, Map<String, String> snap) {
        List<String> m = new ArrayList<String>();
        for (String id : keyIds) {
            if (snap.containsKey(id) && snap.get(id) == null) m.add(id);
        }
        return m;
    }

    /** 发现 → 芯片（去重、只留能真正改变现状的、最多 3 个；一个都没有时给兜底芯片）。 */
    static List<Chip> chipsFor(List<Finding> fs, Map<String, String> snap, String fallbackFix) {
        List<Chip> out = new ArrayList<Chip>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Finding f : fs) {
            for (String id : f.fixIds) {
                if (out.size() >= MAX_CHIPS) break;
                if (!seen.add(id)) continue;
                Chip c = chip(DiagKnowledge.fix(id), snap);
                if (c != null) out.add(c);
            }
        }
        if (out.isEmpty() && fallbackFix != null) {
            Chip c = chip(DiagKnowledge.fix(fallbackFix), snap);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** 一个修复在当前快照下的芯片；不适用（键不存在、已经是目标值）返回 null。 */
    public static Chip chip(DiagKnowledge.Fix f, Map<String, String> snap) {
        if (f == null) return null;
        if (f.kind == DiagKnowledge.FixKind.PAGE || f.kind == DiagKnowledge.FixKind.UI_FIND) {
            return new Chip(f.id, f.label, null, null, f.label);
        }
        String cur = snap != null ? snap.get(f.keyId) : null;
        boolean known = snap != null && snap.containsKey(f.keyId);
        if (f.kind == DiagKnowledge.FixKind.SETTING) {
            if (known && cur == null) return null;                                     // 本机没有这个键：不写
            if (cur != null && DiagKnowledge.valueEquals(cur, f.target)) return null;   // 已经是目标值
        } else if (f.kind == DiagKnowledge.FixKind.CAP) {
            String ringer = snap != null ? snap.get("global/mode_ringer") : null;
            boolean ringerOff = ringer != null && !"2".equals(ringer) && f.keyId != null && f.keyId.contains("volume_ring");
            Double c = DiagKnowledge.num(cur);
            Double t = DiagKnowledge.num(f.target);
            if (!ringerOff && c != null && t != null && c >= t) return null;
        } else if (f.kind == DiagKnowledge.FixKind.DEVICEIDLE) {
            if (cur != null && !"0".equals(cur)) return null;                           // 已在白名单
            if ("0".equals(snap != null ? snap.get("probe/wechat_installed") : null) && "com.tencent.mm".equals(f.pkg)) return null;
        }
        String before = known ? DiagSnapshot.human(f.keyId, cur) : "当前值";
        String after = DiagSnapshot.human(f.keyId, f.target);
        if (f.kind == DiagKnowledge.FixKind.CAP && snap != null) {
            String ringer = snap.get("global/mode_ringer");
            if (ringer != null && !"2".equals(ringer) && f.keyId.contains("volume_ring")) {
                before = DiagSnapshot.human("global/mode_ringer", ringer);
                after = "响铃模式，铃声 " + after;
            }
        }
        return new Chip(f.id, f.label, before, after, summary(f, before, after));
    }

    static String summary(DiagKnowledge.Fix f, String before, String after) {
        DiagKnowledge.Key k = DiagKnowledge.key(f.keyId);
        StringBuilder sb = new StringBuilder(f.label);
        sb.append("：").append(k != null ? k.label : "").append(' ').append(before).append(" → ").append(after);
        if (k != null && k.verify != DiagKnowledge.Verify.AOSP && f.kind == DiagKnowledge.FixKind.SETTING) sb.append("（未在本机核实的设置项）");
        else if (f.note != null) sb.append("（").append(f.note).append("）");
        if (f.reversible) sb.append("；10 分钟内说「撤销」可改回");
        return sb.toString();
    }

    static String compose(String title, List<Finding> fs, Map<String, String> snap, List<String> keyIds, List<String> missing,
                          List<Chip> chips, String normalHint, boolean unknown) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(fs.size(), MAX_FINDINGS);
        if (n > 0) {
            sb.append(unknown ? "我读了相关设置，可能和这些有关：" : "「" + title + "」——我读了手机当前的设置，最可能的原因：");
            for (int i = 0; i < n; i++) {
                sb.append('\n').append(i + 1).append(". ").append(fs.get(i).text);
            }
        } else {
            sb.append(unknown ? "我读了相关设置，没看出明显问题：" : "「" + title + "」——相关设置看起来都正常：");
            int shown = 0;
            for (String id : keyIds) {
                String v = snap.get(id);
                DiagKnowledge.Key k = DiagKnowledge.key(id);
                if (v == null || k == null) continue;
                sb.append(shown == 0 ? "" : "，").append(k.label).append(' ').append(DiagSnapshot.human(id, v));
                if (++shown >= 5) break;
            }
            sb.append('。');
            if (normalHint != null) sb.append('\n').append(normalHint);
        }
        boolean hintedMissing = false;
        for (String id : missing) {
            DiagKnowledge.Key k = DiagKnowledge.key(id);
            if (k == null || k.verify == DiagKnowledge.Verify.AOSP) continue;
            boolean cited = false;
            for (int i = 0; i < n; i++) if (id.equals(fs.get(i).keyId)) cited = true;
            if (cited) continue;
            if (!hintedMissing) sb.append("\n（这台机器上没读到：");
            else sb.append('、');
            sb.append(k.label);
            hintedMissing = true;
        }
        if (hintedMissing) sb.append("）");
        boolean anyWrite = false;
        for (Chip c : chips) {
            DiagKnowledge.Fix f = DiagKnowledge.fix(c.fixId);
            if (f != null && f.writes()) anyWrite = true;
        }
        if (anyWrite) sb.append("\n点下面的按钮可以直接改：改之前会让你确认，改完 10 分钟内说「撤销」或「改回去」能恢复原值。");
        else if (!chips.isEmpty()) sb.append("\n可以点下面的按钮打开对应的设置页。");
        return sb.toString();
    }

    // =====================================================================
    // 执行 / 撤销
    // =====================================================================

    public static final class ExecOutcome {
        public final boolean ok;
        public final boolean verified;
        public final String message;
        public final String detail;
        public final DiagUndoStore.Record undo;

        ExecOutcome(boolean ok, boolean verified, String message, String detail, DiagUndoStore.Record undo) {
            this.ok = ok;
            this.verified = verified;
            this.message = message;
            this.detail = detail;
            this.undo = undo;
        }

        static ExecOutcome fail(String msg, String detail) {
            return new ExecOutcome(false, false, msg, detail, null);
        }

        @Override public String toString() {
            return "ok=" + ok + " verified=" + verified + " detail=" + detail + (undo != null ? " " + undo : "");
        }
    }

    private static final Pattern INT = Pattern.compile("(-?\\d+)");

    /** 执行一个表内修复（PAGE 不在这里执行，它走现有 settings.page.* 能力）。 */
    public static ExecOutcome executeFix(String fixId, ShellRunner sh, CapResolver caps, DiagUndoStore store, long now) {
        DiagKnowledge.Fix f = DiagKnowledge.fix(fixId);
        if (f == null) return ExecOutcome.fail("这个修复不在知识表里，已拒绝", "unknown fix " + fixId);
        if (sh == null) return ExecOutcome.fail("执行通道不可用", "no shell");
        switch (f.kind) {
            case SETTING: return execSetting(f, sh, caps, store, now);
            case CAP: return execCap(f, sh, caps, store, now);
            case DEVICEIDLE: return execIdle(f, sh, store, now);
            default: return ExecOutcome.fail("这个动作只是打开设置页", "page fix not executed here");
        }
    }

    private static ExecOutcome execSetting(DiagKnowledge.Fix f, ShellRunner sh, CapResolver caps, DiagUndoStore store, long now) {
        DiagKnowledge.Key k = DiagKnowledge.key(f.keyId);
        if (k == null || k.isProbe()) return ExecOutcome.fail("修复配置错误", "bad key " + f.keyId);
        String why = DiagKnowledge.checkWrite(k.ns, k.key, f.target);
        if (why != null) return ExecOutcome.fail("已拒绝：" + why, "whitelist " + why);
        ShellExecutor.ShellResponse r0 = sh.execute(getCmd(k));
        if (r0.isDown || r0.isTimeout) return ExecOutcome.fail("系统特权通道不可用，没有改动", "read prev: " + r0.output);
        String prev = settingValue(r0.output);
        if (prev == null) {
            return ExecOutcome.fail("这台机器上没有「" + k.label + "」这个设置项（" + k.id + "），没有做任何改动", "key missing " + k.id);
        }
        if (!DiagKnowledge.SAFE_VALUE.matcher(prev).matches()) {
            return ExecOutcome.fail("「" + k.label + "」当前值格式异常，为安全起见没有改动", "unsafe prev");
        }
        if (DiagKnowledge.valueEquals(prev, f.target)) {
            return new ExecOutcome(true, true, "「" + k.label + "」已经是" + DiagSnapshot.human(k.id, f.target) + "，不用改", "noop", null);
        }
        String cmd = null;
        if (f.capSign != null && caps != null) cmd = caps.command(f.capSign, args(f.capArgs));
        if (cmd == null) cmd = "settings put " + k.ns + " " + k.key + " " + f.target;
        ShellExecutor.ShellResponse r1 = sh.execute(cmd);
        if (r1.isDown || r1.isTimeout || r1.rc != 0) {
            return ExecOutcome.fail("改「" + k.label + "」失败（系统没有执行）", "write rc=" + r1.rc + " " + r1.output);
        }
        String now1 = settingValue(sh.execute(getCmd(k)).output);
        boolean verified = DiagKnowledge.valueEquals(now1, f.target);
        String before = DiagSnapshot.human(k.id, prev);
        String after = DiagSnapshot.human(k.id, verified ? f.target : now1);
        DiagUndoStore.Record rec = null;
        if (!DiagKnowledge.valueEquals(now1, prev)) {
            List<DiagUndoStore.Entry> es = new ArrayList<DiagUndoStore.Entry>();
            es.add(DiagUndoStore.Entry.setting(k.ns, k.key, prev));
            rec = store != null ? store.push(f.id, k.label, before, after, es, now) : null;
        }
        String msg;
        if (verified) {
            msg = "已改好：" + k.label + " " + before + " → " + after + "。" + (rec != null ? "10 分钟内说「撤销」或「改回去」可以恢复。" : "");
        } else if (rec == null) {
            msg = "已发送修改，但系统没有接受：「" + k.label + "」还是" + before + "。可能需要到设置页里手动改。";
        } else {
            msg = "已执行，但回读是「" + after + "」（期望" + DiagSnapshot.human(k.id, f.target) + "）。10 分钟内说「撤销」可以恢复原值" + before + "。";
        }
        return new ExecOutcome(verified || rec != null, verified, msg, k.id + " " + prev + "→" + now1, rec);
    }

    private static ExecOutcome execCap(DiagKnowledge.Fix f, ShellRunner sh, CapResolver caps, DiagUndoStore store, long now) {
        if (caps == null) return ExecOutcome.fail("执行通道不可用", "no caps");
        String getCmd = caps.command(f.prevGetSign, new JSONObject());
        String setCmd = caps.command(f.capSign, args(f.capArgs));
        if (getCmd == null || setCmd == null) return ExecOutcome.fail("找不到对应的能力（" + f.capSign + "）", "cap missing");
        String prev = firstInt(sh.execute(getCmd).output);
        ShellExecutor.ShellResponse r1 = sh.execute(setCmd);
        if (r1.isDown || r1.isTimeout || r1.rc != 0) return ExecOutcome.fail(f.label + "失败（系统没有执行）", "rc=" + r1.rc);
        String now1 = firstInt(sh.execute(getCmd).output);
        boolean verified = DiagKnowledge.valueEquals(now1, f.target);
        String before = prev != null ? prev + "/15" : "原值";
        String after = (now1 != null ? now1 : f.target) + "/15";
        DiagUndoStore.Record rec = null;
        if (prev != null && !DiagKnowledge.valueEquals(prev, now1)) {
            List<DiagUndoStore.Entry> es = new ArrayList<DiagUndoStore.Entry>();
            es.add(DiagUndoStore.Entry.cap(f.capSign, f.undoArg, prev));
            DiagKnowledge.Key k = DiagKnowledge.key(f.keyId);
            rec = store != null ? store.push(f.id, k != null ? k.label : f.label, before, after, es, now) : null;
        }
        String msg = (verified ? "已改好：" : "已执行：") + f.label.replace("调到", "") + " " + before + " → " + after + "。"
                + (rec != null ? "10 分钟内说「撤销」可以恢复。" : "");
        return new ExecOutcome(true, verified, msg, f.capSign + " " + prev + "→" + now1, rec);
    }

    private static ExecOutcome execIdle(DiagKnowledge.Fix f, ShellRunner sh, DiagUndoStore store, long now) {
        if (!DiagKnowledge.isIdlePkgAllowed(f.pkg)) return ExecOutcome.fail("已拒绝：包名不在白名单", "pkg " + f.pkg);
        String check = "dumpsys deviceidle whitelist | grep -c '," + f.pkg + ",'";
        boolean was = intOr0(sh.execute(check).output) > 0;
        if (was) return new ExecOutcome(true, true, f.label.replace("不受", "已经不受") + "，不用改", "noop", null);
        ShellExecutor.ShellResponse r1 = sh.execute("dumpsys deviceidle whitelist +" + f.pkg);
        if (r1.isDown || r1.isTimeout || r1.rc != 0) return ExecOutcome.fail(f.label + "失败（系统没有执行）", "rc=" + r1.rc);
        boolean is = intOr0(sh.execute(check).output) > 0;
        DiagUndoStore.Record rec = null;
        if (is) {
            List<DiagUndoStore.Entry> es = new ArrayList<DiagUndoStore.Entry>();
            es.add(DiagUndoStore.Entry.idle(f.pkg));
            rec = store != null ? store.push(f.id, "电池优化白名单", "不在白名单", "在白名单", es, now) : null;
        }
        String msg = is ? "已改好：" + f.label + "（电池优化白名单 不在 → 在）。10 分钟内说「撤销」可以恢复。"
                : "已发送，但回读仍不在白名单，可能需要在「电池」设置里手动加。";
        return new ExecOutcome(is, is, msg, "deviceidle +" + f.pkg + " → " + is, rec);
    }

    /** 撤销：id=null 撤销最近一条。 */
    public static ExecOutcome undo(String recordId, ShellRunner sh, CapResolver caps, DiagUndoStore store, long now) {
        if (store == null || sh == null) return ExecOutcome.fail("执行通道不可用", "no store/shell");
        DiagUndoStore.Record rec = store.take(recordId, now);
        if (rec == null) return ExecOutcome.fail("没有 10 分钟内可撤销的设置改动", "no record");
        boolean allOk = true;
        StringBuilder detail = new StringBuilder();
        for (int i = rec.entries.size() - 1; i >= 0; i--) {
            DiagUndoStore.Entry e = rec.entries.get(i);
            boolean ok;
            switch (e.kind) {
                case SETTING: {
                    String why = DiagKnowledge.checkWrite(e.ns, e.key, e.prev);
                    if (why != null) { ok = false; detail.append("refused ").append(why).append(' '); break; }
                    String cmd = e.prev == null ? "settings delete " + e.ns + " " + e.key
                            : "settings put " + e.ns + " " + e.key + " " + e.prev;
                    ShellExecutor.ShellResponse r = sh.execute(cmd);
                    String back = settingValue(sh.execute("settings get " + e.ns + " " + e.key).output);
                    ok = r.rc == 0 && !r.isDown && DiagKnowledge.valueEquals(back, e.prev);
                    detail.append(e.ns).append('/').append(e.key).append("←").append(e.prev).append(ok ? " ok " : " mismatch ");
                    break;
                }
                case CAP: {
                    if (caps == null || e.prev == null || !e.prev.matches("-?\\d{1,4}") || e.argName == null) { ok = false; break; }
                    JSONObject a = new JSONObject();
                    try {
                        a.put(e.argName, Integer.parseInt(e.prev));
                    } catch (Exception ex) {
                        ok = false;
                        break;
                    }
                    String cmd = caps.command(e.sign, a);
                    ok = cmd != null && sh.execute(cmd).rc == 0;
                    detail.append(e.sign).append('←').append(e.prev).append(ok ? " ok " : " fail ");
                    break;
                }
                case DEVICEIDLE: {
                    if (!DiagKnowledge.isIdlePkgAllowed(e.pkg)) { ok = false; break; }
                    ok = sh.execute("dumpsys deviceidle whitelist -" + e.pkg).rc == 0;
                    detail.append("deviceidle -").append(e.pkg).append(ok ? " ok " : " fail ");
                    break;
                }
                default:
                    ok = false;
            }
            allOk &= ok;
        }
        String msg = allOk ? "已撤销：" + rec.what + " 改回 " + rec.before + "。"
                : "撤销没有完全成功：「" + rec.what + "」可能需要到设置里手动改回 " + rec.before + "。";
        return new ExecOutcome(allOk, allOk, msg, detail.toString().trim(), rec);
    }

    // =====================================================================
    // 工具
    // =====================================================================

    static String getCmd(DiagKnowledge.Key k) {
        return "settings get " + k.ns + " " + k.key;
    }

    static String settingValue(String out) {
        if (out == null) return null;
        String t = out.replace("\r", "").trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        if (t.length() == 0 || "null".equals(t)) return null;
        return t;
    }

    static String firstInt(String out) {
        if (out == null) return null;
        Matcher m = INT.matcher(out);
        return m.find() ? m.group(1) : null;
    }

    static int intOr0(String out) {
        String s = firstInt(out);
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static JSONObject args(String json) {
        try {
            return json == null ? new JSONObject() : new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 快照的有序副本（测试 / 调试用）。 */
    public static Map<String, String> snap(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
