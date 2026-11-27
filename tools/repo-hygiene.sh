#!/usr/bin/env bash
# 只许留在本机的东西不能顺便跟着推送走：竞品取证材料、真机序列号。进了索引就报错。
# 用法：tools/repo-hygiene.sh
set -uo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root" || exit 2

failed=0
fail() { printf 'FAIL  %s\n' "$*"; failed=1; }
pass() { printf 'ok    %s\n' "$*"; }

for path in hiecho-reverse PROJECT_MEMORY.md docs/design/competitor-icons; do
  count=$(git ls-files -- "$path" | wc -l | tr -d ' ')
  if [ "$count" -gt 0 ]; then
    fail "$path 有 $count 个文件在索引里：git rm -r --cached $path"
  else
    pass "$path 不在索引里"
  fi
done

serial="${VOCA_DEV:-${QOD_DEV:-}}"
if [ -z "$serial" ]; then
  pass "没给 VOCA_DEV，序列号这一项跳过"
else
  hits=$(git grep -I -l -F "$serial" -- . 2>/dev/null || true)
  if [ -n "$hits" ]; then
    fail "仓库里带着设备序列号：$(printf '%s ' $hits)"
  else
    pass "序列号没进版本库"
  fi
fi

exit "$failed"
