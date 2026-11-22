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
const need = ['Speaker/TopBar', 'Speaker/TopBar/Chat', 'Speaker/TopBar/Review', 'Speaker/Indicator/DoubleChevronDown', 'Speaker/InputBar', 'Speaker/InputBar/Text', 'Speaker/InputBar/Recording', 'Speaker/InputBar/Cancel', 'Speaker/InputBar/Locked', 'Speaker/InputBar/Preview', 'Speaker/LockChip', 'Speaker/Menu/Message', 'Speaker/Bubble/Echo', 'Speaker/Bubble/Echo/Translated', 'Speaker/Bubble/User', 'Speaker/ScorePill', 'Speaker/HintCard', 'Speaker/Field/Search', 'Speaker/Chip/Filter/On', 'Speaker/Chip/Filter/Off', 'Speaker/VocabCard/Collocation', 'Speaker/VocabCard/Word', 'Speaker/VocabCard/New', 'Speaker/Score/Value', 'Speaker/SectionLabel', 'Speaker/SectionLabel/Mic', 'Speaker/Chip/Word', 'Speaker/Panel/Original', 'Speaker/Panel/Rewrite', 'Speaker/Tab/On', 'Speaker/Tab/Off', 'Speaker/Button/Ghost', 'Speaker/Button/Primary/Label', 'Speaker/IconButton/30/dismiss', 'Speaker/IconButton/30/edit', 'Speaker/IconButton/32/arrowLeft', 'Speaker/IconButton/32/chatAdd', 'Speaker/IconButton/30/checkMark', 'Speaker/Select/Menu', 'Speaker/Select/Menu/Voice', 'Speaker/Row/Voice', 'Speaker/Row/Voice/Open', 'Speaker/Row/Provider', 'Speaker/Row/Provider/Off', 'Speaker/Row/Provider/Open', 'Speaker/Toast', 'Speaker/Row/DrawerSession', 'Speaker/Row/DrawerSession/Current', 'Speaker/Button/NewSession', 'Speaker/IconButton/36/search', 'Speaker/IconButton/44/settings'];
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
  const lb = t(page, 'SPK ' + name, { font: F.mono, size: 11, color: C.ink3, lh: 16 });
  lb.name = 'cap ' + name;
  lb.x = x; lb.y = (y === undefined ? 120 : y) - 32;
  return f;
}

function echoRow(parent, text, translated, gloss) {
  const row = frame(parent, 'msg AI', { w: 320, dir: 'HORIZONTAL' });
  inst(row, C_[translated ? 'Speaker/Bubble/Echo/Translated' : 'Speaker/Bubble/Echo'], translated ? { text: text, gloss: gloss } : { text: text });
  return row;
}
function userRow(parent, text, pron, gram) {
  const col = frame(parent, 'msg user', { w: 320, dir: 'VERTICAL', cross: 'MAX', gap: 9 });
  if (text) inst(col, C_['Speaker/Bubble/User'], { text: text });
  const p = inst(col, C_['Speaker/ScorePill']);
  if (pron !== undefined) {
    const a = p.findOne(n => n.type === 'TEXT' && n.name === 'pron');
    const b = p.findOne(n => n.type === 'TEXT' && n.name === 'gram');
    if (a) a.characters = String(pron);
    if (b) b.characters = String(gram);
  }
  return col;
}

