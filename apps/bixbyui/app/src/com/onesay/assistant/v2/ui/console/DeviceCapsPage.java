package com.onesay.assistant.v2.ui.console;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.platform.DeviceCap;
import com.onesay.assistant.v2.platform.DeviceCapabilityProbe;
import com.onesay.assistant.v2.platform.DeviceProfile;
import com.onesay.assistant.v2.platform.FeatureAvailability;
import com.onesay.assistant.v2.platform.FeatureRegistry;
import com.onesay.assistant.v2.platform.FeatureSpec;
import com.onesay.assistant.v2.platform.Tri;

import java.util.List;
import java.util.Map;

/**
 * [refactor/oss 2026-11-06] 关于 › 设备能力与功能：按 {@link FeatureRegistry} 生成。
 *
 * <ul>
 *   <li>上半部分：探到的设备能力（root、按键守护、Shizuku、无障碍、厂商、Android 版本……），每项三态；</li>
 *   <li>下半部分：每个功能一行。有开关的绑定到原来的 prefs 键（与原设置页同一个值、默认值不变）；
 *       这台设备上做不到的置灰并写明原因（「需要 root」「仅三星」），以前因系统限制没做的功能也列出、永远置灰。</li>
 * </ul>
 * 页面只读写原有开关，不改任何默认值；拨开关走 {@link DeviceCapsToggles}（与原设置页同一组 setter 和副作用）；能力「未确认」时不置灰。
 */
final class DeviceCapsPage extends Console.BuiltPage {

    private boolean mProbing, mAutoProbed;

    DeviceCapsPage(Console c) {
        super(c, "设备能力与功能");
    }

    @Override String rightAction() {
        return "重新检测";
    }

    @Override void onRight() {
        reprobe();
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        DeviceProfile p = DeviceCapabilityProbe.cached(c.act);
        if (p == null && !mProbing && !mAutoProbed) {
            mAutoProbed = true;   // 只自动探一次；探不出来就停在「检测中」，用右上角「重新检测」再试
            reprobe();
        }

        LinearLayout dev = k.group(col, "这台设备");
        if (p == null) {
            k.valueRow(dev, "检测中", null, "…", ConsoleHost.TONE_MUTED);
        } else {
            k.valueRow(dev, "机型", null, p.summary(), ConsoleHost.TONE_NORMAL);
            for (DeviceCap cap : DeviceCap.values()) {
                Tri t = p.get(cap);
                k.valueRow(dev, cap.title, t == Tri.NO ? "替代：" + cap.alternative : null, label(t), tone(t));
            }
        }

        addEntries(col);
        addExecBackends(col);
        addWakeWords(col);
        addPrivacy(col);

        Map<String, List<FeatureSpec>> groups = FeatureRegistry.byGroup();
        for (Map.Entry<String, List<FeatureSpec>> e : groups.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            LinearLayout g = k.group(col, e.getKey());
            for (FeatureSpec f : e.getValue()) addFeatureRow(g, f, p);
        }
        k.footer(col, "置灰的功能在这台设备上做不到，原因写在下面；「未确认」表示还没探到，不影响使用。"
                + "开关和原设置页是同一个，默认值不变。");
    }

