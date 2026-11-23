package com.onesay.assistant.v2.ui.console;

import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureKernel;

/**
 * ★ console-align 2026-11-12：连接 · 权限与审批（原「权限保活」+「隐私」+ 新「以后这类不用问」）· 替你操作 · 下载 四页在这里；
 * 主动建议页、开发者 › 诊断与摘要不变。与电脑端设置页同名（docs/CONSOLE.md）。
 *
 * <p>[控制台整理第二步 2026-11-05] 设置区分组页（只搬家，内容与原「功能与权限」页一致，开关和默认值都不变）：
 * 权限保活（原「权限与运行」）· 隐私（原「屏幕与剪贴板」）· 主动建议（原功能里的主动建议行）
 * · 开发者 › 诊断与摘要（原功能里的设置诊断 / 长内容摘要 + 没迁移的旧插件卡片）。见 {@link ConsoleGroups}。
 */
final class GroupPages {

    private GroupPages() {}

    /**
     * ★ console-align：权限与审批 = 系统权限（原「权限保活」：悬浮窗 / 电池白名单 / Root / 麦克风，状态全部现查）
     * + 屏幕与剪贴板（原「隐私」页整页）+ 以后这类不用问（确认卡上点过「始终允许」的动作，可逐条撤销；对应电脑端同名一组）。
     */
    static final class PermPage extends Console.BuiltPage {
        /** ★ design-1118：true = 基础设置 › 隐私与审批（只有「以后这类不用问」+ 应用白名单；系统权限并进「权限配置」，屏幕与剪贴板并进「插件」）。 */
        final boolean privacyOnly;

        PermPage(Console c) {
            this(c, false);
        }

        PermPage(Console c, boolean privacyOnly) {
            super(c, privacyOnly ? "隐私与审批" : "权限与审批");
            this.privacyOnly = privacyOnly;
        }

        @Override void build(LinearLayout col) {
            final ConsoleKit k = c.kit;
            if (privacyOnly) {
                buildRemembered(col);
                buildWhitelist(col);
                return;
            }
            LinearLayout perm = k.group(col, "系统权限");
            if (StatusProbe.overlayOk(c.act)) {
                k.valueRow(perm, "悬浮窗", "灵动岛靠它显示", "已授权", ConsoleHost.TONE_OK);
            } else {
                k.actionRow(perm, "悬浮窗", "灵动岛靠它显示", "去授权", new Runnable() {
                    @Override public void run() {
                        c.openOverlaySettings();
                    }
                });
            }
            Boolean wl = StatusProbe.batteryWhitelisted(c.act);
            if (Boolean.TRUE.equals(wl)) {
                k.valueRow(perm, "电池优化白名单", "熄屏时定时任务也能准点", "已加入", ConsoleHost.TONE_OK);
            } else {
                ConsoleKit.RowImpl r = k.actionRow(perm, "电池优化白名单", wl == null ? "状态未知" : "不在白名单，熄屏久了定时任务会晚", "加入", new Runnable() {
                    @Override public void run() {
                        c.joinBatteryWhitelist(new Runnable() {
                            @Override public void run() {
                                fill();
                            }
                        });
                    }
                });
                r.setSubTone(wl == null ? ConsoleHost.TONE_NORMAL : ConsoleHost.TONE_WARN);
            }
            final ConsoleKit.RowImpl root = k.valueRow(perm, "Root 通道", "执行系统设置与按键守护", "检测中", ConsoleHost.TONE_MUTED);
            ConsoleStatus.Snapshot last = StatusProbe.last();
            if (last != null) showRoot(root, last);
            StatusProbe.run(c.act, c.compiler, new StatusProbe.Listener() {
                @Override public void onStatus(ConsoleStatus.Snapshot s) {
                    if (s.rootOk != null || s.done) showRoot(root, s);
                }
            });
            boolean mic = c.act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (mic) {
                k.valueRow(perm, "麦克风", "唤醒与听写", "已授权", ConsoleHost.TONE_OK);
            } else {
                k.actionRow(perm, "麦克风", "唤醒与听写", "去设置", new Runnable() {
                    @Override public void run() {
                        c.openAppSettings();
                    }
                });
            }
            k.footer(col, "这些是助手在后台保持运行、准点提醒要的权限；状态每次打开都现查。");

            // 原「隐私」页（屏幕与剪贴板插件行原样）
            LinearLayout screen = c.kit.group(col, "屏幕与剪贴板");
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_SCREEN, screen);
            dropIfEmpty(col, screen);
            k.footer(col, "这些功能会读当前屏幕或剪贴板；关掉总开关，下面的细项一起停用。声纹和密钥只存在本机。");

