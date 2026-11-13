package com.onesay.plan.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.plan.core.Capabilities;
import com.onesay.plan.core.Contract;
import com.onesay.plan.core.Fmt;
import com.onesay.plan.core.Row;
import com.onesay.plan.core.Shortcuts;
import com.onesay.plan.data.Settings;
import com.onesay.plan.data.Store;
import com.onesay.plan.platform.CapabilityProbe;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

/**
 * 「快捷指令」的列表 / 详情 / 编辑（自动任务页的第一段，与定时、触发并列）。只管列表、编辑、触发；执行在 OneSay。
 * 点击运行 = 往 shortcut_runs 插一行 status=requested，OneSay 的 ContentObserver 看到后认领并执行（见 docs/SHORTCUTS.md §3）。
 */
final class ShortcutUi {

    final MainActivity a;
    final Ui ui;
    final Editors ed;
    final TimeZone tz = TimeZone.getDefault();

    ShortcutUi(MainActivity a, Editors ed) {
        this.a = a;
        this.ui = a.ui;
        this.ed = ed;
    }

    // ------------------------------------------------------------------ 列表

    void section(LinearLayout col, LinearLayout card) {
        List<Row> all = Store.list(a, Contract.T_SHORTCUTS, null, null, "flagged DESC, last_run_at DESC, _id");
        Shortcuts.Actions.Catalog cat = Settings.shortcutCatalog(a);
        if (all.isEmpty()) card.addView(ui.empty("还没有快捷指令", "在 OneSay 结果卡上点「存为快捷指令」，或点右下角＋拼一条"));
        for (Row r : all) {
            if (card.getChildCount() > 0) card.addView(ui.divider());
            card.addView(item(r, cat));
        }
    }

