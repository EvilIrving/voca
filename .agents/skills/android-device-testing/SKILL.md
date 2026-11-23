---
name: android-device-testing
description: "Parley 口语客户端 Android 真机自动化测试驱动。用于在这台 vivo 手机上启动 App、在主对话、长按收音、评测抽屉、生词库与设置之间按名字精准交互、校验页面流转与元素特征，以及执行免打扰的静默装机与屏幕状态判别。"
---

# Parley Android 真机自动化测试规范

以仓库根目录 `AGENTS.md` 确立的产品规约为基础，本测试驱动专门保障 Parley 客户端在 Android 真机环境下的可观测性、交互可靠性与验证闭环。

## 驱动与资源组件

| 资源路径 | 用途说明 |
| --- | --- |
| `scripts/android-drive.sh` | 核心执行驱动（相对路径 `.agents/skills/android-device-testing/scripts/android-drive.sh`），纯基于 `adb` 与 `/tmp`，无需外部重型测试框架 |
| `references/element-map.md` | 真机全界面元素坐标映射表，记录核心按钮、文本节点与布局边界 |
| `references/pitfalls.md` | 真机实测踩坑记录与排查经验汇总 |

**执行环境约束**：所有指令必须在**仓库根目录**下执行。脚本具备路径自我校验机制，从其他目录调用将自动提示并退出，确保工程临时文件统一受限于 `/tmp`，保持工作区整洁。

---

## 1. 设备接入与动态端口发现

无线调试环境下服务监听端口每次重连均会动态变化，严禁在脚本或文档中硬编码固定的 IP 与端口。标准连接步骤如下：

```bash
adb devices                        # 检查当前设备连接状态；已显示 device 即可直接使用
adb mdns services                  # 检索局域网内广播的 ADB 服务
```

在 `adb mdns services` 输出中，仅提取带有 `_adb-tls-connect._tcp` 标识的那一行末尾地址：

```
adb-ANDROID_SERIAL-xxxxx  _adb-tls-connect._tcp  192.168.x.x:5xxxx
                                     ↑ 取此行末尾的真实 ip:port
```

```bash
adb connect 192.168.x.x:5xxxx      # 连接提取到的动态地址
adb devices -l                     # 确认状态变更为 device
```

**连接规则**：
- 仅认准 `_adb-tls-connect._tcp`，请勿连接 `_adb._tcp` 或 `_adb-tls-pairing._tcp` 配对通道。
- 若提示连接拒绝或超时，优先执行 `adb kill-server && adb start-server` 并重查 mDNS，或使用 USB 数据线直连。
- 多设备并存时，可通过环境变量 `PARLEY_DEV` 指定目标序列号，脚本默认自动选取唯一的有效设备。

---

## 2. 状态感知准则：先读屏，再动手

任何界面交互或断言之前，必须首先调用状态读取指令确认当前视窗与页面归属：

```bash
.agents/skills/android-device-testing/scripts/android-drive.sh state
```

该指令输出包含三项核心上下文信息：
1. **当前前台应用**：本应用（`com.parley.speaker`）、系统桌面（`com.bbk.launcher2`）或系统权限弹框。
2. **所在页面特征**：`app:主对话`、`app:收音中`、`app:评测抽屉`、`app:生词库`、`app:设置`。
3. **关键操作项与文本**：对话轮次以及屏幕上出现的交互控件。

### 页面判别特征表

Compose 声明式界面采用全屏覆盖浮层呈现，层叠组件在底层语义树中依然可见。判据严格按照优先级自上而下匹配：

| 页面类别 | 判据特征 | 到达路径 | 返回路径 |
| --- | --- | --- | --- |
| `系统:安装确认框` | 前台为 `*packageinstaller*` | 推送安装包触发 | 自动点击标定坐标 `(302, 2267)` |
| `系统:权限框` | 前台为 `*permissioncontroller*` | 首次录音请求权限 | 授予权限或拒绝返回 |
| `系统:设置页` | 前台为 `*settings*` | 授权跳转 | 物理返回键 |
| `app:收音中` | 包含 `松手发送` 或 `上滑取消` | 长按「按住说话」 | 松开手指或上滑取消 |
| `app:评测抽屉` | 包含 `本轮评测` 标题 | 点击气泡下方的评分徽标 | 点击「关闭」或「继续对话」 |
| `app:生词库` | 包含 `生词库` 标题与 `搜索词或音标` | 点击顶栏生词本图标 | 点击右上角「关闭」图标 |
| `app:设置` | 包含 `设置` 标题与 `实时对话` / `连接自检` | 点抽屉右下角的设置 | 点击右上角「关闭」图标 |
| `app:主对话` | 包含 `按住说话` 文本，或顶栏动作图标健全 | 启动应用或关闭上层浮层 | 挂断会话或按 Home 回桌面 |
| `系统桌面` | 包含 `第N页，共M页` | 物理 Home 键 | 执行 `launch` 启动应用 |

