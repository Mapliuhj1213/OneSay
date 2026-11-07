package com.onesay.assistant.v2.diag;

import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.ShellRunner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** [diag] 测试用假 root shell：模拟 settings 表、deviceidle 白名单与 media volume。记录全部命令。 */
final class FakeSettingsShell implements ShellRunner {

    final Map<String, String> settings = new LinkedHashMap<String, String>();
    final Set<String> idle = new LinkedHashSet<String>();
    final Map<String, Integer> volume = new LinkedHashMap<String, Integer>();
    final List<String> cmds = new ArrayList<String>();
    /** 模拟「系统把外部写入改回」（如 Android 8 的勿扰）：这些键的 put 不生效。 */
    final Set<String> sticky = new LinkedHashSet<String>();
    boolean down;

    private static final Pattern GET = Pattern.compile("^settings get (\\w+) ([\\w.]+)");
    private static final Pattern PUT = Pattern.compile("^settings put (\\w+) ([\\w.]+) '?([^' ]+)'?");
    private static final Pattern DEL = Pattern.compile("^settings delete (\\w+) ([\\w.]+)");
    private static final Pattern IDLE_GREP = Pattern.compile("deviceidle whitelist \\| grep -c ',([\\w.]+),'");
    private static final Pattern IDLE_SET = Pattern.compile("^dumpsys deviceidle whitelist ([+-])([\\w.]+)$");
    private static final Pattern VOL_SET = Pattern.compile("media volume --stream (\\d+) --set '?(\\d+)'?");
    private static final Pattern VOL_GET = Pattern.compile("media volume --stream (\\d+) --get");

    FakeSettingsShell put(String id, String v) {
        settings.put(id, v);
        return this;
    }

    @Override
    public ShellExecutor.ShellResponse execute(String command) {
        cmds.add(command);
        if (down) return new ShellExecutor.ShellResponse(-1, "down", false, true);
        if (command.contains(DiagSnapshot.MARK)) return ok(snapshot(command));
        Matcher m;
        if ((m = GET.matcher(command)).find()) {
            String v = settings.get(m.group(1) + "/" + m.group(2));
            return ok(v == null ? "null" : v);
        }
        if ((m = PUT.matcher(command)).find()) {
            String id = m.group(1) + "/" + m.group(2);
            if (!sticky.contains(id)) settings.put(id, m.group(3));
            return ok("");
        }
        if ((m = DEL.matcher(command)).find()) {
            settings.remove(m.group(1) + "/" + m.group(2));
            return ok("");
        }
        if ((m = IDLE_GREP.matcher(command)).find()) return ok(idle.contains(m.group(1)) ? "1" : "0");
        if ((m = IDLE_SET.matcher(command)).find()) {
            if ("+".equals(m.group(1))) idle.add(m.group(2));
            else idle.remove(m.group(2));
            return ok("");
        }
        if ((m = VOL_SET.matcher(command)).find()) {
            volume.put(m.group(1), Integer.valueOf(m.group(2)));
            return ok("");
        }
        if ((m = VOL_GET.matcher(command)).find()) {
            Integer v = volume.get(m.group(1));
            return ok("volume is " + (v == null ? 0 : v));
        }
        return ok("");
    }

    /** 模拟一条快照命令：按 @@id 分段回答。 */
    private String snapshot(String command) {
        StringBuilder out = new StringBuilder();
        for (String part : command.split("; echo '")) {
            int a = part.indexOf(DiagSnapshot.MARK);
            if (a < 0) continue;
            int b = part.indexOf('\'', a);
            String id = part.substring(a + DiagSnapshot.MARK.length(), b);
            out.append(DiagSnapshot.MARK).append(id).append('\n');
            DiagKnowledge.Key k = DiagKnowledge.key(id);
            if (k == null) continue;
            if (k.isProbe()) {
                String v = settings.get(id);
                if (v != null) out.append(v).append('\n');
            } else {
                String v = settings.get(id);
                out.append(v == null ? "null" : v).append('\n');
            }
        }
        return out.toString();
    }

    private static ShellExecutor.ShellResponse ok(String out) {
        return new ShellExecutor.ShellResponse(0, out, false, false);
    }

    boolean anyCmdContains(String s) {
        for (String c : cmds) if (c.contains(s)) return true;
        return false;
    }
}
