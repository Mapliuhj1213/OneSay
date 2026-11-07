package com.onesay.assistant.v2.ui.console;

import android.content.Intent;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;

/**
 * 设置 › 模型与密钥：API 密钥面板入口（{@link com.onesay.assistant.v2.ui.KeysActivity}）· 思考模式 · 账户（邮箱 IMAP）· 外观。
 */
final class AdvancedPage extends Console.BuiltPage {

    AdvancedPage(Console c) {
        super(c, "模型与密钥");
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        LinearLayout g = k.group(col, "模型");
        // OneSay：所有 API Key 统一在「API 密钥」面板（与 OneSay Clock 同款：状态 / 测试 / 去申请 / FLAG_SECURE）
        String sum;
        try {
            boolean llm = c.compiler.hasApiKey();
            boolean ds = new com.onesay.assistant.v2.asr.AliyunAsrConfig(c.act).hasDashScopeKey();
            boolean voice = new com.onesay.assistant.v2.tts.CloudTtsConfig(c.act).getVoice().length() > 0;
            sum = (llm ? "大模型 已配置" : "大模型 未配置") + " · " + (ds ? "百炼 已配置" : "百炼 未配置") + " · " + (ds && voice ? "朗读 可用" : "朗读 未配置");
        } catch (Throwable t) {
            sum = "未知";
        }
        k.linkRow(g, "API 密钥", sum, 0, new Runnable() {
            @Override public void run() {
                c.act.startActivity(new Intent(c.act, com.onesay.assistant.v2.ui.KeysActivity.class));
            }
        });
        String vm;
        try {
            vm = com.onesay.assistant.v2.vision.ScreenVisionEngine.effectiveModel(c.act);
        } catch (Throwable t) {
            vm = "未知";
        }
        k.valueRow(g, "视觉模型", "识屏 / 看图总结用（百炼 Key）", vm, ConsoleHost.TONE_NORMAL);
        k.linkRow(g, "阿里云 NLS 语音识别（高级 · 可选）", "用 AccessKey / AppKey 代替百炼识别", 0, new Runnable() {
            @Override public void run() {
                c.editAsrKey(new Runnable() {
                    @Override public void run() {
                        fill();
                    }
                });
            }
        });
        // D3：只读数值工具（电量 / 音量 / 亮度…）直接套模板回答，不再调第二轮大模型；关掉 = 原流程
        k.switchRow(g, "读数类问题直接回答", "电量、音量、亮度等，省一轮大模型",
                com.onesay.assistant.v2.router.ReadOnlyAnswer.isEnabled(c.act), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                com.onesay.assistant.v2.router.ReadOnlyAnswer.setEnabled(c.act, on);
            }
        });

        // ★ 控制台整理第二步：从「语音」页搬来，行为不变。int/1102：思考模式三档单选（默认关）。开了以后回答更稳但首字更慢；思考过程不显示、不朗读、不进对话历史
        LinearLayout gThink = k.group(col, "思考模式");
        final ConsoleKit.RowImpl thinkRow = k.actionRow(gThink, "思考模式", VoicePage.thinkSub(com.onesay.assistant.v2.net.ThinkingMode.level()),
                com.onesay.assistant.v2.net.ThinkingMode.label(com.onesay.assistant.v2.net.ThinkingMode.level()), null);
        thinkRow.button.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                final String[] keys = {"off", "agent", "all"};
                final String[] names = {"关", "仅多步任务", "全部"};
                int cur = java.util.Arrays.asList(keys).indexOf(com.onesay.assistant.v2.net.ThinkingMode.key(com.onesay.assistant.v2.net.ThinkingMode.level()));
                k.dialog().setTitle("思考模式").setSingleChoiceItems(names, Math.max(0, cur), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        com.onesay.assistant.v2.net.ThinkingMode.Level l = com.onesay.assistant.v2.router.ctx.ContextPrefs.setThinking(c.act, keys[which]);
                        thinkRow.button.setText(com.onesay.assistant.v2.net.ThinkingMode.label(l));
                        thinkRow.setSub(VoicePage.thinkSub(l));
                        d.dismiss();
                    }
                }).setNegativeButton("取消", null).show();
            }
        });

        LinearLayout acc = k.group(col, "账户");
        boolean mail;
        try {
            mail = com.onesay.assistant.v2.executor.MailBridge.hasPassword(c.act);
        } catch (Throwable t) {
            mail = false;
        }
        k.linkRow(acc, "邮箱（IMAP）", mail ? "已配置 · 说「读一下我的邮件」" : "未配置", 0, new Runnable() {
            @Override public void run() {
                c.act.startActivity(new Intent(c.act, com.onesay.assistant.v2.ui.MailSettingsActivity.class));
            }
        });

        LinearLayout look = k.group(col, "外观");
        k.switchRow(look, "浅色外观", "默认深色；灵动岛始终是黑色", !k.t.dark, new ConsoleHost.Toggle() {
            @Override public void onToggle(final boolean on) {
                // 等开关动画走完再整页换色
                c.act.getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override public void run() {
                        c.setThemeMode(on ? ConsoleTheme.MODE_LIGHT : ConsoleTheme.MODE_DARK);
                    }
                }, 220);
            }
        });

        // 控制台整理第二步：「插件 › 功能插件」与「开发者」入口搬到首页最下面的「开发者」分组（默认折叠）
        k.footer(col, "密钥只以加密形式存在本机；这里只显示末 4 位。");
    }

    /** 插件列表（只读）：每个插件的状态和开关。 */
    static final class PluginsPage extends Console.BuiltPage {
        PluginsPage(Console c) {
            super(c, "功能插件");
        }

        @Override void build(LinearLayout col) {
            LinearLayout g = c.kit.group(col, null);
            for (FeaturePlugin p : FeatureKernel.get().plugins()) {
                FeatureKernel.State st = FeatureKernel.get().state(p.id());
                boolean en;
                try {
                    en = p.enabled(c.act);
                } catch (Throwable t) {
                    en = false;
                }
                String v = st == FeatureKernel.State.FAILED ? "加载失败" : (en ? "在用" : "停用");
                int tone = st == FeatureKernel.State.FAILED ? ConsoleHost.TONE_BAD : (en ? ConsoleHost.TONE_OK : ConsoleHost.TONE_MUTED);
                String title = p.id();
                try {
                    String d = p.consoleCard();
                    if (d != null && c.devMode()) title = p.id() + " · " + d;
                } catch (Throwable ignored) {
                }
                c.kit.valueRow(g, title, null, v, tone);
            }
            c.kit.footer(col, "插件在代码里注册（kernel/BuiltinPlugins）；这里只读。各功能的开关在「设置 › 功能」里（诊断 / 摘要在「开发者」里）。");
        }
    }
}
