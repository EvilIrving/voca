// 侧边抽屉交互原型：三态（收起 / 拖到一半 / 展开）加一块参数看板。
// 与 02 · 界面 的「13 · 侧边抽屉」共用一套组件，改组件这两处一起变。
let page = figma.root.children.find(pn => pn.name === '侧边抽屉交互原型');
if (!page) { page = figma.createPage(); page.name = '侧边抽屉交互原型'; }
await figma.setCurrentPageAsync(page);
page.children.slice().forEach(n => n.remove());

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const lib = figma.root.children.find(x => x.name === '01 · 组件');
await figma.setCurrentPageAsync(lib);
const C_ = {};
for (const n of lib.children) { if (n.type === 'COMPONENT') C_[String(n.name)] = n; }
await figma.setCurrentPageAsync(page);
const need = ['Speaker/TopBar', 'Speaker/InputBar', 'Speaker/Bubble/Echo', 'Speaker/Bubble/User', 'Speaker/ScorePill', 'Speaker/HintCard', 'Speaker/Button/NewSession', 'Speaker/Row/DrawerSession', 'Speaker/Row/DrawerSession/Current', 'Speaker/IconButton/36/search', 'Speaker/IconButton/44/settings'];
const missing = need.filter(n => !C_[n]);
if (missing.length) return { error: 'missing components', missing };

const W = 360, H = 792, TOP = 44, BAR_Y = 681, LIST_H = H - 96 - TOP;
const PANEL = 295, INSET = 18, CONTENT = 259;

/* 页面抬头 */
const banner = frame(page, 'Header_Banner', { w: 1560, h: 120, dir: 'VERTICAL', gap: 6, pt: 24, pl: 36, x: 0, y: 0 });
t(banner, '侧边抽屉交互原型', { font: F.serifCnMd, size: 22, color: C.ink, lh: 30 });
t(banner, 'Drawer Prototype · 会话、新会话与配置中心 · 拖动 295 与卡片 0.94 的同一串进度', { font: F.mono, size: 11, color: C.ink4, lh: 16 });

/* 屏幕骨架 */
function screen(name, x) {
  const f = frame(page, name, { w: W, h: H, fill: C.paper, dir: 'VERTICAL', clip: true, x: x, y: 120 });
  f.primaryAxisSizingMode = 'FIXED';
  f.resize(W, H);
  const lb = t(page, name, { font: F.mono, size: 11, color: C.ink3, lh: 16 });
  lb.name = 'cap ' + name;
  lb.x = x; lb.y = 92;
  return f;
}

/* 抽屉正文：标题与检索一行、新会话胶囊、三段会话、右下角配置中心 */
function drawerPanel(root, x) {
  const panel = frame(root, 'drawer', { w: PANEL, h: H, dir: 'VERTICAL', pl: INSET, pr: INSET, pt: 20, pb: 20, fill: C.paper, x: x, y: 0, absolute: true });
  const head = frame(panel, 'head', { w: CONTENT, h: 44, dir: 'HORIZONTAL', cross: 'CENTER' });
  t(head, '会话', { font: F.serifCnMd, size: 20, color: C.ink, lh: 27, grow: 1 });
  inst(head, C_['Speaker/IconButton/36/search']);
  frame(panel, 'sp 1', { w: 1, h: 8 });
  inst(panel, C_['Speaker/Button/NewSession']);
  frame(panel, 'sp 2', { w: 1, h: 14 });
  const list = frame(panel, 'sessions', { w: CONTENT, dir: 'VERTICAL' });
  function group(label) {
    const g = frame(list, 'group ' + label, { w: CONTENT, h: 31, dir: 'HORIZONTAL', cross: 'MAX', pl: 6, pt: 12, pb: 4 });
    t(g, label, { font: F.sans, size: 11, color: C.ink4, lh: 15, name: 'group' });
  }
  function row(name, current) {
    const r = inst(list, C_['Speaker/Row/DrawerSession' + (current ? '/Current' : '')]);
    r.layoutAlign = 'STRETCH';
    const n = r.findOne(x => x.type === 'TEXT' && x.name === 'name');
    if (n) n.characters = name;
  }
  group('今天');
  row('没睡好的早晨', true);
  row('咖啡店点单');
  row('面试自我介绍');
  group('更早');
  row('周末出行计划');
  frame(panel, 'sp 3', { w: 1, h: 1, grow: 1 });
  const dock = frame(panel, 'dock', { w: CONTENT, h: 44, dir: 'HORIZONTAL', main: 'MAX', cross: 'CENTER' });
  inst(dock, C_['Speaker/IconButton/44/settings']);
  return panel;
}

