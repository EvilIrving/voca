# 已知注意事项与排查经验

本篇记录在 vivo `V2454DA` 真机测试及 Voca 客户端自动化调试过程中总结的技术特征与应对方案。

---

## 1. 点击偶发丢失（手势仲裁吞噬）

- **现象描述**：执行普通点击指令时，界面无跳转响应；连续多次点击后偶发产生动作。
- **原因机理**：底层自动化接口默认的瞬时轻触（tap）按压时间极短（常低于 20ms），容易被 Jetpack Compose 的指针输入系统或手势仲裁机制当成抖动过滤。
- **应对方案**：驱动脚本统一封装 `press` 逻辑，以微小位移的滑动模拟 80ms 的稳定按压；每次调用 `tap_name` 后会自动校验前后界面状态并打印流转结果。

---

## 2. Compose 全屏浮层导致的节点共存

- **现象描述**：设置、生词库或评测抽屉处于前台时，UI 节点转储（dump）仍能提取到底层主界面的「按住说话」或顶栏图标。
- **原因机理**：Compose 采用单一 Activity 承载全屏覆盖动画，底层组件在语义树（SemanticsTree）中仍处于激活或可见状态。
- **应对方案**：在状态判定算法（`screen_kind`）中严格执行优先级判定——优先检查系统弹窗，其次检查顶层浮层特征（如「本轮评测」、「生词库」、「设置」、「松手发送」），全部不命中后再回落至主对话界面。

---

## 3. 麦克风录音权限拦截

- **现象描述**：首次长按「按住说话」时，系统弹出录音权限授予弹窗（`permissioncontroller`），测试流程卡住。
- **原因机理**：Android 系统对 `Manifest.permission.RECORD_AUDIO` 实施运行时权限管控。
- **应对方案**：驱动脚本状态判定能够识别 `系统:权限框`；开发阶段可通过 `adb shell pm grant com.voca.speaker android.permission.RECORD_AUDIO` 预先授予权限，确保自动化流程平稳推进。

---

## 4. 系统静默安装拦截弹窗自动处理

- **现象描述**：执行覆盖安装（`pm install -r -d`）时，系统可能会在后台唤起 `com.android.packageinstaller/.PackageInterceptActivity` 拦截界面（提示「继续安装」），导致控制台阻塞。
- **原因机理**：厂商定制系统会对命令行触发的静默安装执行二次防劫持校验。在拦截状态下 `uiautomator dump` 会因窗口焦点锁定而报错。
- **应对方案**：驱动脚本安装流程采用后台进程监控结合前台顶层 Activity 轮询（`dumpsys activity activities | grep topResumedActivity`），一旦探测到 `packageinstaller` 就按一组实测坐标逐个点，「继续安装」按钮在 1080×2376 上实测中心是 `(541, 2217)`，同组还有 `(540, 2210)`、`(302, 2267)`、`(720, 2200)`，按钮随系统版本与弹窗高度挪位，落空就换下一个；每点一次回读前台，离开拦截页就停手。整套最多等 `VOCA_INSTALL_TIMEOUT`（默认 150 秒），到点直接退出并提示去开「USB 安装」权限。

---

## 5. vivo 系统整机日志等级过滤

- **现象描述**：代码中通过 `Log.d(...)` 输出的调试信息在 logcat 中完全无法检索，误以为执行路径未触达。
- **原因机理**：vivo 商业机型默认在系统底层关闭 Debug 级别日志输出（实测 logcat 中 D 级日志总数为 0）。
- **应对方案**：项目全量埋点与状态输出统一使用 `Log.i(...)` 及以上级别；排查问题时只看 `Log.i` 及以上级别的输出。

---

## 6. 上滑取消手势的位移阈值判定

- **现象描述**：模拟收音取消动作时，手指离开屏幕后依然触发了语音发送。
- **原因机理**：Compose 端代码设定取消阈值为 64dp（在 480dpi 屏幕下换算约为 192px 垂直位移），位移不足时无法激活取消标记。
- **应对方案**：脚本中的 `record-cancel` 命令从拾音胶囊中心点（y≈2256）向上滑动 350px，确保稳定穿越取消判定临界区。

---

## 7. macOS 缺失 GNU timeout 命令

