# Telegram 语音输入条：状态机、动效参数与 Lottie 用法

对照对象是本机 Android 版 Telegram（vivo PD2454，1080×2376 @3x）实机录屏与截图。手势与状态机以 Android 源码 `DrKLO/Telegram` 的 `TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java` 为准，iOS 源码（`/Users/actor/Documents/swift 最佳实践/Telegram-iOS`）只作结构与动效参照，两端的数值不同处在文末单列。两端界面形态基本同构，差异集中在动画细节和录音后的编辑能力上，下文逐项标注。

结论先给：Telegram 的语音输入不是"弹出面板"，而是输入条原地变身。整个流程由四个状态串成（空态、按住、锁定、预览）。凡是空间位移与形变，都由手指位移的进度直接算出（锁形、圆钮缩放、提示条左移、锁定进度），定时动画只负责入场和退场。Lottie 只用在图形本身要变形的地方，其余动效一律 CoreAnimation 手写。

## 一、四种形态与尺寸

输入条在 iOS 里的最小高度是 45pt，算法是 `14.0 + 31.0`（17pt 字号下的输入框最小高），见 `ChatTextInputPanelNode/Sources/ChatTextInputPanelNode.swift:1495`。Android 端实测内容区 2163–2295 像素，除以 3 得 44dp，两端差 1dp，属于同一套栅格。

右侧动作区按 46pt 布局，即 40pt 的图形盒外加左右各 3pt 余量（`ChatTextInputActionButtonsNode/Sources/ChatTextInputActionButtonsNode.swift:238`），发送键的圆形底 34pt（同文件 200 行）。Android 上实测四颗按钮各 44dp 首尾相接、中间不留间隙，靠点击区本身撑开距离，与 iOS 的 40pt 槽位思路一致。

录音态在 iOS 里不换容器，只把输入框背景压到透明，往同一条胶囊里塞三样东西：10pt 的红点（`#FF2D55`）、15pt 的计时、居中的取消提示。红点见 `ChatTextInputPanelNode.swift:2874`，计时格式是 `%d:%02d,%02d`（`ChatTextInputAudioRecordingTimeNode.swift:141`），也就是 `0:04,62` 这种带百分秒的写法；Android 端只到十分位，实机显示 `0:02,4`。

取消提示由箭头加 14pt 的「Slide to cancel」组成（`ChatTextInputAudioRecordingCancelIndicator.swift:59`），锁定后原地换成 17pt 的品牌色「Cancel」按钮（同文件 47 行）。

按住之后从麦克风位置长出来的装饰在旧实现里是两层同心图形：内圆 110pt、外圈 160pt（`ChatTextInputAudioRecordingOverlayButton.swift:8-9`），外圈的基础缩放比是 `110/160 = 0.6875`，随电平涨到 1。iOS 新版更常用三团 blob 替代这两个圆：画布 220×220，每团 8 个控制点，缩放区间分别是 0.45–0.55、0.52–0.87、0.57–1.00，透明度 1 / 0.3 / 0.15（`ChatTextInputMediaRecordingButton.swift:322` 起）。这层光晕就是实机上麦克风外那圈跟着说话一张一缩的东西。

锁面板 40×72，锁图元 40×60（`ChatTextInputMediaRecordingButton.swift:347`），锁定后整体缩到 40 高并向上让位。预览条高 40pt，波形 13pt 高、左右各留 21pt（`ChatRecordingPreviewInputPanelNode.swift:539`、`548`）。

## 二、手势状态机

判定点击与长按的是一条 0.19 秒的计时器：手指按下先起表，抬起时表还没走完就算点击，走完了才开始录音（`ChatTextInputMediaRecordingButton.swift:491`）。点击的语义是切换语音与视频消息，所以实机上轻点一下会看到工具提示和图标变形，而不是开始录音。另外有一道 0.4 秒的双触抑制，两次触摸间隔不足 0.4 秒直接忽略（`LegacyComponents/Sources/TGModernConversationInputMicButton.m:728`），防止手抖连按出两段录音。

拖动阈值全部在基类 `TGModernConversationInputMicButton.m`（下文简称 mic base）里：

| 行为 | 阈值 | 位置 |
| --- | --- | --- |
| 抬起时取消 | 左移 100pt，或横向速度低于 −400 | mic base:861 |
| 拖动中立即取消 | 左移 150pt，手指未抬起就作废 | mic base:793 |
| 松手锁定 | 上移 60pt，或纵向速度低于 −400 | mic base:866 |
| 拖动中立即锁定 | 上移 110pt | mic base:807 |
| 锁定进度 | 位移绝对值 ÷ 105 | mic base:774 |
| 圆钮收缩 | 左移 300pt 内由 1 线性降到 0.4 | mic base:763 |

