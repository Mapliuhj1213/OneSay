package com.onesay.assistant.v2.phone;

import java.util.regex.Pattern;

/**
 * ★ r2/fast-screen：敏感步骤判定（纯 Java）。命中 → 这一步<b>不执行</b>，任务暂停，灵动岛展开等用户确认。
 * 用户在岛上点「允许」只放行这一步（单次），下一次照样再问。
 *
 * <p>★ fast-screen-v2：只拦<b>真正提交</b>的那一下 —— 付款 / 下单、发送 / 发布 / 提交、获取或提交验证码、
 * 删除 / 清除 / 卸载 / 重置、登录 / 授权按钮，以及往密码 / 验证码框里填字。
 * <b>导航项不拦</b>：设置里的「密码与安全」「隐私和安全」「恢复出厂设置」「支付管理」这类只是进入下一页的条目，
 * 真要提交的按钮（「重置」「全部删除」「立即支付」）在下一页，到那时再拦。
 *
 * <p>判法：
 * <ol>
 *   <li>名字取元素自己的文字（没字的可点容器取的是「标题 · 摘要」，只看标题那段）；</li>
 *   <li>像导航条目 → 放行：带「与 / 和 / 及」连接两个名词、以「设置 / 管理 / 中心 / 选项 / 详情 / 帮助 / 安全 / 隐私 / 权限 / 账户 / 方式 / 记录…」收尾、
 *       超过 {@link #MAX_BUTTON_LEN} 字（按钮没这么长）、或者是开关；</li>
 *   <li>否则名字里有提交动词 → 拦；资源 id 里有 pay / send / submit / delete / login / verify 这类词（按单词边界）也拦。</li>
 * </ol>
 */
public final class SensitiveGuard {

    private SensitiveGuard() {}

    /** 按钮文字一般很短；超过这个长度的多半是列表条目 / 说明文字。 */
    static final int MAX_BUTTON_LEN = 10;

    static final Pattern PAY = Pattern.compile("支付|付款|付钱|买单|结算|下单|提交订单|立即购买|购买|确认订单|转账|充值|打赏|发红包|收银|pay\\b|checkout|purchase|buy\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern LOGIN = Pattern.compile("^(立即|确认|马上|一键)?(登录|登 录|注册|授权)$|同意并继续|一键登录|授权登录|允许授权|退出登录|切换账号|^sign ?in$|^log ?in$|^sign ?up$|^authorize$", Pattern.CASE_INSENSITIVE);
    static final Pattern VERIFY = Pattern.compile("获取验证码|发送验证码|重新获取|重新发送验证码|^(立即|确认|提交|去)?验证$|人脸识别|刷脸|^verify$|^confirm code$", Pattern.CASE_INSENSITIVE);
    static final Pattern SEND = Pattern.compile("^(确认|立即|马上)?(发送|发 送|发布|提交|回复|评论|转发|发表)$|^(send|post|submit|reply|publish)$", Pattern.CASE_INSENSITIVE);
    static final Pattern DESTRUCT = Pattern.compile("删除|清除数据|清除缓存|清空|卸载|恢复出厂|重置|格式化|注销|移除|delete|uninstall|reset|erase|remove", Pattern.CASE_INSENSITIVE);
    static final Pattern SECRET_FIELD = Pattern.compile("password|passwd|pwd|密码|验证码|captcha|otp|verify_?code|sms_?code|cvv|身份证|银行卡", Pattern.CASE_INSENSITIVE);
    /** 资源 id 按单词边界（下划线 / 驼峰拆开后）判。 */
    static final Pattern RID_COMMIT = Pattern.compile("(^|_)(pay|checkout|purchase|buy|send|submit|publish|post|delete|del|remove|uninstall|login|signin|verify|captcha|otp)(_|$)", Pattern.CASE_INSENSITIVE);
    static final Pattern RID_NAV = Pattern.compile("(^|_)(title|summary|item|entry|menu|tab|header|setting|settings|pref|preference|list|row|icon)(_|$)", Pattern.CASE_INSENSITIVE);
    /**
     * ★ fast-screen-v3（2026-10-31 产品决定）：资源 id <b>含</b>这些词（子串，不按单词边界）的节点，
     * 即使文字像导航条目（多长都一样）也不放行。
     */
    static final Pattern RID_STRONG = Pattern.compile("send|pay|submit|confirm|login|verify|delete", Pattern.CASE_INSENSITIVE);
    /** 导航条目：名词连接 / 名词收尾。 */
    static final Pattern NAV_JOIN = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z]+(与|和|及|&)[\\u4e00-\\u9fa5A-Za-z]+");
    static final Pattern NAV_TAIL = Pattern.compile("(设置|管理|中心|选项|详情|帮助|说明|安全|隐私|权限|账户|帐户|账号|帐号|方式|记录|历史|列表|服务|信息|偏好|模式|功能|向导)$");

