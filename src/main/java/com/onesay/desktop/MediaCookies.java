package com.onesay.desktop;

import java.io.IOException;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 链接下载 · 登录内容：你自己从浏览器导出的 cookies.txt（Netscape 格式），按站点存在配置目录 cookies\ 下，
 * 文件权限只给当前账户（Windows 用 ACL 只留文件所有者；其他系统 rw-------）。
 *
 * <p>不从浏览器读 Cookie、不做浏览器扩展、不破 DRM。Cookie 内容不进日志、不进提交；日志里的路径也打码（{@link MediaArgs#masked}）。
 * 每次下载用一份临时副本（yt-dlp 会把 Cookie 写回文件，两个任务同时写同一份会打架），下载完删掉。
 */
public final class MediaCookies {
    public static final int MAX_BYTES = 200 * 1024;
    final Path dir;

    public MediaCookies(Path dir) { this.dir = dir; }

    /** 站点名规范化：去 www.、转小写、IDN 转 ASCII；不像域名返回 null。 */
    public static String site(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("http://") || s.startsWith("https://")) {
            try { s = URI.create(s).getHost(); } catch (Exception e) { return null; }
            if (s == null) return null;
        }
        while (s.startsWith(".")) s = s.substring(1);
        if (s.startsWith("www.")) s = s.substring(4);
        try { s = IDN.toASCII(s); } catch (Exception e) { return null; }
        return s.matches("[a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63})+") && s.length() <= 253 ? s : null;
    }

    /**
     * 检查并整理 cookies.txt：只留属于这个站点（含子域名）的行；格式不对 / 一条都没有返回原因（以 "!" 开头），否则返回整理后的内容。
     * 每条 Cookie 行是 7 个用 Tab 分开的字段：域名、子域、路径、安全、过期、名字、值；#HttpOnly_ 前缀的也认。
     */
    public static String clean(String site, String text) {
        if (text == null || text.isBlank()) return "!文件是空的";
        if (text.length() > MAX_BYTES) return "!文件太大（超过 200 KB）：请只导出这个网站的 Cookie";
        StringBuilder b = new StringBuilder("# Netscape HTTP Cookie File\n# OneSay：只留 ").append(site).append(" 的 Cookie\n");
        int n = 0, bad = 0;
        for (String line : text.split("\\r?\\n")) {
            String l = line;
            if (l.isBlank()) continue;
            boolean httpOnly = l.startsWith("#HttpOnly_");
            if (l.startsWith("#") && !httpOnly) continue;
            String[] f = l.split("\t", -1);
            if (f.length != 7) { bad++; continue; }
            String dom = (httpOnly ? f[0].substring("#HttpOnly_".length()) : f[0]).toLowerCase(Locale.ROOT);
            String d = dom.startsWith(".") ? dom.substring(1) : dom;
            if (!(d.equals(site) || d.endsWith("." + site))) continue;
            if (!f[1].matches("(?i)TRUE|FALSE") || !f[3].matches("(?i)TRUE|FALSE") || !f[4].matches("-?\\d{1,20}")) { bad++; continue; }
            b.append(l).append('\n');
            n++;
        }
        if (n == 0) return bad > 0 ? "!不是 Netscape 格式的 cookies.txt（每行要有 7 个 Tab 分开的字段）" : "!文件里没有 " + site + " 的 Cookie";
        return b.toString();
    }

    Path file(String site) { return dir.resolve(site + ".txt"); }

    /** 导入：返回 null = 成功，否则原因。 */
    public synchronized String save(String rawSite, String text) {
        String site = site(rawSite);
        if (site == null) return "站点要写成域名，例如 bilibili.com";
        String c = clean(site, text);
        if (c.startsWith("!")) return c.substring(1);
        try {
            Files.createDirectories(dir);
            ownerOnly(dir, true);
            Path tmp = dir.resolve(site + ".txt.tmp");
            Files.deleteIfExists(tmp);
            Files.createFile(tmp);
            ownerOnly(tmp, false);
            Files.writeString(tmp, c, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file(site), StandardCopyOption.REPLACE_EXISTING);
            ownerOnly(file(site), false);
            return null;
        } catch (IOException e) {
            return "保存失败：" + e.getClass().getSimpleName();
        }
    }

    public synchronized boolean delete(String rawSite) {
        String site = site(rawSite);
        if (site == null) return false;
        try { return Files.deleteIfExists(file(site)); } catch (IOException e) { return false; }
    }

    /** 设置页：导入过的站点（只给站点名和条数，不给内容）。 */
    public synchronized List<Object> list() {
        List<Object> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().forEach(p -> {
                Map<String, Object> m = new TreeMap<>();
                String n = p.getFileName().toString();
                m.put("site", n.substring(0, n.length() - 4));
                try {
                    m.put("count", Files.readAllLines(p, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank() && (!l.startsWith("#") || l.startsWith("#HttpOnly_"))).count());
                    m.put("at", Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) { }
                out.add(m);
            });
        } catch (IOException ignored) { }
        return out;
    }

    /** 这个链接用哪份 Cookie：域名是站点本身或它的子域名，最长匹配优先；没有返回 null。 */
    public synchronized Path forUrl(String url) {
        String host;
        try { host = URI.create(url).getHost(); } catch (Exception e) { return null; }
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        Path best = null;
        int len = -1;
        for (Object o : list()) {
            String s = String.valueOf(((Map<?, ?>) o).get("site"));
            if ((host.equals(s) || host.endsWith("." + s)) && s.length() > len) {
                best = file(s);
                len = s.length();
            }
        }
        return best;
    }

    /** 给一次下载用的临时副本（只给当前账户）；没有这个站点的 Cookie 返回 null。用完调用方删掉。 */
    public Path copyFor(String url, Path workDir) throws IOException {
        Path src = forUrl(url);
        if (src == null) return null;
        Files.createDirectories(workDir);
        Path tmp = Files.createTempFile(workDir, "c", ".txt");
        ownerOnly(tmp, false);
        Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
        ownerOnly(tmp, false);
        return tmp;
    }

    /** 只给当前账户：POSIX 用 rw-------（目录 rwx------）；Windows 用 ACL 只留文件所有者一条（不继承）。都不支持就算了（不影响功能）。 */
    static void ownerOnly(Path p, boolean dirToo) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(dirToo ? "rwx------" : "rw-------"));
            return;
        } catch (UnsupportedOperationException | IOException ignored) { }
        try {
            AclFileAttributeView v = Files.getFileAttributeView(p, AclFileAttributeView.class);
            if (v == null) return;
            UserPrincipal me = currentUser(p);
            AclEntry e = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(me)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            v.setAcl(List.of(e));
        } catch (Exception ignored) { }
    }

    /**
     * [fix-1114 实测 P2-6] 当前登录的账户（不是「文件所有者」）：内置 Administrator 建的文件所有者是 BUILTIN\Administrators 组，
     * 按所有者设 ACL 等于全体管理员可读。按「域\用户名」→「用户名」查；都查不到才退回所有者（总比不设强）。
     */
    static UserPrincipal currentUser(Path p) throws IOException {
        var look = p.getFileSystem().getUserPrincipalLookupService();
        for (String name : userNames()) {
            try { return look.lookupPrincipalByName(name); } catch (Exception ignored) { }
        }
        return Files.getOwner(p);
    }

    static List<String> userNames() {
        List<String> out = new java.util.ArrayList<>();
        String user = System.getenv("USERNAME"), dom = System.getenv("USERDOMAIN");
        if (user == null || user.isBlank()) user = System.getProperty("user.name", "");
        if (user != null && !user.isBlank()) {
            if (dom != null && !dom.isBlank()) out.add(dom + "\\" + user);
            out.add(user);
        }
        return out;
    }
}
