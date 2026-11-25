#!/usr/bin/env bash
# 一段交互落地之后读一眼帧数据：帧数、卡顿帧占比、50 与 90 分位、最长那一帧。
# 界面在流式回复时闪，肉眼只看到闪，看不到「内容变高与滚动修正差了一帧」这类事实；
# 一条命令读出来，就不必靠读框架源码去推断。
# 用法：tools/gfxinfo.sh [reset] [包名]
#   reset  先清零再看这一段，看清即将发生的那一段而不是开机以来的累计。
# 一帧超过 VOCA_FRAME_MS（默认 16.7）算卡顿，120 赫兹的屏把它设成 8.3。
set -uo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root" || exit 2

pkg=com.voca.speaker
reset=0
for arg in "$@"; do
  case "$arg" in
    reset) reset=1 ;;
    selftest) selftest=1 ;;
    *) pkg="$arg" ;;
  esac
done

# framestats 每一行是一帧，列名在头一行。只认两列：这一帧该在什么时候完成、实际什么时候完成，
# 两者之差就是这一帧画了多久。
frames() {
  awk -F, '
    $1 == "Flags" {
      for (i = 1; i <= NF; i++) {
        if ($i == "IntendedVsync") start = i
        if ($i == "FrameCompleted") end = i
      }
      next
    }
    start && end && $start ~ /^[0-9]+$/ && $end ~ /^[0-9]+$/ {
      printf "%.3f\n", ($end - $start) / 1000000
    }
  '
}

summary() {
  awk -v budget="${VOCA_FRAME_MS:-16.7}" '
    NF {
      n += 1
      value[n] = $1
      if ($1 > budget) jank += 1
      if ($1 > worst) worst = $1
    }
    END {
      if (n == 0) {
        print "没有读到帧数据：这一段里界面没画过东西，或者包名不对"
        exit 1
      }
      sort(n)
      printf "帧数 %d  卡顿 %d 帧 %.1f%%  p50 %.1fms  p90 %.1fms  最长 %.1fms\n",
        n, jank, jank * 100 / n, at(0.5), at(0.9), worst
    }
    function sort(count,    i, j, key) {
      for (i = 2; i <= count; i++) {
        key = value[i]
        for (j = i - 1; j >= 1 && value[j] > key; j--) value[j + 1] = value[j]
        value[j + 1] = key
      }
    }
    function at(share,    rank) {
      rank = int(n * share + 0.9999)
      if (rank < 1) rank = 1
      if (rank > n) rank = n
      return value[rank]
    }
  '
}

if [ "${selftest:-0}" = 1 ]; then
  # 四帧依次 8、12、20、24 毫秒：卡顿两帧、占比一半、p50 落在 12、p90 与最长都是 24。
  sample=$(printf '%s\n' \
    'Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameInterval,FrameStartTime,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,' \
    '0,1,1000000,1000000,0,0,0,0,0,0,0,0,0,0,0,0,9000000,' \
    '0,2,2000000,2000000,0,0,0,0,0,0,0,0,0,0,0,0,14000000,' \
    '0,3,3000000,3000000,0,0,0,0,0,0,0,0,0,0,0,0,23000000,' \
    '0,4,4000000,4000000,0,0,0,0,0,0,0,0,0,0,0,0,28000000,')
  got=$(printf '%s\n' "$sample" | frames | summary)
  want='帧数 4  卡顿 2 帧 50.0%  p50 12.0ms  p90 24.0ms  最长 24.0ms'
  if [ "$got" = "$want" ]; then
    printf 'ok    %s\n' "$got"
  else
    printf 'FAIL  读出来是「%s」，应该是「%s」\n' "$got" "$want"
    exit 1
  fi
  exit 0
fi

if [ "$reset" = 1 ]; then
  adb shell dumpsys gfxinfo "$pkg" reset >/dev/null || exit 1
  printf '已清零，做一遍要看的交互，再跑一次不带 reset 的这条命令\n'
  exit 0
fi

data=$(adb shell dumpsys gfxinfo "$pkg" framestats) || exit 1
# 数据夹在两条 PROFILEDATA 之间，别的段落里也有逗号分隔的数字，只看这一段。
block=$(printf '%s\n' "$data" | awk '/PROFILEDATA/{n++; next} n == 1')
if [ -z "$block" ]; then
  printf '这个包里没有 framestats：先确认前台是不是 Voca\n'
  exit 1
fi

printf '%s\n' "$block" | frames | summary
