package com.onesay.assistant.v2.conn.port;

import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.mail.MailService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A↔B 合并适配器：A 线 {@link MailReadPort} 接到 B 线 {@link MailService}。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li>{@link #unreadImportantTitles}：B 的 unreadImportant → 主题（取 MailSummary.subject）；</li>
 *   <li>{@link #correspondents}：B 没有现成方法，从 {@code MailService.search} 结果的发件人里取 {名字, 地址}（按地址去重，新的在前）。
 *       B 的 MailSummary 只有发件人、没有收件人，所以只能核实「给我写过信的人」。搜索词 {@link #QUERY}：IMAP 端是发件人 / 主题 / 正文
 *       包含匹配（「@」= 几乎所有邮件）；Gmail 端最多 10 封。查不到就返回空（核实收件人退回到用户亲口说地址 / 通讯录）。</li>
 * </ul>
 */
public final class LaneBMailReadAdapter implements MailReadPort {

    public static final String QUERY = "@";

    private final LaneBMailDraftAdapter.ServiceProvider mSvc;

    public LaneBMailReadAdapter(LaneBMailDraftAdapter.ServiceProvider svc) {
        mSvc = svc;
    }

    @Override public boolean available() {
        try {
            return mSvc != null && mSvc.service() != null && mSvc.ready();
        } catch (Throwable t) {
            return false;
        }
    }

    private MailService need() {
        MailService s = mSvc == null ? null : mSvc.service();
        if (s == null || !available()) throw new IllegalStateException("邮箱未接入");
        return s;
    }

    @Override public List<String> unreadImportantTitles(int max) throws Exception {
        ConnResult r = need().unreadImportant(max, null);
        if (!r.ok()) throw new IllegalStateException(r.text);
        List<String> out = new ArrayList<String>();
        JSONArray arr = new JSONArray(r.data == null || r.data.length() == 0 ? "[]" : r.data);
        for (int i = 0; i < arr.length() && out.size() < max; i++) out.add(arr.getJSONObject(i).optString("subject"));
        return out;
    }

    @Override public List<String[]> correspondents(int max) throws Exception {
        ConnResult r = need().search(QUERY, Math.max(1, max), null);
        if (!r.ok()) throw new IllegalStateException(r.text);
        return parseSenders(r.data, max);
    }

    static final Pattern ANGLE = Pattern.compile("^\\s*\"?([^\"<]*?)\"?\\s*<\\s*([^<>\\s]+@[^<>\\s]+)\\s*>\\s*$");
    static final Pattern BARE = Pattern.compile("^\\s*<?([^<>\\s,;]+@[^<>\\s,;]+?)>?\\s*$");

    /** 纯函数：MailSummary.json → {名字, 地址}（地址小写去重；名字可能为空）。 */
    public static List<String[]> parseSenders(String json, int max) throws Exception {
        Map<String, String[]> m = new LinkedHashMap<String, String[]>();
        JSONArray arr = new JSONArray(json == null || json.length() == 0 ? "[]" : json);
        for (int i = 0; i < arr.length() && m.size() < max; i++) {
            JSONObject o = arr.optJSONObject(i);
            String[] p = parseAddress(o == null ? "" : o.optString("from", ""));
            if (p == null) continue;
            String key = p[1].toLowerCase(java.util.Locale.ROOT);
            if (!m.containsKey(key)) m.put(key, p);
        }
        return new ArrayList<String[]>(m.values());
    }

    /** "张三 &lt;a@b.com&gt;" / "a@b.com" → {名字, 地址}；认不出 → null。 */
    public static String[] parseAddress(String from) {
        if (from == null) return null;
        String f = from.trim();
        Matcher a = ANGLE.matcher(f);
        if (a.matches()) return new String[]{a.group(1).trim(), a.group(2).trim()};
        Matcher b = BARE.matcher(f);
        if (b.matches()) return new String[]{"", b.group(1).trim()};
        return null;
    }
}
