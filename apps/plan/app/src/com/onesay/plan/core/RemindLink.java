package com.onesay.plan.core;

/**
 * [borrow-1112] 给助手的「提醒」接口（provider call 方法名与字段）。只经计划 App 已有的 provider call() 通道，
 * 门卫与数据接口相同：只认本 App 或同一证书签名的调用方（不认 root / adb），不新增任何导出组件。
 *
 * <pre>
 * listRemindedTasks     extras: window_min（int，默认 30，限 1–120）
 *                       → code / ok / count / data（JSON 数组：id, title, due_at, state, reminded_at, first_at, strong, snooze_until）
 *                       state：ringing 正在响 / snoozed 稍后中 / notified 发过普通通知 / stopped 已停止 / missed 没人管自动停了
 *                       只含未完成、未删除的待办；正在响的排最前，其余按 reminded_at 倒序
 * completeRemindedTask  extras: id（long，必填）, window_min（同上）
 *                       → code / ok / id / already_done；只认上面列表里的 id（防止勾错），会停掉正在响的铃
 * </pre>
 * 结果码与个人版伴侣连接一致：1 成功 / 2 参数不对 / 3 找不到（或不在最近提醒里）/ 4 不认识。
 * 开源版：门卫就是数据接口的 {@link AccessPolicy}（本 App / signature 权限 / 同证书签名；root、adb 一律拒绝）。
 */
public final class RemindLink {
    private RemindLink() {}

    public static final String F_LIST_REMINDED = "listRemindedTasks", F_COMPLETE_REMINDED = "completeRemindedTask";
    public static final String[] CAPS = {F_LIST_REMINDED, F_COMPLETE_REMINDED};
    public static final String X_ID = "id", X_WINDOW_MIN = "window_min", X_ALREADY_DONE = "already_done";
    public static final String K_OK = "ok", K_CODE = "code", K_DATA = "data", K_ERROR = "error";
    public static final int RC_OK = 1, RC_BAD_ARGS = 2, RC_NOT_FOUND = 3, RC_UNKNOWN = 4;

    public static boolean isRemindMethod(String m) {
        for (String c : CAPS) if (c.equals(m)) return true;
        return false;
    }
}
