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

    /**
     * ★ settings-diag-1124：一页拆成两页，不再在两个入口看到同一堆东西。
     * MODE_WAKE =「基础设置 › 唤起方式」（唤起入口 + 按键唤醒细项 + 我的唤醒词）；
     * MODE_DEVICE =「高级 › 设备能力与执行后端」（机型、执行后端、隐私与外部请求、功能在这台手机上能不能用）。
     */
    static final int MODE_WAKE = 1, MODE_DEVICE = 2;
    final int mode;

    DeviceCapsPage(Console c) {
        this(c, MODE_WAKE);
    }

    DeviceCapsPage(Console c, int mode) {
        super(c, mode == MODE_DEVICE ? "设备能力与执行后端" : "唤起方式");
        this.mode = mode;
    }

    @Override String rightAction() {
        return mode == MODE_DEVICE ? "重新检测" : null;
    }

    @Override void onRight() {
        reprobe();
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        if (mode == MODE_WAKE) {
            addEntries(col);
            return;
        }
        DeviceProfile p = DeviceCapabilityProbe.cached(c.act);
        if (p == null && !mProbing && !mAutoProbed) {
            mAutoProbed = true;   // 只自动探一次；探不出来就停在「检测中」，用右上角「重新检测」再试
            reprobe();
        }

        // ★ settings-diag-1124：「这台设备」只留机型 + 一行权限汇总。删掉的：Android 8.0+ / 11+ / 14+ / arm64 CPU 这类「有 / 没有」
        //   （用户改不了、也不用看；真影响到哪个功能，那个功能的开关下面会写缺什么）；悬浮窗 / 麦克风 / 无障碍 / 通知使用权 / 默认助理这些
        //   权限行并进「权限配置」（同一份状态，这里不再重复一遍）；root / 执行守护 / Shizuku 在下面「执行后端」里。
        LinearLayout dev = k.group(col, "这台设备");
        if (p == null) {
            k.valueRow(dev, "检测中", null, "…", ConsoleHost.TONE_MUTED);
        } else {
            k.valueRow(dev, "机型", null, p.summary(), ConsoleHost.TONE_NORMAL);
        }
        k.linkRow(dev, "权限", "悬浮窗 · 麦克风 · 无障碍 · 通知 · 默认助理，都在「权限配置」里一步步开", 0, new Runnable() {
            @Override public void run() {
                c.push(new PermSetupPage(c));
            }
        });

        addExecBackends(col);
        addPrivacy(col);

        // 功能在这台手机上能不能用：只列状态和缺什么（开关在「插件」里，这里不再放第二个同样的开关）；
        // 「唤醒入口」那组在「唤起方式」页；「受限未实现」那组（永远置灰）对用户没用，不显示。
        Map<String, List<FeatureSpec>> groups = FeatureRegistry.byGroup();
        LinearLayout g = k.group(col, "功能在这台手机上能不能用");
        for (Map.Entry<String, List<FeatureSpec>> e : groups.entrySet()) {
            if (FeatureRegistry.G_WAKE.equals(e.getKey()) || FeatureRegistry.G_NOT_BUILT.equals(e.getKey())) continue;
            for (FeatureSpec f : e.getValue()) addFeatureRow(g, f, p);
        }
        Console.BuiltPage.dropIfEmpty(col, g);
        k.footer(col, "「待授权」去「权限配置」打开对应权限；「不可用」是这台手机缺的条件补不上。功能开关都在「插件」里。");
    }

    /** ★ settings-diag-1124：缺的能力写成「缺 root、无障碍服务」（不写路径、不写系统接口名）。 */
    static String missingText(FeatureAvailability a) {
        if (a == null || a.missing == null || a.missing.isEmpty()) return null;
        StringBuilder b = new StringBuilder("缺 ");
        for (int i = 0; i < a.missing.size(); i++) {
            if (i > 0) b.append("、");
            b.append(a.missing.get(i).title);
        }
        return b.toString();
    }

    private void addFeatureRow(LinearLayout g, final FeatureSpec f, DeviceProfile p) {
        final ConsoleKit k = c.kit;
        if (f.status == FeatureSpec.Status.NOT_BUILT) return;
        FeatureAvailability a = FeatureAvailability.evaluate(f, p);
        String sub = missingText(a);
        if (a.state == FeatureAvailability.State.NEEDS_GRANT && sub != null) sub = sub + "，去「权限配置」打开";
        if (f.status == FeatureSpec.Status.DEBUG_ONLY) sub = sub == null ? "只在调试包" : sub + " · 只在调试包";
        ConsoleKit.RowImpl r = k.valueRow(g, plainTitle(f.title), sub, stateLabel(a.state), stateTone(a.state));
        r.setSubTone(a.state == FeatureAvailability.State.AVAILABLE ? ConsoleHost.TONE_NORMAL
                : (a.state == FeatureAvailability.State.UNKNOWN ? ConsoleHost.TONE_MUTED : ConsoleHost.TONE_WARN));
    }

    /** 功能名去掉括号里的技术说明（「识屏（截图 + 界面树问答）」→「识屏」）。 */
    static String plainTitle(String t) {
        if (t == null) return "";
        int i = t.indexOf('（');
        return i > 0 ? t.substring(0, i) : t;
    }

    /**
     * 唤起入口（EntryTrigger 注册表）：每个入口一行开关。
     * ★ settings-diag-1124：开关反映真实状态、写明还差哪步 —— 和聊天里「为什么 X 用不了」读的是同一份清单（SelfCatalog / SelfSettingsEngine）：
     * <ul>
     *   <li>缺的是自己能补的（麦克风、无障碍、默认助理、填唤醒词）→ 开关不置灰，下面紧跟一行「去打开 …」；</li>
     *   <li>缺的是这台手机补不上的（处理器、没放唤醒词模型）且现在关着 → 开关置灰，写明原因；</li>
     *   <li>默认助理入口：开关开着但 OneSay 还不是默认助理 → 写「开着，但还差一步」，「设为默认助理」就放在它下面（以前在高级页，和这里对不上）。</li>
     * </ul>
     * 本地唤醒词的开关和「我的唤醒词」合在这一组（以前另有一组「启用本地唤醒词」，和这里是同一个开关）。
     */
    private void addEntries(LinearLayout parent) {
        final ConsoleKit k = c.kit;
        com.onesay.assistant.v2.entry.Entries.ensureInit(c.act);
        final com.onesay.assistant.v2.selfset.SelfStore store = com.onesay.assistant.v2.selfset.SelfSettingsAndroid.store(c.act);
        LinearLayout g = k.group(parent, "唤起入口");
        for (final com.onesay.plugin.api.entry.EntryTrigger t : com.onesay.plugin.api.entry.EntryRegistry.all()) {
            com.onesay.assistant.v2.selfset.SelfCatalog.Item item = com.onesay.assistant.v2.selfset.SelfCatalog.get(t.id());
            com.onesay.assistant.v2.selfset.SelfSettingsEngine.Row row = item == null ? null : com.onesay.assistant.v2.selfset.SelfSettingsEngine.row(store, item);
            boolean on = com.onesay.plugin.api.entry.EntryRegistry.isEnabled(t.id());
            String sub = EntryRows.sub(t.id(), t.description(), on, row == null ? null : row.missing, row != null && row.blockedForever());
            ConsoleKit.RowImpl r = k.switchRow(g, t.label(), sub, on, new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean v) {
                    com.onesay.assistant.v2.entry.Entries.setEnabled(c.act, t.id(), v);
                    if (col != null) fill();
                }
            });
            boolean warn = row != null && !row.missing.isEmpty();
            r.setSubTone(warn ? ConsoleHost.TONE_WARN : ConsoleHost.TONE_MUTED);
            if (!on && row != null && row.blockedForever()) r.setEnabled(false);
            if (row != null) {
                for (final com.onesay.assistant.v2.selfset.SelfCatalog.Need n : row.missing) {
                    if (!n.fixable || n.target == com.onesay.assistant.v2.selfset.SelfCatalog.Target.NONE) continue;
                    if (n.target == com.onesay.assistant.v2.selfset.SelfCatalog.Target.PAGE_WAKE) continue;   // 填唤醒词就在下面一行
                    if (com.onesay.assistant.v2.selfset.SelfCatalog.N_ASSIST.equals(n.id)) {
                        final Boolean isDef = com.onesay.assistant.v2.assist.AssistRoleHost.isDefault(c.act);
                        k.actionRow(g, com.onesay.assistant.v2.assist.AssistRole.ROW_TITLE, com.onesay.assistant.v2.assist.AssistRole.rowSub(isDef),
                                com.onesay.assistant.v2.assist.AssistRole.rowAction(isDef), new Runnable() {
                            @Override public void run() {
                                c.openAssistRole();
                            }
                        });
                        continue;
                    }
                    k.actionRow(g, "去打开" + n.title, n.how, "去打开", new Runnable() {
                        @Override public void run() {
                            if (!com.onesay.assistant.v2.selfset.SelfSettingsAndroid.open(c.act, n.target)) c.toast("没能打开，请到系统设置里找一下");
                        }
                    });
                }
            }
            if (com.onesay.assistant.v2.entry.BuiltinEntries.WAKE_WORD.equals(t.id())) addWakeWords(g);
        }
        addKeyWake(parent);
        k.footer(parent, "关掉的入口不会叫出 OneSay。外部 App 唤起默认关；怎么接入自己的入口见 README。");
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
        k.switchRow(g, "联网反查所在城市", "问天气没说城市、也没手动设常用城市时，把约 1 公里精度的位置发给公开的地名反查服务，查出城市名。默认关",
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
        k.footer(parent, "OneSay 不收集统计数据。其余联网都由你发起（提问、搜索、识别、朗读），只发往你在「API 密钥」里配置的服务或你问到的网站。");
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
            String sub = (b.level() == com.onesay.plugin.api.exec.ExecBackend.UidLevel.ROOT ? "root 身份" : "命令行身份")
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
                ? (b.level() == com.onesay.plugin.api.exec.ExecBackend.UidLevel.ROOT ? "可用（root 启动）" : "可用（命令行身份）")
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
        k.actionRow(g, "授予后台读剪贴板", "电脑联动同步剪贴板用", "授予", new Runnable() {
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

    /** OneSay 开源版：「我的唤醒词」（不内置任何唤醒词，用户自己填 token 序列）。开关就是上面的「本地唤醒词」入口。 */
    private void addWakeWords(LinearLayout g) {
        final ConsoleKit k = c.kit;
        final Context app = c.act.getApplicationContext();
        final String words = com.onesay.assistant.v2.wake.WakeWordSettings.getLines(app);
        k.actionRow(g, "我的唤醒词", words.length() == 0 ? "还没填写：填一条以后本地唤醒词才会听" : words.replace('\n', '；'), "编辑", new Runnable() {
            @Override public void run() {
                final android.widget.EditText e = new android.widget.EditText(c.act);
                e.setText(com.onesay.assistant.v2.wake.WakeWordSettings.getLines(app));
                e.setHint("每行一条，例如：HH EY1 W AH1 N S EY1 @HEY_ONESAY");
                e.setMinLines(3);
                new android.app.AlertDialog.Builder(c.act).setTitle("我的唤醒词")
                        .setMessage("中文用带声调的拼音，英文用音标；每个音都要在唤醒词模型的音表里（见 README）。保存后唤醒服务重新加载就生效。")
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