---

## 3. 指令集参考速查

| 指令语法 | 作用与执行说明 |
| --- | --- |
| `devices` | 列出所有已连接设备并输出当前选定机器 |
| `state` | 输出前台视窗、当前页面、对话轮次与核心操作项 |
| `el` 或 `elements` | 输出当前屏内所有可见可交互元素及其中心点物理坐标 |
| `tap <名字>` | 检索指定的文本或 `content-desc`，施加 80ms 稳定按压，并对比输出状态跳转差异 |
| `press <x> <y> [ms]` | 按指定绝对物理坐标点按，默认按压 80ms |
| `record [毫秒数]` | 模拟长按「按住说话」录音（默认 2500ms 后松手发送） |
| `record-cancel` | 模拟长按录音并向上滑动超出 64dp 取消阈值，验证上滑取消交互 |
| `vocab` | 快速打开全屏生词库面板 |
| `settings` | 快速打开设置 |
| `review` | 快速打开本轮评测分析抽屉 |
| `selfcheck` | 在设置内触发模型连接自检测试 |
| `shot [名称]` | 截取当前设备屏幕并保存至 `/tmp/parley-<名称>.png` |
| `build` | 调用 `android/gradlew assembleDebug` 执行构建（内置 Java 17 环境支持） |
| `install [apk]` | 推送安装包并调用系统安装服务，内置 vivo 拦截弹窗自动点按处理 |
| `launch` | 冷启动或唤醒应用主界面 |
| `home` | 模拟物理 Home 键回桌面 |
| `back` | 模拟物理返回键 |
| `raw <adb 参数...>` | 透传一次 adb 调用（探针广播、`run-as` 读文件这类一次性命令），外层不必再写包装脚本 |

详细元素清单参阅 `references/element-map.md`；排查要点参阅 `references/pitfalls.md`。

---

## 4. 自动化构建与静默装机流程

vivo 机型在执行覆盖安装时，会在系统底层唤醒 `PackageInterceptActivity` 拦截界面并暂停命令响应。整套处置都收在 `install` 里，一条命令跑完，外面不用再套监控循环：

```bash
DRV=.agents/skills/android-device-testing/scripts/android-drive.sh
$DRV build                      # 构建 debug 变体，产物在 android/app/build/outputs/apk/debug/
$DRV install                    # 推送 + 安装 + 盯拦截框 + 收尾，默认 150 秒超时
$DRV state                      # 校验装机后落地界面
$DRV shot install-verify        # 保存界面截图备查
```

流程技术要点：
- 安装进程在后台跑，脚本每秒读一次 `dumpsys activity activities` 的前台 Activity；拦截期间 `uiautomator dump` 会被锁死，只能走这条。
- 「继续安装」按钮在这台 vivo PD2454（1080×2376）上实测中心是 `(541, 2217)`；脚本按 `(541, 2217)`、`(540, 2210)`、`(302, 2267)`、`(720, 2200)` 逐个试，每点一次回读前台，离开拦截页就停手。
- 整套最多等 `PARLEY_INSTALL_TIMEOUT`（默认 150 秒），到点直接退出并提示去开发者选项打开「USB 安装」权限。
- 安装成功后 vivo 可能再推一页「安全守护 / 超级守护」（`InstallSwitchActivity`），那不是安装确认，脚本连按两次返回把它收掉。
- 构建与装机分成两条命令：`gradlew installDebug` 把安装混在 Gradle 任务里同步等待，遇到拦截框时整条命令停在安装阶段、不输出任何进展。
- 装机完毕后应用进程会自动重置，必须先调用 `state` 确认当前所处页面后再开展后续验证。
