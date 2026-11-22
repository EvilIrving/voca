#!/usr/bin/env bash
# iOS 人声判定的离线核对：直接把仓库里那两份 Swift 源码编成命令行程序，
# 拿真实音频跑一遍，证明"没人说话"与"压着嗓子说话"两边判得开。
# 依赖 macOS 自带的 SoundAnalysis 与 AVFoundation，不需要模拟器也不需要真机。
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d /tmp/parley-vad.XXXXXX)
trap 'rm -rf "$work"' EXIT

cp "$root/ios/Parley/Domain/SpeechGate.swift" "$work/"
cp "$root/ios/Parley/Audio/SpeechDetector.swift" "$work/"
cp "$root/android/app/src/test/resources/speech_16k.pcm" "$work/speech.pcm"

cat > "$work/main.swift" <<'SWIFT'
import Foundation

func load(_ path: String) -> [Int16] {
    let data = try! Data(contentsOf: URL(fileURLWithPath: path))
    var out = [Int16](repeating: 0, count: data.count / 2)
    data.withUnsafeBytes { raw in
        for i in out.indices {
            let lo = Int16(raw[i * 2]) & 0x00FF
            let hi = Int16(bitPattern: UInt16(raw[i * 2 + 1]) << 8)
            out[i] = hi | lo
        }
    }
    return out
}

func scaled(_ pcm: [Int16], _ db: Double) -> [Int16] {
    let peak = pcm.reduce(0) { max($0, abs(Int($1))) }
    guard peak > 0 else { return pcm }
    let gain = 32768.0 * pow(10, db / 20) / Double(peak)
    return pcm.map { Int16(max(-32768, min(32767, Int(Double($0) * gain)))) }
}

func noise(_ n: Int, _ db: Double) -> [Int16] {
    var rng = SystemRandomNumberGenerator()
    let amp = 32768.0 * pow(10, db / 20)
    return (0..<n).map { _ in Int16((Double.random(in: -1...1, using: &rng) * amp).rounded()) }
}

func tone(_ n: Int, _ hz: Double, _ db: Double) -> [Int16] {
    let amp = 32768.0 * pow(10, db / 20)
    return (0..<n).map { Int16((sin(2 * Double.pi * hz * Double($0) / 16000) * amp).rounded()) }
}

func tap(_ n: Int) -> [Int16] {
    var out = [Int16](repeating: 0, count: n)
    let amp = 32768.0 * pow(10, -15.0 / 20)
    for i in 1000..<1960 { out[i] = Int16((Double.random(in: -1...1) * amp).rounded()) }
    return out
}

/// 语音前头再砸一下：手指碰到机壳那一下能顶到满量程，参照电平不该跟着它走。
func struck(_ pcm: [Int16], _ db: Double) -> [Int16] {
    let amp = 32768.0 * pow(10, db / 20)
    var out = [Int16](repeating: 0, count: 8000)
    for i in 0..<960 { out[i] = Int16((Double.random(in: -1...1) * amp).rounded()) }
    out.append(contentsOf: pcm)
    return out
}

let speech = load(CommandLine.arguments[1])
let cases: [(String, [Int16], Bool)] = [
    ("正常语音", speech, true),
    ("压到负六十分贝", scaled(speech, -60), true),
    ("压到负六十五分贝", scaled(speech, -65), true),
    ("压到负七十分贝", scaled(speech, -70), true),
    ("纯静音", [Int16](repeating: 0, count: 48000), false),
    ("白噪声负四十分贝", noise(48000, -40), false),
    ("白噪声负五十五分贝", noise(48000, -55), false),
    ("白噪声负六十五分贝", noise(48000, -65), false),
    ("二百二十赫兹纯音", tone(48000, 220, -20), false),
    ("一声六十毫秒敲击", tap(48000), false),
    ("压到负三十分贝再加一声撞击", struck(scaled(speech, -30), -3), true),
    ("压到负五十五分贝再加一声撞击", struck(scaled(speech, -55), -3), true),
]

var failures = 0
for (name, pcm, expected) in cases {
    let windows = SpeechDetector.windows(pcm)
    let ms = SpeechGate.longestSpeechMs(windows)
    let has = SpeechGate.hasSpeech(windows)
    if has != expected { failures += 1 }
    let mark = has == expected ? "通过" : "失败"
    print(String(format: "%-12@ 最长人声 %5dms 判定 %@ 期望 %@ %@",
                 name as NSString, ms, has ? "说话" : "静音", expected ? "说话" : "静音", mark as NSString))
}
if failures > 0 {
    print("人声判定核对失败 \(failures) 条")
    exit(1)
}
print("人声判定核对全部通过")
SWIFT

cd "$work"
swiftc -O SpeechGate.swift SpeechDetector.swift main.swift -o vad-check 2>/dev/null
./vad-check "$work/speech.pcm"
