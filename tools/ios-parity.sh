#!/usr/bin/env bash
# 把 iOS 端口的硬规矩收成脚本：文案从安卓界面抄、不搬安卓实现、
# 电平不进会话树、输入条用 DisplayLink、高刷打开。
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
python3 - "$root" <<'PY'
import re, sys
from pathlib import Path

root = Path(sys.argv[1])
ios = root / "ios" / "Parley"
android_java = root / "android" / "app" / "src" / "main" / "java" / "com" / "parley" / "speaker"
failed = 0

def fail(msg):
    global failed
    failed = 1
    print(f"FAIL  {msg}")

def ok(msg):
    print(f"ok    {msg}")

plist = ios / "Info.plist"
yml = root / "ios" / "project.yml"
if "CADisableMinimumFrameDurationOnPhone" not in plist.read_text():
    fail("Info.plist 没有 CADisableMinimumFrameDurationOnPhone")
else:
    ok("Info.plist 打开了高刷")
if "CADisableMinimumFrameDurationOnPhone" not in yml.read_text():
    fail("project.yml 没有 CADisableMinimumFrameDurationOnPhone")
else:
    ok("project.yml 打开了高刷")

forbidden = [
    (r"\bAudioRecord\b", "AudioRecord"),
    (r"\bAudioTrack\b", "AudioTrack"),
    (r"\bOkHttp\b", "OkHttp"),
    (r"\bEncryptedSharedPreferences\b", "EncryptedSharedPreferences"),
    (r"androidx\.room", "Room"),
    (r"FluentIcons", "FluentIcons"),
    (r"createNodeFromSvg", "SVG parse"),
]
swift_files = list(ios.rglob("*.swift"))
for pattern, label in forbidden:
    hits = []
    rx = re.compile(pattern)
    for path in swift_files:
        text = path.read_text()
        if rx.search(text):
            hits.append(str(path.relative_to(root)))
    if hits:
        fail(f"搬了安卓实现 {label}：{' '.join(hits)}")
    else:
        ok(f"没有 {label}")

store = (ios / "Chat" / "ChatStore.swift").read_text()
if re.search(r"@Published[^\n]*(elapsed|levels|levelFrame|meter)", store):
    fail("ChatStore 把电平或计时发进了 @Published")
else:
    ok("ChatStore 没有发布电平或计时")
if "if state != next" not in store:
    fail("ChatStore.project 没有在状态没变时跳过发布")
else:
    ok("ChatStore.project 状态没变就不发布")

bar = (ios / "Chat" / "VoiceInputBar.swift").read_text()
if "TimelineView" in bar:
    fail("输入条还在用 TimelineView")
else:
    ok("输入条不用 TimelineView")
if "DisplayLinkView" not in bar and "DisplayPulse" not in bar:
    fail("输入条没有 DisplayLink 节拍")
else:
    ok("输入条用 DisplayLink 取电平")

meter = (ios / "Audio" / "VoiceMeter.swift").read_text()
if "class DisplayLinkView" not in meter:
    fail("VoiceMeter 里没有 DisplayLinkView")
else:
    ok("DisplayLinkView 在输入条这一层")
if "class DisplayPulseHub" not in meter:
    fail("电平节拍没有收成一条 DisplayLink")
else:
    ok("输入条共用一条 DisplayLink")

quoted = re.compile(r'"((?:\\.|[^"\\])*)"')
cjk = re.compile(r"[\u4e00-\u9fff]")
kotlin_interp = re.compile(r"\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_.]*")

def strip_swift_interp(value):
    out = []
    i = 0
    while i < len(value):
        if value[i] == "\\" and i + 1 < len(value) and value[i + 1] == "(":
            i += 2
            depth = 1
            while i < len(value) and depth:
                if value[i] == "(":
                    depth += 1
                elif value[i] == ")":
                    depth -= 1
                i += 1
            out.append("*")
        else:
            out.append(value[i])
            i += 1
    return "".join(out)

def normalize(value, suffix):
    if suffix == ".swift":
        value = strip_swift_interp(value)
    else:
        value = kotlin_interp.sub("*", value)
    return re.sub(r"\*+(?:\(\))*", "*", value)

def gather(paths):
    found = set()
    for path in paths:
        if not path.exists():
            continue
        files = [path] if path.is_file() else list(path.rglob("*"))
        for file in files:
            if file.suffix not in {".kt", ".swift"}:
                continue
            for match in quoted.finditer(file.read_text()):
                value = match.group(1)
                if "\\u" in value:
                    value = value.encode("utf-8").decode("unicode_escape")
                if cjk.search(value):
                    found.add(normalize(value, file.suffix))
    return found

android_copy = gather([
    android_java / "ui",
    android_java / "domain" / "TurnFlow.kt",
    android_java / "domain" / "CaptureFailure.kt",
    android_java / "data" / "realtime" / "ProviderFault.kt",
    android_java / "ui" / "chat" / "ChatViewModel.kt",
])
ios_copy = gather([
    ios / "Chat",
    ios / "Settings",
    ios / "Vocab",
    ios / "Session",
    ios / "Review",
    ios / "Component",
    ios / "Domain" / "TurnFlow.swift",
    ios / "Data" / "ProviderFault.swift",
    ios / "Data" / "Realtime.swift",
    ios / "App" / "RootView.swift",
])
extra = sorted(ios_copy - android_copy)
# 系统提示词正文、开场材料不是界面文案，不进这对。
if extra:
    fail("iOS 多出来的界面中文： " + " | ".join(extra))
else:
    ok(f"界面中文都能在安卓对上，共 {len(ios_copy)} 条")

sys.exit(failed)
PY
