package com.onesay.assistant.v2.ui.wake;

/**
 * [R13 唤醒界面 v2] 唤醒卡片上的文案（纯 Java，JVM 可测）。
 *
 * <p>方案 §8 / v2.2：调试味文案退出用户界面 —— 状态行只剩「正在听 / 正在想… / 已完成 / 没听清」；来源（本地 / 云端 /
 * 模型）、状态码、回读校验、「这是一条复合任务…」「OneSay · 唤醒确认」之类一律不上卡片（日志照旧写，开发者模式照旧看）。
 * 旧界面（classic）不经过这里，文案原样。
 */
public final class WakeCopy {

    private WakeCopy() {}

    public static final String LISTENING = "正在听";
    public static final String THINKING = "正在想…";
    public static final String DONE = "已完成";
    public static final String NO_SPEECH = "没听清";
    public static final String NO_SPEECH_LINE = "没听清，再说一次？";
    /** [R13 离线兜底] 没网：语音识别要联网，打字的本地指令照常。 */
    public static final String OFFLINE_LINE = "没有网络，语音暂时用不了";
    /** [R13 卡死兜底] 等结果超时。 */
    public static final String TIMEOUT_LINE = "等太久了，再说一次？";
    /** 服务层统一用的徽标（classifyBadge 认它们是 ERROR）。 */
    public static final String BADGE_OFFLINE = "没有网络";
    /** ★ R13b：离线兜底改为直接打开网络设置。 */
    public static final String OFFLINE_OPEN_SETTINGS = "没有网络，正在打开网络设置";
    public static final String BADGE_TIMEOUT = "等待超时";

    /** 错误态卡片上那一句（按徽标选；JVM 可测）。 */
    public static String errorLine(String badge) {
        String asr = asrErrorLine(badge);
        if (asr != null) return asr;
        if (badge != null && badge.contains(BADGE_OFFLINE)) return OFFLINE_LINE;
        if (badge != null && badge.contains(BADGE_TIMEOUT)) return TIMEOUT_LINE;
        return NO_SPEECH_LINE;
    }
    public static final String LISTEN_HINT = "我在听";

    /**
     * r2/p0 P0-2：语音识别失败分型（{@link com.onesay.assistant.v2.asr.AsrFailure}）的徽标 → 新卡片上那一句（短）；不是这几个徽标返回 null。
     * 「没听清」不在这里（照旧 {@link #NO_SPEECH_LINE}）。
     */
    public static String asrErrorLine(String badge) {
        com.onesay.assistant.v2.asr.AsrFailure.Kind k = asrKind(badge);
        if (k == null) return null;
        switch (k) {
            case NOT_CONFIGURED: return "语音识别还没配置，点「去配置」";
            case AUTH: return "语音识别 Key 失效，点「去配置」换一个";
            case TIMEOUT: return "网络有点慢，这句没识别出来";
            case RATE_LIMIT: return "识别服务太忙，这句没识别出来";
            case SERVER: return "识别服务出错，这句没识别出来";
            case NETWORK: return "连不上语音识别服务";
            default: return null;
        }
    }

    /** 徽标是不是识别失败分型的徽标（精确匹配，避免误伤「识别中」之类）。 */
    public static com.onesay.assistant.v2.asr.AsrFailure.Kind asrKind(String badge) {
        if (badge == null) return null;
        String b = badge.trim();
        for (com.onesay.assistant.v2.asr.AsrFailure.Kind k : com.onesay.assistant.v2.asr.AsrFailure.Kind.values()) {
            if (k == com.onesay.assistant.v2.asr.AsrFailure.Kind.NO_SPEECH) continue;
            if (com.onesay.assistant.v2.asr.AsrFailure.badge(k).equals(b)) return k;
        }
        return null;
    }

    /** 状态行文字（null = 不显示状态行）。 */
    public static String statusWord(WakeStage.Stage s) {
        switch (s) {
            case LISTENING: return LISTENING;
            case THINKING:
            case ANSWERING:
            case TASK: return THINKING;
            case ERROR: return NO_SPEECH;
            default: return null;
        }
    }

