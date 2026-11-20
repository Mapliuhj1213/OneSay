package com.onesay.desktop;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * [workflow-1120] 技能（skill）包：一个文件夹 = SKILL.md（头部写 name / description / version，正文给人和 AI 看）
 * + skill.json（inputs / outputs 用 JSON Schema 子集声明、entry 入口与运行时、permissions 权限清单，默认全关）+ scripts/ 脚本。
 *
 * <ul>
 *   <li>注册表扫描用户目录（数据目录 skills/），每个技能算 sha256（所有文件按路径排序后连同内容一起算），工作流节点记 id + 版本 + sha256。</li>
 *   <li>按参数声明生成节点库条目、端口和表单（{@link #palette}）。</li>
 *   <li>安装 / 新建一律走确认卡，卡上列出要的权限；<b>不能「记住」</b>（调用方传进来的 approver 只有允许 / 拒绝）。</li>
 *   <li>运行：JS 入口放进 {@link WfCode} 沙箱；宿主函数按权限逐项给 —— net → onesay.http_get；files → onesay.read_file（只能读技能自己文件夹里的文件）；
 *       tools 这一版不提供（声明了也只是列在确认卡上，调用会报「这一版不支持」）。</li>
 *   <li>没有 skill.json 的社区技能（只有 SKILL.md）照样能装，但只能当「给 AI 看的说明」，不能变成节点。Python / bash 入口这一版不能跑。</li>
 * </ul>
 */
public final class SkillRegistry {
    static final int MAX_FILES = 200, MAX_TOTAL = 2 * 1024 * 1024, MAX_ZIP = 5 * 1024 * 1024;
    static final Set<String> PERM_KEYS = Set.of("net", "files", "tools");

    /** 安装前的确认：title、详情（含权限清单）→ 允许 / 拒绝。 */
    public interface Approver {
        boolean approve(String title, String detail);
    }

    public static final class Skill {
        public String id = "", name = "", description = "", version = "", sha256 = "", body = "", runtime = "", script = "", reason = "";
        public Object inputs, outputs;
        public boolean net, files, runnable;
        public List<String> tools = new ArrayList<>();
        public Path dir;
        public List<String> problems = new ArrayList<>();

        public String risk() { return !tools.isEmpty() ? "local" : net ? "outbound" : "read"; }

        public List<String> permLines() {
            List<String> l = new ArrayList<>();
            if (net) l.add("联网（只能发 GET 请求读网页 / 接口）");
            if (files) l.add("读它自己文件夹里的文件（只读）");
            for (String t : tools) l.add("调用 OneSay 能力：" + t + "（这一版不支持，调用会失败）");
            if (l.isEmpty()) l.add("什么权限都不要（不能联网、不能读写文件）");
            return l;
        }

        public Map<String, Object> view() {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("description", description);
            m.put("version", version);
            m.put("sha256", sha256);
            m.put("runtime", runtime);
            m.put("runnable", runnable);
            if (!reason.isEmpty()) m.put("reason", reason);
            m.put("inputs", inputs == null ? new TreeMap<>() : inputs);
            m.put("outputs", outputs == null ? new TreeMap<>() : outputs);
            m.put("fields", JsonSchemaLite.fields(inputs));
            m.put("out_fields", JsonSchemaLite.fields(outputs));
            Map<String, Object> p = new TreeMap<>();
            p.put("net", net);
            p.put("files", files);
            p.put("tools", new ArrayList<Object>(tools));
            m.put("permissions", p);
            m.put("perm_lines", new ArrayList<Object>(permLines()));
            m.put("risk", risk());
            return m;
        }

        /** 存进节点的参数声明快照（技能缺了也能把节点画出来）。 */
        public Map<String, Object> snapshot() {
            Map<String, Object> m = new TreeMap<>();
            m.put("name", name);
            m.put("inputs", inputs == null ? new TreeMap<>() : inputs);
            m.put("outputs", outputs == null ? new TreeMap<>() : outputs);
            Map<String, Object> p = new TreeMap<>();
            p.put("net", net);
            p.put("files", files);
            p.put("tools", new ArrayList<Object>(tools));
            m.put("permissions", p);
            return m;
        }

        public Map<String, Object> ref() {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("version", version);
            m.put("sha256", sha256);
            return m;
        }
    }

    private final Path userDir;
    private final Consumer<String> log;
    private final Map<String, Skill> skills = new TreeMap<>();
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public SkillRegistry(Path userDir, Consumer<String> log) {
        this.userDir = userDir;
        this.log = log == null ? s -> { } : log;
        rescan();
    }

    public Path dir() { return userDir; }

    public synchronized void rescan() {
        skills.clear();
        if (userDir == null || !Files.isDirectory(userDir)) return;
        try (Stream<Path> s = Files.list(userDir)) {
            s.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().forEach(p -> {
                try {
                    Skill k = parse(p);
                    if (k.problems.isEmpty() || !k.id.isEmpty()) skills.put(k.id, k);
                } catch (IOException | RuntimeException e) {
                    log.accept("技能读不出来：" + p.getFileName() + "（" + e.getClass().getSimpleName() + "）");
                }
            });
        } catch (IOException e) {
            log.accept("技能目录读不出来");
        }
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Skill s : skills.values()) out.add(s.view());
        return out;
    }

    public synchronized Skill get(String id) { return skills.get(id); }

    /** 节点库条目：只给能变成节点的（有参数声明、JS 入口）。 */
    public synchronized List<Map<String, Object>> palette() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Skill s : skills.values()) if (s.runnable) {
            Map<String, Object> m = s.view();
            m.put("node", nodeTemplate(s));
            out.add(m);
        }
        return out;
    }

    /** 按声明生成的节点（data 部分）：技能引用 + 声明快照 + 每个输入一个空值。 */
    public static Map<String, Object> nodeTemplate(Skill s) {
        Map<String, Object> d = new TreeMap<>();
        d.put("title", s.name);
        d.put("skill", s.ref());
        d.put("schemaSnapshot", s.snapshot());
        Map<String, Object> in = new TreeMap<>();
        for (Map<String, Object> f : JsonSchemaLite.fields(s.inputs)) in.put(String.valueOf(f.get("name")), f.containsKey("default") ? f.get("default") : "");
        d.put("inputs", in);
        return d;
    }

    // ------------------------------------------------------------------ 解析

    /** 解析 SKILL.md 头部（--- 之间的 key: value，只认一层）。 */
    static Map<String, String> frontMatter(String md) {
        Map<String, String> m = new LinkedHashMap<>();
        if (md == null) return m;
        String t = md.replace("\r\n", "\n");
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        if (!t.startsWith("---\n")) return m;
        int end = t.indexOf("\n---", 4);
        if (end < 0) return m;
        for (String line : t.substring(4, end).split("\n")) {
            int c = line.indexOf(':');
            if (c <= 0 || line.startsWith(" ") || line.startsWith("#")) continue;
            String k = line.substring(0, c).trim(), v = line.substring(c + 1).trim();
            if ((v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'")) && v.length() >= 2) v = v.substring(1, v.length() - 1);
            m.put(k, v);
        }
        return m;
    }

    static String bodyOf(String md) {
        String t = md.replace("\r\n", "\n");
        if (!t.startsWith("---\n")) return t;
        int end = t.indexOf("\n---", 4);
        if (end < 0) return t;
        int nl = t.indexOf('\n', end + 4);
        return nl < 0 ? "" : t.substring(nl + 1);
    }

    /** 读一个技能文件夹（不检查有没有装过）。 */
    @SuppressWarnings("unchecked")
    public static Skill parse(Path dir) throws IOException {
        Skill k = new Skill();
        k.dir = dir;
        Map<String, byte[]> files = readTree(dir);
        k.sha256 = sha256(files);
        byte[] md = files.get("SKILL.md");
        if (md == null) { k.problems.add("缺 SKILL.md"); k.id = ""; return k; }
        String mds = new String(md, StandardCharsets.UTF_8);
        Map<String, String> fm = frontMatter(mds);
        k.body = bodyOf(mds);
        k.name = fm.getOrDefault("name", "").trim();
        k.description = fm.getOrDefault("description", "").trim();
        k.version = fm.getOrDefault("version", "0.0.0").trim();
        String id = fm.getOrDefault("id", "").trim();
        if (id.isEmpty()) id = slug(k.name.isEmpty() ? dir.getFileName().toString() : k.name);
        if (id.isEmpty()) id = slug(dir.getFileName().toString());
        k.id = id;
        if (!id.matches("[a-z0-9][a-z0-9-]{0,47}")) k.problems.add("技能编号只能用小写字母、数字、短横线：" + id);
        if (k.name.isEmpty()) k.problems.add("SKILL.md 头部要写 name");
        if (k.name.length() > 40) k.problems.add("name 太长（最多 40 字）");
        if (k.description.length() > 500) k.problems.add("description 太长（最多 500 字）");
        if (!k.version.matches("\\d{1,4}(\\.\\d{1,4}){0,2}([-+][A-Za-z0-9.]{1,20})?")) k.problems.add("version 写法不对：" + k.version + "（例如 1.0.0）");
        byte[] sj = files.get("skill.json");
        if (sj == null) {
            k.runnable = false;
            k.reason = "没有 skill.json（参数声明），只能给 AI 当说明，不能变成节点";
            return k;
        }
        Map<String, Object> spec;
        try { spec = Json.obj(new String(sj, StandardCharsets.UTF_8)); } catch (RuntimeException e) { k.problems.add("skill.json 不是合法 JSON"); k.reason = "skill.json 写错了"; return k; }
        for (String key : spec.keySet()) if (!Set.of("inputs", "outputs", "entry", "permissions").contains(key)) k.problems.add("skill.json 里不认识的字段：" + key);
        k.inputs = spec.get("inputs") == null ? Map.of("type", "object", "properties", new TreeMap<>()) : spec.get("inputs");
        k.outputs = spec.get("outputs") == null ? Map.of("type", "object", "properties", new TreeMap<>()) : spec.get("outputs");
        for (String p : JsonSchemaLite.checkSchema(k.inputs)) k.problems.add("inputs：" + p);
        for (String p : JsonSchemaLite.checkSchema(k.outputs)) k.problems.add("outputs：" + p);
        Object perms = spec.get("permissions");
        if (perms != null) {
            if (!(perms instanceof Map)) k.problems.add("permissions 应该是对象");
            else {
                Map<String, Object> pm = (Map<String, Object>) perms;
                for (String key : pm.keySet()) if (!PERM_KEYS.contains(key)) k.problems.add("不认识的权限：" + key + "（只认 net / files / tools）");
                k.net = Boolean.TRUE.equals(pm.get("net"));
                k.files = Boolean.TRUE.equals(pm.get("files"));
                if (pm.get("tools") instanceof List) for (Object t : (List<Object>) pm.get("tools")) k.tools.add(String.valueOf(t));
            }
        }
        Object entry = spec.get("entry");
        Map<String, Object> em = entry instanceof Map ? (Map<String, Object>) entry : Map.of();
        k.runtime = String.valueOf(em.getOrDefault("runtime", "js")).toLowerCase(Locale.ROOT);
        k.script = String.valueOf(em.getOrDefault("script", "scripts/main.js"));
        if (!k.runtime.equals("js") && !k.runtime.equals("javascript")) {
            k.runnable = false;
            k.reason = k.runtime.startsWith("py") ? "这一版还不能跑 Python 技能，只能给 AI 当说明" : "运行时 " + k.runtime + " 不支持（只能跑 JS），只能给 AI 当说明";
        } else if (!safeRel(k.script) || !files.containsKey(k.script)) {
            k.problems.add("入口脚本不存在或路径不合法：" + k.script);
            k.reason = "入口脚本找不到";
        } else k.runnable = k.problems.isEmpty();
        if (!k.problems.isEmpty() && k.reason.isEmpty()) k.reason = "声明有问题：" + k.problems.get(0);
        return k;
    }

    static boolean safeRel(String rel) {
        return rel != null && !rel.isEmpty() && rel.length() < 200 && !rel.startsWith("/") && !rel.startsWith("\\") && !rel.contains("..") && !rel.contains(":") && !rel.contains("\\");
    }

    static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /** 读整棵目录（相对路径用 /）；不跟符号链接；有数量和总大小上限。 */
    static Map<String, byte[]> readTree(Path dir) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        long[] total = {0};
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                if (Files.isSymbolicLink(p)) throw new IOException("技能里不能有符号链接");
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".git/") || rel.equals(".DS_Store")) continue;
                if (out.size() >= MAX_FILES) throw new IOException("技能文件太多（最多 " + MAX_FILES + " 个）");
                byte[] b = Files.readAllBytes(p);
                total[0] += b.length;
                if (total[0] > MAX_TOTAL) throw new IOException("技能太大（最多 2 MB）");
                out.put(rel, b);
            }
        }
        return out;
    }

    static String sha256(Map<String, byte[]> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue());
                md.update((byte) 0);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ 安装 / 新建

    /** 从文件夹 / zip 文件 / https zip 地址安装。走确认卡（列权限），拒绝就不装。 */
    public Map<String, Object> install(String source, Approver approver) {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("onesay-skill");
            Path src;
            String s = source == null ? "" : source.trim();
            if (s.matches("(?i)https://.+")) {
                byte[] zip = download(s);
                src = unzip(new ByteArrayInputStream(zip), tmp.resolve("x"));
            } else if (s.matches("(?i)http://.+")) {
                return fail("只能从 https 地址安装");
            } else {
                Path p = Path.of(s);
                if (Files.isDirectory(p)) src = p;
                else if (Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    if (Files.size(p) > MAX_ZIP) return fail("压缩包太大（最多 5 MB）");
                    try (InputStream in = Files.newInputStream(p)) { src = unzip(in, tmp.resolve("x")); }
                } else return fail("找不到这个技能：要给一个文件夹、.zip 文件或 https 的 zip 地址");
            }
            return installParsed(src, approver, "安装");
        } catch (IOException | RuntimeException e) {
            return fail("安装失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (tmp != null) deleteTree(tmp);
        }
    }

    Map<String, Object> installParsed(Path src, Approver approver, String verb) throws IOException {
        Skill k = parse(src);
        if (k.id.isEmpty() || !k.problems.isEmpty()) return fail("这个技能的声明有问题：" + String.join("；", k.problems.subList(0, Math.min(3, k.problems.size()))));
        Skill old = get(k.id);
        if (old != null && old.sha256.equals(k.sha256)) { Map<String, Object> r = ok("「" + k.name + "」已经装过同一版"); r.put("skill", old.view()); return r; }
        StringBuilder card = new StringBuilder();
        card.append(verb).append("技能「").append(k.name).append("」").append(old != null ? "（" + old.version + " → " + k.version + "）" : "（" + k.version + "）").append("\n");
        if (!k.description.isEmpty()) card.append(WfCode.clip(k.description, 200)).append("\n");
        card.append("它要的权限：\n");
        for (String l : k.permLines()) card.append("· ").append(l).append("\n");
        if (!k.runnable) card.append("注意：").append(k.reason).append("\n");
        card.append("校验码 ").append(k.sha256, 0, 12);
        boolean yes;
        try { yes = approver != null && approver.approve(verb + "技能", card.toString()); } catch (RuntimeException e) { yes = false; }
        if (!yes) return fail("你没批准，没有" + verb);
        Path dest = userDir.resolve(k.id);
        Files.createDirectories(userDir);
        Path stage = userDir.resolve("." + k.id + ".new");
        deleteTree(stage);
        copyTree(src, stage);
        Skill check = parse(stage);
        if (!check.sha256.equals(k.sha256)) { deleteTree(stage); return fail("复制后校验码对不上，没有" + verb); }
        deleteTree(dest);
        Files.move(stage, dest, StandardCopyOption.ATOMIC_MOVE);
        rescan();
        log.accept("已" + verb + "技能：" + k.name + " " + k.version);
        Map<String, Object> r = ok("已" + verb + "「" + k.name + "」" + (k.runnable ? "，节点库里已经有它了" : "（" + k.reason + "）"));
        r.put("skill", get(k.id).view());
        return r;
    }

    /** 新建技能（AI 或界面填的）：spec 里有 id/name/description/version/inputs/outputs/code/permissions。同样走确认卡。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> spec, Approver approver) {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("onesay-skill-new");
            String name = String.valueOf(spec.getOrDefault("name", "")).trim();
            String id = String.valueOf(spec.getOrDefault("id", slug(name))).trim();
            if (id.isEmpty()) id = "skill-" + Long.toString(System.currentTimeMillis() % 100000, 36);
            String code = String.valueOf(spec.getOrDefault("code", ""));
            if (code.isBlank()) return fail("新技能要有代码（function main(input) { ... }）");
            Path d = tmp.resolve(id);
            Files.createDirectories(d.resolve("scripts"));
            String md = "---\nid: " + id + "\nname: " + name.replace("\n", " ") + "\ndescription: " + String.valueOf(spec.getOrDefault("description", "")).replace("\n", " ")
                    + "\nversion: " + spec.getOrDefault("version", "0.1.0") + "\n---\n\n" + spec.getOrDefault("doc", "（由 OneSay 新建）") + "\n";
            Files.writeString(d.resolve("SKILL.md"), md, StandardCharsets.UTF_8);
            Map<String, Object> sj = new TreeMap<>();
            sj.put("inputs", spec.getOrDefault("inputs", Map.of("type", "object", "properties", new TreeMap<>())));
            sj.put("outputs", spec.getOrDefault("outputs", Map.of("type", "object", "properties", new TreeMap<>())));
            sj.put("entry", Map.of("runtime", "js", "script", "scripts/main.js"));
            Map<String, Object> perms = new TreeMap<>();
            perms.put("net", false);
            perms.put("files", false);
            perms.put("tools", new ArrayList<>());
            if (spec.get("permissions") instanceof Map) perms.putAll((Map<String, Object>) spec.get("permissions"));
            sj.put("permissions", perms);
            Files.writeString(d.resolve("skill.json"), Json.stringify(sj), StandardCharsets.UTF_8);
            Files.writeString(d.resolve("scripts/main.js"), code, StandardCharsets.UTF_8);
            return installParsed(d, approver, "新建");
        } catch (IOException | RuntimeException e) {
            return fail("新建失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (tmp != null) deleteTree(tmp);
        }
    }

    public synchronized Map<String, Object> uninstall(String id) {
        Skill k = skills.get(id);
        if (k == null) return fail("没有这个技能");
        Path d = k.dir == null ? null : k.dir.toAbsolutePath().normalize();
        if (d == null || !d.startsWith(userDir.toAbsolutePath().normalize()) || d.equals(userDir.toAbsolutePath().normalize())) return fail("这个技能不在技能目录里，没删");
        deleteTree(d);
        rescan();
        return ok("已删除技能「" + k.name + "」");
    }

    byte[] download(String url) throws IOException {
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("下载失败（HTTP " + r.statusCode() + "）");
            if (!r.uri().getScheme().equalsIgnoreCase("https")) throw new IOException("被跳转到了非 https 地址");
            try (InputStream in = r.body()) {
                byte[] b = in.readNBytes(MAX_ZIP + 1);
                if (b.length > MAX_ZIP) throw new IOException("压缩包太大（最多 5 MB）");
                return b;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("被取消");
        }
    }

    /** 解压（防目录穿越、限数量和大小）；压缩包里只有一个顶层文件夹时返回它。 */
    static Path unzip(InputStream raw, Path dest) throws IOException {
        Files.createDirectories(dest);
        int n = 0;
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..") || name.contains(":")) throw new IOException("压缩包里有不安全的路径：" + name);
                Path out = dest.resolve(name).normalize();
                if (!out.startsWith(dest)) throw new IOException("压缩包里有不安全的路径：" + name);
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                if (++n > MAX_FILES) throw new IOException("压缩包文件太多");
                Files.createDirectories(out.getParent());
                byte[] b = z.readNBytes(MAX_TOTAL + 1);
                total += b.length;
                if (total > MAX_TOTAL) throw new IOException("解压后太大（最多 2 MB）");
                Files.write(out, b);
            }
        }
        if (Files.exists(dest.resolve("SKILL.md"))) return dest;
        try (Stream<Path> s = Files.list(dest)) {
            List<Path> l = s.toList();
            if (l.size() == 1 && Files.isDirectory(l.get(0))) return l.get(0);
        }
        return dest;
    }

    static void copyTree(Path src, Path dst) throws IOException {
        Map<String, byte[]> files = readTree(src);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path out = dst.resolve(e.getKey()).normalize();
            if (!out.startsWith(dst)) throw new IOException("不安全的路径");
            Files.createDirectories(out.getParent());
            Files.write(out, e.getValue());
        }
        Files.createDirectories(dst);
    }

    static void deleteTree(Path p) {
        if (p == null || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ 运行

    /** 节点引用和已装的对不对得上：null = 对得上；否则是给人看的原因。 */
    public synchronized String check(Map<String, Object> ref) {
        String id = ref == null ? "" : String.valueOf(ref.getOrDefault("id", ""));
        Skill k = skills.get(id);
        if (k == null) return "缺少技能「" + id + "」，请先安装";
        if (!k.runnable) return "技能「" + k.name + "」不能当节点运行：" + k.reason;
        String sha = ref == null ? "" : String.valueOf(ref.getOrDefault("sha256", ""));
        if (!sha.isEmpty() && !sha.equals(k.sha256)) return "技能「" + k.name + "」已装的版本（" + k.version + "）和工作流里记的不一样，请在节点上点「换成已装版本」确认后再运行";
        return null;
    }

    /** 跑一个技能：校验输入 → 沙箱 → 校验输出。 */
    public Map<String, Object> run(Map<String, Object> ref, Map<String, Object> input, int timeoutMs, Function<String, String> httpGet) throws Exception {
        String bad = check(ref);
        if (bad != null) throw new IllegalStateException(bad);
        Skill k = get(String.valueOf(ref.get("id")));
        if (!sha256(readTree(k.dir)).equals(k.sha256)) throw new IllegalStateException("技能「" + k.name + "」的文件在安装后被改过，不运行（请重新安装）");
        Object[] v = JsonSchemaLite.validate(k.inputs, input == null ? new TreeMap<>() : input);
        @SuppressWarnings("unchecked") List<String> errs = (List<String>) v[1];
        if (!errs.isEmpty()) throw new IllegalArgumentException("输入不对：" + String.join("；", errs));
        String code = Files.readString(k.dir.resolve(k.script), StandardCharsets.UTF_8);
        Map<String, Function<String, String>> host = new TreeMap<>();
        if (k.net && httpGet != null) host.put("http_get", httpGet);
        if (k.files) {
            Path root = k.dir.toRealPath();
            host.put("read_file", rel -> {
                if (!safeRel(rel)) return "{\"error\":\"路径不合法\"}";
                try {
                    Path p = root.resolve(rel).normalize();
                    if (!p.startsWith(root) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return "{\"error\":\"只能读技能自己文件夹里的文件\"}";
                    if (!p.toRealPath().startsWith(root)) return "{\"error\":\"只能读技能自己文件夹里的文件\"}";
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) { return "{\"error\":\"读不出来\"}"; }
            });
        }
        if (!k.tools.isEmpty()) host.put("tool", s -> "{\"error\":\"这一版技能还不能调用 OneSay 能力\"}");
        WfCode.Result r = WfCode.run(code, v[0], timeoutMs <= 0 ? 10_000 : timeoutMs, 64, host);
        if (!r.ok) throw new IllegalStateException(r.error);
        Map<String, Object> out = WfCode.asOutput(r.value);
        Object[] ov = JsonSchemaLite.validate(k.outputs, out);
        @SuppressWarnings("unchecked") List<String> oerr = (List<String>) ov[1];
        if (!oerr.isEmpty()) throw new IllegalStateException("技能返回的结果和它的声明对不上：" + String.join("；", oerr));
        @SuppressWarnings("unchecked") Map<String, Object> res = (Map<String, Object>) ov[0];
        return res;
    }

    static Map<String, Object> ok(String t) { Map<String, Object> m = new TreeMap<>(); m.put("ok", true); m.put("text", t); return m; }

    static Map<String, Object> fail(String t) { Map<String, Object> m = ok(t); m.put("ok", false); return m; }

    /** 能力列表里的技能条目。 */
    public synchronized List<Map<String, Object>> capabilities() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Skill s : skills.values()) {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", "skill:" + s.id);
            m.put("name", s.name);
            m.put("kind", "skill");
            m.put("kind_zh", "技能");
            m.put("description", s.description);
            m.put("risk", s.risk());
            m.put("credential", "不需要");
            m.put("ready", s.runnable);
            m.put("schema", s.inputs == null ? new TreeMap<>() : s.inputs);
            out.add(m);
        }
        return out;
    }
}
