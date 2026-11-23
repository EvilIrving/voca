#!/usr/bin/env python3
import os
from pathlib import Path

root = Path(__file__).resolve().parents[1]
env_path = root / ".env"
out = root / "ios" / "Parley" / "Generated" / "Secrets.swift"
out.parent.mkdir(parents=True, exist_ok=True)

env = {}
if env_path.exists():
    for line in env_path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip()


def s(key: str, default: str = "") -> str:
    return env.get(key, default).replace("\\", "\\\\").replace('"', '\\"')


# 这个脚本由 Xcode 的预编译阶段跑，Release 也跑一遍，所以密钥只在 Debug 配置里注入。
# 手动运行时没有 CONFIGURATION，按 Debug 处理，开发机上照旧带着值。
debug = os.environ.get("CONFIGURATION", "Debug") == "Debug"


def credential(key: str) -> str:
    return s(key) if debug else ""


out.write_text(
    "enum Secrets {\n"
    f'    static let geminiKey = "{credential("GEMINI_API_KEY")}"\n'
    f'    static let geminiLiveModel = "{s("GEMINI_LIVE_MODEL", "gemini-3.8-live")}"\n'
    f'    static let geminiTextModel = "{s("GEMINI_TEXT_MODEL", "gemini-flash-latest")}"\n'
    f'    static let geminiVoice = "{s("GEMINI_VOICE", "Aoede")}"\n'
    f'    static let xfyunAppId = "{credential("XFYUN_APP_ID")}"\n'
    f'    static let xfyunApiKey = "{credential("XFYUN_API_KEY")}"\n'
    f'    static let xfyunApiSecret = "{credential("XFYUN_API_SECRET")}"\n'
    "}\n"
)
