#!/bin/bash
# 用法：./build.sh <components|org|screens|tokens> "说明"
set -e
name=$1
desc=${2:-重建画板}
dir=$(cd "$(dirname "$0")" && pwd)
cat "$dir/fluent-compact.js" "$dir/lib.js" "$dir/$name.js" | python3 "$dir/minify.py" > "/tmp/figma-$name.js"
wc -c "/tmp/figma-$name.js"
cd /Users/actor/Documents/code/swift-daily-oracle/tools/figma-mcp
python3 run.py "/tmp/figma-$name.js" --file 6br1NEidzHTn39lmijberV --desc "$desc"