    /** 旧代码塞进转写位 / 副标题 / 徽标的调试文案（新界面不显示）。 */
    private static final String[] DEBUG_EXACT = {
            "OneSay · 唤醒确认", "随时对我说话，或点击下方文字输入...", "我在听，请讲...", "正在调度特权通道...",
            "这是一条复合任务，正在逐步执行…", "已跳转界面 · 面板即将让位", "多步任务已处理", "已完成屏幕视觉解析",
            "正在执行并继续后续步骤…", "本轮任务已结束", "请在下方输入文字指令...", "按键唤醒，或点「✦ 语音」「⌨ 文字」",
            "已切回语音 · 请直接说话", "文字模式 · 回车或点「发送」提交", "正在执行系统动作...", "本次唤醒已结束",
            "注入的指令为空", "刚才的指令已丢弃", "高危操作 · 需手动确认", "确认后才会执行该操作", "云端识别中…"
    };

    private static final String[] DEBUG_PREFIX = {
            "云端大模型", "本地直答", "云端 · ", "✓ 物理硬件", "⚠ 状态码", "状态码:", "OneSay", "正在调度"
    };

    public static boolean isDebugCopy(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.length() == 0) return true;
        for (String e : DEBUG_EXACT) if (t.equals(e)) return true;
        for (String p : DEBUG_PREFIX) if (t.startsWith(p)) return true;
        // 「共 3 步」「第 1 步 / 共 3 步」这种进度计数：岛上有步骤环，卡片不重复
        if (t.matches("^共\\s*\\d+\\s*步$")) return true;
        return false;
    }

    /**
     * 转写位文字 → 卡片上真正要显示的那句话。
     * 「你说：“明天上午有什么安排”」→「明天上午有什么安排」；「问：“…”」→ 提问回显（同样去壳）；调试文案 → null。
     */
    public static String transcript(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (isDebugCopy(t)) return null;
        t = unwrap(t, "你说：");
        t = unwrap(t, "问：");
        if (t.startsWith("“") && t.endsWith("”") && t.length() >= 2) t = t.substring(1, t.length() - 1);
        if (t.startsWith("正在：")) t = t.substring(3);
        return t.trim().length() == 0 ? null : t.trim();
    }

    private static String unwrap(String t, String prefix) {
        if (!t.startsWith(prefix)) return t;
        String r = t.substring(prefix.length()).trim();
        if (r.startsWith("“") && r.endsWith("”") && r.length() >= 2) r = r.substring(1, r.length() - 1);
        return r;
    }

    /** 服务层塞进「提问」位的伪问题（不是用户说的话）：新界面不回显。 */
    public static boolean isPseudoQuestion(String q) {
        if (q == null) return true;
        String t = q.trim();
        return t.length() == 0 || t.equals("任务结果") || t.equals("多步任务") || t.equals("屏幕内容识别") || t.equals("识屏失败")
                || t.equals("识别屏幕") || t.equals("正在识别当前屏幕…");
    }

    /**
     * 结果正文：去掉旧 emoji 前缀（⚠️ 📧 ✦ ⌨ ◎ ✓ ✕）；[r2/uxcompare] 再去掉开头的客套 / 来源铺垫
     * （"好的，""根据检索结果，"），让大字首行直接是结论（见 {@link com.onesay.assistant.v2.util.SpeechText#stripLeadFiller}）。
     */
    public static String cleanBody(String s) {
        return com.onesay.assistant.v2.util.SpeechText.stripLeadFiller(cleanEmojiLead(s));
    }

    private static String cleanEmojiLead(String s) {
        if (s == null) return "";
        String t = s;
        String[] lead = {"⚠️ ", "⚠️", "⚠ ", "⚠", "\uD83D\uDCE7 ", "✦ ", "⌨ ", "◎ ", "✓ "};
        boolean again = true;
        while (again) {
            again = false;
            for (String l : lead) {
                if (t.startsWith(l)) {
                    t = t.substring(l.length());
                    again = true;
                }
            }
        }
        return t.trim();
    }

    /**
     * 答案拆成「首行（26sp 级大字）+ 其余（正文）」（方案 v2.5 排版）：
     * <ul>
     *   <li>有换行且第一行 ≤ 28 字 → 第一行是首行；</li>
     *   <li>第一句（。！？!?）≤ 24 字且后面还有内容 → 第一句是首行；</li>
     *   <li>否则整段 ≤ 24 字 → 只有首行；</li>
     *   <li>否则没有首行（长段落整体按正文排，大字读长文反而累）。</li>
     * </ul>
     * 返回 {headline 或 null, body 或 null}。
     */
    public static String[] splitHeadline(String answer) {
        String a = cleanBody(answer);
        if (a.length() == 0) return new String[] {null, null};
        int nl = a.indexOf('\n');
        if (nl > 0 && nl <= 28) {
            String rest = a.substring(nl + 1).trim();
            return new String[] {a.substring(0, nl).trim(), rest.length() == 0 ? null : rest};
        }
        int end = -1;
        for (int i = 0; i < a.length() && i <= 25; i++) {
            char c = a.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') {
                end = i;
                break;
            }
        }
        if (end > 0 && end < a.length() - 1) {
            String head = a.substring(0, end + 1).trim();
            // 句号收尾的首行去掉句号（大字更干净），问号 / 感叹号保留语气
            if (head.endsWith("。")) head = head.substring(0, head.length() - 1);
            String rest = a.substring(end + 1).trim();
            return new String[] {head, rest.length() == 0 ? null : rest};
        }
        if (a.length() <= 24 && a.indexOf('\n') < 0) return new String[] {a, null};
        return new String[] {null, a};
    }

    // ------------------------------------------------------------------ ★ r2/voice-templates 快速阅读

    /** 正文超过这个长度、又没有换行时，才按句子拆成短段落。 */
    public static final int PARA_MIN_TOTAL = 80;
    /** 一段攒到这个字数就收（约两句话）。 */
    public static final int PARA_TARGET = 48;

    /**
     * 卡片正文分段（答案不再读出来，长段落一眼扫不完）：
     * <ul>
     *   <li>已有换行：只把连续空行收成一个换行、去掉每行首尾空白（模型自己分好的段保留）；</li>
     *   <li>没换行且 ≤ {@link #PARA_MIN_TOTAL} 字：原样；</li>
     *   <li>否则按句末（。！？!?；）切句，攒到 ≥ {@link #PARA_TARGET} 字成一段，最后一段太短（&lt;16 字）并回上一段。</li>
     * </ul>
     * 只动空白，不改一个字。返回的段落以 "\n" 分隔。
     */
    public static String readableParagraphs(String body) {
        if (body == null) return "";
        String t = body.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (t.length() == 0) return "";
        if (t.indexOf('\n') >= 0) {
            StringBuilder sb = new StringBuilder();
            for (String line : t.split("\n")) {
                String l = line.trim();
                if (l.length() == 0) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(l);
            }
            return sb.toString();
        }
        if (t.length() <= PARA_MIN_TOTAL) return t;
        java.util.List<String> sentences = new java.util.ArrayList<String>();
        int start = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '；') {
                int end = i + 1;
                // 句末后面紧跟的右引号 / 右括号归本句
                while (end < t.length() && "”’」』）)".indexOf(t.charAt(end)) >= 0) end++;
                sentences.add(t.substring(start, end));
                start = end;
                i = end - 1;
            }
        }
        if (start < t.length()) sentences.add(t.substring(start));
        java.util.List<String> paras = new java.util.ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (String se : sentences) {
            String x = se.trim();
            if (x.length() == 0) continue;
            cur.append(x);
            if (cur.length() >= PARA_TARGET) {
                paras.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            if (cur.length() < 16 && !paras.isEmpty()) {
                int last = paras.size() - 1;
                paras.set(last, paras.get(last) + cur);
            } else {
                paras.add(cur.toString());
            }
        }
        StringBuilder out = new StringBuilder();
        for (String pa : paras) {
            if (out.length() > 0) out.append('\n');
            out.append(pa);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ [r2/uxcompare] 失败提示可操作

    /**
     * 执行失败那一句：原因 + 下一步（失败不暴露状态码）。
     *
     * <p>对标 Google 对话设计「出错时给出能把用户带回正轨的下一步」、Apple HIG「Help people understand errors」：
     * 只说"没生效"不够，要告诉用户<b>现在可以怎么做</b>。原文案里已经带了下一步（"可以…""请…""说「…"）就不再追加。
     * 用户自己拒绝 / 取消 / 待确认 / 安全策略拦截不追加（那不是需要补救的失败）。
     */
    public static String failureLine(com.onesay.assistant.v2.executor.ErrorCode code, String userMessage) {
        String base = cleanBody(userMessage);
        if (base.length() == 0) base = "没能完成";
        String next = nextStep(code);
        if (next == null || hasNextStep(base)) return base;
        char last = base.charAt(base.length() - 1);
        boolean punct = last == '。' || last == '！' || last == '？' || last == '!' || last == '?' || last == '.';
        return base + (punct ? "" : "。") + next + "。";
    }

    /** 按错误码给用户的下一步（人话；null = 不追加）。 */
    static String nextStep(com.onesay.assistant.v2.executor.ErrorCode code) {
        if (code == null) return null;
        switch (code) {
            case NOT_FOUND_APP: return "可以换个叫法再说一次，或先去应用商店安装";
            case VERIFY_FAILED: return "可以再说一次，或者说「打开设置」自己调";
            case EXEC_FAILED: return "可以再说一次试试";
            case CHANNEL_TIMEOUT:
            case TIMEOUT: return "稍等几秒再说一次";
            case CHANNEL_DOWN: return "系统通道没在运行，重启手机后再试";
            case NETWORK_ERROR: return "检查一下网络再说一次";
            case TLS_ERROR: return "检查网络和系统时间后再试";
            case PERMISSION_DENIED: return "到系统设置里给 OneSay 开权限后再试";
            case NOT_CONFIGURED: return "先到 OneSay 设置里配好再用";
            case NOT_INSTALLED: return "先装好需要的应用再试";
            case AUTH_FAILED: return "授权可能过期了，到设置里更新一下";
            case NEED_PARAM:
            case SCHEMA_INVALID: return "可以说得具体一点，比如带上数字或名字";
            case UNKNOWN_CAPABILITY: return "这个我还做不了，可以换个说法试试";
            default: return null;   // OK / 用户拒绝 / 取消 / 待确认 / 安全策略拒绝
        }
    }

    private static boolean hasNextStep(String s) {
        return s.contains("可以") || s.contains("请") || s.contains("去设置") || s.contains("点「") || s.contains("点击")
                || s.contains("说「") || s.contains("再试") || s.contains("重试");
    }

    /** 没听懂（路由返回空）那一句：复述 + 换个说法的例子（Google「rapid reprompt」：别只说"不懂"）。 */
    public static String unrecognizedLine(String command) {
        String c = command == null ? "" : command.trim();
        if (c.length() > 20) c = c.substring(0, 20) + "…";
        String head = c.length() == 0 ? "这句我没听懂" : "这句我没听懂：「" + c + "」";
        return head + "\n可以换个说法，比如「打开微信」「明天天气怎么样」「随口记 车停在 B2」";
    }

    /** 红卡标题（人话）。 */
    public static String confirmTitle(boolean red) {
        return red ? "这一步需要你确认" : "要允许这一步吗？";
    }

    /** 确认卡说明（人话；能力摘要 + 通信说明 + 一句后果）。 */
    public static String confirmBody(boolean red, String summary, String comms, String note) {
        StringBuilder b = new StringBuilder();
        b.append(summary == null || summary.length() == 0 ? "这个操作" : summary);
        if (comms != null && comms.length() > 0) b.append("\n").append(comms);
        b.append("\n").append(red ? "做了就撤不回来，确定要继续吗？" : "允许后我才会去做。");
        if (note != null && note.length() > 0) b.append("\n").append(note);
        return b.toString();
    }

    /**
     * 确认卡说明 = 标题行（远程工具用真实 sign，不用远端 description）
     * + 通信号码行 + {@link com.onesay.assistant.v2.executor.ConfirmFacts#lines 关键实参}（收件人 / 网址 host / 金额 / 短信正文 / 远程参数摘要）+ 后果句。
     */
    public static String confirmBodyFor(boolean red, com.onesay.assistant.v2.capability.Capability cap, org.json.JSONObject args, String note) {
        String comms = cap == null ? null : com.onesay.assistant.v2.executor.CommsRisk.cardNote(cap.sign, args);
        String facts = com.onesay.assistant.v2.executor.ConfirmFacts.lines(cap, args);
        String extra = comms;
        if (facts != null && facts.length() > 0) extra = extra == null || extra.length() == 0 ? facts : extra + "\n" + facts;
        return confirmBody(red, com.onesay.assistant.v2.executor.ConfirmFacts.headline(cap), extra, note);
    }

    /** 执行结果的一句话（失败不暴露状态码；成功不写「回读校验」）。 */
    public static String resultLine(boolean ok, String userMessage) {
        String m = cleanBody(userMessage);
        if (m.length() > 0) return m;
        return ok ? DONE : "没能完成";
    }
}
