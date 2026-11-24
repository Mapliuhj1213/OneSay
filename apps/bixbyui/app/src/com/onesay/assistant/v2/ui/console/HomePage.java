package com.onesay.assistant.v2.ui.console;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onesay.assistant.v2.kernel.FeatureKernel;

import java.util.List;

/**
 * [console R12 → 计划区整理 2026-11-05] 首页：状态 Hero（总开关 + 4 个真实状态胶囊）→ 只在出问题时出现的提醒条（≤2）→ 记一记 / 记忆两张大卡
 * →「计划」（打开计划 + 直达页签，跳计划 App 对应页；没装 / 开关关 → 本机计划页）
 * →「设置」分组（★ console-align 2026-11-12，{@link ConsoleGroups}，与电脑端设置页同名同序）：连接 · 权限与审批 · 模型与密钥 · 语音
 * · 替你操作 · 功能 · 下载 · 插件 → 关于 →「开发者」（放最下，默认折叠：诊断与摘要 · 调试工具）。见 bixby-plan/docs/控制台整理.md。
 */
final class HomePage extends ConsoleNav.Page {

    private final Console c;
    private ConsoleKit k;
    private ScrollView mScroll;
    private View mDot;
    private TextView mWord, mHeadline, mHint;
    private ToggleView mToggle;
    private FlowLayout mPills;
    private LinearLayout mBanners;
    private ConsoleKit.Tile mNoteTile, mMemTile;
    private ConsoleKit.RowImpl mVoiceRow, mAboutRow, mPlanRow, mPermRow, mPluginsRow;
    private ConsoleStatus.Snapshot mSnap;
    private LinearLayout mPcGroup;
    private String mPcState = "";
    private boolean mQuickNoteOn = true;

    /** ★ feat/console-ui：true = 从对话控制台首页 ⚙ 进来的「设置」页（原来首页的开关 / 设置整页，分组不变，只是顶上换成返回 +「设置」）。 */
    private final boolean mSettings;

    HomePage(Console c) {
        this(c, false);
    }

    HomePage(Console c, boolean settings) {
        this.c = c;
        this.mSettings = settings;
    }

