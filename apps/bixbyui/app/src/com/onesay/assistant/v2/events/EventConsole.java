package com.onesay.assistant.v2.events;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.onesay.assistant.v2.jobs.PermissionCards;
import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.ui.console.FormChoices;
import com.onesay.assistant.v2.ui.console.Pickers;

import java.util.List;

/**
 * 控制台「定时任务」页里的「事件任务」分组（lane-a 第 7 项）：列表（暂停原因 / 今日次数 / 要不要重新授权）、新建（表单 → 权限卡 → 入库）、
 * 恢复 / 关闭 / 删除。只是界面，规则都在 {@link EventEngine}。
 */
public final class EventConsole {

    private EventConsole() {}

    public static void addGroup(final ConsoleHost host, LinearLayout root) {
        final Activity act = host.activity();
        final EventEngine e = EventHooks.engine(act);
        LinearLayout g = host.group(root, "事件任务");
        host.actionRow(g, "新建事件任务", "充电 / Wi‑Fi / 蓝牙 / 通知触发；创建前先过权限卡", "新建", new Runnable() {
            @Override public void run() { showCreate(host); }
        });
        List<EventTask> all = e.store().all();
        if (all.isEmpty()) host.valueRow(g, "还没有", "例：连上家里 Wi‑Fi 时播报今天的待办", "", ConsoleHost.TONE_MUTED);
        for (final EventTask t : all) {
            String state = t.paused ? t.pausedReason : !t.enabled ? "已关闭" : e.jobs().validGrant(t.grantKey()) == null ? "需要重新授权" : "生效中";
            String sub = t.describeTrigger() + "：" + t.command + " · " + state + " · 今天 " + (t.dayKey.length() > 0 ? t.firesToday : 0) + "/" + t.dailyCap;
            final String action = t.paused ? "恢复" : t.enabled ? "关闭" : "删除";
            host.actionRow(g, t.title.length() > 0 ? t.title : t.describeTrigger(), sub, action, new Runnable() {
                @Override public void run() {
                    if ("恢复".equals(action)) {
                        e.resume(t.id);
                        host.toast("已恢复：" + t.describeTrigger());
                    } else if ("关闭".equals(action)) {
                        e.setEnabled(t.id, false);
                        host.toast("已关闭（再点一次删除）");
                    } else {
                        host.confirm("删除事件任务", t.describeTrigger() + "：" + t.command, "删除", new Runnable() {
                            @Override public void run() {
                                e.delete(t.id);
                                host.toast("已删除");
                            }
                        });
                    }
                }
            });
        }
        host.footer(root, "通知触发只看白名单 App 里含白名单关键词的通知；通知内容只当数据给模型看，不会被当成指令，也不会扩大任务权限。"
                + "需要在系统「通知使用权」里打开 OneSay。每条任务默认防抖 10 分钟、每天最多 5 次、连续失败 3 次自动暂停。");
    }

    /**
     * [fix-1123-c] 新建事件任务：Wi‑Fi 从已保存 / 附近的网络里选，蓝牙从已配对设备里选，通知白名单从已装 App 里勾，关键词是能增删的小标签。
     * 只有「要做的事」和「名称」还是自己写（本来就是一句话）。列表里没有的 Wi‑Fi / 蓝牙放在列表最后「自己填」。
     */
    static void showCreate(final ConsoleHost host) {
        final Activity act = host.activity();
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(16);
        box.setPadding(pad, pad / 2, pad, 0);
        final Spinner src = new Spinner(act);
        final EventTask.Source[] sources = EventTask.Source.values();
        String[] labels = new String[sources.length];
        for (int i = 0; i < sources.length; i++) labels[i] = sources[i].label;
        src.setAdapter(new ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, labels));
        box.addView(label(act, "触发源"));
        box.addView(src);
        final CheckBox on = new CheckBox(act);
        on.setText("连上 / 插上 / 收到时（不勾 = 断开 / 拔掉时）");
        on.setChecked(true);
        box.addView(on);

        final String[] target = {""};
        final java.util.Set<String> apps = new java.util.LinkedHashSet<String>();
        final List<String>[] kws = new List[] {new java.util.ArrayList<String>()};

        final TextView targetLbl = label(act, "");
        final android.widget.Button targetBtn = new android.widget.Button(act);
        final TextView appsLbl = label(act, "通知：哪些 App 的通知");
        final android.widget.Button appsBtn = new android.widget.Button(act);
        final TextView kwLbl = label(act, "通知：通知里要有这些词（点一个词删掉）");
        final LinearLayout kwRow = new LinearLayout(act);
        kwRow.setOrientation(LinearLayout.VERTICAL);
        box.addView(targetLbl);
        box.addView(targetBtn);
        box.addView(appsLbl);
        box.addView(appsBtn);
        box.addView(kwLbl);
        box.addView(kwRow);