    View item(final Row r, Shortcuts.Actions.Catalog cat) {
        String sub;
        try {
            sub = Shortcuts.summary(Shortcuts.parseSteps(r.s("steps_json")), cat);
        } catch (IllegalArgumentException e) {
            sub = "步骤损坏：" + e.getMessage();
        }
        if (r.l("last_run_at") > 0) sub += " · 上次 " + Fmt.when(r.l("last_run_at"), System.currentTimeMillis(), tz) + " " + statusZh(r.s("last_status"));
        boolean on = r.l("enabled", 1) != 0;
        TextView run = null;
        if (on && Shortcuts.trigger(r.s("triggers_json"), Contract.TRIG_TAP)) {
            run = ui.pill("运行", Ui.C_AUTO, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    run(r, Contract.TRIG_TAP);
                }
            });
        }
        LinearLayout it = ui.item(r.l("flagged") != 0 ? Ui.RED : Ui.C_AUTO, r.s("name"), sub, run, !on);
        if (r.l("flagged") != 0) {
            LinearLayout tc = (LinearLayout) it.getChildAt(1);
            tc.addView(ui.text(r.s("flag_reason").length() > 0 ? r.s("flag_reason") : "需要修改", 12, Ui.RED, null));
        }
        it.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                detail(r.id());
            }
        });
        return it;
    }

    static String statusZh(String st) {
        if (Contract.SC_OK.equals(st)) return "已执行";
        if (Contract.SC_FALLBACK_OK.equals(st)) return "已执行（已更新）";
        if (Contract.SC_FAIL.equals(st) || Contract.SC_FALLBACK_FAIL.equals(st)) return "没做完";
        if (Contract.SC_CANCELLED.equals(st)) return "已取消";
        if (Contract.SC_DENIED.equals(st)) return "未授权";
        if (Contract.SC_REQUESTED.equals(st) || Contract.SC_QUEUED.equals(st)) return "等 OneSay 执行";
        if (Contract.SC_STALE.equals(st)) return "OneSay 没在运行";
        return st;
    }

    /** 点击 / 桌面快捷方式：发起一次运行。 */
    static long request(android.content.Context c, long id, String trigger) {
        return Store.insert(c, Contract.T_SHORTCUT_RUNS, Shortcuts.request(id, trigger, System.currentTimeMillis()), false, null);
    }

    void run(Row r, String trigger) {
        try {
            long runId = request(a, r.id(), trigger);
            Toast.makeText(a, "已交给 OneSay 执行「" + r.s("name") + "」", Toast.LENGTH_SHORT).show();
            a.watchShortcutRun(runId, r.s("name"));   // 结束时在计划 App 里提示结果（沉浸全屏时岛被隐藏）
        } catch (IllegalArgumentException e) {
            Toast.makeText(a, "没发出去：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ 详情

    void detail(final long id) {
        final Row r = Store.get(a, Contract.T_SHORTCUTS, id);
        if (r == null) return;
        long now = System.currentTimeMillis();
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(4));
        Shortcuts.Actions.Catalog cat = Settings.shortcutCatalog(a);
        try {
            int i = 1;
            for (Shortcuts.Step s : Shortcuts.parseSteps(r.s("steps_json"))) {
                Shortcuts.Actions.Action act = cat.get(s.tool);
                String label = s.title.length() > 0 ? s.title : act != null ? act.label : s.tool;
                List<String> ask = Shortcuts.askParams(s);
                String line = i++ + ". " + label + argsText(s) + (ask.isEmpty() ? "" : "（每次问：" + join(ask) + "）");
                boolean grey = act != null && !act.available;
                box.addView(ui.text(line + (grey ? "\n   不可用：" + act.reason : ""), 14, grey ? Ui.TEXT3 : Ui.TEXT, null));
            }
        } catch (IllegalArgumentException e) {
            box.addView(ui.text("步骤损坏：" + e.getMessage(), 14, Ui.RED, null));
        }
        box.addView(ui.text("版本 " + r.l("version", 1) + (Shortcuts.canUndo(r) ? " · 可撤销到上一版" : ""), 13, Ui.TEXT3, null));
        TextView h = ui.text("最近运行", 13, Ui.TEXT3, Ui.MEDIUM);
        h.setPadding(0, ui.dp(14), 0, ui.dp(4));
        box.addView(h);
        List<Row> runs = Store.list(a, Contract.T_SHORTCUT_RUNS, "shortcut_id=?", new String[]{String.valueOf(id)}, "started_at DESC LIMIT 10");
        if (runs.isEmpty()) box.addView(ui.text("还没跑过", 14, Ui.TEXT2, null));
        for (Row x : runs) {
            String st = Shortcuts.isStale(x, now) ? Contract.SC_STALE : x.s("status");
            box.addView(ui.text(Fmt.when(x.l("started_at"), now, tz) + "  " + Shortcuts.historyStatus(statusZh(st), x.s("result")), 13.5f,
                    Contract.SC_OK.equals(st) || Contract.SC_FALLBACK_OK.equals(st) ? Ui.GREEN : Ui.TEXT2, null));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(r.s("name")).setView(ed.scroll(box))
                .setPositiveButton("编辑", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        editor(r);
                    }
                });
        if (Shortcuts.canUndo(r)) {
            b.setNeutralButton("撤销到上一版", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    Row u = Shortcuts.undo(r);
                    if (u != null) Store.update(a, Contract.T_SHORTCUTS, Editors.one(id), u, null);
                    Toast.makeText(a, "已撤销到上一版", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            b.setNeutralButton("删除", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    ed.confirmDelete(Contract.T_SHORTCUTS, id, r.s("name"));
                }
            });
        }
        b.setNegativeButton("关闭", null).show();
    }

    static String argsText(Shortcuts.Step s) {
        StringBuilder sb = new StringBuilder();
        Iterator<?> it = s.args.keys();
        while (it.hasNext()) {
            String k = String.valueOf(it.next());
            if (s.args.isNull(k) || s.ask.contains(k)) continue;
            sb.append(sb.length() == 0 ? "：" : "，").append(k).append("=").append(s.args.opt(k));
        }
        return sb.toString();
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? "、" : "").append(s);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 编辑

    static final class StepView {
        Shortcuts.Step step;
        final List<EditText> fields = new ArrayList<EditText>();
        final List<Shortcuts.Actions.Param> params = new ArrayList<Shortcuts.Actions.Param>();
    }

    void editor(final Row r) {
        final Shortcuts.Actions.Catalog cat = Settings.shortcutCatalog(a);
        final LinearLayout f = ed.form();
        final EditText name = ed.field(f, "名字（也是语音触发词，如：晚安）", r == null ? "" : r.s("name"));
        ed.label(f, "步骤（参数留空 = 每次运行时问；写 {{prev}} = 用上一步的结果）");
        final LinearLayout stepsBox = ui.col();
        f.addView(stepsBox);
        final List<StepView> views = new ArrayList<StepView>();
        if (r != null) {
            try {
                for (Shortcuts.Step s : Shortcuts.parseSteps(r.s("steps_json"))) addStep(stepsBox, views, s, cat);
            } catch (IllegalArgumentException ignored) {
            }
        }
        f.addView(ui.pill("＋ 加一步", Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickAction(cat, stepsBox, views);
            }
        }));
        String trig = r == null ? Shortcuts.DEFAULT_TRIGGERS : r.s("triggers_json");
        // 「开启」是整条快捷指令的总开关，挪出「定时」那一组（原来紧跟在时间行后面，像是定时的开关）
        final Switch on = ed.toggle(f, "启用这条快捷指令", r == null || r.l("enabled", 1) != 0);
        ed.label(f, "怎么触发");
        final Switch voice = ed.toggle(f, "对 OneSay 说名字", Shortcuts.trigger(trig, Contract.TRIG_VOICE));
        final Switch tap = ed.toggle(f, "在计划里点「运行」", Shortcuts.trigger(trig, Contract.TRIG_TAP));
        final Switch launcher = ed.toggle(f, "桌面快捷方式", Shortcuts.trigger(trig, Contract.TRIG_LAUNCHER));
        Capabilities.Status pin = Capabilities.of(Capabilities.Feature.PIN_SHORTCUT, CapabilityProbe.probe(a));
        if (!pin.available) {
            launcher.setEnabled(false);
            launcher.setChecked(false);
            launcher.setText("桌面快捷方式（不可用：" + pin.reason + "）");
        }
        final Switch time = ed.toggle(f, "定时（到点由 OneSay 执行）", Shortcuts.trigger(trig, Contract.TRIG_TIME));
        final Row auto = r == null ? null : timedRow(r.id());
        final long[] at = {auto != null ? ed.atOf(auto) : ed.nextHour()};
        // 关掉「定时」后不再显示时间行和重复（时间与重复放进 timeBox，跟着开关显示 / 隐藏）
        final LinearLayout timeBox = ui.col();
        f.addView(timeBox);
        ed.whenField(timeBox, at, false, "选时间");
        final Spinner rep = ed.spinner(timeBox, Editors.AUTO_REPEATS, auto == null ? 1 : (int) auto.l("repeat"));
        timeBox.setVisibility(time.isChecked() ? View.VISIBLE : View.GONE);
        time.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                timeBox.setVisibility(checked ? View.VISIBLE : View.GONE);
            }
        });
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(r == null ? "新建快捷指令" : "编辑快捷指令").setView(ed.scroll(f))
                .setPositiveButton("保存", null).setNegativeButton("取消", null);
        if (r != null) b.setNeutralButton("删除", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                ed.confirmDelete(Contract.T_SHORTCUTS, r.id(), r.s("name"));
            }
        });
        final AlertDialog d = b.create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            long id = save(r, Editors.str(name), collect(views), voice.isChecked(), tap.isChecked(), launcher.isChecked(), time.isChecked(),
                                    at[0], rep.getSelectedItemPosition(), on.isChecked());
                            if (launcher.isChecked() && (r == null || !Shortcuts.trigger(r.s("triggers_json"), Contract.TRIG_LAUNCHER))) pin(id, Editors.str(name));
                            d.dismiss();
                        } catch (IllegalArgumentException e) {
                            Toast.makeText(a, "没存上：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
        d.show();
    }

    void pickAction(final Shortcuts.Actions.Catalog cat, final LinearLayout stepsBox, final List<StepView> views) {
        final List<Shortcuts.Actions.Action> l = cat.actions;
        String[] items = new String[l.size()];
        for (int i = 0; i < items.length; i++) {
            Shortcuts.Actions.Action x = l.get(i);
            items[i] = x.group + " · " + x.label + (x.available ? "" : "（不可用：" + x.reason + "）");
        }
        new AlertDialog.Builder(a).setTitle("加一步").setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dd, int w) {
                Shortcuts.Actions.Action x = l.get(w);
                if (!x.available) {   // 置灰项：不加，只说原因
                    Toast.makeText(a, x.label + " 不可用：" + x.reason, Toast.LENGTH_LONG).show();
                    return;
                }
                Shortcuts.Step s = new Shortcuts.Step();
                s.tool = x.sign;
                addStep(stepsBox, views, s, cat);
            }
        }).show();
    }

    void addStep(final LinearLayout box, final List<StepView> views, Shortcuts.Step s, Shortcuts.Actions.Catalog cat) {
        final StepView sv = new StepView();
        sv.step = s;
        final LinearLayout one = ui.col();
        Shortcuts.Actions.Action act = cat.get(s.tool);
        String title = (act != null ? act.label : s.tool) + (act != null && !act.available ? "（不可用：" + act.reason + "）" : "");
        TextView t = ui.text((views.size() + 1) + ". " + title + "   ✕", 15, act != null && !act.available ? Ui.TEXT3 : Ui.TEXT, Ui.MEDIUM);
        t.setPadding(0, ui.dp(8), 0, 0);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                views.remove(sv);
                box.removeView(one);
            }
        });
        one.addView(t);
        List<Shortcuts.Actions.Param> ps = new ArrayList<Shortcuts.Actions.Param>();
        if (act != null) ps.addAll(act.params);
        Iterator<?> it = s.args.keys();
        while (it.hasNext()) {   // 从对话里存下来的参数可能不在清单里，也给一个框
            String k = String.valueOf(it.next());
            boolean known = false;
            for (Shortcuts.Actions.Param p : ps) if (p.name.equals(k)) known = true;
            if (!known) ps.add(newParam(k));
        }
        List<String> ask = Shortcuts.askParams(s);
        for (Shortcuts.Actions.Param p : ps) {
            String v = ask.contains(p.name) || !s.args.has(p.name) ? "" : String.valueOf(s.args.opt(p.name));
            EditText e = ed.field(one, p.label + "（留空 = 每次问）", v);
            if ("int".equals(p.type)) e.setInputType(InputType.TYPE_CLASS_NUMBER);
            sv.fields.add(e);
            sv.params.add(p);
        }
        views.add(sv);
        box.addView(one);
    }

    static Shortcuts.Actions.Param newParam(String k) {
        return new Shortcuts.Actions.Param(k, k, "string");   // 从对话里存下来、清单里没有的参数按字符串
    }

    static String collect(List<StepView> views) {
        List<Shortcuts.Step> out = new ArrayList<Shortcuts.Step>();
        for (StepView sv : views) {
            Shortcuts.Step s = new Shortcuts.Step();
            s.tool = sv.step.tool;
            s.title = sv.step.title;
            JSONObject args = new JSONObject();
            try {
                for (int i = 0; i < sv.params.size(); i++) {
                    Shortcuts.Actions.Param p = sv.params.get(i);
                    String v = Editors.str(sv.fields.get(i));
                    if (v.length() == 0) {
                        s.ask.add(p.name);
                        args.put(p.name, JSONObject.NULL);
                    } else if ("int".equals(p.type)) {
                        try {
                            args.put(p.name, Long.parseLong(v));
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException(p.label + " 要填整数");
                        }
                    } else {
                        args.put(p.name, v);
                    }
                }
            } catch (org.json.JSONException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
            s.args = args;
            out.add(s);
        }
        return Shortcuts.stepsJson(out);
    }

    long save(Row r, String name, String steps, boolean voice, boolean tap, boolean launcher, boolean time, long at, int rep, boolean on) {
        Row x = new Row().with("name", name).with("triggers_json", Shortcuts.triggers(voice, tap, launcher, time)).with("enabled", on ? 1 : 0);
        long id;
        if (r == null) {
            x.with("steps_json", steps).with("origin", "ui");
            id = Store.insert(a, Contract.T_SHORTCUTS, x, false, null);
        } else {
            id = r.id();
            if (!steps.equals(Shortcuts.stepsJson(Shortcuts.parseSteps(r.s("steps_json"))))) {
                x.putAll(Shortcuts.replaceSteps(r, steps, "手动修改", System.currentTimeMillis()));   // 旧版本进历史，可撤销；清掉标红
            }
            Store.update(a, Contract.T_SHORTCUTS, Editors.one(id), x, null);
        }
        syncTimed(id, name, time && on, at, rep);
        return id;
    }

    Row timedRow(long id) {
        List<Row> l = Store.list(a, Contract.T_AUTOMATIONS, "src_key=?", new String[]{Contract.SC_TIME_SRC_PREFIX + id}, null);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 定时触发 = 一条自动任务（复用 OneSay 的定时执行）；关掉就删。 */
    void syncTimed(long id, String name, boolean want, long at, int rep) {
        Row ex = timedRow(id);
        if (!want) {
            if (ex != null) Store.delete(a, Contract.T_AUTOMATIONS, Editors.one(ex.id()), null);
            return;
        }
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(at);
        Row w = Shortcuts.timedAutomation(id, name).with("repeat", rep).with("hour", k.get(Calendar.HOUR_OF_DAY)).with("minute", k.get(Calendar.MINUTE))
                .with("once_at", rep == 0 ? at : 0L).with("week_mask", rep == 2 ? 1 << (k.get(Calendar.DAY_OF_WEEK) - 1) : 0).with("enabled", 1);
        if (ex == null) Store.insert(a, Contract.T_AUTOMATIONS, w, false, null);
        else {
            w.remove(Contract.C_SRC_KEY);
            w.remove("owner");
            Store.update(a, Contract.T_AUTOMATIONS, Editors.one(ex.id()), w, null);
        }
    }

    /** 加到桌面（API 26+ requestPinShortcut）；不支持时已在开关上置灰并写原因。 */
    void pin(long id, String name) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            android.content.pm.ShortcutManager sm = a.getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                Toast.makeText(a, "这个桌面不支持添加快捷方式", Toast.LENGTH_LONG).show();
                return;
            }
            com.onesay.plan.platform.PinnedShortcuts.enableIfDisabled(a, id);
            Intent i = new Intent(a, ShortcutRunActivity.class).setAction(ShortcutRunActivity.ACTION).putExtra(ShortcutRunActivity.EXTRA_ID, id);
            android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(a, Shortcuts.pinId(id)).setShortLabel(name).setIntent(i)
                    .setIcon(android.graphics.drawable.Icon.createWithResource(a, com.onesay.plan.R.mipmap.ic_launcher)).build();
            sm.requestPinShortcut(info, null);
        } catch (Throwable t) {
            Toast.makeText(a, "没加上桌面：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