`ChatTextInputMediaRecordingButton.swift:20-21` 里还留着 `offsetThreshold = 10` 与 `dismissOffsetThreshold = 70` 两个常量，全仓无引用，是重构后的残留，不要当成真实阈值。

Android 端是另一套数，行号相对 `ChatActivityEnterView.java`：

| 行为 | 阈值 | 位置 |
| --- | --- | --- |
| 可走距离 | min(容器宽 × 0.35, 140dp)，360 宽屏等于 126 | 3241 |
| 取消进度 | `alpha = 1 + 左移 ÷ 可走距离`，夹在 0 到 1 | 3249 |
| 拖动中立即取消 | 进度归零当刻 `stopRecording(0)`，手指不用抬 | 3262 |
| 抬手取消 | 进度低于 0.45 | 3155 |
| 松手锁定 | 纵向 57dp，且进度不低于 0.7 | 2166 |
| 系统打断 | 进度低于 0.7 作废，否则原地转锁定 | 3127 |
| 圆钮缩放 | `0.7 + 进度 × 0.3`；已作废时走 `0.7 × easeOut(1 − 进度)` | 2250 |
| 提示位移 | `−宽 ÷ 4 × (1 − 进度) + 圆钮位移 × 0.3`，透明度乘进度 | 14438 |

落值以 Android 这套为准：可走距离按容器实测宽度、进度 1→0、圆钮收到七成、锁定 57 且进度 ≥ 0.7、抬手时进度低于 0.45 作废，锁定与作废的立即触发都发生在手指还没抬起的那一刻。

## 三、跟手与阻尼

手指位移不直接映射到图形位置，而是每帧做一次指数滤波：

```
currentTranslation = min(0, current * 0.7 + target * 0.3)
currentScale       = max(0.4, 1 - 左移距离 / 300)
lockness           = min(1, |纵向位移| / 105)
```

前两行里位移那一行在 mic base:918，缩放在 763 行，第三行锁定进度在 774 行；电平平滑在 916 行。0.7/0.3 这个配比让图形始终落后手指一截，松手后自己追上来，形成"橡皮筋"的观感，替代了真正的弹簧求解。音频电平同样处理：`currentLevel = currentLevel * 0.9 + inputLevel * 0.1`，20 毫秒一帧的原始分贝被压成平滑的呼吸。

这套做法比临界阻尼弹簧便宜得多，帧率稳定，而且不需要处理"连点两次目标值"的收敛问题。

## 四、过渡动画参数

具体参数如下表（`Panel` 指 `ChatTextInputPanelNode.swift`，`mic base` 指 `TGModernConversationInputMicButton.m`，行号为文件内位置）：

| 时刻 | 做了什么 | 参数 |
| --- | --- | --- |
| 进入录音 | 麦克风从 0.2 放大到 1 | 0.50s 弹簧，damping 0.55（mic base:495） |
| | 锁面板从下方 100pt 浮上来 | 同一条弹簧动画 |
| | 输入框背景 alpha 归零 | 与面板高度动画同帧（Panel:2792） |
| | 计时首次出现时从输入框左边缘滑入 | alpha 0→1，0.5s spring（Panel:2852-2858） |
| | 取消提示从屏外右侧滑入 | 位置 0.4s，spring 曲线（Panel:2816） |
| | 红点出现并开始闪 | 0.3→1 缩放加 alpha 0.15s；随后透明度关键帧 [1,1,0,0]，keyTimes [0, 0.4546, 0.9091, 1]，0.5s 自动往复，约 1 秒一个周期（Panel:2888-2905） |
| 待机 | 取消提示轻微摇摆 | 横向 ±6pt，1s 缓入缓出往复，仅在宽度大于 320 时启用（Panel:2831） |
| 拖动 | 提示随手指左移并淡出 | 位移超过 8pt 开始跟随，越过横坐标 100 后按 (x−100)/10 压透明度（Panel:2799-2809） |
| 锁定 | 图标换成发送箭头 | 0.3s，旧图标缩到 0.001，新图标从 0.3 弹回（mic base:640） |
| | 锁面板 72→40、锁上移 11pt、箭头淡出 | 0.2s |
| | 停录按钮淡入 | 0.25s，延迟 0.56s（mic base:689 附近） |
| | 提示条整体收起 | 0.45s 后下移 120pt，0.2s（mic base:672） |
| 取消 | 播放 BinRed | 动画视图从红点所在帧起步，插值到回形针中心，播一次 |
| | 回形针从 0.001 弹回 | 0.2s 缓入缓出（Panel:2947） |
| 删除预览 | 播放 BinBlue | 播一次，结束后图标同样弹回 |
| 麦克风与发送互换 | 发送键淡入、去掉模糊、纸飞机飞入 | alpha 0.2s 缓入缓出，模糊半径 4→0 用 0.18s，图标附加位移从 (−22, +18) 滑到原位（Panel:4584、4677） |
| 退出录音 | 圆缩回 0.2、alpha 归零 | 0.18s（mic base:545） |

