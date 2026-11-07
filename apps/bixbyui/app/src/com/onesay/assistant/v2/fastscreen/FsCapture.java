package com.onesay.assistant.v2.fastscreen;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * ★ r2/fast-screen：现有读屏调用点（识屏 / 总结 / 圈一下 / 存图 / 记一记缩略图 / 视觉识屏截图）共用的快速采集入口。
 * 每个方法返回 null = 助手不可用，调用方走原路径（并已替它让出 UiAutomation，原路径的 uiautomator dump 不会被挡）。
 */
public final class FsCapture {

    private static final String TAG = "FastScreen";

    private FsCapture() {}

    /** dumpsys 风格的前台行（给 UiDumpParser.parseResumedActivity）。act 为空时只给包名（解析不出 → 调用方用树根包名）。 */
    public static String activityLine(String pkg, String act) {
        if (pkg == null || pkg.length() == 0) return "";
        if (act == null || act.length() == 0) return "";
        String a = act.startsWith(pkg + ".") ? act.substring(pkg.length()) : act;
        return "  mResumedActivity: ActivityRecord{0 u0 " + pkg + "/" + a + " t0}";
    }

    static String jsonField(String json, String key) {
        if (json == null) return "";
        try {
            return new org.json.JSONObject(json).optString(key, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static final class Xml {
        public String xml;
        public String activityLine;
        public long ms;
    }

    /** 识屏：压缩树 XML + 前台行。 */
    public static Xml xml(Context ctx, boolean compressed) {
        if (!FastScreen.enabled(ctx)) return null;
        FastScreen fs = FastScreen.get(ctx);
        long t0 = SystemClock.elapsedRealtime();
        String x = fs.xml(compressed);
        if (x == null) {
            fs.releaseForFallback();
            Log.w(TAG, "[FS] 读树不可用，回退 uiautomator：" + fs.lastError());
            return null;
        }
        String top = fs.top();
        Xml r = new Xml();
        r.xml = x;
        r.activityLine = activityLine(jsonField(top, "pkg"), jsonField(top, "act"));
        r.ms = SystemClock.elapsedRealtime() - t0;
        return r;
    }

    public static final class RawAndXml {
        public byte[] raw;
        public String xml;
        public String activityLine;
        public long ms;
    }

    /** 存图：原始帧（screencap 无 -p 格式）+ 非压缩树。 */
    public static RawAndXml rawAndXml(Context ctx) {
        if (!FastScreen.enabled(ctx)) return null;
        FastScreen fs = FastScreen.get(ctx);
        long t0 = SystemClock.elapsedRealtime();
        byte[] raw = fs.shotRaw();
        String x = raw == null ? null : fs.xml(false);
        if (raw == null || x == null) {
            fs.releaseForFallback();
            Log.w(TAG, "[FS] 截图/读树不可用，回退 root 命令：" + fs.lastError());
            return null;
        }
        String top = fs.top();
        RawAndXml r = new RawAndXml();
        r.raw = raw;
        r.xml = x;
        r.activityLine = activityLine(jsonField(top, "pkg"), jsonField(top, "act"));
        r.ms = SystemClock.elapsedRealtime() - t0;
        return r;
    }

    /** 截图 JPEG 写到 App 私有文件（记一记缩略图 / 视觉识屏）；成功返回 true。 */
    public static boolean jpegTo(Context ctx, File out, int quality, int maxWidth) {
        if (!FastScreen.enabled(ctx)) return false;
        byte[] b = FastScreen.get(ctx).shotJpeg(quality, maxWidth);
        return b != null && writeFile(out, b);
    }

    public static boolean writeFile(File f, byte[] b) {
        if (f == null || b == null) return false;
        FileOutputStream o = null;
        try {
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            o = new FileOutputStream(f);
            o.write(b);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (o != null) o.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
