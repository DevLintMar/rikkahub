"""Settings 键的「声明 / 读取 / 写入」三集合比对 —— 同步上游后必跑。

为什么需要：上游把内联的 `dataStore.edit{}` 抽成了 `persistSettings(dataStore, settings)`，
解冲突时 fork 自有键极易只搬一部分 —— 漏掉的键**只读不写** → 该设置改完重启即回退、
备份恢复也不落地，而且不报错、CI 全绿（memory: upstream-sync-procedure 第 12 类）。

判据：`preferences[KEY] =` / `preferences.remove(KEY)` 视为写入，其余出现视为读取。
用法：在仓库根目录执行 `python docs/superpowers/scripts/prefs_key_audit.py [<基线 ref>]`
      （基线默认 `7042fa80`，即上次上游同步前的 fork master）
退出码：0 = 没有「读取但从不写入」的新增键；1 = 有（需要修或显式加进 ALLOWED 说明）
"""

import re
import subprocess
import sys

PATH = "app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt"
BASELINE = sys.argv[1] if len(sys.argv) > 1 else "7042fa80"

# 允许「只读不写」的键：历史遗留 / 只读迁移源，不是漏搬
ALLOWED_READ_ONLY = {
    "SEARCH_SELECTED": "旧版按索引存的搜索选择，只为迁移读取（新键是 SEARCH_SELECTED_IDS）",
}

DECL = re.compile(r"val\s+([A-Z0-9_]+)\s*=\s*(string|boolean|int|long|float|double|stringSet)PreferencesKey")
READ = re.compile(r"preferences\[([A-Z0-9_]+)\]")
WRITE = re.compile(r"preferences\[([A-Z0-9_]+)\]\s*=|preferences\.remove\(([A-Z0-9_]+)\)")


def analyze(label, text):
    decls, reads, writes = set(), set(), set()
    for line in text.splitlines():
        m = DECL.search(line)
        if m:
            decls.add(m.group(1))
        for m in READ.finditer(line):
            if re.search(r"preferences\[" + m.group(1) + r"\]\s*=", line):
                writes.add(m.group(1))
            else:
                reads.add(m.group(1))
        for m in WRITE.finditer(line):
            writes.add(m.group(1) or m.group(2))
    print(f"### {label}: 声明 {len(decls)} / 读取 {len(reads)} / 写入 {len(writes)}")
    return decls, reads, writes


cur = analyze("HEAD", open(PATH, encoding="utf-8").read())
old = analyze(
    BASELINE,
    subprocess.run(
        ["git", "show", BASELINE + ":" + PATH],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout,
)

failed = False
for name, (_d, r, w) in (("HEAD", cur), (BASELINE, old)):
    lost = sorted(r - w - set(ALLOWED_READ_ONLY))
    print(f"[{name}] 读取但从不写入（{len(lost)}）: {lost}")
    if name == "HEAD" and lost:
        failed = True
    print(f"[{name}] 声明了但既没读也没写: {sorted(_d - r - w)}")

print(f"\n=== 合并前有写入、现在没有写入的键（= 迁移中丢掉的写入）===")
lost_writes = sorted(old[2] - cur[2])
print(lost_writes if lost_writes else "（无）")
print("\n=== 现在新增写入的键 ===")
print(sorted(cur[2] - old[2]) or "（无）")

if failed:
    print("\nFAIL：存在只读不写的键 —— 要么补写入，要么加进 ALLOWED_READ_ONLY 并写明理由")
    sys.exit(1)
print("\nPASS")
