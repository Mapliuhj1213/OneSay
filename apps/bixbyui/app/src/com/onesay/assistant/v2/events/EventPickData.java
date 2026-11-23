package com.onesay.assistant.v2.events;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * [fix-1123-c] 新建事件任务时可选的 Wi‑Fi 名（已保存的 + 现在连着的 + 附近扫到的）和蓝牙设备名（已配对的）。
 * 读不到（没权限、系统不给、开关关着）就返回空表，界面上还有「任意」和「自己填」。只读名字，不连接、不扫描。
 */
final class EventPickData {
    private EventPickData() {}

    @SuppressWarnings("deprecation")
    static List<String> wifiNames(Context c) {
        List<String> out = new ArrayList<String>();
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return out;
            try {
                android.net.wifi.WifiInfo now = wm.getConnectionInfo();
                if (now != null && now.getSSID() != null) out.add(now.getSSID());
            } catch (Throwable ignored) { }
            try {
                List<android.net.wifi.WifiConfiguration> saved = wm.getConfiguredNetworks(); // Android 10+ 普通应用拿到的是空表
                if (saved != null) for (android.net.wifi.WifiConfiguration w : saved) if (w != null && w.SSID != null) out.add(w.SSID);
            } catch (Throwable ignored) { }
            try {
                List<android.net.wifi.ScanResult> near = wm.getScanResults(); // 上次系统扫描的结果（要定位权限，没有就是空表）
                if (near != null) for (android.net.wifi.ScanResult r : near) if (r != null && r.SSID != null && out.size() < 200) out.add(r.SSID);
            } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
        return out;
    }

    /** ★ 1123d B34：有定位权限且定位开着（Android 10 起读 Wi‑Fi 名的前提）。读不到当没有。 */
    static boolean locationOk(Context c) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23
                    && c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                return lm == null || lm.isLocationEnabled();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ★ 1123e：定位权限（只看权限，不看开关）。 */
    static boolean locationPerm(Context c) {
        try {
            return android.os.Build.VERSION.SDK_INT < 23
                    || c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ★ 1123e：定位总开关（Android 9 起能直接问；更早当开着）。 */
    static boolean locationOn(Context c) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                return lm == null || lm.isLocationEnabled();
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** ★ 1123e：这个权限之前在这里弹过系统框没有（弹过还没给 = 用户拒了，改引导去设置）。 */
    static boolean asked(Context c, String perm) {
        try {
            return c.getSharedPreferences("event_pick", Context.MODE_PRIVATE).getBoolean("asked_" + perm, false);
        } catch (Throwable t) {
            return false;
        }
    }

    static void markAsked(Context c, String perm) {
        try {
            c.getSharedPreferences("event_pick", Context.MODE_PRIVATE).edit().putBoolean("asked_" + perm, true).apply();
        } catch (Throwable ignored) { }
    }

    /** ★ 1123d B35：蓝牙开着没。读不到当开着（只影响提示用词）。 */
    static boolean bluetoothOn() {
        try {
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            return a == null || a.isEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** ★ 1123d B35：Android 12 以上有没有「附近的设备」（BLUETOOTH_CONNECT）权限；低版本恒为 true。 */
    static boolean bluetoothConnectOk(Context c) {
        try {
            boolean granted = android.os.Build.VERSION.SDK_INT < EventPickHint.SDK_BT_CONNECT
                    || c.checkSelfPermission(EventPickHint.PERM_BT_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            return !EventPickHint.needBtConnect(android.os.Build.VERSION.SDK_INT, granted);
        } catch (Throwable t) {
            return true;
        }
    }

    static List<String> bluetoothNames(Context c) {
        List<String> out = new ArrayList<String>();
        try {
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a == null) return out;
            java.util.Set<android.bluetooth.BluetoothDevice> b = a.getBondedDevices();
            if (b != null) for (android.bluetooth.BluetoothDevice d : b) {
                String n = d.getName();
                out.add(n != null && n.trim().length() > 0 ? n : d.getAddress());
            }
        } catch (Throwable ignored) { }
        return out;
    }
}
