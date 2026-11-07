package com.onesay.assistant.v2.errand;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * 跑腿阶梯最后一档：用<b>用户的默认浏览器</b>打开链接交给用户（ACTION_VIEW，不指定包名，由系统解析默认浏览器 / 选择器）。
 * 只开链接，不做任何 GUI 点击。
 */
public final class DefaultBrowser {

    private DefaultBrowser() {}

    static final String TAG = "Errand/Browser";

    /** 交接文案。 */
    public static final String NOTE = "已在浏览器打开，请你接着看";
    /** 控制台开关说明。 */
    public static final String TOGGLE_NOTE = "用你的默认浏览器打开链接，不替你点";

    /** 只接受 http(s)；返回规范后的 url，不合法返回 null（纯函数）。 */
    public static String checkUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        String low = u.toLowerCase(java.util.Locale.ROOT);
        return low.startsWith("http://") || low.startsWith("https://") ? u : null;
    }

    /** 是否有 App 能打开网页链接。 */
    public static boolean available(Context ctx) {
        try {
            return intent("https://example.com").resolveActivity(ctx.getPackageManager()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static Intent intent(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** 用默认浏览器打开（任意线程）。返回是否成功。 */
    public static boolean open(Context ctx, String url) {
        String u = checkUrl(url);
        if (u == null) return false;
        try {
            ctx.getApplicationContext().startActivity(intent(u));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "默认浏览器打开失败: " + t.getClass().getSimpleName());
            return false;
        }
    }
}
