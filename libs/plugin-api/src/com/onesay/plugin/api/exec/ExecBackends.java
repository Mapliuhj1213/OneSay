package com.onesay.plugin.api.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行后端注册表 + 当前选择（纯 Java，进程内单例）。
 *
 * <ul>
 *   <li>宿主（App）启动时 {@link #register} 各后端插件，并把用户在设置里选的 id 交给 {@link #select}；</li>
 *   <li>默认什么都不选（{@link #NONE}）——开源构建的特权动作因此默认置灰；</li>
 *   <li>{@link #ready()}：选中的后端此刻报告 {@link ExecBackend.Tri#YES} 才返回它。</li>
 * </ul>
 */
public final class ExecBackends {
    private ExecBackends() {}

    public static final String NONE = "";

    private static final Map<String, ExecBackend> REG = new LinkedHashMap<String, ExecBackend>();
    private static volatile String sSelected = NONE;

    public static synchronized void register(ExecBackend b) {
        if (b == null || b.id() == null || b.id().length() == 0) throw new IllegalArgumentException("backend id");
        REG.put(b.id(), b);
    }

    public static synchronized void unregisterAll() {
        REG.clear();
        sSelected = NONE;
    }

    public static synchronized List<ExecBackend> all() {
        return Collections.unmodifiableList(new ArrayList<ExecBackend>(REG.values()));
    }

    public static synchronized ExecBackend get(String id) {
        return id == null ? null : REG.get(id);
    }

    /** 选择后端；未注册的 id 当作不选。 */
    public static synchronized void select(String id) {
        sSelected = id != null && REG.containsKey(id) ? id : NONE;
    }

    public static String selected() {
        return sSelected;
    }

    /** 选中且此刻可用的后端；否则 null。 */
    public static ExecBackend ready() {
        ExecBackend b = get(sSelected);
        return b != null && b.available() == ExecBackend.Tri.YES ? b : null;
    }

    /** 特权动作为什么不能用（给界面）；能用返回 null。 */
    public static String unavailableReason() {
        ExecBackend b = get(sSelected);
        if (b == null) return "未选择执行后端（设置 › 设备能力 › 执行后端：root 或 Shizuku）";
        ExecBackend.Tri t = b.available();
        if (t == ExecBackend.Tri.YES) return null;
        String r = b.reason();
        return b.title() + (t == ExecBackend.Tri.NO ? " 不可用" : " 状态未确认") + (r == null || r.length() == 0 ? "" : "：" + r);
    }
}
