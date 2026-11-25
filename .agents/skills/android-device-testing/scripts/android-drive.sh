#!/usr/bin/env bash
#  Voca 口语客户端 Android 真机自动化测试驱动。
#  配对文档：.agents/skills/android-device-testing/SKILL.md
#
#  用法（只在仓库根目录执行，脚本自动校验）：
#    .agents/skills/android-device-testing/scripts/android-drive.sh state
#    .agents/skills/android-device-testing/scripts/android-drive.sh tap <名字>
#    .agents/skills/android-device-testing/scripts/android-drive.sh record [毫秒数]
#    .agents/skills/android-device-testing/scripts/android-drive.sh record-cancel
#    .agents/skills/android-device-testing/scripts/android-drive.sh vocab
#    .agents/skills/android-device-testing/scripts/android-drive.sh settings
#    .agents/skills/android-device-testing/scripts/android-drive.sh review
#    .agents/skills/android-device-testing/scripts/android-drive.sh shot [名字]
#    .agents/skills/android-device-testing/scripts/android-drive.sh install [apk]
#    .agents/skills/android-device-testing/scripts/android-drive.sh launch / home / back / devices / el
#    .agents/skills/android-device-testing/scripts/android-drive.sh raw <adb 参数...>   透传一次 adb 调用
#
#  环境变量：VOCA_DEV 或 QOD_DEV 指定目标序列号（默认自动探测唯一处于 device 状态的机器）。

set -uo pipefail

# 仓库根目录定位与路径校验
SKILL_DIR=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$SKILL_DIR/../../.." && pwd)
case "$(pwd)/" in
  "$ROOT"/*) ;;
  *) echo "请在仓库根目录执行："; echo "  cd $ROOT && .agents/skills/android-device-testing/scripts/android-drive.sh ${*:-state}"; exit 2 ;;
esac
[ -f "$ROOT/AGENTS.md" ] || { echo "找不到 $ROOT/AGENTS.md：未在正确的项目目录中执行"; exit 2; }
cd "$ROOT" || exit 2

# Java 编译环境保障
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  if [ -d "/opt/homebrew/opt/openjdk@17" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
  fi
fi

PKG="com.voca.speaker"
MAIN_ACT="com.voca.speaker/.MainActivity"
XML="/tmp/voca-ui.xml"
NODES_TSV="/tmp/voca-nodes.tsv"
SHOTDIR=${SHOTDIR:-/tmp}
ADB=${ADB:-adb}

if [ -z "${VOCA_DEV:-}" ]; then
  VOCA_DEV=${QOD_DEV:-$($ADB devices | awk '/\tdevice$/{print $1; exit}')}
fi
DEV=${VOCA_DEV:-}
[ -n "$DEV" ] || {
  echo "未检测到可用 Android 设备。无线调试端口动态分配，请通过如下方式连接："
  echo "  adb devices          # 检查当前设备列表"
  echo "  adb mdns services    # 获取 _adb-tls-connect._tcp 那行的 ip:port"
  echo "  adb connect <获取到的 ip:port>"
  echo "或者直接使用 USB 数据线连接。"
  exit 1
}

A() { $ADB -s "$DEV" "$@"; }

dump() {
  A shell uiautomator dump /sdcard/voca-ui.xml >/dev/null 2>&1
  A exec-out cat /sdcard/voca-ui.xml > "$XML" 2>/dev/null
  [ -s "$XML" ] || { echo "UI 转储失败：界面可能正处于动画过渡阶段，请稍后重试" >&2; return 1; }
}

nodes() {
  python3 - "$XML" <<'PY'
import re, sys
try:
    content = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
except Exception:
    sys.exit(1)
for m in re.finditer(r'<node[^>]*>', content):
    s = m.group(0)
    g = lambda k: (re.search(k+r'="([^"]*)"', s) or [None, ''])[1]
    print('\t'.join([g('bounds'), g('clickable'), g('text'), g('content-desc'), g('class'), g('package')]))
PY
}

center_of() {
  python3 - "$1" <<'PY'
import re, sys
n = [int(v) for v in re.findall(r'-?\d+', sys.argv[1])]
if len(n) >= 4:
    print((n[0] + n[2]) // 2, (n[1] + n[3]) // 2)
else:
    print(0, 0)
PY
}

foreground() {
  A shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed -E 's/.*u0 ([^ ]+) .*/\1/;s/.*mCurrentFocus=//'
}

