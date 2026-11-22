#!/usr/bin/env bash
# 本地验收闸门：编译 debug 变体并跑单元测试。多个子代理同时调用时靠目录锁排队，
# 避免 Gradle 缓存锁互相打架。
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

lock=".gradle/gate.lock"
mkdir -p .gradle
for _ in $(seq 1 600); do
  if mkdir "$lock" 2>/dev/null; then
    trap 'rmdir "$lock" 2>/dev/null || true' EXIT
    break
  fi
  sleep 2
done

if [ "$#" -eq 0 ]; then
  set -- :app:compileDebugKotlin :app:testDebugUnitTest
fi
./gradlew "$@" --offline --console=plain
