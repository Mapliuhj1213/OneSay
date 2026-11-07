package com.onesay.assistant.v2.contacts;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.ui.ActionChipsView;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;
import com.onesay.assistant.v2.wake.WakeService;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「打电话给妈妈」「给老爸发短信说…」——目标不是号码 → 本机通讯录解析 →
 * 一个号码直接进确认卡（phone.call 红卡、sms.send 黄卡，卡上写联系人名 + 号码；永不自动拨号）；
 * 多个 → 芯片选；没有 → 「通讯录里没找到 X」。选定后走 {@link FloatingSheetService#screenRefExecute}（同一套门禁 → 确认卡 → 执行）。
 */
public final class ContactCallHooks {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ContactCallHooks() {}

    /** 主线程，dispatch 过闸前调用。接管了返回 true。 */
    public static boolean intercept(final FloatingSheetService svc, final Capability cap, final Invocation inv, final String command) {
        if (svc == null || cap == null || inv == null) return false;
        if (!ContactMatcher.needsLookup(cap.sign, inv.args)) return false;
        final FloatingSheetView sheet = svc.getSheetView();
        if (sheet == null) return false;
        final String target = inv.args.optString("number", "");
        final boolean sms = "sms.send".equals(cap.sign);
        sheet.updateState("正在查通讯录", Color.parseColor("#5E5CE6"), null);
        ContactSource.installFor(svc);
        com.onesay.assistant.v2.util.Threads.start("contact-lookup", new Runnable() {
            @Override public void run() {
                ContactMatcher.Directory d = ContactMatcher.directory();
                List<ContactMatcher.Contact> book = d == null ? null : d.lookup(ContactMatcher.terms(target));
                final ContactMatcher.Resolution r = book == null ? null : ContactMatcher.resolve(target, book);
                Log.i(ContactSource.TAG, "[CONTACTS] " + cap.sign + " 目标长度=" + target.length() + " → "
                        + (r == null ? "通讯录不可用" : r.kind + " " + r.matches.size()));
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (svc.getSheetView() == null) return;
                        String q = command != null && command.length() > 0 ? command : (sms ? "发短信" : "打电话");
                        if (r == null) {
                            sheet.showAskReply(q, "通讯录暂时读不了，没法查「" + target + "」。可以直接说号码，比如「打电话给 10086」。");
                            sheet.setDismissLabel("✕ 关闭");
                        } else if (r.kind == ContactMatcher.Kind.NONE) {
                            sheet.showAskReply(q, "通讯录里没找到「" + r.target + "」。可以说联系人的全名，或者直接说号码。");
                            sheet.setDismissLabel("✕ 关闭");
                        } else if (r.kind == ContactMatcher.Kind.ONE) {
                            proceed(svc, cap, inv, q, r.single());
                        } else {
                            choose(svc, cap, inv, q, r);
                        }
                    }
                });
            }
        });
        return true;
    }

    private static void proceed(FloatingSheetService svc, Capability cap, Invocation inv, String query, ContactMatcher.Contact c) {
        JSONObject a;
        try {
            a = new JSONObject(inv.args.toString());
        } catch (Exception e) {
            a = new JSONObject();
        }
        ContactMatcher.apply(a, c);
        Log.i(ContactSource.TAG, "[CONTACTS] 选定 " + c + " → 过闸（确认卡）");
        svc.screenRefExecute(cap, a, query);
    }

    private static void choose(final FloatingSheetService svc, final Capability cap, final Invocation inv, final String query,
                               final ContactMatcher.Resolution r) {
        FloatingSheetView sheet = svc.getSheetView();
        sheet.showAskReply(query, "通讯录里「" + r.target + "」有 " + r.matches.size() + " 个号码，点一个：");
        sheet.setDismissLabel("✕ 关闭");
        ActionChipsView v = ensureChips(svc);
        if (v == null) return;
        List<String> labels = new ArrayList<String>();
        for (ContactMatcher.Contact c : r.matches) labels.add(c.label());
        v.setListener(new ActionChipsView.Listener() {
            @Override public void onChipClicked(int index) {
                if (index < 0 || index >= r.matches.size()) return;
                try { WakeService.cancelCaptureFromUi(); } catch (Throwable ignored) {}
                hideChips(svc);
                proceed(svc, cap, inv, query, r.matches.get(index));
            }
        });
        v.showChips(labels);
        Log.i(ContactSource.TAG, "[CONTACTS] 多个匹配 → 芯片 " + r.matches.size() + " 个");
    }

    private static ActionChipsView ensureChips(FloatingSheetService svc) {
        FloatingSheetView sheet = svc.getSheetView();
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) return (ActionChipsView) existing;
        ActionChipsView v = new ActionChipsView(svc);
        int idx = Math.min(2, sheet.getChildCount());
        sheet.addView(v, idx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private static void hideChips(FloatingSheetService svc) {
        FloatingSheetView sheet = svc != null ? svc.getSheetView() : null;
        if (sheet == null) return;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) ((ActionChipsView) existing).hideChips();
    }
}
