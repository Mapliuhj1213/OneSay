package com.onesay.assistant.v2.platform;

import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.ShellRunner;

/**
 * 特权 shell 操作抽象（refactor/oss 2026-11-06）。
 *
 * <p>今天唯一的实现是 root 执行守护（{@link ShellExecutor}，文件 IPC + HMAC，经 {@link ShellRunner}）。
 * 无 root 设备的计划实现是 Shizuku（ADB / shell 身份）：大部分 {@code settings} / {@code cmd} / {@code am} / {@code input} /
 * {@code uiautomator dump} / {@code screencap} 命令在 shell 身份下同样可跑，root 专属的（读 /data/data、改 /system、
 * 劫持 /dev/input）则不行——每条命令该走哪档由 {@link #level()} 和调用方声明的需求比较决定。
 *
 * <p>迁移策略：调用方仍拿 {@link ShellRunner}；本接口只是在它上面加「这档后端能做什么」的声明，行为与直接用 ShellExecutor 一致。
 */
public interface RootOps extends ShellRunner {

    /** 后端的特权等级。 */
    enum Level {
        /** 没有任何特权后端。 */
        NONE,
        /** shell（uid 2000）：Shizuku / adb。 */
        SHELL,
        /** root（uid 0）。 */
        ROOT
    }

    /** 这个后端的特权等级。 */
    Level level();

    /** 后端当前是否在线（守护活着 / Shizuku 已授权）；不确定返回 {@link Tri#UNKNOWN}。 */
    Tri available();

    /** 把现有 root 执行守护包成 {@link RootOps}（不改变它的任何行为）。 */
    final class Daemon implements RootOps {
        private final ShellRunner mRunner;
        private volatile Tri mAvailable = Tri.UNKNOWN;

        public Daemon(ShellRunner runner) {
            mRunner = runner;
        }

        @Override public Level level() {
            return Level.ROOT;
        }

        @Override public Tri available() {
            return mAvailable;
        }

        /** 由状态探测（控制台 StatusProbe）回填。 */
        public void setAvailable(Tri t) {
            mAvailable = t == null ? Tri.UNKNOWN : t;
        }

        @Override public ShellExecutor.ShellResponse execute(String command) {
            return mRunner.execute(command);
        }
    }
}