    @Override protected View create() {
        k = c.kit;
        LinearLayout col = k.column();
        mScroll = k.scroll(col);
        if (mSettings) {
            col.addView(k.topBar("设置", new Runnable() {
                @Override public void run() {
                    c.nav.back();
                }
            }, null, null));
        } else {
            col.addView(k.largeTitle(com.onesay.assistant.v2.BuildVariant.APP_TITLE, "v2 · " + Console.RELEASE));
        }
        col.addView(hero());
        mBanners = k.column();
        col.addView(mBanners, k.lpMatch(0, 0));

        LinearLayout tiles = k.rowLayout();
        tiles.setBaselineAligned(false);
        mNoteTile = k.tile(ConsoleIcon.NOTE, "记一记", "条", new Runnable() {
            @Override public void run() {
                com.onesay.assistant.v2.quicknote.ui.QuickNotePage.open(c.act, -1);
            }
        });
        mMemTile = k.tile(ConsoleIcon.MEMORY, "记忆", "条", new Runnable() {
            @Override public void run() {
                c.openPage(com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_MEMORY, "记忆");
            }
        });
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a.rightMargin = k.dp(6);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.leftMargin = k.dp(6);
        tiles.addView(mNoteTile.root, a);
        tiles.addView(mMemTile.root, b);
        col.addView(tiles, k.lpMatch(ConsoleTheme.GAP_CARD, 0));

        // ---- [fix-1123-c] 连接电脑：配对入口直接放在首页（扫码 / 粘贴配对码一步到位），不用先进「插件 › 电脑联动」
        mPcGroup = k.group(col, "连接电脑");
        mPcState = "";   // ★ 1123-dui B17'：页面重建（后台切回）时新组是空的，别沿用旧状态跳过填充
        fillPc();

        // ---- 计划：日程 / 定时任务 / 待办 / 票 / 取件码 / 自动任务统一入口（一行「打开计划」+ 一排直达页签）
        LinearLayout gp = k.group(col, "计划");
        mPlanRow = k.linkRow(gp, "打开计划", PlanSection.sub(com.onesay.assistant.v2.planner.PlannerLink.installed(c.act), com.onesay.assistant.v2.planner.PlannerLink.SWITCH.on(c.act)), ConsoleIcon.PLAN, new Runnable() {
            @Override public void run() {
                openPlan(null);
            }
        });
        FlowLayout tabs = new FlowLayout(c.act, k.dp(8), k.dp(8));
        tabs.setPadding(k.dp(16), k.dp(2), k.dp(16), k.dp(14));
        for (final String[] t : PlanSection.TABS) {
            tabs.addView(k.chip(t[0], new Runnable() {
                @Override public void run() {
                    openPlan(t[1]);
                }
            }));
        }
        gp.addView(tabs, k.lpMatch(0, 0));

        // ---- 设置（★ design-1118，docs/DESIGN.md / IA）：首页只留 4 行 —— 权限配置 · 基础设置 · 插件 · 高级（关于、开发者、运行状态都在「高级」里）。
        //      旧分组页（连接 / 权限与审批 / 模型与密钥 / 语音 / 替你操作 / 功能 / 下载）都还在，从这 4 页里进得去，设置键不改名。
        LinearLayout g1 = k.group(col, "设置");
        mPermRow = k.linkRow(g1, "权限配置", "一步步配好必须的权限和密钥", ConsoleIcon.SHIELD, new Runnable() {
            @Override public void run() {
                c.push(new PermSetupPage(c));
            }
        });
        mVoiceRow = k.linkRow(g1, "基础设置", "模型 · 语音 · 唤起 · 外观 · 隐私", ConsoleIcon.GEAR, new Runnable() {
            @Override public void run() {
                c.push(new DesignPages.BasicsPage(c));
            }
        });
        mPluginsRow = k.linkRow(g1, "插件", "每个功能一个开关，开着才显示设置", ConsoleIcon.PUZZLE, new Runnable() {
            @Override public void run() {
                c.push(new DesignPages.PluginsPage(c));
            }
        });
        mAboutRow = k.linkRow(g1, "高级", "专家选项 · 运行状态 · 日志 · 关于" + (c.devMode() ? " · 开发者" : ""), ConsoleIcon.SLIDERS, new Runnable() {
            @Override public void run() {
                c.push(new DesignPages.AdvancedHubPage(c));
            }
        });

        ConsoleStatus.Snapshot last = StatusProbe.last();
        // 首帧：有上次结果画上次的；冷启动没有 → 「检测中」快照带上次的守护版本占位（不画「按键 未知」，免得 1 秒后变宽度顶动布局）
        render(last != null ? last : StatusProbe.seed(c.act));
        return mScroll;
    }

