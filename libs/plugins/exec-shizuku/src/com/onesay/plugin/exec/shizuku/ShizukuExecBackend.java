package com.onesay.plugin.exec.shizuku;

import com.onesay.plugin.api.exec.ExecBackend;
import com.onesay.plugin.api.exec.ExecResult;

/**
 * Shizuku 执行后端插件（可选，默认不选）—— <b>本版是占位（stub）</b>。
 *
 * <p>目标覆盖三种 Shizuku 启动方式：root 启动、有线 adb 启动、Android 11+ 无线调试启动；后两种以 shell 身份（uid 2000）执行。
 *
 * <p>TODO（后续）：
 * <ol>
 *   <li>引入 Shizuku API（dev.rikka.shizuku:api / provider，Apache-2.0）——本仓库是无 Gradle 的脚本构建，需要把 aar 里的
 *       classes.jar 与 Manifest 片段（ShizukuProvider）接进 build_linux.sh；</li>
 *   <li>{@link #available()}：{@code Shizuku.pingBinder()} + {@code checkSelfPermission()}，未授权时 {@code requestPermission()}；
 *       {@code Shizuku.getUid()} 为 0 时报 ROOT，2000 时报 SHELL；</li>
 *   <li>{@link #run}：用 UserService（或 newProcess）执行命令，超时即杀；</li>
 *   <li>shell 身份做不到的命令（读 /data/data、改 /system、/dev/input）由调用方按 {@link #level()} 过滤。</li>
 * </ol>
 * 在此之前 {@link #available()} 恒为 {@link Tri#NO}，选它不会点亮任何特权动作。
 */
public final class ShizukuExecBackend implements ExecBackend {

    public static final String REASON_STUB = "本版尚未接入 Shizuku 库（占位，见 libs/plugins/exec-shizuku）";

    @Override public String id() {
        return "shizuku";
    }

    @Override public String title() {
        return "Shizuku（root / 有线 adb / 无线调试启动）";
    }

    @Override public UidLevel level() {
        return UidLevel.SHELL;
    }

    @Override public Tri available() {
        return Tri.NO;
    }

    @Override public String reason() {
        return REASON_STUB;
    }

    @Override public ExecResult run(String command, long timeoutMs) {
        return ExecResult.down(REASON_STUB);
    }
}
