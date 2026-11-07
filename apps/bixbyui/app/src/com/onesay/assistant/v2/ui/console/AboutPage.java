package com.onesay.assistant.v2.ui.console;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;

import java.security.MessageDigest;

/** [console R12] 关于：版本（连点 7 次 = 开发者模式）、构建类型、签名证书指纹前 8 位、用到的开源组件。 */
final class AboutPage extends Console.BuiltPage {

    AboutPage(Console c) {
        super(c, "关于");
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        LinearLayout g = k.group(col, null);
        String vn = "?";
        try {
            PackageInfo pi = c.act.getPackageManager().getPackageInfo(c.act.getPackageName(), 0);
            vn = pi.versionName;
        } catch (Throwable ignored) {
        }
        final ConsoleKit.RowImpl ver = k.valueRow(g, "版本", null, vn + " · " + Console.RELEASE, ConsoleHost.TONE_NORMAL);
        ver.root.setBackground(k.ripple(null));
        ver.root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = c.devMode();
                int left = c.devGate.tap(SystemClock.elapsedRealtime(), on);
                if (left == 0) c.setDevMode(true);
                String h = DevMode.hint(left);
                if (h != null) c.toast(h);
            }
        });
        k.valueRow(g, "构建", null, Console.debugBuild() ? "debug" : "release", ConsoleHost.TONE_NORMAL);
        k.valueRow(g, "签名证书", "SHA-256 前 8 位", certPrefix(), ConsoleHost.TONE_NORMAL);
        k.valueRow(g, "包名", null, c.act.getPackageName(), ConsoleHost.TONE_NORMAL);

        // ★ refactor/oss：设备能力探测 + 功能注册表生成的开关页（只读写原有开关，不改默认值）
        LinearLayout caps = k.group(col, null);
        k.linkRow(caps, "设备能力与功能", "root / 按键 / 厂商 / 系统版本决定哪些功能可用", 0, new Runnable() {
            @Override public void run() {
                c.push(new DeviceCapsPage(c));
            }
        });

        LinearLayout os = k.group(col, "开源组件");
        k.valueRow(os, "sherpa-onnx", "离线唤醒词 / 声纹", "Apache-2.0", ConsoleHost.TONE_MUTED);
        k.valueRow(os, "JavaMail for Android", "邮箱 IMAP（android-mail / activation 1.6.5）", "见项目许可", ConsoleHost.TONE_MUTED);
        k.valueRow(os, "Mika", "部分移植自 Mika：读屏 / 动作 / 守卫 / 证据 / App 知识（Copyright 2026 wangzan101，NOTICE.txt）", "Apache-2.0", ConsoleHost.TONE_MUTED);   // ★ mika-plug
        k.valueRow(os, "phongent", "多设备执行端协议与部分改写（mylea1/phongent，基于 Mika）", "Apache-2.0", ConsoleHost.TONE_MUTED);
        k.footer(col, "Mika / phongent 改写部分的许可、署名与改动说明见 APK 内 assets/NOTICE.txt。");
        k.footer(col, "连点版本号 7 次打开开发者模式（调试按钮、自测、关停文件）。");
    }

    /** Android 8 没有 GET_SIGNING_CERTIFICATES（API 28 起），只能用旧接口。 */
    @SuppressWarnings("deprecation")
    private String certPrefix() {
        try {
            PackageInfo pi = c.act.getPackageManager().getPackageInfo(c.act.getPackageName(), PackageManager.GET_SIGNATURES);
            byte[] d = MessageDigest.getInstance("SHA-256").digest(pi.signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i == 2) sb.append(':');
                sb.append(String.format(java.util.Locale.ROOT, "%02X", d[i] & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "未知";
        }
    }
}