    private void addFeatureRow(LinearLayout g, final FeatureSpec f, DeviceProfile p) {
        final ConsoleKit k = c.kit;
        FeatureAvailability a = FeatureAvailability.evaluate(f, p);
        String reason = a.reason();
        String sub = reason;
        if (f.status == FeatureSpec.Status.DEBUG_ONLY) sub = sub == null ? "只在 debug 包" : sub + " · 只在 debug 包";
        if (!a.switchEnabled() && f.alternative != null) sub = (sub == null ? "" : sub + "\n") + "替代：" + f.alternative;
        int subTone = a.state == FeatureAvailability.State.AVAILABLE ? ConsoleHost.TONE_NORMAL
                : (a.state == FeatureAvailability.State.UNKNOWN ? ConsoleHost.TONE_MUTED : ConsoleHost.TONE_WARN);

        if (f.hasSwitch()) {
            boolean killed = false;
            for (String kf : f.killFiles) killed |= FeatureSwitch.killFilePresent(c.act, kf);
            final SharedPreferences sp = c.act.getApplicationContext().getSharedPreferences(f.prefs, Context.MODE_PRIVATE);
            boolean on = !killed && sp.getBoolean(f.key, f.def);
            ConsoleKit.RowImpl r = k.switchRow(g, f.title, killed ? "关停文件存在 → 已停用" : sub, on, new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean v) {
                    // 复用原设置页的 setter 与即时副作用（见 DeviceCapsToggles），不只写 prefs
                    DeviceCapsToggles.apply(c.act, f, v);
                }
            });
            r.setSubTone(killed ? ConsoleHost.TONE_WARN : subTone);
            if (killed || !a.switchEnabled()) r.setEnabled(false);
        } else if (f.status == FeatureSpec.Status.NOT_BUILT) {
            ConsoleKit.RowImpl r = k.switchRow(g, f.title, sub, false, null);
            r.setSubTone(ConsoleHost.TONE_WARN);
            r.setEnabled(false);
        } else {
            ConsoleKit.RowImpl r = k.valueRow(g, f.title, sub, stateLabel(a.state), stateTone(a.state));
            r.setSubTone(subTone);
        }
    }

    /** 唤起入口（EntryTrigger 注册表）：每个入口一行开关；做不到的置灰并写明原因。 */
    private void addEntries(LinearLayout parent) {
        final ConsoleKit k = c.kit;
        com.onesay.assistant.v2.entry.Entries.ensureInit(c.act);
        LinearLayout g = k.group(parent, "唤起入口");
        for (final com.onesay.plugin.api.entry.EntryTrigger t : com.onesay.plugin.api.entry.EntryRegistry.all()) {
            com.onesay.plugin.api.entry.EntryTrigger.Tri a = com.onesay.plugin.api.entry.EntryRegistry.availability(t.id());
            String why = a == com.onesay.plugin.api.entry.EntryTrigger.Tri.YES ? null : t.unavailableReason(com.onesay.plugin.api.entry.EntryRegistry.env());
            String sub = t.description() + (why == null ? "" : "\n" + why);
            ConsoleKit.RowImpl r = k.switchRow(g, t.label(), sub, com.onesay.plugin.api.entry.EntryRegistry.isEnabled(t.id()), new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean v) {
                    com.onesay.assistant.v2.entry.Entries.setEnabled(c.act, t.id(), v);
                    if (col != null) fill();
                }
            });
            r.setSubTone(a == com.onesay.plugin.api.entry.EntryTrigger.Tri.NO ? ConsoleHost.TONE_WARN : ConsoleHost.TONE_MUTED);
            if (a == com.onesay.plugin.api.entry.EntryTrigger.Tri.NO) r.setEnabled(false);
        }
        addKeyWake(parent);
        k.footer(parent, "关掉的入口触发一律丢弃。外部 App 唤起默认关；唤醒词改动后重启唤醒服务生效。"
                + "接入自定义入口见 README「如何接入自定义入口」。");
    }

    /** 按键唤醒的触发方式 / 动作 / 时间窗，以及快捷开关、小部件、快捷方式点一下做什么。 */
    private void addKeyWake(LinearLayout parent) {
        final ConsoleKit k = c.kit;
        final Context app = c.act.getApplicationContext();
        LinearLayout g = k.group(parent, com.onesay.assistant.v2.entry.keywake.KeyWake.LABEL);
        final com.onesay.assistant.v2.entry.keywake.KeyWake.Trigger[] ts = com.onesay.assistant.v2.entry.keywake.KeyWake.Trigger.values();
        final String[] tl = new String[ts.length];
        for (int i = 0; i < ts.length; i++) tl[i] = ts[i].label;
        final com.onesay.assistant.v2.entry.keywake.KeyWake.Trigger curT = com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.trigger(app);
        k.actionRow(g, "触发方式", "默认双击电源键（按熄屏 / 亮屏判定）。很多手机把双击电源键给了相机，请先在系统设置里关掉", curT.label, new Runnable() {
            @Override public void run() {
                k.dialog().setTitle("触发方式").setSingleChoiceItems(tl, curT.ordinal(), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        d.dismiss();
                        com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.setTrigger(app, ts[w]);
                        com.onesay.assistant.v2.a11y.A11yBackend.refreshKeyWake();
                        if (col != null) fill();
                    }
                }).setNegativeButton("取消", null).show();
            }
        });
        actionChooser(g, "按下后", "默认语音唤起；也可以直接识屏或只开文字面板", null);
        final int[] wins = {400, 500, 600, 800, 1000};
        final String[] wl = {"400 ms", "500 ms", "600 ms（默认）", "800 ms", "1000 ms"};
        final int curW = com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.windowMs(app);
        k.actionRow(g, "双击间隔", "两次按电源键之间最多隔多久", curW + " ms", new Runnable() {
            @Override public void run() {
                int sel = 2;
                for (int i = 0; i < wins.length; i++) if (wins[i] == curW) sel = i;
                k.dialog().setTitle("双击间隔").setSingleChoiceItems(wl, sel, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        d.dismiss();
                        com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.setWindowMs(app, wins[w]);
                        com.onesay.assistant.v2.a11y.A11yBackend.refreshKeyWake();
                        if (col != null) fill();
                    }
                }).setNegativeButton("取消", null).show();
            }
        });
        actionChooser(g, "快捷开关点一下", "语音唤起 / 识屏 / 文字面板", com.onesay.assistant.v2.entry.BuiltinEntries.QS_TILE);
        actionChooser(g, "小部件点一下", "语音唤起 / 识屏 / 文字面板", com.onesay.assistant.v2.entry.BuiltinEntries.WIDGET);
    }

    /** entryId == null = 按键唤醒本身的动作。 */
    private void actionChooser(LinearLayout g, final String title, String sub, final String entryId) {
        final ConsoleKit k = c.kit;
        final Context app = c.act.getApplicationContext();
        final com.onesay.assistant.v2.entry.keywake.KeyWake.Action[] as = com.onesay.assistant.v2.entry.keywake.KeyWake.Action.values();
        final String[] al = new String[as.length];
        for (int i = 0; i < as.length; i++) al[i] = as[i].label;
        final com.onesay.assistant.v2.entry.keywake.KeyWake.Action cur = entryId == null
                ? com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.action(app)
                : com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.entryAction(app, entryId);
        k.actionRow(g, title, sub, cur.label, new Runnable() {
            @Override public void run() {
                k.dialog().setTitle(title).setSingleChoiceItems(al, cur.ordinal(), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        d.dismiss();
                        if (entryId == null) com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.setAction(app, as[w]);
                        else com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.setEntryAction(app, entryId, as[w]);
                        if (col != null) fill();
                    }
                }).setNegativeButton("取消", null).show();
            }
        });
    }

    /** 不经用户配置就会发生的外部请求：一律默认关，在这里打开。 */
    private void addPrivacy(LinearLayout parent) {
        final ConsoleKit k = c.kit;
        final Context app = c.act.getApplicationContext();
        LinearLayout g = k.group(parent, "隐私与外部请求");
        k.actionRow(g, "从天气 App 读城市", "打开你手机上的天气 App，经无障碍读它首屏显示的城市，当作常用城市的来源（只在你点的时候读一次）",
                "读取", new Runnable() {
                    @Override public void run() {
                        com.onesay.assistant.v2.util.Threads.start("city-screen", new Runnable() {
                            @Override public void run() {
                                final String msg = com.onesay.assistant.v2.city.WeatherAppCity.capture(app);
                                c.act.runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        android.widget.Toast.makeText(c.act, msg, android.widget.Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        });
                    }
                });
        k.switchRow(g, "联网反查所在城市", "问天气没说城市、也没手动设常用城市时，把约 1 km 精度的位置发给 BigDataCloud / OpenStreetMap Nominatim 反查城市名。默认关",
                com.onesay.assistant.v2.city.HomeCityProvider.isNetReverseEnabled(app), new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean v) { com.onesay.assistant.v2.city.HomeCityProvider.setNetReverseEnabled(app, v); }
                });
        final android.content.SharedPreferences rp = app.getSharedPreferences(com.onesay.assistant.v2.roles.AndroidInstallEnv.PREFS, Context.MODE_PRIVATE);
        String vpn = rp.getString(com.onesay.assistant.v2.roles.AndroidInstallEnv.K_VPN_PKG, "");
        k.actionRow(g, "装国外 App 时可打开的代理 App", vpn.length() == 0 ? "未填写：Play 连不上时只退回其他商店" : vpn, "编辑", new Runnable() {
            @Override public void run() {
                final android.widget.EditText e = new android.widget.EditText(c.act);
                e.setSingleLine(true);
                e.setHint("包名，例如 com.example.vpn；留空 = 不打开");
                e.setText(rp.getString(com.onesay.assistant.v2.roles.AndroidInstallEnv.K_VPN_PKG, ""));
                new android.app.AlertDialog.Builder(c.act).setTitle("代理 App 包名")
                        .setMessage("Play 商店连不上且当前没有 VPN 时，先打开这个 App 再复测；已有 VPN 时只给按钮，不会自动切。")
                        .setView(e).setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                com.onesay.assistant.v2.roles.InstallLinkPlanner.setVpnPackage(e.getText().toString());
                                rp.edit().putString(com.onesay.assistant.v2.roles.AndroidInstallEnv.K_VPN_PKG,
                                        com.onesay.assistant.v2.roles.InstallLinkPlanner.vpnPackage()).apply();
                                if (col != null) fill();
                            }
                        }).setNegativeButton("取消", null).show();
            }
        });
        k.footer(parent, "OneSay 没有统计 / 遥测。其余联网都由你发起（提问、搜索、识别、朗读），只发往你在「API 密钥」里配置的服务或你问到的网站。");
    }

    /** OneSay 开源版：特权执行后端（插件，可选，默认不选）。只能选一个；选中且可用时快捷指令里的特权动作才点亮。 */
    private void addExecBackends(LinearLayout parent) {
        final ConsoleKit k = c.kit;
        com.onesay.assistant.v2.platform.ExecBackendHost.ensureInit(c.act);
        LinearLayout g = k.group(parent, "执行后端（特权动作）");
        final String sel = com.onesay.plugin.api.exec.ExecBackends.selected();
        for (final com.onesay.plugin.api.exec.ExecBackend b : com.onesay.plugin.api.exec.ExecBackends.all()) {
            com.onesay.plugin.api.exec.ExecBackend.Tri t = b.available();
            if (com.onesay.plugin.exec.shizuku.ShizukuExecBackend.ID.equals(b.id())) {
                addShizukuRow(g, b, t);   // ★ priv-llm：Shizuku 是独立插件开关（中间档），不占「选一个后端」
                continue;
            }
            String sub = (b.level() == com.onesay.plugin.api.exec.ExecBackend.UidLevel.ROOT ? "root 身份" : "shell 身份（uid 2000）")
                    + " · " + (t == com.onesay.plugin.api.exec.ExecBackend.Tri.YES ? "可用"
                    : (t == com.onesay.plugin.api.exec.ExecBackend.Tri.NO ? "不可用" : "未确认"))
                    + (b.reason() == null ? "" : "\n" + b.reason());
            ConsoleKit.RowImpl r = k.switchRow(g, b.title(), sub, b.id().equals(sel), new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean v) {
                    com.onesay.assistant.v2.platform.ExecBackendHost.select(c.act, v ? b.id() : "");
                    if (col != null) fill();
                }
            });
            r.setSubTone(t == com.onesay.plugin.api.exec.ExecBackend.Tri.YES ? ConsoleHost.TONE_OK : ConsoleHost.TONE_MUTED);
        }
        k.footer(parent, "默认不选任何后端：需要 root / shell 身份的动作保持置灰。su 直连没有执行守护的命令白名单，只靠确认卡把关。"
                + "「Shizuku 特权」默认关：打开后在没有 root 时，只把白名单里的命令（后台读剪贴板授权、通知使用权、模拟按键、截屏）交给 Shizuku。");
    }

    /** ★ priv-llm：「Shizuku 特权」插件开关（默认关）。打开时 Shizuku 在跑但没授权会弹 Shizuku 的授权框。 */
    private void addShizukuRow(LinearLayout g, final com.onesay.plugin.api.exec.ExecBackend b, com.onesay.plugin.api.exec.ExecBackend.Tri t) {
        final ConsoleKit k = c.kit;
        boolean on = com.onesay.assistant.v2.platform.ExecBackendHost.shizukuEnabled(c.act);
        String sub = "中间档：普通权限与 root 之间 · " + (t == com.onesay.plugin.api.exec.ExecBackend.Tri.YES
                ? (b.level() == com.onesay.plugin.api.exec.ExecBackend.UidLevel.ROOT ? "可用（root 启动）" : "可用（shell 身份）")
                : (t == com.onesay.plugin.api.exec.ExecBackend.Tri.NO ? "不可用" : "未确认"))
                + (b.reason() == null ? "" : "\n" + b.reason())
                + "\n只执行白名单命令：后台读剪贴板授权 · 通知使用权 · 模拟按键 · 截屏";
        ConsoleKit.RowImpl r = k.switchRow(g, "Shizuku 特权（可选插件）", sub, on, new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean v) {
                com.onesay.assistant.v2.platform.ExecBackendHost.setShizukuEnabled(c.act, v);
                if (col != null) fill();
            }
        });
        r.setSubTone(t == com.onesay.plugin.api.exec.ExecBackend.Tri.YES ? ConsoleHost.TONE_OK : ConsoleHost.TONE_MUTED);
        if (!on || t != com.onesay.plugin.api.exec.ExecBackend.Tri.YES) return;
        final String pkg = c.act.getPackageName();
        k.actionRow(g, "授予后台读剪贴板", "电脑联动同步剪贴板用（appops READ_CLIPBOARD）", "授予", new Runnable() {
            @Override public void run() {
                runShizuku(com.onesay.assistant.v2.platform.ShizukuActions.clipboardGrant(pkg), "后台读剪贴板");
            }
        });
        k.actionRow(g, "打开通知使用权", "放开受限设置并允许本 App 的通知监听", "打开", new Runnable() {
            @Override public void run() {
                runShizuku(com.onesay.assistant.v2.platform.ShizukuActions.notificationAccess(pkg, android.os.Build.VERSION.SDK_INT), "通知使用权");
            }
        });
    }

    private void runShizuku(final java.util.List<String> cmds, final String what) {
        final android.content.Context app = c.act.getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("shizuku-grant", new Runnable() {
            @Override public void run() {
                String fail = null;
                com.onesay.assistant.v2.executor.ShellExecutor ex = new com.onesay.assistant.v2.executor.ShellExecutor(app);
                for (String cmd : cmds) {
                    com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r = ex.execute(cmd);
                    if (!r.isSuccess()) { fail = r.isDenied ? r.denyReason : (r.output.length() > 80 ? r.output.substring(0, 80) : r.output); break; }
                }
                final String msg = fail == null ? what + "：已完成" : what + "：失败（" + fail + "）";
                c.act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        android.widget.Toast.makeText(c.act, msg, android.widget.Toast.LENGTH_LONG).show();
                        if (col != null) fill();
                    }
                });
            }
        });
    }

    /** OneSay 开源版：本地唤醒词（可选，默认关；不内置任何唤醒词，用户自己填 token 序列）。 */
    private void addWakeWords(LinearLayout parent) {
        final ConsoleKit k = c.kit;
        final Context app = c.act.getApplicationContext();
        LinearLayout g = k.group(parent, "本地唤醒词（可选）");
        final String words = com.onesay.assistant.v2.wake.WakeWordSettings.getLines(app);
        boolean on = com.onesay.assistant.v2.wake.WakeWordSettings.isEnabled(app);
        k.switchRow(g, "启用本地唤醒词", "默认关；入口是按钮 / 默认数字助理。需要唤醒词模型（见 README）", on, new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean v) {
                com.onesay.assistant.v2.wake.WakeWordSettings.setEnabled(app, v);
            }
        });
        k.actionRow(g, "我的唤醒词", words.length() == 0 ? "还没填写" : words.replace('\n', '；'), "编辑", new Runnable() {
            @Override public void run() {
                final android.widget.EditText e = new android.widget.EditText(c.act);
                e.setText(com.onesay.assistant.v2.wake.WakeWordSettings.getLines(app));
                e.setHint("每行一条 token 序列，例如：HH EY1 W AH1 N S EY1 @HEY_ONESAY");
                e.setMinLines(3);
                new android.app.AlertDialog.Builder(c.act).setTitle("我的唤醒词")
                        .setMessage("中文用带声调拼音 token，英文用 CMU 音素；每个 token 都必须在模型的 tokens.txt 里。重启唤醒服务后生效。")
                        .setView(e).setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                String err = com.onesay.assistant.v2.wake.WakeWordSettings.saveLines(app, e.getText().toString());
                                if (err != null) {
                                    android.widget.Toast.makeText(c.act, "没保存：" + err, android.widget.Toast.LENGTH_LONG).show();
                                    return;
                                }
                                if (col != null) fill();
                            }
                        }).setNegativeButton("取消", null).show();
            }
        });
    }

    private void reprobe() {
        mProbing = true;
        DeviceCapabilityProbe.probeAsync(c.act, new Runnable() {
            @Override public void run() {
                c.ui(new Runnable() {
                    @Override public void run() {
                        mProbing = false;
                        if (col != null) fill();
                    }
                });
            }
        });
    }

    static String label(Tri t) {
        return t == Tri.YES ? "有" : (t == Tri.NO ? "没有" : "未确认");
    }

    static int tone(Tri t) {
        return t == Tri.YES ? ConsoleHost.TONE_OK : (t == Tri.NO ? ConsoleHost.TONE_WARN : ConsoleHost.TONE_MUTED);
    }

    static String stateLabel(FeatureAvailability.State s) {
        switch (s) {
            case AVAILABLE: return "可用";
            case NEEDS_GRANT: return "待授权";
            case UNKNOWN: return "未确认";
            case UNAVAILABLE: return "不可用";
            default: return "未实现";
        }
    }

    static int stateTone(FeatureAvailability.State s) {
        switch (s) {
            case AVAILABLE: return ConsoleHost.TONE_OK;
            case NEEDS_GRANT: return ConsoleHost.TONE_WARN;
            case UNKNOWN: return ConsoleHost.TONE_MUTED;
            default: return ConsoleHost.TONE_BAD;
        }
    }
}
