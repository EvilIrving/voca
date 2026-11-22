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
  ['hair', C.hair, '纸感细边线'],
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

/* type scale */
t(root, '字体', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
frame(root, 'sp', { w: 1, h: 12 });
const types = [
  ['Display / Newsreader · Noto Serif SC 500', '松手发送', 20, F.serifCnMd, C.ink],
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
const radii = [[5, '气泡尖角'], [8, '词条 / 标签 / 下拉选项'], [12, '搜索框 / 提示行'], [14, '提示卡 / 改写卡 / 下拉菜单'], [16, '生词卡'], [18, '气泡主圆角'], [26, '半屏抽屉'], [999, '胶囊 / 圆形按钮']];
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
t(spaceCol, '列表与底栏宽 320，左右外边距 20；底栏胶囊 208×48；抽屉圆角 26。', { font: F.cn, size: 11, color: C.ink4, lh: 18, width: 320 });
frame(root, 'sp', { w: 1, h: 32 });

/* structure map */
t(root, '组件结构与代码映射', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
frame(root, 'sp', { w: 1, h: 12 });
const map = frame(root, 'structure', { w: 820, dir: 'HORIZONTAL', gap: 12, cross: 'MIN' });
map.layoutWrap = 'WRAP';
const groups = [
  ['原子层 atoms', [
    'IconButton/30-32-34-44', 'Button/Primary/Talk', 'Button/Primary/Label', 'Button/Ghost',
    'Chip/Filter/On', 'Chip/Filter/Off', 'Chip/Tag/Olive', 'Chip/Tag/Neutral', 'Chip/Word', 'Score/Value',
    'Select/Option/On', 'Select/Option/Off', 'Select/Option/Single/On-Off', 'Row/Session', 'Toast'
  ]],
  ['复合层 molecules', [
    'Bubble/Echo', 'Bubble/Echo/Translated', 'Bubble/User', 'ScorePill', 'HintCard', 'SectionLabel', 'SectionLabel/Mic',
    'Field/Search', 'Field/Input', 'Field/Rename', 'IconButton/44/mic', 'IconButton/44/arrowUp', 'IconButton/30/checkMark', 'Row/Provider', 'Row/Provider/Open', 'Row/Provider/Off', 'Row/Action/Text', 'Row/Action/Summary', 'Row/Action/Records',
    'Select/Menu', 'Select/Menu/Scene', 'VocabCard/Collocation', 'VocabCard/Word', 'VocabCard/New',
    'Panel/Original', 'Panel/Rewrite', 'Tab/On', 'Tab/Off', 'Waveform/Split', 'Waveform/Split/Cancel'
  ]],
  ['组织层 organisms', [
    'TopBar', 'TopBar/Open', 'TopBar/Offline', 'TopBar/Connecting', 'BottomBar', 'RecordSheet', 'RecordSheet/Cancel', 'Waveform/Cancel', 'PressButton', 'PressButton/Cancel'
  ]],
  ['界面 screens', [
    '01 对话', '02 收音中', '03 评测抽屉', '04 生词库', '05 配置中心',
    '06 场景下拉', '07 模型下拉', '08 动作面板', '09 会话小结', '10 会话记录', '11 上滑取消', '12 轻提示', '13 打字输入', '14 改名', '15 未连接', '16 译文展开'
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
