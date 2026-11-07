package com.onesay.assistant.v2.fastscreen;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ★ r2/fast-screen：常驻 root 读屏助手（{@link HelperMain}）与 App（{@link FastScreen}）之间的线协议。纯 Java，JVM 可测。
 *
 * <h3>为什么要它</h3>
 * 以前每次读屏都是「exec 守护文件通道（空闲首条约 2 s）+ 冷启动 uiautomator dump（约 2–8 s）」。
 * 真机探针（fix-20261025/fs-probe/PROBE-RESULTS.md）：常驻 app_process 里持有 UiAutomation，读树热态 p50 6–34 ms，
 * SurfaceControl 截图 + JPEG 约 60–100 ms，LocalSocket 往返约 11 ms。
 *
 * <h3>帧格式</h3>
 * 请求：int 长度 + UTF-8 文本，文本 = op 与参数用 TAB 分隔（参数里的 TAB / 换行先被 {@link #clean} 换成空格）。
 * 应答：int 状态（0 成功 / 1 失败 / 2 拒绝）+ int 长度 + 字节（文本是 UTF-8，截图是 JPEG）。
 *
 * <h3>安全边界</h3>
 * 只认 {@link #OPS} 里的固定方法，<b>没有任意 shell</b>；参数逐个校验（{@link #validate}）。
 * 连接方身份：LocalSocket 对端 uid 必须等于守护在启动助手时核过签名的那个 uid，助手再按包名核一次证书（{@link #PINNED}）。
 */
public final class FsProtocol {

    private FsProtocol() {}

    public static final Charset UTF8 = Charset.forName("UTF-8");

    public static final int ST_OK = 0, ST_ERR = 1, ST_DENY = 2;

    /** 单帧上限：请求 64 KB，应答 24 MB（原始帧 1080×2220×4≈9.6 MB）（整屏 JPEG 一般 100–200 KB）。 */
    public static final int MAX_REQ = 64 * 1024, MAX_RESP = 24 * 1024 * 1024;

    /** 抽象命名空间 socket 名：每个包一个（release / debug 各起各的助手，互不串）。 */
    public static String socketName(String pkg) {
        return "bixbyui_fs." + pkg;
    }

    /** 进程名（守护用来判断是否已在跑）。 */
    public static String niceName(String pkg) {
        return "bixbyui_fs:" + pkg;
    }

    /** 主类（守护模板里写死）。 */
    public static final String MAIN_CLASS = "com.onesay.assistant.v2.fastscreen.HelperMain";

    /** 守护固定模板：只用来启动助手（守护自己拼命令，App 只发这一个字面量）。 */
    public static final String START_TEMPLATE = "bixby-fastscreen-start";

    /**
     * 固定证书指纹（包名 → SHA-256，小写 hex），须与 exec 守护的 BIXBY_EXEC_SIG_REL / BIXBY_EXEC_SIG_DBG 一致。
     * 开源版不内置任何证书：默认为空 = 快速读屏助手拒绝任何连接（fail closed）。
     * 自行签名构建时在 {@link FsPins#PINNED} 填自己的证书指纹。
     */
    public static final String[][] PINNED = FsPins.PINNED;

    public static String pinnedSig(String pkg) {
        for (String[] p : PINNED) if (p[0].equals(pkg)) return p[1];
        return null;
    }

    // ------------------------------------------------------------------ 方法表

    public static final String OP_PING = "ping";
    /** 精简控件树（JSON 数组，见 {@link FsTree}）。参数：[maxNodes]。 */
    public static final String OP_TREE = "tree";
    /** uiautomator 兼容 XML（给既有 UiDumpParser）。参数：compressed 0|1。 */
    public static final String OP_XML = "xml";
    /** 截图 JPEG。参数：quality(30–95) maxWidth(0=原尺寸)。 */
    public static final String OP_SHOT = "shot";
    /** 截图原始 RGBA（与 screencap 无 -p 同格式：12 字节头 w,h,format + 像素）。 */
    public static final String OP_SHOT_RAW = "shotraw";
    /** 注入点按。参数：x y。 */
    public static final String OP_TAP = "tap";
    /** 无障碍点击：点 (x,y) 处最深的可点击节点（不经过触摸，不受防误触护罩影响）。参数：x y。 */
    public static final String OP_CLICK = "click";
    /** 长按。参数：x y ms。 */
    public static final String OP_LONG = "long";
    /** 注入滑动。参数：x1 y1 x2 y2 ms。 */
    public static final String OP_SWIPE = "swipe";
    /** 无障碍滚动（找 (x,y) 处可滚动节点；没有就退回注入滑动）。参数：x y dir(1 下一页 / -1 上一页)。 */
    public static final String OP_SCROLL = "scroll";
    /** 按键。参数：keycode（白名单 {@link #KEYS}）。 */
    public static final String OP_KEY = "key";
    /** 写文字（中文可用）：ACTION_SET_TEXT 到 (x,y) 处 / 当前焦点的可编辑节点。参数：x y text。 */
    public static final String OP_SETTEXT = "settext";
    /** 粘贴：注入 KEYCODE_PASTE（App 先把文字放进自己的剪贴板）。 */
    public static final String OP_PASTE = "paste";
    /** 启动 App 的启动页。参数：pkg。 */
    public static final String OP_LAUNCH = "launch";
    /** 前台：包名 / Activity / 输入法是否显示。 */
    public static final String OP_TOP = "top";
    /** ★ mika-plug：按节点范围点：找到范围（和类名）对得上的节点，对它或 ≤ 8 层内可点可用的祖先 ACTION_CLICK。参数：l t r b [cls]。 */
    public static final String OP_CLICKB = "clickb";
    /** 等界面空闲（无障碍事件驱动，不是固定 sleep）。参数：idleMs timeoutMs。 */
    public static final String OP_IDLE = "idle";
    /** 导航键观察：开始统计导航栏「返回 / 主页」区域的真人触摸。参数：返回键 l t r b + 主页键 l t r b（屏幕坐标；无障碍里找得到导航键时以它为准）。 */
    public static final String OP_BACKWATCH = "backwatch";
    /** 取走「返回」计数（取后清零）。 */
    public static final String OP_BACKS = "backs";
    /** 停止观察。 */
    public static final String OP_BACKWATCH_OFF = "backwatchoff";
    /** 断开 UiAutomation（App 不用读屏时可以主动释放，accessibility_enabled 随之回 0）。 */
    public static final String OP_RELEASE = "release";
    /** 退出助手。 */
    public static final String OP_QUIT = "quit";

    public static final Set<String> OPS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            OP_PING, OP_TREE, OP_XML, OP_SHOT, OP_SHOT_RAW, OP_TAP, OP_CLICK, OP_LONG, OP_SWIPE, OP_SCROLL, OP_KEY,
            OP_SETTEXT, OP_PASTE, OP_LAUNCH, OP_TOP, OP_CLICKB, OP_IDLE, OP_BACKWATCH, OP_BACKS, OP_BACKWATCH_OFF, OP_RELEASE, OP_QUIT)));

    /** 允许注入的按键：返回 3? 不——HOME=3、BACK=4、ENTER=66、DEL=67、PASTE=279、TAB=61、DPAD 19–22、SEARCH=84、MOVE_END=123、ESCAPE=111。 */
    public static final Set<Integer> KEYS = Collections.unmodifiableSet(new HashSet<Integer>(Arrays.asList(
            3, 4, 19, 20, 21, 22, 61, 66, 67, 84, 111, 123, 279)));

    public static final int KEY_HOME = 3, KEY_BACK = 4, KEY_ENTER = 66, KEY_PASTE = 279;

    // ------------------------------------------------------------------ 编码

    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c == '\t' || c == '\n' || c == '\r' || c == 0 ? ' ' : c);
        }
        return b.toString();
    }

    public static String encode(String op, String... args) {
        StringBuilder b = new StringBuilder(op);
        if (args != null) for (String a : args) b.append('\t').append(clean(a));
        return b.toString();
    }

    public static String[] decode(String line) {
        if (line == null) return new String[0];
        List<String> out = new ArrayList<String>();
        int s = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == '\t') {
                out.add(line.substring(s, i));
                s = i + 1;
            }
        }
        return out.toArray(new String[out.size()]);
    }

    /** 参数校验：返回 null = 合法；否则返回拒绝原因。助手对每条请求先过这里。 */
    public static String validate(String[] p) {
        if (p == null || p.length == 0) return "EMPTY";
        String op = p[0];
        if (!OPS.contains(op)) return "OP_NOT_ALLOWED(" + op + ")";
        try {
            if (op.equals(OP_TAP) || op.equals(OP_CLICK)) return need(p, 3) ? coords(p, 1, 2) : "ARGC";
            if (op.equals(OP_CLICKB)) {
                if (!need(p, 5)) return "ARGC";
                String c = coords(p, 1, 2);
                return c != null ? c : coords(p, 3, 4);
            }
            if (op.equals(OP_LONG)) return need(p, 4) ? (coords(p, 1, 2) != null ? coords(p, 1, 2) : range(p[3], 50, 5000)) : "ARGC";
            if (op.equals(OP_SWIPE)) {
                if (!need(p, 6)) return "ARGC";
                String c = coords(p, 1, 2);
                if (c == null) c = coords(p, 3, 4);
                return c != null ? c : range(p[5], 20, 5000);
            }
            if (op.equals(OP_SCROLL)) return need(p, 4) ? (coords(p, 1, 2) != null ? coords(p, 1, 2) : range(p[3], -1, 1)) : "ARGC";
            if (op.equals(OP_KEY)) {
                if (!need(p, 2)) return "ARGC";
                return KEYS.contains(Integer.parseInt(p[1])) ? null : "KEY_NOT_ALLOWED(" + p[1] + ")";
            }
            if (op.equals(OP_SETTEXT)) {
                if (!need(p, 4)) return "ARGC";
                String c = coords(p, 1, 2);
                if (c != null) return c;
                return p[3].length() > 2000 ? "TEXT_TOO_LONG" : null;
            }
            if (op.equals(OP_LAUNCH)) return need(p, 2) && isPkg(p[1]) ? null : "BAD_PKG";
            if (op.equals(OP_SHOT)) {
                if (p.length >= 2 && range(p[1], 30, 95) != null) return range(p[1], 30, 95);
                if (p.length >= 3 && range(p[2], 0, 4096) != null) return range(p[2], 0, 4096);
                return null;
            }
            if (op.equals(OP_XML)) return p.length >= 2 ? range(p[1], 0, 1) : null;
            if (op.equals(OP_TREE)) {
                if (p.length >= 3 && !p[2].equals("w") && !p[2].equals("1")) return "TREE_MODE";
                return p.length >= 2 ? range(p[1], 10, 5000) : null;
            }
            if (op.equals(OP_IDLE)) return need(p, 3) ? (range(p[1], 50, 5000) != null ? range(p[1], 50, 5000) : range(p[2], 100, 30000)) : "ARGC";
            if (op.equals(OP_BACKWATCH)) {
                if (!need(p, 9)) return "ARGC";
                for (int i = 1; i <= 8; i++) if (range(p[i], 0, 10000) != null) return range(p[i], 0, 10000);
                return null;
            }
            return null;
        } catch (NumberFormatException e) {
            return "NUM";
        }
    }

    private static boolean need(String[] p, int n) {
        return p.length >= n;
    }

    private static String coords(String[] p, int xi, int yi) {
        String a = range(p[xi], 0, 10000);
        return a != null ? a : range(p[yi], 0, 10000);
    }

    private static String range(String s, int lo, int hi) {
        int v = Integer.parseInt(s.trim());
        return v < lo || v > hi ? "RANGE(" + s + ")" : null;
    }

    public static boolean isPkg(String s) {
        if (s == null || s.length() == 0 || s.length() > 128) return false;
        if (s.indexOf('.') < 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) && c < 128) && c != '.' && c != '_') return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ 帧读写

    public static void writeRequest(DataOutputStream o, String line) throws IOException {
        byte[] b = line.getBytes(UTF8);
        if (b.length > MAX_REQ) throw new IOException("request too large");
        o.writeInt(b.length);
        o.write(b);
        o.flush();
    }

    public static String readRequest(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_REQ) throw new IOException("bad request length " + n);
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, UTF8);
    }

    public static void writeResponse(DataOutputStream o, int status, byte[] body) throws IOException {
        if (body == null) body = new byte[0];
        o.writeInt(status);
        o.writeInt(body.length);
        o.write(body);
        o.flush();
    }

    public static void writeResponse(DataOutputStream o, int status, String text) throws IOException {
        writeResponse(o, status, text == null ? new byte[0] : text.getBytes(UTF8));
    }

    /** 应答：status + body。 */
    public static final class Resp {
        public final int status;
        public final byte[] body;

        public Resp(int status, byte[] body) {
            this.status = status;
            this.body = body == null ? new byte[0] : body;
        }

        public boolean ok() {
            return status == ST_OK;
        }

        public String text() {
            return new String(body, UTF8);
        }
    }

    public static Resp readResponse(DataInputStream in) throws IOException {
        int st = in.readInt();
        int n = in.readInt();
        if (n < 0 || n > MAX_RESP) throw new IOException("bad response length " + n);
        byte[] b = new byte[n];
        in.readFully(b);
        return new Resp(st, b);
    }

    // ------------------------------------------------------------------ 证书

    /** DER 证书字节的 SHA-256（小写 hex）。 */
    public static String sha256Hex(byte[] der) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(der);
            StringBuilder b = new StringBuilder(h.length * 2);
            for (byte x : h) b.append(String.format(Locale.US, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 对端身份判定（纯函数）：uid 必须等于守护授权的 uid；该 uid 下必须恰好是我们认识的包，且证书指纹（只允许 1 张）等于固定值。
     * @return null = 放行；否则是拒绝原因
     */
    public static String authorizePeer(int peerUid, int allowedUid, String allowedPkg, String[] pkgsForUid, String[] certShasOfPkg) {
        if (peerUid != allowedUid) return "UID(" + peerUid + "!=" + allowedUid + ")";
        if (pkgsForUid == null || pkgsForUid.length != 1 || !allowedPkg.equals(pkgsForUid[0])) return "PKG";
        String want = pinnedSig(allowedPkg);
        if (want == null) return "NOT_PINNED(" + allowedPkg + ")";
        if (certShasOfPkg == null || certShasOfPkg.length != 1) return "CERT_COUNT";
        return want.equalsIgnoreCase(certShasOfPkg[0]) ? null : "CERT_MISMATCH";
    }

    /** 把小于 MAX 的字节数组拼起来（截图分块用）。 */
    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (byte[] p : parts) if (p != null) bo.write(p, 0, p.length);
        return bo.toByteArray();
    }
}
