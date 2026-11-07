package com.onesay.assistant.v2.quicknote;

import com.onesay.assistant.v2.s2.SensitiveGuard;
import com.onesay.assistant.v2.vision.screenref.ScreenEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [quicknote] S2+「记一记」：把当前屏幕的文字 + 识屏实体分类，写成<b>给人看的</b>一句摘要（纯 Java，JVM 全测，零网络）。
 *
 * <p>类别：取件码 · 快递 · 订单/票据 · 日程/地址 · 聊天 · 文章 · 其他。
 * <b>不记</b>：验证码 / 密码 / 银行卡号 / 证件号（SensitiveGuard 同口径），以及空屏。
 * 只做存储与摘要，<b>不触发任何副作用</b>（不拨号、不导航、不打开链接）。
 */
public final class QuickNoteClassifier {

    public enum Category {
        PICKUP("取件码"), PARCEL("快递"), ORDER("订单"), SCHEDULE("日程"), ADDRESS("地址"),
        CHAT("聊天"), ARTICLE("文章"), OTHER("屏幕"), REFUSED("不记"),
        /** R10：名片 / 联系人页（姓名 + 号码 →「存联系人」）。 */
        CONTACT("联系人"),
        /** R10：随口记（语音一句）。 */
        VOICE("随口记"),
        /** ★ R11：邮件（邮件 App 读信页：发件人 · 主题 · 要点 + 待办 / 截止 / 会议）。 */
        MAIL("邮件");

        public final String label;
        Category(String label) { this.label = label; }
    }

    public static final class Note {
        public final Category category;
        public final String title;     // 一行：「取件码 5-2-1234」
        public final String summary;   // 存进记忆库的完整一句（≤ 160 字）
        public final String app;       // 来源包名（只存包名，不存截图）
        public final String reason;    // REFUSED 时的原因
        // ★ R10 v2：全文 + 标签 + 实体 + 码 / 金额 / 时间（给新库、动作、按内容回忆用）
        public String body = "";
        public final List<String> tags = new ArrayList<String>();
        public final List<QuickNoteEntry.Ent> ents = new ArrayList<QuickNoteEntry.Ent>();
        public String codeKind = "", codeValue = "", codeWhere = "";
        public long eventAt;
        public long amountFen;
        /** ★ R11：邮件抽取结果（只有 MAIL 有）。 */
        public MailDigest.Result mail;
        Note(Category c, String title, String summary, String app, String reason) {
            this.category = c; this.title = title; this.summary = summary; this.app = app; this.reason = reason;
        }
        public boolean saved() { return category != Category.REFUSED; }
        /** 存库用的类别名：码类按码的种类（取件码 / 取餐码 / 券码 / 入园码）。 */
        public String label() { return category == Category.PICKUP && codeKind.length() > 0 ? codeKind : category.label; }
        /** 没读屏就拒绝（锁屏等），原因给人看。 */
        public static Note refused(String reason) { return new Note(Category.REFUSED, "", "", "", reason); }
        @Override public String toString() { return category + " title=" + title + " len=" + (summary == null ? 0 : summary.length()); }
    }

    static final Pattern ORDER_WORDS = Pattern.compile("订单|实付|合计|应付|总价|付款|已支付|支付成功|小计|发票|账单|票价|车次|航班|座位|检票|入场");
    static final Pattern CHAT_PKG = Pattern.compile("^(com\\.tencent\\.mm|com\\.tencent\\.mobileqq|com\\.tencent\\.tim|com\\.alibaba\\.android\\.rimet|com\\.ss\\.android\\.lark|com\\.android\\.mms|com\\.samsung\\.android\\.messaging)$");
    static final int MAX_SUMMARY = 160;

