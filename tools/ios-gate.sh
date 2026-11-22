#!/usr/bin/env bash
# iOS 验收闸门：先跑一对一口径，再编 Debug。
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
"$root/tools/ios-parity.sh"
cd "$root/ios"
xcodebuild -scheme Parley -destination 'generic/platform=iOS' -configuration Debug \
  -derivedDataPath "$root/ios/DerivedData" \
  CODE_SIGNING_ALLOWED=NO \
  build
