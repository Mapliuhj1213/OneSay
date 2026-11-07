package com.onesay.assistant.v2.executor;

/**
 * 执行一条 shell 命令（经 root 守护）的最小接口（P1-7）。
 * 生产实现是 {@link ShellExecutor}（文件 IPC）；单测里用假实现记录命令、返回预设输出。
 */
public interface ShellRunner {
    ShellExecutor.ShellResponse execute(String command);
}