- **现象描述**：在自动化命令中加入 `timeout` 保护会导致 macOS zsh 报 `command not found`。
- **原因机理**：macOS 默认 BSD 工具链未包含 GNU coreutils 的 `timeout`。
- **应对方案**：进程生命周期轮询统一采用 POSIX 标准的 `kill -0 $PID` 循环判定，避免平台特有命令依赖。

---

## 8. Java 编译环境自动适配

- **现象描述**：调用 `./gradlew` 时报 `Unable to locate a Java Runtime`。
- **原因机理**：macOS 环境下如未设置全局 `JAVA_HOME`，Gradle 包装器无法正确定位 JVM。
- **应对方案**：驱动脚本内置自动检测逻辑，当检测到系统未导出 `JAVA_HOME` 时，自动装载 `/opt/homebrew/opt/openjdk@17`，保障构建命令即开即用。

---

## 9. 滚动导致元素坐标位移

- **现象描述**：生词库或设置滚动后，原先记录的坐标点击失效。
- **原因机理**：列表组件按需渲染，滚动后元素物理坐标发生位移，部分项目甚至移出视窗。
- **应对方案**：一律先滚动后执行 `el` 重新捕获当前可视区域节点，避免复用过时的绝对坐标。

---

## 10. 零距离 swipe 打不出长按

- **现象描述**：`adb shell input swipe x y x y 3000` 在 Android 16 上被当成瞬时点按，「按住说话」的录音面板根本不出现；同一命令点击普通按钮却正常。
- **原因机理**：起止坐标相同时系统把手势折叠成一次 tap，按下与抬起之间没有停留，Compose 的 `pointerInput` 收到 DOWN 后立刻收到 UP。
- **应对方案**：长按一律拆成 `input motionevent DOWN` 与 `input motionevent UP` 两条命令，中间停顿即可控制按压时长；驱动脚本的 `mic-down` / `mic-up` / `mic-cancel` 就是这样实现的。

---

## 11. 隐式广播到不了清单接收器

- **现象描述**：`am broadcast -a com.voca.speaker.CAPTURE_PROBE` 返回 `Broadcast completed: result=0`，但接收器没有执行，探针文件仍是旧内容。
- **原因机理**：这台 vivo 的 Android 16 上，自定义 action 的隐式广播不会投递给清单里注册的接收器。
- **应对方案**：广播必须显式指定组件，例如 `am broadcast -n com.voca.speaker/.debug.CaptureProbeReceiver -a com.voca.speaker.CAPTURE_PROBE`。

---

## 12. 探针文件写外部目录会失效

- **现象描述**：探针接收器把状态写进 `/sdcard/Android/data/<包名>/files/` 后，重装一次应用，脚本读到的永远是旧值。
- **原因机理**：重装后外部私有目录的写入可能静默失败，`getExternalFilesDir` 返回可用但写入不落地。
- **应对方案**：探针改写到应用内部目录 `context.filesDir`，脚本用 `adb exec-out run-as <包名> cat files/<名字>` 读取。

## 13. 装机不能用 `gradlew installDebug`

- **现象描述**：把装机交给 Gradle 任务时，构建完成后停在安装这一步长时间没有输出，手机上停着「继续安装」没人点。
- **原因机理**：Gradle 的安装走的是同样的 `pm install` 通道，遇到厂商拦截框就同步等待用户确认，既没有轮询也没有超时，等待期间不输出任何东西。
- **应对方案**：装机只走 `.agents/skills/android-device-testing/scripts/android-drive.sh install`，它自己会构建、推送、盯弹窗、到点报错；构建与装机分成两步跑（先 `build` 再 `install`），需要长时间跑的构建用后台加日志，不要用会把安装混在里面的 Gradle 任务。

## 14. 装完弹「安全守护」页

- **现象描述**：安装成功后前台停着 `com.android.packageinstaller/.InstallSwitchActivity`，显示「安全升级模式 / 超级守护 / 待开启」，应用被压在下面，读屏会把当前界面判成「系统:安装确认框」，后续点击全落在这一页上。
- **原因机理**：厂商在安装成功后追加一页推荐与安全开关，它不是安装确认，也不需要点任何按钮。
- **应对方案**：`install` 结束前若前台仍是 `packageinstaller`，连按两次返回回到应用；读屏看到这一页时同样按返回，不要去找「继续安装」重复点击。
