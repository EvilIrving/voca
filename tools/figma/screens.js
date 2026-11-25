let page = figma.root.children.find(pn => pn.name === '02 · 界面');
if (!page) { page = figma.createPage(); page.name = '02 · 界面'; }
await figma.setCurrentPageAsync(page);

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const lib = figma.root.children.find(x => x.name === '01 · 组件');
// 非当前页的子节点不会自动加载，取组件前先把组件页切成当前页再读。
await figma.setCurrentPageAsync(lib);
const C_ = {};
for (const n of lib.children) { if (n.type === 'COMPONENT' || n.type === 'COMPONENT_SET') C_[String(n.name)] = n; }
await figma.setCurrentPageAsync(page);
const need = ['Speaker/TopBar', 'Speaker/TopBar/Chat', 'Speaker/TopBar/Review', 'Speaker/Indicator/DoubleChevronDown', 'Speaker/InputBar', 'Speaker/InputBar/Text', 'Speaker/InputBar/Recording', 'Speaker/InputBar/Cancel', 'Speaker/InputBar/Locked', 'Speaker/InputBar/Preview', 'Speaker/LockChip', 'Speaker/Actions/Message', 'Speaker/Menu/Message', 'Speaker/Bubble/Echo', 'Speaker/Bubble/Echo/Translated', 'Speaker/Bubble/User', 'Speaker/Score/Badge', 'Speaker/HintCard', 'Speaker/Heatmap', 'Speaker/Heatmap/Selected', 'Speaker/Field/Search', 'Speaker/Chip/Filter/On', 'Speaker/Chip/Filter/Off', 'Speaker/VocabCard/Collocation', 'Speaker/VocabCard/Word', 'Speaker/VocabCard/New', 'Speaker/VocabCard/SwipeOpen', 'Speaker/Score/Value', 'Speaker/SectionLabel', 'Speaker/SectionLabel/Mic', 'Speaker/Chip/Word', 'Speaker/Panel/Original', 'Speaker/Panel/Rewrite', 'Speaker/Tab/On', 'Speaker/Tab/Off', 'Speaker/Button/Ghost', 'Speaker/Button/Primary/Label', 'Speaker/IconButton/30/dismiss', 'Speaker/IconButton/30/edit', 'Speaker/IconButton/32/arrowLeft', 'Speaker/IconButton/32/chatAdd', 'Speaker/IconButton/30/checkMark', 'Speaker/Select/Menu', 'Speaker/Select/Menu/Voice', 'Speaker/Row/Voice', 'Speaker/Row/Voice/Open', 'Speaker/Row/Provider', 'Speaker/Row/Provider/Off', 'Speaker/Row/Provider/Open', 'Speaker/Toast', 'Speaker/Toast/Undo', 'Speaker/Row/DrawerSession', 'Speaker/Row/DrawerSession/Current', 'Speaker/Button/NewSession', 'Speaker/IconButton/36/search', 'Speaker/IconButton/44/settings'];
const missing = need.filter(n => !C_[n]);
if (missing.length) return { error: 'missing components', missing };

// 组件齐了才清上一轮的产物。先清后查，中途缺一个组件就会把界面页清空，
// 那一轮白跑、画板还光着——这一条顺序不能换。
page.children.filter(n => n.name.indexOf('SPK') === 0 || n.name.indexOf('cap ') === 0).forEach(n => n.remove());

const TOP = 44, BAR_Y = 653, LIST_H = 653 - TOP;

function screen(name, x, y) {
  const f = frame(page, 'SPK ' + name, { w: 360, h: 792, fill: C.paper, dir: 'VERTICAL', clip: true, x: x, y: y === undefined ? 120 : y });
  f.primaryAxisSizingMode = 'FIXED';
  f.resize(360, 792);
  return f;
}

function echoRow(parent, text, translated, gloss, showActions) {
  const col = frame(parent, 'msg AI', { w: 320, dir: 'VERTICAL', cross: 'MIN', gap: 4 });
  inst(col, C_[translated ? 'Speaker/Bubble/Echo/Translated' : 'Speaker/Bubble/Echo'], translated ? { text: text, gloss: gloss } : { text: text });
  if (showActions !== false) {
    inst(col, C_['Speaker/Actions/Message'] || C_['Speaker/Menu/Message']);
  }
  return col;
}
function userRow(parent, text, overall) {
  const col = frame(parent, 'msg user', { w: 320, dir: 'VERTICAL', cross: 'MAX', gap: 9 });
  const bubble = text ? inst(col, C_['Speaker/Bubble/User'], { text: text }) : null;
  if (bubble) {
    const badge = scoreBadge(col, C_['Speaker/Score/Badge'], overall);
    badge.name = 'score badge';
    badge.layoutPositioning = 'ABSOLUTE';
    badge.x = bubble.x + bubble.width - badge.width - 2;
    badge.y = bubble.height - 13;
  }
  return col;
}