    private View hero() {
        LinearLayout h = k.column();
        android.graphics.drawable.GradientDrawable bg = k.round(k.t.surface, ConsoleTheme.R_HERO);
        bg.setStroke(Math.max(1, k.dp(1)), k.t.line);
        h.setBackground(k.ripple(bg));
        h.setPadding(k.dp(20), k.dp(16), k.dp(16), k.dp(18));
        LinearLayout top = k.rowLayout();
        mDot = k.dot(k.t.text3, 9);
        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) mDot.getLayoutParams();
        dlp.rightMargin = k.dp(8);
        top.addView(mDot, dlp);
        mWord = k.text("检测中", ConsoleTheme.SP_ITEM, k.t.text2, ConsoleKit.W_MEDIUM);
        top.addView(mWord, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mToggle = new ToggleView(c.act, k.t, !com.onesay.assistant.v2.wake.AssistantPause.isPaused(c.act));
        mToggle.setClickable(true);
        mToggle.setContentDescription("助手总开关（关 = 暂停）");
        mToggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        mToggle.setBackground(k.ripple(null));
        mToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final boolean wantOn = !mToggle.isOn();
                mToggle.setOn(wantOn, true);
                c.setPaused(!wantOn, new Runnable() {
                    @Override public void run() {
                        refresh();
                    }
                }, new Runnable() {
                    @Override public void run() {
                        mToggle.setOn(true, true);
                    }
                });
            }
        });
        top.addView(mToggle);
        h.addView(top);
        mHeadline = k.text("正在检查…", ConsoleTheme.SP_LARGE_TITLE, k.t.text1, ConsoleKit.W_BOLD);
        mHeadline.setPadding(0, k.dp(6), 0, 0);
        h.addView(mHeadline);
        mHint = k.text(" ", ConsoleTheme.SP_BODY, k.t.text2, ConsoleKit.W_REGULAR);
        mHint.setPadding(0, k.dp(4), 0, k.dp(12));
        h.addView(mHint);
        mPills = new FlowLayout(c.act, k.dp(6), k.dp(8));
        h.addView(mPills);
        h.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                c.push(new StatusPage(c));
            }
        });
        h.setContentDescription("运行状态，点开看详情");
        LinearLayout.LayoutParams lp = k.lpMatch(0, 0);
        h.setLayoutParams(lp);
        return h;
    }

    /** 计划区：先跳计划 App（开关开着 + 能打开）；不行就进本机计划页。tab = null 即「打开计划」。 */
    void openPlan(String tab) {
        PlanSection.go(new PlanSection.Opener() {
            @Override public boolean open(String t) {
                return com.onesay.assistant.v2.planner.PlannerLink.manageInPlanner(c.act, t);
            }
        }, new PlanSection.Local() {
            @Override public void show(String t) {
                LocalPlanPage.openLocal(c, t);
            }
        }, tab);
    }

    void render(ConsoleStatus.Snapshot s) {
        mSnap = s;
        ConsoleStatus.Hero hero = ConsoleStatus.hero(s);
        int col = k.levelColor(hero.level);
        ((android.graphics.drawable.GradientDrawable) mDot.getBackground()).setColor(col);
        mWord.setText(hero.word);
        mWord.setTextColor(hero.level == ConsoleStatus.Level.UNKNOWN || hero.level == ConsoleStatus.Level.OFF ? k.t.text2 : col);
        mHeadline.setText(hero.headline);
        mHint.setText(ConsoleStatus.heroHint(s.paused, mQuickNoteOn));
        mToggle.setOn(!s.paused, mToggle.isAttachedToWindow());
        mPills.removeAllViews();
        // ★ design-1118（mz21-perm）：首页只留 Hero 一个汇总状态，不再摆一排胶囊；各项权限合并成一条「权限未就绪」提醒条
        if (ConsoleStatus.homeShowsPills()) for (ConsoleStatus.Pill p : ConsoleStatus.pills(s)) mPills.addView(k.pill(p));
        mPills.setVisibility(mPills.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (mPermRow != null) {
            mPermRow.setSub(PermSetup.headline(s));
            mPermRow.setSubTone(PermSetup.anyBad(s) ? com.onesay.assistant.v2.kernel.ConsoleHost.TONE_BAD
                    : (PermSetup.problems(s).isEmpty() ? com.onesay.assistant.v2.kernel.ConsoleHost.TONE_NORMAL : com.onesay.assistant.v2.kernel.ConsoleHost.TONE_WARN));
        }
        mBanners.removeAllViews();
        List<ConsoleStatus.Banner> bs = ConsoleStatus.banners(s);
        for (final ConsoleStatus.Banner b : bs) {
            View v = k.banner(b, new Runnable() {
                @Override public void run() {
                    onBanner(b.actionId);
                }
            });
            mBanners.addView(v, k.lpMatch(ConsoleTheme.GAP_CARD, 0));
        }
        if (s.voiceprint != null || mVoiceRow != null) updateVoiceRow();
        // 语音引擎还在启动：过 2 秒再探一次（最多 6 次），别让首页一直停在「启动中」
        if (s.done && !s.paused && ConsoleStatus.wake(s).level == ConsoleStatus.Level.WARN && mRetries < 6) {
            mRetries++;
            mScroll.postDelayed(new Runnable() {
                @Override public void run() {
                    if (view != null && view.isAttachedToWindow()) refresh();
                }
            }, 2000);
        } else if (ConsoleStatus.wake(s).level == ConsoleStatus.Level.OK) {
            mRetries = 0;
        }
    }

    private int mRetries;

    private void onBanner(String id) {
        if (ConsoleStatus.Banner.ACT_PERM.equals(id)) {   // ★ design-1118（mz21-perm）
            c.push(new PermSetupPage(c));
            return;
        }
        if (ConsoleStatus.Banner.ACT_WAKE.equals(id)) {
            c.startWakeService();
            mScroll.postDelayed(new Runnable() {
                @Override public void run() {
                    refresh();
                }
            }, 2500);
        } else if (ConsoleStatus.Banner.ACT_KEY.equals(id)) {
            c.editApiKey(new Runnable() {
                @Override public void run() {
                    refresh();
                }
            });
        } else if (ConsoleStatus.Banner.ACT_OVERLAY.equals(id)) {
            c.openOverlaySettings();
        } else if (ConsoleStatus.Banner.ACT_BATTERY.equals(id)) {
            c.joinBatteryWhitelist(new Runnable() {
                @Override public void run() {
                    refresh();
                }
            });
        } else if (ConsoleStatus.Banner.ACT_ASR_KEY.equals(id)) {   // r2/p0 P0-2
            c.editAsrKey(new Runnable() {
                @Override public void run() {
                    refresh();
                }
            });
        } else if (ConsoleStatus.Banner.ACT_MIC.equals(id)) {
            c.openAppSettings();
        } else if (ConsoleStatus.Banner.ACT_VOICEPRINT.equals(id)) {
            c.push(new VoicePage(c));
        } else {
            c.push(new StatusPage(c));
        }
    }

    private String mTtsOff;
    private boolean mFullAnswer;

    private void updateVoiceRow() {
        if (mVoiceRow == null || mSnap == null) return;
        // ★ design-1118：「基础设置」一行只说有什么，不堆状态
        mVoiceRow.setSub("模型 · 语音 · 唤起 · 外观 · 隐私");
    }

    /** 重新探测状态 + 刷新大卡 / 入口行副标题。 */
    void refresh() {
        StatusProbe.run(c.act, c.compiler, new StatusProbe.Listener() {
            @Override public void onStatus(ConsoleStatus.Snapshot s) {
                if (view != null) render(s);
            }
        });
        final Context app = c.act.getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("console-home", new Runnable() {
            @Override public void run() {
                final String[] note = FeatureKernel.get().consoleTile(app, "quicknote");
                final String[] mem = FeatureKernel.get().consoleTile(app, "s2");
                com.onesay.assistant.v2.kernel.FeaturePlugin qn = FeatureKernel.get().find("quicknote");
                final boolean qnOn = qn != null && qn.enabled(app);
                final String tts = com.onesay.assistant.v2.ui.TtsSpeaker.isEnabled(app) ? "语音回复开" : "语音回复关";
                final boolean fullAnswer = com.onesay.assistant.v2.ui.TtsSpeaker.isFullAnswerEnabled(app);
                final int plugOn = DesignPages.countOnPlugins(app);
                final String plan = PlanSection.sub(com.onesay.assistant.v2.planner.PlannerLink.installed(app),
                        com.onesay.assistant.v2.planner.PlannerLink.SWITCH.on(app));
                c.ui(new Runnable() {
                    @Override public void run() {
                        if (view == null) return;
                        fillTile(mNoteTile, note);
                        fillTile(mMemTile, mem);
                        mPlanRow.setSub(plan);
                        mTtsOff = tts;
                        mFullAnswer = fullAnswer;
                        mQuickNoteOn = qnOn;
                        if (mSnap != null) mHint.setText(ConsoleStatus.heroHint(mSnap.paused, qnOn));
                        updateVoiceRow();
                        mAboutRow.setSub("专家选项 · 运行状态 · 日志 · 关于" + (c.devMode() ? " · 开发者" : ""));
                        mPluginsRow.setSub("已开 " + plugOn + " 个 · 开着才显示设置");
                    }
                });
            }
        });
    }

    private static void fillTile(ConsoleKit.Tile t, String[] v) {
        if (v == null || v.length < 2) {
            t.number.setText("—");
            t.sub.setText("未知");
            return;
        }
        t.number.setText(v[0]);
        t.sub.setText(v[1]);
    }

    @Override public void onShown() {
        refresh();
        fillPc();   // 扫码配对回来 / 在细项页解除配对后，首页这一组跟着变
    }

    /**
     * [fix-1123-c] 首页「连接电脑」：没开 → 一行「打开」；没配对 → 「扫码连电脑」「粘贴配对码」两行直接做；配对了 → 一行显示连的是哪台电脑，点进去管理。
     * 行为都是电脑联动原来的（同一个扫码页、同一个粘贴框、同一个开关），只是入口挪到首页。
     */
    /** ★ 1123-dui B17'：状态没变且组里已有内容才跳过；组是空的（重建过）一定重填。 */
    static boolean needFill(String st, String prev, int childCount) {
        return childCount == 0 || !st.equals(prev);
    }

    void fillPc() {
        if (mPcGroup == null) return;
        final android.app.Activity act = c.act;
        final com.onesay.assistant.v2.kernel.FeatureSwitch sw = com.onesay.assistant.v2.pclink.PcLink.SWITCH;
        String st = PcEntry.state(sw.on(act), sw.killed(act), com.onesay.assistant.v2.pclink.PcLink.paired(act), com.onesay.assistant.v2.pclink.PcLink.keyLost(act))
                + "|" + com.onesay.assistant.v2.pclink.PcLink.pcName(act);
        if (!needFill(st, mPcState, mPcGroup.getChildCount())) return;
        mPcState = st;
        mPcGroup.removeAllViews();
        String state = PcEntry.state(sw.on(act), sw.killed(act), com.onesay.assistant.v2.pclink.PcLink.paired(act), com.onesay.assistant.v2.pclink.PcLink.keyLost(act));
        if (PcEntry.KILLED.equals(state)) {
            k.valueRow(mPcGroup, "连接电脑", com.onesay.assistant.v2.kernel.ConsoleHost.KILLED, "", com.onesay.assistant.v2.kernel.ConsoleHost.TONE_MUTED);
            return;
        }
        if (PcEntry.OFF.equals(state)) {
            k.actionRow(mPcGroup, "连接电脑", "电脑联动关着。打开后就能扫码连上电脑上的 OneSay", "打开", new Runnable() {
                @Override public void run() {
                    sw.set(act, true);
                    fillPc();
                }
            });
            return;
        }
        if (PcEntry.UNPAIRED.equals(state)) {
            // [rel-1123 合并] 免扫码：找同一网络里的电脑，选它、输电脑上设的 4 位配对码（和「电脑联动」页同一个流程）
            k.actionRow(mPcGroup, "找附近的电脑", "不用扫码：手机和电脑连同一个 Wi-Fi（或都开着 Tailscale），选电脑、输电脑上设的 4 位配对码", "找", new Runnable() {
                @Override public void run() {
                    com.onesay.assistant.v2.pclink.PcLinkUi.nearby(c, act);
                }
            });
            k.actionRow(mPcGroup, "扫码连电脑", "电脑上 OneSay 主页点「配对」，扫出来的二维码（要用相机）", "扫码", new Runnable() {
                @Override public void run() {
                    act.startActivity(new android.content.Intent(act, com.onesay.assistant.v2.pclink.PcScanActivity.class));
                }
            });
            k.actionRow(mPcGroup, "粘贴配对码", "扫不了码时：电脑上点「复制配对串」，发到手机上，在这里粘贴", "粘贴", new Runnable() {
                @Override public void run() {
                    com.onesay.assistant.v2.pclink.PcLinkUi.pasteDialog(act, new Runnable() {
                        @Override public void run() { fillPc(); }
                    });
                }
            });
            return;
        }
        String name = com.onesay.assistant.v2.pclink.PcLink.pcName(act);
        k.linkRow(mPcGroup, PcEntry.KEY_LOST.equals(state) ? "要重新配对" : "已连 " + name,
                PcEntry.KEY_LOST.equals(state) ? "配对信息解不开了（换机还原 / 系统清过密钥），点进去解除后重新扫码" : "点进去看在线状态、让电脑做事、解除配对",
                ConsoleIcon.LINK, new Runnable() {
                    @Override public void run() {
                        c.openPage(com.onesay.assistant.v2.pclink.PcLinkUi.PAGE, com.onesay.assistant.v2.pclink.PcLinkUi.TITLE);
                    }
                });
    }
}