    /**
     * ★ approve-not-block 硬规则 ①：付款 / 转账的最后一下（最终支付 / 确认转账 / 提交订单并支付）只能你亲手点 ——
     * 机器人不代点，问过你、你说了「允许」也不点（{@link com.onesay.assistant.v2.pclink.LinkPolicy#finalPayLabel}）。前面的步骤（选商品、填地址、进收银台）照常确认。
     * @return null = 不是；否则是给用户看的一句话。
     */
    public static String userMustTap(PhonePlan.Step s, UiTree screen) {
        if (s == null || screen == null) return null;
        if (!"tap".equals(s.op) && !"long".equals(s.op)) return null;
        UiTree.El e = target(s, screen);   // ★ sec-audit-1118 S2：0–999 坐标点也按坐标下面的元素判（以前 s.id < 0 直接放行）
        if (e == null || e.edit) return null;
        if (!com.onesay.assistant.v2.pclink.LinkPolicy.finalPayLabel(e.label)) return null;
        String n = e.label.length() > 16 ? e.label.substring(0, 16) + "…" : e.label;
        return "付款 / 转账的最后一下（「" + n + "」）只能你自己点";
    }

    /**
     * ★ sec-audit-1118 S2：这一步实际会落到的元素。按编号（@eN）的照旧；没给编号、只给了 0–999 坐标（mika.dosteps）的，
     * 按与 {@code FastScreenDriver.tapXY} 同一换算（{@link com.onesay.assistant.plug.screen.core.UiElement#denormalize}）找坐标下面的元素：
     * 优先可点的，同类里取面积最小的（最内层的按钮）。以前坐标点三道闸（付款亲手点 / 敏感步骤 / 污点确认）都拿不到元素，一律放行。
     */
    public static UiTree.El target(PhonePlan.Step s, UiTree screen) {
        if (s == null || screen == null) return null;
        if (s.id >= 0) return screen.find(s.id);
        if (s.x < 0 || s.y < 0) return null;
        int px = com.onesay.assistant.plug.screen.core.UiElement.denormalize(s.x, screen.screenW);
        int py = com.onesay.assistant.plug.screen.core.UiElement.denormalize(s.y, screen.screenH);
        UiTree.El best = null;
        long bestArea = Long.MAX_VALUE;
        boolean bestClick = false;
        for (UiTree.El e : screen.els) {
            if (e == null || px < e.l || px >= e.r || py < e.t || py >= e.b) continue;
            long area = (long) (e.r - e.l) * (long) (e.b - e.t);
            if (best == null || (e.click && !bestClick) || (e.click == bestClick && area < bestArea)) {
                best = e;
                bestArea = area;
                bestClick = e.click;
            }
        }
        if (best == null && s.target != null && s.target.trim().length() > 0) best = fromTarget(s);   // [exec-ground-1124]
        return best;
    }

