let page = figma.root.children.find(pn => pn.name === '02 · 界面');
if (!page) { page = figma.createPage(); page.name = '02 · 界面'; }
await figma.setCurrentPageAsync(page);

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const lib = figma.root.children.find(x => x.name === '01 · 组件');
await figma.setCurrentPageAsync(lib);
const C_ = {};
if (lib) for (const n of lib.children) { if (n.type === 'COMPONENT' || n.type === 'COMPONENT_SET') C_[String(n.name)] = n; }
await figma.setCurrentPageAsync(page);

const need = ['Speaker/IconButton/32/arrowLeft', 'Speaker/Heatmap', 'Speaker/Row/Voice'];
const missing = need.filter(n => !C_[n]);
if (missing.length) return { error: 'missing components', missing };

// 只清这一轮自己的产物，别的屏不动。
const mine = ['SPK 20 · 设置 · 三组收起', 'SPK 21 · 设置 · 展开已配置', 'SPK 22 · 设置 · 展开未配置',
  'SPK 17 · 设置 · 三组收起', 'SPK 18 · 设置 · 展开已配置', 'SPK 19 · 设置 · 展开未配置'];
page.children.filter(n => mine.indexOf(String(n.name)) >= 0 || ['cap 17', 'cap 20'].some(k => String(n.name).indexOf(k) === 0)).forEach(n => n.remove());

const TOP = 44;

function screen(name, x) {
  const f = frame(page, 'SPK ' + name, { w: 360, h: 792, fill: C.paper, dir: 'VERTICAL', clip: true, x: x, y: 120 });
  f.primaryAxisSizingMode = 'FIXED';
  f.resize(360, 792);
  const hd = frame(f, 'header', { w: 360, h: TOP, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, gap: 10 });
  inst(hd, C_['Speaker/IconButton/32/arrowLeft']);
  t(hd, '设置', { font: F.serifCnMd, size: 20, color: C.ink, lh: 27 });
  const body = frame(f, 'body', { w: 360, h: 792 - TOP, dir: 'VERTICAL', pl: 20, pr: 20, pb: 20, gap: 8, clip: true });
  return { f: f, body: body };
}

function sectionTitle(parent, text) {
  const row = frame(parent, 'section ' + text, { w: 320, h: 18, dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 });
  t(row, text, { font: F.cn, size: 10, color: C.ink4, lh: 14, ls: 10 });
}

function groupCard(parent) {
  return frame(parent, 'group', { w: 320, dir: 'VERTICAL', fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 16, clip: true });
}

function cardRule(parent) {
  const r = frame(parent, 'rule', { w: 292, h: 1, fill: C.hair, fillA: 0.5 });
  r.layoutAlign = 'CENTER';
}

/**
 * 一家供应商一行：单选点、家名、第二行的模型或端点、右侧状态。
 * open 有值时第二行末尾带开合箭头，clear 为真时行尾多一枚清除。
 */