screen_kind() {
  local fg
  fg=${1:-$(foreground)}
  nodes > "$NODES_TSV"
  python3 - "$fg" "$NODES_TSV" <<'PY'
import sys
fg = sys.argv[1]
tsv = sys.argv[2]
rows = [l.rstrip("\n").split("\t") for l in open(tsv, encoding='utf-8', errors='ignore') if l.strip()]
texts = {r[2] for r in rows if len(r) > 2 and r[2]}
descs = {r[3] for r in rows if len(r) > 3 and r[3]}
pks = {r[5] for r in rows if len(r) > 5 and r[5]}
pk = ' '.join(pks)
buttons = [(r[2] or r[3]) for r in rows if len(r) > 1 and r[1] == 'true' and (r[2] or r[3])]

def anyof(*xs): return any(x in texts or x in descs for x in xs)

low = fg.lower()
kind = ''

if 'packageinstaller' in low:
    kind = '系统:安装确认框'
elif 'permissioncontroller' in low:
    kind = '系统:权限框'
elif 'settings' in low and 'com.voca' not in low:
    kind = '系统:设置页'

if not kind and 'com.voca.speaker' in pks:
    if anyof('松手发送', '上滑取消'):
        kind = 'app:收音中'
    elif anyof('本轮评测'):
        kind = 'app:评测抽屉'
    elif anyof('生词库') and anyof('搜索词或音标', '今天 24', '待复习 9', '全部 15'):
        kind = 'app:生词库'
    elif anyof('设置') and anyof('实时对话', '识别与分析', '连接自检', '角色提示词'):
        kind = 'app:设置'
    elif anyof('按住说话') or anyof('生词库', '设置', '结束会话', '更多') or any(t.startswith('第 ') and t.endswith(' 轮') for t in texts):
        kind = 'app:主对话'
    else:
        kind = 'app:未识别页面（可能在动画过渡中，请调用 el 检查）'

page = next((d.split(",")[1] for d in descs if d.startswith("搜索,第")), '')
if not kind:
    if page:
        kind = '桌面 ' + page
    elif 'com.bbk.launcher2' in pk or 'com.vivo' in pk:
        kind = '桌面(无页码节点)'
    else:
        kind = '第三方应用:' + fg.split('/')[0]

print(kind)
if kind.startswith('系统:'):
    print('  可操作按钮:', ' / '.join(dict.fromkeys(buttons))[:120] or '（未提取到可点按钮，请执行 el 查询）')
PY
}

signature() {
  nodes | python3 -c '
import sys
rows = [l.rstrip("\n").split("\t") for l in sys.stdin]
texts = [r[2] for r in rows if len(r) > 2 and r[2]]
descs = [r[3] for r in rows if len(r) > 3 and r[3]]
round_tag = next((t for t in texts if t.startswith("第 ") and t.endswith(" 轮")), "—")
actions = [d for d in descs if d in ("生词库", "设置", "更多", "结束会话", "关闭", "查看评测", "复听原文", "播放改写", "收进生词本", "继续对话")]
print(f"  对话进度: {round_tag}")
print("  关键操作项:", "、".join(dict.fromkeys(actions)) or "无")
print("  主要文本摘要:", " / ".join(texts[:6]) or "—")
'
}

state() {
  dump || return 1
  local out
  out=$(printf '设备标识: %s  前台视窗: %s\n' "$DEV" "$(foreground)"; printf '当前所处界面: %s\n' "$(screen_kind)"; signature)
  printf '%s\n' "$out"
}

el() {
  dump || return 1
  nodes > "$NODES_TSV"
  python3 - "$NODES_TSV" <<'PY'
import sys, re
for line in open(sys.argv[1], encoding='utf-8', errors='ignore'):
    parts = line.rstrip("\n").split("\t")
    if len(parts) < 6: continue
    b, clk, txt, cd, cls, pk = parts[:6]
    if clk != "true" and not (txt or cd): continue
    if pk == "com.android.systemui": continue
    n = [int(v) for v in re.findall(r"-?\d+", b)]
    if len(n) < 4: continue
    mark = "可交互" if clk == "true" else "      "
    name = (txt or cd)[:40]
    print(f"  {(n[0]+n[2])//2:4d},{(n[1]+n[3])//2:4d}  {b:22s} {mark} {name}")
PY
}

wait_ready() { sleep "${1:-1.0}"; }