    /**
     * [exec-ground-1124] 截图模式：坐标下面没有任何节点（树读不到）时，按模型写的 target 当按钮名过同一套闸
     * （付款亲手点 / 敏感确认 / 读过外部内容后发送确认）。以前这种情况三道闸都拿不到元素、一律放行。
     */
    static final Pattern TARGET_KEY = Pattern.compile("确认支付|立即支付|确认付款|去支付|提交订单|立即购买|确认订单|确认转账|免密支付|付款|支付|下单|结算|买单|转账|充值|发红包|"
            + "确认发送|发送验证码|获取验证码|发送|发布|发表|提交|回复|评论|转发|删除|清空|卸载|注销|退出登录|一键登录|授权登录|登录|注册|授权|"
            + "pay|checkout|purchase|send|submit|post|publish|delete|login|sign in", Pattern.CASE_INSENSITIVE);

    static UiTree.El fromTarget(PhonePlan.Step s) {
        UiTree.El e = new UiTree.El();
        String t = s.target.trim();
        int q1 = t.indexOf('「'), q2 = t.lastIndexOf('」');   // 「底部「发送」按钮」→ 发送
        if (q1 >= 0 && q2 > q1 + 1) t = t.substring(q1 + 1, q2);
        t = t.replaceAll("(按钮|按键|图标|这个|那个)$", "").trim();
        java.util.regex.Matcher m = TARGET_KEY.matcher(t);   // 「右下角的发送」这类描述：取出里面的动作词，按按钮名判
        e.label = m.find() ? m.group() : t;
        e.cls = "android.widget.Button";
        e.click = true;
        e.id = -1;
        return e;
    }

    /** @return null = 不敏感；否则是给用户看的原因（「这一步是付款」）。 */
    public static String check(PhonePlan.Step s, UiTree screen) {
        UiTree.El e = target(s, screen);   // ★ sec-audit-1118 S2：坐标点同样判
        if (s.op.equals("type")) {
            if (e != null && (e.password || SECRET_FIELD.matcher(e.rid + " " + e.label).find())) return "要往密码 / 验证码框里填写";
            return null;
        }
        if (s.op.equals("key") && "enter".equals(s.key)) return null;   // 回车提交搜索：常见、无外部影响；发送类在按钮上拦
        if (!(s.op.equals("tap") || s.op.equals("long"))) return null;
        if (e == null) return null;
        return checkElement(e);
    }

    /** 单个元素会不会是「提交」类按钮。 */
    static String checkElement(UiTree.El e) {
        // ★ sec-audit-1118 H3（严格）：立即开通 / 自动续费 / 订阅 / 确认收货 / Place your order / Buy with 1-Click / 签署 / 同意授权 / 立即付 / Agree and pay
        //   先于导航 / 开关判定、在归一化后的整个标题上找（多长都拦；「订阅」单独出现时只取消「超长当导航」）。
        String shown = title(e.label);
        com.onesay.assistant.plug.action.core.RiskGate.Irrev strict = com.onesay.assistant.plug.action.core.RiskGate.strictIrreversible(shown);
        if (e.edit) strict = null;   // 点进输入框（「签署人姓名」）不是提交
        if (strict != null && !com.onesay.assistant.plug.action.core.RiskGate.strictSoftOnly(shown)) return reason(strictKind(strict), shown);
        String name = com.onesay.assistant.v2.security.SafetyText.fold(shown);   // ★ sec-audit-1118 H3：同形字 / U+034F / 零宽 / 繁体先折掉再判
        boolean softStrict = strict != null;
        String rid = splitRid(e.rid);
        boolean ridCommit = rid.length() > 0 && RID_COMMIT.matcher(rid).find() && !RID_NAV.matcher(rid).find();
        if (name.length() == 0 && !ridCommit) return null;
        if (e.check) return null;                       // 开关：切换设置，不是提交
        if (name.length() > 0 && (softStrict ? isNavigationShort(name) : isNavigation(name))) {
            // ★ fast-screen-v3：导航放行有两个例外 ——
            //   ① 资源 id 含 send/pay/submit/confirm/login/verify/delete → 无论文字多长都拦；
            //   ② 节点是可点击的 Button 类 → 不当导航条目，照常按提交动词判（多长都一样）。
            if (ridCommit) return reason(ridKind(rid), name);
            if (strongRid(e.rid)) return reason(strongKind(e.rid), name);
            if (!isClickableButton(e)) return null;
        }
        if (name.length() > 0) {
            if (softStrict) return reason("付款 / 下单", shown);
            if (PAY.matcher(name).find()) return reason("付款 / 下单", name);
            if (SEND.matcher(name).find()) return reason("发送 / 提交", name);
            if (VERIFY.matcher(name).find()) return reason("验证码 / 身份验证", name);
            if (DESTRUCT.matcher(name).find()) return reason("删除 / 清除数据", name);
            if (LOGIN.matcher(name).find()) return reason("登录 / 授权", name);
        }
        if (ridCommit) return reason(ridKind(rid), name.length() > 0 ? name : e.rid);
        return null;
    }

