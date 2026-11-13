package com.onesay.assistant.v2.vyrse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.onesay.assistant.plug.vyrse.core.VyrseHttp;
import com.onesay.assistant.plug.vyrse.core.VyrseLink;
import com.onesay.assistant.plug.vyrse.core.VyrsePairing;
import com.onesay.assistant.v2.kernel.ConsoleHost;

/**
 * [vyrse 适配层] 控制台「VYRSE 电脑兼容」页：总开关（第一次打开先弹明文 HTTP 警告）、手动输入电脑地址 + 6 位配对码、
 * 配对后的连接开关 / 状态 / 解除配对。VYRSE 电脑端只显示文字配对码、没有二维码，所以这里没有扫码入口。
 */
public final class VyrseUi {

    public static final String PAGE = "vyrse";
    public static final String TITLE = "VYRSE 电脑兼容";

    static final String HTTP_WARNING = "VYRSE 的手机连接走家里 Wi-Fi 上的明文 HTTP，没有加密：同一个 Wi-Fi 里的其他设备能看到电脑发来的命令、"
            + "读到的通知内容和这台手机的连接 key。只在自己信任的家庭网络里用；公共 Wi-Fi、公司网络别开。";
    static final String SUPPORTED = "电脑能让这台手机做的事：响铃找手机（闹钟音量拉满，30 秒自动停并恢复原音量）、开关手电筒、读当前通知、"
            + "弹一条通知、看电量、打开 App。支付、银行、钱包、密码管理和应用商店类 App 一律不打开，它们的通知也不交给电脑，"
            + "通知里的验证码会打码。点击、输入、截屏等「完整操控」本版不支持，电脑会收到「不支持」。";

    private VyrseUi() {}