取消提示本身在取消态切换时还有一次交叉：文字缩到 0.25 并上移 22pt，同时「取消」按钮从下方 22pt 处以 0.25 放大到 1，两者 alpha 各 0.25s 对切（`ChatTextInputAudioRecordingCancelIndicator.swift:110-125`）。Android 端锁定后文字直接换成「取消」，实机没有观察到这段缩放交叉，切换更硬。

## 五、Lottie 清单

七个文件，全在 `submodules/TelegramUI/Resources/Animations/`：

| 文件 | 画布与帧率 | 时长 | 用法 |
| --- | --- | --- | --- |
| anim_micToVideo.json | 30×30，60fps，15 帧，3 层 | 0.25s | 麦克风与摄像头图标的互变，`playOnce` 播一次，渲染结果当按钮遮罩 |
| anim_videoToMic.json | 同上 | 0.25s | 反方向 |
| LockWait.json | 240×360，60fps，120 帧，4 层 | 2.0s | 待机开锁图元，`autoReverse` 循环，就是拖动前那个轻轻晃的挂锁 |
| Lock.json | 240×360，60fps，30 帧，3 层 | 0.5s | 锁定过程，不按时间播放，`currentProgress = lockness` 由拖动进度直接擦洗 |
| LockPause.json | 240×360，60fps，60 帧，5 层 | 1.0s | 同上，多一层暂停标识，用于允许暂停的会话 |
| BinRed.json | 240×240，360fps，1026–1508 帧，7 层 | 1.34s | 取消录音：录音条落进垃圾桶 |
| BinBlue.json | 240×240，240fps，658–1006 帧，12 层 | 1.45s | 删除已录好的预览：分两段连续播放 |

图标互变那两个动画注册时用 `LottieComponent.AppBundleContent(name:color:)`，配单色效果后其渲染结果被写成按钮的遮罩内容（`animationOutput = micButtonTintMaskView`），玻璃底色从遮罩里透出来，颜色由主题的 `panelControlColor` 给（`ChatTextInputMediaRecordingButton.swift:419-447`）。所以同一个 json 在浅色和深色主题下都不需要改。

锁图元走的是 `Lottie` 原始 `AnimationView`，开启 `renderingEngine: .mainThread` 与 `codable` 解码（`LockView.swift:18-30`）。两个视图叠在一起：待机那个负责循环晃动，锁定那个 `isHidden` 直到拖动，之后 `currentProgress` 跟随 lockness 从 0 走到 1，手指停在半路，挂锁就停在半开的位置。这是整条链路里最值得抄的一处：变形动画交给进度，而不是交给时间。

BinRed 的时间轴值得单独看：0.32 秒垃圾桶淡入，录音条先在 0.18 秒里上提 48px，再在 0.35 秒时落进桶里，盖子两片 0.38 秒起在 0.8 秒内弹跳五次（纵向 166→53→88→58→62→83，转角 0→−5→0→10→−12→0），三条速度线 0.4 秒淡入并上滑 7px。整段 1.34 秒，播完即弃，不留状态。

## 六、录音前、录音中、录音后的界面

实机走一遍，Android 上的形态是这样的：空态是 346dp 宽的浮起胶囊，里面依次是 44dp 表情键、输入框、频道专有的通知铃、回形针、44dp 麦克风。文字一进来，铃与回形针整体消失，麦克风换成实心黑的纸飞机，输入框撑满。这条互换规则在 iOS 里由 `updateActionButtons(hasText:)` 统管，麦克风淡出到 scale 0.001，发送键带模糊地淡入。

按住后胶囊原地变成录音条，红点、计时、「‹ 滑动以取消」依次就位，麦克风放大成一枚带白圈的圆钮，上方浮出开锁图元。锁上之后提示文字变成可点的「取消」，麦克风换成纸飞机，上方再浮出一个暂停键（实机实测 939–1047 × 1941–2049 像素，约 36dp）。

