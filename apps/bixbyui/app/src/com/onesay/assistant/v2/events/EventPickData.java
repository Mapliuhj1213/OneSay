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