    /** 选座 / 待支付页（没付钱、没出票）→ 照记，但不建日程、不记账、不进票夹。 */
    static final Pattern TICKET_UNPAID = Pattern.compile("待支付|去支付|确认支付|立即支付|完成支付|支付剩余时间|剩余支付时间|支付剩余|锁座|未支付|(?<!值机|在线)选座|确认座位");
    static final Pattern TICKET_PAID = Pattern.compile("支付成功|购票成功|出票成功|已出票|已支付|交易成功|取票码|检票码|订单完成|已完成");
    static final Pattern TICKET_CTX = Pattern.compile("票|影城|影院|电影|场次|影厅|号厅|座位|排\\d|车次|航班|演出|演唱会|剧场|剧院|订单");
    static final Pattern FILM = Pattern.compile("《([^》]{1,30})》");
    static final Pattern ORDER_NO = Pattern.compile("(?:订单号|订单编号|订单)[:：]?\\s*([A-Za-z0-9]{5,24})");

    /** 选座 / 待支付页（整屏文字）：有未付字样、没有已付 / 出票字样、且像票务或订单页。 */
    public static boolean unpaidTicketPage(String all) {
        if (all == null) return false;
        return TICKET_UNPAID.matcher(all).find() && !TICKET_PAID.matcher(all).find() && TICKET_CTX.matcher(all).find();
    }

    /** 订单标题里代替「¥0.00」的东西：片名（《》里）→ 订单号 → 头一行。 */
    static String orderName(List<String> lines, String all) {
        Matcher f = FILM.matcher(all);
        if (f.find()) return "《" + f.group(1) + "》";
        Matcher o = ORDER_NO.matcher(all);
        if (o.find()) return o.group(1);
        return lines.isEmpty() ? "" : clip(lines.get(0), 24);
    }

    private QuickNoteClassifier() {}

    public static Note classify(String pkg, List<String> texts, List<ScreenEntity> entities) {
        return classify(pkg, texts, entities, System.currentTimeMillis());
    }

    /** ★ R11：带「现在」（邮件里只认将来的时间）。 */
    public static Note classify(String pkg, List<String> texts, List<ScreenEntity> entities, long now) {
        Note n = classify1(pkg, texts, entities, now);
        if (n.saved()) enrich(n, meaningful(texts), entities == null ? new ArrayList<ScreenEntity>() : entities);
        return n;
    }

    static final Pattern PARCEL_WORDS = Pattern.compile("快递|物流|包裹|运单|签收|派送|派件|驿站|丰巢|菜鸟|揽收|中转");

    /** 那串「单号」其实是订单号：挂在「订单号 / 订单编号」那一行上，或整屏没有任何快递字样。 */
    static boolean orderNumberNotParcel(List<String> lines, String all, ScreenEntity trk) {
        String v = trk.value == null ? "" : trk.value;
        for (String l : lines) {
            if (v.length() > 0 && l.contains(v) && (l.contains("订单号") || l.contains("订单编号") || l.contains("交易单号") || l.contains("流水号"))) return true;
        }
        return !PARCEL_WORDS.matcher(all).find();
    }

