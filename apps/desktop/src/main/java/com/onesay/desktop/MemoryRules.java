package com.onesay.desktop;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [fix-1123-c] 电脑端长期记忆的规矩：只记用户自己明确说过、以后还成立的事（身份、偏好、习惯、设备名、重要的人和日期）。
 * 某个任务的进度、还没回答的问题、没做完的事、开关 / 权限状态、失败原因、程序怎么启动的、测试过程，一律不进长期记忆
 * （2026-11-22 远程预跑里，「周末文件夹仍未建」「以管理员身份运行」「已开启按目标操作」这类被记成长期事实，后面的任务拿它说错话）。
 *
 * <p>做法两层：①给模型的提示里写清只记什么、不记什么，而且只给它看用户自己说的话；②模型新加的每一行再过一遍 {@link #transientWhy}，
 * 像进度 / 状态的直接丢掉。旧记忆里已有的行不自动删（那是用户的数据），用户在「记忆」里一条条删（{@link #deleteLine}）。
 */
final class MemoryRules {
    private MemoryRules() {}

    /** 给模型的系统提示。 */
    static String prompt(int maxChars) {
        return "你负责维护一份关于用户的长期记忆。只记用户自己明确说过、以后一直成立的事：身份、偏好、习惯、常用设备和它们的名字、重要的人和日期、长期在做的事。"
                + "下面这些一律不记：某个任务做到哪了、哪件事还没做完 / 还没建好 / 待确认、还没回答的问题、某个开关或权限现在开着还是关着、"
                + "这次为什么失败或被拒、程序是怎么启动的（比如以管理员身份运行）、测试过程、一次性的请求和闲聊、密码和 Key。"
                + "只根据「用户说的话」记；助手的猜测和任务结果不算用户说的。旧记忆里被用户新说法推翻的要改掉。"
                + "输出新的完整记忆，每行一条，以「- 」开头，总共不超过 " + maxChars + " 字；没有值得记的就原样输出旧记忆。只输出记忆本身。";
    }

    /** 像「进度 / 状态 / 没做完」的写法。 */
    static final Pattern PENDING = Pattern.compile("尚未|仍未|还没(有)?(建|做|弄|完成|确认|回答|回复|决定|处理|装|开)|没建|未建|未完成|未确认|未解决|待确认|待定|待处理|待回复|悬而未决|还在等|正在(做|处理|进行|等待|运行)|进行中|下一步|下次再|稍后|没来得及");
    static final Pattern STATE = Pattern.compile("(已|已经|目前|现在|当前)(开启|打开|关闭|关掉|关上|启用|禁用|允许|授权|批准|拒绝)|开关.{0,8}(开着|关着|开启|关闭|打开|关掉|是开|是关)|(权限|开关)(已|已经|是)?(开|关|开启|关闭|打开)|以管理员身份|管理员权限运行|没人批准|超时|报错|出错了|失败了|被拒(绝)?了?");
    static final Pattern SESSION = Pattern.compile("这次|本次|刚才|刚刚|上一轮|这一轮|本轮|今晚测试|测试时|测试中|测试数据|任务卡|这个任务|上个任务");

    /** 这一行看起来是任务进度 / 状态 / 一次性的事：返回原因；像长期事实返回 ""。 */
    static String transientWhy(String line) {
        String s = line == null ? "" : line.trim();
        if (s.isEmpty()) return "空行";
        if (PENDING.matcher(s).find()) return "没做完 / 待确认的事";
        if (STATE.matcher(s).find()) return "开关、权限或这次的结果";
        if (SESSION.matcher(s).find()) return "只跟这次任务有关";
        return "";
    }

    /** 记忆文本拆成行（去掉空行，保留原样）。 */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String l : text.split("\\r?\\n")) if (!l.trim().isEmpty()) out.add(l);
        return out;
    }

    static String norm(String l) {
        return l == null ? "" : l.trim().replaceFirst("^[-*•·]\\s*", "").trim();
    }

    /**
     * 模型给的新记忆过一遍：旧记忆里本来就有的行照留；新加的行像进度 / 状态的丢掉（dropped 收丢掉的行）。
     */
    static String filterNew(String old, String proposed, List<String> dropped) {
        Set<String> had = new LinkedHashSet<>();
        for (String l : lines(old)) had.add(norm(l));
        StringBuilder b = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (String l : lines(proposed)) {
            String n = norm(l);
            if (n.isEmpty() || !seen.add(n)) continue;
            if (n.matches("[（(]?(空|无|没有|暂无|没有值得记的[^）)]*)[）)]?")) continue; // 模型写的「（空）」不当一条记忆
            if (!had.contains(n) && !transientWhy(n).isEmpty()) {
                if (dropped != null) dropped.add(n);
                continue;
            }
            if (b.length() > 0) b.append('\n');
            b.append(l.trim().startsWith("- ") ? l.trim() : "- " + n);
        }
        return b.toString();
    }

    /** 只取用户说的话给模型（助手的回答、任务结果、命令输出都不给）。 */
    static boolean userLine(java.util.Map<String, Object> m) {
        if (m == null || !"user".equals(m.get("role"))) return false;
        Object via = m.get("via");
        return !"cmd".equals(via) && !"error".equals(via) && !"step".equals(via) && !"task".equals(via);
    }

    /** 删掉一条（按去掉「- 」后的文字比，全部同样的都删）；没找到返回 null。 */
    static String deleteLine(String text, String line) {
        String want = norm(line);
        if (want.isEmpty()) return null;
        List<String> keep = new ArrayList<>();
        boolean hit = false;
        for (String l : lines(text)) {
            if (norm(l).equals(want)) { hit = true; continue; }
            keep.add(l);
        }
        return hit ? String.join("\n", keep) : null;
    }
}
