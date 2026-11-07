#!/usr/bin/env python3
"""扫描 git 索引里的文本文件，找疑似密钥 / 个人信息（refactor/oss 2026-11-06）。

只报「文件:行 类型 长度」，**从不打印命中的值**。命中允许清单（tools/ci/secret_allowlist.txt，
每行「路径:行号:类型」或「路径:*:类型」）以外的条目即 exit 1。

  python3 tools/ci/scan_secrets.py            # 扫当前索引
  python3 tools/ci/scan_secrets.py --history  # 另外扫全部历史 blob（只报告，不失败）
"""
import os, re, subprocess, sys

PATTERNS = [
    ('sk-key', r'\bsk-[A-Za-z0-9_\-]{16,}'),
    ('tavily-key', r'\btvly-[A-Za-z0-9_\-]{10,}'),
    ('github-token', r'\b(?:ghp|gho|ghs|ghu)_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}'),
    ('google-api-key', r'\bAIza[0-9A-Za-z_\-]{30,}'),
    ('google-client-secret', r'GOCSPX-[A-Za-z0-9_\-]{10,}'),
    ('aws-key', r'\bAKIA[0-9A-Z]{16}\b'),
    ('aliyun-ak', r'\bLTAI[A-Za-z0-9]{12,}'),
    ('private-key', r'-----BEGIN [A-Z ]*PRIVATE KEY-----'),
    ('jwt', r'\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}'),
    ('bearer', r'Bearer\s+[A-Za-z0-9_\-\.]{24,}'),
    ('keystore-pass', r'(?i)\b(?:storepass|keypass|ks_pass|key_pass)\s*[=:]\s*["\']?(?!env:|file|\$)[^\s"\'$]{4,}'),
    ('cookie', r'(?i)\bcookie\s*[:=]\s*["\']?[A-Za-z0-9_]+=[^;\s"]{16,}'),
]
COMPILED = [(n, re.compile(p)) for n, p in PATTERNS]
ALLOW = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'secret_allowlist.txt')


def load_allow():
    s = set()
    if os.path.exists(ALLOW):
        for line in open(ALLOW, encoding='utf-8'):
            line = line.split('#', 1)[0].strip()
            if line:
                s.add(line)
    return s


def scan_text(text):
    for i, line in enumerate(text.splitlines(), 1):
        for name, c in COMPILED:
            for m in c.finditer(line):
                yield i, name, len(m.group(0))


def main():
    root = subprocess.check_output(['git', 'rev-parse', '--show-toplevel']).decode().strip()
    os.chdir(root)
    allow = load_allow()
    bad = 0
    files = [f for f in subprocess.check_output(['git', 'ls-files', '-z']).decode('utf-8', 'replace').split('\0') if f]
    for f in files:
        try:
            b = open(f, 'rb').read()
        except OSError:
            continue
        if b'\0' in b[:8000]:
            continue
        for ln, name, n in scan_text(b.decode('utf-8', 'replace')):
            if '%s:%d:%s' % (f, ln, name) in allow or '%s:*:%s' % (f, name) in allow:
                continue
            print('[secret-scan] %s:%d %s (len=%d)' % (f, ln, name, n))
            bad += 1
    if '--history' in sys.argv:
        objs = subprocess.check_output(['git', 'rev-list', '--objects', '--all']).decode('utf-8', 'replace').splitlines()
        seen = set()
        for o in objs:
            parts = o.split(' ', 1)
            if len(parts) != 2 or parts[0] in seen:
                continue
            seen.add(parts[0])
            try:
                t = subprocess.check_output(['git', 'cat-file', '-p', parts[0]], stderr=subprocess.DEVNULL)
            except subprocess.CalledProcessError:
                continue
            if b'\0' in t[:8000]:
                continue
            for ln, name, n in scan_text(t.decode('utf-8', 'replace')):
                print('[secret-scan:history] %s (blob %s):%d %s (len=%d)' % (parts[1], parts[0][:10], ln, name, n))
    if bad:
        print('[secret-scan] FAIL：%d 处疑似密钥（值未打印）。确认是测试假值的，加进 tools/ci/secret_allowlist.txt' % bad)
        return 1
    print('[secret-scan] OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())
