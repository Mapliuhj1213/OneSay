package com.onesay.assistant.v2.ui.console;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.FeatureSwitch;

/**
 * [console R12] 开发者（默认隐藏，方案 §5）：旧控制台顶部的 4 个调试按钮、自测日志、能力表计数、File IPC、20 个 *.off 关停文件、
 * 内核插件顺序 / 状态、WakeTuning 阈值（只读，D7）、守护版本与回滚提示、「复制诊断信息」。
 * 「运行全量回归自测」只在 debug 包出现（release 包没有自测代码，直接不画）。
 */
final class DeveloperPage extends Console.BuiltPage {

    /** 全部功能级 kill-switch 文件（外部 files 目录或 /data/local/tmp/bixby_&lt;名&gt;，见 FeatureSwitch）。 */
    static final String[] KILL_FILES = {"s1.off", "capsule.off", "pickup.off", "circle.off", "clipchip.off", "s2.off", "memory.off",
            "habit.off", "ledger.off", "memory.implicit.off", "s3.off", "todo.off", "diag.off", "summary.off", "island.off", "screenref.off",
            "imgsave.off", "imgsave.cache.off", "quicknote.off", "hello.off"};

    private static final StringBuilder sLog = new StringBuilder();
    private TextView mLog;

    DeveloperPage(Console c) {
        super(c, "开发者");
    }

    @Override String rightAction() {
        return "关闭";
    }

    @Override void onRight() {
        c.setDevMode(false);
        c.toast("已关闭开发者模式");
        c.nav.popToRoot();
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        LinearLayout act = k.group(col, "调试动作");
        k.linkRow(act, "唤起底部面板", "FloatingSheetService.show", 0, new Runnable() {
            @Override public void run() {
                com.onesay.assistant.v2.ui.FloatingSheetService.show(c.act, null);
            }
        });
        k.linkRow(act, "模拟 Bixby 键", "WakeService.simulateKeyTrigger（暂停时会被挡）", 0, new Runnable() {
            @Override public void run() {
                com.onesay.assistant.v2.wake.WakeService s = com.onesay.assistant.v2.wake.WakeService.getInstance();
                if (s != null) {
                    s.simulateKeyTrigger();
                } else {
                    Intent it = new Intent(c.act, com.onesay.assistant.v2.wake.WakeService.class);
                    it.putExtra("trigger_key", true);
                    if (Build.VERSION.SDK_INT >= 26) c.act.startForegroundService(it);
                    else c.act.startService(it);
                }
                c.toast("已触发 Bixby 键唤醒事件");
            }
        });
        k.linkRow(act, "播放测试音频", "test_wake.wav（麦克风回环唤醒）", 0, new Runnable() {
            @Override public void run() {
                Intent it = new Intent(c.act, com.onesay.assistant.v2.wake.WakeService.class);
                it.putExtra("play_test", "test_wake.wav");
                if (Build.VERSION.SDK_INT >= 26) c.act.startForegroundService(it);
                else c.act.startService(it);
                c.toast("正在播放 test_wake.wav");
            }
        });
        if (com.onesay.assistant.v2.test.V2SelfTest.AVAILABLE) {
            k.linkRow(act, "运行全量回归自测", "只在 debug 包里有", 0, new Runnable() {
                @Override public void run() {
                    runSelfTest();
                }
            });
        }
        k.linkRow(act, "复制诊断信息", "把下面这些状态拼成文本复制到剪贴板", 0, new Runnable() {
            @Override public void run() {
                ClipboardManager cm = (ClipboardManager) c.act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("bixbyui-diag", diagText()));
                c.toast("诊断信息已复制");
            }
        });

        LinearLayout info = k.group(col, "运行信息");
        com.onesay.assistant.v2.capability.CapabilityRegistry reg = com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance();
        k.valueRow(info, "原子能力", "其中自动挖掘 " + reg.minedCount() + " 项", reg.size() + " 项", ConsoleHost.TONE_NORMAL);
        ConsoleStatus.Snapshot s = StatusProbe.last();
        k.valueRow(info, "Root 通道（File IPC）", null, s == null || s.rootOk == null ? "未知" : (s.rootOk ? "在线就绪" : "离线"),
                s == null || s.rootOk == null ? ConsoleHost.TONE_MUTED : (s.rootOk ? ConsoleHost.TONE_OK : ConsoleHost.TONE_BAD));
        Object[] d = ConsoleStatus.parseDaemon(s == null ? null : s.daemonProbe);
        k.valueRow(info, "按键守护", "回滚：/data/adb/modules/bixbykey_hijack/rollback_v7.sh",
                d[0] == null ? "未知" : ((Boolean) d[0] ? "运行" : "未运行") + (d[2] != null ? " " + d[2] : ""), ConsoleHost.TONE_NORMAL);
        k.valueRow(info, "唤醒引擎", com.onesay.assistant.v2.wake.WakeService.getStatusText(), com.onesay.assistant.v2.wake.AssistantPause.isPaused(c.act) ? "已暂停" : "—",
                ConsoleHost.TONE_NORMAL);

