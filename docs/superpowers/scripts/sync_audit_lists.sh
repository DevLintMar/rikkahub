#!/usr/bin/env bash
# 生成"上游改动 vs fork 改动"的文件清单，供同步审计使用。
# 用法：bash docs/superpowers/scripts/sync_audit_lists.sh <分叉点> <上游pin> [fork合并前master] [输出目录]
#   例：bash docs/superpowers/scripts/sync_audit_lists.sh 4b6449e3 2689e753 7042fa80 /tmp/sync-audit
# - 分叉点（merge-base）与两个 SHA 都可从上次同步的 handoff 里抄到
# - 输出：upstream_commits/fork_commits/post_merge_commits/upstream_files/fork_files/both_touched_files
#         + both_touched_numstat.tsv（双方都改过的文件逐文件增删行数）
set -eu

BASE=${1:?分叉点 sha}
PIN=${2:?上游 pin sha}
FORK=${3:-$(git rev-parse HEAD)}
OUT=${4:-./sync-audit-lists}

mkdir -p "$OUT"
git log --oneline --no-merges "$BASE..$PIN"  > "$OUT/upstream_commits.txt"
git log --oneline --no-merges "$BASE..$FORK" > "$OUT/fork_commits.txt"
git log --oneline --first-parent "$FORK..HEAD" > "$OUT/post_merge_commits.txt"
git diff --name-only "$BASE..$PIN"  | sort > "$OUT/upstream_files.txt"
git diff --name-only "$BASE..$FORK" | sort > "$OUT/fork_files.txt"
comm -12 "$OUT/fork_files.txt" "$OUT/upstream_files.txt" > "$OUT/both_touched_files.txt"

: > "$OUT/both_touched_numstat.tsv"
while IFS= read -r f; do
    up=$(git diff --numstat "$BASE..$PIN"  -- "$f" | head -1)
    fk=$(git diff --numstat "$BASE..$FORK" -- "$f" | head -1)
    printf '%s\t%s\t%s\n' "$f" "${fk:-0\t0\t}" "${up:-0\t0\t}" >> "$OUT/both_touched_numstat.tsv"
done < "$OUT/both_touched_files.txt"

echo "上游改动 $(wc -l < "$OUT/upstream_files.txt") 文件 / fork 改动 $(wc -l < "$OUT/fork_files.txt") 文件 / 双方都改 $(wc -l < "$OUT/both_touched_files.txt") 文件"
echo "清单已写入 $OUT"
