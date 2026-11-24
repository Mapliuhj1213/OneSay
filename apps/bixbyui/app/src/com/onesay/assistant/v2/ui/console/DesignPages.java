package com.onesay.assistant.v2.ui.console;

import android.content.Context;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureKernel;

/**
 * ★ design-1118（OneSay 设计语言 v1，docs/DESIGN.md、docs/CONSOLE.md 第七节）：控制台首页「设置」只剩 4 行，对应这里的页面：
 * <ul>
 *   <li>权限配置 = {@link PermSetupPage}（移植自个人版 mz21-perm-1118）；</li>
 *   <li>{@link BasicsPage 基础设置}：模型 · 语音 · 唤起 · 外观 · 隐私与审批 —— 不随插件开关变，始终可见；</li>
 *   <li>{@link PluginsPage 插件}：每个功能一个开关；开着才进得去它的设置（{@link ConsoleKit#switchLinkRow} 关着只提示）；</li>
 *   <li>{@link AdvancedHubPage 高级}：专家项、运行状态、开发者、关于。</li>
 * </ul>
 * 旧分组页（连接 / 权限与审批 / 模型与密钥 / 语音 / 替你操作 / 功能 / 下载）都保留，从这几页进得去；不删功能、设置键不改名。
 */
final class DesignPages {

    private DesignPages() {}

    /** 插件页分组：{标题, 一句说明, 插件 id…}。PAGE_FEATURES / PAGE_SCREEN 上的插件按 id 挑（{@link FeatureKernel#pickedFor}）。 */
    static final String[] DAILY = {"callback", "s3", "sched", "planner", "brief"};
    static final String[] ACT = {"phone", "apifirst", "errand", "suggest"};
    static final String[] CONNECT = {"pclink", "conn", "rain"};
    static final String[] MORE = {"phongent", "vyrse", "diag", "summary"};

