package com.onesay.assistant.v2.shortcut;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.planner.AndroidPlannerBackend;
import com.onesay.assistant.v2.planner.PlannerBackend;
import com.onesay.assistant.v2.planner.PlannerContract;
import com.onesay.assistant.v2.planner.PlannerLink;
import com.onesay.assistant.v2.router.AgentLoop;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * [shortcut] 快捷指令的宿主胶水（Android）。纯逻辑都在 {@link ShortcutFlow} / {@link ShortcutRunner}，这里只做接线：
 *
 * <table>
 *   <tr><th>接点</th><th>做什么</th></tr>
 *   <tr><td>UnifiedRouter.shouldUseAgentLoop 开头</td><td>{@link #matches}：说的是已存快捷指令的名字 / 原话 → 交给 AgentLoop 入口</td></tr>
 *   <tr><td>UnifiedRouter.runAgentLoop 开头</td><td>{@link #tryRun}：命中就按快捷指令逐步执行；用的是<b>同一个宿主回调</b>
 *       （前台 = 面板确认卡 / ask_user；定时 / 点击 = S3Runner 的无人值守回调，确认在岛上）。失败转正常模式也用它</td></tr>
 *   <tr><td>UnifiedRouter.runAgentLoop 结尾</td><td>{@link #rememberAgentRun}：记下这次普通任务实际调用的工具，结果卡「存为快捷指令」用</td></tr>
 *   <tr><td>FloatingSheetService 收工上屏后</td><td>{@link #onAgentResultShown}：结果卡芯片「存为快捷指令」/「不对」/「撤销更新」</td></tr>
 *   <tr><td>PlannerHooks 变更监听</td><td>{@link #onPlannerChanged}：认领计划 App 发起的运行（点击 / 桌面快捷方式）→ 排进任务队列；清缓存</td></tr>
 *   <tr><td>PlannerHooks 启动</td><td>{@link #pushCatalog}：把带可用性的动作清单推给计划 App（编辑页置灰用）</td></tr>
 * </table>
 * 计划 App 没装 / 太旧（结构 &lt; 3）/ 关了适配层 → 全部不生效（{@link PlannerLink#shortcutsOn}）。
 */
public final class ShortcutHooks {

    private ShortcutHooks() {}

    static final String TAG = "BixbyShortcut";
    static final long CACHE_MS = 30000L;

    private static volatile List<ShortcutModel.Shortcut> sCache;
    private static volatile long sCacheAt;

    static ShortcutStore store(Context c) {
        return new ShortcutStore(PlannerLink.get(c).backend());
    }

    static List<ShortcutModel.Shortcut> cached(Context c) {
        long now = System.currentTimeMillis();
        List<ShortcutModel.Shortcut> l = sCache;
        if (l != null && now - sCacheAt < CACHE_MS) return l;
        l = store(c).all();
        sCache = l;
        sCacheAt = now;
        return l;
    }

    public static void invalidate() {
        sCache = null;
    }

    // ------------------------------------------------------------------ 命中

    /** 这句话命中已存快捷指令？任意线程（会走一次计划 App 的 IPC，结果缓存 30 s）。出错一律 false。 */
    public static boolean matches(Context ctx, String query) {
        return hit(ctx, query) != null;
    }

    static ShortcutMatcher.Hit hit(Context ctx, String query) {
        try {
            if (ctx == null || query == null || query.trim().length() == 0) return null;
            if (!PlannerLink.get(ctx).shortcutsOn()) return null;
            return ShortcutMatcher.match(query, cached(ctx));
        } catch (Throwable t) {
            Log.w(TAG, "[SHORTCUT] 命中检查失败（按没命中处理）: " + com.onesay.assistant.v2.conn.Redact.t(t));
            return null;
        }
    }

    // ------------------------------------------------------------------ 执行

    /** 正常模式：由 UnifiedRouter 提供（不经快捷指令入口、不写 L1 技能缓存）。 */
    public interface Normal {
        AgentLoop.Result run(String prompt);
    }

    /**
     * 命中就执行并返回循环结果；没命中返回 null（调用方照常跑 AgentLoop）。在 AgentLoop 的线程上同步执行。
     */
    public static AgentLoop.Result tryRun(final Context ctx, String query, final AgentLoop.Callback cb, final Normal normal) {
        ShortcutMatcher.Hit h = hit(ctx, query);
        if (h == null) {
            // 定时 / 点击排进来的「执行快捷指令#id」找不到（删了 / 停用了 / 计划 App 连不上）：如实失败，不把这句交给模型瞎猜
            if (ShortcutModel.commandId(query) > 0) return AgentLoop.Result.external("这条快捷指令不在了（可能已删除、停用，或计划 App 连不上）",
                    new ArrayList<AgentLoop.Step>(), false, "shortcut_missing");
            return null;
        }
        final ShortcutModel.Shortcut sc = h.shortcut;
        String trigger;
        try {
            trigger = ShortcutModel.effectiveTrigger(h.byCommand, h.byCommand ? store(ctx).queued(sc.id) : null);
        } catch (RuntimeException e) {
            trigger = ShortcutModel.effectiveTrigger(h.byCommand, null);
        }
        Log.i(TAG, "[SHORTCUT] 命中 " + sc + " trigger=" + trigger);
        ShortcutRunner.Ports ports = ports(ctx, cb);
        final List<AgentLoop.Step> steps = new ArrayList<AgentLoop.Step>();
        ShortcutFlow.Outcome o;
        try {
            o = ShortcutFlow.run(sc, trigger, store(ctx), ports, new ShortcutFlow.Normal() {
                @Override public ShortcutFlow.NormalResult run(String prompt) {
                    Log.i(TAG, "[SHORTCUT] 转正常模式");
                    AgentLoop.Result r = normal.run(prompt);
                    ShortcutFlow.NormalResult nr = new ShortcutFlow.NormalResult();
                    if (r == null) return nr;
                    nr.finished = r.finished && r.outcome == com.onesay.assistant.v2.agent.Outcome.DONE;
                    nr.answer = r.answer == null ? "" : r.answer;
                    nr.reason = r.reason == null ? "" : r.reason;
                    for (AgentLoop.Step s : r.steps) {
                        nr.steps.add(new ShortcutFlow.NormalStep(s.tool, s.args, s.ok));
                        steps.add(s);
                    }
                    return nr;
                }
            }, System.currentTimeMillis());
        } catch (PlannerBackend.PlannerException e) {
            PlannerLink.get(ctx).failed(e);
            return AgentLoop.Result.external("快捷指令没法读写计划 App：" + e.getMessage(), steps, false, "planner");
        } finally {
            invalidate();
        }
        if (o.run != null) {
            List<AgentLoop.Step> mine = new ArrayList<AgentLoop.Step>();
            for (ShortcutRunner.StepRun r : o.run.runs) mine.add(AgentLoop.Step.external(r.tool(), r.args, r.ok ? r.output : r.error, r.ok));
            mine.addAll(steps);
            steps.clear();
            steps.addAll(mine);
        }
        Last l = new Last();
        l.query = query;
        l.shortcutId = sc.id;
        l.updated = o.updated;
        l.at = System.currentTimeMillis();
        sLast = l;
        Log.i(TAG, "[SHORTCUT] 结束 #" + sc.id + " status=" + o.status + (o.updated ? "（已更新，可撤销）" : ""));
        return AgentLoop.Result.external(o.text, steps, o.finished(), o.finished() ? null : o.status);
    }

    /** 把 AgentLoop 的宿主回调适配成快捷指令端口：确认卡、ask_user、取消、执行都和普通任务一样。 */
    static ShortcutRunner.Ports ports(final Context ctx, final AgentLoop.Callback cb) {
        return new ShortcutRunner.Ports() {
            int askSeq;

            @Override public Capability lookup(String sign) {
                Capability c = CapabilityRegistry.getInstance().describe(sign);
                if (c == null) c = com.onesay.assistant.v2.kernel.FeatureKernel.get().describeForAgent(ctx, sign);
                if (c == null) c = com.onesay.assistant.v2.kernel.FeatureKernel.get().describe(sign);   // 插件能力（s3.todo.add 等走 DESCRIBE，不走 AGENT_DESCRIBE）
                if (c == null) c = ShortcutActions.internalCap(sign);
                return c;
            }

            @Override public String unavailableReason(String sign, Capability cap) {
                return ShortcutActions.unavailableReason(sign, cap, com.onesay.assistant.v2.platform.DeviceCapabilityProbe.cached(ctx), ShortcutActions.PRIVILEGED_BUILD);
            }

            @Override public String ask(String question, String param) {
                if (!(cb instanceof AgentLoop.LoopEvents)) return null;   // 无人值守（定时）没人回答 → 取消
                return ((AgentLoop.LoopEvents) cb).askUser("shortcut-" + (++askSeq), question, new ArrayList<String>(), 60);
            }

            @Override public boolean confirm(Capability cap, JSONObject args, String reason, int tier, boolean tainted) {
                if (cb instanceof AgentLoop.GatedCallback) {
                    return ((AgentLoop.GatedCallback) cb).onRequireConfirm(cap, args, reason, tier, tainted) == AgentLoop.ConfirmVerdict.ALLOW;
                }
                return cb != null && cb.onRequireConfirm(cap, args, reason) == AgentLoop.ConfirmVerdict.ALLOW;
            }

            @Override public ExecutionResult execute(Capability cap, JSONObject args, ExecutionGate.ConfirmToken token, boolean tainted) {
                return ShortcutSafety.unattendedVerdict(exec(cap, args, token, tainted));
            }

            ExecutionResult exec(Capability cap, JSONObject args, ExecutionGate.ConfirmToken token, boolean tainted) {
                if (ShortcutActions.isInternal(cap.sign)) {
                    // 无人值守（定时）：内部动作不经 UnifiedExecutor，先过同一道权限闸 / 岛上确认（PermissionGuard.Preflight）
                    if (cb instanceof com.onesay.assistant.v2.jobs.PermissionGuard.Preflight) {
                        ExecutionResult no = ((com.onesay.assistant.v2.jobs.PermissionGuard.Preflight) cb).preflight(cap, args, token);
                        if (no != null) {
                            if (token != null) ExecutionGate.reject(token);
                            return no;
                        }
                    }
                    if (token != null) ExecutionGate.reject(token);   // 内部动作不经 UnifiedExecutor 消费令牌，用完即作废
                    return internal(ctx, cap, args);
                }
                ExecutionResult local = com.onesay.assistant.v2.kernel.FeatureKernel.get().executeForAgent(ctx, cap, args);
                if (local != null) {
                    if (token != null) ExecutionGate.reject(token);
                    return local;
                }
                if (cb instanceof AgentLoop.GatedCallback) return ((AgentLoop.GatedCallback) cb).execute(cap, args, token, tainted);
                return cb == null ? null : cb.execute(cap, args);
            }

            @Override public void announce(String text) {
                if (cb instanceof AgentLoop.StepEvents) ((AgentLoop.StepEvents) cb).onToolStep(text, false);
            }

            @Override public boolean cancelled() {
                return cb instanceof AgentLoop.Cancellable && ((AgentLoop.Cancellable) cb).isCancelled();
            }
        };
    }

    /** 内部动作执行（门禁已放行 / 已确认）。 */
    static ExecutionResult internal(Context ctx, Capability cap, JSONObject args) {
        try {
            if (ShortcutActions.LLM_ASK.equals(cap.sign)) {
                com.onesay.assistant.v2.router.L2LLMCompiler l2 = com.onesay.assistant.v2.router.UnifiedRouter.getInstance(ctx).getL2();
                if (!l2.hasApiKey()) return ExecutionResult.failed(ErrorCode.NOT_CONFIGURED, "还没有配置大模型密钥", "no key", cap, args);
                com.onesay.assistant.v2.router.Invocation inv = l2.ask(args.optString("prompt", ""));
                String text = inv == null || inv.args == null ? "" : inv.args.optString("text", "").trim();
                if (text.length() == 0) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "模型没有回答", "empty", cap, args);
                return ExecutionResult.success(cap, args, text, false, "llm.ask");
            }
            if (ShortcutActions.EVENT_ADD.equals(cap.sign)) {
                Map<String, Object> row = ShortcutActions.eventRow(args, System.currentTimeMillis(), TimeZone.getDefault());
                PlannerLink.get(ctx).backend().insert(PlannerContract.T_EVENTS, row);
                return ExecutionResult.success(cap, args, "已加日程：" + row.get("title"), true, "plan.event.add");
            }
            if (ShortcutActions.BRIEF_PLAY.equals(cap.sign)) {
                boolean ok = com.onesay.assistant.v2.brief.BriefRemote.sendNow(ctx, com.onesay.assistant.v2.brief.BriefControl.Cmd.REPLAY, 0);
                return ok ? ExecutionResult.success(cap, args, "已开始播报晨间简报", false, "brief")
                        : ExecutionResult.failed(ErrorCode.EXEC_FAILED, "晨间简报没响应", "brief send failed", cap, args);
            }
            if (ShortcutActions.WEATHER.equals(cap.sign)) {   // ★ feat/rain-minutely
                String why = com.onesay.assistant.v2.rain.RainService.unavailableReason(ctx);
                if (why != null) return ExecutionResult.failed(ErrorCode.NOT_CONFIGURED, "分钟级降水" + why, "rain unavailable", cap, args);
                return ExecutionResult.success(cap, args, com.onesay.assistant.v2.rain.RainHooks.sayForShortcut(ctx), false, "rain");
            }
            return ExecutionResult.failed(ErrorCode.NOT_CONFIGURED, "这一步这一版还做不了", "not built", cap, args);
        } catch (IllegalArgumentException e) {
            return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, e.getMessage(), "bad args", cap, args);
        } catch (RuntimeException e) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "执行出错", com.onesay.assistant.v2.conn.Redact.t(e), cap, args);
        }
    }

    // ------------------------------------------------------------------ 结果卡芯片

    static final class Last {
        String query;
        long shortcutId;
        boolean updated;
        long at;
        List<ShortcutFlow.NormalStep> steps;
        boolean finished;
    }

    private static volatile Last sLast;
    private static volatile Last sLastAgent;

    /** 普通 AgentLoop 收工后记下实际调用的步骤（UnifiedRouter.runAgentLoop 结尾调；快捷指令执行不经这里）。 */
    public static void rememberAgentRun(String query, AgentLoop.Result r) {
        if (r == null || query == null) return;
        Last l = new Last();
        l.query = query;
        l.finished = r.finished && r.outcome == com.onesay.assistant.v2.agent.Outcome.DONE;
        l.steps = new ArrayList<ShortcutFlow.NormalStep>();
        for (AgentLoop.Step s : r.steps) l.steps.add(new ShortcutFlow.NormalStep(s.tool, s.args, s.ok));
        l.at = System.currentTimeMillis();
        sLastAgent = l;
    }

    /** 主线程：面板显示了 AgentLoop 的结果之后。快捷指令结果 → 「不对」（+「撤销更新」）；普通任务做成了 → 「存为快捷指令」。 */
    public static void onAgentResultShown(final com.onesay.assistant.v2.ui.FloatingSheetService svc, final String command) {
        try {
            if (svc == null || command == null) return;
            final Last sc = sLast;
            final Last ag = sLastAgent;
            long now = System.currentTimeMillis();
            final List<String> labels = new ArrayList<String>();
            final boolean isShortcut = sc != null && command.equals(sc.query) && now - sc.at < 120000L;
            if (isShortcut) {
                labels.add("不对");
                if (sc.updated) labels.add("撤销更新");
            } else if (ag != null && command.equals(ag.query) && ag.finished && now - ag.at < 120000L && hasOkStep(ag)) {
                labels.add("存为快捷指令");
            } else {
                return;
            }
            final com.onesay.assistant.v2.ui.ActionChipsView v = chips(svc);
            if (v == null) return;
            v.setListener(new com.onesay.assistant.v2.ui.ActionChipsView.Listener() {
                @Override public void onChipClicked(int index) {
                    if (index < 0 || index >= labels.size()) return;
                    final String what = labels.get(index);
                    v.hideChips();
                    final Context app = svc.getApplicationContext();
                    com.onesay.assistant.v2.util.Threads.bg("shortcut-chip", new Runnable() {
                        @Override public void run() {
                            String msg = chip(app, what, sc, ag, command);
                            toast(app, msg);
                        }
                    });
                }
            });
            v.showChips(labels);
        } catch (Throwable t) {
            Log.w(TAG, "[SHORTCUT] 结果卡芯片异常: " + com.onesay.assistant.v2.conn.Redact.t(t));
        }
    }

    static boolean hasOkStep(Last ag) {
        if (ag.steps == null) return false;
        for (ShortcutFlow.NormalStep s : ag.steps) if (s.ok && !ShortcutFlow.skipSilently(ShortcutFlow.sign(s.tool))) return true;
        return false;
    }

    /** 后台线程：芯片动作。返回给用户的一句话。 */
    static String chip(Context app, String what, Last sc, Last ag, String command) {
        try {
            if (!PlannerLink.get(app).shortcutsOn()) return "快捷指令要先装好新版计划 App";
            ShortcutStore st = store(app);
            if ("不对".equals(what)) {
                boolean red = ShortcutFlow.markWrong(sc.shortcutId, st);
                return red ? "记下了。连续两次不对，已在计划里标红，记得改一下步骤" : "记下了";
            }
            if ("撤销更新".equals(what)) {
                return ShortcutFlow.undo(sc.shortcutId, st) ? "已撤销，快捷指令回到上一版" : "没有可撤销的版本";
            }
            ShortcutFlow.Saved sv = ShortcutFlow.saveFromChat(command, ag.steps, st, ports(app, null));
            if (sv.error != null) return "没存：" + sv.error;
            return sv.existed ? "已经存过这条快捷指令了" : "已存为快捷指令，下次说同样的话就直接执行";
        } catch (RuntimeException e) {
            return "没弄好：" + e.getMessage();
        } finally {
            invalidate();
        }
    }

    static void toast(final Context app, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    static com.onesay.assistant.v2.ui.ActionChipsView chips(com.onesay.assistant.v2.ui.FloatingSheetService svc) {
        com.onesay.assistant.v2.ui.FloatingSheetView sheet = svc.getSheetView();
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(com.onesay.assistant.v2.ui.ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof com.onesay.assistant.v2.ui.ActionChipsView) return (com.onesay.assistant.v2.ui.ActionChipsView) existing;
        com.onesay.assistant.v2.ui.ActionChipsView v = new com.onesay.assistant.v2.ui.ActionChipsView(svc);
        int idx = Math.min(2, sheet.getChildCount());   // 与诊断 / 识屏芯片同一位置
        sheet.addView(v, idx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    // ------------------------------------------------------------------ 计划 App 变化 / 启动

    /** 每条快捷指令上次算出的权限（计划 App 一时连不上时沿用，免得授权指纹抖动、平白要求重新授权）。 */
    static final java.util.Map<Long, java.util.Set<com.onesay.assistant.v2.jobs.Perm>> sPermsSeen =
            new java.util.concurrent.ConcurrentHashMap<Long, java.util.Set<com.onesay.assistant.v2.jobs.Perm>>();

    /**
     * 服务启动时（PlannerHooks.onServiceCreate，任何定时任务跑之前）：让定时任务的权限卡 / 授权指纹认得
     * 「执行快捷指令#id」—— 权限清单按这条快捷指令的步骤算。效果：计划 App 里建的定时快捷指令到点先要重新授权一次，
     * 授权覆盖它的步骤；之后步骤改了（清单变）再授权一次。发消息 / 花钱 / 删除类照旧每次在岛上确认。
     */
    public static void installJobPerms(Context ctx) {
        final Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        com.onesay.assistant.v2.jobs.JobsEngine.setCommandPerms(new com.onesay.assistant.v2.jobs.JobsEngine.CommandPerms() {
            @Override public java.util.Set<com.onesay.assistant.v2.jobs.Perm> permsFor(String command) {
                long id = ShortcutModel.commandId(command);
                if (id <= 0) return null;
                try {
                    if (!PlannerLink.get(app).shortcutsOn()) return sPermsSeen.get(id);
                    for (ShortcutModel.Shortcut sc : cached(app)) {
                        if (sc.id != id) continue;
                        final ShortcutRunner.Ports p = ports(app, null);
                        java.util.Set<com.onesay.assistant.v2.jobs.Perm> s = ShortcutSafety.perms(sc, new ShortcutActions.Lookup() {
                            @Override public Capability lookup(String sign) {
                                return p.lookup(sign);
                            }
                        });
                        sPermsSeen.put(id, s);
                        return s;
                    }
                    return sPermsSeen.get(id);
                } catch (Throwable t) {
                    return sPermsSeen.get(id);
                }
            }
        });
    }

    /** 计划 App 有变化（ContentObserver，planner-obs 线程）：清缓存；认领点击 / 桌面发起的运行，排进任务队列（一次一个，确认在岛上）。 */
    public static void onPlannerChanged(Context app) {
        invalidate();
        try {
            if (!PlannerLink.get(app).shortcutsOn()) return;
            ShortcutStore st = store(app);
            long now = System.currentTimeMillis();
            boolean any = false;
            for (Map<String, Object> r : st.takeRequests(now)) {
                ShortcutModel.Shortcut sc = st.get(ShortcutModel.l(r, "shortcut_id"));
                if (sc == null || !sc.enabled) continue;
                com.onesay.assistant.v2.jobs.JobStore.Run run = com.onesay.assistant.v2.jobs.JobsHooks.queue(app).enqueue(ShortcutModel.runCommand(sc.id, sc.name), now);
                Log.i(TAG, "[SHORTCUT] 计划 App 发起运行 #" + sc.id + " → " + (run == null ? "队列满了，没排上" : "已排队"));
                any |= run != null;
            }
            if (any) com.onesay.assistant.v2.jobs.JobsHooks.pumpQueue(app);
        } catch (Throwable t) {
            Log.w(TAG, "[SHORTCUT] 认领运行失败: " + com.onesay.assistant.v2.conn.Redact.t(t));
        }
    }

    /** 启动时把带可用性的动作清单推给计划 App（后台线程）。 */
    public static void pushCatalog(final Context app) {
        try {
            if (!PlannerLink.get(app).shortcutsOn()) return;
            PlannerBackend b = PlannerLink.get(app).backend();
            if (!(b instanceof AndroidPlannerBackend)) return;
            String json = ShortcutActions.catalogJson(new ShortcutActions.Lookup() {
                @Override public Capability lookup(String sign) {
                    return ports(app, null).lookup(sign);
                }
            }, com.onesay.assistant.v2.platform.DeviceCapabilityProbe.cached(app), ShortcutActions.PRIVILEGED_BUILD);
            ((AndroidPlannerBackend) b).pushShortcutActions(json);
            Log.i(TAG, "[SHORTCUT] 动作清单已推给计划 App（上机版专属动作：" + (ShortcutActions.PRIVILEGED_BUILD ? "构建已开" : "开源构建已关") + "）");
        } catch (Throwable t) {
            Log.w(TAG, "[SHORTCUT] 推动作清单失败: " + com.onesay.assistant.v2.conn.Redact.t(t));
        }
    }
}
