#!/usr/bin/env bash
# iOS 讯飞评测线上自检：把同一段音频按成功、坏密钥、空音频三种情况各打一次，
# 打印归类结果，成功那条没出分就退出非零。用法：tools/ise-check-ios.sh <pcm 路径> "<原文本>"
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

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# 只补 PhonemeIssue 一处：其余类型直接取 App 里的源文件，编译的是线上那条实现。
cat > "$work/stub.swift" <<'EOF'
import Foundation

struct PhonemeIssue: Codable, Hashable {
    var word: String
    var phonetic: String
    var note: String
}
EOF

cat > "$work/main.swift" <<'EOF'
import Foundation

let pcm = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let text = CommandLine.arguments[2]
let env = ProcessInfo.processInfo.environment
let good = XfyunCredentials(
    appId: env["XFYUN_APP_ID"] ?? "",
    apiKey: env["XFYUN_API_KEY"] ?? "",
    apiSecret: env["XFYUN_API_SECRET"] ?? ""
)

func line(_ label: String, _ outcome: AssessOutcome) {
    switch outcome {
    case .scored(let result):
        print("\(label) scored accuracy=\(result.accuracy ?? -1) fluency=\(result.fluency ?? -1) total=\(result.total ?? -1) issues=\(result.issues.map(\.word))")
    case .failed(let failure):
        print("\(label) failed \(failure) notice=\(failure.notice)")
    }
}

let done = DispatchSemaphore(value: 0)
Task {
    line("good", await XfyunAssessor(credentials: good).assess(pcm16k: pcm, referenceText: text, locale: "en-US"))
    var badKey = good
    badKey.apiKey = String(repeating: "0", count: 32)
    line("badkey", await XfyunAssessor(credentials: badKey).assess(pcm16k: pcm, referenceText: text, locale: "en-US"))
    line("empty", await XfyunAssessor(credentials: good).assess(pcm16k: Data(), referenceText: text, locale: "en-US"))
    done.signal()
}
done.wait()
EOF

xcrun swiftc -o "$work/run" \
  "$work/stub.swift" \
  "$root/ios/Parley/Data/JsonFields.swift" \
  "$root/ios/Parley/Data/ProviderFault.swift" \
  "$root/ios/Parley/Data/SpeechAssessor.swift" \
  "$root/ios/Parley/Data/XfyunAssessor.swift" \
  "$work/main.swift"

"$work/run" "$1" "$2" | tee "$work/out"
grep -q '^good scored' "$work/out"