# 使用 80ms 滑动模拟稳定按压，防止常规 input tap 偶发被 Compose 手势仲裁忽略
press() {
  A shell input swipe "$1" "$2" "$1" "$2" "${3:-80}" >/dev/null
}

# 长按按住说话：零距离 input swipe 在 Android 16 上会被当成瞬时点按，
# 长按手势必须拆成 DOWN/UP 两次事件，中间才能真正停留。
mic_down() { A shell input motionevent DOWN 987 2226 >/dev/null; }
mic_up() { A shell input motionevent UP 987 2226 >/dev/null; }
mic_move() { A shell input motionevent MOVE "$1" "$2" >/dev/null; }
mic_cancel() { A shell input swipe 987 2226 987 1900 "${1:-400}" >/dev/null; }

# 探针：读应用自己写的采集状态，绕开被 ROM 屏蔽的 logcat
capture_state() {
  # vivo 的 Android 16 上隐式广播不会送到清单里的接收器，必须显式指定组件
  A shell am broadcast -n com.voca.speaker/.debug.CaptureProbeReceiver -a com.voca.speaker.CAPTURE_PROBE >/dev/null 2>&1
  sleep 0.3
  A exec-out run-as com.voca.speaker cat files/capture_state.txt 2>/dev/null | tr -d '\r'
}

tap_name() {
  local want="$1" xy before after
  dump || return 1
  nodes > "$NODES_TSV"
  xy=$(python3 - "$want" "$NODES_TSV" <<'PY'
import sys, re
want = sys.argv[1]
rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[2], encoding='utf-8', errors='ignore') if l.strip()]
def c(b):
    n = [int(v) for v in re.findall(r'-?\d+', b)]
    return (n[0] + n[2]) // 2, (n[1] + n[3]) // 2

exact = [r for r in rows if len(r) > 3 and want in (r[2], r[3])]
part = [r for r in rows if len(r) > 3 and (want in r[2] or want in r[3])]
pick = exact or part
if not pick:
    sys.stderr.write("未匹配到该元素\n")
    sys.exit(3)
if len(pick) > 1 and not exact:
    sys.stderr.write("匹配到多个候选项: " + " | ".join((r[2] or r[3]) + "@" + r[0] for r in pick[:6]) + "\n")
    sys.exit(4)
x, y = c(pick[0][0])
print(x, y)
PY
) || { echo "未定位到元素「${want}」，请先执行 el 查看当前视图节点"; return 1; }
  before=$(screen_kind)
  press ${xy% *} ${xy#* }
  wait_ready 1.2
  dump || return 1
  after=$(screen_kind)
  echo "已点击「${want}」@ ${xy% *},${xy#* }：$before → $after"
}

# 模拟长按「按住说话」录音指定毫秒数后松手
simulate_record() {
  local duration="${1:-2500}"
  echo "开始模拟长按录音（持续 ${duration}ms）..."
  mic_down
  sleep "$(python3 -c "print($duration/1000)")"
  mic_up
  wait_ready 1.5
  state
}

# 模拟长按录音并向上滑动取消
simulate_record_cancel() {
  dump || return 1
  nodes > "$NODES_TSV"
  local xy
  xy=$(python3 - "按住说话" "$NODES_TSV" <<'PY'
import sys, re
rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[2], encoding='utf-8', errors='ignore') if l.strip()]
pick = [r for r in rows if len(r) > 3 and "按住说话" in (r[2], r[3])]
if pick:
    n = [int(v) for v in re.findall(r'-?\d+', pick[0][0])]
    print((n[0] + n[2]) // 2, (n[1] + n[3]) // 2)
else:
    print(540, 2256)
PY
)
  local x=${xy% *} y=${xy#* }
  local target_y=$((y - 350))
  echo "开始模拟录音并上滑取消（从 y=${y} 滑至 y=${target_y}）..."
  A shell input swipe "$x" "$y" "$x" "$target_y" 1200 >/dev/null
  wait_ready 1.2
  state
}

# 编译并打包应用
build_app() {
  echo "执行 Gradle 调试包构建..."
  android/gradlew -p android assembleDebug
}