        LinearLayout kill = k.group(col, "关停文件（*.off）");
        int present = 0;
        for (String f : KILL_FILES) {
            if (FeatureSwitch.killFilePresent(c.act, f)) {
                present++;
                k.valueRow(kill, f, null, "存在 → 已停用", ConsoleHost.TONE_WARN);
            }
        }
        if (present == 0) k.valueRow(kill, "全部 " + KILL_FILES.length + " 个都不存在", "功能按开关走", "正常", ConsoleHost.TONE_OK);

        LinearLayout ker = k.group(col, "内核插件（CONSOLE 顺序）");
        for (FeaturePlugin p : FeatureKernel.get().order(com.onesay.assistant.v2.kernel.FeatureHook.CONSOLE)) {
            k.valueRow(ker, p.id(), p.consoleV2() ? "控制台 v2" : "控制台 v1（画在「其他」）", String.valueOf(FeatureKernel.get().state(p.id())),
                    FeatureKernel.get().state(p.id()) == FeatureKernel.State.FAILED ? ConsoleHost.TONE_BAD : ConsoleHost.TONE_NORMAL);
        }

        LinearLayout tun = k.group(col, "唤醒阈值（只读，改用 adb）");
        TextView tv = k.text(com.onesay.assistant.v2.wake.WakeTuning.get(c.act).dump(), ConsoleTheme.SP_LABEL, k.t.text2, ConsoleKit.W_REGULAR);
        tv.setTextIsSelectable(true);
        tv.setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12));
        tun.addView(tv);

        LinearLayout log = k.group(col, "自测与诊断输出");
        mLog = k.text(sLog.length() == 0 ? "（还没有输出）" : sLog.toString(), ConsoleTheme.SP_LABEL, k.t.text2, ConsoleKit.W_REGULAR);
        mLog.setTextIsSelectable(true);
        mLog.setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12));
        log.addView(mLog);
    }

    private String diagText() {
        StringBuilder sb = new StringBuilder("OneSay " + Console.RELEASE + (Console.debugBuild() ? " debug" : " release") + "\n");
        ConsoleStatus.Snapshot s = StatusProbe.last();
        if (s != null) {
            sb.append("pills=").append(ConsoleStatus.pills(s)).append("\nproblems=").append(ConsoleStatus.allProblems(s))
                    .append("\nvision=").append(s.visionModel).append(" asr=").append(s.asrConfigured).append(" overlay=").append(s.overlayOk)
                    .append(" battery=").append(s.batteryWhitelisted).append(" voiceprint=").append(s.voiceprint).append('\n');
        }
        sb.append("wake=").append(com.onesay.assistant.v2.wake.WakeService.getStatusText()).append(" paused=")
                .append(com.onesay.assistant.v2.wake.AssistantPause.isPaused(c.act)).append('\n');
        for (String f : KILL_FILES) if (FeatureSwitch.killFilePresent(c.act, f)) sb.append("kill ").append(f).append('\n');
        try {
            sb.append(FeatureKernel.get().dump(c.act));
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private void append(String line) {
        synchronized (sLog) {
            sLog.append(line).append('\n');
        }
        if (mLog != null) mLog.setText(sLog.toString());
    }

    private void runSelfTest() {
        synchronized (sLog) {
            sLog.setLength(0);
        }
        append("正在启动 V2 全量回归自测，请稍候…");
        c.toast("自测已在后台启动");
        com.onesay.assistant.v2.test.V2SelfTest.run(c.act, new com.onesay.assistant.v2.test.V2SelfTest.SelfTestCallback() {
            @Override public void onProgress(final String l) {
                c.ui(new Runnable() {
                    @Override public void run() {
                        append(l);
                    }
                });
            }

            @Override public void onComplete(final String summary) {
                c.ui(new Runnable() {
                    @Override public void run() {
                        append("\n" + summary);
                        c.toast("自测全部完成");
                    }
                });
            }
        });
    }

    @Override public void onShown() {
        // 自测进行中不重画（日志在同一个 TextView 上追加）；其余照常
        super.onShown();
    }
}