    public static void addEntryRow(final ConsoleHost host, LinearLayout root) {
        final Activity act = host.activity();
        LinearLayout g = host.group(root, null);
        host.switchLinkRow(g, TITLE, entrySub(act), VyrseSettings.SWITCH.on(act), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                setMain(host, act, on);
            }
        }, new Runnable() {
            @Override public void run() { host.openPage(PAGE, TITLE); }
        });
    }

    static String entrySub(Activity c) {
        if (VyrseSettings.SWITCH.killed(c)) return ConsoleHost.KILLED;
        if (!VyrseSettings.SWITCH.on(c)) return "默认关闭 · 让电脑上的 VYRSE 把这台手机当作它的手机（局域网明文）";
        if (!VyrseSettings.paired(c)) return "未配对 · 输入电脑地址和 6 位配对码";
        return "已配对 " + VyrseSettings.pcName(c) + (VyrseLinkService.running() ? " · 连接中" : " · 连接已停");
    }

    /** 打开总开关前先确认明文警告（只问一次）；关 = 立即停服务。 */
    static void setMain(final ConsoleHost host, final Activity act, boolean on) {
        if (!on) {
            VyrseSettings.SWITCH.set(act, false);
            VyrseLinkService.sync(act);
            return;
        }
        if (VyrseSettings.warningAcked(act)) {
            VyrseSettings.SWITCH.set(act, true);
            VyrseLinkService.sync(act);
            return;
        }
        new AlertDialog.Builder(act).setTitle("连接没有加密").setMessage(HTTP_WARNING)
                .setPositiveButton("我知道了，开启", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        VyrseSettings.ackWarning(act);
                        VyrseSettings.SWITCH.set(act, true);
                        VyrseLinkService.sync(act);
                        refresh(host);
                    }
                })
                .setNegativeButton("不开", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        refresh(host);
                    }
                }).setCancelable(false).show();
    }

    public static void buildPage(final ConsoleHost host, final LinearLayout root) {
        final Activity act = host.activity();
        LinearLayout w = host.group(root, "安全提示");
        host.valueRow(w, "局域网明文 HTTP", HTTP_WARNING, "未加密", ConsoleHost.TONE_WARN);
        if (!VyrseSettings.SWITCH.on(act)) {
            LinearLayout g = host.group(root, null);
            host.switchRow(g, TITLE, VyrseSettings.SWITCH.killed(act) ? ConsoleHost.KILLED : "已关闭：不联网、不起服务", false, new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean on) {
                    setMain(host, act, on);
                    if (on && VyrseSettings.warningAcked(act)) refresh(host);
                }
            });
            host.footer(root, SUPPORTED);
            return;
        }
        if (!VyrseSettings.paired(act)) buildUnpaired(host, root, act);
        else buildPaired(host, root, act);
        LinearLayout o = host.group(root, null);
        host.switchRow(o, "总开关", "关掉 = 立即断开、不再联系电脑（配对信息保留）", true, new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                setMain(host, act, on);
                refresh(host);
            }
        });
        host.footer(root, SUPPORTED + " 基于 VYRSE（MIT）的手机连接协议。");
    }

    static void buildUnpaired(final ConsoleHost host, LinearLayout root, final Activity act) {
        LinearLayout g = host.group(root, "配对");
        if (VyrseSettings.removedByPc(act)) {
            host.valueRow(g, "电脑已移除这台手机", "在电脑上重新点「Pair a phone」后再配对", "需重配", ConsoleHost.TONE_BAD);
        } else if (VyrseSettings.keyLost(act)) {
            host.valueRow(g, "配对信息解不开了", "换机还原或系统清过密钥，请重新配对", "需重配", ConsoleHost.TONE_BAD);
        }
        host.actionRow(g, "输入电脑地址和配对码", "电脑上：VYRSE → Settings → Phone → 打开手机访问 →「Pair a phone」，会显示地址和 6 位码（2 分钟有效，错 5 次作废）",
                "配对", new Runnable() {
                    @Override public void run() { pairDialog(host, act); }
                });
        host.footer(root, "VYRSE 电脑端没有二维码，只能手动输入。手机和电脑要连同一个 Wi-Fi；只接受家庭网络地址（192.168.x.x / 10.x.x.x / 172.16–31.x.x）。");
    }

    static void buildPaired(final ConsoleHost host, LinearLayout root, final Activity act) {
        LinearLayout g = host.group(root, "电脑");
        VyrseLink.State s = VyrseLinkService.state();
        String value = !VyrseLinkService.running() ? "已停" : s == VyrseLink.State.ONLINE ? "在线" : s == VyrseLink.State.OFFLINE ? "找不到电脑" : "连接中";
        int tone = s == VyrseLink.State.ONLINE ? ConsoleHost.TONE_OK : s == VyrseLink.State.OFFLINE ? ConsoleHost.TONE_WARN : ConsoleHost.TONE_MUTED;
        host.valueRow(g, VyrseSettings.pcName(act), VyrseSettings.base(act).replace("http://", ""), value, tone);
        String last = VyrseLinkService.lastCommand();
        if (last.length() > 0) host.valueRow(g, "最近一条命令", last, null, ConsoleHost.TONE_NORMAL);
        host.switchRow(g, "让电脑联系这台手机", "开着时常驻一条通知，后台每次最多等 35 秒取一次电脑的命令", VyrseSettings.linkOn(act), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                VyrseSettings.setLinkOn(act, on);
                VyrseLinkService.sync(act);
            }
        });
        host.actionRow(g, "解除配对", "手机这边忘掉 key；电脑那边在 Settings → Phone 里把这台手机删掉才算彻底作废", "解除", new Runnable() {
            @Override public void run() {
                host.confirm("解除配对？", "之后要重新输入电脑上的配对码。", "解除", new Runnable() {
                    @Override public void run() {
                        VyrseSettings.unpair(act);
                        VyrseLinkService.sync(act);
                        host.toast("已解除配对");
                        refresh(host);
                    }
                });
            }
        });
    }

    static EditText field(Activity act, String hint, int type) {
        EditText et = new EditText(act);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setInputType(type);
        et.setImeOptions(EditorInfo.IME_FLAG_FORCE_ASCII | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        return et;
    }

    static void pairDialog(final ConsoleHost host, final Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(20);
        box.setPadding(pad, host.dp(8), pad, 0);
        final EditText addr = field(act, "电脑地址，例如 192.168.1.20", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        String old = VyrseSettings.base(act);
        if (old.length() > 0) addr.setText(old.replace("http://", ""));
        final EditText code = field(act, "6 位配对码", InputType.TYPE_CLASS_NUMBER);
        final EditText name = field(act, "这台手机在电脑上显示的名字", InputType.TYPE_CLASS_TEXT);
        name.setText("OneSay " + android.os.Build.MODEL);
        box.addView(addr);
        box.addView(code);
        box.addView(name);
        new AlertDialog.Builder(act).setTitle("连接 VYRSE 电脑").setMessage("连接是局域网明文 HTTP，只在自己家的 Wi-Fi 里用。").setView(box)
                .setPositiveButton("配对", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String a = addr.getText().toString(), c = code.getText().toString(), n = name.getText().toString();
                        host.toast("正在连接电脑…");
                        com.onesay.assistant.v2.util.Threads.start("vyrse-pair", new Runnable() {
                            @Override public void run() {
                                final VyrsePairing.Result r = VyrsePairing.pair(new VyrseHttp.LanSocket(), a, c, n);
                                if (r.ok) {
                                    VyrseSettings.save(act, r);
                                    VyrseLinkService.sync(act);
                                }
                                host.ui(new Runnable() {
                                    @Override public void run() {
                                        Toast.makeText(act, r.ok ? "已配对 " + r.pcName : r.error, Toast.LENGTH_LONG).show();
                                        if (r.ok) refresh(host);
                                    }
                                });
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    static void refresh(ConsoleHost host) {
        if (host instanceof com.onesay.assistant.v2.ui.console.Console) ((com.onesay.assistant.v2.ui.console.Console) host).onResume();
    }
}