function providerRow(parent, o) {
  const row = frame(parent, 'row ' + o.name, { w: 320, h: 54, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 14, gap: 12 });
  const radio = frame(row, 'radio', { w: 18, h: 18, fill: C.card, stroke: o.on ? C.clay : C.hairStrong, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
  if (o.on) frame(radio, 'dot', { w: 9, h: 9, fill: C.clay, radius: 999 });
  const txts = frame(row, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
  t(txts, o.name, { font: F.cnMd, size: 13.5, color: o.on || o.open ? C.ink : C.ink3, lh: 19, name: 'name' });
  const metaRow = frame(txts, 'meta row', { dir: 'HORIZONTAL', cross: 'CENTER', gap: 4 });
  t(metaRow, o.meta, { font: F.mono, size: 10.5, color: o.open ? C.clayDeep : C.ink4, lh: 14, name: 'meta' });
  if (o.open !== undefined) icon(metaRow, o.open ? 'chevUp' : 'chevDown', 12, o.open ? C.clayDeep : C.ink4);
  const state = frame(row, 'state', { h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 5 });
  if (o.dot) frame(state, 'dot', { w: 5, h: 5, fill: o.dotColor || C.clay, radius: 9 });
  t(state, o.status, { font: F.cn, size: 10.5, color: o.dot ? C.ink2 : C.ink4, lh: 14, name: 'status' });
  if (o.clear) {
    const btn = frame(row, 'clear', { w: 24, h: 24, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
    icon(btn, 'dismiss', 13, C.ink4);
  } else if (o.collapse) {
    icon(metaRow, 'chevDown', 12, C.ink4);
  }
  return row;
}

/* 表单里的一格：标签在上，浅底输入框在下，与弹窗里那套字段同构。 */
function field(parent, label, value) {
  const col = frame(parent, 'field ' + label, { w: 292, dir: 'VERTICAL', gap: 6 });
  t(col, label, { font: F.cn, size: 11, color: C.ink3, lh: 15 });
  const box = frame(col, 'box', { w: 292, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 12, fill: C.tint, radius: 12 });
  if (value) t(box, value, { font: F.cn, size: 13, color: C.ink2, lh: 18 });
  return col;
}

/* 音色这类只挑不填的项：标签在上，浅底一行，右端开合箭头。 */
function picker(parent, label, value) {
  const col = frame(parent, 'picker ' + label, { w: 292, dir: 'VERTICAL', gap: 6 });
  t(col, label, { font: F.cn, size: 11, color: C.ink3, lh: 15 });
  const box = frame(col, 'box', { w: 292, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 12, gap: 8, fill: C.tint, radius: 12 });
  const texts = frame(box, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1, main: 'CENTER' });
  t(texts, value, { font: F.cnMd, size: 13, color: C.ink2, lh: 18, name: 'value' });
  icon(box, 'chevDown', 12, C.ink4);
  return col;
}

function saveButton(parent) {
  const b = frame(parent, 'save', { w: 292, h: 44, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.clay, radius: 22 });
  t(b, '保存', { font: F.cnMd, size: 13.5, color: C.card, lh: 19 });
}

function promptCard(parent) {
  const card = frame(parent, 'prompt card', { w: 320, dir: 'VERTICAL', gap: 8, pl: 14, pr: 14, pt: 12, pb: 12, fill: C.paper, stroke: C.hair, strokeA: 0.5, radius: 16 });
  t(card, '你是一位精通二语习得机制与可理解性输入理论的英语口语陪练，对话对象具备大学英语四级基础，排斥死记硬背单词与语法条文，希望通过每日自然交流逐步提升至雅思六点五分以上的流利度。在每次对话中…', { font: F.serifCn, size: 13, lh: 21, color: C.ink2, width: 292 });
  const row = frame(card, 'prompt actions', { w: 292, h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 6 });
  frame(row, 'gap', { w: 1, h: 1, grow: 1 });
  icon(row, 'edit', 13, C.clayDeep);
  t(row, '编辑提示词', { font: F.cnMd, size: 11, color: C.clayDeep, lh: 15 });
}

function caption(text, x) {
  const n = cap(page, text, x, 120 + 792 + 16);
  n.name = 'cap 20 ' + text.slice(0, 12);
  return n;
}

/* ---------------- 20 三组各收成一行 ---------------- */
const s17 = screen('20 · 设置 · 三组收起', 7600);
sectionTitle(s17.body, '实时对话');
const g17a = groupCard(s17.body);
providerRow(g17a, { name: 'Gemini Live', meta: 'gemini-3.8-live', on: true, status: '已配置', dot: true, open: false });
sectionTitle(s17.body, '发音评测');
const g17b = groupCard(s17.body);
providerRow(g17b, { name: '微软 Azure 语音评测', meta: 'japaneast', on: true, status: '已配置', dot: true, open: false });
sectionTitle(s17.body, '文本分析');
const g17c = groupCard(s17.body);
providerRow(g17c, { name: 'DeepSeek', meta: 'deepseek-chat', on: true, status: '已配置', dot: true, open: false });
sectionTitle(s17.body, '打卡统计');
inst(s17.body, C_['Speaker/Heatmap']);
sectionTitle(s17.body, '系统提示词');
promptCard(s17.body);
caption('三组各占一行：单选点＝生效中，第二行是这一家的模型或端点，行尾箭头点开同组', 7600);

/* ---------------- 21 展开已配置的那家 ---------------- */
const s18 = screen('21 · 设置 · 展开已配置', 8000);
sectionTitle(s18.body, '实时对话');
const g18 = groupCard(s18.body);
providerRow(g18, { name: 'Gemini Live', meta: 'gemini-3.8-live', on: true, open: true, status: '已配置', dot: true, clear: true });
const voice = inst(g18, C_['Speaker/Row/Voice']);
// 音色只写名字，不写供应商那边的 voice_type；中文名字制在那行原来的等宽字体上会掉字，改成中文字体。
const voiceValue = voice.findOne(n => n.type === 'TEXT' && n.name === 'value');
if (voiceValue) { voiceValue.fontName = F.cnMd; voiceValue.fontSize = 13; voiceValue.lineHeight = { unit: 'PIXELS', value: 18 }; }
cardRule(g18);
providerRow(g18, { name: 'OpenAI Realtime', meta: 'gpt-realtime-2.1', on: false, open: false, status: '未配置' });
cardRule(g18);
providerRow(g18, { name: '豆包实时语音', meta: '1.2.6.1', on: false, open: false, status: '未配置' });
sectionTitle(s18.body, '发音评测');
const g18b = groupCard(s18.body);
providerRow(g18b, { name: '微软 Azure 语音评测', meta: 'japaneast', on: true, status: '已配置', dot: true, open: false });
sectionTitle(s18.body, '文本分析');
const g18c = groupCard(s18.body);
providerRow(g18c, { name: 'DeepSeek', meta: 'deepseek-chat', on: true, status: '已配置', dot: true, open: false });
sectionTitle(s18.body, '打卡统计');
inst(s18.body, C_['Speaker/Heatmap']);
caption('已配置那家展开只留音色与清除，不再问密钥；另两家仍是未配置，切换只动单选点', 8000);

/* ---------------- 22 展开没配置的那家 ---------------- */
const s19 = screen('22 · 设置 · 展开未配置', 8400);
sectionTitle(s19.body, '实时对话');
const g19 = groupCard(s19.body);
providerRow(g19, { name: 'Gemini Live', meta: 'gemini-3.8-live', on: true, open: false, status: '已配置', dot: true, clear: true });
cardRule(g19);
providerRow(g19, { name: '豆包实时语音', meta: '1.2.6.1', on: false, open: true, status: '未配置' });
const form = frame(g19, 'form 豆包', { w: 320, dir: 'VERTICAL', pl: 14, pr: 14, pb: 14, gap: 12 });
field(form, 'API Key', '');
picker(form, '音色', '小何');
saveButton(form);
cardRule(g19);
providerRow(g19, { name: 'OpenAI Realtime', meta: 'gpt-realtime-2.1', on: false, open: false, status: '未配置' });
sectionTitle(s19.body, '发音评测');
const g19b = groupCard(s19.body);
providerRow(g19b, { name: '微软 Azure 语音评测', meta: 'japaneast', on: true, status: '已配置', dot: true, open: false });
sectionTitle(s19.body, '文本分析');
const g19c = groupCard(s19.body);
providerRow(g19c, { name: 'DeepSeek', meta: 'deepseek-chat', on: true, status: '已配置', dot: true, open: false });
caption('没配置那家就地铺表单：凭据、音色、保存；存下来才重建连接，不边填边存', 8400);

return {
  frames: [s17.f, s18.f, s19.f].map(f => [String(f.name), f.id, Math.round(f.width), Math.round(f.height)]),
  voiceRow: voice ? Math.round(voice.height) : null
};
