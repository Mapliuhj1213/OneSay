package com.onesay.assistant.v2.roles;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * [roles] 安装链（2026-10-10 去应用宝，按国内 / 国外分流）：每条分支一个用例。
 * cn → 三星商店 / market://；intl → 通则 Play，不通 + 无 VPN → 自动代理 App复测，不通 + 已有 VPN → 只给芯片，
 * 代理 App没装 / 复测仍不通 → 退三星商店并说明。
 */
public class InstallLinkPlannerTest {
    @org.junit.Before public void vpnApp() { InstallLinkPlanner.setVpnPackage("com.example.vpn"); }
    @org.junit.After public void noVpnApp() { InstallLinkPlanner.setVpnPackage(""); }


    static final String DIDI = "com.sdu.didi.psnger";
    static final String UBER = "com.ubercab";
    static final String GALAXY_URI_DIDI = "samsungapps://ProductDetail/com.sdu.didi.psnger";
    static final String GALAXY_URI_UBER = "samsungapps://ProductDetail/com.ubercab";
    static final String MARKET_UBER = "market://details?id=com.ubercab";

    /** 脚本化假环境：connectivity 按队列依次返回（队列空 = 最后一个值），时钟由 sleepMs 推进。 */
    static final class Net implements InstallLinkPlanner.NetEnv {
        final Set<String> installed = new HashSet<String>();
        final Set<String> resolvable = new HashSet<String>();
        final LinkedList<Boolean> conn = new LinkedList<Boolean>();
        boolean lastConn = false;
        Boolean vpn = Boolean.FALSE; // null = 抛异常
        boolean launchOk = true;
        long now = 0;
        int probes = 0;
        final List<String> launched = new ArrayList<String>();

        Net install(String... p) {
            for (String s : p) installed.add(s);
            return this;
        }

        Net conn(Boolean... seq) {
            for (Boolean b : seq) conn.add(b);
            return this;
        }

        @Override public boolean isInstalled(String pkg) {
            return installed.contains(pkg);
        }

        @Override public boolean canResolve(String uri) {
            return resolvable.contains(uri);
        }

        @Override public boolean connectivityOk() {
            probes++;
            now += 200; // 一次检查本身也要时间
            if (!conn.isEmpty()) lastConn = conn.removeFirst();
            return lastConn;
        }

        @Override public boolean vpnActive() {
            if (vpn == null) throw new IllegalStateException("x");
            return vpn;
        }

        @Override public boolean launchApp(String pkg) {
            launched.add(pkg);
            return launchOk;
        }

        @Override public long nowMs() {
            return now;
        }

        @Override public void sleepMs(long ms) {
            now += ms;
        }
    }

    static Net phone() {
        return new Net().install(InstallLinkPlanner.GALAXY_PKG, InstallLinkPlanner.PLAY_PKG, "com.example.vpn");
    }

    // ---------------------------------------------------------------- 国内

    @Test public void cnGalaxy() {
        Net n = phone();
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(DIDI, InstallLinkPlanner.Region.CN, n);
        assertEquals(InstallLinkPlanner.Step.OPEN, d.step);
        assertEquals(InstallLinkPlanner.Kind.GALAXY, d.link.kind);
        assertEquals(GALAXY_URI_DIDI, d.link.uri);
        assertEquals(InstallLinkPlanner.GALAXY_PKG, d.link.viaPkg);
        assertNull(d.note);
        assertEquals("国内不联网", 0, n.probes);
        assertTrue("国内绝不启动代理 App", n.launched.isEmpty());
    }

    @Test public void cnNoGalaxyMarket() {
        Net n = new Net();
        n.resolvable.add("market://details?id=" + DIDI);
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(DIDI, InstallLinkPlanner.Region.CN, n);
        assertEquals(InstallLinkPlanner.Kind.MARKET, d.link.kind);
        assertEquals("market://details?id=" + DIDI, d.link.uri);
        assertNull(d.link.viaPkg);
        assertNull(d.note);
    }

