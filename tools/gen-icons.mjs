// 从 tools/figma/fluent-compact.js 的 FLUENT 表生成 Compose 图标源码。
// 用法：node tools/gen-icons.mjs
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const src = readFileSync(resolve(root, "tools/figma/fluent-compact.js"), "utf8");
const json = src.slice(src.indexOf("{"), src.lastIndexOf("}") + 1);
const table = JSON.parse(json);

const pathOf = (svg) => {
  const m = svg.match(/d="([^"]+)"/);
  if (!m) throw new Error("no path in " + svg);
  return m[1];
};

const esc = (s) => s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');

const entries = Object.entries(table).map(([name, v]) => {
  const [vw, vh] = v.vb.split(" ").slice(2).map(Number);
  return { name, vw, vh, d: pathOf(v.d) };
});

const kotlinName = (n) => n[0].toUpperCase() + n.slice(1);

const body = entries
  .map(
    (e) => `    val ${kotlinName(e.name)}: ImageVector by lazy {
        vector("${e.name}", ${e.vw}f, ${e.vh}f, "${esc(e.d)}")
    }`,
  )
  .join("\n\n");

const out = `package com.parley.speaker.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// 由 tools/gen-icons.mjs 从微软 Fluent System Icons 的真实路径生成，改动请回到脚本。
object FluentIcons {
    private val cache = HashMap<String, ImageVector>()

    private fun vector(name: String, width: Float, height: Float, path: String): ImageVector =
        cache.getOrPut(name) {
            ImageVector.Builder(
                name = name,
                defaultWidth = width.dp,
                defaultHeight = height.dp,
                viewportWidth = width,
                viewportHeight = height,
            ).apply {
                addPath(
                    pathData = PathParser().parsePathString(path).toNodes(),
                    fill = SolidColor(Color.Black),
                )
            }.build()
        }

${body}
}
`;

const target = resolve(root, "android/app/src/main/java/com/parley/speaker/ui/icon/FluentIcons.kt");
mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, out);
console.log(`wrote ${target} with ${entries.length} icons`);

const swiftPaths = entries
  .map(
    (e) => `        case .${e.name}: return "${esc(e.d)}"`,
  )
  .join("\n");

const swiftSizes = entries
  .map(
    (e) => `        case .${e.name}: return CGSize(width: ${e.vw}, height: ${e.vh})`,
  )
  .join("\n");

const swift = `import SwiftUI

enum Fluent: String, CaseIterable {
${entries.map((e) => `    case ${e.name}`).join("\n")}

    var viewport: CGSize {
        switch self {
${swiftSizes}
        }
    }

    var path: String {
        switch self {
${swiftPaths}
        }
    }
}
`;

const swiftTarget = resolve(root, "ios/Parley/Component/Fluent.swift");
mkdirSync(dirname(swiftTarget), { recursive: true });
writeFileSync(swiftTarget, swift);
console.log(`wrote ${swiftTarget} with ${entries.length} icons`);
