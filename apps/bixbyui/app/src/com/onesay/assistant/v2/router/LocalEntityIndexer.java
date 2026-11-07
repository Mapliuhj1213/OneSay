package com.onesay.assistant.v2.router;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import java.util.ArrayList;
import java.util.List;

public class LocalEntityIndexer {

    /**
     * 已安装应用标签，逗号分隔。
     *
     * @return 拿不到时返回 <b>null</b>（调用方据此**整块省略**，
     *         绝不把 "无法获取应用列表" 这类错误文案当作实体数据喂进 prompt）
     */
    public static String getAppEntities(Context context) {
        if (context == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (ApplicationInfo info : apps) {
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && count > 10) continue;
                CharSequence label = pm.getApplicationLabel(info);
                if (label != null) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(label.toString());
                    count++;
                }
                if (count >= 30) break;
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 常用联系人（星标 + 通话频次 Top20），逗号分隔。
     *
     * @return 查询异常/无权限返回 <b>null</b>；查询成功但没有星标联系人时返回
     *         一句明确的"（无）"说明 —— 后者是**正常状态**，让模型知道"确实没有"
     *         比让它以为眼前这行错误文案是数据要好。
     */
    public static String getContactEntities(Context context) {
        if (context == null) return null;
        try {
            StringBuilder sb = new StringBuilder();
            Cursor cursor = context.getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts.DISPLAY_NAME},
                    ContactsContract.Contacts.STARRED + "=?",
                    new String[]{"1"},
                    ContactsContract.Contacts.TIMES_CONTACTED + " DESC LIMIT 20");

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(0);
                    if (name == null || name.length() == 0) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(name);
                }
                cursor.close();
            }

            return sb.length() == 0 ? "（本机没有星标联系人）" : sb.toString();
        } catch (Exception e) {
            // 无权限 / provider 不可用 —— 返回 null，让调用方整块省略这行，
            // 而不是把 "未授予联系人权限" 当成一份"联系人列表"塞进 prompt。
            return null;
        }
    }
}