/* 顶栏 + 对话列表 + 浮在底部的输入条 */
function newSessionStack(root) {
  inst(root, C_['Speaker/TopBar']);
  const list = frame(root, 'transcript', { w: 360, h: LIST_H, dir: 'VERTICAL', main: 'MIN', cross: 'CENTER', pl: 24, pr: 24, pt: 130, pb: 0, clip: true });

  const hero = frame(list, 'hero', { dir: 'VERTICAL', cross: 'CENTER', gap: 8 });
  t(hero, 'Voca', { font: F.serifMd, size: 28, color: C.ink, lh: 34 });
  t(hero, 'Speak freely, learn naturally.', { font: F.serifIt, size: 16, color: C.ink2, lh: 22 });
  t(hero, '自由开口，自然习得', { font: F.serifCn, size: 13, color: C.ink4, lh: 18 });

  frame(list, 'spacer', { w: 1, h: 285 });

  const ind = inst(list, C_['Speaker/Indicator/DoubleChevronDown']);
  ind.name = 'indicator/doubleChevronDown';

  return list;
}

function chatStack(root, o) {
  o = o || {};
  inst(root, C_[o.top || 'Speaker/TopBar/Chat']);
  const list = frame(root, 'transcript', { w: 360, h: o.listH || LIST_H, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 10, gap: 16, clip: true });
  echoRow(list, 'Good morning! How did you start your day? Are you craving an Americano or something else?');
  userRow(list, 'I get up early today. Actually I… 嗯… 没睡好，feeling a bit tired, so I want a cup of hot Americano.', 84);
  echoRow(list, 'An Americano on a tired morning — that is a very honest choice. Do you usually brew it at home, or is there a café you keep going back to?');
  if (o.hint !== false) inst(list, C_['Speaker/HintCard'], { text: 'Actually, I didn’t sleep a wink last night, so a hot Americano is exactly what I need.' });
  return list;
}

function bar(root, name) {
  const b = inst(root, C_['Speaker/InputBar' + (name || '')]);
  b.layoutPositioning = 'ABSOLUTE';
  b.x = 0; b.y = BAR_Y;
  return b;
}

function overBubbleName(root) {
  return root.findOne(n => n.name === 'session');
}

function dropdown(root, anchor, componentName, w, items) {
  const ab = anchor.absoluteBoundingBox, rb = root.absoluteBoundingBox;
  const m = inst(root, C_['Speaker/' + componentName]);
  m.layoutPositioning = 'ABSOLUTE';
  m.x = Math.round(ab.x - rb.x); m.y = Math.round(ab.y + ab.height - rb.y + 6);
  if (w !== m.width) { m.resize(w, m.height); m.primaryAxisSizingMode = 'AUTO'; }
  m.children.forEach((o, i) => {
    const it = items[i]; if (!it) return;
    const nm = o.findOne(n => n.type === 'TEXT' && n.name === 'name');
    const nt = o.findOne(n => n.type === 'TEXT' && n.name === 'note');
    if (nm) nm.characters = it[0];
    if (nt && it[1]) nt.characters = it[1];
  });
  return m;
}

/* ---------------- 01 对话（空态） ---------------- */
const chat = screen('01 · 对话', 0);
newSessionStack(chat);
bar(chat);

/* ---------------- 02 对话（有草稿） ---------------- */
const draft = screen('02 · 有草稿', 400);
chatStack(draft);
bar(draft, '/Text');

/* ---------------- 03 按住录音 ---------------- */
const rec = screen('03 · 按住录音', 800);
chatStack(rec, { hint: false });
bar(rec, '/Recording');

/* ---------------- 04 左滑取消 ---------------- */
const cancel = screen('04 · 左滑取消', 1200);
chatStack(cancel, { hint: false });
bar(cancel, '/Cancel');

/* ---------------- 05 锁定录音 ---------------- */
const locked = screen('05 · 锁定录音', 1600);
chatStack(locked, { hint: false });
bar(locked, '/Locked');

/* ---------------- 06 暂停预览 ---------------- */
const preview = screen('06 · 暂停预览', 2000);
chatStack(preview, { hint: false });
bar(preview, '/Preview');

/* ---------------- 07 消息操作 ---------------- */
const menu = screen('07 · 消息操作', 2400);
chatStack(menu);
bar(menu);

