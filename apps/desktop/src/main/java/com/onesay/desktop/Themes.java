package com.onesay.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * [preview-1120] 主题插件：浅色 / 深色 / 跟随系统 + 可加载的主题包。
 *
 * <p>主题包是一个小 JSON（格式见 docs/THEMES.md）：{@code {"id","name","mode":"light|dark","author","license","source","tokens":{"--bg":"#…",…}}}。
 * 只认下面列出的颜色变量，值只能是 #RGB / #RRGGBB / #RRGGBBAA / rgb() / rgba()——界面把它们写成 CSS 变量，所以包里放不进脚本、网址或别的 CSS。
 * 自带几个从 MIT 许可的配色移植的包（Nord、Catppuccin、Dracula、Solarized，见 THIRD_PARTY_NOTICES.md）；用户导入的放在配置目录 themes/ 下。
 */
public final class Themes {
    private Themes() { }

    /** 主题包能改的变量（界面里都用这些画）。 */
    public static final Set<String> TOKENS = Set.of("--bg", "--card", "--surface2", "--text", "--text2", "--text3", "--line", "--accent", "--accentC",
            "--red", "--green", "--warn", "--swoff", "--hover", "--chip", "--event", "--todo", "--ticket", "--pickup", "--auto", "--device", "--shadow");

    static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{3}|#[0-9a-fA-F]{4}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}|rgba?\\(\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*(,\\s*(0|1|0?\\.\\d{1,3})\\s*)?\\)");
    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    public static final int MAX_BYTES = 32 * 1024;
    /** 自带的包（resources/onesay/themes/ 下的文件名）。 */
    static final List<String> BUNDLED = List.of("nord", "catppuccin-latte", "catppuccin-mocha", "dracula", "solarized-light");

    /** 读一个包并校验；不合格抛 IllegalArgumentException（中文原因）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("主题包是空的");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("主题包太大（超过 32 KB）");
        Map<String, Object> m;
        try { m = Json.obj(json); } catch (RuntimeException e) { throw new IllegalArgumentException("主题包不是合法的 JSON"); }
        String id = Mcp.str(m.get("id")).trim().toLowerCase(java.util.Locale.ROOT);
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("主题包的 id 只能用小写字母、数字和 -（1–40 个字）");
        String name = Mcp.str(m.get("name")).trim();
        if (name.isEmpty() || name.length() > 40) throw new IllegalArgumentException("主题包要有名字（40 字以内）");
        String mode = Mcp.str(m.get("mode")).trim().toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals("light") && !mode.equals("dark")) throw new IllegalArgumentException("mode 要写 light 或 dark");
        if (!(m.get("tokens") instanceof Map)) throw new IllegalArgumentException("主题包里没有 tokens（颜色表）");
        Map<String, Object> tk = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("tokens")).entrySet()) {
            String k = e.getKey().startsWith("--") ? e.getKey() : "--" + e.getKey();
            String v = Mcp.str(e.getValue()).trim();
            if (!TOKENS.contains(k)) { bad.add(e.getKey() + "（不认识的颜色名）"); continue; }
            if (!COLOR.matcher(v).matches()) { bad.add(e.getKey() + "（值只能是 #颜色 或 rgb()/rgba()）"); continue; }
            tk.put(k, v);
        }
        if (!bad.isEmpty()) throw new IllegalArgumentException("主题包里有不支持的项：" + String.join("、", bad));
        for (String need : List.of("--bg", "--card", "--text", "--accent")) if (!tk.containsKey(need)) throw new IllegalArgumentException("主题包至少要有 --bg、--card、--text、--accent，缺 " + need);
        Map<String, Object> out = new TreeMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("mode", mode);
        for (String k : List.of("author", "license", "source")) out.put(k, ChatAgent.clip(Mcp.str(m.get(k)).trim(), 120).replaceAll("[<>\"`]", ""));
        out.put("tokens", tk);
        return out;
    }

    static Map<String, Object> bundled(String id) {
        try (InputStream in = Themes.class.getClassLoader().getResourceAsStream("onesay/themes/" + id + ".json")) {
            if (in == null) return null;
            Map<String, Object> m = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            m.put("bundled", true);
            return m;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** 所有包：自带的在前，用户导入的在后（坏掉的跳过）。 */
    public static List<Object> list(Path userDir) {
        List<Object> out = new ArrayList<>();
        for (String id : BUNDLED) { Map<String, Object> m = bundled(id); if (m != null) out.add(m); }
        if (userDir != null && Files.isDirectory(userDir)) {
            try (java.util.stream.Stream<Path> s = Files.list(userDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                    try {
                        Map<String, Object> m = parse(Files.readString(p, StandardCharsets.UTF_8));
                        if (BUNDLED.contains(String.valueOf(m.get("id")))) return;
                        m.put("bundled", false);
                        out.add(m);
                    } catch (Exception ignored) { }
                });
            } catch (IOException ignored) { }
        }
        return out;
    }

    /** 导入：校验后存到 userDir/<id>.json（同 id 覆盖）。返回包。 */
    public static Map<String, Object> importPack(Path userDir, String json) throws IOException {
        Map<String, Object> m = parse(json);
        if (BUNDLED.contains(String.valueOf(m.get("id")))) throw new IllegalArgumentException("id「" + m.get("id") + "」和自带的主题重名，换一个");
        Files.createDirectories(userDir);
        Map<String, Object> save = new TreeMap<>(m);
        Files.writeString(userDir.resolve(m.get("id") + ".json"), Json.stringify(save), StandardCharsets.UTF_8);
        m.put("bundled", false);
        return m;
    }

    public static boolean remove(Path userDir, String id) throws IOException {
        if (id == null || !ID.matcher(id).matches() || BUNDLED.contains(id)) return false;
        return Files.deleteIfExists(userDir.resolve(id + ".json"));
    }

    /** 外观设置的三种：跟随系统 / 浅色 / 深色。 */
    public static String mode(String v) {
        return "light".equals(v) || "dark".equals(v) ? v : "system";
    }
}
