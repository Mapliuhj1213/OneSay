package com.onesay.assistant.v2.pclink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.onesay.assistant.v2.kernel.ConsoleHost;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * [pclink] 控制台「电脑联动」页：没配对 = 扫码配对 / 粘贴配对码；配对后 = 在线状态（页面开着时 10 秒探一次）、
 * 让电脑做事（锁屏 / 截图 / 剪贴板 / 打开网址 / 睡眠 / 关机）、v2 加密状态与「电脑可以让手机做的事」开关、通知转发三个开关、解除配对。
 */
public final class PcLinkUi {

    public static final String PAGE = "pclink";
    public static final String TITLE = "电脑联动";
    static final long POLL_MS = 10000L;
    static final long POLL_MAX_MS = 10L * 60000L;
    /** 电脑上可能要确认的指令手机这边等多久（电脑确认最多 60 秒 + 执行）。 */
    static final int MAY_CONFIRM_WAIT_MS = (int) LinkPolicy.CONFIRM_TIMEOUT_MS + 15000;

    private PcLinkUi() {}

    // ================================================================== 入口行（设置 › 功能）

    public static void addEntryRow(final ConsoleHost host, LinearLayout root) {
        final Activity act = host.activity();
        LinearLayout g = host.group(root, null);
        host.switchLinkRow(g, TITLE, entrySub(act), PcLink.SWITCH.on(act), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) { PcLink.SWITCH.set(act, on); }
        }, new Runnable() {
            @Override public void run() { host.openPage(PAGE, TITLE); }
        });
    }

    static String entrySub(Context c) {
        if (PcLink.SWITCH.killed(c)) return ConsoleHost.KILLED;
        if (!PcLink.paired(c)) return "扫电脑端 OneSay 的二维码配对：锁屏、截图、剪贴板，提醒同步弹到电脑";
        return "已配对 " + PcLink.pcName(c) + " · 点进去看在线状态、让电脑做事";
    }

    // ================================================================== 细项页

    public static void buildPage(final ConsoleHost host, final LinearLayout root) {
        final Activity act = host.activity();
        if (!PcLink.SWITCH.on(act)) {
            LinearLayout g = host.group(root, null);
            host.switchRow(g, "电脑联动", PcLink.SWITCH.killed(act) ? ConsoleHost.KILLED : "已关闭：不找电脑、不转发提醒", false, new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean on) {
                    PcLink.SWITCH.set(act, on);
                    host.toast(on ? "已开启，返回再进来刷新" : "已关闭");
                }
            });
            return;
        }
        if (!PcLink.paired(act)) {
            buildUnpaired(host, root, act);
            return;
        }
        buildPaired(host, root, act);
    }

    static void buildUnpaired(final ConsoleHost host, LinearLayout root, final Activity act) {
        LinearLayout g = host.group(root, "配对");
        host.actionRow(g, "扫码配对", "扫电脑端 OneSay 设置页上的二维码（要用相机）", "扫码", new Runnable() {
            @Override public void run() { act.startActivity(new Intent(act, PcScanActivity.class)); }
        });
        host.actionRow(g, "粘贴配对码", "扫不了码时：在电脑端点「复制配对串」，发到手机上，在这里粘贴", "粘贴", new Runnable() {
            @Override public void run() {
                pasteDialog(act, new Runnable() {
                    @Override public void run() { refresh(host); }
                });
            }
        });
        host.footer(root, "手机和电脑要连同一个 Wi-Fi（局域网直连，不经过任何服务器）。配对码等于钥匙，只在自己的设备之间传。"
                + "没配对时这个功能什么都不做、不联网。本机名：" + PcLink.me(act));
    }

    static void buildPaired(final ConsoleHost host, final LinearLayout root, final Activity act) {
        if (PcLink.keyLost(act)) {
            LinearLayout g = host.group(root, "电脑");
            host.valueRow(g, PcLink.pcName(act), "配对信息解不开了（换机还原 / 系统清过密钥），请解除后重新扫码", "需重配", ConsoleHost.TONE_BAD);
            addUnpairRow(host, g, act);
            return;
        }
        LinearLayout g = host.group(root, "电脑");
        final ConsoleHost.Row state = host.valueRow(g, PcLink.pcName(act), "经由局域网 · 本机 " + PcLink.me(act), "查找中…", ConsoleHost.TONE_MUTED);
        startPolling(host, state, act);

        LinearLayout a = host.group(root, "让电脑做事");
        host.actionRow(a, "锁屏", "立刻锁住电脑屏幕", "锁屏", new Runnable() {
            @Override public void run() { simple(host, "lock", null, "正在锁屏…"); }
        });
        final File last = PcLink.lastShot(act);
        host.actionRow(a, "截图", last == null ? "截电脑屏幕，存到手机相册「OneSay」或 App 私有目录" : "上次：" + when(last.lastModified()) + " · 点右边再截一张",
                "截图", new Runnable() {
                    @Override public void run() { screenshot(host, act); }
                });
        if (last != null) {
            host.linkRow(a, "查看上次截图", last.getName(), new Runnable() {
                @Override public void run() { showShot(act, PcLink.lastShot(act)); }
            });
        }
        host.actionRow(a, "剪贴板发到电脑", "把手机剪贴板里的文字放进电脑剪贴板", "发送", new Runnable() {
            @Override public void run() { sendClipboard(host, act); }
        });
        host.actionRow(a, "在电脑上打开网址", "只打开 http / https 网址", "打开", new Runnable() {
            @Override public void run() { openUrlDialog(host, act); }
        });
        host.actionRow(a, "把链接下到电脑", "视频 / 音频链接交给电脑下载（电脑上用 yt-dlp），通知栏看进度；也可以在别的 App 里点分享 → 「下到电脑」", "下载", new Runnable() {
            @Override public void run() {
                String clip = clipText(act).trim();
                PcMediaAndroid.chooser(act, PcMedia.tidy(clip), null);
            }
        });
        host.actionRow(a, "睡眠", "让电脑睡眠（会先问你确认）", "睡眠", new Runnable() {
            @Override public void run() {
                host.confirm("让电脑睡眠？", "电脑会立刻进入睡眠，没保存的东西不会丢，但正在跑的任务会暂停。", "睡眠", new Runnable() {
                    @Override public void run() { simple(host, "sleep", confirmArgs(), "正在让电脑睡眠…"); }
                });
            }
        });
        host.actionRow(a, "关机", "60 秒后关机，电脑上可以取消（会先问你确认）", "关机", new Runnable() {
            @Override public void run() {
                host.confirm("让电脑关机？", "电脑会在 60 秒后关机。确认前请先保存电脑上的工作。", "关机", new Runnable() {
                    @Override public void run() { simple(host, "shutdown", confirmArgs(), "正在发送关机…"); }
                });
            }
        });

        buildRx(host, root, act);

        LinearLayout f = host.group(root, "提醒同步到电脑");
        fwdRow(host, f, act, "日程与定时提醒", "日程、定时任务提醒", PcLink.K_FWD_REMINDER);
        fwdRow(host, f, act, "自动任务结果", "定时 / 自动任务跑完的结果", PcLink.K_FWD_AUTO);
        fwdRow(host, f, act, "降水提醒", "「12 分钟后下雨」这类分钟级降水提醒", PcLink.K_FWD_RAIN);

        LinearLayout u = host.group(root, null);
        addUnpairRow(host, u, act);
        host.footer(root, "手机和电脑要连同一个 Wi-Fi。提醒只在电脑在线时发过去，电脑离线就不发、也不攒着补发。"
                + "这一页开着时每 10 秒查一次电脑在不在，离开这一页就停。");
    }

    /** 当前页上「接受电脑指令」那一行（轮询时刷新常连状态）；离开页面后由 detach 置空也无妨，只是不再刷新。 */
    static volatile ConsoleHost.Row rxRow;

    /** v2：加密状态 + 「电脑可以让手机做的事」（总开关 = 常连通道，每项单独开关）+ 最近记录。 */
    static void buildRx(final ConsoleHost host, LinearLayout root, final Activity act) {
        String fp = PcLink.pcFp(act);
        LinearLayout sec = host.group(root, "连接安全");
        if (fp.length() > 0) {
            host.valueRow(sec, "加密连接", "电脑证书指纹 " + PcTls.shortFp(fp) + " · 本机 " + PcTls.shortFp(PcLink.myTls(act).fp)
                    + "（两边设置页里显示的应一致）", "已加密", ConsoleHost.TONE_OK);
        } else {
            host.valueRow(sec, "加密连接", "这是旧版配对码（电脑端 0.4.x）：内容有签名但没加密。电脑端升到 0.5.0 后重新扫码即可加密", "未加密", ConsoleHost.TONE_WARN);
        }

        LinearLayout g = host.group(root, "电脑可以让手机做的事");
        final boolean on = PcRxAndroid.rxOn(act);
        final ConsoleHost.Row[] rows = new ConsoleHost.Row[PcRxPolicy.ALL.length]; // 原来的 8 项 + 加回来的 8 项，每项一个开关，都挂在总开关下
        rxRow = host.switchRow(g, "接受电脑指令", on ? PcRxAndroid.stateText() : "关着：电脑找不到这台手机，下面各项都不生效", on, new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean v) {
                PcRxAndroid.setRx(act, v);
                for (ConsoleHost.Row r : rows) if (r != null) r.setEnabled(v);
                if (rxRow != null) rxRow.setSub(v ? "正在找电脑…" : "关着：电脑找不到这台手机，下面各项都不生效");
            }
        });
        for (int i = 0; i < PcRxPolicy.ALL.length; i++) {
            final String cmd = PcRxPolicy.ALL[i];
            rows[i] = host.switchRow(g, PcRxPolicy.label(cmd), PcRxPolicy.sub(cmd) + (PcRxPolicy.dangerous(cmd) ? " · 会改动手机，按需打开" : ""),
                    PcRxAndroid.cmdOn(act, cmd), new ConsoleHost.Toggle() {
                        @Override public void onToggle(boolean v) { PcRxAndroid.setCmd(act, cmd, v); }
                    });
            rows[i].setEnabled(on);
        }
        // [approve-not-block] 敏感剪贴板 60 秒清除（可选，默认关）；「以后这类不用问」记住的类型，可撤销
        host.switchRow(g, "敏感内容 60 秒后自动清除", "电脑写进手机剪贴板的验证码 / 卡号 / 密码这类内容，60 秒后清掉（默认关）",
                PcRxAndroid.clipAutoClear(act), new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean v) { PcRxAndroid.setClipAutoClear(act, v); }
                });
        java.util.List<String> kinds = PcRxAndroid.rememberedKinds(act);
        LinearLayout rg = host.group(root, "以后这类不用问");
        if (kinds.isEmpty()) {
            host.valueRow(rg, "还没有记住过", "手机确认卡上勾「以后这类不用问」并允许后，同一类直接做（付款最后一下、改 OneSay 自己、给 App 授权不会弹卡，也记不住）", "", ConsoleHost.TONE_MUTED);
        } else {
            for (final String k : kinds) {
                host.actionRow(rg, LinkPolicy.kindZh(k), "电脑让手机做这一类时不再弹确认卡", "撤销", new Runnable() {
                    @Override public void run() {
                        PcRxAndroid.forget(act, k);
                        host.toast("已撤销，下次会再问你");
                        refresh(host);
                    }
                });
            }
        }
        String log = PcLink.sp(act).getString(PcRxAndroid.K_RX_LOG, "");
        host.footer(root, "开着时手机和电脑保持一条连接（只在 Wi-Fi 下，电脑不在就等它上线），电脑随时能找到手机；每条指令都带配对钥匙签名，"
                + "过期或重放的直接拒绝。打开 / 卸载支付类与系统 App、带账号或疑似仿冒的网址会先在手机上弹确认卡（只认手指点，60 秒不点 = 拒绝）；"
                + "电脑上要确认的事也会同时发到这里，在手机上点也算数。" + (log.length() > 0 ? "\n最近：\n" + log : ""));
    }

    /** 重画当前页（控制台的 onResume 会重建顶层页面）。 */
    static void refresh(ConsoleHost host) {
        if (host instanceof com.onesay.assistant.v2.ui.console.Console) ((com.onesay.assistant.v2.ui.console.Console) host).onResume();
    }

    static Map<String, Object> confirmArgs() {
        Map<String, Object> m = new TreeMap<String, Object>();
        m.put("confirm", Boolean.TRUE);
        return m;
    }

    static void fwdRow(ConsoleHost host, LinearLayout g, final Activity act, String title, String sub, final String key) {
        host.switchRow(g, title, sub, PcLink.sp(act).getBoolean(key, true), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) { PcLink.setFwd(act, key, on); }
        });
    }

    static void addUnpairRow(final ConsoleHost host, LinearLayout g, final Activity act) {
        host.actionRow(g, "解除配对", "手机这边忘掉这台电脑；电脑端如要彻底作废旧配对，在电脑上「换新钥匙」", "解除", new Runnable() {
            @Override public void run() {
                host.confirm("解除配对？", "之后要用得重新扫码。", "解除", new Runnable() {
                    @Override public void run() {
                        PcLink.unpair(act);
                        host.toast("已解除配对");
                        refresh(host);
                    }
                });
            }
        });
    }

    static String when(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(ms));
    }

    // ================================================================== 在线状态轮询（只在页面可见时）

    static void startPolling(final ConsoleHost host, final ConsoleHost.Row row, final Activity act) {
        final View v = row.view();
        final boolean[] alive = {false};
        final View.OnAttachStateChangeListener l = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                if (alive[0]) return;
                alive[0] = true;
                com.onesay.assistant.v2.util.Threads.start("pclink-poll", new Runnable() {
                    @Override public void run() { pollLoop(host, row, act, v, alive); }
                });
            }

            @Override public void onViewDetachedFromWindow(View view) {
                alive[0] = false;
            }
        };
        v.addOnAttachStateChangeListener(l);
        // 配对成功后 refresh 会在页面已显示时重建这一行：此时行已挂在窗口上，attach 回调不会再来，
        // 必须立刻开始轮询，否则一直停在「查找中…」（oss-device-1108 真机发现）
        if (v.isAttachedToWindow()) l.onViewAttachedToWindow(v);
    }

    static void pollLoop(final ConsoleHost host, final ConsoleHost.Row row, Activity act, View v, boolean[] alive) {
        long end = System.currentTimeMillis() + POLL_MAX_MS;
        Context app = act.getApplicationContext();
        while (alive[0] && System.currentTimeMillis() < end && !act.isFinishing()) {
            if (v.getWindowVisibility() == View.VISIBLE) {
                final PcLanClient.Result r = PcLink.probe(app);
                if (!alive[0]) return;
                final String name = PcLink.pcName(app);
                final String last = PcLink.sp(app).getString(PcLink.K_LAST, "");
                host.ui(new Runnable() {
                    @Override public void run() {
                        ConsoleHost.Row rr = rxRow;
                        if (rr != null && rr.isOn()) rr.setSub(PcRxAndroid.stateText());
                        if (r.ok) {
                            row.setValue("在线", ConsoleHost.TONE_OK);
                            row.setSub("经由局域网" + (last.length() > 0 ? " " + last.substring(0, Math.max(0, last.lastIndexOf(':'))) : "") + " · " + name);
                        } else if ("auth".equals(r.fail)) {
                            row.setValue("被拒绝", ConsoleHost.TONE_BAD);
                            row.setSub("电脑端换过钥匙？请解除配对后重新扫码");
                        } else if ("tls".equals(r.fail) || "pin".equals(r.fail)) {
                            row.setValue("证书不对", ConsoleHost.TONE_BAD);
                            row.setSub(r.text);
                        } else {
                            row.setValue("离线", ConsoleHost.TONE_WARN);
                            row.setSub("局域网里没找到 " + name + "：电脑开着 OneSay 吗？和手机在同一个 Wi-Fi 吗？");
                        }
                    }
                });
            }
            for (int i = 0; i < POLL_MS / 500 && alive[0]; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    // ================================================================== 动作

    static void simple(final ConsoleHost host, String cmd, Map<String, Object> args, String pending) {
        // link-v2：电脑作为被控端时可能要确认（关机 / 睡眠 / 卸载 / 打开不在信任列表的网址 / 写剪贴板 / 设置改动；
        // 快捷指令第一次或改过后要批准；运行脚本 / 执行命令每次确认）。[approve-not-block] 电脑最多等 60 秒（电脑上或这台手机的确认卡上点都行），手机这边多等一会儿
        LinkPolicy.Cat cat = LinkPolicy.pcCat(cmd);
        boolean mayConfirm = cat == LinkPolicy.Cat.SIMPLE_SETTING || cat == LinkPolicy.Cat.POWER || cat == LinkPolicy.Cat.OPEN_URL
                || cat == LinkPolicy.Cat.CLIPBOARD_WRITE || cat == LinkPolicy.Cat.RUN_WS_SCRIPT || cat == LinkPolicy.Cat.SHORTCUT
                || cat == LinkPolicy.Cat.SHELL_EXEC || cat == LinkPolicy.Cat.UNINSTALL_APP || cat == LinkPolicy.Cat.APP_OPERATE || cat == LinkPolicy.Cat.OPEN_APP
                || cat == LinkPolicy.Cat.MEDIA_DOWNLOAD;
        host.toast(mayConfirm ? pending + "（可能要确认：电脑上或这台手机弹出的确认卡上点）" : pending);
        PcLink.run(host.activity(), cmd, args, mayConfirm ? MAY_CONFIRM_WAIT_MS : 15000, new PcLink.Done() {
            @Override public void done(PcLanClient.Result r) {
                Object why = r.data == null ? null : r.data.get("reason_zh");
                String t = r.text.length() > 0 ? r.text : (r.ok ? "完成" : "失败");
                host.toast(why != null && String.valueOf(why).length() > 0 && !t.contains(String.valueOf(why)) ? t + "（" + why + "）" : t);
            }
        });
    }

    static void screenshot(final ConsoleHost host, final Activity act) {
        host.toast("正在让电脑截图…");
        final Context app = act.getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("pclink-shot", new Runnable() {
            @Override public void run() {
                final PcLanClient.Result r = PcLink.runNow(app, "screenshot", null, 2000, 20000);
                final File f = r.ok ? PcLink.saveShot(app, r) : null;
                host.ui(new Runnable() {
                    @Override public void run() {
                        if (!r.ok) host.toast(r.text);
                        else if (f == null) host.toast("截到了，但存不下来");
                        else if (!act.isFinishing()) showShot(act, f);
                    }
                });
            }
        });
    }

    /** 看截图：图 + 「分享」（存进相册的才能分享）。 */
    public static void showShot(final Activity act, final File f) {
        if (f == null || !f.isFile()) {
            Toast.makeText(act, "截图文件不在了", Toast.LENGTH_SHORT).show();
            return;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        ImageView iv = new ImageView(act);
        iv.setAdjustViewBounds(true);
        iv.setImageBitmap(bm);
        AlertDialog.Builder b = new AlertDialog.Builder(act).setTitle("电脑截图").setView(iv).setNegativeButton("关闭", null);
        b.setPositiveButton("分享", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                Uri u = PcLink.lastShotUri(act);
                if (u == null) {
                    Toast.makeText(act, "这张存在 App 私有目录（没有存储权限），暂时分享不了。路径：" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    return;
                }
                Intent s = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    act.startActivity(Intent.createChooser(s, "分享截图"));
                } catch (Exception e) {
                    Toast.makeText(act, "没有可分享的应用", Toast.LENGTH_SHORT).show();
                }
            }
        });
        b.show();
    }

    public static String clipText(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm == null ? null : cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return "";
            CharSequence t = d.getItemAt(0).coerceToText(c);
            return t == null ? "" : t.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static void sendClipboard(ConsoleHost host, Activity act) {
        String t = clipText(act);
        if (t.trim().length() == 0) {
            host.toast("手机剪贴板里没有文字");
            return;
        }
        if (t.length() > 100000) {
            host.toast("内容太长（超过 10 万字）");
            return;
        }
        Map<String, Object> m = new TreeMap<String, Object>();
        m.put("text", t);
        simple(host, "clipboard_set", m, "正在发送 " + t.length() + " 字…");
    }

    static void openUrlDialog(final ConsoleHost host, final Activity act) {
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("https://…");
        String clip = clipText(act).trim();
        if (PcVoice.normalizeUrl(clip) != null && (clip.startsWith("http://") || clip.startsWith("https://"))) et.setText(clip);
        new AlertDialog.Builder(act).setTitle("在电脑上打开网址").setView(et)
                .setPositiveButton("打开", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String u = PcVoice.normalizeUrl(et.getText().toString());
                        if (u == null) {
                            host.toast("只能打开 http / https 网址");
                            return;
                        }
                        Map<String, Object> m = new TreeMap<String, Object>();
                        m.put("url", u);
                        simple(host, "open_url", m, "正在打开…");
                    }
                }).setNegativeButton("取消", null).show();
    }

    /** 粘贴配对码对话框（控制台和扫码页共用）。成功后调 onPaired。 */
    public static void pasteDialog(final Activity act, final Runnable onPaired) {
        final EditText et = new EditText(act);
        // 配对码是机器生成的 ASCII 串：要求输入法给英文键盘、不联想不学习，免得中文输入法把 : ? & = 改成全角（oss-device-1108 真机）
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setImeOptions(EditorInfo.IME_FLAG_FORCE_ASCII | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        et.setHint("onesay://pair?…");
        // 剪贴板里有配对码（哪怕夹在聊天文字里）就直接填好，不经过输入法
        String clip = PcPairing.extract(clipText(act));
        if (clip != null) et.setText(clip);
        new AlertDialog.Builder(act).setTitle("粘贴配对码").setMessage("电脑端 OneSay → 设置 → 配对手机 →「显示」→「复制配对串」。配对码等于钥匙，别发到群里。")
                .setView(et)
                .setPositiveButton("配对", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            String in = et.getText().toString();
                            if (in.trim().length() == 0) in = clipText(act); // 没填就用剪贴板
                            PcPairing p = PcLink.pair(act, in);
                            Toast.makeText(act, "已配对 " + p.peer + "，正在找电脑…", Toast.LENGTH_LONG).show();
                            if (onPaired != null) onPaired.run();
                        } catch (IllegalArgumentException e) {
                            Toast.makeText(act, e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }).setNegativeButton("取消", null).show();
    }
}