/* 顶栏 + 对话列表 + 浮在底部的输入条 */
function newSessionStack(root) {
  inst(root, C_['Speaker/TopBar']);
  const list = frame(root, 'transcript', { w: 360, h: LIST_H, dir: 'VERTICAL', main: 'MIN', cross: 'CENTER', pl: 24, pr: 24, pt: 130, pb: 0, clip: true });

  const hero = frame(list, 'hero', { dir: 'VERTICAL', cross: 'CENTER', gap: 8 });
  t(hero, 'Parley', { font: F.serifMd, size: 28, color: C.ink, lh: 34 });
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
  userRow(list, 'I get up early today. Actually I… 嗯… 没睡好，feeling a bit tired, so I want a cup of hot Americano.', 86, 82);
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

/* ---------------- 07 消息菜单 ---------------- */
const menu = screen('07 · 消息菜单', 2400);
chatStack(menu);
frame(menu, 'scrim', { w: 360, h: 792, fill: C.ink, fillA: 0.08, x: 0, y: 0, absolute: true });
const menuCard = inst(menu, C_['Speaker/Menu/Message']);
menuCard.layoutPositioning = 'ABSOLUTE';
menuCard.x = 32; menuCard.y = 196;
bar(menu);

/* ---------------- 08 评测抽屉 ---------------- */
const ev = screen('08 · 评测抽屉', 2800);
inst(ev, C_['Speaker/TopBar/Chat']);
const list8 = frame(ev, 'transcript', { w: 360, h: LIST_H, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 10, gap: 16, clip: true });
echoRow(list8, 'Good morning! How did you start your day? Are you craving an Americano or something else?');
frame(ev, 'scrim', { w: 360, h: 792, fill: C.ink, fillA: 0.18, x: 0, y: 0, absolute: true });
const sheet = frame(ev, 'eval sheet', { w: 360, dir: 'VERTICAL', pl: 20, pr: 20, pt: 12, pb: 24, x: 0, y: 0, absolute: true, fill: C.card, corners: [26, 26, 0, 0], clip: true });
sheet.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.07 }, offset: { x: 0, y: -4 }, radius: 20, spread: 0, blendMode: 'NORMAL', visible: true }];
const handle = frame(sheet, 'handle', { w: 38, h: 4, fill: C.hairStrong, radius: 2 });
handle.layoutAlign = 'CENTER';
frame(sheet, 'sp 1', { w: 1, h: 16 });
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
frame(scores, 'divider', { w: 1, h: 76, fill: C.hair });
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
inst(sheet, C_['Speaker/SectionLabel'], { label: '母语者表达', trailing: '' });
frame(sheet, 'sp 7', { w: 1, h: 10 });
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

/* ---------------- 09 生词库 ---------------- */
const vo = screen('09 · 生词库', 3200);
const vh = frame(vo, 'header', { w: 360, h: TOP, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, gap: 10 });
inst(vh, C_['Speaker/IconButton/32/arrowLeft']);
t(vh, '生词库', { font: F.serifCnMd, size: 20, color: C.ink, lh: 27 });
const vs = frame(vo, 'search wrap', { w: 360, dir: 'VERTICAL', pl: 20, pr: 20, pb: 12 });
inst(vs, C_['Speaker/Field/Search']);
const vf = frame(vo, 'filters', { w: 360, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, gap: 8 });
inst(vf, C_['Speaker/Chip/Filter/On'], { label: '今天 3' });
inst(vf, C_['Speaker/Chip/Filter/Off'], { label: '待复习 1' });
inst(vf, C_['Speaker/Chip/Filter/Off'], { label: '全部 9' });
const vlist = frame(vo, 'list', { w: 360, h: 792 - TOP - 52 - 44, dir: 'VERTICAL', pl: 20, pr: 20, pt: 6, gap: 12, clip: true });
function vocabCard(variant, word, phonetic, definition, example, highlight) {
  const i = inst(vlist, C_['Speaker/VocabCard/' + variant], { word: word, phonetic: phonetic, definition: definition, example: example });
  const ex = i.findOne(n => n.type === 'TEXT' && n.name === 'example');
  const idx = example.indexOf(highlight);
  if (ex && idx >= 0) ex.setRangeFills(idx, idx + highlight.length, [p(C.clayDeep)]);
  return i;
}
vocabCard('Collocation', 'slept badly', '/slept ˈbædli/', '睡得不好。', 'I got up early, but I slept badly — so a hot Americano is exactly what I need.', 'slept badly');
vocabCard('Word', 'crave', '/kreɪv/', '强烈渴望。', 'Are you craving an Americano or something else?', 'craving');
vocabCard('New', 'keep going back to', '/kiːp ˈɡəʊɪŋ bæk tuː/', '反复回到、总是去。', 'Is there a café you keep going back to?', 'keep going back to');