    static Note classify1(String pkg, List<String> texts, List<ScreenEntity> entities, long now) {
        String app = pkg == null ? "" : pkg;
        List<String> lines = meaningful(texts);
        List<ScreenEntity> ents = entities == null ? new ArrayList<ScreenEntity>() : entities;
        if (lines.isEmpty() && ents.isEmpty()) {
            return new Note(Category.REFUSED, "", "", app, "屏幕上没有可记的文字");
        }
        // 1. 敏感：验证码实体 / 整屏有密码、证件、卡号字样 → 不记
        for (ScreenEntity e : ents) {
            if (e.type == ScreenEntity.Type.CODE && "验证码".equals(e.subLabel)) {
                return new Note(Category.REFUSED, "", "", app, "验证码");
            }
        }
        // ★ R11：邮箱地址 / 网址先拿掉再判；邮件正文里谈到「密码」的句子不算密码页（ScreenSensitivity）
        String sens = ScreenSensitivity.pageReason(MailDigest.isMailApp(app), lines);
        if (sens != null) return new Note(Category.REFUSED, "", "", app, sens);
        // 1b. ★ 选座 / 待支付页：记成「待支付」，不当日程、不记账（enrich 里清掉时间与金额）
        String all0 = join(lines, " ");
        if (unpaidTicketPage(all0)) {
            String name = orderName(lines, all0);
            String title = "待支付 " + name;
            Note un = note(Category.ORDER, title.trim(), title.trim() + "（未支付，不建日程、不记账）", app);
            un.tags.add("待支付");
            return un;
        }
        // 2. 取件码 / 取餐码 / 券码 / ★ 取票码 / 检票码
        ScreenEntity code = first(ents, ScreenEntity.Type.CODE);
        if (code != null) {
            String label = code.subLabel != null ? code.subLabel : "取件码";
            String where = contextOf(lines, code.raw);
            String title = label + " " + code.value;
            return note(Category.PICKUP, title, title + (where.length() > 0 ? "（" + where + "）" : ""), app);
        }
        // 3. 快递单号（R10 真机复核：「订单号 2026…」也会被抽成 TRACKING——有金额 + 订单字样、且那串号挂在「订单号」上
        //    或整屏没有任何快递字样时，按订单处理，不当快递单号）
        ScreenEntity trk = first(ents, ScreenEntity.Type.TRACKING);
        ScreenEntity amt = first(ents, ScreenEntity.Type.AMOUNT);
        String all = join(lines, " ");
        boolean orderish = amt != null && ORDER_WORDS.matcher(all).find();
        if (trk != null && !(orderish && orderNumberNotParcel(lines, all, trk))) {
            String title = (trk.subLabel != null ? trk.subLabel + " " : "") + "快递单号 " + trk.value;
            return note(Category.PARCEL, title, title, app);
        }
        // 3b. ★ R11 邮件：发件人 · 主题 · 要点；有待办 / 截止 / 会议 → 结果卡给「提醒 / 加日程 / 加待办」
        if (MailDigest.isMailApp(app)) {
            MailDigest.Result md = MailDigest.digest(lines, ents, now);
            String head = md.subject.length() > 0 ? md.subject : (lines.isEmpty() ? "" : clip(lines.get(0), 24));
            String title = "邮件 " + (md.actionable() ? md.kindLabel() + " " : "") + clip(head, 24);
            String sum = md.summary();
            Note mn = note(Category.MAIL, title.trim(), sum.length() > 0 ? sum : title, app);
            mn.mail = md;
            return mn;
        }
        // 4. 订单 / 票据：有金额 + 订单类字样
        if (orderish) {
            String head = lines.isEmpty() ? "" : clip(lines.get(0), 24);
            // ★ 0 元订单（免费票 / 兑换券）：标题用片名 / 订单号，不写「订单 ¥0.00」
            String title = fen(amt.value) <= 0 ? ("订单 " + orderName(lines, all)).trim() : "订单 " + amt.raw;
            return note(Category.ORDER, title, title + (head.length() > 0 ? "（" + head + "）" : ""), app);
        }
        // 5. 日程 / 地址（★ R11：光秃秃一个钟点、又≈现在 → 是状态栏 / 桌面时钟，不当日程：真机「日程 下午11:02」「日程 下午 11:12 (vl)」）
        ScreenEntity dt = firstScheduleTime(ents, now, lines);
        ScreenEntity addr = first(ents, ScreenEntity.Type.ADDRESS);
        if (dt != null) {
            String title = "日程 " + dt.raw + (addr != null ? " @ " + clip(addr.value, 30) : "");
            return note(Category.SCHEDULE, title, title, app);
        }
        if (addr != null) {
            String title = "地址 " + clip(addr.value, 40);
            return note(Category.ADDRESS, title, title, app);
        }
        // 5b. R10 名片 / 联系人：有电话号码 + 一行像姓名（2–4 个汉字）→ 联系人（聊天 App 里不算）
        ScreenEntity ph = first(ents, ScreenEntity.Type.PHONE);
        if (ph != null && !CHAT_PKG.matcher(app).matches()) {
            String name = guessName(lines, ph.raw);
            if (name != null) {
                String title = "联系人 " + name + " " + ph.value;
                return note(Category.CONTACT, title, title, app);
            }
        }
        // 6. 聊天：最后 3 行
        if (CHAT_PKG.matcher(app).matches() && !lines.isEmpty()) {
            int from = Math.max(0, lines.size() - 3);
            String body = join(lines.subList(from, lines.size()), " / ");
            return note(Category.CHAT, "聊天 " + clip(lines.get(lines.size() - 1), 20), "聊天记录：" + body, app);
        }
        // 7. 文章：文字多 → 标题 = 最长的前排行，摘要 = 头两段
        int chars = all.length();
        if (chars >= 200 && lines.size() >= 4) {
            String title = longestOfFirst(lines, 5);
            String body = join(lines.subList(0, Math.min(3, lines.size())), " ");
            return note(Category.ARTICLE, "文章 " + clip(title, 24), "文章《" + clip(title, 30) + "》：" + body, app);
        }
        // 8. 其他：前两行
        String body = join(lines.subList(0, Math.min(2, lines.size())), " / ");
        return note(Category.OTHER, "屏幕 " + clip(lines.get(0), 20), body, app);
    }

