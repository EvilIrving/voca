let page = figma.root.children.find(pn => pn.name === '00 · 规范');
if (!page) { page = figma.createPage(); page.name = '00 · 规范'; }
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('spec') === 0 || n.name.indexOf('SPK') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const root = frame(page, 'SPK 00 · 设计规范', { w: 900, dir: 'VERTICAL', pl: 40, pr: 40, pt: 40, pb: 40, gap: 0, fill: C.paper, x: 0, y: 0, clip: false });

/* title */
t(root, 'Parley · 口语训练客户端', { font: F.serifCnMd, size: 30, color: C.ink, lh: 40 });
frame(root, 'sp', { w: 1, h: 28 });

/* color tokens */
t(root, '颜色', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
frame(root, 'sp', { w: 1, h: 12 });
const colors = [
  ['paper', C.paper, '页面基底 · 羊皮纸'],
  ['card', C.card, '气泡 / 卡片 / 抽屉'],
  ['tint', C.tint, '用户气泡'],
  ['hair', C.hair, '纸感细边线 · 页内描边半透明'],
  ['hairStrong', C.hairStrong, '拖拽把手 / 分隔'],
  ['ink', C.ink, '主文字 · 暖近黑'],
  ['ink2', C.ink2, '正文'],
  ['ink3', C.ink3, '次级文字'],
  ['ink4', C.ink4, '辅助 / 占位'],
  ['clay', C.clay, '品牌珊瑚陶土 · 主操作'],
  ['clayDeep', C.clayDeep, '强调文字 / 发音分'],
  ['clayTint', C.clayTint, '陶土浅底'],
  ['olive', C.olive, '鼠尾草 · 语法分 / 提示'],
  ['oliveTint', C.oliveTint, '提示卡底色'],
  ['danger', C.danger, '取消 / 破坏性']
];
const swatchRow = frame(root, 'colors', { dir: 'HORIZONTAL', w: 820, gap: 10, main: 'MIN' });
swatchRow.layoutWrap = 'WRAP';
for (const [name, hex, use] of colors) {
  const card = frame(swatchRow, 'spec color ' + name, { w: 154, dir: 'VERTICAL', gap: 6, pt: 8, pb: 10, pl: 8, pr: 8, fill: C.card, stroke: C.hair, radius: 12 });
  frame(card, 'chip', { w: 138, h: 48, fill: hex, radius: 8, stroke: C.hair });
  t(card, name, { font: F.monoMd, size: 10.5, color: C.ink2, lh: 14 });
  t(card, hex.toUpperCase(), { font: F.mono, size: 9.5, color: C.ink4, lh: 13 });
  t(card, use, { font: F.cn, size: 9.5, color: C.ink4, lh: 14, width: 138 });
}
frame(root, 'sp', { w: 1, h: 32 });

/* 打卡热力五档：颜色只由当日练习分钟决定，与客户端 CheckInLevel 同一套档位 */
t(root, '打卡热力色阶', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
frame(root, 'sp', { w: 1, h: 12 });
const heatSteps = [
  ['无练习', C.heat0],
  ['1–4 分', C.heat1],
  ['5–14 分', C.heat2],
  ['15–29 分', C.heat3],
  ['30 分以上', C.heat4]
];
const heatRow = frame(root, 'heat steps', { dir: 'HORIZONTAL', w: 820, gap: 10, main: 'MIN' });
for (const [label, hex] of heatSteps) {
  const card = frame(heatRow, 'spec heat ' + label, { w: 154, dir: 'VERTICAL', gap: 6, pt: 8, pb: 10, pl: 8, pr: 8, fill: C.card, stroke: C.hair, radius: 12 });
  frame(card, 'chip', { w: 138, h: 40, fill: hex, radius: 8, stroke: C.hair });
  t(card, label, { font: F.cnMd, size: 10.5, color: C.ink2, lh: 14 });
  t(card, hex.toUpperCase(), { font: F.mono, size: 9.5, color: C.ink4, lh: 13 });
}
frame(root, 'sp', { w: 1, h: 10 });
t(root, '热力图铺二十六周乘七天，格 8.3 圆角 2 间距 3，行首为周一，卡片 320 宽内缩 14、格区 292；末列没到的日子留空。', { font: F.cn, size: 11, color: C.ink4, lh: 16, width: 820 });
frame(root, 'sp', { w: 1, h: 32 });

/* type scale */
t(root, '字体', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
frame(root, 'sp', { w: 1, h: 12 });
const types = [
  ['Display / Newsreader · Noto Serif SC 500', '说点什么', 20, F.serifCnMd, C.ink],
  ['Prose / Newsreader Regular 15·23', 'An Americano on a tired morning — that is a very honest choice.', 15, F.serif, C.ink],
  ['Prose Italic / Newsreader Italic 14·21', 'Actually, I didn’t sleep a wink last night.', 14, F.serifIt, C.ink2],
  ['UI / Inter Medium 13.5·19', 'Gemini Live · gemini-3.8-live', 13.5, F.sansMd, C.ink],
  ['Caption / Inter Regular 11·15 · 字距 +6%', '音素建议 · 仅 1 处', 11, F.sans, C.ink4],
  ['Mono / IBM Plex Mono 11·15', '/slept ˈbædli/ · 00:06', 11, F.mono, C.ink3]
];
const typeBox = frame(root, 'types', { w: 820, dir: 'VERTICAL', gap: 10 });
for (const [label, sample, size, font, color] of types) {
  const row = frame(typeBox, 'spec type', { w: 820, dir: 'HORIZONTAL', cross: 'CENTER', gap: 20, pt: 10, pb: 10, fill: C.card, radius: 12, pl: 16, pr: 16 });
  t(row, label, { font: F.mono, size: 10, color: C.ink4, lh: 14, width: 236 });
  t(row, sample, { font: font, size: size, color: color, lh: Math.round(size * 1.5), width: 520 });
}
frame(root, 'sp', { w: 1, h: 32 });

/* radius + spacing */
const twoCol = frame(root, 'radius and spacing', { dir: 'HORIZONTAL', gap: 24, cross: 'MIN' });
const radiusCol = frame(twoCol, 'radius col', { dir: 'VERTICAL', gap: 10, w: 396 });
t(radiusCol, '圆角', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
const radii = [[5, '气泡尖角'], [8, '词条 / 标签 / 下拉选项'], [10, '抽屉会话行'], [12, '搜索框 / 提示行'], [14, '提示卡 / 改写卡 / 下拉菜单'], [16, '生词卡'], [18, '气泡主圆角'], [26, '半屏抽屉 / 卡片拖到一半'], [32, '卡片推开后'], [999, '胶囊 / 圆形按钮']];
for (const [r, use] of radii) {
  const row = frame(radiusCol, 'spec radius ' + r, { w: 380, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', gap: 12 });
  const box = frame(row, 'box', { w: 44, h: 44, fill: C.card, stroke: C.hairStrong, radius: r === 999 ? 22 : r });
  t(row, String(r) + ' rad', { font: F.monoMd, size: 11, color: C.ink2, lh: 16, width: 60 });
  t(row, use, { font: F.cn, size: 11, color: C.ink4, lh: 16 });
}
const spaceCol = frame(twoCol, 'space col', { dir: 'VERTICAL', gap: 8, w: 396 });
t(spaceCol, '间距与版心', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
const spaces = [[2, '行内微距'], [6, '图标与文字'], [8, '行内分组'], [10, '头像与气泡'], [12, '卡片内块间距'], [18, '气泡之间'], [20, '页面左右外边距'], [24, '抽屉内上下留白']];
for (const [v, use] of spaces) {
  const row = frame(spaceCol, 'spec space ' + v, { w: 380, h: 28, dir: 'HORIZONTAL', cross: 'CENTER', gap: 12 });
  const bar = frame(row, 'bar', { w: v * 2, h: 8, fill: C.clayTint, stroke: C.clay, radius: 4 });
  t(row, String(v) + ' px', { font: F.monoMd, size: 11, color: C.ink2, lh: 16, width: 56 });
  t(row, use, { font: F.cn, size: 11, color: C.ink4, lh: 16 });
}
t(spaceCol, '列表与底栏宽 320，左右外边距 20；底栏胶囊 208×48；半屏抽屉 26，被推走的主卡片推开后 32。', { font: F.cn, size: 11, color: C.ink4, lh: 18, width: 320 });
frame(root, 'sp', { w: 1, h: 32 });

/* interaction states */
t(root, '交互状态', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
frame(root, 'sp', { w: 1, h: 12 });
const stateRow = frame(root, 'states', { w: 820, dir: 'HORIZONTAL', gap: 10, main: 'MIN' });
stateRow.layoutWrap = 'WRAP';
const states = [
  ['默认', C.card, C.hair, C.ink2, null, 1],
  ['按压', C.tint, null, C.ink, null, 1],
  ['选中', C.clayTint, null, C.clayDeep, 'checkMark', 1],
  ['禁用', C.card, C.hair, C.ink4, null, 0.4],
  ['加载', C.card, C.hair, C.ink4, 'dot', 1]
];
for (const [label, fill, stroke, ink, glyph, alpha] of states) {
  const card = frame(stateRow, 'spec state ' + label, { w: 154, dir: 'VERTICAL', gap: 8, pt: 10, pb: 10, pl: 10, pr: 10, fill: C.paper, stroke: C.hair, radius: 12 });
  const sample = frame(card, 'sample', { w: 134, h: 40, dir: 'HORIZONTAL', cross: 'CENTER', gap: 6, pl: 10, pr: 10, fill: fill, stroke: stroke, radius: 8 });
  t(sample, '咖啡点单', { font: F.cnMd, size: 12.5, color: ink, lh: 18, grow: 1, colorA: alpha });
  if (glyph === 'checkMark') icon(sample, 'checkMark', 12, C.clayDeep);
  if (glyph === 'dot') {
    // 加载态用一枚呼吸的四级灰点，连接状态不再上界面，这一枚只表示「在等」。
    const d = frame(sample, 'breath', { w: 8, h: 8, fill: C.ink4, radius: 999 });
    d.opacity = 0.55;
  }
  t(card, label, { font: F.monoMd, size: 10.5, color: C.ink2, lh: 14 });
}
frame(root, 'sp', { w: 1, h: 16 });
const stateNotes = [
  '按压只换底色：白卡转 tint，已选中的陶土浅底加深一档，陶土实底降到 clayDeep。涟漪、按压阴影、缩放三样一律不用。\n',
  '禁用态不另加灰：文字降到四成不透明度，底色与描边保持原样，可点语义一并撤掉。\n',
  '长按录音的胶囊与拾音钮没有按压态，按下去直接进收音面板。\n',
  '阴影只属于浮层：两个半屏纸面、下拉菜单、白卡弹层各一层暖影，页面内元素一律不投影。\n'
];
for (const line of stateNotes) t(root, line, { font: F.cn, size: 11.5, color: C.ink3, lh: 19, width: 820 });
frame(root, 'sp', { w: 1, h: 32 });

/* performance budget */
t(root, '性能预算', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
frame(root, 'sp', { w: 1, h: 12 });
const perf = [
  '列表一律 LazyColumn 配稳定 key，滚动只重组可见行；行内状态收在最小范围，不下沉到整屏。\n',
  '声波在单个 Canvas 里画完，三十四根柱不进组合树，电平按分贝平滑后再取帧。\n',
  '下拉与半屏面板只在开合时重组，动画只做透明度与位移，180 毫秒收尾。\n',
  '图标与文字样式是常量，不在组合期新建对象；菜单项数量按供应商与模型清单静态生成。\n',
  '录音与播放的每一段各自成套，停止只作用于当前段，句柄由持有它的线程释放。\n'
];
for (const line of perf) t(root, line, { font: F.cn, size: 11.5, color: C.ink3, lh: 19, width: 820 });
frame(root, 'sp', { w: 1, h: 32 });

/* structure map */
t(root, '组件结构与代码映射', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
frame(root, 'sp', { w: 1, h: 12 });
const map = frame(root, 'structure', { w: 820, dir: 'HORIZONTAL', gap: 12, cross: 'MIN' });
map.layoutWrap = 'WRAP';
const groups = [
  ['原子层 atoms', [
    'IconButton/30/checkMark', 'IconButton/30/dismiss', 'IconButton/30/edit', 'IconButton/30/speaker',
    'IconButton/32/arrowLeft', 'IconButton/32/chatAdd', 'IconButton/32/book', 'IconButton/36/search', 'IconButton/44/settings',
    'Mic/48', 'Mic/82', 'Send/48', 'Pause/40', 'Resume/36', 'Trash/22', 'LockChip', 'Tooltip', 'Timeline/236',
    'Button/Primary/Label', 'Button/Ghost', 'Chip/Filter/On', 'Chip/Filter/Off',
    'Chip/Tag/Olive', 'Chip/Tag/Neutral', 'Chip/Word', 'Score/Value',
    'Select/Option/On', 'Select/Option/Off', 'Toast'
  ]],
  ['复合层 molecules', [
    'Bubble/Echo', 'Bubble/Echo/Translated', 'Bubble/User', 'ScorePill', 'HintCard',
    'SectionLabel', 'SectionLabel/Mic', 'Field/Search',
    'Row/Provider', 'Row/Provider/Open', 'Row/Provider/Off', 'Row/Voice', 'Row/Voice/Open',
    'Row/DrawerSession', 'Row/DrawerSession/Current', 'Button/NewSession',
    'Row/Menu/Speak', 'Row/Menu/Translate', 'Row/Menu/Save', 'Row/Menu/Copy', 'Row/Menu/Retry', 'Row/Menu/Delete',
    'Select/Menu', 'Select/Menu/Voice', 'Actions/Message',
    'VocabCard/Collocation', 'VocabCard/Word', 'VocabCard/New',
    'Panel/Original', 'Panel/Rewrite', 'Tab/On', 'Tab/Off'
  ]],
  ['组织层 organisms', [
    'TopBar', 'TopBar/Chat', 'TopBar/Named', 'TopBar/Review',
    'InputBar', 'InputBar/Text', 'InputBar/Recording', 'InputBar/Cancel', 'InputBar/Locked', 'InputBar/Preview',
    'LockBar'
  ]],
  ['界面 screens', [
    '01 对话', '02 有草稿', '03 按住录音', '04 左滑取消', '05 锁定录音', '06 暂停预览',
    '07 消息操作', '08 评测抽屉', '09 生词库', '10 设置', '11 模型下拉',
    '12 侧边抽屉', '13 译文展开', '14 轻提示', '15 系统提示词'
  ]]
];
for (const [title, lines] of groups) {
  const box = frame(map, 'spec group ' + title, { w: 196, dir: 'VERTICAL', gap: 6, pl: 12, pr: 12, pt: 12, pb: 14, fill: C.card, stroke: C.hair, radius: 12 });
  t(box, title, { font: F.cnMd, size: 11, color: C.clayDeep, lh: 16, ls: 4 });
  for (const line of lines) t(box, line, { font: F.mono, size: 9, color: C.ink3, lh: 14, width: 172 });
}
frame(root, 'sp', { w: 1, h: 24 });
t(root, '所有界面只由组件实例拼装，文字靠实例覆盖，不复制组件内部结构。改一处样式，全部界面同步。', { font: F.cn, size: 11.5, color: C.ink3, lh: 19 });
t(root, 'Compose 结构：ui/theme/ParleyTheme.kt（颜色·字体·圆角·间距）→ ui/components/{atoms,molecules,organisms} → ui/screens。组件名与画板名一一对应。下拉菜单、顶部胶囊与状态行各带开合两态，界面只切实例不画新样式。', { font: F.cn, size: 11.5, color: C.ink3, lh: 19 });

return { frame: root.id, w: Math.round(root.width), h: Math.round(root.height) };