/* ---------------- 10 设置 ---------------- */
function settingsBody(root, open) {
  const body = frame(root, 'body', { w: 360, h: 792 - TOP, dir: 'VERTICAL', pl: 20, pr: 20, pt: 0, pb: 20, gap: 8, clip: true });
  function sectionTitle(parent, text) {
    const row = frame(parent, 'section ' + text, { w: 320, h: 18, dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 });
    t(row, text, { font: F.cn, size: 10, color: C.ink4, lh: 14, ls: 10 });
    frame(row, 'gap', { w: 1, h: 1, grow: 1 });
  }
  function groupCard(parent) {
    return frame(parent, 'group', { w: 320, dir: 'VERTICAL', fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 16, clip: true });
  }
  function cardRule(parent) {
    const r = frame(parent, 'rule', { w: 292, h: 1, fill: C.hair, fillA: 0.5 });
    r.layoutAlign = 'CENTER';
  }
  sectionTitle(body, '实时对话');
  const g1 = groupCard(body);
  const liveRow = inst(g1, open.live ? C_['Speaker/Row/Provider/Open'] : C_['Speaker/Row/Provider'], { name: 'Gemini Live', meta: 'gemini-3.8-live', status: '已连通' });
  cardRule(g1);
  const voiceRow = inst(g1, open.voice ? C_['Speaker/Row/Voice/Open'] : C_['Speaker/Row/Voice'], { value: 'Aoede' });
  cardRule(g1);
  inst(g1, C_['Speaker/Row/Provider/Off'], { name: 'OpenAI Realtime', meta: 'gpt-realtime', status: '未配置' });
  cardRule(g1);
  inst(g1, C_['Speaker/Row/Provider/Off'], { name: '豆包端到端实时语音', meta: 'volc.speech.dialog', status: '未配置' });

  sectionTitle(body, '发音评测');
  const g2 = groupCard(body);
  inst(g2, C_['Speaker/Row/Provider/Off'], { name: '微软 Azure 语音评测', meta: 'F0 · 每月 5 小时', status: '未配置' });
  cardRule(g2);
  inst(g2, C_['Speaker/Row/Provider/Off'], { name: '讯飞评测流式版', meta: '每日 500 次', status: '未配置' });

  sectionTitle(body, '文本分析');
  const g3 = groupCard(body);
  inst(g3, C_['Speaker/Row/Provider'], { name: 'DeepSeek', meta: 'deepseek-chat', status: '已连通' });
  cardRule(g3);
  inst(g3, C_['Speaker/Row/Provider/Off'], { name: 'Gemini Flash', meta: 'gemini-flash-latest', status: '未配置' });

  sectionTitle(body, '系统提示词');
  const prompt = frame(body, 'prompt card', { w: 320, dir: 'VERTICAL', gap: 8, pl: 14, pr: 14, pt: 12, pb: 12, fill: C.paper, stroke: C.hair, strokeA: 0.5, radius: 16 });
  t(prompt, '你是一位精通二语习得机制与可理解性输入理论的英语口语陪练，对话对象具备大学英语四级基础，排斥死记硬背单词与语法条文，希望通过每日自然交流逐步提升至雅思六点五分以上的流利度。在每次对话中…', { font: F.serifCn, size: 13, lh: 21, color: C.ink2, width: 292 });
  const pRow = frame(prompt, 'prompt actions', { w: 292, h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 6 });
  frame(pRow, 'gap', { w: 1, h: 1, grow: 1 });
  icon(pRow, 'edit', 13, C.clayDeep);
  t(pRow, '编辑提示词', { font: F.cnMd, size: 11, color: C.clayDeep, lh: 15 });

  const selfCheck = frame(body, 'row self check', { w: 320, h: 54, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 14, gap: 12, fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 16 });
  icon(selfCheck, 'sync', 16, C.clayDeep);
  const scTexts = frame(selfCheck, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
  t(scTexts, '连接自检', { font: F.cnMd, size: 13.5, color: C.ink, lh: 19, name: 'label' });
  t(scTexts, '上次 3.2 秒前', { font: F.mono, size: 10.5, color: C.ink4, lh: 14, name: 'meta' });
  icon(selfCheck, 'chevRight', 12, C.ink4);
  return { body: body, liveRow: liveRow, voiceRow: voiceRow };
}

function settingsScreen(name, x, open) {
  const s = screen(name, x);
  const hd = frame(s, 'header', { w: 360, h: TOP, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, gap: 10 });
  inst(hd, C_['Speaker/IconButton/32/arrowLeft']);
  t(hd, '设置', { font: F.serifCnMd, size: 20, color: C.ink, lh: 27 });
  const cfg = settingsBody(s, open || {});
  return { screen: s, row: cfg.liveRow, voiceRow: cfg.voiceRow, body: cfg.body };
}
const cfg = settingsScreen('10 · 设置', 3600, {});
const md = settingsScreen('11 · 模型下拉', 4000, { live: true });
dropdown(md.screen, md.row, 'Select/Menu', 320, [
  ['gemini-3.8-live', '手动回合 · 首包 450 毫秒'],
  ['gemini-3.8-live-extended-thinking', '扩展思考 · 更深推理'],
  ['gemini-3.1-flash-live-preview', '更快首包 · 预览'],
  ['gemini-2.5-flash-native-audio-latest', '原生音频 · 长会话']
]);

/* ---------------- 13 侧边抽屉 ---------------- */
const dr = screen('12 · 侧边抽屉', 4400);
const panel = frame(dr, 'drawer', { w: 295, h: 792, dir: 'VERTICAL', pl: 18, pr: 18, pt: 20, pb: 20, fill: C.paper, x: 0, y: 0, absolute: true });
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
drRow('没睡好的早晨', true);
drRow('咖啡店点单');
drRow('面试自我介绍');
drGroup('更早');
drRow('周末出行计划');
frame(panel, 'sp 3', { w: 1, h: 1, grow: 1 });
const drDock = frame(panel, 'dock', { w: 259, h: 44, dir: 'HORIZONTAL', main: 'MAX', cross: 'CENTER' });
inst(drDock, C_['Speaker/IconButton/44/settings']);

/* 主卡片右推一个抽屉宽、缩到 0.94、圆角 32、垫暖影，表面压六成白蒙层 */
const card = frame(dr, 'card', { w: 338, h: 745, fill: C.paper, radius: 32, clip: true, x: 306, y: 24, absolute: true });
card.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#3A2F26'), a: 0.16 }, offset: { x: -8, y: 0 }, radius: 24, spread: 0, blendMode: 'NORMAL', visible: true }];
const cardView = frame(card, 'card view', { w: 360, h: 792, dir: 'VERTICAL', x: 0, y: 0, absolute: true });
chatStack(cardView);
bar(cardView);
try { cardView.rescale(0.94); } catch (e) {}
frame(card, 'veil', { w: 338, h: 745, fill: C.card, fillA: 0.6, x: 0, y: 0, absolute: true });

/* ---------------- 14 译文展开 ---------------- */
const tr = screen('13 · 译文展开', 4800);
inst(tr, C_['Speaker/TopBar/Chat']);
const trList = frame(tr, 'transcript', { w: 360, h: LIST_H, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 10, gap: 16, clip: true });
echoRow(trList, 'Was there a moment today when you completely lost track of time?', true, '今天有没有哪一刻，你完全忘了时间？');
userRow(trList, 'Yeah, when I was reading by the window. 嗯… time just flew by.', 88, 84);
bar(tr);

/* ---------------- 15 轻提示 ---------------- */
const ts = screen('14 · 轻提示', 5200);
chatStack(ts);
bar(ts);
const toast = inst(ts, C_['Speaker/Toast']);
toast.layoutPositioning = 'ABSOLUTE';
toast.x = Math.round((360 - toast.width) / 2);
toast.y = TOP + 8;

/* ---------------- 17 系统提示词 ------------- */
/* 全局共享的一段提示词：所有会话建连接时都带上它，编辑入口只有设置页这一张卡。
   弹窗里只放正文与两个动作，全局记忆不进界面，所以这里也没有它的一行。 */
const pr = screen('15 · 系统提示词', 5600);
settingsBody(pr, {});
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
