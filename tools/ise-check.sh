#!/usr/bin/env bash
# 讯飞语音评测线上自检：拿 .env 里的三件套把一段 16 千赫兹单声道裸流传给线上评测，
# 并把服务端回报与解析结果打进测试报告。用法：tools/ise-check.sh <pcm 路径> "<原文本>"
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if [ "$#" -ne 2 ]; then
  echo "用法: $0 <16kHz 单声道 16 位 PCM 路径> <原文本>" >&2
  exit 2
fi
if [ ! -f "$1" ]; then
  echo "找不到音频: $1" >&2
  exit 2
fi
set -a
# shellcheck disable=SC1091
. "$root/.env"
set +a
for key in XFYUN_APP_ID XFYUN_API_KEY XFYUN_API_SECRET; do
  if [ -z "${!key:-}" ]; then
    echo ".env 里缺 $key" >&2
    exit 3
  fi
done
ISE_PCM="$1" ISE_TEXT="$2" ISE_BAD_KEY=1 "$root/tools/gate.sh" :app:testDebugUnitTest \
  --tests 'com.voca.speaker.data.assessor.XfyunLiveProbeTest' --rerun-tasks >/dev/null
report=$(ls "$root"/android/app/build/test-results/testDebugUnitTest/*XfyunLiveProbeTest.xml)
python3 - "$report" <<'PY'
import re, sys
raw = open(sys.argv[1]).read()
out = re.search(r'<system-out><!\[CDATA\[(.*?)\]\]></system-out>', raw, re.S).group(1)
kept = []
for line in out.splitlines():
    if '"status":1' in line:
        continue
    kept.append(re.sub(r'("data":"[A-Za-z0-9+/=]{80})[A-Za-z0-9+/=]+', r'\1…', line)[:600])
print('\n'.join(kept))
text = '\n'.join(kept)
if 'PROBE assessor Scored' not in text:
    sys.exit('真音频没出分')
if 'PROBE badkey Failed(failure=Key)' not in text:
    sys.exit('坏密钥没归成密钥类')
PY
