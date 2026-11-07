package com.onesay.assistant.v2.s3;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [s3] S3 自带能力（与 S2 一样<b>不注册进 CapabilityRegistry</b>：不改道老指令、能力快照不变）。执行链路：面板 dispatch 找不到注册能力
 * → {@code S3Hooks.describe} → ExecutionGate → （需要时）确认卡 → UnifiedExecutor → {@code S3Backend}（executor="s3"，App 内执行）。
 *
 * <table>
 *   <tr><th>签名</th><th>副作用 / 风险</th><th>说明</th></tr>
 *   <tr><td>{@link #SIGN_TASK_ADD}</td><td>PERSISTENT_WRITE / 0</td><td>新建提醒或普通 AGENT 定时任务，不确认</td></tr>
 *   <tr><td>{@link #SIGN_TASK_ADD_CONFIRM}</td><td>PERSISTENT_WRITE / 2 + confirm</td><td>新建<b>含红卡动作</b>（发短信 / 发邮件 / 支付 / 删除 / 打电话）的
 *       AGENT 任务：创建环节就要确认（到点执行时还会再弹确认卡，绝不自动批准）</td></tr>
 *   <tr><td>{@link #SIGN_TASK_CANCEL}</td><td>PERSISTENT_WRITE / 1</td><td>「取消读邮件那个」：用户点名的一条，不确认</td></tr>
 *   <tr><td>{@link #SIGN_TODO_ADD}</td><td>PERSISTENT_WRITE / 0</td><td>记个待办（带截止时同时建提醒）</td></tr>
 *   <tr><td>{@link #SIGN_TODO_DONE}</td><td>PERSISTENT_WRITE / 0</td><td>勾销一条待办（同时取消它派生的提醒）</td></tr>
 * </table>
 */
public final class S3Capabilities {

    private S3Capabilities() {}

    public static final String EXECUTOR = "s3";
    public static final String SIGN_TASK_ADD = "s3.task.add";
    public static final String SIGN_TASK_ADD_CONFIRM = "s3.task.add_confirm";
    public static final String SIGN_TASK_CANCEL = "s3.task.cancel";
    public static final String SIGN_TODO_ADD = "s3.todo.add";
    public static final String SIGN_TODO_DONE = "s3.todo.done";
    /** ★ int/1102 审计：清空全部未完成待办（批量、不可撤销 → risk 2 + 确认卡）。 */
    public static final String SIGN_TODO_CLEAR = "s3.todo.clear";

    private static volatile Map<String, Capability> sCatalog;

    private static ParamsSchema taskSchema() {
        return new ParamsSchema().addInteger("kind", true, 0, 1, null).addInteger("repeat", true, 0, 3, null)
                .addInteger("week_mask", false, 0, 127, "0").addInteger("hour", true, 0, 23, null)
                .addInteger("minute", true, 0, 59, null).addString("once_at", false, "0").addString("title", false, "")
                .addString("command", false, "").addString("preview", false, "");
    }

    private static Map<String, Capability> catalog() {
        Map<String, Capability> c = sCatalog;
        if (c != null) return c;
        synchronized (S3Capabilities.class) {
            if (sCatalog != null) return sCatalog;
            Map<String, Capability> m = new LinkedHashMap<String, Capability>();
            put(m, new Capability.Builder(SIGN_TASK_ADD, "新建定时任务：到点提醒，或到点把一句指令跑一遍")
                    .domain("s3").category("schedule").sideEffect(SideEffect.PERSISTENT_WRITE).risk(0, false)
                    .executor(EXECUTOR).schema(taskSchema()).reply("好的，{preview}").build());
            put(m, new Capability.Builder(SIGN_TASK_ADD_CONFIRM, "新建含发送/支付/删除等动作的定时任务（到点执行时仍需你确认）")
                    .domain("s3").category("schedule").sideEffect(SideEffect.PERSISTENT_WRITE).risk(2, true)
                    .executor(EXECUTOR).schema(taskSchema()).reply("好的，{preview}").build());
            put(m, new Capability.Builder(SIGN_TASK_CANCEL, "取消一条定时任务")
                    .domain("s3").category("schedule").sideEffect(SideEffect.PERSISTENT_WRITE).risk(1, false)
                    .executor(EXECUTOR)
                    .schema(new ParamsSchema().addString("id", true, null).addString("preview", false, ""))
                    .reply("已取消：{preview}").build());
            put(m, new Capability.Builder(SIGN_TODO_ADD, "记一条待办（可带截止时间）")
                    .domain("s3").category("todo").sideEffect(SideEffect.PERSISTENT_WRITE).risk(0, false)
                    .executor(EXECUTOR)
                    .schema(new ParamsSchema().addString("text", true, null).addString("due_at", false, "0")
                            .addString("remind_at", false, "0").addString("preview", false, ""))
                    .reply("记下待办：{preview}").build());
            put(m, new Capability.Builder(SIGN_TODO_DONE, "勾销一条待办")
                    .domain("s3").category("todo").sideEffect(SideEffect.PERSISTENT_WRITE).risk(0, false)
                    .executor(EXECUTOR)
                    .schema(new ParamsSchema().addString("id", true, null).addString("preview", false, ""))
                    .reply("已完成：{preview}").build());
            put(m, new Capability.Builder(SIGN_TODO_CLEAR, "清空全部未完成的待办（同时取消它们派生的提醒）")
                    .domain("s3").category("todo").sideEffect(SideEffect.PERSISTENT_WRITE).risk(2, true)
                    .executor(EXECUTOR)
                    .schema(new ParamsSchema().addInteger("count", false, 0, 100000, "0").addString("preview", false, ""))
                    .reply("已清空 {count} 条待办").build());
            sCatalog = Collections.unmodifiableMap(m);
            return sCatalog;
        }
    }

    private static void put(Map<String, Capability> m, Capability c) {
        m.put(c.sign, c);
    }

    public static Capability get(String sign) {
        return sign == null ? null : catalog().get(sign);
    }

    public static boolean isS3Sign(String sign) {
        return sign != null && catalog().containsKey(sign);
    }

    public static boolean isLocal(Capability cap) {
        return cap != null && EXECUTOR.equals(cap.executorType) && isS3Sign(cap.sign);
    }
}
