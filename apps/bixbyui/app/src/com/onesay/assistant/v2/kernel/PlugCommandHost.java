package com.onesay.assistant.v2.kernel;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * ★ int/1102：{@code --es plug …} 统一入口的宿主侧（TestRunReceiver 调）。分派规则见 {@link PlugCommand}。
 * mika 族（mika.* + skillpack*）与 suggest 族共用 MikaPlugs.debugCommand（它内部再把 suggest 拆给 SuggestPlugs）；
 * richout 族交给 RichOutHost.debugCommand。返回 [分派层错误, mika/suggest 结果+状态, richout 结果+状态]，
 * 调用方按原 tag（[PLUG] / [MIKA-PLUG] / [RICHOUT-PLUG]）打日志，旧的验收 grep 不受影响。
 */
public final class PlugCommandHost {
    private PlugCommandHost() {
    }

    public static final String MIKA = "mika", SUGGEST = "suggest", RICHOUT = "richout";

    public static List<PlugCommand.Family> families() {
        List<PlugCommand.Family> f = new ArrayList<PlugCommand.Family>();
        f.add(new PlugCommand.Family(MIKA, com.onesay.assistant.v2.mikaplug.MikaPlugs.REG, false));
        f.add(new PlugCommand.Family(SUGGEST, com.onesay.assistant.v2.suggest.SuggestPlugs.REG, false));
        f.add(new PlugCommand.Family(RICHOUT, com.onesay.assistant.plug.richout.core.RichOutPlugs.registry(), true));
        return f;
    }

    /** mika + suggest 两段拼成一条交给 MikaPlugs（all=… 两族各带了一份，去重成一份，MikaPlugs 内部会再发给两族）。纯函数。 */
    public static String mikaSuggestPart(PlugCommand.Routed r) {
        LinkedHashSet<String> items = new LinkedHashSet<String>();
        for (String fam : new String[]{MIKA, SUGGEST}) {
            String p = r.part(fam);
            if (p.length() > 0) items.addAll(Arrays.asList(p.split(",")));
        }
        StringBuilder b = new StringBuilder();
        for (String s : items) b.append(b.length() > 0 ? "," : "").append(s);
        return b.toString();
    }

    public static String[] debug(Context ctx, String arg) {
        PlugCommand.Routed r = PlugCommand.route(arg, families());
        StringBuilder err = new StringBuilder();
        for (String e : r.errors) err.append(e).append('\n');
        String mika = com.onesay.assistant.v2.mikaplug.MikaPlugs.debugCommand(ctx, mikaSuggestPart(r));
        String rich = com.onesay.assistant.plug.richout.host.RichOutHost.debugCommand(ctx, r.part(RICHOUT));
        return new String[]{err.toString(), mika, rich};
    }
}
