package com.onesay.assistant.v2.ui.console;

import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureKernel;

/**
 * [控制台整理第二步 2026-11-05] 设置区分组页（只搬家，内容与原「功能与权限」页一致，开关和默认值都不变）：
 * 权限保活（原「权限与运行」）· 隐私（原「屏幕与剪贴板」）· 主动建议（原功能里的主动建议行）
 * · 开发者 › 诊断与摘要（原功能里的设置诊断 / 长内容摘要 + 没迁移的旧插件卡片）。见 {@link ConsoleGroups}。
 */
final class GroupPages {

    private GroupPages() {}

    /** 权限保活：悬浮窗 / 电池白名单 / Root / 麦克风，状态全部现查（原「功能与权限 › 权限与运行」原样搬来）。 */
    static final class PermPage extends Console.BuiltPage {
        PermPage(Console c) {
            super(c, "权限保活");
        }

        @Override void build(LinearLayout col) {
            final ConsoleKit k = c.kit;
            LinearLayout perm = k.group(col, "权限与运行");
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
        }

        private static void showRoot(ConsoleKit.RowImpl r, ConsoleStatus.Snapshot s) {
            if (s.rootOk == null) r.setValue(s.done ? "未知" : "检测中", ConsoleHost.TONE_MUTED);
            else r.setValue(s.rootOk ? "就绪" : "未就绪", s.rootOk ? ConsoleHost.TONE_OK : ConsoleHost.TONE_BAD);
        }
    }

    /** 隐私：会读屏幕 / 剪贴板的功能（原「功能与权限 › 屏幕与剪贴板」，插件行原样）。 */
    static final class PrivacyPage extends Console.BuiltPage {
        PrivacyPage(Console c) {
            super(c, "隐私");
        }

        @Override void build(LinearLayout col) {
            LinearLayout screen = c.kit.group(col, "屏幕与剪贴板");
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_SCREEN, screen);
            dropIfEmpty(col, screen);
            c.kit.footer(col, "这些功能会读当前屏幕或剪贴板；关掉总开关，下面的细项一起停用。声纹和密钥只存在本机。");
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
