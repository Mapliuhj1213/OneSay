/*
 * [插件规范 · 公共端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，宿主实现窄接口把插件接进来。
 * 规范见 fix-20261025/mika-plug/PLUGIN-SPEC.md（与 phongent 路 PHONGENT-PORT.md §插件规范对齐）。
 */
package com.onesay.assistant.plug.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 一个插件的身份证：id（小写点分，如 {@code mika.screen}）、版本、依赖、出厂默认、给人看的名字和一句话说明、
 * 移植来源（照抄的上游文件，许可审计用）。不可变。
 */
public final class PlugSpec {
    public final String id;
    public final int version;
    public final String title;
    public final String summary;
    public final boolean defaultOn;
    public final List<String> deps;
    /** 照抄 / 改写的上游文件（相对上游仓库根）；没有为空表。 */
    public final List<String> ported;

    public PlugSpec(String id, int version, String title, String summary, boolean defaultOn, String[] deps, String[] ported) {
        if (id == null || !id.matches("[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)*")) throw new IllegalArgumentException("bad plug id: " + id);
        this.id = id;
        this.version = version;
        this.title = title == null ? id : title;
        this.summary = summary == null ? "" : summary;
        this.defaultOn = defaultOn;
        this.deps = deps == null ? Collections.<String>emptyList() : Collections.unmodifiableList(Arrays.asList(deps.clone()));
        this.ported = ported == null ? Collections.<String>emptyList() : Collections.unmodifiableList(Arrays.asList(ported.clone()));
    }

    @Override public String toString() {
        return id + "@" + version + (defaultOn ? " [默认开]" : " [默认关]") + (deps.isEmpty() ? "" : " deps=" + deps);
    }
}
