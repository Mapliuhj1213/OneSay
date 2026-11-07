package com.onesay.assistant.v2.entry;

import com.onesay.plugin.api.entry.EntryEnv;
import com.onesay.plugin.api.entry.EntryRegistry;
import com.onesay.plugin.api.entry.EntryTrigger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * OneSay 内置的唤起入口（纯 Java，JVM 可测）。Android 侧的组件（快捷开关、小部件、快捷方式、公开 Intent）
 * 只负责收到点击后调 {@code EntryRegistry.fire(id, mode)}；开关、可用性都在这里声明。
 *
 * <p>默认开关：会开麦且由用户自己摆出来的入口（快捷开关 / 小部件 / 快捷方式 / 默认助理）默认开 ——
 * 用户不去添加就不会出现；任何 App 都能发的公开 Intent、常驻监听的唤醒词、厂商按键劫持默认关。
 */
public final class BuiltinEntries {
    private BuiltinEntries() {}

    public static final String ASSISTANT = "entry.assistant";
    public static final String QS_TILE = "entry.qs_tile";
    public static final String WIDGET = "entry.widget";
    public static final String SHORTCUT = "entry.shortcut";
    public static final String WAKE_WORD = "entry.wake_word";
    public static final String INTENT = "entry.intent";

    /** 公开 Intent：{@code am start -a com.onesay.assistant.action.SUMMON}（调用方需申请危险级权限，且用户在设置里打开）。 */
    public static final String ACTION_SUMMON = "com.onesay.assistant.action.SUMMON";
    public static final String PERMISSION_SUMMON = "com.onesay.assistant.permission.SUMMON";
    /** 可选 extra：{@code "voice"}（默认）或 {@code "text"}。 */
    public static final String EXTRA_MODE = "com.onesay.assistant.extra.MODE";

    /** SYSTEM 入口打开 / 关闭时，宿主据此启停 Manifest 组件（JVM 单测里为 null）。 */
    public interface ComponentSwitch {
        void setComponentEnabled(String entryId, boolean on);
    }

    /** 通用实现：按 API 级别与能力声明判断可用性。 */
    public static class Simple implements EntryTrigger {
        final String id, label, desc;
        final Kind kind;
        final boolean def;
        final int minSdk;
        final List<String> caps;
        final ComponentSwitch sw;

        public Simple(String id, String label, String desc, Kind kind, boolean def, int minSdk, String[] caps, ComponentSwitch sw) {
            this.id = id; this.label = label; this.desc = desc; this.kind = kind; this.def = def; this.minSdk = minSdk;
            this.caps = caps == null ? Collections.<String>emptyList() : Collections.unmodifiableList(Arrays.asList(caps));
            this.sw = sw;
        }

        @Override public String id() { return id; }
        @Override public String label() { return label; }
        @Override public String description() { return desc; }
        @Override public Kind kind() { return kind; }
        @Override public boolean defaultEnabled() { return def; }
        public List<String> requires() { return caps; }

        @Override public Tri isAvailable(EntryEnv env) {
            if (env == null) return Tri.UNKNOWN;
            if (env.sdkInt() < minSdk) return Tri.NO;
            Tri out = Tri.YES;
            for (String c : caps) {
                Tri t = env.cap(c);
                if (t == Tri.NO) return Tri.NO;
                if (t != Tri.YES) out = Tri.UNKNOWN;
            }
            return out;
        }

        @Override public String unavailableReason(EntryEnv env) {
            if (env == null) return "未确认";
            if (env.sdkInt() < minSdk) return "需要 Android API " + minSdk + " 及以上";
            List<String> miss = new ArrayList<String>(), unk = new ArrayList<String>();
            for (String c : caps) {
                Tri t = env.cap(c);
                if (t == Tri.NO) miss.add(c); else if (t != Tri.YES) unk.add(c);
            }
            if (!miss.isEmpty()) return "缺少：" + join(miss);
            if (!unk.isEmpty()) return "未确认：" + join(unk);
            return null;
        }

        @Override public boolean start(EntryEnv env) {
            if (sw != null) sw.setComponentEnabled(id, true);
            return true;
        }

        @Override public void stop(EntryEnv env) {
            if (sw != null) sw.setComponentEnabled(id, false);
        }
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append("、"); sb.append(s); }
        return sb.toString();
    }

    /** 内置入口（不含厂商入口）。顺序即设置页顺序。 */
    public static List<EntryTrigger> create(ComponentSwitch sw) {
        List<EntryTrigger> l = new ArrayList<EntryTrigger>();
        l.add(new Simple(ASSISTANT, "默认数字助理", "长按 Home / 电源键或助理手势唤起；先在系统「默认应用 › 数字助理」里选 OneSay",
                EntryTrigger.Kind.SYSTEM, true, 23, null, null));
        l.add(new Simple(QS_TILE, "快捷开关", "下拉通知栏 › 编辑，把「OneSay」拖进快捷开关；点一下开始聆听",
                EntryTrigger.Kind.SYSTEM, true, 24, null, sw));
        l.add(new Simple(WIDGET, "桌面小部件", "长按桌面 › 小部件 › OneSay；点一下开始聆听",
                EntryTrigger.Kind.SYSTEM, true, 21, null, sw));
        l.add(new Simple(SHORTCUT, "桌面快捷方式", "长按 OneSay 图标 › 「语音唤起」（可拖到桌面）",
                EntryTrigger.Kind.SYSTEM, true, 25, null, sw));
        l.add(new Simple(WAKE_WORD, "本地唤醒词", "常驻监听你自己填写的唤醒词（需另行下载模型，见 README）",
                EntryTrigger.Kind.LISTENER, false, 24, new String[]{"MIC", "ARM64"}, null));
        l.add(new Simple(INTENT, "外部 App 唤起（公开 Intent）",
                "允许其他 App 用 " + ACTION_SUMMON + " 唤起 OneSay；对方还必须申请「" + PERMISSION_SUMMON + "」并经你授权",
                EntryTrigger.Kind.SYSTEM, false, 23, null, sw));
        return l;
    }

    /** 注册全部内置入口。 */
    public static void registerAll(ComponentSwitch sw) {
        for (EntryTrigger t : create(sw)) EntryRegistry.register(t);
    }

    /** 公开 Intent 的 mode extra → Mode（未知值按语音）。 */
    public static EntryTrigger.Mode parseMode(String s) {
        return s != null && s.trim().equalsIgnoreCase("text") ? EntryTrigger.Mode.TEXT : EntryTrigger.Mode.VOICE;
    }
}
