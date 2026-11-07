package com.onesay.assistant.v2.soul;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.s2.S2Clock;

import java.util.List;

/**
 * [soul] 控制台「记忆」页里的人设与长期记忆：查看 / 编辑 / 清空 SOUL 与 MEMORY（由 MemoryPlugin 委托）。
 * <ul>
 *   <li>记忆页：入口两行（人设 / 长期记忆）+ 注入、会话整理开关 + 本轮注入字数；</li>
 *   <li>{@link #PAGE_SOUL}：人设全文、编辑（手改即锁定）、待确认的提议（批准 / 拒绝）、回滚上一版、恢复默认、清空历史、名字 / 称呼 / 语气；</li>
 *   <li>{@link #PAGE_LONG}：画像与核心记忆逐条（编辑 = 锁定不被整理覆盖 / 置顶 / 删除 = 真删）、最近日志、清空长期记忆。</li>
 * </ul>
 */
public final class SoulConsole {

    private SoulConsole() {}

    public static final String PAGE_SOUL = "memory.soul";
    public static final String PAGE_LONG = "memory.long";

    /** @return true = 这个页面是 soul 的（已画完） */
    public static boolean addEntries(ConsoleHost host, String page, LinearLayout root) {
        try {
            if (ConsoleHost.PAGE_MEMORY.equals(page)) {
                memoryPage(host, root);
                return false;   // 记忆页其它分组（记一下开关、默认应用）照常画
            }
            if (PAGE_SOUL.equals(page)) {
                soulPage(host, root);
                return true;
            }
            if (PAGE_LONG.equals(page)) {
                longPage(host, root);
                return true;
            }
        } catch (Throwable t) {
            host.footer(root, "人设 / 长期记忆暂时打不开：" + t.getMessage());
        }
        return false;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    static void memoryPage(final ConsoleHost host, LinearLayout root) {
        final Activity act = host.activity();
        SoulEngine e = SoulHooks.engine(act);
        long now = S2Clock.now();
        SoulVersion v = e.activeSoul(now);
        LinearLayout g = host.group(root, "人设与长期记忆");
        host.linkRow(g, "人设（SOUL）", MemoryText.clip(firstLine(v.text), 24) + (v.userLocked ? " · 你改过（已锁定）" : "")
                + (e.pendingProposals().isEmpty() ? "" : " · 有 " + e.pendingProposals().size() + " 条待确认"), new Runnable() {
            @Override public void run() { host.openPage(PAGE_SOUL, "人设"); }
        });
        java.util.Map<String, Integer> c = e.counts();
        host.linkRow(g, "长期记忆（MEMORY）", "画像 " + c.get(Claim.T_PROFILE) + " 条 · 核心 " + c.get(Claim.T_CORE) + " 条 · 日志 " + c.get(Claim.T_LOG) + " 条",
                new Runnable() {
                    @Override public void run() { host.openPage(PAGE_LONG, "长期记忆"); }
                });
        host.switchRow(g, "每轮带上人设和记忆", "问答时把人设、画像和关键记忆放进提示词（约 " + e.budget().totalMax + " 字以内）",
                SoulHooks.pref(act, SoulHooks.KEY_INJECT), new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) { SoulHooks.set(act, SoulHooks.KEY_INJECT, on); }
                });
        host.switchRow(g, "对话结束后整理", "面板关闭时请模型从你说的话里挑候选（只进日志，满足条件才转正）",
                SoulHooks.pref(act, SoulHooks.KEY_FLUSH), new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) { SoulHooks.set(act, SoulHooks.KEY_FLUSH, on); }
                });
        SoulEngine.Injection inj = e.inject(now, false);
        host.valueRow(g, "当前每轮注入", "人设 " + inj.soulChars + " · 画像 " + inj.profileChars + " · 核心 " + inj.coreChars + " 字",
                inj.chars() + " 字 ≈" + SoulBudget.estimateTokens(inj.prefix) + " tokens", ConsoleHost.TONE_NORMAL);
    }

    static void soulPage(final ConsoleHost host, final LinearLayout root) {
        final Activity act = host.activity();
        final SoulEngine e = SoulHooks.engine(act);
        final long now = S2Clock.now();
        final SoulVersion v = e.activeSoul(now);
        host.footer(root, v.text);
        LinearLayout g = host.group(root, v.userLocked ? "当前人设（你改过，已锁定）" : "当前人设");
        host.actionRow(g, "编辑人设", "手改后助手不会再提议覆盖（≤" + e.budget().soulMax + " 字）", "编辑", new Runnable() {
            @Override public void run() {
                edit(act, "编辑人设", v.text, new Edit() {
                    @Override public void done(String text) {
                        SoulEngine.SoulResult r = e.userEditSoul(text, S2Clock.now());
                        host.toast(r.ok ? "已保存（已锁定）" : r.reason);
                    }
                });
            }
        });
        for (final SoulVersion p : e.pendingProposals()) {
            final ConsoleHost.Row[] row = new ConsoleHost.Row[1];
            row[0] = host.actionRow(g, "待确认的修改", MemoryText.clip(p.text.replace(v.text, "").trim().length() > 0 ? p.text.replace(v.text, "").trim() : p.text, 60)
                    + (p.reason.length() > 0 ? "（" + p.reason + "）" : ""), "处理", new Runnable() {
                @Override public void run() {
                    new AlertDialog.Builder(act).setTitle("改人设？").setMessage(p.text)
                            .setPositiveButton("好", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    boolean ok = e.approveSoul(p.id, S2Clock.now());
                                    host.toast(ok ? "人设已更新，可随时回滚" : "没改成（人设可能被你手改过）");
                                    row[0].setEnabled(false);
                                }
                            })
                            .setNegativeButton("不要", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    e.rejectSoul(p.id);
                                    row[0].setSub("已拒绝");
                                    row[0].setEnabled(false);
                                }
                            }).show();
                }
            });
        }
        host.actionRow(g, "回滚到上一版", "所有版本都保留", "回滚", new Runnable() {
            @Override public void run() {
                host.confirm("回滚人设", "恢复到上一个版本？", "回滚", new Runnable() {
                    @Override public void run() { host.toast(e.rollbackSoul(S2Clock.now()) ? "已回滚" : "没有更早的版本"); }
                });
            }
        });
        host.actionRow(g, "恢复默认人设", "历史版本保留", "恢复", new Runnable() {
            @Override public void run() {
                host.confirm("恢复默认", "人设恢复成默认模板？", "恢复", new Runnable() {
                    @Override public void run() {
                        e.resetSoul(false, S2Clock.now());
                        host.toast("已恢复默认");
                    }
                });
            }
        });
        host.actionRow(g, "清空人设", "删掉所有历史版本和名字 / 称呼 / 语气，只留默认模板", "清空", new Runnable() {
            @Override public void run() {
                host.confirm("清空人设", "删除全部人设版本与身份设置，只留默认模板？不可恢复。", "清空", new Runnable() {
                    @Override public void run() {
                        e.resetSoul(true, S2Clock.now());
                        host.toast("已清空");
                    }
                });
            }
        });
        LinearLayout id = host.group(root, "身份");
        for (final String k : SoulTemplate.ID_KEYS) {
            final String cur = e.identity(k);
            final ConsoleHost.Row[] row = new ConsoleHost.Row[1];
            row[0] = host.actionRow(id, SoulTemplate.label(k), cur.length() > 0 ? cur : "未设置", "修改", new Runnable() {
                @Override public void run() {
                    edit(act, SoulTemplate.label(k), cur, new Edit() {
                        @Override public void done(String text) {
                            SoulEngine.SoulResult r = e.setIdentity(k, text);
                            host.toast(r.ok ? "已保存" : r.reason);
                            if (r.ok) row[0].setSub(text.trim().length() > 0 ? text.trim() : "未设置");
                        }
                    });
                }
            });
        }
        List<SoulVersion> all = e.versions();
        LinearLayout hist = host.group(root, "版本（最近 5 个）");
        for (int i = all.size() - 1, n = 0; i >= 0 && n < 5; i--, n++) {
            SoulVersion x = all.get(i);
            host.valueRow(hist, "#" + x.id + " " + label(x), MemoryText.clip(firstLine(x.text), 30), x.status, ConsoleHost.TONE_MUTED);
        }
    }

    private static String label(SoulVersion v) {
        if (SoulVersion.SRC_USER.equals(v.source)) return "你手改";
        if (SoulVersion.SRC_AGENT.equals(v.source)) return "助手提议";
        return "默认模板";
    }

    static void longPage(final ConsoleHost host, LinearLayout root) {
        final Activity act = host.activity();
        final SoulEngine e = SoulHooks.engine(act);
        host.footer(root, "画像和核心记忆每轮按预算带上（超出的按重要度和新近度挑整条）；日志只在你问起相关的事时检索。"
                + "90 天没再提到的不再自动带上；识屏、邮件、网页里的内容不会转成长期记忆。删除是真删。");
        claimGroup(host, root, e, "用户画像", e.active(Claim.T_PROFILE));
        claimGroup(host, root, e, "核心记忆", e.active(Claim.T_CORE));
        LinearLayout lg = host.group(root, "最近日志（只检索、不注入）");
        List<Claim> log = e.repo().list(Claim.T_LOG);
        int shown = 0;
        for (int i = log.size() - 1; i >= 0 && shown < 10; i--) {
            Claim c = log.get(i);
            if (Claim.K_EVIDENCE.equals(c.kind) || !Claim.S_OPEN.equals(c.status)) continue;
            host.valueRow(lg, MemoryText.clip(c.text, 24), c.day + (c.tainted ? " · 外部来源" : "") + (Claim.K_CANDIDATE.equals(c.kind) ? " · 候选" : " · 笔记"),
                    c.recallCount > 0 ? "召回 " + c.recallCount : "", ConsoleHost.TONE_MUTED);
            shown++;
        }
        if (shown == 0) host.valueRow(lg, "暂无", null, "", ConsoleHost.TONE_MUTED);
        LinearLayout danger = host.group(root, null);
        host.actionRow(danger, "清空长期记忆", "画像、核心记忆、日志全部删除（人设不动）", "清空", new Runnable() {
            @Override public void run() {
                host.confirm("清空长期记忆", "删除全部画像、核心记忆和日志？不可恢复。", "清空", new Runnable() {
                    @Override public void run() { host.toast("已清空 " + e.clearMemory() + " 条"); }
                });
            }
        });
    }

    private static void claimGroup(final ConsoleHost host, LinearLayout root, final SoulEngine e, String title, List<Claim> items) {
        final Activity act = host.activity();
        LinearLayout g = host.group(root, title + "（" + items.size() + "）");
        if (items.isEmpty()) {
            host.valueRow(g, "暂无", null, "", ConsoleHost.TONE_MUTED);
            return;
        }
        for (final Claim c : items) {
            final ConsoleHost.Row[] row = new ConsoleHost.Row[1];
            String sub = c.day + (c.pinned ? " · 置顶" : "") + (c.userEdited ? " · 你改过" : "")
                    + (Claim.P_HEALTH_FINANCE.equals(c.privacy) ? " · 健康/财务（不自动带上）" : "");
            row[0] = host.actionRow(g, MemoryText.clip(c.text, 40), sub, "管理", new Runnable() {
                @Override public void run() {
                    new AlertDialog.Builder(act).setTitle(MemoryText.clip(c.text, 40))
                            .setItems(new String[]{"编辑", c.pinned ? "取消置顶" : "置顶", "删除"}, new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int which) {
                                    if (which == 0) {
                                        edit(act, "编辑", c.text, new Edit() {
                                            @Override public void done(String text) {
                                                boolean ok = e.userEditClaim(c.id, text, S2Clock.now());
                                                host.toast(ok ? "已保存（整理不会覆盖）" : "没保存（不能写敏感内容）");
                                                if (ok) row[0].setSub("已修改");
                                            }
                                        });
                                    } else if (which == 1) {
                                        e.setPinned(c.id, !c.pinned);
                                        row[0].setSub(c.pinned ? "已取消置顶" : "已置顶");
                                    } else {
                                        host.confirm("删除", "从记忆里删掉这条（连同它的证据记录）？", "删除", new Runnable() {
                                            @Override public void run() {
                                                e.forget(new long[]{c.id}, S2Clock.now());
                                                row[0].setSub("已删除");
                                                row[0].setEnabled(false);
                                            }
                                        });
                                    }
                                }
                            }).show();
                }
            });
        }
    }

    interface Edit {
        void done(String text);
    }

    private static void edit(Activity act, String title, String text, final Edit cb) {
        final EditText et = new EditText(act);
        et.setText(text);
        et.setMinLines(2);
        new AlertDialog.Builder(act).setTitle(title).setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { cb.done(et.getText().toString()); }
                })
                .setNegativeButton("取消", null).show();
    }
}
