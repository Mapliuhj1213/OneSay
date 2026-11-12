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
    private ConsoleKit.RowImpl mVoiceRow, mAboutRow, mPlanRow, mDevHead;
    private LinearLayout mDevBody;
    private boolean mDevExpanded = ConsoleGroups.DEV_EXPANDED_BY_DEFAULT;
    private boolean mDevBuiltFor;
    private ConsoleStatus.Snapshot mSnap;
    private boolean mQuickNoteOn = true;

    HomePage(Console c) {
        this.c = c;
    }

    @Override protected View create() {
        k = c.kit;
        LinearLayout col = k.column();
        mScroll = k.scroll(col);
        col.addView(k.largeTitle(com.onesay.assistant.v2.BuildVariant.APP_TITLE, "v2 · " + Console.RELEASE));
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

        // ---- 设置（★ console-align 2026-11-12，ConsoleGroups.GROUPS 的顺序 = 电脑端设置页的顺序）：
        //      连接 · 权限与审批 · 模型与密钥 · 语音 · 替你操作 · 功能 · 下载 · 插件
        LinearLayout g1 = k.group(col, "设置");
        k.linkRow(g1, "连接", ConsoleGroups.defaultSub(ConsoleGroups.CONNECT), ConsoleIcon.LINK, new Runnable() {
            @Override public void run() {
                c.push(new GroupPages.ConnectPage(c));
            }
        });
        k.linkRow(g1, "权限与审批", ConsoleGroups.defaultSub(ConsoleGroups.PERM), ConsoleIcon.SHIELD, new Runnable() {
            @Override public void run() {
                c.push(new GroupPages.PermPage(c));
            }
        });
        k.linkRow(g1, "模型与密钥", ConsoleGroups.defaultSub(ConsoleGroups.MODEL), ConsoleIcon.GEAR, new Runnable() {
            @Override public void run() {
                c.push(new AdvancedPage(c));
            }
        });
        mVoiceRow = k.linkRow(g1, "语音", ConsoleGroups.defaultSub(ConsoleGroups.VOICE), ConsoleIcon.MIC, new Runnable() {
            @Override public void run() {
                c.push(VoicePage.all(c));
            }
        });
        k.linkRow(g1, "替你操作", ConsoleGroups.defaultSub(ConsoleGroups.ACT), ConsoleIcon.HAND, new Runnable() {
            @Override public void run() {
                c.push(new GroupPages.ActPage(c));
            }
        });
        k.linkRow(g1, "功能", ConsoleGroups.defaultSub(ConsoleGroups.FEATURES), ConsoleIcon.SLIDERS, new Runnable() {
            @Override public void run() {
                c.push(new FeaturesPage(c));
            }
        });
        k.linkRow(g1, "下载", ConsoleGroups.defaultSub(ConsoleGroups.DOWNLOAD), ConsoleIcon.DOWNLOAD, new Runnable() {
            @Override public void run() {
                c.push(new GroupPages.DownloadPage(c));
            }
        });
        k.linkRow(g1, "插件", ConsoleGroups.defaultSub(ConsoleGroups.PLUGINS), ConsoleIcon.PUZZLE, new Runnable() {
            @Override public void run() {
                c.push(new AdvancedPage.PluginsPage(c));
            }
        });

        LinearLayout ga = k.group(col, null);
        mAboutRow = k.linkRow(ga, "关于", "v2 " + Console.RELEASE + (c.devMode() ? " · 开发者模式" : ""), ConsoleIcon.INFO, new Runnable() {
            @Override public void run() {
                c.push(new AboutPage(c));
            }
        });

        // ---- 开发者（放最下，默认折叠）：诊断与摘要 · 调试工具（开发者模式开着才有，与之前一样）
        LinearLayout gd = k.group(col, null);
        mDevHead = k.linkRow(gd, ConsoleGroups.DEV_TITLE, ConsoleGroups.DEV_SUB_COLLAPSED, ConsoleIcon.CODE, new Runnable() {
            @Override public void run() {
                setDevExpanded(!mDevExpanded);
            }
        });
        mDevBody = k.column();
        gd.addView(mDevBody, k.lpMatch(0, 0));
        fillDev();
        setDevExpanded(ConsoleGroups.DEV_EXPANDED_BY_DEFAULT);

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

    /** 开发者分组的子行（开发者模式变了要重画：「调试工具」只在开发者模式开着时出现）。 */
    private void fillDev() {
        mDevBody.removeAllViews();
        mDevBuiltFor = c.devMode();
        k.linkRow(mDevBody, "诊断与摘要", "设置诊断 · 长内容摘要 · 旧插件卡片", 0, new Runnable() {
            @Override public void run() {
                c.push(new GroupPages.DevFeaturesPage(c));
            }
        });
        // ★ console-align：「功能插件」搬到设置区「插件」
        if (mDevBuiltFor) {
            k.linkRow(mDevBody, "调试工具", "调试按钮 · 自测 · 关停文件 · 阈值 · 日志", 0, new Runnable() {
                @Override public void run() {
                    c.push(new DeveloperPage(c));
                }
            });
        }
    }

    private void setDevExpanded(boolean on) {
        mDevExpanded = on;
        mDevBody.setVisibility(on ? View.VISIBLE : View.GONE);
        mDevHead.setSub(on ? ConsoleGroups.DEV_SUB_EXPANDED : ConsoleGroups.DEV_SUB_COLLAPSED);
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
        for (ConsoleStatus.Pill p : ConsoleStatus.pills(s)) mPills.addView(k.pill(p));
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
        String vp = mSnap.voiceprint == null ? "声纹 未知" : (mSnap.voiceprint ? "声纹已注册" : "声纹未注册");
        // ★ console-align：「语音」= 唤醒 + 朗读一页，副标题两样都带
        mVoiceRow.setSub(vp + " · 唤醒手感 · 提示音" + (mTtsOff != null ? " · " + mTtsOff + " · 朗读完整回答" + (mFullAnswer ? "开" : "关") : " · 朗读"));
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
                        if (mDevBuiltFor != c.devMode()) fillDev();
                        mQuickNoteOn = qnOn;
                        if (mSnap != null) mHint.setText(ConsoleStatus.heroHint(mSnap.paused, qnOn));
                        updateVoiceRow();
                        mAboutRow.setSub("v2 " + Console.RELEASE + (c.devMode() ? " · 开发者模式" : ""));
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
    }
}
