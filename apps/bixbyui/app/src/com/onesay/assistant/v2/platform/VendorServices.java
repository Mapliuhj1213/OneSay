package com.onesay.assistant.v2.platform;

/**
 * 厂商专属服务抽象（refactor/oss 2026-11-06）：三星浏览器 / 三星时钟 / 三星商店 / Bixby 键等散落在各处的包名与深链，
 * 收拢成一个按厂商选择的描述对象。纯 Java，JVM 可测。
 *
 * <p>现状：原代码里的三星包名与深链（{@code StoreLinks.GALAXY_PKG}、{@code SamsungBrowser}、{@code IntentExecutor} 的应用别名表…）
 * <b>还没有</b>改为读这里——那属于行为面较大的搬家，按报告分步做。这里先给出各厂商的对照，供移植与能力页使用。
 */
public final class VendorServices {

    /** 厂商 id：samsung / meizu / generic。 */
    public final String vendor;
    /** 系统浏览器包名（可空）。 */
    public final String browserPkg;
    /** 系统时钟包名（可空）。 */
    public final String clockPkg;
    /** 厂商应用商店包名（可空）。 */
    public final String storePkg;
    /** 应用商店搜索深链前缀（拼上 URL 编码后的关键词；可空 = 用 market://search?q=）。 */
    public final String storeSearchPrefix;
    /** 是否可能有 Bixby 键。 */
    public final boolean mayHaveBixbyKey;

    private VendorServices(String vendor, String browserPkg, String clockPkg, String storePkg, String storeSearchPrefix,
                           boolean mayHaveBixbyKey) {
        this.vendor = vendor;
        this.browserPkg = browserPkg;
        this.clockPkg = clockPkg;
        this.storePkg = storePkg;
        this.storeSearchPrefix = storeSearchPrefix;
        this.mayHaveBixbyKey = mayHaveBixbyKey;
    }

    public static final VendorServices SAMSUNG = new VendorServices("samsung", "com.sec.android.app.sbrowser",
            "com.sec.android.app.clockpackage", "com.sec.android.app.samsungapps", "samsungapps://SearchResult/", true);

    /** 魅族（Flyme）：包名按公开资料整理，未在真机核实（移植时第一步用 pm list packages 校对）。 */
    public static final VendorServices MEIZU = new VendorServices("meizu", "com.android.browser",
            "com.android.alarmclock", "com.meizu.mstore", null, false);

    /** 其他 / AOSP：只用标准 Intent（AlarmClock、ACTION_VIEW、market://）。 */
    public static final VendorServices GENERIC = new VendorServices("generic", null, null, null, null, false);

    /** 按 Build.MANUFACTURER / BRAND 选。 */
    public static VendorServices forDevice(String manufacturer, String brand) {
        if (is("samsung", manufacturer, brand)) return SAMSUNG;
        if (is("meizu", manufacturer, brand)) return MEIZU;
        return GENERIC;
    }

    public static VendorServices forProfile(DeviceProfile p) {
        return p == null ? GENERIC : forDevice(p.manufacturer, p.brand);
    }

    /** 商店搜索链接：有厂商深链用厂商的，否则 {@code market://search?q=}。kw 需已 URL 编码。 */
    public String storeSearchUri(String encodedKeyword) {
        String kw = encodedKeyword == null ? "" : encodedKeyword;
        return storeSearchPrefix != null ? storeSearchPrefix + kw : "market://search?q=" + kw;
    }

    private static boolean is(String v, String a, String b) {
        return (a != null && v.equalsIgnoreCase(a.trim())) || (b != null && v.equalsIgnoreCase(b.trim()));
    }
}
