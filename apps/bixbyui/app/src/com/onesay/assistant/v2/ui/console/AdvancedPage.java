package com.onesay.assistant.v2.ui.console;

import android.content.Intent;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;

/**
 * ★ console-align 2026-11-12：分组改成 主模型（接口地址 · 模型名 · API Key）/ 备用模型 / 其他模型与密钥 / 发送与回答 / 思考模式 / 账户，
 * 与电脑端「模型与密钥」同名；「外观」搬到「关于」，「功能插件」搬到首页「插件」。
 * 设置 › 模型与密钥：API 密钥面板入口（{@link com.onesay.assistant.v2.ui.KeysActivity}）· 思考模式 · 账户（邮箱 IMAP）· 外观。
 */
final class AdvancedPage extends Console.BuiltPage {

    AdvancedPage(Console c) {
        super(c, "模型与密钥");
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        // ★ console-align 2026-11-12：分组与电脑端「模型与密钥」同名同序：主模型（接口地址 · 模型名 · API Key）→ 备用模型 → 其他模型与密钥 → 发送与回答 → 思考模式 → 账户。
        //   「外观」搬到「关于」页；开关和 prefs 键都不变。所有 Key 仍统一在「API 密钥」面板（KeysActivity）里填。
        LinearLayout g = k.group(col, "主模型");
        k.linkRow(g, "接口地址", "OpenAI 兼容；留空 = 默认 DeepSeek · " + com.onesay.assistant.v2.net.MainModelEndpoint.summary(), 0, new Runnable() {
            @Override public void run() {
                editMainUrl();
            }
        });
        String cm = com.onesay.assistant.v2.net.LlmModels.chat();
        k.linkRow(g, "模型名", com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT.equals(cm) ? "留空 = 默认 " + cm : cm, 0, new Runnable() {
            @Override public void run() {
                editMainModel();
            }
        });
        boolean llm, ds, voice;
        try {
            llm = c.compiler.hasApiKey();
            ds = new com.onesay.assistant.v2.asr.AliyunAsrConfig(c.act).hasDashScopeKey();
            voice = new com.onesay.assistant.v2.tts.CloudTtsConfig(c.act).getVoice().length() > 0;
        } catch (Throwable t) {
            llm = ds = voice = false;
        }
        final Runnable keys = new Runnable() {
            @Override public void run() {
                c.act.startActivity(new Intent(c.act, com.onesay.assistant.v2.ui.KeysActivity.class));
            }
        };
        // OneSay：所有 API Key 统一在「API 密钥」面板（与 OneSay Clock 同款：状态 / 测试 / 去申请 / FLAG_SECURE）；这里两行都进那一页
        k.linkRow(g, "API Key", (llm ? "已配置" : "未配置") + " · 在「API 密钥」面板里填 / 测试", 0, keys);
        // ★ borrow-1112：备用模型（主模型超时 / 服务出错 / 限流时自动换；没配置 = 照旧报错）
        LinearLayout gb = k.group(col, "备用模型");
        k.linkRow(gb, "备用模型", new com.onesay.assistant.v2.net.BackupModelConfig(c.act).summary(), 0, new Runnable() {
            @Override public void run() {
                editBackup();
            }
        });

        LinearLayout go = k.group(col, "其他模型与密钥");
        String vm;
        try {
            vm = com.onesay.assistant.v2.vision.ScreenVisionEngine.effectiveModel(c.act);
        } catch (Throwable t) {
            vm = "未知";
        }
        k.valueRow(go, "视觉模型", "识屏 / 看图总结用（百炼 Key）", vm, ConsoleHost.TONE_NORMAL);
        k.linkRow(go, "语音识别 Key", (ds ? "百炼 已配置" : "百炼 未配置") + " · " + (ds && voice ? "朗读 可用" : "朗读 未配置") + " · 在「API 密钥」面板里填", 0, keys);
        k.linkRow(go, "阿里云 NLS 语音识别（高级 · 可选）", "用 AccessKey / AppKey 代替百炼识别", 0, new Runnable() {
            @Override public void run() {
                c.editAsrKey(new Runnable() {
                    @Override public void run() {
                        fill();
                    }
                });
            }
        });

        LinearLayout gs = k.group(col, "发送与回答");
        // ★ borrow-1112：发给云端模型前本地隐藏个人信息（默认开）
        k.switchRow(gs, "发给云端前隐藏个人信息", "手机号、身份证号、银行卡号、邮箱、验证码换成代号再发，要输入时在本机换回",
                com.onesay.assistant.v2.privacy.PrivacySettings.REDACT.on(c.act), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                com.onesay.assistant.v2.privacy.PrivacySettings.REDACT.set(c.act, on);
            }
        });
        // D3：只读数值工具（电量 / 音量 / 亮度…）直接套模板回答，不再调第二轮大模型；关掉 = 原流程
        k.switchRow(gs, "读数类问题直接回答", "电量、音量、亮度等，省一轮大模型",
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

        // 控制台整理第二步：「插件 › 功能插件」搬到首页「插件」（console-align），「开发者」入口在首页最下面（默认折叠）
        k.footer(col, "密钥只以加密形式存在本机；这里只显示末 4 位。接口地址只接受 https://（调试包也接受 http://，方便测试切换备用模型）；日志里只记主机名。");
    }

    /** ★ console-align：主模型接口地址（OpenAI 兼容 base，留空 = 默认 DeepSeek）。 */
    void editMainUrl() {
        final ConsoleKit k = c.kit;
        final boolean dbg = com.onesay.assistant.v2.net.MainModelEndpoint.debuggable(c.act);
        LinearLayout box = k.column();
        box.setPadding(k.dp(24), k.dp(8), k.dp(24), 0);
        box.addView(k.text("填到 /v1 为止（也可以整条 …/chat/completions）；留空 = 默认 " + com.onesay.assistant.v2.net.MainModelEndpoint.DEFAULT_BASE
                + "。只接受 https://" + (dbg ? "（调试包也接受 http://）" : "") + "。换了地址，记得把模型名和 API Key 换成那一家的。",
                ConsoleTheme.SP_SUB + 1, k.t.text2, ConsoleKit.W_REGULAR));
        final android.widget.EditText url = field(com.onesay.assistant.v2.net.MainModelEndpoint.isDefault() ? "" : com.onesay.assistant.v2.net.MainModelEndpoint.base(),
                com.onesay.assistant.v2.net.MainModelEndpoint.DEFAULT_BASE, false);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(url, k.lpMatch(8, 0));
        k.dialog().setTitle("主模型接口地址").setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        com.onesay.assistant.v2.net.MainModelEndpoint.Check r = com.onesay.assistant.v2.net.MainModelEndpoint.save(c.act, url.getText().toString());
                        if (!r.ok) {
                            c.toast(r.error);
                            return;
                        }
                        android.util.Log.i("L2LLMCompiler", com.onesay.assistant.v2.net.MainModelEndpoint.logLine());   // 只记主机名
                        c.toast("已保存：" + com.onesay.assistant.v2.net.MainModelEndpoint.summary());
                        fill();
                    }
                })
                .setNeutralButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        com.onesay.assistant.v2.net.MainModelEndpoint.save(c.act, "");
                        c.toast("已恢复默认（DeepSeek）");
                        fill();
                    }
                }).setNegativeButton("取消", null).show();
    }

    /** ★ console-align：主模型名（存既有的 bixby_conv / chat_model，留空 = 默认）。 */
    void editMainModel() {
        final ConsoleKit k = c.kit;
        LinearLayout box = k.column();
        box.setPadding(k.dp(24), k.dp(8), k.dp(24), 0);
        box.addView(k.text("留空 = 默认 " + com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT + "。用别家接口时填那一家的模型名。",
                ConsoleTheme.SP_SUB + 1, k.t.text2, ConsoleKit.W_REGULAR));
        String cur = com.onesay.assistant.v2.net.LlmModels.chat();
        final android.widget.EditText model = field(com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT.equals(cur) ? "" : cur,
                com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT, false);
        box.addView(model, k.lpMatch(8, 0));
        k.dialog().setTitle("主模型名").setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String m = model.getText().toString().trim();
                        String bad = com.onesay.assistant.v2.net.MainModelEndpoint.checkModel(m);
                        if (bad != null) {
                            c.toast(bad);
                            return;
                        }
                        com.onesay.assistant.v2.router.ctx.ContextPrefs.setChatModel(c.act, m);
                        c.toast("已保存：" + com.onesay.assistant.v2.net.LlmModels.chat());
                        fill();
                    }
                }).setNegativeButton("取消", null).show();
    }

    /** ★ borrow-1112：备用模型选择：关 / 通义千问（百炼）/ 自定义 OpenAI 兼容端点。Key 输入框永远是空的（同 DeepSeek Key 规矩）。 */
    void editBackup() {
        final ConsoleKit k = c.kit;
        final com.onesay.assistant.v2.net.BackupModelConfig cfg = new com.onesay.assistant.v2.net.BackupModelConfig(c.act);
        final String[] ids = {com.onesay.assistant.v2.net.BackupModelConfig.P_OFF, com.onesay.assistant.v2.net.BackupModelConfig.P_DASHSCOPE, com.onesay.assistant.v2.net.BackupModelConfig.P_CUSTOM};
        final String[] names = {"不用备用模型", "通义千问（百炼，默认用语音识别同一把 Key）", "自定义（OpenAI 兼容地址）"};
        int cur = java.util.Arrays.asList(ids).indexOf(cfg.provider());
        k.dialog().setTitle("备用模型").setSingleChoiceItems(names, Math.max(0, cur), new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int which) {
                d.dismiss();
                if (which == 0) {
                    cfg.save(ids[0], "", "", null);
                    c.toast("已关闭备用模型");
                    fill();
                    return;
                }
                editBackupDetail(cfg, ids[which]);
            }
        }).setNegativeButton("取消", null).show();
    }

    private void editBackupDetail(final com.onesay.assistant.v2.net.BackupModelConfig cfg, final String provider) {
        final ConsoleKit k = c.kit;
        final boolean custom = com.onesay.assistant.v2.net.BackupModelConfig.P_CUSTOM.equals(provider);
        LinearLayout box = k.column();
        box.setPadding(k.dp(24), k.dp(8), k.dp(24), 0);
        final android.widget.EditText url = field(custom ? cfg.sp().getString(com.onesay.assistant.v2.net.BackupModelConfig.K_URL, "") : "", "https://…/chat/completions", false);
        final android.widget.EditText model = field(provider.equals(cfg.provider()) ? cfg.sp().getString(com.onesay.assistant.v2.net.BackupModelConfig.K_MODEL, "") : "",
                custom ? "模型名" : "模型名（留空 = " + com.onesay.assistant.v2.net.BackupModelConfig.DASHSCOPE_MODEL + "）", false);
        final android.widget.EditText key = field("", "Key（留空 = 不改" + (custom ? "" : "，没填过就用语音识别 Key") + "）", true);
        box.addView(k.text("当前 Key：" + KeyMask.mask(cfg.ownKey()), ConsoleTheme.SP_SUB + 1, k.t.text2, ConsoleKit.W_REGULAR));
        if (custom) box.addView(url, k.lpMatch(8, 0));
        box.addView(model, k.lpMatch(8, 0));
        box.addView(key, k.lpMatch(8, 0));
        k.dialog().setTitle(custom ? "自定义备用模型" : "通义千问（百炼）").setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String u = url.getText().toString().trim();
                        if (custom && !u.startsWith("https://")) {
                            c.toast("地址要以 https:// 开头");
                            return;
                        }
                        String kk = key.getText().toString();
                        cfg.save(provider, u, model.getText().toString(), KeyMask.shouldSave(kk) ? kk.trim() : null);
                        c.toast("已保存：" + cfg.summary());
                        fill();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private android.widget.EditText field(String text, String hint, boolean secret) {
        android.widget.EditText et = new android.widget.EditText(c.act);
        et.setSingleLine(true);
        if (secret) et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setText(text);
        et.setHint(hint);
        et.setTextColor(c.kit.t.text1);
        et.setHintTextColor(c.kit.t.text3);
        return et;
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
            c.kit.footer(col, "插件在代码里注册（kernel/BuiltinPlugins）；这里只读。各功能的开关在「设置」各页里（连接 / 替你操作 / 功能；诊断 / 摘要在「开发者」里）。");
        }
    }
}
