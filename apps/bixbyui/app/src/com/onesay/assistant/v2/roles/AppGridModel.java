package com.onesay.assistant.v2.roles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [r2/bike-chooser] 应用选择网格的数据模型（纯 Java，JVM 可测）：role_ui.options → 最多 2×2 = 4 格。
 *
 * <p><b>排序 / 截断规则</b>（用户 2026-10-30 定「最多 4 个、超过只显示前 4 个」）：
 * <ol>
 *   <li>已安装的排前面，未安装的排后面；</li>
 *   <li>同一组内保持 role_ui 给的原有顺序（本地角色 = app_roles.json 种子顺序 = 热门度；大模型 = 它给的顺序）；</li>
 *   <li>取前 {@link AppChoiceSpec#GRID_MAX} 个。每行 2 个，奇数个时最后一行只有 1 格（左对齐，右格留空）。</li>
 * </ol>
 * 每格的动作：已安装 → {@link Action#OPEN}（直接打开，能直达扫码页的用 open_uri）；未安装 → {@link Action#DOWNLOAD}（商店详情页）。
 */
public final class AppGridModel {

    public enum Action {
        OPEN("打开"), DOWNLOAD("下载");

        public final String text;

        Action(String t) {
            text = t;
        }
    }

    public static final class Cell {
        /** 在 role_ui.options 里的下标（点击时回查原始选项用）。 */
        public final int optionIndex;
        public final String id;
        public final String label;
        public final String pkg;
        public final boolean installed;
        public final Action action;
        /** 可选：已安装时优先尝试的深链（如扫码页）；失败退回按包名打开。 */
        public final String openUri;

        Cell(int optionIndex, String id, String label, String pkg, boolean installed, String openUri) {
            this.optionIndex = optionIndex;
            this.id = id;
            this.label = label;
            this.pkg = pkg;
            this.installed = installed;
            this.action = installed ? Action.OPEN : Action.DOWNLOAD;
            this.openUri = openUri;
        }

        /** 无障碍朗读 / 日志：「哈啰，下载」。 */
        public String describe() {
            return label + "，" + action.text;
        }

        @Override public String toString() {
            return label + ":" + action.name();
        }
    }

    public final List<Cell> cells;
    /** 「打开应用商店查看更多」的搜索词；null = 不显示这行链接。 */
    public final String storeQuery;
    /** 原始候选数（截断前），> cells.size() 时说明有被截掉的。 */
    public final int total;

    private AppGridModel(List<Cell> cells, String storeQuery, int total) {
        this.cells = Collections.unmodifiableList(cells);
        this.storeQuery = storeQuery;
        this.total = total;
    }

    public static AppGridModel from(JSONObject roleUi) {
        List<Cell> inst = new ArrayList<Cell>();
        List<Cell> miss = new ArrayList<Cell>();
        int total = 0;
        JSONArray opts = roleUi == null ? null : roleUi.optJSONArray("options");
        if (opts != null) {
            for (int i = 0; i < opts.length(); i++) {
                JSONObject o = opts.optJSONObject(i);
                if (o == null) continue;
                String pkg = o.optString("pkg", "");
                if (!RoleCatalog.PKG.matcher(pkg).matches()) continue;
                total++;
                boolean installed = o.optBoolean("installed", false);
                String uri = o.optString("open_uri", "");
                Cell c = new Cell(i, o.optString("id", ""), nzOr(o.optString("label", ""), pkg), pkg, installed,
                        uri.length() > 0 ? uri : null);
                (installed ? inst : miss).add(c);
            }
        }
        List<Cell> cells = new ArrayList<Cell>();
        for (Cell c : inst) if (cells.size() < AppChoiceSpec.GRID_MAX) cells.add(c);
        for (Cell c : miss) if (cells.size() < AppChoiceSpec.GRID_MAX) cells.add(c);
        String q = roleUi == null ? null : AppChoiceSpec.sanitizeQuery(roleUi.optString("store_query", ""));
        if (q == null && roleUi != null) q = AppChoiceSpec.sanitizeQuery(roleUi.optString("role_label", ""));
        return new AppGridModel(cells, q, total);
    }

    /** 行数（每行 2 格）。 */
    public int rows() {
        return (cells.size() + 1) / 2;
    }

    /** 第 row 行第 col 列（0/1）的格子；空位返回 null。 */
    public Cell at(int row, int col) {
        int i = row * 2 + col;
        return i < cells.size() ? cells.get(i) : null;
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    @Override public String toString() {
        return "Grid" + cells + (storeQuery != null ? " more=" + storeQuery : "") + (total > cells.size() ? " (截掉 " + (total - cells.size()) + ")" : "");
    }

    private static String nzOr(String s, String d) {
        return s == null || s.trim().length() == 0 ? d : s.trim();
    }
}