            buildRemembered(col);
            buildWhitelist(col);
        }

        /**
         * ★ app-whitelist-1116：应用白名单（默认空）。加的时候先弹一次风险说明确认；和电脑双向同步（电脑联动配对后）。
         * 规则：{@link com.onesay.assistant.v2.whitelist.ActionPolicy}；手机 App 填包名，电脑程序填 exe 名（如 notepad.exe）。
         */
        private void buildWhitelist(LinearLayout col) {
            final ConsoleKit k = c.kit;
            final com.onesay.assistant.v2.whitelist.WhitelistState st = com.onesay.assistant.v2.whitelist.WhitelistAndroid.state(c.act);
            LinearLayout g = k.group(col, "应用白名单");
            if (st.broken()) {
                k.actionRow(g, "白名单文件读坏了", "现在当空用（不放行任何应用）", "清空重建", new Runnable() {
                    @Override public void run() {
                        k.confirm("清空重建应用白名单？", "读坏的文件会被覆盖，白名单变成空的；和电脑同步时电脑上的条目会同步回来。", "清空重建", new Runnable() {
                            @Override public void run() {
                                st.reset();
                                fill();
                            }
                        });
                    }
                });
            }
            java.util.List<com.onesay.assistant.v2.whitelist.AppWhitelist.Entry> live = st.live();
            // 2026-11-16 用户定：电脑同步来的先「待确认」，在手机上看过风险说明点「确认生效」才放行
            for (final com.onesay.assistant.v2.whitelist.AppWhitelist.Entry e : st.pending()) {
                String title = (e.name.length() > 0 ? e.name : e.id) + "（待确认）";
                String sub = ("windows".equals(e.platform) ? "电脑 · " : "手机 · ") + e.id + " · 电脑同步来的，还没生效";
                k.actionRow(g, title, sub, "确认生效", new Runnable() {
                    @Override public void run() {
                        k.confirm("让电脑同步来的「" + (e.name.length() > 0 ? e.name : e.id) + "」在手机上生效？",
                                com.onesay.assistant.v2.whitelist.AppWhitelist.riskText(e.platform, e.id, e.name), "确认生效", new Runnable() {
                            @Override public void run() {
                                String err = st.confirm(e.platform, e.id);
                                c.toast(err == null ? "已生效" : err);
                                fill();
                            }
                        });
                    }
                });
            }
            if (live.isEmpty()) k.valueRow(g, "还没有", "默认空；加进来的应用里操作不再逐步问你", "0 个", ConsoleHost.TONE_MUTED);
            for (final com.onesay.assistant.v2.whitelist.AppWhitelist.Entry e : live) {
                String title = e.name.length() > 0 ? e.name : e.id;
                String sub = ("windows".equals(e.platform) ? "电脑 · " : "手机 · ") + e.id + ("pc".equals(e.originKind) ? " · 电脑上加的" : "")
                        + (st.isConfirmed(e.platform, e.id) ? "" : " · 待确认（上面「确认生效」）");
                k.actionRow(g, title, sub, "删除", new Runnable() {
                    @Override public void run() {
                        k.confirm("把「" + e.id + "」移出应用白名单？", "移出后在这个应用里的操作会照常先问你，电脑上也会同步删掉。", "删除", new Runnable() {
                            @Override public void run() {
                                st.remove(e.platform, e.id);
                                c.toast("已删除");
                                fill();
                            }
                        });
                    }
                });
            }
            k.actionRow(g, "加一个应用", "手机 App 填包名（com.example.app），电脑程序填 exe 名（notepad.exe）", "加入", new Runnable() {
                @Override public void run() {
                    askAdd(st);
                }
            });
            k.footer(col, "白名单里的应用，除了付款和发送隐私资料（身份证、银行卡、住址、真名、手机号、邮箱等，文字或图片），助手的操作不再逐步问你。"
                    + "本次任务读到过隐私后往外发、填密码 / 验证码、读过网页短信后发送、卸载，照样问；付款最后一下仍要你自己点。系统安装器、权限管理和助手自己不能加。电脑同步来的要在这里确认一次才生效。");
        }

