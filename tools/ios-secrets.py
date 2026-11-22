#!/usr/bin/env python3
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


out.write_text(
    "enum Secrets {\n"
    f'    static let geminiKey = "{s("GEMINI_API_KEY")}"\n'
    f'    static let geminiLiveModel = "{s("GEMINI_LIVE_MODEL", "gemini-3.8-live")}"\n'
    f'    static let geminiTextModel = "{s("GEMINI_TEXT_MODEL", "gemini-flash-latest")}"\n'
    f'    static let geminiVoice = "{s("GEMINI_VOICE", "Aoede")}"\n'
    "}\n"
)
