package com.onesay.assistant.v2.ui.wake;

import android.graphics.Region;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * ★ LC-1：给一个窗口设「可触摸区域」（touchable region），窗口尺寸不变。
 *
 * <p>机制：{@code ViewTreeObserver.OnComputeInternalInsetsListener}（@hide，Android 4.x 起就有；输入法、状态栏、
 * PopupWindow 的浮动工具条都靠它）。每次 ViewRootImpl traversal 时回调，把 {@code InternalInsetsInfo.touchableRegion}
 * 填成卡片矩形并 {@code setTouchableInsets(TOUCHABLE_INSETS_REGION)}；ViewRootImpl 只在值变了时才经 oneway 的
 * {@code IWindowSession.setInsets} 告诉 WMS。WMS 的 InputMonitor 对没有 AppToken 的窗口（overlay）直接用
 * {@code WindowState.getTouchableRegion()}，InputDispatcher 对 FLAG_NOT_TOUCH_MODAL 窗口只在 region 内才算命中 ——
 * 区域外的 DOWN 落到后面的窗口，本窗口（FLAG_WATCH_OUTSIDE_TOUCH）照旧收到 ACTION_OUTSIDE。
 *
 * <p>Android 8.0 没有隐藏 API 限制，直接反射。反射失败 → {@link #supported()} = false，调用方退回 wrap 窗（触摸行为已知正确）。
 */
public final class TouchRegionCompat {

    private TouchRegionCompat() {}

    private static final String TAG = "WakeUI";

    /** 每次 traversal 问一次：true = 只有 out 可触摸；false = 整窗可触摸。主线程调用，要便宜。 */
    public interface Source {
        boolean touchRegion(Region out);
    }

    private static volatile Boolean sSupported;
    private static Class<?> sListenerCls;
    private static Method sAdd, sRemove, sSetInsets;
    private static Field sRegion;
    private static int sInsetsRegion = 3, sInsetsFrame = 0;

    public static boolean supported() {
        Boolean s = sSupported;
        if (s != null) return s;
        synchronized (TouchRegionCompat.class) {
            if (sSupported != null) return sSupported;
            boolean ok;
            try {
                sListenerCls = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
                Class<?> info = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
                sAdd = ViewTreeObserver.class.getMethod("addOnComputeInternalInsetsListener", sListenerCls);
                sRemove = ViewTreeObserver.class.getMethod("removeOnComputeInternalInsetsListener", sListenerCls);
                sSetInsets = info.getMethod("setTouchableInsets", int.class);
                sRegion = info.getField("touchableRegion");
                try { sInsetsRegion = info.getField("TOUCHABLE_INSETS_REGION").getInt(null); } catch (Throwable ignore) {}
                try { sInsetsFrame = info.getField("TOUCHABLE_INSETS_FRAME").getInt(null); } catch (Throwable ignore) {}
                ok = true;
            } catch (Throwable t) {
                Log.w(TAG, "[LC-1] touchable region 不可用，退回 wrap 窗: " + t);
                ok = false;
            }
            sSupported = ok;
            return ok;
        }
    }

    /** 挂到 v 所在窗口（v 必须已 attach）；返回令牌给 {@link #uninstall}。不支持 / 失败返回 null。 */
    public static Object install(View v, final Source src) {
        if (v == null || src == null || !supported()) return null;
        try {
            final Region tmp = new Region();
            Object listener = Proxy.newProxyInstance(sListenerCls.getClassLoader(), new Class<?>[] {sListenerCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                            String n = m.getName();
                            if ("onComputeInternalInsets".equals(n) && args != null && args.length == 1) {
                                apply(args[0], src, tmp);
                                return null;
                            }
                            if ("equals".equals(n)) return args != null && args.length == 1 && proxy == args[0];
                            if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                            if ("toString".equals(n)) return "TouchRegionCompat@" + Integer.toHexString(System.identityHashCode(proxy));
                            return null;
                        }
                    });
            sAdd.invoke(v.getViewTreeObserver(), listener);
            return listener;
        } catch (Throwable t) {
            Log.w(TAG, "[LC-1] 挂 touchable region 失败: " + t);
            return null;
        }
    }

    public static void uninstall(View v, Object token) {
        if (v == null || token == null || !supported()) return;
        try {
            sRemove.invoke(v.getViewTreeObserver(), token);
        } catch (Throwable ignore) {
        }
    }

    static void apply(Object info, Source src, Region tmp) {
        try {
            tmp.setEmpty();
            if (src.touchRegion(tmp) && !tmp.isEmpty()) {
                ((Region) sRegion.get(info)).set(tmp);
                sSetInsets.invoke(info, sInsetsRegion);
            } else {
                sSetInsets.invoke(info, sInsetsFrame);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[LC-1] 计算 touchable region 异常: " + t);
        }
    }
}
