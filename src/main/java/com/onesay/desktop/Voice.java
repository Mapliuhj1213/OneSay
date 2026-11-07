package com.onesay.desktop;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * 长按空格说话：钩子进程报 START/END → 录音 → 识别 → 规则识意图 → 本机执行 → 浮条显示结果。
 * 钩子只在 Windows 上有；没配识别 Key 时不启动（不抢空格）。
 */
public final class Voice {
    private final Config cfg;
    private final Actions actions;
    private final Consumer<String> log;
    private final Recorder rec = new Recorder();
    private Process hook;
    private Pill pill;
    private volatile boolean recording;
    public volatile String status = "未开启";
    /** 认不出指令时交给「对话」（大模型），返回回复文字；null = 不转。 */
    public volatile java.util.function.Function<String, String> fallback;

    public Voice(Config cfg, Actions actions, Consumer<String> log) {
        this.cfg = cfg;
        this.actions = actions;
        this.log = log;
    }

    public void start() {
        if (!cfg.pttEnabled) { status = "已关闭"; return; }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) { status = "仅 Windows 支持"; return; }
        if (cfg.asrKey.isEmpty()) { status = "未配置识别 Key（setup 里填），不抢空格"; log.accept("按住说话：" + status); return; }
        try {
            Path dir = cfg.file.getParent().resolve("hotkey");
            Files.createDirectories(dir);
            Path cs = dir.resolve("OneSayHotkey.cs"), ps = dir.resolve("hotkey.ps1");
            copy("/onesay/OneSayHotkey.cs", cs);
            copy("/onesay/hotkey.ps1", ps);
            hook = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", ps.toString(), "-Cs", cs.toString(), "-Skip", cfg.pttSkip).redirectErrorStream(true).start();
            // 标准输入一直开着：本程序一退出，管道断开，钩子进程自己卸钩退出
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (hook != null) hook.destroy(); }));
            Thread t = new Thread(this::readHook, "onesay-hotkey");
            t.setDaemon(true);
            t.start();
            status = "启动中";
        } catch (Exception e) {
            status = "钩子启动失败：" + e.getClass().getSimpleName();
            log.accept("按住说话：" + status);
        }
    }

    public void stop() { if (hook != null) hook.destroy(); }

    private static void copy(String res, Path to) throws Exception {
        try (InputStream in = Voice.class.getResourceAsStream(res)) {
            if (in == null) throw new IllegalStateException("缺少资源 " + res);
            Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void readHook() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(hook.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                switch (line) {
                    case "READY": status = "长按空格说话"; log.accept("按住说话：就绪"); break;
                    case "START": onStart(); break;
                    case "END": onEnd(false); break;
                    case "CANCEL": onEnd(true); break;
                    default: if (!line.isEmpty()) log.accept("钩子：" + line);
                }
            }
        } catch (Exception ignored) { }
        status = "钩子已退出";
        log.accept("按住说话：钩子进程退出");
    }

    private void onStart() {
        try {
            rec.start();
            recording = true;
            show("● 正在听…", true);
        } catch (Exception e) {
            show("麦克风打不开：" + e.getClass().getSimpleName(), false);
        }
    }

    private void onEnd(boolean cancel) {
        if (!recording) return;
        recording = false;
        byte[] pcm = rec.stop();
        if (cancel) { show("已取消", false); return; }
        if (Recorder.seconds(pcm) < 0.3 || Recorder.rms(pcm) < 120) { show("没听到声音", false); return; }
        show("识别中…", true);
        new Thread(() -> handle(pcm), "onesay-voice").start();
    }

    private void handle(byte[] pcm) {
        String text;
        try {
            text = new Asr(cfg.asrBase, cfg.asrKey).recognize(pcm, Recorder.RATE);
        } catch (Exception e) {
            show(e.getMessage() == null ? "识别失败" : e.getMessage(), false);
            return;
        }
        log.accept("听到：" + text);
        Intent it = Intent.parse(text, cfg.appMap(), shortcutNames());
        if (it.cmd == null) {
            java.util.function.Function<String, String> fb = fallback;
            if (fb != null && (it.say.startsWith("没听懂") || it.say.startsWith("没听清") || it.say.startsWith("让手机"))) {
                show("「" + text + "」 交给对话…", true);
                String ans = fb.apply(text);
                show(ans == null ? "「" + text + "」 " + it.say : Notify.clip(ans.replace('\n', ' '), 60), false);
                return;
            }
            show("「" + text + "」 " + it.say, false);
            return;
        }
        show("「" + text + "」 " + it.say, true);
        try {
            Actions.Result r = actions.run(it.cmd, it.args);
            if (it.cmd.equals("screenshot") && r.ok && r.data != null) r = saveShot(r.data);
            show("「" + text + "」 " + (r.ok ? it.say : r.text), false);
        } catch (Exception e) {
            show("「" + text + "」 出错：" + e.getClass().getSimpleName(), false);
        }
    }

    private List<String> shortcutNames() {
        List<String> out = new ArrayList<>();
        if (cfg.scriptsDir.isEmpty()) return out;
        try (var s = Files.list(Paths.get(cfg.scriptsDir))) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                int dot = n.lastIndexOf('.');
                String ext = dot < 0 ? "" : n.substring(dot).toLowerCase();
                if (ext.equals(".ps1") || ext.equals(".bat") || ext.equals(".cmd")) out.add(n.substring(0, dot));
            });
        } catch (Exception ignored) { }
        return out;
    }

    /** 本机语音截图：存到 图片\OneSay，并放进剪贴板。 */
    static Actions.Result saveShot(Map<String, Object> d) throws Exception {
        byte[] jpg = Base64.getDecoder().decode((String) d.get("b64"));
        Path dir = Paths.get(System.getProperty("user.home"), "Pictures", "OneSay");
        Files.createDirectories(dir);
        Path f = dir.resolve("截图-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jpg");
        Files.write(f, jpg);
        BufferedImage img = javax.imageio.ImageIO.read(f.toFile());
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
            public boolean isDataFlavorSupported(DataFlavor fl) { return DataFlavor.imageFlavor.equals(fl); }
            public Object getTransferData(DataFlavor fl) { return img; }
        }, null);
        return Actions.Result.ok("已存到 " + f);
    }

    // ---- 屏幕底部的小浮条 ----

    private void show(String text, boolean sticky) {
        log.accept("浮条：" + text);
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            if (pill == null) pill = new Pill();
            pill.show(text, sticky);
        });
    }

    private static final class Pill {
        final JWindow w = new JWindow();
        final JLabel l = new JLabel("", JLabel.CENTER);
        javax.swing.Timer hide;

        Pill() {
            w.setAlwaysOnTop(true);
            w.setFocusableWindowState(false);
            l.setOpaque(true);
            l.setBackground(new Color(0x1F, 0x22, 0x28));
            l.setForeground(Color.WHITE);
            l.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 15));
            l.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
            w.getContentPane().add(l);
            hide = new javax.swing.Timer(2600, e -> w.setVisible(false));
            hide.setRepeats(false);
        }

        void show(String text, boolean sticky) {
            l.setText(text.length() > 60 ? text.substring(0, 60) + "…" : text);
            w.pack();
            Dimension d = w.getSize();
            Rectangle s = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            w.setLocation(s.x + (s.width - d.width) / 2, s.y + s.height - d.height - 48);
            try { w.setShape(new java.awt.geom.RoundRectangle2D.Double(0, 0, d.width, d.height, d.height, d.height)); } catch (Exception ignored) { }
            w.setVisible(true);
            hide.stop();
            if (!sticky) hide.start();
        }
    }
}
