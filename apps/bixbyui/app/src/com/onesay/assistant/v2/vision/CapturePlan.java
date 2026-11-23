package com.onesay.assistant.v2.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ a11y-1118：识屏截图试哪些目录、每处试不试特权 screencap，以及失败时给用户看的原因（纯 Java，JVM 可测）。
 *
 * <ul>
 *   <li>root（守护 / su）：照旧 私有 scratch → 内部 cache → 外部缓存目录，每处都可以 screencap（root 原地写 App 建好的文件）；</li>
 *   <li>只有 Shizuku（shell uid）：screencap 白名单只认外部缓存目录（另两处一定被拒，shell 本来也写不了 App 私有目录），
 *       所以私有 scratch 只试 App 自己落盘的方式（读屏助手 / 无障碍截图），screencap 第一次就写外部缓存目录。</li>
 * </ul>
 */
public final class CapturePlan {
    private CapturePlan() {}

    public static final int PRIVATE = 0, INTERNAL = 1, EXTERNAL = 2;

    public static final class Step {
        public final int dir;
        public final boolean shell;

        Step(int dir, boolean shell) {
            this.dir = dir;
            this.shell = shell;
        }

        @Override public String toString() {
            return (dir == PRIVATE ? "private" : dir == INTERNAL ? "internal" : "external") + (shell ? "+shell" : "");
        }
    }

    public static List<Step> steps(boolean shizukuOnly) {
        List<Step> l = new ArrayList<Step>();
        if (shizukuOnly) {
            l.add(new Step(PRIVATE, false));
            l.add(new Step(EXTERNAL, true));
        } else {
            l.add(new Step(PRIVATE, true));
            l.add(new Step(INTERNAL, true));
            l.add(new Step(EXTERNAL, true));
        }
        return Collections.unmodifiableList(l);
    }

    /**
     * ★ sec-1123 L4：Android 10 及以下，外部缓存目录（Android/data/本包/cache）持存储权限的别的 App 也能读。
     * root 能写私有目录，所以这些系统上 root 档<b>不再退到外部缓存目录</b>（私有 / 内部 cache 都失败就如实报失败）；
     * 只有 Shizuku 时 shell 写不了私有目录，外部缓存目录照旧要用，但文件名每次随机（{@link #externalShotName}），用完即删。纯函数。
     */
    public static List<Step> forSdk(List<Step> steps, int sdk) {
        if (steps == null || sdk > 29) return steps;
        boolean shellOnlyExternal = true;
        for (Step s : steps) if (s.dir != EXTERNAL && s.shell) shellOnlyExternal = false;
        if (shellOnlyExternal) return steps; // 只有 Shizuku：外部缓存是唯一能 screencap 的地方
        List<Step> l = new ArrayList<Step>();
        for (Step s : steps) if (s.dir != EXTERNAL) l.add(s);
        return Collections.unmodifiableList(l);
    }

    /** ★ sec-1123 L4：外部缓存目录里的截图名每次随机（别的 App 猜不到、不能提前放同名文件）；只含 Shizuku 截图白名单认的字符。 */
    public static String externalShotName(java.util.Random r) {
        StringBuilder b = new StringBuilder("vs_");
        for (int i = 0; i < 16; i++) b.append(Character.forDigit((r == null ? new java.security.SecureRandom() : r).nextInt(16), 16));
        return b.append(".png").toString();
    }

    /** 一次 screencap 没出有效文件的原因（给日志和提示）。 */
    public static String describe(boolean denied, String denyReason, boolean down, boolean timeout, int rc, String output,
                                  boolean fileExists, long fileLen) {
        String out = output == null ? "" : output.trim();
        if (out.length() > 160) out = out.substring(0, 160);
        if (denied) return "截屏命令被拒绝（" + (denyReason == null || denyReason.length() == 0 ? "DENY" : denyReason) + "）";
        if (down) return "特权通道不可用" + (out.length() > 0 ? "（" + out + "）" : "");
        if (timeout) return "截屏命令超时";
        if (!fileExists) return "截屏命令没有写出文件（rc=" + rc + (out.length() > 0 ? "，" + out : "") + "）";
        if (fileLen <= 0) return "截图文件是空的（0 字节，rc=" + rc + "；可能是系统不让特权进程写本 App 的 Android/data 目录）";
        return "截图文件太小（" + fileLen + " 字节，rc=" + rc + "）";
    }

    /** 给用户看的一句话：有真实原因就带上。 */
    public static String userError(String lastErr) {
        if (lastErr == null || lastErr.trim().length() == 0) return "无法截取屏幕内容：画面文件为空或权限受阻";
        return "无法截取屏幕内容：" + lastErr.trim();
    }
}
