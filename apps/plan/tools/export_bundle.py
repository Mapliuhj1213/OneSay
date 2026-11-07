#!/usr/bin/env python3
"""把从手机只读复制出来的 OneSay 库文件打成计划 App 的导入包（bixbyui-raw-v1）。
用法: export_bundle.py <src_dir> <out.json>
src_dir 里可有：sched.db s3_tasks.db jobs.db quicknote.db（连同 -journal 一起复制）、bixby_s1_pickup.xml、watches.txt。
映射逻辑不在这里（只原样导出各表行），在计划 App 的 core/RawImport.java。OneSay 新版的 PlannerMigration 做同样的事。"""
import json, os, sqlite3, sys, html, re, shutil, tempfile

src, out = sys.argv[1], sys.argv[2]
b = {"format": "bixbyui-raw-v1", "source_pkg": "com.onesay.assistant"}

def rows(db, table):
    p = os.path.join(src, db)
    if not os.path.exists(p): return []
    tmp = tempfile.mkdtemp()
    for suf in ("", "-journal"):
        if os.path.exists(p + suf): shutil.copy(p + suf, os.path.join(tmp, db + suf))
    try:
        c = sqlite3.connect(os.path.join(tmp, db)); c.row_factory = sqlite3.Row
        try: return [dict(r) for r in c.execute("SELECT * FROM %s" % table)]
        except sqlite3.OperationalError: return []
        finally: c.close()
    finally: shutil.rmtree(tmp)

b["sched_event"] = rows("sched.db", "sched_event")
b["s3_task"] = rows("s3_tasks.db", "task")
b["s3_todo"] = rows("s3_tasks.db", "todo")
b["event_task"] = rows("jobs.db", "event_task")
b["job"] = rows("jobs.db", "job")
b["quicknote"] = rows("quicknote.db", "quicknote")
px = os.path.join(src, "bixby_s1_pickup.xml")
if os.path.exists(px):
    m = re.search(r'name="cards">([^<]*)<', open(px, encoding="utf-8").read())
    if m:
        try: b["pickup_cards"] = json.loads(html.unescape(m.group(1)))
        except Exception: pass
wt = os.path.join(src, "watches.txt")
if os.path.exists(wt) and os.path.getsize(wt) > 2:
    try: b["watches"] = json.loads(html.unescape(open(wt, encoding="utf-8").read().strip()))
    except Exception: pass
json.dump(b, open(out, "w", encoding="utf-8"), ensure_ascii=False)
print({k: len(v) for k, v in b.items() if isinstance(v, list)})
