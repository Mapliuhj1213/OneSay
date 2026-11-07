package com.onesay.assistant.v2.mikaplug;

import android.content.Context;

import com.onesay.assistant.plug.api.ModelPort;
import com.onesay.assistant.plug.evidence.core.EvidenceLedger;
import com.onesay.assistant.plug.guard.core.OperationGuards;
import com.onesay.assistant.plug.guard.core.StuckAdvice;
import com.onesay.assistant.plug.inbox.core.RunInbox;
import com.onesay.assistant.plug.outcome.core.OutcomeJudge;
import com.onesay.assistant.plug.outcome.core.StopSummary;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ mika-plug：AgentLoop 侧的主循环插件适配（mika.loopguard / evidence / inbox / stopsummary / outcome / appkb）。
 * AgentLoop 只在 6 处调这里；{@link #begin} 在相关插件全关时返回 null，AgentLoop 的每个钩子都先判 null，行为与改前一致。
 *
 * <ul>
 *   <li>loopguard：工具连续失败 2/4 提醒、8 叫停；页面没变时反复读 5/8 提醒、12 叫停（OperationGuards）；叫停 = StopRules 说法收尾；</li>
 *   <li>evidence：读到的材料进账（脱敏 / 截短 / 去回显），收尾时按账本把对得上回答的来源排前；</li>
 *   <li>inbox：任务跑着时用户再说一句 = 追加给当前任务（RunInbox），模型调用前取出；要收尾时还有插话就不收；</li>
 *   <li>stopsummary：用户中途停止时按记录拼几行交代当回答；</li>
 *   <li>outcome：回答交出后后台判一次结局（默认关，多一次模型调用），只记日志 / 诊断；</li>
 *   <li>appkb：开局按相关度注入 ≤ 3 份 App 知识（AppKb）。</li>
 * </ul>
 */
public final class LoopPlugs {
    private LoopPlugs() {
    }

    private static volatile Run sActive;

    /** 一个 run 的插件状态。 */
    public static final class Run {
        final OperationGuards guards;
        final StuckAdvice advice = new StuckAdvice();
        final EvidenceLedger evidence;
        final RunInbox inbox;
        final boolean stopSummary, outcome;
        final com.onesay.assistant.plug.appkb.core.KnowledgeIndex.Injected kb;
        final List<StopSummary.Activity> acts = new ArrayList<StopSummary.Activity>();
        String stopReason;
        int step;
        String lastRead;
        final ModelPort model;
        public int steersTaken, guardWarns;

        Run(boolean guard, boolean ev, boolean inbox, boolean stop, boolean outcome, com.onesay.assistant.plug.appkb.core.KnowledgeIndex.Injected kb, ModelPort model) {
            this.guards = guard ? new OperationGuards(null) : null;
            this.evidence = ev ? new EvidenceLedger(0) : null;
            this.inbox = inbox ? new RunInbox() : null;
            this.stopSummary = stop;
            this.outcome = outcome;
            this.kb = kb;
            this.model = model;
        }

        /** 开局：App 知识块追加到用户消息后面（appkb）。 */
        public String withKnowledge(String userContent) {
            if (kb == null || kb.block.length() == 0) return userContent;
            return userContent + "\n\n" + kb.block;
        }

        /** 深链 / 打开 App 是否放行（appkb：只放行开局注入过知识的 App 的深链）；没开 appkb = 放行。 */
        public boolean deepLinkAllowed(String pkgOrUri) {
            return kb == null || kb.allowsDeepLink(pkgOrUri);
        }

        /** 模型调用之前：把插话追加成一条用户消息（inbox）。 */
        public void beforeModel(JSONArray messages) {
            if (inbox == null || !inbox.hasSteers()) return;
            appendSteers(messages);
        }

        private void appendSteers(JSONArray messages) {
            List<RunInbox.Steer> s = inbox.takeSteers();
            if (s.isEmpty()) return;
            steersTaken += s.size();
            try {
                JSONObject u = new JSONObject();
                u.put("role", "user");
                u.put("content", RunInbox.render(s));
                messages.put(u);
            } catch (Exception ignored) {
            }
            if (evidence != null) for (RunInbox.Steer x : s) evidence.add(EvidenceLedger.Kind.USER_SAID, x.text, "user", null, step);
        }

        /** 模型想收尾时：还有插话就不收（把这次回复和插话补进对话，返回 true = 继续循环）。 */
        public boolean holdFinish(JSONArray messages, String lastContent) {
            if (inbox == null || inbox.closeUnlessSteered()) return false;
            try {
                JSONObject a = new JSONObject();
                a.put("role", "assistant");
                a.put("content", lastContent == null ? "" : lastContent);
                messages.put(a);
            } catch (Exception ignored) {
            }
            appendSteers(messages);
            return true;
        }

        /** 每个工具结果：进账、过守卫、记活动；返回给模型的观察值（守卫提醒 / 卡住建议并进 JSON）。 */
        public String afterTool(String name, JSONObject args, String observation, boolean ok) {
            step++;
            boolean read = isRead(name);
            boolean outward = isOutward(name);
            if (evidence != null && observation != null && ok) {
                EvidenceLedger.Kind k = read ? EvidenceLedger.Kind.READ : EvidenceLedger.Kind.FACT;
                String place = name;
                if (args != null) {
                    String u = args.optString("url", args.optString("pkg", args.optString("app", "")));
                    if (u.length() > 0) place = u;
                }
                evidence.add(k, observation, place, null, step);
                if (read) lastRead = observation;
                if (name != null && name.contains("type") && args != null) evidence.noteTyped(args.optString("text", ""));
            }
            acts.add(new StopSummary.Activity(step, label(name), args == null ? null : nz(args.optString("app", args.optString("pkg", ""))), read, outward, !ok, null));
            if (guards == null) return observation;
            List<OperationGuards.Verdict> vs = new ArrayList<OperationGuards.Verdict>();
            boolean recoverable = observation == null || !(observation.contains("被安全策略拦截") || observation.contains("未注册的工具"));
            vs.addAll(guards.result(name, ok, recoverable, ok ? null : clip(observation, 80)));
            if (read && ok) {
                long fp = observation == null ? 0 : observation.hashCode();
                if (freshFp(fp)) guards.read(name, true, fp);
                else vs.addAll(guards.read(name, false, null));
            } else if (!read && ok) {
                guards.pageChanged();
            }
            String adv = advice.next(vs, null, name, false);
            if (guards.stop() != null) stopReason = StuckAdvice.stopReason(guards.stop());
            if (vs.isEmpty() && adv == null) return observation;
            StringBuilder nudge = new StringBuilder();
            for (OperationGuards.Verdict v : vs) if (v.nudge != null) {
                guardWarns++;
                nudge.append(v.nudge).append(' ');
            }
            if (adv != null) nudge.append(adv);
            if (nudge.length() == 0) return observation;
            try {
                JSONObject o = new JSONObject(observation);
                o.put("guard", nudge.toString().trim());
                return o.toString();
            } catch (Exception e) {
                return observation + "\n[guard] " + nudge.toString().trim();
            }
        }

        private final java.util.Set<Long> seenReads = new java.util.HashSet<Long>();

        private boolean freshFp(long fp) {
            return seenReads.add(fp);
        }

        /** 守卫叫停的说法（StopRules）；null = 接着做。 */
        public String stopReason() {
            return stopReason;
        }

        /** 收尾：关收件箱（剩下的插话当下一个任务交给宿主）；取消时给交代；后台判结局。返回取消时的交代（没有为 null）。 */
        public String end(final String query, boolean cancelled, final String answer) {
            List<RunInbox.Steer> left = inbox == null ? null : inbox.close();
            if (left != null && !left.isEmpty()) LoopPlugs.handoff(left);
            String summary = null;
            if (cancelled && stopSummary && StopSummary.worthSaying(acts)) summary = StopSummary.plain(query, acts);
            if (outcome && !cancelled && answer != null && model != null) {
                final ModelPort m = model;
                com.onesay.assistant.v2.util.Threads.start("mika-outcome", new Runnable() {
                    @Override public void run() {
                        OutcomeJudge.Judged j = OutcomeJudge.judge(m, query, answer, 30000);
                        sLastOutcome = j.outcome.wire + (j.reason.length() > 0 ? "：" + j.reason : "");
                        android.util.Log.i("AgentLoop", "[MIKA-PLUG] 结局判定 " + sLastOutcome);
                    }
                });
            }
            return summary;
        }

        /** 按账本把对得上回答的来源排到前面（evidence）。 */
        public List<String> rankSources(List<String> sources, String answer) {
            if (evidence == null || sources == null || sources.size() < 2) return sources;
            List<String> sup = new ArrayList<String>(), rest = new ArrayList<String>();
            List<EvidenceLedger.Entry> s = evidence.supporting(answer);
            for (String u : sources) {
                boolean hit = false;
                for (EvidenceLedger.Entry e : s) if (e.place != null && (e.place.equals(u) || u.contains(e.place) || e.text.contains(u))) hit = true;
                (hit ? sup : rest).add(u);
            }
            sup.addAll(rest);
            return sup;
        }
    }

    public static volatile String sLastOutcome;
    private static volatile List<RunInbox.Steer> sHandoff;

    static void handoff(List<RunInbox.Steer> left) {
        sHandoff = left;
    }

    /** 宿主取走「收尾时还没交给模型的插话」（当下一个任务）。 */
    public static List<RunInbox.Steer> takeHandoff() {
        List<RunInbox.Steer> l = sHandoff;
        sHandoff = null;
        return l;
    }

    /** 开一个 run；相关插件全关返回 null（AgentLoop 原行为）。 */
    public static Run begin(Context ctx, String query, boolean unattended, ModelPort model) {
        MikaPlugs.attach(ctx);
        boolean g = MikaPlugs.on(MikaPlugs.LOOPGUARD), ev = MikaPlugs.on(MikaPlugs.EVIDENCE), in = !unattended && MikaPlugs.on(MikaPlugs.INBOX),
                st = MikaPlugs.on(MikaPlugs.STOPSUMMARY), oc = MikaPlugs.on(MikaPlugs.OUTCOME), kb = MikaPlugs.on(MikaPlugs.APPKB);
        if (!(g || ev || in || st || oc || kb)) return null;
        com.onesay.assistant.plug.appkb.core.KnowledgeIndex.Injected inj = kb ? AppKb.get(ctx).inject(query, 3) : null;
        Run r = new Run(g, ev, in, st, oc, inj, oc ? (model != null ? model : HostModel.forContext(ctx)) : null);
        if (in) sActive = r;
        return r;
    }

    public static void finished(Run r) {
        if (r != null && sActive == r) sActive = null;
    }

    /** 插话入口：有正在跑的 run 且开了 inbox → 追加给它。返回 null = 没接（宿主按新任务处理）。 */
    public static RunInbox.Receipt steer(String id, String text) {
        Run r = sActive;
        if (r == null || r.inbox == null) return null;
        RunInbox.Receipt rc = r.inbox.steer(id, text);
        return rc == RunInbox.Receipt.ACCEPTED ? rc : null;
    }

    public static boolean steering() {
        Run r = sActive;
        return r != null && r.inbox != null && !r.inbox.closed();
    }

    static boolean isRead(String name) {
        if (name == null) return false;
        return name.startsWith("web.") || name.startsWith("screen.") || name.startsWith("probe.") || name.contains(".read") || name.contains(".get")
                || name.contains(".list") || name.contains(".query") || name.contains("search") || name.equals("memory.search") || name.equals("research.spawn");
    }

    static boolean isOutward(String name) {
        if (name == null) return false;
        return name.startsWith("sms.") || name.startsWith("mail.send") || name.contains(".send") || name.startsWith("phone.call");
    }

    static String label(String name) {
        if (name == null) return "工具";
        if (name.startsWith("web.search")) return "搜索";
        if (name.startsWith("web.read")) return "读网页";
        if (name.equals("phone.operate")) return "操作 App";
        if (name.startsWith("app.open") || name.contains("launch")) return "打开应用";
        if (name.contains(".send")) return "发送";
        return name;
    }

    static String nz(String s) {
        return s == null || s.length() == 0 ? null : s;
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }
}
