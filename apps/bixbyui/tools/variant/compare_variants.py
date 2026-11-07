#!/usr/bin/env python3
"""对比 release / debug 两个 APK 会不会在同一台手机上冲突。只读 APK，不碰手机。

用法：python3 tools/variant/compare_variants.py <release.apk> <debug.apk> [<基线 release.apk>]
  · 用 aapt2 dump badging / xmltree 取：包名、版本、label、声明的权限、用到的自定义权限、provider authority、
    导出组件的保护权限、自定义 intent action、sharedUserId、android:process；
  · 用 apksigner 取证书 SHA-256；
  · 给了基线就逐个 zip 条目比对 release 与基线（签名块之外应全部相同）。
输出 Markdown，退出码 0 = 无冲突（且 release 与基线条目一致），1 = 有问题。
"""
import hashlib, os, re, subprocess, sys, zipfile

TOOLS = os.environ.get("TOOLS", os.path.expanduser("~/onesay-tools"))
JDK = os.environ.get("JAVA_HOME", TOOLS + "/jdk") + "/bin/java"
BT = TOOLS + "/android-sdk/build-tools/30.0.3"
AAPT2 = os.environ.get("AAPT2") or (BT + "/aapt2" if os.uname().machine in ("x86_64", "amd64")
                                    else TOOLS + "/native-aarch64/build-tools/aapt2")

