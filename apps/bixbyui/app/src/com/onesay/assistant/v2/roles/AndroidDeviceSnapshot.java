package com.onesay.assistant.v2.roles;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [roles] 生产设备快照：PackageManager（是否安装 / 标签 / 标准 intent 解析）+ 能力注册表里的挖掘接口。
 *
 * <p>Android 8 没有包可见性限制（Android 11 才有 {@code <queries>}），所以 {@code getPackageInfo} /
 * {@code queryIntentActivities} 不需要任何新权限。安装状态<b>每次实时查</b>（装完/卸载后立即生效）；
 * 挖掘接口列表 60 秒缓存（注册表只在启动/探针挖到新接口时变）。
 */
public final class AndroidDeviceSnapshot implements DeviceSnapshot, InstallLinkPlanner.Env {

    private static final String TAG = "Roles";
    private static final long MINED_TTL_MS = 60000L;
    private static final Pattern CMD_PKG = Pattern.compile("(?:-n\\s+'?|-p\\s+'?|component=)([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)");

    private final Context mCtx;
    private volatile List<MinedIface> mMined;
    private volatile long mMinedAt;

    public AndroidDeviceSnapshot(Context ctx) {
        this.mCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
    }

    private PackageManager pm() {
        return mCtx.getPackageManager();
    }

    @Override public boolean isInstalled(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        try {
            ApplicationInfo ai = pm().getApplicationInfo(pkg, 0);
            return ai != null && ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] 查询安装状态异常 " + pkg + ": " + t.getMessage());
            return false;
        }
    }

    @Override public String label(String pkg) {
        try {
            ApplicationInfo ai = pm().getApplicationInfo(pkg, 0);
            CharSequence l = pm().getApplicationLabel(ai);
            return l == null ? null : l.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public List<String> resolve(RoleCatalog.Probe probe) {
        List<String> out = new ArrayList<String>();
        if (probe == null) return out;
        try {
            Intent it = new Intent(probe.action);
            if (probe.data != null) it.setData(Uri.parse(probe.data));
            if (probe.category != null) it.addCategory(probe.category);
            Set<String> seen = new LinkedHashSet<String>();
            List<ResolveInfo> l = pm().queryIntentActivities(it, 0);
            if (l != null) {
                for (ResolveInfo ri : l) {
                    if (ri.activityInfo == null) continue;
                    String p = ri.activityInfo.packageName;
                    if (p == null || p.equals(mCtx.getPackageName()) || "android".equals(p)) continue;
                    if (seen.add(p)) out.add(p);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] 标准 intent 解析异常 " + probe + ": " + t.getMessage());
        }
        return out;
    }

    @Override public List<MinedIface> mined() {
        List<MinedIface> m = mMined;
        long now = SystemClock.elapsedRealtime();
        if (m != null && now - mMinedAt < MINED_TTL_MS) return m;
        List<MinedIface> out = new ArrayList<MinedIface>();
        try {
            for (Capability c : CapabilityRegistry.getInstance().listCapabilities()) {
                if (c == null || c.cmdTemplate == null) continue;
                Matcher mm = CMD_PKG.matcher(c.cmdTemplate);
                if (!mm.find()) continue;
                StringBuilder text = new StringBuilder(c.sign).append(' ').append(c.summary == null ? "" : c.summary);
                if (c.keywords != null) for (String k : c.keywords) text.append(' ').append(k);
                out.add(new MinedIface(mm.group(1), c.sign, text.toString()));
            }
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] 读取挖掘接口异常: " + t.getMessage());
        }
        mMined = out;
        mMinedAt = now;
        return out;
    }

    @Override public boolean hasCapability(String sign) {
        try {
            return sign != null && CapabilityRegistry.getInstance().describe(sign) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public boolean canResolve(String uri) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            List<ResolveInfo> l = pm().queryIntentActivities(it, 0);
            return l != null && !l.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