    /** 资源 id（去掉包名前缀）是否含强提交词。 */
    static boolean strongRid(String rid) {
        if (rid == null || rid.length() == 0) return false;
        return RID_STRONG.matcher(UiTree.shortRid(rid)).find();
    }

    static String strongKind(String rid) {
        String r = UiTree.shortRid(rid).toLowerCase();
        if (r.contains("pay")) return "付款 / 下单";
        if (r.contains("send") || r.contains("submit") || r.contains("confirm")) return "发送 / 提交";
        if (r.contains("delete")) return "删除 / 清除数据";
        if (r.contains("login")) return "登录 / 授权";
        return "验证码 / 身份验证";
    }

    /** 可点击的 Button 类（android.widget.Button / ImageButton / AppCompatButton / MaterialButton…）。 */
    static boolean isClickableButton(UiTree.El e) {
        return e.click && e.cls != null && e.cls.toLowerCase().contains("button");
    }

    /** 像「进入下一页」的条目吗。 */
    static boolean isNavigation(String name) {
        String n = name.replace(" ", "");
        if (n.length() > MAX_BUTTON_LEN) return true;
        return isNavigationShort(n);
    }

    /** 只看名词连接 / 名词收尾，不因为长就当导航（★ sec-audit-1118 H3：带「订阅」的长文字）。 */
    static boolean isNavigationShort(String name) {
        String n = name.replace(" ", "");
        if (NAV_JOIN.matcher(n).find()) return true;
        return NAV_TAIL.matcher(n).find();
    }

    static String strictKind(com.onesay.assistant.plug.action.core.RiskGate.Irrev k) {
        if (k == com.onesay.assistant.plug.action.core.RiskGate.Irrev.SUBMIT) return "签署 / 授权";
        return "付款 / 下单";
    }

    /** 「标题 · 摘要」只取标题；去掉括号里的描述。 */
    static String title(String label) {
        if (label == null) return "";
        String s = label;
        int k = s.indexOf(" · ");
        if (k >= 0) s = s.substring(0, k);
        k = s.indexOf('（');
        if (k > 0) s = s.substring(0, k);
        return s.trim();
    }

    /** btnSendMsg → btn_send_msg。 */
    static String splitRid(String rid) {
        if (rid == null) return "";
        String r = UiTree.shortRid(rid);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(r.charAt(i - 1))) b.append('_');
            b.append(c == '-' || c == '.' ? '_' : Character.toLowerCase(c));
        }
        return b.toString();
    }

    static String ridKind(String rid) {
        if (rid.matches(".*(^|_)(pay|checkout|purchase|buy)(_|$).*")) return "付款 / 下单";
        if (rid.matches(".*(^|_)(send|submit|publish|post)(_|$).*")) return "发送 / 提交";
        if (rid.matches(".*(^|_)(delete|del|remove|uninstall)(_|$).*")) return "删除 / 清除数据";
        if (rid.matches(".*(^|_)(login|signin)(_|$).*")) return "登录 / 授权";
        return "验证码 / 身份验证";
    }

    static String reason(String kind, String name) {
        return "这一步是" + kind + "（" + UiTree.clip(name, 12) + "）";
    }
}
