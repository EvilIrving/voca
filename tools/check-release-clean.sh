#!/usr/bin/env bash
# release 包里不该出现 debug 探针。不带参数只查源集，带上 apk 路径连包一起查。
# 用法：tools/check-release-clean.sh [app/build/outputs/apk/release/app-release-unsigned.apk]
set -uo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
android="$root/android"
app="$android/app"
failed=0

fail() { printf 'FAIL  %s\n' "$*"; failed=1; }
pass() { printf 'ok    %s\n' "$*"; }

probe_names='CaptureProbeReceiver|PlayProbeReceiver|FocusProbeReceiver'
probe_actions='com\.parley\.speaker\.(CAPTURE|PLAY|FOCUS)_PROBE'
probe_pattern="$probe_names|$probe_actions|com\.parley\.speaker\.debug"

for dir in "$app/src/main" "$app/src/release"; do
  [ -d "$dir" ] || continue
  hits=$(grep -rlnE "$probe_pattern" "$dir" 2>/dev/null || true)
  if [ -n "$hits" ]; then
    fail "release 侧源集提到探针：$(printf '%s ' $hits)"
  else
    pass "$dir 没有探针"
  fi
done

outside=$(grep -rlE "class ($probe_names)" "$app/src" 2>/dev/null | grep -v "^$app/src/debug/java/" || true)
if [ -n "$outside" ]; then
  fail "探针类跑出 debug 源集：$(printf '%s ' $outside)"
else
  pass "探针类都在 app/src/debug/java"
fi

declared=$(grep -rlE "android:name=\"\.debug\.($probe_names)\"" "$app/src" 2>/dev/null | grep -v "^$app/src/debug/AndroidManifest.xml" || true)
if [ -n "$declared" ]; then
  fail "探针 receiver 声明跑出 debug 清单：$(printf '%s ' $declared)"
else
  pass "探针 receiver 只声明在 app/src/debug/AndroidManifest.xml"
fi

apk=${1:-}
if [ -z "$apk" ]; then
  apk=$(ls -1 "$app"/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || true)
fi
if [ -z "$apk" ] || [ ! -f "$apk" ]; then
  printf 'skip  没找到 release apk，安装包层面未查。先跑 android/gradlew :app:assembleRelease，再用同一个脚本带上 apk 路径\n'
  exit "$failed"
fi

aapt2=${AAPT2:-}
if [ -z "$aapt2" ]; then
  sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  if [ -z "$sdk" ] && [ -f "$android/local.properties" ]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' "$android/local.properties")
  fi
  [ -n "$sdk" ] && aapt2=$(ls -1 "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
fi
if [ -n "$aapt2" ] && [ -x "$aapt2" ]; then
  if "$aapt2" dump xmltree --file AndroidManifest.xml "$apk" 2>/dev/null | grep -qE "$probe_pattern"; then
    fail "合并后的 release 清单里还有探针 receiver"
  else
    pass "release 清单里没有探针 receiver"
  fi
else
  fail "找不到 aapt2，清单未查；用 AAPT2=/path/to/aapt2 指定"
fi

if unzip -p "$apk" 'classes*.dex' 2>/dev/null | strings | grep -q 'Lcom/parley/speaker/debug/'; then
  fail "dex 里有 com/parley/speaker/debug 的类"
else
  pass "dex 里没有探针类"
fi

exit "$failed"