def run(*a):
    return subprocess.run(a, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout

def info(apk):
    b = run(AAPT2, "dump", "badging", apk)
    x = run(AAPT2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    c = run(JDK, "-cp", BT + "/lib/apksigner.jar", "com.android.apksigner.ApkSignerTool", "verify", "--print-certs", apk)
    d = {}
    m = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", b)
    d["package"], d["versionCode"], d["versionName"] = m.groups()
    d["label"] = re.search(r"application-label:'([^']*)'", b).group(1)
    d["launcher_label"] = (re.search(r"launchable-activity: name='[^']+'\s+label='([^']*)'", b) or [None, ""])[1]
    d["cert_sha256"] = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", c).group(1)
    d["cert_dn"] = re.search(r"Signer #1 certificate DN: (.+)", c).group(1).strip()
    # xmltree：按元素走一遍
    elems, cur = [], None
    for line in x.splitlines():
        e = re.match(r"\s*E: ([\w-]+)", line)
        if e:
            cur = {"tag": e.group(1), "attrs": {}}; elems.append(cur); continue
        a = re.match(r'\s*A: (?:http://schemas.android.com/apk/res/android:)?(\w+)(?:\(0x[0-9a-f]+\))?="([^"]*)"', line)
        if a and cur is not None:
            cur["attrs"][a.group(1)] = a.group(2)
    attr = lambda tag, k: [el["attrs"].get(k) for el in elems if el["tag"] == tag and k in el["attrs"]]
    d["declared_permissions"] = sorted(attr("permission", "name"))
    d["custom_uses_permissions"] = sorted(p for p in attr("uses-permission", "name") if not p.startswith("android."))
    d["uses_permission_count"] = len(attr("uses-permission", "name"))
    d["authorities"] = sorted(a for v in attr("provider", "authorities") for a in v.split(";"))
    d["component_permissions"] = sorted(set(p for t in ("activity", "service", "receiver", "provider") for p in attr(t, "permission")))
    d["custom_actions"] = sorted(set(a for a in attr("action", "name") if not a.startswith("android.")))
    d["sharedUserId"] = attr("manifest", "sharedUserId")
    d["processes"] = sorted(set(p for t in ("application", "activity", "service", "receiver", "provider") for p in attr(t, "process")))
    d["components"] = sorted(el["attrs"]["name"] for el in elems if el["tag"] in ("activity", "service", "receiver", "provider") and "name" in el["attrs"])
    return d

def entries(apk):
    z = zipfile.ZipFile(apk)
    return {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist()}

def main():
    rel, dbg = sys.argv[1], sys.argv[2]
    base = sys.argv[3] if len(sys.argv) > 3 else None
    R, D = info(rel), info(dbg)
    problems = []
    def row(name, rv, dv, ok, note=""):
        f = lambda v: ", ".join(v) if isinstance(v, list) else str(v)
        print("| %s | %s | %s | %s | %s |" % (name, f(rv) or "（无）", f(dv) or "（无）", "✅" if ok else "❌", note))
        if not ok: problems.append(name)
    print("| 项目 | release | debug | 冲突? | 说明 |\n|---|---|---|---|---|")
    row("包名 (applicationId)", R["package"], D["package"], R["package"] != D["package"], "不同包名 → 可同时安装")
    row("versionCode / versionName", R["versionCode"] + " / " + R["versionName"], D["versionCode"] + " / " + D["versionName"], True, "同版本号，不影响共存")
    row("应用名 label", R["label"], D["label"], R["label"] != D["label"], "桌面 / 设置里能分开")
    row("桌面图标 label", R["launcher_label"], D["launcher_label"], R["launcher_label"] != D["launcher_label"], "")
    row("签名证书 SHA-256", R["cert_sha256"], D["cert_sha256"], R["cert_sha256"] != D["cert_sha256"], "两把不同的钥匙")
    dup = sorted(set(R["declared_permissions"]) & set(D["declared_permissions"]))
    row("声明的权限 <permission>", R["declared_permissions"], D["declared_permissions"], not dup,
        "同名且签名不同会 INSTALL_FAILED_DUPLICATE_PERMISSION" + ("；重名：" + ", ".join(dup) if dup else "；无重名"))
    crossR = [p for p in R["component_permissions"] if p in D["declared_permissions"]]
    crossD = [p for p in D["component_permissions"] if p in R["declared_permissions"]]
    row("组件保护权限 android:permission", R["component_permissions"], D["component_permissions"], not crossR and not crossD,
        "各自只认自己声明的签名级权限")
    row("自定义 uses-permission", R["custom_uses_permissions"], D["custom_uses_permissions"], True, "")
    row("uses-permission 总数", R["uses_permission_count"], D["uses_permission_count"], R["uses_permission_count"] == D["uses_permission_count"], "系统权限两边一样")
    dupa = sorted(set(R["authorities"]) & set(D["authorities"]))
    row("provider authority", R["authorities"], D["authorities"], not dupa, "重名会 INSTALL_FAILED_CONFLICTING_PROVIDER" + ("；重名：" + ", ".join(dupa) if dupa else "；两边都没有 provider"))
    row("自定义 intent action（manifest）", R["custom_actions"], D["custom_actions"], not (set(R["custom_actions"]) & set(D["custom_actions"])), "")
    row("sharedUserId", R["sharedUserId"], D["sharedUserId"], not R["sharedUserId"] and not D["sharedUserId"], "")
    row("android:process", R["processes"], D["processes"], not R["processes"] and not D["processes"], "默认进程名 = 包名，天然不同")
    row("组件类名", "%d 个" % len(R["components"]), "%d 个（多 TestRunReceiver）" % len(D["components"]) if len(D["components"]) == len(R["components"]) + 1 else "%d 个" % len(D["components"]),
        True, "类名相同不冲突：组件按 包名/类名 寻址")
    print()
    print("release 证书 DN：%s  \ndebug 证书 DN：%s" % (R["cert_dn"], D["cert_dn"]))
    if base:
        B = info(base); eb, er = entries(base), entries(rel)
        diff = sorted(n for n in set(eb) | set(er) if eb.get(n) != er.get(n))
        print("\n### release vs 基线 %s\n" % os.path.basename(os.path.dirname(os.path.abspath(base))))
        print("| 项目 | 基线 | 本次 release | 一致? |\n|---|---|---|---|")
        for k in ("package", "versionCode", "versionName", "label", "cert_sha256"):
            print("| %s | %s | %s | %s |" % (k, B[k], R[k], "✅" if B[k] == R[k] else "❌"))
            if B[k] != R[k]: problems.append("baseline:" + k)
        print("| zip 条目（%d 个，含 classes.dex / resources.arsc / AndroidManifest.xml） | — | 不同 %d 个 | %s |" % (len(er), len(diff), "✅" if not diff else "❌ " + ", ".join(diff)))
        if diff: problems.append("baseline:entries")
    print("\n结论：" + ("无冲突" if not problems else "有问题：" + ", ".join(problems)))
    sys.exit(1 if problems else 0)

if __name__ == "__main__":
    main()