        final Runnable sync = new Runnable() {
            @Override public void run() {
                EventTask.Source s = sources[Math.max(0, src.getSelectedItemPosition())];
                boolean net = s == EventTask.Source.WIFI || s == EventTask.Source.BLUETOOTH, nt = s == EventTask.Source.NOTIFICATION;
                targetLbl.setText(s == EventTask.Source.WIFI ? "哪个 Wi‑Fi" : "哪个蓝牙设备");
                targetBtn.setText(target[0].length() == 0 ? FormChoices.ANY : target[0]);
                int v = net ? android.view.View.VISIBLE : android.view.View.GONE, w = nt ? android.view.View.VISIBLE : android.view.View.GONE;
                targetLbl.setVisibility(v);
                targetBtn.setVisibility(v);
                on.setVisibility(nt ? android.view.View.GONE : android.view.View.VISIBLE);
                appsLbl.setVisibility(w);
                appsBtn.setVisibility(w);
                kwLbl.setVisibility(w);
                kwRow.setVisibility(w);
                appsBtn.setText(apps.isEmpty() ? "选 App（还没选）" : "已选 " + apps.size() + " 个，点这里改");
                fillTags(act, kwRow, kws, this);
            }
        };
        src.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                target[0] = "";
                sync.run();
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        targetBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                final EventTask.Source s = sources[Math.max(0, src.getSelectedItemPosition())];
                List<String> names = s == EventTask.Source.WIFI ? EventPickData.wifiNames(act) : EventPickData.bluetoothNames(act);
                Pickers.one(act, s == EventTask.Source.WIFI ? "选 Wi‑Fi（已保存 / 附近的）" : "选蓝牙设备（已配对的）", FormChoices.targetChoices(names),
                        target[0].length() == 0 ? FormChoices.ANY : target[0],
                        s == EventTask.Source.WIFI ? "Wi‑Fi 名称" : "蓝牙设备名或 MAC 地址", new Pickers.OnText() {
                            @Override public void pick(String c) {
                                String t = FormChoices.targetOf(c);
                                target[0] = t == null ? c : t;
                                sync.run();
                            }
                        });
            }
        });
        appsBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Pickers.apps(act, "哪些 App 的通知", apps, new Pickers.OnPkgs() {
                    @Override public void pick(java.util.Set<String> p) {
                        apps.clear();
                        apps.addAll(p);
                        sync.run();
                    }
                });
            }
        });
        final EditText cmd = field(act, box, "要做的事（例：播报今天的待办）");
        final EditText title = field(act, box, "名称（可空）");
        sync.run();
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.addView(box);
        new AlertDialog.Builder(act).setTitle("新建事件任务").setView(sv)
                .setPositiveButton("下一步：授权", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        EventTask t = new EventTask();
                        t.source = sources[Math.max(0, src.getSelectedItemPosition())];
                        t.onConnect = t.source == EventTask.Source.NOTIFICATION || on.isChecked();
                        t.target = t.source == EventTask.Source.WIFI || t.source == EventTask.Source.BLUETOOTH ? target[0] : "";
                        t.apps = new java.util.ArrayList<String>(apps);
                        t.keywords = new java.util.ArrayList<String>(kws[0]);
                        t.command = cmd.getText().toString().trim();
                        t.title = title.getText().toString().trim();
                        String bad = EventEngine.invalidReason(t);
                        if (bad != null) {
                            host.toast(bad);
                            return;
                        }
                        EventEngine.Pending p = EventHooks.engine(act).prepare(t);
                        PermissionCards.show(act, PermissionCards.forNewEvent(p, System.currentTimeMillis()));
                    }
                }).setNegativeButton("取消", null).show();
    }

    /** 关键词小标签：每个词一个按钮（点了删掉），最后一个「＋ 加关键词」。 */
    static void fillTags(final Activity act, final LinearLayout row, final List<String>[] kws, final Runnable sync) {
        row.removeAllViews();
        for (final String k : kws[0]) {
            android.widget.Button b = new android.widget.Button(act);
            b.setText(k + "  ✕");
            b.setAllCaps(false);
            b.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    kws[0] = FormChoices.removeTag(kws[0], k);
                    sync.run();
                }
            });
            row.addView(b);
        }
        if (kws[0].size() < FormChoices.MAX_TAGS) {
            android.widget.Button add = new android.widget.Button(act);
            add.setText("＋ 加关键词");
            add.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    Pickers.manual(act, "例如：快递、取件码（可以一次写几个，用逗号分开）", new Pickers.OnText() {
                        @Override public void pick(String t) {
                            kws[0] = FormChoices.addTags(kws[0], t);
                            sync.run();
                        }
                    });
                }
            });
            row.addView(add);
        }
    }

    static TextView label(Activity act, String s) {
        TextView t = new TextView(act);
        t.setText(s);
        return t;
    }

    static EditText field(Activity act, LinearLayout box, String hint) {
        EditText et = new EditText(act);
        et.setHint(hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(et);
        return et;
    }
}