暂停之后才出现预览条：左边 44dp 垃圾桶，中间 252dp 黑色条里塞白色声波柱和一个带时长的播放键，右边仍是发送。Android 到这一步只能播、只能删，条的上方浮一枚「恢复」；iOS 在预览态多出三样东西，条上方浮着「一次性播放」与「录制更多」两枚按钮（纵坐标 −105 与 −52，`ChatTextInputPanelNode.swift:3701-3760`），波形两端多出修剪手柄。修剪手柄只在时长不短于 2 秒时出现（`ChatRecordingPreviewInputPanelNode.swift:516`），最短可修剪时长按波形宽度换算，下限 56pt 对应的那一段（同文件 473 行）。

播放键里那个三角与双竖线的互变也是一段被擦洗的动画：`anim_playpause` 的第 0–41 帧是三角到双竖线，41–83 帧是反向，切换时按 0.35s 走完对应帧段（`PlayPauseIconNode`，同文件 683 行起）。因此它既不是两个图标硬切，也不是一张 gif。

发出去的语音气泡在两端几乎一样：实心圆播放键、未播放部分的浅灰声波柱、时长与未读小点、右上角一个「→A」的转录入口、右下角查看数与时间。

## 七、哪些能搬到 Voca，哪些不能

能直接用的手感参数有四组：0.19 秒的点击与长按分界、0.4 秒的双触抑制、0.7/0.3 的位置阻尼与 0.9/0.1 的电平平滑。这四组决定的是"按下去像不像实物"，与产品形态无关。速度阈值那一组不要搬：iOS 看抬起速度与位移两道，Android 只看位置，落值是后者。

闪烁红点的关键帧、取消提示的节奏、图标从 0.001 弹回的收尾，也都是独立于布局的小动作，可以照搬。

锁定、暂停、修剪三件原判为不适合回合制对练，实际做下来改了口径：输入条原地变身之后，锁定与暂停不占额外布局，它们不抢主对话的面积，只是把"说完再说"与"说错了重录"这两种真实状态接住，已经随五档状态机一起落了；修剪没搬，一轮十几秒到一分钟的练习不需要剪开头那段犹豫。真正被采纳的是那套"原地变形、不遮对话"的做法，半屏收音面板已经删了。

## 八、证据索引

源码路径均相对 `/Users/actor/Documents/swift 最佳实践/Telegram-iOS`。

- 输入条主体与录音态布局：`submodules/TelegramUI/Components/Chat/ChatTextInputPanelNode/Sources/ChatTextInputPanelNode.swift`（1495、2679、2799-2838、2874-2905、2926-2955、4584、4677）
- 动作按钮与发送键：`.../Chat/ChatTextInputActionButtonsNode/Sources/ChatTextInputActionButtonsNode.swift`（200、238）
- 麦克风按钮与模式互变：`submodules/TelegramUI/Components/ChatTextInputMediaRecordingButton/Sources/ChatTextInputMediaRecordingButton.swift`（20-21、322、347、419-447、491）
- 手势与阈值：`submodules/LegacyComponents/Sources/TGModernConversationInputMicButton.m`（495、545、640、672、689、728、763-811、861-866、916-918）
- 待机与锁定的 Lottie：`submodules/TelegramUI/Components/ChatTextInputMediaRecordingButton/Sources/LockView.swift`（18-30、64）
- 取消提示：`.../Chat/ChatTextInputAudioRecordingCancelIndicator/Sources/ChatTextInputAudioRecordingCancelIndicator.swift`（47、59、110-125）
- 计时：`.../Chat/ChatTextInputAudioRecordingTimeNode/Sources/ChatTextInputAudioRecordingTimeNode.swift`（29、141）
- 展开圆与光晕：`.../ChatTextInputMediaRecordingButton/Sources/ChatTextInputAudioRecordingOverlayButton.swift`（8-9）、`submodules/AudioBlob/Sources/BlobView.swift`（62-110）
- 预览条与修剪：`.../Chat/ChatRecordingPreviewInputPanelNode/Sources/ChatRecordingPreviewInputPanelNode.swift`（473、516、539、548、683-717）
- Lottie 资源：`submodules/TelegramUI/Resources/Animations/{anim_micToVideo,anim_videoToMic,LockWait,Lock,LockPause,BinRed,BinBlue}.json`
- Android 实机尺寸来自本机截图与 `uiautomator dump` 的节点边界，分辨率 1080×2376、密度 3x。
