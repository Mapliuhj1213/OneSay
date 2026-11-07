package com.onesay.assistant.v2.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.onesay.assistant.v2.platform.DeviceCap.*;

/**
 * 功能注册表（纯 Java，refactor/oss 2026-11-06）：每个和设备能力有关的功能声明自己需要什么，
 * 控制台「关于 › 设备能力与功能」按这张表生成开关 / 状态行。
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>只<b>登记</b>，不改任何功能的默认值和运行逻辑。有开关的项绑定到它原来的 prefs 键（JVM 测试逐项比对原 Settings 类）；</li>
 *   <li>新功能如果依赖 root / 三星 / 特定系统版本，必须在这里加一项，写清所需能力和无 root 替代；</li>
 *   <li>{@link FeatureSpec.Status#NOT_BUILT} 是以前因系统限制没做或砍掉的功能，留在表里给移植 / 以后实现时对照。</li>
 * </ul>
 */
public final class FeatureRegistry {

    private FeatureRegistry() {}

    public static final String G_WAKE = "唤醒入口";
    public static final String G_EXEC = "执行与读屏";
    public static final String G_DISPLAY = "显示与提醒";
    public static final String G_VENDOR = "厂商专属";
    public static final String G_NOT_BUILT = "受限未实现";

    /** 分组顺序（页面按这个顺序画）。 */
    public static final String[] GROUPS = {G_WAKE, G_EXEC, G_DISPLAY, G_VENDOR, G_NOT_BUILT};

    private static final String ALT_SCREEN = "无障碍服务读控件树（debug 已有 B2 后端）· Shizuku 以 shell 身份跑 uiautomator dump / screencap · "
            + "MediaProjection 截屏（每次需用户同意）";
    private static final String ALT_EXEC = "Shizuku（ADB 级：settings put / cmd / am / input）· 无障碍代点设置页 · 只给设置页深链";

    private static final List<FeatureSpec> ALL;

