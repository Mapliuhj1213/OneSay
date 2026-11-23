package com.onesay.assistant.v2.ui.console;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TimePicker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * [fix-1123-c] 控制台里的选择框（档位 / 时间 / 已装 App 勾选 / 列表选一个），代替以前的手填输入框。选项怎么算在 {@link FormChoices}。
 * 只用系统自带的对话框和列表，不加 Activity、不改 Manifest。
 */
public final class Pickers {
    private Pickers() {}

    public interface OnInt { void pick(int v); }

    public interface OnText { void pick(String v); }

    public interface OnPkgs { void pick(Set<String> pkgs); }

    /** 档位单选（提前多少分钟）。 */
    public static void minutes(Activity act, String title, int lo, int hi, int current, final OnInt done) {
        final List<Integer> steps = FormChoices.minuteSteps(lo, hi, current);
        String[] labels = new String[steps.size()];
        int sel = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = FormChoices.minuteLabel(steps.get(i));
            if (steps.get(i) == current) sel = i;
        }
        new AlertDialog.Builder(act).setTitle(title).setSingleChoiceItems(labels, sel, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                d.dismiss();
                done.pick(steps.get(w));
            }
        }).setNegativeButton("取消", null).show();
    }

    /** 系统时间选择器（24 小时制）。 */
    public static void time(Activity act, String current, String def, final OnText done) {
        int[] hm = FormChoices.hm(current, def);
        new TimePickerDialog(act, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker v, int h, int m) { done.pick(FormChoices.formatHm(h, m)); }
        }, hm[0], hm[1], true).show();
    }

    /** 列表选一个；选到「自己填一个… / 列表里没有，自己填…」时再弹一个输入框（manualHint）。 */
    public static void one(final Activity act, String title, final List<String> items, String current, final String manualHint, final OnText done) {
        String[] labels = FormChoices.labels(items, current);
        int sel = current == null ? -1 : items.indexOf(current.trim());
        new AlertDialog.Builder(act).setTitle(title).setSingleChoiceItems(labels, sel, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                d.dismiss();
                String v = items.get(w);
                if (FormChoices.CUSTOM.equals(v) || FormChoices.MANUAL.equals(v)) manual(act, manualHint, done);
                else done.pick(v);
            }
        }).setNegativeButton("取消", null).show();
    }

    /** 兜底：自己填（放在列表最后一项，不是首选）。 */
    public static void manual(Activity act, String hint, final OnText done) {
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint(hint);
        new AlertDialog.Builder(act).setTitle("自己填").setView(et).setPositiveButton("好", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                String v = et.getText().toString().trim();
                if (v.length() > 0) done.pick(v);
            }
        }).setNegativeButton("取消", null).show();
    }

    /** 手机上能从桌面打开的 App（名字 + 包名）。 */
    public static List<FormChoices.App> launchable(Activity act) {
        List<FormChoices.App> out = new ArrayList<FormChoices.App>();
        try {
            PackageManager pm = act.getPackageManager();
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            if (l != null) for (ResolveInfo r : l) {
                if (r.activityInfo == null || act.getPackageName().equals(r.activityInfo.packageName)) continue;
                out.add(new FormChoices.App(String.valueOf(r.loadLabel(pm)), r.activityInfo.packageName));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 已装 App 勾选（可搜索；勾着的排前面）。读列表在后台做，读完再弹。 */
    public static void apps(final Activity act, final String title, final Set<String> checked0, final OnPkgs done) {
        com.onesay.assistant.v2.util.Threads.start("console-apps", new Runnable() {
            @Override public void run() {
                final List<FormChoices.App> all = launchable(act);
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!act.isFinishing()) showApps(act, title, all, checked0, done);
                    }
                });
            }
        });
    }

    static void showApps(Activity act, String title, List<FormChoices.App> all0, Set<String> checked0, final OnPkgs done) {
        final Set<String> checked = new LinkedHashSet<String>(checked0 == null ? new LinkedHashSet<String>() : checked0);
        // 以前填过、但手机上已经找不到的包名也列出来（能取消勾选）
        List<FormChoices.App> withOld = new ArrayList<FormChoices.App>(all0);
        for (String p : checked) {
            boolean has = false;
            for (FormChoices.App a : all0) if (a.pkg.equals(p)) { has = true; break; }
            if (!has) withOld.add(new FormChoices.App(p + "（没装）", p));
        }
        final List<FormChoices.App> all = FormChoices.sortApps(withOld, checked);
        final List<FormChoices.App> shown = new ArrayList<FormChoices.App>(all);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText search = new EditText(act);
        search.setSingleLine(true);
        search.setHint("搜索 App");
        box.addView(search);
        final ListView lv = new ListView(act);
        lv.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(act, android.R.layout.simple_list_item_multiple_choice, new ArrayList<String>());
        lv.setAdapter(ad);
        final Runnable fill = new Runnable() {
            @Override public void run() {
                ad.clear();
                for (FormChoices.App a : shown) ad.add(a.label);
                ad.notifyDataSetChanged();
                for (int i = 0; i < shown.size(); i++) lv.setItemChecked(i, checked.contains(shown.get(i).pkg));
            }
        };
        fill.run();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                String pkg = shown.get(pos).pkg;
                if (lv.isItemChecked(pos)) checked.add(pkg);
                else checked.remove(pkg);
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                shown.clear();
                shown.addAll(FormChoices.filter(all, s.toString()));
                fill.run();
            }
        });
        box.addView(lv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (360 * act.getResources().getDisplayMetrics().density)));
        new AlertDialog.Builder(act).setTitle(title).setView(box).setPositiveButton("好", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { done.pick(checked); }
        }).setNegativeButton("取消", null).show();
    }
}
