package com.onesay.assistant.v2.ui.console;

import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;

/**
 * [console R12] 运行状态（点首页 Hero 进来）：每项一个真实状态 + 能修的给修复按钮。探测不到 = 未知（灰），不猜。
 */
final class StatusPage extends Console.BuiltPage {

    private ConsoleStatus.Snapshot mSnap;
    private boolean mProbing;

    StatusPage(Console c) {
        super(c, "运行状态");
    }

    @Override String rightAction() {
        return "刷新";
    }

    @Override void onRight() {
        probe();
    }

    @Override protected android.view.View create() {
        mSnap = StatusProbe.last();
        android.view.View v = super.create();
        probe();
        return v;
    }

    private void probe() {
        if (mProbing) return;
        mProbing = true;
        StatusProbe.run(c.act, c.compiler, new StatusProbe.Listener() {
            @Override public void onStatus(ConsoleStatus.Snapshot s) {
                mSnap = s;
                if (s.done) mProbing = false;
                if (view != null) fill();
            }
        });
    }

    private static int tone(ConsoleStatus.Level l) {
        switch (l) {
            case OK: return ConsoleHost.TONE_OK;
            case WARN: return ConsoleHost.TONE_WARN;
            case BAD: return ConsoleHost.TONE_BAD;
            default: return ConsoleHost.TONE_MUTED;
        }
    }

    private static String unknown(ConsoleStatus.Snapshot s) {
        return s.done ? "未知" : "检测中";
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        ConsoleStatus.Snapshot s = mSnap != null ? mSnap : new ConsoleStatus.Snapshot();
        LinearLayout g = k.group(col, "核心");
        ConsoleStatus.Pill w = ConsoleStatus.wake(s);
        if (w.level == ConsoleStatus.Level.BAD) {
            k.actionRow(g, "唤醒服务", "前台服务：麦克风与唤醒入口", "启动", new Runnable() {
                @Override public void run() {
                    c.startWakeService();
                }
            });
        } else {
            String v = w.level == ConsoleStatus.Level.OK ? "运行中" : w.level == ConsoleStatus.Level.OFF ? "已暂停"
                    : w.level == ConsoleStatus.Level.WARN ? "启动中" : unknown(s);
            k.valueRow(g, "唤醒服务", s.paused ? "已暂停：唤醒与按键不响应" : "前台服务：麦克风与唤醒入口", v, tone(w.level));
        }
        ConsoleStatus.Pill r = ConsoleStatus.root(s);
        k.valueRow(g, "Root 通道", "系统设置类操作靠它", s.rootOk == null ? unknown(s) : (s.rootOk ? "就绪" : "未就绪"), tone(r.level));

        LinearLayout m = k.group(col, "云端");
        if (Boolean.FALSE.equals(s.llmConfigured)) {
            k.actionRow(m, "DeepSeek", "复杂问题 / 智能体", "去配置", new Runnable() {
                @Override public void run() {
                    c.editApiKey(new Runnable() {
                        @Override public void run() {
                            probe();
                        }
                    });
                }
            });
        } else {
            k.valueRow(m, "DeepSeek", "复杂问题 / 智能体", s.llmConfigured == null ? unknown(s) : "已配置", tone(ConsoleStatus.llm(s).level));
        }
        k.valueRow(m, "视觉模型", "识屏 / 看图总结", s.visionModel != null ? s.visionModel : unknown(s), ConsoleHost.TONE_NORMAL);
        if (Boolean.FALSE.equals(s.asrConfigured) || Boolean.TRUE.equals(s.asrKeyInvalid)) {   // r2/p0 P0-2：能直接去填
            k.actionRow(m, "云端语音识别", Boolean.FALSE.equals(s.asrConfigured) ? "未配置，说话听不懂" : "Key 失效（鉴权失败）", "去配置", new Runnable() {
                @Override public void run() {
                    c.editAsrKey(new Runnable() {
                        @Override public void run() {
                            probe();
                        }
                    });
                }
            }).setSubTone(ConsoleHost.TONE_WARN);
        } else {
            k.valueRow(m, "云端语音识别", "阿里云 / DashScope", s.asrConfigured == null ? unknown(s) : "已配置",
                    s.asrConfigured == null ? ConsoleHost.TONE_MUTED : ConsoleHost.TONE_OK);
        }

        LinearLayout p = k.group(col, "权限");
        if (Boolean.FALSE.equals(s.overlayOk)) {
            k.actionRow(p, "悬浮窗", "灵动岛靠它显示", "去授权", new Runnable() {
                @Override public void run() {
                    c.openOverlaySettings();
                }
            });
        } else {
            k.valueRow(p, "悬浮窗", "灵动岛靠它显示", s.overlayOk == null ? unknown(s) : "已授权", s.overlayOk == null ? ConsoleHost.TONE_MUTED : ConsoleHost.TONE_OK);
        }
        if (Boolean.FALSE.equals(s.batteryWhitelisted)) {
            k.actionRow(p, "电池优化白名单", "不在白名单，熄屏久了定时任务会晚", "加入", new Runnable() {
                @Override public void run() {
                    c.joinBatteryWhitelist(new Runnable() {
                        @Override public void run() {
                            probe();
                        }
                    });
                }
            }).setSubTone(ConsoleHost.TONE_WARN);
        } else {
            k.valueRow(p, "电池优化白名单", "定时任务准点", s.batteryWhitelisted == null ? unknown(s) : "已加入",
                    s.batteryWhitelisted == null ? ConsoleHost.TONE_MUTED : ConsoleHost.TONE_OK);
        }
        if (Boolean.FALSE.equals(s.micPermission)) {
            k.actionRow(p, "麦克风", "唤醒与听写", "去设置", new Runnable() {
                @Override public void run() {
                    c.openAppSettings();
                }
            });
        } else {
            k.valueRow(p, "麦克风", "唤醒与听写", s.micPermission == null ? unknown(s) : "已授权", s.micPermission == null ? ConsoleHost.TONE_MUTED : ConsoleHost.TONE_OK);
        }
        if (Boolean.FALSE.equals(s.voiceprint)) {
            k.actionRow(p, "声纹", "没注册时别人说唤醒词也会唤醒", "去录制", new Runnable() {
                @Override public void run() {
                    c.push(new VoicePage(c));
                }
            });
        } else {
            k.valueRow(p, "声纹", "只存本机", s.voiceprint == null ? unknown(s) : "已注册", s.voiceprint == null ? ConsoleHost.TONE_MUTED : ConsoleHost.TONE_OK);
        }
        k.footer(col, "状态全部现查：查不到就显示「未知」，不会默认显示正常。");
    }
}