/* ---------------- 08 评测抽屉 ---------------- */
const ev = screen('08 · 评测抽屉', 2800);
inst(ev, C_['Speaker/TopBar/Chat']);
const list8 = frame(ev, 'transcript', { w: 360, h: LIST_H, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 10, gap: 16, clip: true });
echoRow(list8, 'Good morning! How did you start your day? Are you craving an Americano or something else?');
frame(ev, 'scrim', { w: 360, h: 792, fill: C.ink, fillA: 0.18, x: 0, y: 0, absolute: true });
const sheet = frame(ev, 'eval sheet', { w: 360, dir: 'VERTICAL', pl: 20, pr: 20, pt: 12, pb: 24, x: 0, y: 0, absolute: true, fill: C.card, corners: [26, 26, 0, 0], clip: true });
sheet.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.07 }, offset: { x: 0, y: -4 }, radius: 20, spread: 0, blendMode: 'NORMAL', visible: true }];
/* 抽屉顶部不放拖拽把手：安卓的评测抽屉固定 602 高，没有下拉手势，横条只会承诺一个不存在的动作。 */
frame(sheet, 'sp 1', { w: 1, h: 8 });
const head = frame(sheet, 'head', { w: 320, dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 });
t(head, '本轮评测', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
frame(head, 'gap', { w: 1, h: 1, grow: 1 });
inst(head, C_['Speaker/IconButton/30/dismiss']);
frame(sheet, 'sp 2', { w: 1, h: 12 });
inst(sheet, C_['Speaker/Panel/Original'], { text: 'I get up early today. Actually, I… 嗯… 没睡好, so I want a cup of hot Americano.' });
frame(sheet, 'sp 3', { w: 1, h: 18 });
const scores = frame(sheet, 'scores', { w: 320, dir: 'HORIZONTAL', cross: 'MIN', gap: 18 });
const s1 = inst(scores, C_['Speaker/Score/Value'], { label: '发音', value: '86', note: '元音偏扁，收尾略赶' });
s1.layoutGrow = 1;
const s2 = inst(scores, C_['Speaker/Score/Value'], { label: '语法', value: '82', note: '时态与冠词两处需要收紧' });
s2.layoutGrow = 1;
frame(sheet, 'sp 4', { w: 1, h: 18 });
inst(sheet, C_['Speaker/SectionLabel/Mic'], { label: '音素建议', trailing: '' });
frame(sheet, 'sp 4b', { w: 1, h: 8 });
const ph = frame(sheet, 'phoneme row', { w: 320, dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 });
inst(ph, C_['Speaker/Chip/Word'], { label: 'early' });
t(ph, '／əː／ 读成了 ／e／，嘴型再圆一点', { font: F.cn, size: 12, color: C.ink3, lh: 18 });
frame(ph, 'gap', { w: 1, h: 1, grow: 1 });
frame(sheet, 'sp 5', { w: 1, h: 18 });
frame(sheet, 'rule', { w: 320, h: 1, fill: C.hair });
frame(sheet, 'sp 6', { w: 1, h: 18 });
const tabs = frame(sheet, 'tabs', { h: 30, dir: 'HORIZONTAL', gap: 8 });
inst(tabs, C_['Speaker/Tab/On']);
inst(tabs, C_['Speaker/Tab/Off']);
frame(sheet, 'sp 8', { w: 1, h: 10 });
inst(sheet, C_['Speaker/Panel/Rewrite'], { text: 'I got up early, but I slept badly — so a hot Americano is exactly what I need.' });
frame(sheet, 'sp 9', { w: 1, h: 18 });
const cta = frame(sheet, 'cta', { w: 320, h: 48, dir: 'HORIZONTAL', cross: 'CENTER', gap: 10 });
inst(cta, C_['Speaker/Button/Ghost']);
inst(cta, C_['Speaker/Button/Primary/Label'], { label: '继续对话' }).layoutGrow = 1;
sheet.y = 792 - sheet.height;
list8.resize(360, BAR_Y - TOP - 0);

/* ---------------- 09 生词库系列（常态、滑动删除、删除撤销） ---------------- */
function buildVocabScreen(screenName, screenX, mode) {
  const vo = screen(screenName, screenX);
  const vh = frame(vo, 'header', { w: 360, h: TOP, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, gap: 10 });
  inst(vh, C_['Speaker/IconButton/32/arrowLeft']);
  t(vh, '生词库', { font: F.serifCnMd, size: 20, color: C.ink, lh: 27 });

  const vs = frame(vo, 'search wrap', { w: 360, dir: 'VERTICAL', pl: 20, pr: 20, pb: 12 });
  inst(vs, C_['Speaker/Field/Search']);

  const vf = frame(vo, 'filters', { w: 360, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, gap: 8 });
  inst(vf, C_['Speaker/Chip/Filter/On'], { label: mode === 'undo' ? '今天 2' : '今天 3' });
  inst(vf, C_['Speaker/Chip/Filter/Off'], { label: '待复习 1' });
  inst(vf, C_['Speaker/Chip/Filter/Off'], { label: mode === 'undo' ? '全部 8' : '全部 9' });

  const vlist = frame(vo, 'list', { w: 360, h: 792 - TOP - 52 - 44, dir: 'VERTICAL', pl: 20, pr: 20, pt: 6, gap: 12, clip: true });

  function vocabCard(variant, word, phonetic, definition, example, highlight) {
    const i = inst(vlist, C_['Speaker/VocabCard/' + variant], { word: word, phonetic: phonetic, definition: definition, example: example });
    const ex = i.findOne(n => n.type === 'TEXT' && n.name === 'example');
    const idx = example.indexOf(highlight);
    if (ex && idx >= 0) ex.setRangeFills(idx, idx + highlight.length, [p(C.clayDeep)]);
    return i;
  }

  vocabCard('Collocation', 'slept badly', '/slept ˈbædli/', '睡得不好。', 'I got up early, but I slept badly — so a hot Americano is exactly what I need.', 'slept badly');

  if (mode === 'swipe') {
    inst(vlist, C_['Speaker/VocabCard/SwipeOpen']);
  } else if (mode === 'normal') {
    vocabCard('Word', 'crave', '/kreɪv/', '强烈渴望。', 'Are you craving an Americano or something else?', 'craving');
  }

  vocabCard('New', 'keep going back to', '/kiːp ˈɡəʊɪŋ bæk tuː/', '反复回到、总是去。', 'Is there a café you keep going back to?', 'keep going back to');

  if (mode === 'undo') {
    const toast = inst(vo, C_['Speaker/Toast/Undo']);
    toast.layoutPositioning = 'ABSOLUTE';
    toast.x = Math.round((360 - toast.width) / 2);
    toast.y = TOP + 8;
  }
}

buildVocabScreen('09 · 生词库', 3200, 'normal');
buildVocabScreen('09b · 生词滑动删除', 3600, 'swipe');
buildVocabScreen('09c · 生词删除撤销', 4000, 'undo');

/* ---------------- 10 设置：3 组各收 1 行，选完供应商就地填表 ---------------- */
/* 三组都只有 1 张卡。收起只留生效那 1 行；点开在卡内展开同组全部几家；
   已配置那家展开后只有音色与行尾清除，没配过的就地铺凭据、音色与保存。
   已连通这 1 档随连接自检一起退场，状态只剩未配置与已配置。 */
/* 顶栏加滚动体，设置页的 4 帧与系统提示词那帧弹窗底下的那层共用这一份。 */
function settingsPageBody(root) {
  const hd = frame(root, 'header', { w: 360, h: TOP, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, gap: 10 });
  inst(hd, C_['Speaker/IconButton/32/arrowLeft']);
  t(hd, '设置', { font: F.serifCnMd, size: 20, color: C.ink, lh: 27 });
  return frame(root, 'body', { w: 360, h: 792 - TOP, dir: 'VERTICAL', pl: 20, pr: 20, pb: 20, gap: 8, clip: true });
}

function settingsShell(name, x) {
  const s = screen(name, x);
  return { screen: s, body: settingsPageBody(s) };
}

function settingsSection(parent, text) {
  const row = frame(parent, 'section ' + text, { w: 320, h: 18, dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 });
  t(row, text, { font: F.cn, size: 10, color: C.ink4, lh: 14, ls: 10 });
}

function settingsGroup(parent) {
  return frame(parent, 'group', { w: 320, dir: 'VERTICAL', fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 16, clip: true });
}

function settingsRule(parent) {
  const r = frame(parent, 'rule', { w: 292, h: 1, fill: C.hair, fillA: 0.5 });
  r.layoutAlign = 'CENTER';
}

/* 1 家 1 行：单选点填实是生效中，第二行是这家的模型号；
   open 有值时带开合箭头，clear 为真时行尾多 1 枚清除。 */
function settingsProviderRow(parent, o) {
  const row = frame(parent, 'row ' + o.name, { w: 320, h: 54, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 14, gap: 12 });
  const radio = frame(row, 'radio', { w: 18, h: 18, fill: C.card, stroke: o.on ? C.clay : C.hairStrong, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
  if (o.on) frame(radio, 'dot', { w: 9, h: 9, fill: C.clay, radius: 999 });
  const texts = frame(row, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
  t(texts, o.name, { font: F.cnMd, size: 13.5, color: o.on || o.open ? C.ink : C.ink3, lh: 19, name: 'name' });
  const metaRow = frame(texts, 'meta row', { dir: 'HORIZONTAL', cross: 'CENTER', gap: 4 });
  t(metaRow, o.meta, { font: F.mono, size: 10.5, color: o.open ? C.clayDeep : C.ink4, lh: 14, name: 'meta' });
  if (o.open !== undefined) icon(metaRow, o.open ? 'chevUp' : 'chevDown', 12, o.open ? C.clayDeep : C.ink4);
  const state = frame(row, 'state', { h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 5 });
  if (o.dot) frame(state, 'dot', { w: 5, h: 5, fill: C.clay, radius: 9 });
  t(state, o.status, { font: F.cn, size: 10.5, color: o.dot ? C.ink2 : C.ink4, lh: 14, name: 'status' });
  if (o.clear) {
    const btn = frame(row, 'clear', { w: 24, h: 24, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
    icon(btn, 'dismiss', 13, C.ink4);
  }
  return row;
}

/* 表单里 1 格：标签在上，浅底输入框在下，与弹窗里那套字段同构。 */
function settingsField(parent, label, value) {
  const col = frame(parent, 'field ' + label, { w: 292, dir: 'VERTICAL', gap: 6 });
  t(col, label, { font: F.cn, size: 11, color: C.ink3, lh: 15 });
  const box = frame(col, 'box', { w: 292, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 12, fill: C.tint, radius: 12 });
  if (value) t(box, value, { font: F.cn, size: 13, color: C.ink2, lh: 18 });
  return col;
}

/* 音色这类只挑不填的项：标签在上，浅底 1 行，右端开合箭头。中文名落等宽字体上会掉字，用中文字体。 */
function settingsPicker(parent, label, value) {
  const col = frame(parent, 'picker ' + label, { w: 292, dir: 'VERTICAL', gap: 6 });
  t(col, label, { font: F.cn, size: 11, color: C.ink3, lh: 15 });
  const box = frame(col, 'box', { w: 292, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 12, gap: 8, fill: C.tint, radius: 12 });
  const texts = frame(box, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1, main: 'CENTER' });
  t(texts, value, { font: F.cnMd, size: 13, color: C.ink2, lh: 18, name: 'value' });
  icon(box, 'chevDown', 12, C.ink4);
  return col;
}

function settingsSave(parent) {
  const b = frame(parent, 'save', { w: 292, h: 44, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.clay, radius: 22 });
  t(b, '保存', { font: F.cnMd, size: 13.5, color: C.card, lh: 19 });
}

function settingsPrompt(parent) {
  const card = frame(parent, 'prompt card', { w: 320, dir: 'VERTICAL', gap: 8, pl: 14, pr: 14, pt: 12, pb: 12, fill: C.paper, stroke: C.hair, strokeA: 0.5, radius: 16 });
  t(card, '你是一位精通二语习得机制与可理解性输入理论的英语口语陪练，对话对象具备大学英语四级基础，排斥死记硬背单词与语法条文，希望通过每日自然交流逐步提升至雅思六点五分以上的流利度。在每次对话中…', { font: F.serifCn, size: 13, lh: 21, color: C.ink2, width: 292 });
  const row = frame(card, 'prompt actions', { w: 292, h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 6 });
  frame(row, 'gap', { w: 1, h: 1, grow: 1 });
  icon(row, 'edit', 13, C.clayDeep);
  t(row, '编辑提示词', { font: F.cnMd, size: 11, color: C.clayDeep, lh: 15 });
}

/* 三组收起：每张卡只有生效那 1 行，整屏铺到系统提示词，不用滚。 */
function settingsCollapsed(body) {
  settingsSection(body, '实时对话');
  const live = settingsGroup(body);
  settingsProviderRow(live, { name: 'Gemini Live', meta: 'gemini-3.8-live', on: true, open: false, status: '已配置', dot: true });
  settingsSection(body, '发音评测');
  const asr = settingsGroup(body);
  settingsProviderRow(asr, { name: '微软 Azure 语音评测', meta: 'japaneast', on: true, open: false, status: '已配置', dot: true });
  settingsSection(body, '文本分析');
  const txt = settingsGroup(body);
  settingsProviderRow(txt, { name: 'DeepSeek', meta: 'deepseek-chat', on: true, open: false, status: '已配置', dot: true });
  settingsSection(body, '打卡统计');
  inst(body, C_['Speaker/Heatmap']);
  settingsSection(body, '系统提示词');
  settingsPrompt(body);
}

/* 另两组收着，只留生效那 1 行。 */
function settingsOtherGroups(body) {
  settingsSection(body, '发音评测');
  const asr = settingsGroup(body);
  settingsProviderRow(asr, { name: '微软 Azure 语音评测', meta: 'japaneast', on: true, open: false, status: '已配置', dot: true });
  settingsSection(body, '文本分析');
  const txt = settingsGroup(body);
  settingsProviderRow(txt, { name: 'DeepSeek', meta: 'deepseek-chat', on: true, open: false, status: '已配置', dot: true });
}

/* 展开已配置那家：音色 1 行接在生效那行下面，另两家仍是未配置；密钥不再出现，换密钥走行尾清除。 */
function settingsConfigured(body, vendor) {
  vendor = vendor || { name: 'Gemini Live', meta: 'gemini-3.8-live', voice: 'Aoede', others: [
    { name: 'OpenAI Realtime', meta: 'gpt-realtime-2.1' },
    { name: '豆包实时语音', meta: '1.2.6.1' }
  ] };
  settingsSection(body, '实时对话');
  const live = settingsGroup(body);
  settingsProviderRow(live, { name: vendor.name, meta: vendor.meta, on: true, open: true, status: '已配置', dot: true, clear: true });
  const voice = inst(live, C_['Speaker/Row/Voice']);
  const value = voice.findOne(n => n.type === 'TEXT' && n.name === 'value');
  if (value) {
    value.characters = vendor.voice;
    value.fontName = F.cnMd;
    value.fontSize = 13;
    value.lineHeight = { unit: 'PIXELS', value: 18 };
  }
  vendor.others.forEach(other => {
    settingsRule(live);
    settingsProviderRow(live, { name: other.name, meta: other.meta, on: false, open: false, status: '未配置' });
  });
  settingsOtherGroups(body);
  return voice;
}

/* 展开没配过那家：凭据、音色、保存就地铺在这行下面；存下来才重建连接，不边填边存。 */
function settingsUnconfigured(body) {
  settingsSection(body, '实时对话');
  const live = settingsGroup(body);
  settingsProviderRow(live, { name: 'Gemini Live', meta: 'gemini-3.8-live', on: true, open: false, status: '已配置', dot: true, clear: true });
  settingsRule(live);
  settingsProviderRow(live, { name: '豆包实时语音', meta: '1.2.6.1', on: false, open: true, status: '未配置' });
  const form = frame(live, 'form 豆包', { w: 320, dir: 'VERTICAL', pl: 14, pr: 14, pb: 14, gap: 12 });
  settingsField(form, 'API Key', '');
  settingsPicker(form, '音色', '小何');
  settingsSave(form);
  settingsRule(live);
  settingsProviderRow(live, { name: 'OpenAI Realtime', meta: 'gpt-realtime-2.1', on: false, open: false, status: '未配置' });
  settingsOtherGroups(body);
}

const sg1 = settingsShell('10 · 设置', 4400);
settingsCollapsed(sg1.body);
const sg2 = settingsShell('10b · 设置 · 展开已配置', 4800);
settingsConfigured(sg2.body);
const sg3 = settingsShell('10c · 设置 · 展开未配置', 7600);
settingsUnconfigured(sg3.body);
/* 音色下拉：豆包那 4 个 O 系列音色，第 1 行是当前这只，中文名配 1 行风格词。 */
const sg4 = settingsShell('10d · 设置 · 音色下拉', 8000);
const sg4Voice = settingsConfigured(sg4.body, {
  name: '豆包实时语音',
  meta: '1.2.6.1',
  voice: '小何',
  others: [{ name: 'Gemini Live', meta: 'gemini-3.8-live' }, { name: 'OpenAI Realtime', meta: 'gpt-realtime-2.1' }]
});
/* 下拉第 1 行是选中那只，与上面那行的读音色对齐，菜单里不再另标勾。 */
const sg4Menu = dropdown(sg4.screen, sg4Voice, 'Select/Menu/Voice', 320, [
  ['小何', '中文女声 · 温柔，长对话陪伴'],
  ['vivi', '中文女声 · 多情感，日常对话'],
  ['云舟', '中文男声 · 沉稳，讲解与叙事'],
  ['小天', '中文男声 · 活泼，日常闲聊']
]);
if (sg4Menu) {
  sg4Menu.children.forEach(row => {
    const name = row.findOne(n => n.type === 'TEXT' && n.name === 'name');
    if (name) { name.fontName = F.cnMd; name.fontSize = 13; name.lineHeight = { unit: 'PIXELS', value: 18 }; }
  });
}

/* ---------------- 12 侧边抽屉系列（普通、右键浮窗、批量多选） ---------------- */
function buildDrawerScreen(screenName, screenX, mode) {
  const dr = screen(screenName, screenX);
  const panel = frame(dr, 'drawer', { w: 295, h: 792, dir: 'VERTICAL', pl: 18, pr: 18, pt: 20, pb: 20, fill: C.paper, x: 0, y: 0, absolute: true });
  
  if (mode === 'batch') {
    const drHead = frame(panel, 'head', { w: 259, h: 44, dir: 'HORIZONTAL', cross: 'CENTER' });
    t(drHead, '选择会话', { font: F.serifCnMd, size: 20, color: C.ink, lh: 27, grow: 1 });
    const cancelText = t(drHead, '取消', { font: F.cnMd, size: 14, color: C.ink3, lh: 20 });
    cancelText.name = 'cancel';
    frame(panel, 'sp 2', { w: 1, h: 10 });
    const drList = frame(panel, 'sessions', { w: 259, dir: 'VERTICAL' });
    function drGroup(label) {
      const g = frame(drList, 'group ' + label, { w: 259, h: 31, dir: 'HORIZONTAL', cross: 'MAX', pl: 6, pb: 4, pt: 12 });
      t(g, label, { font: F.sans, size: 11, color: C.ink4, lh: 15, name: 'group' });
    }
    function drSelectRow(name, checked) {
      const r = inst(drList, C_['Speaker/Row/DrawerSessionSelectable' + (checked ? '/Checked' : '')]);
      r.layoutAlign = 'STRETCH';
      const n = r.findOne(x => x.type === 'TEXT' && x.name === 'name');
      if (n) n.characters = name;
      return r;
    }
    drGroup('今天');
    drSelectRow('没睡好的早晨', true);
    drSelectRow('咖啡店点单', true);
    drSelectRow('面试自我介绍', false);
    drGroup('更早');
    drSelectRow('周末出行计划', false);
    frame(panel, 'sp 3', { w: 1, h: 1, grow: 1 });
    inst(panel, C_['Speaker/Bar/DrawerBatch']);
  } else {
    const drHead = frame(panel, 'head', { w: 259, h: 44, dir: 'HORIZONTAL', cross: 'CENTER' });
    t(drHead, '会话', { font: F.serifCnMd, size: 20, color: C.ink, lh: 27, grow: 1 });
    inst(drHead, C_['Speaker/IconButton/36/search']);
    frame(panel, 'sp 1', { w: 1, h: 8 });
    inst(panel, C_['Speaker/Button/NewSession']);
    frame(panel, 'sp 2', { w: 1, h: 14 });
    const drList = frame(panel, 'sessions', { w: 259, dir: 'VERTICAL' });
    function drGroup(label) {
      const g = frame(drList, 'group ' + label, { w: 259, h: 31, dir: 'HORIZONTAL', cross: 'MAX', pl: 6, pb: 4, pt: 12 });
      t(g, label, { font: F.sans, size: 11, color: C.ink4, lh: 15, name: 'group' });
    }
    function drRow(name, current) {
      const r = inst(drList, C_['Speaker/Row/DrawerSession' + (current ? '/Current' : '')]);
      r.layoutAlign = 'STRETCH';
      const n = r.findOne(x => x.type === 'TEXT' && x.name === 'name');
      if (n) n.characters = name;
      return r;
    }
    drGroup('今天');
    drRow('没睡好的早晨', mode !== 'menu');
    drRow('咖啡店点单', mode === 'menu');
    drRow('面试自我介绍', false);
    drGroup('更早');
    drRow('周末出行计划', false);
    frame(panel, 'sp 3', { w: 1, h: 1, grow: 1 });
    const drDock = frame(panel, 'dock', { w: 259, h: 44, dir: 'HORIZONTAL', main: 'MAX', cross: 'CENTER' });
    inst(drDock, C_['Speaker/IconButton/44/settings']);

    if (mode === 'menu') {
      const sessionMenu = inst(panel, C_['Speaker/Menu/Session']);
      sessionMenu.layoutPositioning = 'ABSOLUTE';
      sessionMenu.x = 24;
      sessionMenu.y = 226;
    }
  }

  /* 主卡片右推一个抽屉宽、缩到 0.94、圆角 32、垫暖影，表面压六成白蒙层 */
  const card = frame(dr, 'card', { w: 338, h: 745, fill: C.paper, radius: 32, clip: true, x: 306, y: 24, absolute: true });
  card.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#3A2F26'), a: 0.16 }, offset: { x: -8, y: 0 }, radius: 24, spread: 0, blendMode: 'NORMAL', visible: true }];
  const cardView = frame(card, 'card view', { w: 360, h: 792, dir: 'VERTICAL', x: 0, y: 0, absolute: true });
  chatStack(cardView);
  bar(cardView);
  try { cardView.rescale(0.94); } catch (e) {}
  frame(card, 'veil', { w: 338, h: 745, fill: C.card, fillA: 0.6, x: 0, y: 0, absolute: true });
}

buildDrawerScreen('12 · 侧边抽屉', 5200, 'normal');
buildDrawerScreen('12b · 会话菜单', 5600, 'menu');
buildDrawerScreen('12c · 批量删除', 6000, 'batch');

/* ---------------- 13 译文展开 ---------------- */
const tr = screen('13 · 译文展开', 6400);
inst(tr, C_['Speaker/TopBar/Chat']);
const trList = frame(tr, 'transcript', { w: 360, h: LIST_H, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 10, gap: 16, clip: true });
echoRow(trList, 'Was there a moment today when you completely lost track of time?', true, '今天有没有哪一刻，你完全忘了时间？');
userRow(trList, 'Yeah, when I was reading by the window. 嗯… time just flew by.', 86);
bar(tr);

/* ---------------- 14 轻提示 ---------------- */
const ts = screen('14 · 轻提示', 6800);
chatStack(ts);
bar(ts);
const toast = inst(ts, C_['Speaker/Toast']);
toast.layoutPositioning = 'ABSOLUTE';
toast.x = Math.round((360 - toast.width) / 2);
toast.y = TOP + 8;

/* ---------------- 15 系统提示词 ------------- */
/* 全局共享的一段提示词：所有会话建连接时都带上它，编辑入口只有设置页这一张卡。
   弹窗里只放正文与两个动作，全局记忆不进界面，所以这里也没有它的一行。 */
const pr = screen('15 · 系统提示词', 7200);
settingsCollapsed(settingsPageBody(pr));
frame(pr, 'scrim', { w: 360, h: 792, fill: C.ink, fillA: 0.18, x: 0, y: 0, absolute: true });
const dlg = frame(pr, 'dialog', { w: 320, dir: 'VERTICAL', fill: C.card, radius: 26, pl: 20, pr: 20, pt: 20, pb: 20, gap: 12, x: 20, y: 130, absolute: true });
dlg.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#3A2F26'), a: 0.13 }, offset: { x: 0, y: 8 }, radius: 28, spread: 0, blendMode: 'NORMAL', visible: true }];
t(dlg, '系统提示词', { font: F.serifCnMd, size: 20, lh: 27, color: C.ink });
const field = frame(dlg, 'field', { w: 280, h: 232, dir: 'VERTICAL', fill: C.tint, radius: 12, pl: 12, pr: 12, pt: 12, pb: 12, clip: true });
t(field, '你是一位精通二语习得机制与可理解性输入理论的英语口语陪练，对话对象具备大学英语四级基础，排斥死记硬背单词与语法条文，希望通过每日自然交流逐步提升至雅思六点五分以上的流利度。在每次对话中，请全程使用自然地道的英语与对方展开日常或深度话题交流，每次回复控制在两至四句话以内以保证对方拥有充足的表达空间，并在结尾提出一个顺承语境的开放式问题推动对话延续。…', { font: F.serifCn, size: 12.5, lh: 20, color: C.ink2, width: 256 });
const prRow = frame(dlg, 'actions', { w: 280, h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 6 });
t(prRow, '恢复默认', { font: F.cnMd, size: 11.5, color: C.clayDeep, lh: 16 });
const prSave = frame(dlg, 'save', { w: 280, h: 44, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.clay, radius: 22 });
t(prSave, '保存', { font: F.cnMd, size: 13.5, color: C.card, lh: 19 });

return {
  screens: page.children.filter(n => n.name.indexOf('SPK ') === 0).length,
  bars: ['Speaker/InputBar', 'Speaker/InputBar/Text', 'Speaker/InputBar/Recording', 'Speaker/InputBar/Cancel', 'Speaker/InputBar/Locked', 'Speaker/InputBar/Preview'].map(n => [n, Math.round(C_[n].width), Math.round(C_[n].height)])
};