# 推包安装与安装拦截确认自适应处置
install_app() {
  local apk="${1:-android/app/build/outputs/apk/debug/app-debug.apk}" dry=0 out
  [ "$apk" = "--dry" ] && { dry=1; apk="android/app/build/outputs/apk/debug/app-debug.apk"; }
  [ -f "$apk" ] || { echo "未找到目标安装包：${apk}，请先执行 .agents/skills/android-device-testing/scripts/android-drive.sh build 构建"; return 1; }

  if [ "$dry" = 1 ]; then
    echo "即将执行的静默安装命令："
    echo "  adb -s $DEV push $apk /data/local/tmp/voca.apk"
    echo "  adb -s $DEV shell pm install -r -d /data/local/tmp/voca.apk"
    return 0
  fi

  echo "推送 APK 文件至设备临时目录..."
  A push "$apk" /data/local/tmp/voca.apk >/dev/null || { echo "文件推送失败"; return 1; }

  echo "启动静默安装流程..."
  A shell pm install -r -d /data/local/tmp/voca.apk >/tmp/voca-install.log 2>&1 &
  local install_pid=$!

  # 拦截框在不同系统版本与弹窗高度上会挪位，「继续安装」按一组实测坐标逐个试，
  # 每点一次回读前台确认是否已经离开拦截页；没有进展就停手报错，绝不无限等。
  local candidates=("541 2217" "540 2210" "302 2267" "720 2200")
  local deadline=$(( $(date +%s) + ${VOCA_INSTALL_TIMEOUT:-150} ))
  while kill -0 "$install_pid" 2>/dev/null; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      kill "$install_pid" 2>/dev/null
      echo "安装超时：系统拦截框一直没被确认。请在手机上解锁后手动点「继续安装」，或到开发者选项里打开「USB 安装」权限"
      return 1
    fi
    local top
    top=$(A shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity || true)
    if echo "$top" | grep -q packageinstaller; then
      local c now
      for c in "${candidates[@]}"; do
        A shell input tap $c >/dev/null 2>&1
        sleep 1
        now=$(A shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity || true)
        echo "$now" | grep -q packageinstaller || break
      done
    fi
    sleep 1
  done
  wait "$install_pid"

  out=$(cat /tmp/voca-install.log)
  echo "$out"

  # 装完之后 vivo 还会推一页「安全守护 / 超级守护」的 InstallSwitchActivity 盖在应用上，
  # 它不是安装确认框，也不需要点击，按两次返回就回到底下的应用。
  local i
  for i in 1 2; do
    if A shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | grep -q packageinstaller; then
      A shell input keyevent KEYCODE_BACK >/dev/null 2>&1
      sleep 1
    fi
  done

  case "$out" in
    *Success*)
      echo "应用安装成功完成"
      ;;
    *)
      echo "安装未顺利结束，如存在未知应用安装授权框请执行 state 查看"
      return 1
      ;;
  esac
}

case "${1:-state}" in
  devices)        $ADB devices -l; echo "当前选定测试设备: $DEV" ;;
  state)          state ;;
  el|elements)    el ;;
  tap)            tap_name "${2:?请输入要点击的元素名称}" ;;
  press)          press "$2" "$3" "${4:-80}"; wait_ready 1.0; echo "完成坐标点击 ($2, $3)" ;;
  record)         simulate_record "${2:-2500}" ;;
  mic-down)       mic_down; echo "已按下录音" ;;
  mic-up)         mic_up; wait_ready 1.0; echo "已松手"; state ;;
  mic-move)       mic_move "$2" "$3"; echo "已移动到 ($2, $3)" ;;
  mic-cancel)     mic_cancel "${2:-400}"; wait_ready 1.2; state ;;
  capture)        capture_state ;;
  record-cancel)  simulate_record_cancel ;;
  vocab)          tap_name "生词库" ;;
  settings)       tap_name "设置" ;;
  review)         tap_name "查看评测" || tap_name "本轮评测" ;;
  selfcheck)      tap_name "连接自检" ;;
  shot)           A exec-out screencap -p > "$SHOTDIR/voca-${2:-shot}.png" && echo "截图保存路径: $SHOTDIR/voca-${2:-shot}.png" ;;
  build)          build_app ;;
  install)        install_app "${2:-}" ;;
  launch)         A shell am start -n "$MAIN_ACT" >/dev/null; wait_ready 2.0; state ;;
  home)           A shell input keyevent KEYCODE_HOME >/dev/null; wait_ready 1.2; state ;;
  back)           A shell input keyevent KEYCODE_BACK >/dev/null; wait_ready 1.0; state ;;
  # 探针广播、字体设置这类一次性 adb 调用走这里，不必在外部另写包装脚本。
  raw)            shift; A "$@" ;;
  *)              echo "用法提示详见脚本头部说明或 SKILL.md 文档"; exit 1 ;;
esac
