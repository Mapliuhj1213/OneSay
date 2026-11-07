package com.onesay.assistant.v2.s2;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [s2] round3 #2：<b>不带前缀</b>的「记一下」——把自然的陈述句（「我车停在B2-17」「护照放在书房第二个抽屉」）
 * 识别成要记住的事实（纯 Java，JVM 可测）。开关 {@code bixby_s2} 的 {@code memory.implicit}（默认开）+ kill-switch。
 *
 * <h3>原则：宁可漏记，不可抢指令</h3>
 * 这条规则在路由层<b>分诊之前</b>接管（S2-H1），误判 = 用户的指令/问题被吞成一条笔记，所以只认三类很窄的句式，
 * 其它一律放行给原流程：
 * <ol>
 *   <li><b>放置/停放</b>：物品 + 放置动词（停/放/搁/锁/塞/藏/挂/存/寄存/停放）+（在/到/进…）+ 像地点的宾语。
 *       「我车停在B2-17」「车停B2-17了」「护照放在书房第二个抽屉」「我把钥匙放鞋柜上了」。
 *       主体省略只认「停」（→ 车，且必须带「在/到」：「停在B2-17」）；主体是音量/亮度/歌/视频等指令对象一律不认。</li>
 *   <li><b>「X 在 Y」</b>：X 必须是个人物品白名单里的东西（钥匙/护照/充电器/车/伞…），Y 像地点。
 *       「北京在华北」（X 不是个人物品）、「我在家」（X 是人）都不认。</li>
 *   <li><b>「X 是 Y」</b>：只认个人槽位白名单（车牌/房间号/柜子号/门禁/停车位/车位/工位/座位号/储物柜/寄存柜/快递柜/行李牌）。
 *       「北京是首都」不认。</li>
 * </ol>
 * 之前必须先过三道排除：<b>问句</b>（带 哪/什么/几/多少/谁/怎么/吗/呢/？…）、<b>指令</b>（打开/关闭/导航/调/设/播放/把…调到…
 * 等开头或结构，或带「然后/再/顺便」的多步句）、<b>识屏指代</b>（「记一下这个」交给识屏）。
 *
 * <p>敏感内容不在这里判：命中的句子交给 {@link SensitiveGuard}（密码/验证码/卡号/证件号拒存并解释）。
 * 另外「wifi 密码是…」这种<b>想存密码的陈述句</b>本身不属于上面三类，但用户显然是想让我记 ——
 * {@link #detect} 以 {@link Kind#SENSITIVE_ATTEMPT} 返回，由 S2 回复拒存原因，而不是把密码送去大模型。
 */
public final class ImplicitNote {

    private ImplicitNote() {}

    public enum Kind { PLACE, AT, SLOT, SENSITIVE_ATTEMPT }

    /** 命中结果。 */
    public static final class Hit {
        public final Kind kind;
        /** 要存的内容（原句修剪后）。 */
        public final String content;
        /** 主体（已归一）。 */
        public final String subject;
        /** 值（位置 / 槽位值）。 */
        public final String value;

        Hit(Kind kind, String content, String subject, String value) {
            this.kind = kind;
            this.content = content;
            this.subject = subject;
            this.value = value;
        }

        @Override public String toString() {
            return kind + " subj=" + subject + " val=" + value;
        }
    }

    // ------------------------------------------------------------------ 排除

    /** 问句信号（key 形态上判：已去标点、小写）。 */
    private static final Pattern QUESTION = Pattern.compile(
            "哪|什么|啥|(?<!茶)几|多少|多久|多远|谁|怎么|怎样|为什么|为啥|是不是|有没有|能不能|可不可以|要不要|会不会|对不对|吗$|呢$|么$|嘛$|不$");
    /** 指令开头。 */
    private static final Pattern COMMAND_HEAD = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我|你帮我|帮忙|我要|我想|想要|我需要|打开|关闭|关掉|开启|启动|开一下|开下|导航|播放|放一首|放首|放歌|放点|放个|来一首|来首|"
            + "调到|调成|调高|调低|调大|调小|设置|设成|设为|拨打|打电话|打给|呼叫|发送|发给|发个|发条|搜索|搜一下|查一下|查查|查询|找一下|"
            + "定个|定一个|订个|订一个|叫个|叫一辆|提醒|告诉|看看|看下|看一下|读一下|念一下|翻译|拍照|拍个|截图|截屏|录音|录像|下载|安装|卸载|"
            + "分享|返回|退出|删除|删掉|清空|清理|取消|停止|暂停|继续|下一|上一|增大|减小|提高|降低|切换|切到|换成|换到|静音|别|不要)");
    /** 「把 X 调到/设成/打开…」结构。 */
    private static final Pattern BA_COMMAND = Pattern.compile(
            "把.{0,12}(调到|调成|调为|调高|调低|调大|调小|设成|设为|设置|改成|改为|换成|切到|打开|关掉|关闭|开到|开启|静音|删|发给|发送|放大|缩小|分享)");
    /** 多步/指令性词。 */
    private static final Pattern MULTI = Pattern.compile("然后|接着|顺便|再帮|并且|同时|导航|打车|叫车|提醒我|告诉我");

    // ------------------------------------------------------------------ 句式

    private static final String VERBS = "停放|寄存|停|放|搁|锁|塞|藏|挂|存";
    /** 放置：可选前缀（我/我的/我家/我把/把）+ 主体 + 可选副词 + 动词 + 可选介词 + 地点。 */
    private static final Pattern P_PLACE = Pattern.compile(
            "^(?:我们家的|我家的|我家|我的|我把|我将|我|把|将)?(.{1,10}?)(?:就|已经|刚刚|刚|先|又|还|都)?(" + VERBS + ")(到了|在了|到|在|进了|进|于|回)?(.{1,24})$");
    /** 主体省略的停车句：「停在B2-17」「停到负二层了」→ 车。 */
    private static final Pattern P_PARK0 = Pattern.compile("^(?:我|我把车|把车)?(?:就|已经|刚刚|刚)?停(?:在|到)(.{1,24})$");
    /** X 在 Y。 */
    private static final Pattern P_AT = Pattern.compile(
            "^(?:我们家的|我家的|我家|我的|我)?(.{1,8}?)(?:就|还|都|现在|一直)?(?:在|放在)(.{1,24})$");
    /** 个人槽位白名单（长的在前）。 */
    private static final String SLOTS = "车牌号码|车牌号|车牌|酒店房间号|酒店房间|房间号|房号|柜子号|柜号|储物柜号|储物柜|寄存柜号|寄存柜|快递柜|门禁卡号|门禁卡|门禁|停车位|车位号|车位|工位号|工位|座位号|座位|行李牌号|行李牌";
    private static final Pattern P_SLOT = Pattern.compile(
            "^(?:我们家的|我家的|我家|我的|我)?(?:的)?(" + SLOTS + ")(?:号码|号)?(?:(是|为|在|:)(.{1,24})|([a-z0-9].{0,23}))$");
    /**
     * ★ int/1102 审计（「我女儿生日是5月3号」进了 AgentLoop）：<b>家人 / 自己的属性与偏好</b>陈述 = 记住（按 SLOT 存成属性）。
     * 只认「亲属称谓白名单 + 属性白名单」或「亲属 + 喜好动词」两种很窄的形状；问句（「我老婆喜欢吃什么」）已被 QUESTION 排除。
     */
    static final String KIN = "女儿|儿子|闺女|老婆|老公|媳妇|妻子|丈夫|爱人|对象|女朋友|男朋友|妈妈|老妈|妈|爸爸|老爸|爸|父亲|母亲|"
            + "爷爷|奶奶|外公|外婆|姥姥|姥爷|岳父|岳母|公公|婆婆|哥哥|姐姐|弟弟|妹妹|孩子|宝宝|大宝|二宝";
    static final String ATTR = "农历生日|阳历生日|生日|结婚纪念日|纪念日|血型|鞋码|衣服尺码|尺码|身高|体重|星座|属相|生肖|学校|班级|班主任|幼儿园";
    /** 「我女儿生日是5月3号」「我的生日是10月18号」「我们结婚纪念日是6月1日」「老婆鞋码37」。 */
    private static final Pattern P_KIN_ATTR = Pattern.compile(
            "^(我们的|我们|我的|我家|我)?(?:的)?(" + KIN + ")?(?:的)?(" + ATTR + ")(?:是|为|在|:)?(.{1,24})$");
    /** 「我女儿5月3号生日」。 */
    private static final Pattern P_KIN_BDAY = Pattern.compile(
            "^(?:我的|我家|我)?(?:的)?(" + KIN + ")(?:的)?(.{2,12}?)(?:过)?生日$");
    /** 「我老婆喜欢吃火锅」「我女儿对花生过敏」「我老公不吃香菜」。 */
    private static final Pattern P_KIN_PREF = Pattern.compile(
            "^(?:我的|我家|我)(" + KIN + ")(?:最|特别|比较|很)?(喜欢|爱吃|爱喝|不吃|不喝|不爱吃|讨厌|怕|对.{1,10}过敏|过敏)(.{0,20})$");
    private static final Pattern DATEISH = Pattern.compile("[0-9一二三四五六七八九十]+(月|号|日)|正月|腊月|初[一二三四五六七八九十]");

    /** 想存敏感信息的陈述句：「wifi密码是…」「我的银行卡号是…」。 */
    private static final Pattern P_SENSITIVE = Pattern.compile(
            "^(?:我们家的|我家的|我家|家里的|家里|公司的|我的|我)?(.{0,10}?)(密码|口令|验证码|pin码|卡号|身份证号|证件号|护照号|社保号|账号|帐号)(?:是|为|:)(.{1,40})$");

    /** 不能当「物品」的主体：人、代词、指令对象、抽象词。 */
    private static final Pattern BAD_SUBJECT = Pattern.compile(
            "^(?:我|你|他|她|它|我们|你们|他们|她们|咱|咱们|大家|妈|妈妈|爸|爸爸|老婆|老公|孩子|儿子|女儿|宝宝|朋友|同事|老板|人)$"
            + "|音量|亮度|声音|铃声|歌|音乐|视频|电影|节目|广播|闹钟|屏幕|wifi|蓝牙|热点|手电|模式|一首|首|天气|时间|心|东西$"
            + "|图标|文件夹|照片|图片|截图|应用|app|软件|壁纸|快捷方式|小组件|插件|联系人|号码|短信|消息|邮件|日程|备忘"
            // 主体里带否定/情态/言说词 = 不是在说一件物品（「确认卡不能藏在…」「ask 缓存命中」）
            + "|不能|不会|不要|没有|没法|可以|应该|^记|说的|让我|缓存|缓$");
    /** 个人物品白名单（「X 在 Y」只认这些；放置句不要求）。 */
    private static final Pattern PERSONAL_ITEM = Pattern.compile(
            "钥匙|护照|身份证|证件|户口本|房产证|结婚证|驾照|驾驶证|行驶证|社保卡|医保卡|银行卡|信用卡|卡包|钱包|"
            + "充电器|充电宝|充电线|数据线|耳机|眼镜|墨镜|手表|戒指|项链|首饰|伞|雨伞|药|遥控器|快递|包裹|行李|箱子|行李箱|"
            + "文件|合同|发票|收据|票|车票|机票|门票|u盘|硬盘|相机|平板|ipad|笔记本|电脑|书包|包|背包|外套|衣服|鞋|"
            + "车|电动车|自行车|摩托|头盔|工具|螺丝刀|印章|公章|存折|保修卡|说明书|备用机|手机|发卡|口罩|水杯|饭卡|工牌|门卡|车钥匙");
    /** 「像地点」：编码（B2-17、3楼、17号）或方位/场所词。 */
    private static final Pattern PLACE_CODE = Pattern.compile("[a-z]+\\d|\\d+[-_号层楼区排栋座室格柜位]|[负地下]+[一二三四五六七八九十\\d]+层?|[一二三四五六七八九十\\d]+[号层楼区排栋座室格柜]");
    private static final Pattern PLACE_WORD = Pattern.compile(
            "层|楼|号|区|排|格|柜|抽屉|箱|包|兜|口袋|桌|床|沙发|架|盒|袋|库|场|车位|房|室|厅|间|台|阳台|厨房|卫生间|洗手间|书房|卧室|客厅|玄关|"
            + "门口|门后|门边|鞋柜|衣柜|保险箱|公司|单位|办公室|工位|车里|车上|后备箱|家里|老家|酒店|宿舍|学校|那儿|那里|这儿|这里|那边|这边|"
            + "上面|上边|下面|下边|里面|里边|旁边|边上|附近|对面|楼下|楼上|角落|底下|后面|前面|左边|右边|"
            + "上$|里$|下$|旁$|边$|那$");
    /** 不是地点的宾语（事件/时间/动作残余）。 */
    private static final Pattern NOT_PLACE = Pattern.compile(
            "^(?:一下|下来|下去|起来|进来|出去|止|掉|着|好|一会|一边|心上|心里)|桌面|主屏|相册|收藏|歌单|云盘|网盘|微信|qq|邮箱|备忘录|日历|剪贴板|通知栏|后台|开会|上课|吃饭|见面|集合|聚餐|面试|值班|加班|出差|培训|上班|下班|睡觉|"
            + "^(?:今天|明天|后天|昨天|晚上|早上|下午|上午|中午)$"
            // 宾语里带谓语/症状 = 在描述现象，不是在说放哪（「手机放兜里老是自己亮」是诊断句）
            + "|老是|总是|一直|自己|经常|动不动|容易|突然|好像|时候|怎么|特别|太|很|就会|不会|不能|没法|没反应|"
            + "亮|黑屏|熄|响|震|卡顿|发热|发烫|烫|坏了|没电|没信号|没声音");

    // ------------------------------------------------------------------ 入口

    /** @return 命中返回 {@link Hit}；不是「要记住的事实」返回 null（原流程照旧）。 */
    public static Hit detect(String utterance) {
        if (utterance == null) return null;
        String raw = S2Text.clean(utterance);
        if (raw.length() < 4 || raw.length() > 48) return null;
        if (raw.indexOf('?') >= 0 || raw.indexOf('？') >= 0) return null;
        // ★ int/1102：带将来时间的事件句（「明天三点开会」「周五和客户吃饭」）是日程，不当事实存
        if (com.onesay.assistant.v2.sched.SchedEngine.looksLikeSchedule(raw)) return null;
        String k = stripTail(S2Text.key(raw));
        if (k.length() < 4) return null;
        if (QUESTION.matcher(k).find()) return null;
        if (COMMAND_HEAD.matcher(k).find()) return null;
        if (BA_COMMAND.matcher(k).find()) return null;
        if (MULTI.matcher(k).find()) return null;

        String content = stripTailRaw(raw);

        Matcher m = P_SENSITIVE.matcher(k);
        if (m.matches()) return new Hit(Kind.SENSITIVE_ATTEMPT, content, m.group(1) + m.group(2), "");

        // ★ int/1102：家人 / 自己的属性与偏好
        m = P_KIN_ATTR.matcher(k);
        if (m.matches() && (m.group(1) != null || m.group(2) != null)) {
            String val = valueOf(content, m.group(4));
            String who = m.group(2) != null ? m.group(2) : (m.group(1).startsWith("我们") ? "我们" : "我");
            if (val.length() > 0 && !val.matches("(多少|几号|哪天|什么时候).*")) return new Hit(Kind.SLOT, content, who + "的" + m.group(3), val);
        }
        m = P_KIN_BDAY.matcher(k);
        if (m.matches() && DATEISH.matcher(m.group(2)).find()) {
            return new Hit(Kind.SLOT, content, m.group(1) + "的生日", valueOf(content, m.group(2)));
        }
        m = P_KIN_PREF.matcher(k);
        if (m.matches() && (m.group(3).length() > 0 || m.group(2).contains("过敏"))) {
            return new Hit(Kind.SLOT, content, m.group(1) + "的喜好", valueOf(content, m.group(2) + m.group(3)));
        }

        // 「X 是 Y」个人槽位
        m = P_SLOT.matcher(k);
        if (m.matches()) {
            String val = valueOf(content, m.group(3) != null ? m.group(3) : m.group(4));
            if (val.length() > 0) {
                // 「门禁卡在包里」是放哪（place），「车位是B2-17」是属性（attr）
                if ("在".equals(m.group(2))) {
                    if (looksLikePlace(m.group(3))) return new Hit(Kind.AT, content, NoteParser.canonical(m.group(1)), val);
                } else {
                    return new Hit(Kind.SLOT, content, slotName(m.group(1)), val);
                }
            }
        }

        // 放置 / 停放
        m = P_PLACE.matcher(k);
        if (m.matches()) {
            String subj = NoteParser.canonical(m.group(1));
            String place = m.group(4);
            // 主体省略（「放在桌上」）不记；「停在B2-17」由下面的 P_PARK0 单独处理（→ 车）
            if (subj.length() > 0 && !BAD_SUBJECT.matcher(subj).find() && looksLikePlace(place)) {
                return new Hit(Kind.PLACE, content, subj, valueOf(content, place));
            }
        }

        m = P_PARK0.matcher(k);
        if (m.matches() && looksLikePlace(m.group(1))) {
            return new Hit(Kind.PLACE, content, "车", valueOf(content, m.group(1)));
        }

        // X 在 Y（个人物品白名单）
        m = P_AT.matcher(k);
        if (m.matches()) {
            String subj = NoteParser.canonical(m.group(1));
            String place = m.group(2);
            if (subj.length() > 0 && !BAD_SUBJECT.matcher(subj).find() && PERSONAL_ITEM.matcher(subj).find()
                    && looksLikePlace(place)) {
                return new Hit(Kind.AT, content, subj, valueOf(content, place));
            }
        }
        return null;
    }

    static boolean looksLikePlace(String place) {
        if (place == null) return false;
        String p = place.trim();
        if (p.length() == 0 || p.length() > 24) return false;
        if (NOT_PLACE.matcher(p).find()) return false;
        return PLACE_CODE.matcher(p).find() || PLACE_WORD.matcher(p).find();
    }

    /** key 形态的值（小写、去标点）尽量换回原文里的写法（保留「B2-17」大小写）。 */
    private static String valueOf(String content, String keyValue) {
        if (keyValue == null) return "";
        String kv = keyValue.trim();
        String compact = S2Text.compact(content);
        int i = compact.toLowerCase(java.util.Locale.ROOT).lastIndexOf(kv);
        String v = i >= 0 ? compact.substring(i, i + kv.length()) : kv;
        return v.replaceAll("^[：:，,]+", "").trim();
    }

    private static String slotName(String s) {
        if (s == null) return "";
        if (s.startsWith("车牌")) return "车牌";
        if (s.startsWith("酒店房间") || s.startsWith("房间") || s.equals("房号")) return "房间号";
        if (s.startsWith("车位") || s.equals("停车位")) return "车位";
        if (s.startsWith("门禁")) return "门禁";
        return s.replaceAll("号$", "");
    }

    private static String stripTail(String k) {
        return k.replaceAll("(了吧|了哦|了啊|了呀|了哈|啦|了|哦|啊|呀|哈|哟)+$", "");
    }

    private static String stripTailRaw(String raw) {
        return raw.replaceAll("[，,。.！!~～…；;]+$", "").trim();
    }
}
