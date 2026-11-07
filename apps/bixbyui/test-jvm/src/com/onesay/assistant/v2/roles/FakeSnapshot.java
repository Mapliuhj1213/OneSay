package com.onesay.assistant.v2.roles;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** [roles] 假设备快照（代替 PackageManager / 注册表）。 */
public final class FakeSnapshot implements DeviceSnapshot, InstallLinkPlanner.Env {

    public final Set<String> installed = new HashSet<String>();
    public final Map<String, String> labels = new HashMap<String, String>();
    /** probe.toString() 或 probe 的 data/category → 解析到的包。 */
    public final Map<String, List<String>> intents = new HashMap<String, List<String>>();
    public final List<MinedIface> mined = new ArrayList<MinedIface>();
    public final Set<String> caps = new HashSet<String>();
    public final Set<String> resolvable = new HashSet<String>();

    public FakeSnapshot install(String... pkgs) {
        for (String p : pkgs) installed.add(p);
        return this;
    }

    public FakeSnapshot uninstall(String pkg) {
        installed.remove(pkg);
        return this;
    }

    public FakeSnapshot handles(String probeKey, String pkg, String label) {
        List<String> l = intents.get(probeKey);
        if (l == null) intents.put(probeKey, l = new ArrayList<String>());
        l.add(pkg);
        labels.put(pkg, label);
        return this;
    }

    @Override public boolean isInstalled(String pkg) {
        return installed.contains(pkg);
    }

    @Override public String label(String pkg) {
        return labels.get(pkg);
    }

    @Override public List<String> resolve(RoleCatalog.Probe probe) {
        List<String> out = new ArrayList<String>();
        if (probe == null) return out;
        String k = probe.data != null ? probe.data : probe.category;
        List<String> l = intents.get(k);
        if (l != null) out.addAll(l);
        return out;
    }

    @Override public List<MinedIface> mined() {
        return mined;
    }

    @Override public boolean hasCapability(String sign) {
        return caps.contains(sign);
    }

    @Override public boolean canResolve(String uri) {
        return resolvable.contains(uri);
    }

    /** 生产资产（app/assets/app_roles.json）。 */
    public static RoleCatalog assetCatalog() {
        String root = System.getProperty("bixby.root", ".");
        RoleCatalog c = RoleCatalog.fromFile(new File(root, "app/assets/" + RoleCatalog.ASSET));
        if (c == null) throw new IllegalStateException("找不到 app_roles.json（bixby.root=" + root + "）");
        return c;
    }

    public static String assetJson() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        java.io.FileInputStream in = new java.io.FileInputStream(new File(root, "app/assets/" + RoleCatalog.ASSET));
        try {
            return RoleCatalog.readAll(in);
        } finally {
            in.close();
        }
    }
}