    static final Pattern DATE_WORD = Pattern.compile("月|日|号|周|星期|礼拜|明天|后天|今天|今晚|明早|明晚|下周|本周|\\d{1,2}[/\\-]\\d{1,2}");

    /** 是不是「时钟读数」：没有日期字样、且与现在相差 ≤ 5 分钟。 */
    static boolean isClockNow(ScreenEntity e, long now) {
        if (e == null || e.type != ScreenEntity.Type.DATETIME || now <= 0 || e.beginMs <= 0) return false;
        if (e.raw != null && DATE_WORD.matcher(e.raw).find()) return false;
        return Math.abs(e.beginMs - now) <= 5L * 60L * 1000L;
    }

    /** ★ R11 真机复核：时间挂在「已更新 / 更新于 / 发布于 / updated …」后面 → 是时间戳，不是日程
     *  （桌面天气小部件「已更新 10/11 下午11:02」被记成「日程 下午11:02」，10-11 23:11 和 10-12 21:43 两次）。 */
    static final Pattern STAMP_WORDS = Pattern.compile("(?i)已更新|更新于|更新时间|最后更新|上次更新|刷新于|同步于|已同步|发布于|发表于|发布时间|发送于|编辑于|修改于|updated|last update|synced|posted");

    static boolean isTimestamp(ScreenEntity e, List<String> lines) {
        if (e == null) return false;
        if (e.contextLine != null && STAMP_WORDS.matcher(e.contextLine).find()) return true;
        if (lines != null && e.raw != null && e.raw.length() > 0) {
            for (String l : lines) if (l != null && l.contains(e.raw) && STAMP_WORDS.matcher(l).find()) return true;
        }
        return false;
    }

    static ScreenEntity firstScheduleTime(List<ScreenEntity> ents, long now) {
        return firstScheduleTime(ents, now, null);
    }

    static ScreenEntity firstScheduleTime(List<ScreenEntity> ents, long now, List<String> lines) {
        ScreenEntity best = null;
        for (ScreenEntity e : ents) {
            if (e.type != ScreenEntity.Type.DATETIME || isClockNow(e, now) || isTimestamp(e, lines)) continue;
            if (best == null || e.confidence > best.confidence) best = e;
        }
        return best;
    }

    // ---------- R10 v2：全文 / 实体 / 标签 ----------

    static final Pattern NAME_LINE = Pattern.compile("^(姓名|联系人|名字)?[:：\\s]*([\\u4e00-\\u9fa5]{1,4}?)(先生|女士|老师|经理|总)?$");
    static final Pattern SALE_WORDS = Pattern.compile("开售|开抢|预售|抢票|放票|开票|开卖|抢购|秒杀");
    static final int MAX_BODY = 8000;