        private void askAdd(final com.onesay.assistant.v2.whitelist.WhitelistState st) {
            final ConsoleKit k = c.kit;
            final android.widget.EditText id = new android.widget.EditText(c.act), name = new android.widget.EditText(c.act);
            id.setSingleLine(true);
            id.setHint("包名或 exe 名");
            name.setSingleLine(true);
            name.setHint("显示名（可选）");
            id.setTextColor(k.t.text1);
            name.setTextColor(k.t.text1);
            id.setHintTextColor(k.t.text3);
            name.setHintTextColor(k.t.text3);
            LinearLayout box = k.column();
            box.setPadding(k.dp(24), k.dp(8), k.dp(24), 0);
            box.addView(id, k.lpMatch(8, 0));
            box.addView(name, k.lpMatch(8, 0));
            k.dialog().setTitle("加进应用白名单").setView(box)
                    .setPositiveButton("下一步", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            final String raw = id.getText().toString().trim(), nm = name.getText().toString().trim();
                            final String pf = platformOf(raw);
                            String why = com.onesay.assistant.v2.whitelist.AppWhitelist.refuseReason(pf, raw);
                            if (why != null) {
                                c.toast(why);
                                return;
                            }
                            k.confirm("加进应用白名单？", com.onesay.assistant.v2.whitelist.AppWhitelist.riskText(pf, raw, nm), "确定加入", new Runnable() {
                                @Override public void run() {
                                    String err = st.add(pf, raw, nm);
                                    c.toast(err == null ? "已加入，会同步到电脑" : err);
                                    fill();
                                }
                            });
                        }
                    })
                    .setNegativeButton("取消", null).show();
        }

        /** 包名（带点、不以 .exe 结尾）→ android；其余按 Windows 程序名。 */
        static String platformOf(String raw) {
        String r = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (r.endsWith(".exe") || r.indexOf('\\') >= 0 || r.indexOf('.') < 0) return com.onesay.assistant.v2.whitelist.AppWhitelist.WINDOWS;
        return com.onesay.assistant.v2.whitelist.AppWhitelist.ANDROID;
        }

        /** 以后这类不用问：确认卡上点过「始终允许」的动作（ConsentPolicy 落盘清单），每条可撤销。 */
        private void buildRemembered(LinearLayout col) {
            final ConsoleKit k = c.kit;
            if (!com.onesay.assistant.v2.executor.ConsentPolicy.initialized()) com.onesay.assistant.v2.executor.ConsentPolicy.init(c.act);
            java.util.List<String> signs = com.onesay.assistant.v2.executor.ConsentPolicy.alwaysAllowedList();
            LinearLayout g = k.group(col, "以后这类不用问");
            if (signs.isEmpty()) {
                k.valueRow(g, "还没有", "确认卡上点「始终允许」的动作会列在这里", "0 类", ConsoleHost.TONE_MUTED);
            }
            for (final String sign : signs) {
                String label = sign;
                try {
                    com.onesay.assistant.v2.capability.Capability cap = com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance().describe(sign);
                    if (cap != null && cap.summary != null && cap.summary.length() > 0) label = cap.summary;
                } catch (Throwable ignored) {
                }
                final String shown = label;
                k.actionRow(g, shown, sign, "撤销", new Runnable() {
                    @Override public void run() {
                        k.confirm("撤销「" + shown + "」？", "撤销后下次再做这件事会先弹卡问你。", "撤销", new Runnable() {
                            @Override public void run() {
                                com.onesay.assistant.v2.executor.ConsentPolicy.revoke(sign);
                                c.toast("已撤销");
                                fill();
                            }
                        });
                    }
                });
            }
            k.footer(col, "付款、通话 / 短信、对外发送、删除类和远程工具每次都问，记不住。读过网页 / 短信等外部内容后，发送类也会再问。");
        }

        private static void showRoot(ConsoleKit.RowImpl r, ConsoleStatus.Snapshot s) {
            ConsoleStatus.Level l = ConsoleStatus.root(s).level;   // ★ priv-llm：Shizuku 中间档；开源版无 root 只是提示
            r.setValue(ConsoleStatus.privilegeText(s), l == ConsoleStatus.Level.OK ? ConsoleHost.TONE_OK
                    : l == ConsoleStatus.Level.BAD ? ConsoleHost.TONE_BAD : l == ConsoleStatus.Level.WARN ? ConsoleHost.TONE_WARN : ConsoleHost.TONE_MUTED);
        }
    }

    /** 主动建议：总开关 + 截图权限（原「功能与权限 › 功能」里的主动建议行；点进去是场景页，不变）。 */
    static final class SuggestPage extends Console.BuiltPage {
        SuggestPage(Console c) {
            super(c, "主动建议");
        }

        @Override void build(LinearLayout col) {
            LinearLayout g = c.kit.group(col, null);
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, g, ConsoleGroups.SUGGEST_PLUGINS, null);
            dropIfEmpty(col, g);
            c.kit.footer(col, "点「主动建议」那一行进场景列表；读个人数据 / 改系统设置的场景默认关。");
        }
    }

    /** ★ console-align：连接 = 电脑联动 · 多设备执行端 · 连接器（插件行原样，从「功能」搬来）。对应电脑端「连接」（配对手机 / 局域网 / 中转）。 */
    static final class ConnectPage extends Console.BuiltPage {
        ConnectPage(Console c) {
            super(c, "连接");
        }

        @Override void build(LinearLayout col) {
            LinearLayout g = c.kit.group(col, "电脑与其他设备");
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, g, ConsoleGroups.CONNECT_PLUGINS, null);
            dropIfEmpty(col, g);
            c.kit.footer(col, "电脑联动走同一个 Wi-Fi 局域网直连（TLS 加密 + 证书指纹），不经过任何服务器。每项点开有细项。");
        }
    }

    /** ★ console-align：替你操作 = 替你操作 App · 接口优先 · 浏览器跑腿（插件行原样）+ 控屏颜色说明（状态徽标，两端同色）。 */
    static final class ActPage extends Console.BuiltPage {
        ActPage(Console c) {
            super(c, "替你操作");
        }

        @Override void build(LinearLayout col) {
            final ConsoleKit k = c.kit;
            LinearLayout g = k.group(col, "替你操作");
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, g, ConsoleGroups.ACT_PLUGINS, null);
            dropIfEmpty(col, g);
            LinearLayout b = k.group(col, "控屏颜色");
            for (String st : ConsoleStyle.BADGES) k.badgeRow(b, st, ConsoleStyle.badgeSub(st), st, ConsoleStyle.badgeRgb(st));
            k.footer(col, "执行时屏幕边框和灵动岛描边按这四种颜色变（开关在「替你操作 App」细项页「控屏状态颜色」）。付款 / 发送 / 删除等步骤先问你。");
        }
    }

    /** ★ console-align：下载 = 把链接下到电脑（从电脑联动细项页搬来）。对应电脑端「下载」。 */
    static final class DownloadPage extends Console.BuiltPage {
        DownloadPage(Console c) {
            super(c, "下载");
        }

        @Override void build(LinearLayout col) {
            com.onesay.assistant.v2.pclink.PcLinkUi.addDownloadRows(c, col);
        }
    }

    /** 开发者 › 诊断与摘要：设置诊断、长内容摘要（原功能里的两行）+ 没迁移到 v2 的旧插件卡片（原「其他」）。 */
    static final class DevFeaturesPage extends Console.BuiltPage {
        DevFeaturesPage(Console c) {
            super(c, "诊断与摘要");
        }

        @Override void build(LinearLayout col) {
            LinearLayout g = c.kit.group(col, "诊断与摘要");
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, g, ConsoleGroups.DEV_PLUGINS, null);
            dropIfEmpty(col, g);
            LinearLayout legacy = c.kit.column();
            if (FeatureKernel.get().addConsoleCards(c, legacy) > 0) {
                col.addView(c.sectionTitle("其他"));
                col.addView(legacy);
            }
            c.kit.footer(col, "每项点开有细项（比如诊断的「没见过的症状让大模型挑」）。开关和以前一样，只是搬到了这里。");
        }
    }
}