    @Test public void cnNoStoreAtAll() {
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(DIDI, InstallLinkPlanner.Region.CN, new Net());
        assertEquals(InstallLinkPlanner.Kind.NONE, d.link.kind);
        assertEquals(InstallLinkPlanner.NOTE_NO_STORE, d.note);
    }

    @Test public void noYingyongbaoAnyMore() {
        Net n = phone().install("com.tencent.android.qqdownloader");
        for (InstallLinkPlanner.Region r : InstallLinkPlanner.Region.values()) {
            n.conn.clear();
            n.lastConn = true;
            InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(DIDI, r, n);
            assertFalse(d.link.uri.startsWith("tmast:"));
            assertFalse(d.link.uri.contains("a.app.qq.com"));
            assertFalse("com.tencent.android.qqdownloader".equals(d.link.viaPkg));
        }
    }

    // ---------------------------------------------------------------- 国外：通

    @Test public void intlOkPlay() {
        Net n = phone().conn(true);
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n);
        assertEquals(InstallLinkPlanner.Step.OPEN, d.step);
        assertEquals(InstallLinkPlanner.Kind.PLAY, d.link.kind);
        assertEquals(MARKET_UBER, d.link.uri);
        assertEquals(InstallLinkPlanner.PLAY_PKG, d.link.viaPkg);
        assertNull(d.note);
        assertEquals(1, n.probes);
        assertTrue(n.launched.isEmpty());
    }

    @Test public void intlOkNoPlayPlainMarket() {
        Net n = new Net().install(InstallLinkPlanner.GALAXY_PKG).conn(true);
        n.resolvable.add(MARKET_UBER);
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n);
        assertEquals(InstallLinkPlanner.Kind.MARKET, d.link.kind);
        assertNull(d.link.viaPkg);
    }

    // ---------------------------------------------------------------- 国外：不通 + 没有 VPN → 自动代理 App

    @Test public void intlFailNoVpnBaicaiyunConnects() {
        Net n = phone().conn(false, false, false, true);
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n);
        assertEquals(InstallLinkPlanner.Step.OPEN, d.step);
        assertEquals(InstallLinkPlanner.Kind.PLAY, d.link.kind);
        assertNull(d.note);
        assertEquals(java.util.Collections.singletonList("com.example.vpn"), n.launched);
        assertEquals("首测 + 3 次复测", 4, n.probes);
    }

    @Test public void intlFailNoVpnBaicaiyunNeverConnects() {
        Net n = phone().conn(false);
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n);
        assertEquals(InstallLinkPlanner.Step.OPEN, d.step);
        assertEquals(InstallLinkPlanner.Kind.GALAXY, d.link.kind);
        assertEquals(GALAXY_URI_UBER, d.link.uri);
        assertEquals(InstallLinkPlanner.NOTE_FALLBACK, d.note);
        assertEquals(1, n.launched.size());
        assertTrue("最多约 10 秒", n.now <= InstallLinkPlanner.POLL_MAX_MS + InstallLinkPlanner.POLL_INTERVAL_MS + 400);
        assertTrue("至少复测到 10 秒", n.now >= InstallLinkPlanner.POLL_MAX_MS);
    }

    @Test public void intlFailNoVpnNoBaicaiyun() {
        Net n = new Net().install(InstallLinkPlanner.GALAXY_PKG, InstallLinkPlanner.PLAY_PKG).conn(false);
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n);
        assertEquals(InstallLinkPlanner.Kind.GALAXY, d.link.kind);
        assertEquals(InstallLinkPlanner.NOTE_FALLBACK, d.note);
        assertTrue(n.launched.isEmpty());
    }

    @Test public void intlFailNoVpnBaicaiyunWontLaunch() {
        Net n = phone().conn(false);
        n.launchOk = false;
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n);
        assertEquals(InstallLinkPlanner.Kind.GALAXY, d.link.kind);
        assertEquals(InstallLinkPlanner.NOTE_FALLBACK, d.note);
        assertEquals("启动失败不复测", 1, n.probes);
    }

    @Test public void intlFailFallbackNoGalaxy() {
        Net n = new Net().install(InstallLinkPlanner.PLAY_PKG).conn(false);
        n.resolvable.add(MARKET_UBER);
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n);
        assertEquals(InstallLinkPlanner.Kind.MARKET, d.link.kind);
        assertNull("退路不指定 Play（它连不上）", d.link.viaPkg);
        assertEquals(InstallLinkPlanner.NOTE_FALLBACK_MARKET, d.note);
    }

    // ---------------------------------------------------------------- 国外：不通 + 已有 VPN（Tailscale）→ 只给芯片

    @Test public void intlFailVpnActiveOffersChipNeverLaunches() {
        Net n = phone().conn(false);
        n.vpn = Boolean.TRUE;
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n);
        assertEquals(InstallLinkPlanner.Step.OFFER_VPN_CHIP, d.step);
        assertEquals("芯片旁的备选 = 三星商店", InstallLinkPlanner.Kind.GALAXY, d.link.kind);
        assertEquals(InstallLinkPlanner.NOTE_VPN_BUSY, d.note);
        assertTrue("已有 VPN 绝不自动启动代理 App", n.launched.isEmpty());
        assertEquals(1, n.probes);
        assertEquals("打开代理 App（会断开当前 VPN）", InstallLinkPlanner.VPN_CHIP);
    }

    @Test public void intlVpnUnknownTreatedAsActive() {
        Net n = phone().conn(false);
        n.vpn = null; // 查询异常
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n);
        assertEquals(InstallLinkPlanner.Step.OFFER_VPN_CHIP, d.step);
        assertTrue(n.launched.isEmpty());
    }

    @Test public void intlFailVpnActiveNoBaicaiyunFallsBack() {
        Net n = new Net().install(InstallLinkPlanner.GALAXY_PKG, InstallLinkPlanner.PLAY_PKG).conn(false);
        n.vpn = Boolean.TRUE;
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n);
        assertEquals("没装代理 App就不给芯片", InstallLinkPlanner.Step.OPEN, d.step);
        assertEquals(InstallLinkPlanner.Kind.GALAXY, d.link.kind);
        assertEquals(InstallLinkPlanner.NOTE_FALLBACK, d.note);
    }

    @Test public void intlOkWithVpnStillPlay() {
        Net n = phone().conn(true);
        n.vpn = Boolean.TRUE;
        assertEquals(InstallLinkPlanner.Kind.PLAY, InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, n).link.kind);
        assertTrue(n.launched.isEmpty());
    }

    @Test public void chipTapLaunchesThenPlay() {
        Net n = phone().conn(false, true);
        InstallLinkPlanner.Decision d = InstallLinkPlanner.afterVpnChip(UBER, n);
        assertEquals(InstallLinkPlanner.Kind.PLAY, d.link.kind);
        assertEquals(1, n.launched.size());
    }

    @Test public void chipTapStillNoNetFallsBack() {
        Net n = phone().conn(false);
        InstallLinkPlanner.Decision d = InstallLinkPlanner.afterVpnChip(UBER, n);
        assertEquals(InstallLinkPlanner.Kind.GALAXY, d.link.kind);
        assertEquals(InstallLinkPlanner.NOTE_FALLBACK, d.note);
    }

    // ---------------------------------------------------------------- 输入校验 / 区域

    @Test public void invalidPackage() {
        Net n = phone().conn(false);
        for (String bad : new String[] {null, "", "x; rm -rf /", "com.a&b=c", "com.a/../b"}) {
            assertNull(InstallLinkPlanner.decide(bad, InstallLinkPlanner.Region.INTL, n));
            assertNull(InstallLinkPlanner.decide(bad, InstallLinkPlanner.Region.CN, n));
            assertNull(InstallLinkPlanner.afterVpnChip(bad, n));
            assertNull(InstallLinkPlanner.galaxyOrMarket(bad, n));
            assertNull(InstallLinkPlanner.play(bad, n));
        }
        assertTrue("非法包名不联网、不启动", n.probes == 0 && n.launched.isEmpty());
    }

    @Test public void nullEnv() {
        assertEquals(InstallLinkPlanner.Kind.NONE, InstallLinkPlanner.decide(DIDI, InstallLinkPlanner.Region.CN, null).link.kind);
        InstallLinkPlanner.Decision d = InstallLinkPlanner.decide(UBER, InstallLinkPlanner.Region.INTL, null);
        assertEquals(InstallLinkPlanner.Step.OPEN, d.step);
        assertEquals(InstallLinkPlanner.Kind.NONE, d.link.kind);
    }

    @Test public void regionParse() {
        assertEquals(InstallLinkPlanner.Region.INTL, InstallLinkPlanner.Region.parse("intl"));
        assertEquals(InstallLinkPlanner.Region.INTL, InstallLinkPlanner.Region.parse(" INTL "));
        assertEquals(InstallLinkPlanner.Region.CN, InstallLinkPlanner.Region.parse("cn"));
        assertEquals(InstallLinkPlanner.Region.CN, InstallLinkPlanner.Region.parse(null));
        assertEquals(InstallLinkPlanner.Region.CN, InstallLinkPlanner.Region.parse("abroad"));
    }

    @Test public void regionFromAssetTable() {
        RoleCatalog c = FakeSnapshot.assetCatalog();
        assertEquals(InstallLinkPlanner.Region.INTL, InstallLinkPlanner.regionOf(c, "com.ubercab"));
        assertEquals(InstallLinkPlanner.Region.INTL, InstallLinkPlanner.regionOf(c, "com.spotify.music"));
        assertEquals(InstallLinkPlanner.Region.INTL, InstallLinkPlanner.regionOf(c, "com.google.android.apps.maps"));
        assertEquals(InstallLinkPlanner.Region.INTL, InstallLinkPlanner.regionOf(c, "com.google.android.apps.youtube.music"));
        for (String cn : new String[] {DIDI, "com.sankuai.meituan", "com.autonavi.minimap", "com.jingdong.app.mall", "com.netease.cloudmusic"}) {
            assertEquals(cn, InstallLinkPlanner.Region.CN, InstallLinkPlanner.regionOf(c, cn));
        }
        assertEquals("表外包 → cn", InstallLinkPlanner.Region.CN, InstallLinkPlanner.regionOf(c, "com.example.unknown"));
        assertEquals("目录不可用 → cn", InstallLinkPlanner.Region.CN, InstallLinkPlanner.regionOf(null, "com.ubercab"));
    }

    /** 资产表：每个种子都显式写了 region（只能是 cn / intl），同一个包在多个角色里区域一致。 */
    @Test public void assetEverySeedHasRegion() throws Exception {
        JSONArray roles = new JSONObject(FakeSnapshot.assetJson()).getJSONArray("roles");
        Map<String, String> byPkg = new HashMap<String, String>();
        int n = 0;
        for (int i = 0; i < roles.length(); i++) {
            JSONArray seeds = roles.getJSONObject(i).getJSONArray("seeds");
            for (int j = 0; j < seeds.length(); j++) {
                JSONObject s = seeds.getJSONObject(j);
                String r = s.optString("region", "");
                assertTrue(s.optString("id") + " 缺 region", "cn".equals(r) || "intl".equals(r));
                String prev = byPkg.put(s.getString("pkg"), r);
                assertTrue(s.getString("pkg") + " 区域不一致", prev == null || prev.equals(r));
                n++;
            }
        }
        assertTrue(n >= 20);
    }
}
