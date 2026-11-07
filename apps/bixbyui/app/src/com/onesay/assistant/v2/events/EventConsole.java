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
        final EditText target = field(act, box, "Wi‑Fi 名称 或 蓝牙设备名 / MAC（空 = 任意）");
        final EditText apps = field(act, box, "通知：白名单 App 包名，逗号分隔");
        final EditText kws = field(act, box, "通知：白名单关键词，逗号分隔");
        final EditText cmd = field(act, box, "要做的事（例：播报今天的待办）");
        final EditText title = field(act, box, "名称（可空）");
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.addView(box);
        new AlertDialog.Builder(act).setTitle("新建事件任务").setView(sv)
                .setPositiveButton("下一步：授权", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        EventTask t = new EventTask();
                        t.source = sources[Math.max(0, src.getSelectedItemPosition())];
                        t.onConnect = t.source == EventTask.Source.NOTIFICATION || on.isChecked();
                        t.target = target.getText().toString().trim();
                        t.apps = EventTask.splitList(apps.getText().toString());
                        t.keywords = EventTask.splitList(kws.getText().toString());
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