    /** 首页「插件」一行的「已开 N 个」：数用户能看到的插件里开着的。后台线程。 */
    static int countOnPlugins(Context app) {
        int n = 0;
        String[][] all = {DAILY, ACT, CONNECT, {"island", "quicknote", "s2", "screenref", "s1", "imgsave"}};
        for (String[] g : all) {
            for (String id : g) {
                com.onesay.assistant.v2.kernel.FeaturePlugin p = FeatureKernel.get().find(id);
                try {
                    if (p != null && p.enabled(app)) n++;
                } catch (Throwable ignored) {
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 基础设置

    static final class BasicsPage extends Console.BuiltPage {
        BasicsPage(Console c) {
            super(c, "基础设置");
        }

        @Override void build(LinearLayout col) {
            final ConsoleKit k = c.kit;
            LinearLayout gm = k.group(col, "模型");
            boolean llm;
            try {
                llm = c.compiler.hasApiKey();
            } catch (Throwable t) {
                llm = false;
            }
            k.linkRow(gm, "主模型与语音识别 Key", (llm ? "主模型已配置" : "主模型没填") + " · 接口地址 · 模型名 · 服务商", 0, new Runnable() {
                @Override public void run() {
                    c.push(new AdvancedPage(c, AdvancedPage.MODE_BASIC));
                }
            });

            LinearLayout gv = k.group(col, "语音");
            k.linkRow(gv, "声音与朗读", "声纹 · 唤醒提示音 · 朗读回答 · 回答后接着听", 0, new Runnable() {
                @Override public void run() {
                    c.push(new VoicePage(c, VoicePage.MODE_BASIC));
                }
            });
            k.linkRow(gv, "唤起方式", "默认助理 · 快捷开关 · 按键唤醒 · 本地唤醒词", 0, new Runnable() {
                @Override public void run() {
                    c.push(new DeviceCapsPage(c, DeviceCapsPage.MODE_WAKE));
                }
            });

            LinearLayout ga = k.group(col, "外观");
            k.switchRow(ga, "浅色外观", "默认深色；灵动岛始终是黑色", !ConsoleTheme.current().dark, new ConsoleHost.Toggle() {
                @Override public void onToggle(final boolean on) {
                    c.act.getWindow().getDecorView().postDelayed(new Runnable() {   // 等开关动画走完再整页换色
                        @Override public void run() {
                            c.setThemeMode(on ? ConsoleTheme.MODE_LIGHT : ConsoleTheme.MODE_DARK);
                        }
                    }, 220);
                }
            });

            LinearLayout gp = k.group(col, "隐私与审批");
            k.switchRow(gp, "发给云端前隐藏个人信息", "手机号、身份证号、卡号、验证码换成代号再发",
                    com.onesay.assistant.v2.privacy.PrivacySettings.REDACT.on(c.act), new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean on) {
                    com.onesay.assistant.v2.privacy.PrivacySettings.REDACT.set(c.act, on);
                }
            });
            k.linkRow(gp, "以后这类不用问 · 应用白名单", "确认卡上「始终允许」的动作；白名单里的应用不再逐步问", 0, new Runnable() {
                @Override public void run() {
                    c.push(new GroupPages.PermPage(c, true));
                }
            });
            // ★ 2026-11-23：聊天存档（单纯的聊天记录）和长期记忆分开；这里只清存档，先弹确认框
            k.actionRow(gp, com.onesay.assistant.v2.router.longctx.ArchiveClear.ROW_TITLE, com.onesay.assistant.v2.router.longctx.ArchiveClear.ROW_SUB,
                    com.onesay.assistant.v2.router.longctx.ArchiveClear.ROW_ACTION, new Runnable() {
                @Override public void run() {
                    com.onesay.assistant.v2.router.longctx.ArchiveClear.request(new com.onesay.assistant.v2.router.longctx.ArchiveClear.Asker() {
                        @Override public void ask(String title, String body, String ok, Runnable onYes) {
                            k.confirm(title, body, ok, onYes);
                        }
                    }, com.onesay.assistant.v2.router.ConversationMemory.getInstance().archive(), new com.onesay.assistant.v2.router.longctx.ArchiveClear.Done() {
                        @Override public void done(String text) {
                            c.toast(text);
                        }
                    });
                }
            });
            k.footer(col, "权限和密钥状态在「权限配置」；功能开关在「插件」；一般不用改的在「高级」。");
        }
    }

    // ------------------------------------------------------------------ 插件

    static final class PluginsPage extends Console.BuiltPage {
        PluginsPage(Console c) {
            super(c, "插件");
        }

        @Override void build(LinearLayout col) {
            final ConsoleKit k = c.kit;
            k.footer(col, "每个功能一个开关；开着才能点进去改它的设置。");

            LinearLayout g1 = k.group(col, "常用");
            k.switchLinkRow(g1, "灵动岛", "消息提醒 · 智能上岛 · 显示",
                    com.onesay.assistant.v2.island.IslandSettings.enabled(c.act), new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean on) {
                    com.onesay.assistant.v2.island.IslandSettings.set(c.act, com.onesay.assistant.v2.island.IslandSettings.KEY_ENABLED, on);
                    com.onesay.assistant.v2.s1.S1Hooks.refreshIslandMode("console");
                }
            }, new Runnable() {
                @Override public void run() {
                    c.openPage(ConsoleHost.PAGE_ISLAND, "灵动岛");
                }
            });
            k.switchLinkRow(g1, "记一记", "按键或说「记一记」记下当前屏幕",
                    com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin.SWITCH.on(c.act), new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean on) {
                    com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin.SWITCH.set(c.act, on);
                    com.onesay.assistant.v2.wake.WakeService.syncGestureFlag(c.act);
                }
            }, new Runnable() {
                @Override public void run() {
                    c.openPage(ConsoleHost.PAGE_QUICKNOTE, "记一记");
                }
            });
            k.switchLinkRow(g1, "记忆", "「记一下」· 默认应用 · 问一次记住",
                    com.onesay.assistant.v2.s2.S2Settings.pref(c.act, com.onesay.assistant.v2.s2.S2Settings.KEY_MEMORY), new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean on) {
                    com.onesay.assistant.v2.s2.S2Settings.set(c.act, com.onesay.assistant.v2.s2.S2Settings.KEY_MEMORY, on);
                }
            }, new Runnable() {
                @Override public void run() {
                    c.openPage(ConsoleHost.PAGE_MEMORY, "记忆");
                }
            });
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, g1, DAILY, null);

            LinearLayout g2 = k.group(col, "让手机替你做");
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, g2, ACT, null);
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_SCREEN, g2);
            Console.BuiltPage.dropIfEmpty(col, g2);

            LinearLayout g3 = k.group(col, "连接");
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, g3, CONNECT, null);
            k.linkRow(g3, "把链接下到电脑", "视频 / 音频链接交给配对的电脑下载", 0, new Runnable() {
                @Override public void run() {
                    c.push(new GroupPages.DownloadPage(c));
                }
            });
            Console.BuiltPage.dropIfEmpty(col, g3);

            LinearLayout g4 = k.group(col, "更多插件");
            FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, g4, MORE, null);
            Console.BuiltPage.dropIfEmpty(col, g4);
            k.footer(col, "关掉的插件不在后台运行，也不占权限；设置保留，再打开时照旧。");
        }
    }

    // ------------------------------------------------------------------ 高级

    static final class AdvancedHubPage extends Console.BuiltPage {
        AdvancedHubPage(Console c) {
            super(c, "高级");
        }

        @Override void build(LinearLayout col) {
            final ConsoleKit k = c.kit;
            k.footer(col, "一般不用改：默认就是推荐值。");
            LinearLayout gm = k.group(col, "模型与语音");
            k.linkRow(gm, "模型细项", "备用模型 · 视觉模型 · 思考模式 · 读数直答 · 邮箱", 0, new Runnable() {
                @Override public void run() {
                    c.push(new AdvancedPage(c, AdvancedPage.MODE_EXPERT));
                }
            });
            k.linkRow(gm, "唤醒手感", "按下即听 · 抢话不丢字 · 自适应断句", 0, new Runnable() {
                @Override public void run() {
                    c.push(new VoicePage(c, VoicePage.MODE_FEEL));
                }
            });
            // ★ 1123e：默认打字唤起（默认关）：开了唤起即弹键盘、不开麦
            k.switchRow(gm, "默认打字唤起", "唤起后直接弹键盘打字，不先听你说",
                    com.onesay.assistant.v2.ui.wake.WakeUiSettings.typeFirst(c.act), new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean on) {
                    com.onesay.assistant.v2.ui.wake.WakeUiSettings.setTypeFirst(c.act, on);
                }
            });
            // ★ feat/console-ui：对话控制台（默认开）。关掉 = 首页回到原来的开关页、说话用原来的聆听卡片（旧路径保留，可回退）
            k.switchRow(gm, "对话控制台", "首页就是对话；说话时全屏听，结果都进同一个对话里。关掉回到原来的小卡片",
                    com.onesay.assistant.v2.ui.wake.WakeUiSettings.consoleMode(c.act), new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean on) {
                    com.onesay.assistant.v2.ui.wake.WakeUiSettings.setConsoleMode(c.act, on);
                    c.toast(on ? "已打开：下次唤起就是全屏对话，首页重新打开 App 后换成对话" : "已关掉：下次唤起回到小卡片，首页重新打开 App 后换回开关页");
                }
            });
            LinearLayout gd = k.group(col, "设备");
            // ★ settings-diag-1124：「设为默认助理」挪到「基础设置 › 唤起方式」里「默认助理入口」开关正下方（开关开着、但还不是默认助理时才出现），
            //   不再在这里和那边各说一套；唤起入口细项也只在那一页。
            k.linkRow(gd, "设备能力与执行后端", "这台手机 · 执行后端 · 隐私与外部请求 · 功能能不能用", 0, new Runnable() {
                @Override public void run() {
                    c.push(new DeviceCapsPage(c, DeviceCapsPage.MODE_DEVICE));
                }
            });
            k.linkRow(gd, "运行状态", "唤醒服务 · 执行通道 · 云端", 0, new Runnable() {
                @Override public void run() {
                    c.push(new StatusPage(c));
                }
            });
            // ★ settings-diag-1124：OneSay 自己的运行日志（魅族这类开不了无线调试的手机也能拿到日志）
            LinearLayout gl = k.group(col, "日志");
            LogRows.add(c, gl);
            LinearLayout gx = k.group(col, "开发者");
            k.linkRow(gx, "诊断与摘要", "设置诊断 · 长内容摘要 · 旧插件卡片", 0, new Runnable() {
                @Override public void run() {
                    c.push(new GroupPages.DevFeaturesPage(c));
                }
            });
            k.linkRow(gx, "插件状态（只读）", "每个内核插件的状态", 0, new Runnable() {
                @Override public void run() {
                    c.push(new AdvancedPage.PluginsPage(c));
                }
            });
            if (DevMode.showSwitch(c.devMode())) {
                // ★ 1123e：和系统设置一样，开着时这里有一行开关，关掉即退出并隐藏调试工具
                k.switchRow(gx, DevMode.SWITCH_TITLE, DevMode.SWITCH_SUB, true, new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        if (on) return;
                        c.setDevMode(false);
                        c.toast(DevMode.CLOSED);
                        c.nav.rebuildAll();
                    }
                });
            }
            if (c.devMode()) {
                k.linkRow(gx, "调试工具", "调试按钮 · 自测 · 关停文件 · 阈值 · 日志", 0, new Runnable() {
                    @Override public void run() {
                        c.push(new DeveloperPage(c));
                    }
                });
            }
            LinearLayout ga = k.group(col, null);
            k.linkRow(ga, "关于", "v2 " + Console.RELEASE + (c.devMode() ? " · 开发者模式" : ""), ConsoleIcon.INFO, new Runnable() {
                @Override public void run() {
                    c.push(new AboutPage(c));
                }
            });
        }
    }
}