    static String guessName(List<String> lines, String phoneRaw) {
        for (String l : lines) {
            String t = l.trim();
            if (phoneRaw != null && t.contains(phoneRaw)) {
                String rest = t.replace(phoneRaw, " ").replaceAll("(电话|手机|号码|联系方式|Tel|TEL)[:：]?", " ").trim();
                java.util.regex.Matcher m = NAME_LINE.matcher(rest);
                if (m.matches()) return m.group(2) + (m.group(3) != null ? m.group(3) : "");
                continue;
            }
            java.util.regex.Matcher m = NAME_LINE.matcher(t);
            if (m.matches() && (m.group(1) != null || m.group(3) != null)) return m.group(2) + (m.group(3) != null ? m.group(3) : "");
        }
        return null;
    }

    /** 补全文、实体、标签、码 / 金额 / 时间（纯函数）。 */
    static void enrich(Note n, List<String> lines, List<ScreenEntity> ents) {
        n.body = clip(join(lines, "\n"), MAX_BODY);
        for (ScreenEntity e : ents) {
            if (e.type == ScreenEntity.Type.CODE && "验证码".equals(e.subLabel)) continue;
            n.ents.add(new QuickNoteEntry.Ent(e.type.name(), e.value, e.raw, e.type == ScreenEntity.Type.DATETIME ? e.beginMs : 0L));
        }
        if (n.category == Category.CONTACT) {
            ScreenEntity ph = first(ents, ScreenEntity.Type.PHONE);
            String name = guessName(lines, ph == null ? null : ph.raw);
            if (name != null) n.ents.add(new QuickNoteEntry.Ent("NAME", name, name, 0L));
        }
        ScreenEntity code = first(ents, ScreenEntity.Type.CODE);
        if (n.category == Category.PICKUP && code != null) {
            n.codeKind = code.subLabel != null ? code.subLabel : "取件码";
            n.codeValue = code.value;
            n.codeWhere = contextOf(lines, code.raw);
        }
        ScreenEntity dt = first(ents, ScreenEntity.Type.DATETIME);
        if (dt != null && dt.beginMs > 0) n.eventAt = dt.beginMs;
        ScreenEntity amt = first(ents, ScreenEntity.Type.AMOUNT);
        if (amt != null) n.amountFen = fen(amt.value);
        if (n.tags.contains("待支付")) {
            // ★ 选座 / 待支付页：没付钱、没出票 → 不给「加日程」、不记账
            n.eventAt = 0L;
            n.amountFen = 0L;
        }
        if (n.mail != null) {
            // 邮件：只用抽出来的将来时间（读信页顶上的发信时间是过去的，不当日程）；邮件不自动记账
            n.eventAt = n.mail.at;
            n.amountFen = 0L;
            addTag(n, "邮件");
            if (n.mail.actionable()) addTag(n, n.mail.kindLabel());
            if (n.mail.allDay && n.mail.at > 0) addTag(n, "全天");
        }
        // 标签：类别 + 来源 App 类型 + 关键词
        addTag(n, n.label());
        String t = appType(n.app);
        if (t != null) addTag(n, t);
        String all = join(lines, " ");
        if (dt != null && SALE_WORDS.matcher(all).find()) addTag(n, "开售");
        if (first(ents, ScreenEntity.Type.URL) != null) addTag(n, "链接");
        if (first(ents, ScreenEntity.Type.PHONE) != null) addTag(n, "电话");
        if (first(ents, ScreenEntity.Type.ADDRESS) != null && n.category != Category.ADDRESS) addTag(n, "地址");
        if (n.category == Category.ORDER) addTag(n, "账单");
    }

    static void addTag(Note n, String t) {
        if (t != null && t.length() > 0 && !n.tags.contains(t)) n.tags.add(t);
    }

