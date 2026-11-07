package com.onesay.assistant.v2.router;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 Ask 智能问答与日常即时查询引擎。
 *
 * 核心设计准则（手机端即时问答黄金法则）：
 * 1. 【毫秒直出】：本地规则与实用算法直接在设备端 0ms 给出，无需网络与大模型等待；
 * 2. 【严格控制在 50~100 字】：拒绝长篇大论，直接给出核心要点，精炼、高效，完美契合手机屏幕；
 * 3. 【覆盖高频移动场景】：时间、日期、天气、四则算术运算、实用度量衡换算、紧急热线与常见生活常识。
 */
public class AskEngine {

    private static final Pattern MATH_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(加|\\+|减|\\-|乘以|乘|\\*|x|除以|除|/)\\s*(\\d+(?:\\.\\d+)?)(?:等于多少|是多少|等于几)?$");

    /**
     * 判断并尝试回答常见日常问答与查询。
     *
     * @param query 用户输入的转写或文本
     * @return 答案文本；若不属于问答则返回 null
     */
    public static String tryAnswer(String query) {
        if (query == null) return null;
        String raw = query.trim();
        String q = raw.replaceAll("[\\s\\p{Punct}，。？！、：；“”‘’\"'`]", "").toLowerCase();
        if (q.length() == 0) return null;

        // 1. 本地简易四则运算计算器（毫秒算毕直出）
        String mathRes = tryMathCalculate(raw);
        if (mathRes != null) return mathRes;

        // 2. 时间查询
        if (q.equals("现在几点") || q.equals("几点了") || q.equals("当前时间")
                || q.equals("报时") || q.equals("现在时间") || q.equals("几点")
                || q.equals("看下时间") || q.equals("看一下时间") || q.equals("时间")
                || q.equals("现在几点了") || q.equals("现在几点啦") || q.equals("几点了呀")
                || q.equals("现在啥时间") || q.equals("现在的时间")) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.CHINA);
            String time = sdf.format(new Date());
            return "现在是北京时间 " + time + "。";
        }

        // 2b. ★ audit-J 4（Gemini 真机 TIME-02）：「40分钟后是几点 / 两小时后是几点 / 一个半小时后几点」本地算（与相对闹钟共用解析）
        String relRes = tryRelativeTime(q);
        if (relRes != null) return relRes;

        // 3. 日期与星期查询
        if (q.equals("今天几号") || q.equals("今天周几") || q.equals("今天星期几")
                || q.equals("今天是几号") || q.equals("查看今天日期") || q.equals("今天是几月几日")
                || q.equals("今天日期") || q.equals("几月几号") || q.equals("几月几日")
                || q.equals("今天是什么日子") || q.equals("日期")
                || q.equals("今天几号了") || q.equals("今天星期几呀") || q.equals("今天周几啊")
                || q.equals("今天日期是啥") || q.equals("今天是星期几")) {
            Calendar cal = Calendar.getInstance();
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DAY_OF_MONTH);
            String[] weekDays = { "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六" };
            int wIdx = cal.get(Calendar.DAY_OF_WEEK) - 1;
            if (wIdx < 0) wIdx = 0;
            String week = weekDays[wIdx];

            String festival = (month == 10 && day == 1) ? "，国庆节" : "";
            return "今天是 " + year + "年" + month + "月" + day + "日 " + week + festival + "。";
        }

        // 3b. 一般时间问句（几月几号星期几 / 明天星期几 / 后天几号 / 几点几分 / 今年哪年 / 上午还是下午 / 这周五几号）
        //   本地按设备时区直答（上面两条精确表先答，口径不变）
        String timeRes = TimeAnswer.answer(raw);
        if (timeRes != null) return timeRes;

        // 4. 天气查询
        // ★ 缺陷修复（2026-10-02）：此处原先返回**写死的假天气**
        //   （"今日多云转晴，气温 18℃~26℃"），无论真实天气如何、无论问哪个城市，
        //   答案都一样。这比答不出来更糟——用户在不知情下把假数据当真实天气用。
        //   天气的时效性与地域性决定它必须拿外部数据，因此本地一律不答，
        //   交回上层由 L2（联网问答通道）处理；L2 拿不到实时数据时会如实说明，
        //   而不是编一个数字。
        //   注意：这里刻意**不**返回任何兜底文案，必须返回 null 让调用方继续往下路由。

        // 5. 身份与名称问答
        if (q.equals("你是谁") || q.equals("你叫什么名字") || q.equals("你叫什么")
                || q.equals("你是哪个") || q.equals("介绍一下你自己") || q.equals("你是什么")) {
            return "我是您的专属 OneSay 智能语音助手，支持全局系统控制、硬件调度与快捷问答。";
        }

        // 6. 实用生活度量衡换算
        String unitRes = tryUnitConversion(q);
        if (unitRes != null) return unitRes;

        // 7. 常用紧急与民生服务热线
        String phoneRes = tryHotlineQuery(q);
        if (phoneRes != null) return phoneRes;

        // 8. 常见生活与健康常识精炼问答（严格 50~90 字）
        String lifeRes = tryLifeTips(q);
        if (lifeRes != null) return lifeRes;

        // 9. 常见日常问候与对话
        if (q.equals("你好") || q.equals("您好") || q.equals("嗨") || q.equals("早上好")
                || q.equals("下午好") || q.equals("晚上好") || q.equals("hello") || q.equals("hi")) {
            return "您好！很高兴为您服务，请问有什么可以帮您？";
        }

        if (q.equals("谢谢") || q.equals("谢谢你") || q.equals("感谢")) {
            return "不客气，随时乐意为您效劳！";
        }

        if (q.equals("再见") || q.equals("退下吧") || q.equals("休眠吧") || q.equals("没事了")) {
            return "好的，有需要随时叫我。";
        }

        if (q.equals("讲个笑话") || q.equals("讲笑话") || q.equals("说个笑话")) {
            return "为什么程序员总是分不清万圣节和圣诞节？因为 Oct 31 == Dec 25。";
        }

        return null;
    }

    static final Pattern REL_TIME = Pattern.compile(
            "^(?:那|那么|请问)?(?:再过|过)?(.{1,6}?)(分钟|分|个小时|小时|个钟头|钟头)(?:以后|之后|后)(?:是|就是|会是)?(?:几点钟?|什么时候|什么时间|几点了)(?:了|呀|啊|呢)?$");

    /** 「40分钟后是几点」→「40分钟后是19:24。」；跨天 →「…是明天 0:10。」。不是这类问句 = null。 */
    static String tryRelativeTime(String q) {
        Matcher m = REL_TIME.matcher(q);
        if (!m.matches()) return null;
        long minutes = FastIntents.relMinutes(m.group(1), m.group(2));
        if (minutes < 0) return null;
        long now = FastIntents.nowMs();
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(now);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(now + minutes * 60000L);
        boolean nextDay = b.get(Calendar.YEAR) != a.get(Calendar.YEAR) || b.get(Calendar.DAY_OF_YEAR) != a.get(Calendar.DAY_OF_YEAR);
        String hm = b.get(Calendar.HOUR_OF_DAY) + ":" + String.format(Locale.US, "%02d", b.get(Calendar.MINUTE));
        return m.group(1) + m.group(2) + "后是" + (nextDay ? "明天 " : "") + hm + "。";
    }

    /**
     * 简易四则运算解析（例如 "35乘18等于多少", "1024除以16"）
     */
    private static String tryMathCalculate(String raw) {
        String clean = raw.replaceAll("[，。？！?]", "").trim();
        Matcher m = MATH_PATTERN.matcher(clean);
        if (!m.matches()) return null;

        try {
            double n1 = Double.parseDouble(m.group(1));
            String op = m.group(2);
            double n2 = Double.parseDouble(m.group(3));
            double result = 0;
            String opSymbol = "+";

            if (op.contains("+") || op.contains("加")) {
                result = n1 + n2;
                opSymbol = "+";
            } else if (op.contains("-") || op.contains("减")) {
                result = n1 - n2;
                opSymbol = "-";
            } else if (op.contains("*") || op.contains("乘") || op.contains("x")) {
                result = n1 * n2;
                opSymbol = "×";
            } else if (op.contains("/") || op.contains("除")) {
                if (n2 == 0) return "除数不能为 0 噢。";
                result = n1 / n2;
                opSymbol = "÷";
            } else {
                return null;
            }

            // 格式化输出整齐的算式
            String resStr = (result == (long) result) ? String.valueOf((long) result) : String.format(Locale.CHINA, "%.2f", result);
            String n1Str = (n1 == (long) n1) ? String.valueOf((long) n1) : String.valueOf(n1);
            String n2Str = (n2 == (long) n2) ? String.valueOf((long) n2) : String.valueOf(n2);
            return "计算结果：" + n1Str + " " + opSymbol + " " + n2Str + " = " + resStr + "。";
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * 常用生活单位换算
     */
    private static String tryUnitConversion(String q) {
        if (q.contains("一斤") && (q.contains("克") || q.contains("千克") || q.contains("多少"))) {
            return "1 斤等于 500 克（即 0.5 千克）。1 公斤等于 2 斤（1000 克）。";
        }
        if (q.contains("一公斤") && (q.contains("斤") || q.contains("多少"))) {
            return "1 公斤等于 2 斤，等于 1000 克（1 千克）。";
        }
        if (q.contains("一公里") && (q.contains("米") || q.contains("里") || q.contains("多少"))) {
            return "1 公里（千米）等于 1000 米，约等于 2 华里。";
        }
        if (q.contains("一英里") && (q.contains("公里") || q.contains("千米") || q.contains("米") || q.contains("多少"))) {
            return "1 英里（mile）约等于 1.609 公里（1609 米）。";
        }
        if (q.contains("一磅") && (q.contains("斤") || q.contains("克") || q.contains("多少"))) {
            return "1 磅（lb）约等于 0.4536 千克（453.6 克），折合约 0.907 斤。";
        }
        if (q.contains("一寸") && (q.contains("厘米") || q.contains("毫米") || q.contains("多少"))) {
            return "中国市寸：1 寸约合 3.33 厘米；若是英制英寸（inch），1 英寸等于 2.54 厘米。";
        }
        return null;
    }

    /**
     * 常用紧急与便民服务热线
     */
    private static String tryHotlineQuery(String q) {
        if (q.contains("报警电话") || q.contains("匪警电话")) {
            return "全国统一公安报警求助电话为 110。";
        }
        if (q.contains("火警电话") || q.contains("火警")) {
            return "全国统一消防火警报警电话为 119。";
        }
        if (q.contains("急救电话") || q.contains("救护车电话")) {
            return "全国统一医疗急救求助电话为 120。";
        }
        if (q.contains("交通事故电话") || q.contains("交警电话")) {
            return "全国交通事故报警服务电话为 122。";
        }
        if (q.contains("消费者投诉") || q.contains("维权电话") || q.contains("315电话")) {
            return "全国市场监管与消费者投诉举报热线为 12315。";
        }
        if (q.contains("查话费") || q.contains("运营商电话")) {
            return "主要客服电话：中国移动 10086、中国联通 10010、中国电信 10000。";
        }
        return null;
    }

    /**
     * 常见生活与健康常识精炼回答（严格限制在 50~90 字，直达痛点）
     */
    private static String tryLifeTips(String q) {
        if (q.contains("手机发烫") || q.contains("手机很热")) {
            return "建议清理后台高耗电应用、拔掉充电器并在通风处静置片刻；避免长时间边充电边玩大型游戏或持续开启便携热点。";
        }
        if (q.contains("感冒了") && (q.contains("吃什么") || q.contains("怎么办"))) {
            return "感冒初期建议多喝温水、清淡饮食、充分休息；若咽痛发热可遵医嘱服用对乙酰氨基酚等退热药，高热不退请及时就医。";
        }
        if (q.contains("西红柿炒鸡蛋") || q.contains("番茄炒蛋")) {
            return "鸡蛋打散热油炒熟盛出；另起锅将番茄块煸炒出汁，加少许白糖提鲜；倒入炒蛋翻炒均匀，撒少许盐和葱花出锅，酸甜鲜美。";
        }
        if (q.contains("中国首都") || q.contains("我国首都")) {
            return "中华人民共和国的首都位于北京，是全国的政治中心、文化中心、国际交往中心与科技创新中心。";
        }
        if (q.contains("光速") && (q.contains("多少") || q.contains("多快"))) {
            return "光在真空中传播的速度约为每秒 299,792,458 米，通常简记为 30 万公里/秒，是宇宙中信息传递的极限速度。";
        }
        return null;
    }
}