/* 主卡片：右推、等比缩放、圆角与白蒙层都吃同一个进度 p */
function card(root, p) {
  const scale = 1 - 0.06 * p;
  const w = Math.round(W * scale), h = Math.round(H * scale);
  const x = Math.round((W - w) / 2 + PANEL * p), y = Math.round((H - h) / 2);
  const c = frame(root, 'card', { w: w, h: h, fill: C.paper, radius: Math.round(32 * p), clip: true, x: x, y: y, absolute: true });
  if (p > 0) c.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#3A2F26'), a: 0.16 }, offset: { x: -8, y: 0 }, radius: 24, spread: 0, blendMode: 'NORMAL', visible: true }];
  const view = frame(c, 'card view', { w: W, h: H, dir: 'VERTICAL', x: 0, y: 0, absolute: true });
  inst(view, C_['Speaker/TopBar']);
  const list = frame(view, 'transcript', { w: W, h: LIST_H, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 10, gap: 16, clip: true });
  inst(list, C_['Speaker/Bubble/Echo'], { text: 'Good morning! How did you start your day? Are you craving an Americano or something else?' });
  const u = frame(list, 'msg user', { w: 320, dir: 'VERTICAL', cross: 'MAX', gap: 9 });
  inst(u, C_['Speaker/Bubble/User'], { text: 'I get up early today. Actually I… 嗯… 没睡好，feeling a bit tired.' });
  const pill = inst(u, C_['Speaker/ScorePill']);
  inst(list, C_['Speaker/Bubble/Echo'], { text: 'An Americano on a tired morning — that is a very honest choice. Do you brew it at home?' });
  const bar = inst(view, C_['Speaker/InputBar']);
  bar.layoutPositioning = 'ABSOLUTE';
  bar.x = 0; bar.y = BAR_Y;
  if (p > 0) frame(c, 'veil', { w: w, h: h, fill: C.card, fillA: 0.6 * p, x: 0, y: 0, absolute: true });
  return c;
}

const closed = screen('01 · 收起', 0);
card(closed, 0);
const half = screen('02 · 拖到一半', 420);
drawerPanel(half, -Math.round(PANEL / 2));
card(half, 0.5);
const open = screen('03 · 展开', 840);
drawerPanel(open, 0);
card(open, 1);

/* 原型连线：三态同一棵图层树，Smart Animate 走同一段曲线 */
const smart = (dest, duration) => ({
  trigger: { type: 'ON_CLICK' },
  actions: [{
    type: 'NODE',
    destinationId: dest.id,
    navigation: 'NAVIGATE',
    transition: { type: 'SMART_ANIMATE', easing: { type: 'CUSTOM_CUBIC_BEZIER', easingFunctionCubicBezier: { x1: 0.16, y1: 1, x2: 0.3, y2: 1 } }, duration: duration / 1000 },
    preserveScrollPosition: false,
  }],
});
closed.reactions = [smart(half, 150)];
half.reactions = [smart(open, 300)];
open.reactions = [smart(closed, 240)];

/* 参数看板 */
function boardBox(name, x, y, title, rows) {
  const box = frame(page, name, { w: 420, h: 32 + rows.length * 44, dir: 'VERTICAL', pl: 20, pr: 20, pt: 18, pb: 18, gap: 0, fill: C.card, stroke: C.hair, radius: 14, x: x, y: y, absolute: true });
  t(box, title, { font: F.cnMd, size: 13, color: C.ink, lh: 18 });
  for (const [label, value] of rows) {
    const r = frame(box, 'row ' + label, { w: 380, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', gap: 10 });
    t(r, label, { font: F.cn, size: 11.5, color: C.ink3, lh: 16, grow: 1 });
    t(r, value, { font: F.mono, size: 11, color: C.ink2, lh: 16 });
  }
  return box;
}

const boardTitle = t(page, '参数与手势', { font: F.serifCnMd, size: 18, color: C.ink, lh: 24 });
boardTitle.x = 1280; boardTitle.y = 120;
const boardNote = t(page, '进度只有一个来源：抽屉位移、卡片缩放、圆角、白蒙层四样都从 0 到 1 的同一个 p 推出来', { font: F.mono, size: 10.5, color: C.ink4, lh: 15, width: 420 });
boardNote.x = 1280; boardNote.y = 152;

boardBox('spec 尺寸', 1280, 200, '尺寸与空间', [
  ['抽屉宽度 (Drawer Width)', '屏宽 0.82 · 封顶 295'],
  ['卡片等比缩放 (Card Scale)', '0.94 · 338 × 745'],
  ['卡片圆角 / 暖影', '32 · 向外 24'],
  ['左滑露出的旧页', '54 宽的一条'],
  ['抽屉内容边距', '18 · 内容 259'],
]);
boardBox('spec 动效', 1280, 560, '动效时序', [
  ['展开曲线 (Open Curve)', 'cubic-bezier(0.16,1,0.3,1)'],
  ['展开时长 (Open Duration)', '300 ms'],
  ['收起时长 (Close Duration)', '240 ms'],
  ['松手落档 (Release)', '速度 > 250 dp/s 或走过半程'],
  ['白蒙层 (Veil)', '0 → 60% 随进度线性'],
]);
boardBox('spec 手势', 1280, 920, '手势与收口', [
  ['拉起抽屉 (Open)', '底栏以上向右横滑 · 跟手'],
  ['收起 (Dismiss)', '点卡片 · 向左拖 · 返回键'],
  ['会话名 (Session Name)', '只读，不做入口'],
  ['新会话 (New Session)', '气泡加号 + 文案，52 高胶囊'],
]);
return { screens: 3, board: 3 };