    static {
        List<FeatureSpec> l = new ArrayList<FeatureSpec>();
        // ---------------------------------------------------------------- 唤醒入口
        l.add(FeatureSpec.status("wake.kws", "语音唤醒词", G_WAKE, FeatureSpec.Status.SHIPPED,
                new DeviceCap[]{MIC, ARM64}, "按键 / 默认助理手势 / 打字", "本地 sherpa-onnx KWS，不需要 root"));
        l.add(FeatureSpec.status("wake.bixby_key", "Bixby 键唤醒（短按 / 长按记一记 / 双击）", G_WAKE, FeatureSpec.Status.SHIPPED,
                new DeviceCap[]{BIXBY_KEY, ROOT, KEY_DAEMON}, BIXBY_KEY.alternative,
                "root 按键守护劫持 /dev/input 上的 Bixby 键事件（tools/kdaemon/hijack_daemon）"));
        l.add(FeatureSpec.status("wake.assist", "默认数字助理手势（长按 Home 等）", G_WAKE, FeatureSpec.Status.SHIPPED,
                new DeviceCap[]{DEFAULT_ASSISTANT}, "按键 / 语音唤醒", "VoiceInteractionService，不需要 root；在系统「默认应用 › 数字助理」里选本 App"));
        // ---------------------------------------------------------------- 执行与读屏
        l.add(FeatureSpec.status("exec.system", "系统设置类执行（Wi-Fi / 亮度 / 勿扰 / 清后台…）", G_EXEC, FeatureSpec.Status.SHIPPED,
                new DeviceCap[]{ROOT, EXEC_DAEMON}, ALT_EXEC, "统一执行网关 → root 执行守护（文件 IPC + HMAC）"));
        l.add(FeatureSpec.toggle("screen.ref", "识屏（截图 + 界面树问答）", G_EXEC, new DeviceCap[]{ROOT, EXEC_DAEMON}, ALT_SCREEN,
                "uiautomator dump / screencap 经 root 守护", "bixby_screenref", "enabled", true, "screenref.off"));
        l.add(FeatureSpec.toggle("quicknote", "记一记（读屏 → 分类 → 摘要卡）", G_EXEC, new DeviceCap[]{ROOT, EXEC_DAEMON}, ALT_SCREEN,
                "读屏经 root 守护；长按 Bixby 键触发另需按键守护", "bixby_quicknote", "enabled", true, "quicknote.off"));
        l.add(FeatureSpec.toggle("imgsave", "保存图片（截图精准裁剪 / 取原图）", G_EXEC, new DeviceCap[]{ROOT, EXEC_DAEMON},
                "MediaProjection 截屏后裁剪（拿不到原图）· 无障碍定位图片控件", "screencap + dump + App 缓存原图经 root 守护",
                "bixby_imgsave", "enabled", true, "imgsave.off"));
        l.add(FeatureSpec.status("suggest.shot_root", "截图秒出建议（root inotify 监听）", G_EXEC, FeatureSpec.Status.SHIPPED,
                new DeviceCap[]{ROOT, EXEC_DAEMON}, "已内置：FileObserver / MediaStore 监听截图目录（自动回退，稍慢）",
                "没有 root 时主动建议仍可用"));
        // ---------------------------------------------------------------- 显示与提醒
        l.add(FeatureSpec.toggle("island", "灵动岛", G_DISPLAY, new DeviceCap[]{OVERLAY}, "通知栏常驻通知", "TYPE_APPLICATION_OVERLAY，不用无障碍",
                "bixby_island", "enabled", true, "island.off"));
        l.add(FeatureSpec.toggle("notice", "灵动岛消息提醒", G_DISPLAY, new DeviceCap[]{OVERLAY, NOTIFICATION_LISTENER}, "无",
                "通知使用权转发到岛上", "bixby_notice", "enabled", true, "notice.off"));
        l.add(FeatureSpec.status("a11y.backend", "无障碍读屏后端（B2）", G_DISPLAY, FeatureSpec.Status.DEBUG_ONLY,
                new DeviceCap[]{ACCESSIBILITY}, "root 读屏", "只在 debug 包；无 root 设备读屏的首选替代"));
        // ---------------------------------------------------------------- 厂商专属
        l.add(FeatureSpec.toggle("errand.samsung", "浏览器跑腿：三星浏览器桌面模式", G_VENDOR, new DeviceCap[]{SAMSUNG_BROWSER},
                SAMSUNG_BROWSER.alternative, "跑腿阶梯的最后一档（默认关）", "bixby_errand", "samsung", false, "errand.off", "errand_samsung.off"));
        l.add(FeatureSpec.status("store.galaxy", "装 App：三星应用商店直达", G_VENDOR, FeatureSpec.Status.SHIPPED,
                new DeviceCap[]{GALAXY_STORE}, GALAXY_STORE.alternative, "安装卡会按已装商店分流"));
        l.add(FeatureSpec.status("wake.cue_samsung", "三星原厂唤醒提示音", G_VENDOR, FeatureSpec.Status.SHIPPED,
                new DeviceCap[]{SAMSUNG, BIXBY_SYSTEM}, BIXBY_SYSTEM.alternative, "运行时从系统 / Bixby 组件读取，不打包"));
        l.add(FeatureSpec.status("city.samsung", "家乡城市（读三星天气时钟数据库）", G_VENDOR, FeatureSpec.Status.SHIPPED,
                new DeviceCap[]{SAMSUNG, ROOT, EXEC_DAEMON}, "定位 / 用户在设置里填写", null));
        l.add(FeatureSpec.status("diag.samsung", "设置诊断：三星专有设置项", G_VENDOR, FeatureSpec.Status.SHIPPED,
                new DeviceCap[]{SAMSUNG, ROOT, EXEC_DAEMON}, "只诊断 AOSP 通用设置项", null));
        // ---------------------------------------------------------------- 受限未实现
        l.add(FeatureSpec.notBuilt("wake.other_key", "其他按键唤醒（音量键组合 / 侧键）", new DeviceCap[]{ACCESSIBILITY},
                "无障碍服务 onKeyEvent 过滤（需 flagRequestFilterKeyEvents）；或 root getevent", "没有 Bixby 键的机型（如魅族 21 Pro）的首选按键入口"));
        l.add(FeatureSpec.notBuilt("wake.floating", "悬浮球唤醒", new DeviceCap[]{OVERLAY},
                "复用灵动岛的 overlay 窗口加一个可拖动按钮", "无 root、无按键时最稳的入口"));
        l.add(FeatureSpec.notBuilt("exec.shizuku", "Shizuku 执行后端", new DeviceCap[]{SHIZUKU},
                "作为 ShellRunner 的第二实现，覆盖 settings / cmd / am / input / uiautomator / screencap", "无 root 设备上替代 root 执行守护的大部分能力"));
        l.add(FeatureSpec.notBuilt("install.silent", "静默安装 App", new DeviceCap[]{ROOT, EXEC_DAEMON},
                "PackageInstaller 弹系统确认 · Shizuku 的 pm install", "用户已搁置"));
        l.add(FeatureSpec.notBuilt("imgsave.lsposed", "进程内取原图（LSPosed）", new DeviceCap[]{ROOT},
                "MediaProjection 截屏", "用户已搁置；需 LSPosed 框架"));
        l.add(FeatureSpec.notBuilt("agent.vision_a11y", "纯视觉 + 无障碍代操作（Tapilot 式）", new DeviceCap[]{API_30, ACCESSIBILITY},
                "现有 root 读屏 + 代点", "上游要求 Android 11+，Note 8（Android 8.0）跑不了"));
        ALL = Collections.unmodifiableList(l);
    }

    /** 全部功能（注册顺序）。 */
    public static List<FeatureSpec> all() {
        return ALL;
    }

    /** 按 id 找；没有返回 null。 */
    public static FeatureSpec get(String id) {
        for (FeatureSpec f : ALL) if (f.id.equals(id)) return f;
        return null;
    }

    /** 按 {@link #GROUPS} 顺序分组。 */
    public static Map<String, List<FeatureSpec>> byGroup() {
        Map<String, List<FeatureSpec>> m = new LinkedHashMap<String, List<FeatureSpec>>();
        for (String g : GROUPS) m.put(g, new ArrayList<FeatureSpec>());
        for (FeatureSpec f : ALL) {
            List<FeatureSpec> l = m.get(f.group);
            if (l == null) {
                l = new ArrayList<FeatureSpec>();
                m.put(f.group, l);
            }
            l.add(f);
        }
        return m;
    }
}