    static long fen(String v) {
        try {
            return Math.round(Double.parseDouble(v.replace(",", "").trim()) * 100.0);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 来源 App 类型（本机规则）。 */
    static String appType(String pkg) {
        if (pkg == null) return null;
        if (CHAT_PKG.matcher(pkg).matches()) return "社交";
        if (pkg.contains("taobao") || pkg.contains("jingdong") || pkg.contains("jd.") || pkg.contains("pinduoduo") || pkg.contains("tmall")) return "购物";
        if (pkg.contains("meituan") || pkg.contains("eleme") || pkg.contains("starbucks") || pkg.contains("luckin") || pkg.contains("mcdonald") || pkg.contains("kfc")) return "外卖";
        if (pkg.contains("xingin") || pkg.contains("weibo") || pkg.contains("zhihu") || pkg.contains("douban") || pkg.contains("toutiao") || pkg.contains("browser") || pkg.contains("chrome") || pkg.contains("sbrowser")) return "资讯";
        if (pkg.contains("cainiao") || pkg.contains("sf") || pkg.contains("express")) return "快递";
        if (pkg.contains("12306") || pkg.contains("ctrip") || pkg.contains("damai") || pkg.contains("maoyan")) return "出行票务";
        return null;
    }

    /** R10 随口记：一句语音（已过敏感检查之外再查一次）→ Note（类别随口记，带实体）。 */
    public static Note classifyVoice(String text, List<ScreenEntity> entities) {
        String t = text == null ? "" : text.trim();
        if (t.length() == 0) return new Note(Category.REFUSED, "", "", "", "没听到要记的内容");
        String r = SensitiveGuard.reason(t);
        if (r != null) return new Note(Category.REFUSED, "", "", "", r);
        Note n = note(Category.VOICE, clip(t, 24), t, "");
        List<String> lines = new ArrayList<String>();
        lines.add(t);
        enrich(n, lines, entities == null ? new ArrayList<ScreenEntity>() : entities);
        return n;
    }

    // ---------- 工具 ----------

    static Note note(Category c, String title, String summary, String app) {
        return new Note(c, title, clip(summary, MAX_SUMMARY), app, null);
    }

    static List<String> meaningful(List<String> texts) {
        List<String> out = new ArrayList<String>();
        if (texts == null) return out;
        for (String t : texts) {
            if (t == null) continue;
            String s = t.trim().replace('\n', ' ');
            if (s.length() < 2) continue;
            if (s.matches("^[\\d:：%\\s.]+$")) continue;   // 时间 / 电量 / 纯数字状态栏
            if (out.contains(s)) continue;
            out.add(s);
        }
        return out;
    }

    static ScreenEntity first(List<ScreenEntity> ents, ScreenEntity.Type t) {
        ScreenEntity best = null;
        for (ScreenEntity e : ents) {
            if (e.type != t) continue;
            if (best == null || e.confidence > best.confidence) best = e;
        }
        return best;
    }

    /** 实体所在行里除了实体本身的部分（例如「菜鸟驿站 5-2-1234」→「菜鸟驿站」）。 */
    static String contextOf(List<String> lines, String raw) {
        if (raw == null) return "";
        for (String l : lines) {
            if (l.contains(raw)) {
                String rest = l.replace(raw, " ").replaceAll("(取件码|取货码|提货码|取餐码|取餐号|券码|核销码|兑换码)[:：]?", " ")
                        .replaceAll("\\s+", " ").trim();
                rest = rest.replaceAll("^[，,。:：\\s]+|[，,。:：\\s]+$", "");
                return clip(rest, 30);
            }
        }
        return "";
    }

    static String longestOfFirst(List<String> lines, int n) {
        String best = lines.get(0);
        for (int i = 0; i < Math.min(n, lines.size()); i++) {
            String l = lines.get(i);
            if (l.length() > best.length() && l.length() <= 40) best = l;
        }
        return best;
    }

    static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
