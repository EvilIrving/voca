# Figma 画板生成脚本

`Speaker App` 画板（fileKey `6br1NEidzHTn39lmijberV`）由这几个脚本生成，重跑即幂等重建对应页面，不留残稿。

`lib.js` 是构件助手，包含令牌、`frame` / `t` / `icon` / `comp` / `inst`；`fluent-compact.js` 存微软 Fluent System Icons 的 path 数据，`icon()` 从这里取图。上面两个是所有脚本的前置。

`components.js` 建 `01 · 组件` 页的原子与复合组件（含下拉菜单、选项行、动作行、抽屉的检索与新会话胶囊、会话行、设置页钮），`org.js` 建组织层（顶栏六个变体、底栏、收音面板与取消态、中空声波柱、按压钮），`screens.js` 建 `02 · 界面` 页的十六套 360×792 视图并全部用组件实例，`drawer.js` 建 `侧边抽屉交互原型` 页的三态与参数看板，`tokens.js` 建 `00 · 规范` 页。

`minify.py` 在拼脚本之后删注释、缩进与标点两侧的空格：Figma 的 `use_figma` 工具一次最多收五万字符，整份组件页拼起来已经贴近这条线，斜杠两侧的空格一律留着，正则字面量不能被改。

```bash
./build.sh components "重建组件页"
./build.sh org "重建组织层"
./build.sh screens "重建界面页"
./build.sh drawer "重建抽屉原型"
./build.sh tokens "重建规范页"
```

顺序是 components → org → screens → drawer → tokens，后面的脚本按组件名取实例，前一步没跑就取不到。脚本走 `swift-daily-oracle/tools/figma-mcp/run.py` 直连 Figma 官方 remote MCP，需要个人访问令牌。

插件 API 的几个坑：Auto Layout 帧先 `resize` 会把该轴锁成 FIXED，要先设 `layoutMode` 再 `resize`，需要 hug 时再把对应轴设回 AUTO；绝对定位子节点要先写 `layoutPositioning`，再写 x/y，而普通帧的子节点不能设绝对定位，直接改坐标；实例内部的节点改不了旋转，会报 `relative-transform` 不能覆盖，开合方向要靠组件变体里的上指向图元解决；菜单实例里的选项文字可以逐条覆盖，字体与字号也能改。
