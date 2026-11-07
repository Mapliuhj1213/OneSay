package com.onesay.assistant.v2.executor;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.backend.BackendOutcome;
import com.onesay.assistant.v2.executor.backend.ExecRequest;
import com.onesay.assistant.v2.executor.backend.ExecutorBackend;
import com.onesay.assistant.v2.executor.backend.IntentBackend;
import com.onesay.assistant.v2.executor.backend.MailBackend;
import com.onesay.assistant.v2.executor.backend.AlarmBackend;
import com.onesay.assistant.v2.executor.backend.ProbeBackend;
import com.onesay.assistant.v2.executor.backend.ShellBackend;
import com.onesay.assistant.v2.executor.backend.VisionBackend;
import com.onesay.assistant.v2.util.TextUtil;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 统一执行网关（特权收敛核心，全系统执行唯一入口）。
 *
 * 铁律兑现：
 * 1. 业务层完全隔离 root 细节与命令拼接；
 * 2. 严格执行 Schema 校验、高危确认门禁；
 * 3. 必须回读验证物理生效（check / expect）；
 * 4. 彻底杜绝旁路绕过回读与状态假阳性。
 *
 * <p>* <ul>
 *   <li>执行方式拆成 {@link ExecutorBackend}：probe / alarmbridge / mail / vision / intent(app.launch) / shell，
 *       本类只剩"门禁 → 校验 → 解析 → 填充 → 选后端 → 回读 → 回执"；</li>
 *   <li>门禁统一走 {@link ExecutionGate}，新增令牌入口 {@link #execute(Capability, JSONObject, ExecutionGate.ConfirmToken, boolean)}；</li>
 *   <li>回读三态：默认 UNVERIFIED，只有回读命中期望才 VERIFIED（P0-4/E1）；</li>
 *   <li>模板填充改用 {@link ShellCommand}（P0-8）；</li>
 *   <li>未知的 custom 能力不再返回占位"Custom delegated"假成功，而是 UNKNOWN_CAPABILITY（目前无此类能力，仅防御）。</li>
 * </ul>
 */
public class UnifiedExecutor implements IExecutor {

    private static final String TAG = "UnifiedExecutor";
    private static volatile UnifiedExecutor sInstance;

    private final Context mContext;
    /** 生产实现；单测构造时为 null。 */
    private final ShellExecutor mShellExecutor;
    private final IntentExecutor mIntentExecutor;
    private final ShellRunner mShell;
    private final AppResolver mApps;
    private final List<ExecutorBackend> mBackends;

    public static UnifiedExecutor getInstance(Context context) {
        if (sInstance == null) {
            synchronized (UnifiedExecutor.class) {
                if (sInstance == null) {
                    sInstance = new UnifiedExecutor(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private UnifiedExecutor(Context context) {
        this.mContext = context;
        this.mShellExecutor = new ShellExecutor(context);
        this.mIntentExecutor = new IntentExecutor(context);
        this.mShell = mShellExecutor;
        this.mApps = mIntentExecutor;
        this.mBackends = defaultBackends();
    }

    /** 单测构造：注入假 shell / 应用解析 / 后端列表（backends 为 null 时用默认列表）。 */
    UnifiedExecutor(Context context, ShellRunner shell, AppResolver apps, List<ExecutorBackend> backends) {
        this.mContext = context;
        this.mShellExecutor = null;
        this.mIntentExecutor = null;
        this.mShell = shell;
        this.mApps = apps;
        this.mBackends = backends != null ? backends : defaultBackends();
    }

    /** 后端注册顺序即匹配优先级；ShellBackend 兜底，必须最后。 */
    static List<ExecutorBackend> defaultBackends() {
        List<ExecutorBackend> l = new ArrayList<ExecutorBackend>();
        l.add(new ProbeBackend());
        l.add(new AlarmBackend());
        l.add(new VisionBackend());
        l.add(new com.onesay.assistant.v2.executor.backend.SysActionBackend());   // ★ fix/triage-fast：清空后台 / 勿扰（custom）
        l.add(new MailBackend());
        // [kernel] 插件的执行后端（BACKENDS 顺序）：[s2] 记忆库/习惯/记账 → [s3] 定时任务/待办 → [diag] 设置诊断的修复/撤销（白名单键）
        l.addAll(com.onesay.assistant.v2.kernel.FeatureKernel.get().executorBackends());
        l.add(new IntentBackend());
        l.add(new ShellBackend());
        return Collections.unmodifiableList(l);
    }

    public ShellExecutor getShellExecutor() {
        return mShellExecutor;
    }

    public IntentExecutor getIntentExecutor() {
        return mIntentExecutor;
    }

    // ------------------------------------------------------------------ 入口

    /** 无确认、可信来源的执行（需确认的能力会返回 RISK_GATED）。 */
    @Override
    public ExecutionResult execute(Capability capability, JSONObject args) {
        ExecutionResult r = run(capability, args, false, false, null, false);
        logObservation(capability, args, r);
        return r;
    }

    /**
     * ★ 推荐入口（P1-7）：带确认令牌执行。
     *
     * @param token   {@link ExecutionGate#evaluate} 签发、用户确认后经 {@link ExecutionGate#approve} 批准的令牌；
     *                不需要确认的能力传 null
     * @param tainted 参数是否来自不可信内容（网页 / 邮件 / 屏幕）
     */
    public ExecutionResult execute(Capability capability, JSONObject args,
                                   ExecutionGate.ConfirmToken token, boolean tainted) {
        ExecutionResult r = run(capability, args, true, false, token, tainted);
        logObservation(capability, args, r);
        return r;
    }

    /**
     * 带用户确认标记和污染标记的执行入口（旧接口，保留兼容）。
     *
     * @param isConfirmed 用户是否已在前端确认卡中明确授权
     * @param isTainted   参数是否被不可信来源污染
     * @deprecated 2026-10-07：布尔 isConfirmed 无法与具体 (能力, 参数) 绑定，改用
     *     {@link #execute(Capability, JSONObject, ExecutionGate.ConfirmToken, boolean)}。
     *     严格模式默认开启（r2/fixes 11），isConfirmed=true 不能绕过确认；只有显式关掉严格模式才放行（打 [GATE-LEGACY] 日志），
     *     见 {@link ExecutionGate#setStrictConfirm(boolean)}。
     */
    @Deprecated
    public ExecutionResult executeInternal(Capability capability, JSONObject args, boolean isConfirmed, boolean isTainted) {
        return run(capability, args, false, isConfirmed, null, isTainted);
    }

    // ------------------------------------------------------------------ 流水线

    private ExecutionResult run(Capability capability, JSONObject args, boolean tokenMode,
                                boolean legacyConfirmed, ExecutionGate.ConfirmToken token, boolean tainted) {
        if (capability == null) {
            return ExecutionResult.failed(ErrorCode.UNKNOWN_CAPABILITY, "未指定可执行能力契约", null, null, args);
        }
        if (args == null) {
            args = new JSONObject();
        }

        // 1. 门禁（唯一实现：ExecutionGate = ToolGate + ConsentPolicy「始终允许」）
        ExecutionResult blocked = tokenMode
                ? ExecutionGate.authorize(capability, args, tainted, token)
                : ExecutionGate.authorizeLegacy(capability, args, tainted, legacyConfirmed);
        if (blocked != null) {
            Log.w(TAG, "门禁拦截: " + capability.sign + " code=" + blocked.code + " (" + blocked.detail + ")");
            return blocked;
        }

        // 2. 参数 Schema 校验与默认值补全
        StringBuilder schemaErr = new StringBuilder();
        if (!capability.paramsSchema.validateAndApplyDefaults(args, schemaErr)) {
            Log.w(TAG, "参数 Schema 校验失败: " + schemaErr.toString());
            return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "参数不符合规范：" + schemaErr.toString(), schemaErr.toString(), capability, args);
        }

        // 3. 应用类能力组件解析与检查（针对 app.launch / app.uninstall）
        String component = null;
        String pkg = null;
        if (args.has("app")) {
            String appName = args.optString("app");
            // [roles] 角色层下发的 app.launch 带包名 → 按包名解析，不再按标签模糊匹配（「美团」不会落到美团外卖）；
            //   角色层下发（带 role）且包没装 → 直接 NOT_FOUND_APP（面板据 args.pkg 出「未安装 X，点此安装」），不回退标签；
            //   其它来源（如模型自己填了 pkg）包名解析不到时仍按原标签匹配，行为不变。
            String hintPkg = args.optString("pkg", "");
            if (hintPkg.length() > 0 && hintPkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") && mApps != null) {
                component = mApps.resolveComponent(hintPkg);
                if (component != null && !component.startsWith(hintPkg + "/")) component = null;
            }
            if (component == null && !(args.has("role") && hintPkg.length() > 0)) {
                component = mApps != null ? mApps.resolveComponent(appName) : null;
            }
            if (component == null) {
                // ★ 11-04 I：观察值带可执行提示（模型不再「失明」收工）；面板会据 app 名反查包名出安装卡
                // ★ audit-J 2：路由层「点名 App 未安装」拦截带了大白话 → 面板直接说它（安装卡由 onExecFailed 出）
                String missReply = args.optString(com.onesay.assistant.v2.router.MissingAppIntercept.ARG_REPLY, "");
                return ExecutionResult.failed(ErrorCode.NOT_FOUND_APP, missReply.length() > 0 ? missReply : "抱歉，手机上没有找到应用「" + appName + "」", "无法解析规范组件",
                        "「" + appName + "」多半没装：别再换名字重试，也别叫用户去确认；直接告诉用户没装、可以点安装卡去商店装（装好会自动接着办），"
                                + "并在终稿末尾附 <app_choice>（apps 里放这个 App 和 1~3 个同类常用 App 的真实包名）；能用网页 / 已装的同类 App 先办的就先办。",
                        capability, args);
            }
            int slash = component.indexOf('/');
            pkg = slash > 0 ? component.substring(0, slash) : component;
        }

        // 4. 替换指令与回读模板中的参数占位符
        //    ShellCommand 按 shell 上下文转义（裸槽位自动加引号、URL 查询值编码、单遍替换防二次注入）；
        //    expect 不是 shell 命令，按原值代入（否则 "50" 会变成 "'50'" 而回读失配）。
        String filledCmd;
        String filledCheck;
        String filledExpect;
        Map<String, String> slots;
        try {
            slots = ShellCommand.slotValues(args, component, pkg);
            filledCmd = ShellCommand.fill(capability.cmdTemplate, slots);
            filledCheck = ShellCommand.fill(capability.checkCommand, slots);
            filledExpect = ShellCommand.fillPlain(capability.expectTemplate, slots);
        } catch (IllegalArgumentException e) {
            return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "参数不符合规范：" + e.getMessage(),
                    e.getMessage(), capability, args);
        }

        // 5. 选后端执行
        ExecRequest req = new ExecRequest(capability, args, component, pkg, filledCmd, mContext, mShell, mApps);
        ExecutorBackend backend = pickBackend(capability, req);
        if (backend == null) {
            return ExecutionResult.failed(ErrorCode.UNKNOWN_CAPABILITY,
                    "这个能力暂时没有可用的执行方式", "no backend for executor=" + capability.executorType
                            + " sign=" + capability.sign, capability, args);
        }
        BackendOutcome out = backend.run(req);
        if (out.isTerminal()) return out.terminal;
        String execOutput = out.output;

        // 6. 物理状态回读与断言检验（check / expect）
        //    默认 UNVERIFIED —— 只有"跑过回读 + 命中期望"才算 VERIFIED（P0-4/E1）。
        ExecutionResult.Verification verification = ExecutionResult.Verification.UNVERIFIED;
        String checkOutput = "";
        if (filledCheck != null && filledCheck.trim().length() > 0) {
            // 2026-10-25 exec 守护 v4：带 $() / if / || 的回读命令改走 ShellPrograms 的多次单条命令
            com.onesay.assistant.v2.executor.backend.ShellPrograms.Program chk =
                    com.onesay.assistant.v2.executor.backend.ShellPrograms.checkFor(capability.sign);
            ShellExecutor.ShellResponse checkResp = chk != null ? chk.run(mShell, slots) : mShell.execute(filledCheck);
            checkOutput = checkResp.output != null ? checkResp.output.trim() : "";

            if (filledExpect != null && filledExpect.trim().length() > 0) {
                if (!matchesExpect(checkOutput, filledExpect)) {
                    Log.e(TAG, "回读校验未达预期: expect=[" + filledExpect + "], actual=[" + checkOutput + "]");
                    return ExecutionResult.failed(ErrorCode.VERIFY_FAILED, "操作已执行，但系统状态未发生变化", "回读输出不匹配: " + checkOutput, capability, args);
                }
                verification = ExecutionResult.Verification.VERIFIED;
            }
        }

        // 7. 生成并格式化面向用户的中文回执文案（过滤 Markdown，针对小溪缺陷 #3）
        String replyText = cleanMarkdown(formatReply(capability, args, checkOutput, execOutput));
        return ExecutionResult.success(capability, args, replyText, verification,
                checkOutput.length() > 0 ? checkOutput : execOutput);
    }

    /**
     * 选后端。executor=custom 但没有对应后端（既不是识屏也不是邮件）时返回 null，
     * 不再让它掉进 shell（custom 能力没有可执行的 shell 模板）。
     */
    ExecutorBackend pickBackend(Capability cap, ExecRequest req) {
        for (ExecutorBackend b : mBackends) {
            if (b instanceof ShellBackend && "custom".equals(cap.executorType)) return null;
            if (b.accepts(cap, req)) return b;
        }
        return null;
    }

    // ------------------------------------------------------------------ 观测与工具

    private static void logObservation(Capability capability, JSONObject args, ExecutionResult r) {
        // ★ int/1102 审计：成功的可改值单步记成「上一轮」，供「改成八点 / 再大一点」引用（FollowupEdit）
        try {
            if (r != null && r.ok && capability != null) com.onesay.assistant.v2.router.FollowupEdit.record(capability.sign, args, System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        // 把**执行结果与回读数据**落日志。
        if (capability != null && r != null) {
            Log.i(TAG, "[EXEC-OBS] " + capability.sign + " args=" + args
                    + " ok=" + r.ok + " code=" + r.code + " verified=" + r.verification
                    + " msg=[" + clip(r.userMessage) + "]"
                    + " detail=[" + clip(r.detail) + "]");
        }
    }

    /** 日志用的截断（换行会毁掉日志可读性）。 */
    private static String clip(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= 300 ? t : (t.substring(0, 300) + "…(+" + (t.length() - 300) + ")");
    }

    /**
     * @deprecated 2026-10-07 P0-8：改用 {@link ShellCommand}（按上下文转义，不再删除字符）。
     *     保留此方法仅为兼容旧自测入口；语义 = "值将被放进单引号内"：去换行，{@code '} → {@code '\''}。
     */
    @Deprecated
    public static String escapeShellValue(String v) {
        return ShellCommand.escapeInSingleQuotes(ShellCommand.sanitize(v));
    }

    static boolean matchesExpect(String actual, String expect) {
        if (actual == null || expect == null) return false;
        String[] parts = expect.split("\\|");
        for (String p : parts) {
            String trimmed = p.trim();
            if (trimmed.length() > 0 && actual.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static String formatReply(Capability cap, JSONObject args, String checkOut, String execOut) {
        // [r2/uxcompare] 系统控制即时反馈：亮度 / 音量报调后的结果值（百分比 / 格数 / 到顶），短信报下一步。
        //   读不出合法数值时返回 null → 下面的原逻辑逐字不变。
        String refined = ControlFeedback.refine(cap.sign, checkOut, execOut);
        if (refined != null) return refined;

        // 优先检查 replyMap 离散映射
        if (!cap.replyMap.isEmpty() && checkOut.length() > 0) {
            for (Map.Entry<String, String> entry : cap.replyMap.entrySet()) {
                if (checkOut.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        String reply = cap.replyTemplate;
        if (reply == null || reply.length() == 0) {
            reply = cap.summary + " 已完成";
        }

        String state = checkOut.length() > 0 ? checkOut : execOut;
        if (state.length() > 50) state = state.substring(0, 50);
        reply = reply.replace("{out}", state);

        Iterator<?> keys = args.keys();
        while (keys.hasNext()) {
            String k = (String) keys.next();
            reply = reply.replace("{" + k + "}", String.valueOf(args.opt(k)));
        }

        return reply;
    }

    /** 剥离 Markdown 字符（*、#、` 等），确保 TTS 语音合成自然发音（实现见 {@link TextUtil#cleanMarkdown}）。 */
    public static String cleanMarkdown(String text) {
        return TextUtil.cleanMarkdown(text);
    }
}
