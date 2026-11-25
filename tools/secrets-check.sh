#!/usr/bin/env bash
# release 包不许带开发者凭据：生成 release 的 BuildConfig，字段名里带 KEY、SECRET、TOKEN、PASSWORD 的一律要求为空。
# 用法：tools/secrets-check.sh
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
"$root/tools/gate.sh" :app:generateReleaseBuildConfig >/dev/null
python3 - "$root" <<'PY'
import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
source = root / "android/app/build/generated/source/buildConfig/release/com/voca/speaker/BuildConfig.java"
text = source.read_text()
secret_names = re.compile(r"(KEY|SECRET|TOKEN|PASSWORD)")
violations = []
checked = 0
for line in text.splitlines():
    match = re.search(r"String (\w+) = \"(.*)\";", line)
    if not match:
        continue
    name, value = match.groups()
    if secret_names.search(name):
        checked += 1
        if value:
            violations.append(name)
print(f"release BuildConfig 里凭据字段 {checked} 个，非空 {len(violations)} 个")
if violations:
    sys.exit("release 包会带走开发者凭据: " + " ".join(violations))
PY
