let page = figma.root.children.find(pn => pn.name === '02 · 界面');
if (!page) { page = figma.createPage(); page.name = '02 · 界面'; }
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('SPK') === 0 || n.name.indexOf('cap ') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const lib = figma.root.children.find(x => x.name === '01 · 组件');
const C_ = {};
for (const n of lib.children) { if (n.type === 'COMPONENT') C_[String(n.name)] = n; }
const need = ['Speaker/TopBar', 'Speaker/TopBar/Open', 'Speaker/BottomBar', 'Speaker/RecordSheet', 'Speaker/Avatar', 'Speaker/Bubble/Echo', 'Speaker/Bubble/User', 'Speaker/ScorePill', 'Speaker/HintCard', 'Speaker/Field/Search', 'Speaker/Chip/Filter/On', 'Speaker/Chip/Filter/Off', 'Speaker/VocabCard/Collocation', 'Speaker/VocabCard/Word', 'Speaker/VocabCard/New', 'Speaker/Score/Value', 'Speaker/SectionLabel', 'Speaker/SectionLabel/Mic', 'Speaker/Chip/Word', 'Speaker/Panel/Original', 'Speaker/Panel/Rewrite', 'Speaker/Tab/On', 'Speaker/Tab/Off', 'Speaker/Button/Ghost', 'Speaker/Button/Primary/Label', 'Speaker/IconButton/30/dismiss', 'Speaker/IconButton/30/edit', 'Speaker/IconButton/32/arrowLeft', 'Speaker/Select/Menu', 'Speaker/Row/Provider', 'Speaker/Row/Provider/Off', 'Speaker/Row/Provider/Open', 'Speaker/Row/Action/Text', 'Speaker/Row/Action/Summary', 'Speaker/Row/Action/Records', 'Speaker/Row/Session', 'Speaker/IconButton/44/add'];
const missing = need.filter(n => !C_[n]);
if (missing.length) return { error: 'missing components', missing };

function screen(name, x) {
  const f = frame(page, 'SPK ' + name, { w: 360, h: 792, fill: C.paper, dir: 'VERTICAL', clip: true, x: x, y: 120 });
  f.primaryAxisSizingMode = 'FIXED';
  f.resize(360, 792);
  const lb = t(page, 'SPK ' + name, { font: F.mono, size: 11, color: C.ink3, lh: 16 });
  lb.name = 'cap ' + name;
  lb.x = x; lb.y = 88;
  return f;
}

function echoRow(parent, text) {
  const row = frame(parent, 'msg AI', { w: 320, dir: 'HORIZONTAL', gap: 10 });
  inst(row, C_['Speaker/Avatar']);
  inst(row, C_['Speaker/Bubble/Echo'], { text: text });
  return row;
}
function userRow(parent, text, pron, gram) {
  const col = frame(parent, 'msg user', { w: 320, dir: 'VERTICAL', cross: 'MAX', gap: 9 });
  if (text) inst(col, C_['Speaker/Bubble/User'], { text: text });
  inst(col, C_['Speaker/ScorePill'], { pron: String(pron), gram: String(gram) });
  return col;
}

/* ---------------- 01 chat ---------------- */
const chat = screen('01 · 对话', 0);
inst(chat, C_['Speaker/TopBar']);
const list = frame(chat, 'transcript', { w: 360, h: 656, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 16, gap: 18, clip: true });
echoRow(list, 'Good morning! How did you start your day? Are you craving an Americano or something else?');
userRow(list, 'I get up early today. Actually I… 嗯… 没睡好，feeling a bit tired, so I want a cup of hot Americano.', 86, 82);
echoRow(list, 'An Americano on a tired morning — that is a very honest choice. Do you usually brew it at home, or is there a café you keep going back to?');
inst(list, C_['Speaker/HintCard'], { text: 'Actually, I didn’t sleep a wink last night, so a hot Americano is exactly what I need.' });
inst(chat, C_['Speaker/BottomBar']);

/* ---------------- 02 recording ---------------- */
const rec = screen('02 · 收音中', 400);
inst(rec, C_['Speaker/TopBar']);
const sheetH = Math.round(C_['Speaker/RecordSheet'].height);
const list2 = frame(rec, 'transcript', { w: 360, h: 792 - 56 - sheetH, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 16, gap: 18, clip: true });
echoRow(list2, 'Good morning! How did you start your day? Are you craving an Americano or something else?');
userRow(list2, 'I get up early today. Actually I… 嗯… 没睡好，feeling a bit tired, so I want a cup of hot Americano.', 86, 82);
frame(rec, 'scrim', { w: 360, h: 792, fill: C.ink, fillA: 0.12, x: 0, y: 0, absolute: true });
const recSheet = inst(rec, C_['Speaker/RecordSheet']);
recSheet.layoutPositioning = 'ABSOLUTE'; recSheet.x = 0; recSheet.y = 792 - recSheet.height;
const recTitle = recSheet.findOne(n => n.type === 'TEXT' && n.name === 'title');
if (recTitle) recTitle.characters = '松手发送';

/* ---------------- 03 evaluation ---------------- */
const ev = screen('03 · 评测抽屉', 800);
inst(ev, C_['Speaker/TopBar']);
const list3 = frame(ev, 'transcript', { w: 360, h: 656, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 16, gap: 18, clip: true });
echoRow(list3, 'Good morning! How did you start your day? Are you craving an Americano or something else?');
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
inst(sheet, C_['Speaker/Panel/Original'], { text: 'I get up early today. Actually I… 嗯… 没睡好, so I want a cup of hot Americano.' });
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
const primary = inst(cta, C_['Speaker/Button/Primary/Label'], { label: '继续对话' });
primary.layoutGrow = 1;
sheet.y = 792 - sheet.height;
list3.resize(360, 792 - 56 - sheet.height);

/* ---------------- 04 vocab ---------------- */
const vo = screen('04 · 生词库', 1200);
const vh = frame(vo, 'header', { w: 360, h: 64, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, pt: 8, pb: 8, gap: 10 });
inst(vh, C_['Speaker/IconButton/32/arrowLeft']);
t(vh, '生词库', { font: F.serifCnMd, size: 22, color: C.ink, lh: 30 });
const vs = frame(vo, 'search wrap', { w: 360, dir: 'VERTICAL', pl: 20, pr: 20, pb: 12 });
inst(vs, C_['Speaker/Field/Search']);
const vf = frame(vo, 'filters', { w: 360, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, gap: 8 });
inst(vf, C_['Speaker/Chip/Filter/On'], { label: '全部 24' });
inst(vf, C_['Speaker/Chip/Filter/Off'], { label: '地道搭配 9' });
inst(vf, C_['Speaker/Chip/Filter/Off'], { label: '生词 15' });
const vlist = frame(vo, 'list', { w: 360, h: 616, dir: 'VERTICAL', pl: 20, pr: 20, pt: 6, gap: 12, clip: true });
function group(text) {
  const row = frame(vlist, 'group ' + text, { w: 320, h: 22, dir: 'HORIZONTAL', cross: 'CENTER', gap: 10 });
  t(row, text, { font: F.cn, size: 10, color: C.ink4, lh: 14, ls: 10 });
  frame(row, 'rule', { w: 1, h: 1, grow: 1, fill: C.hair });
}
group('今天');
function vocabCard(variant, word, phonetic, definition, example, highlight) {
  const i = inst(vlist, C_['Speaker/VocabCard/' + variant], { word: word, phonetic: phonetic, definition: definition, example: example });
  const ex = i.findOne(n => n.type === 'TEXT' && n.name === 'example');
  const idx = example.indexOf(highlight);
  if (ex && idx >= 0) ex.setRangeFills(idx, idx + highlight.length, [p(C.clayDeep)]);
  return i;
}
vocabCard('Collocation', 'slept badly', '/slept ˈbædli/', '睡得不好。', 'I got up early, but I slept badly — so a hot Americano is exactly what I need.', 'slept badly');
vocabCard('Word', 'crave', '/kreɪv/', '强烈渴望。', 'Are you craving an Americano or something else?', 'craving');
group('昨天');
vocabCard('New', 'keep going back to', '/kiːp ˈɡəʊɪŋ bæk tuː/', '反复回到、总是去。', 'Is there a café you keep going back to?', 'keep going back to');


/* ---------------- 05 settings ---------------- */
function settingsScreen(name, x, open) {
  const s = screen(name, x);
  const hd = frame(s, 'header', { w: 360, h: 64, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, pt: 8, pb: 8, gap: 10 });
  inst(hd, C_['Speaker/IconButton/32/arrowLeft']);
  t(hd, '配置中心', { font: F.serifCnMd, size: 22, color: C.ink, lh: 30 });
  const cfg = settingsBody(s, open);
  return { screen: s, row: cfg.row, body: cfg.body };
}

function settingsBody(root, open) {
const body = frame(root, 'body', { w: 360, h: 728, dir: 'VERTICAL', pl: 20, pr: 20, pt: 0, pb: 20, gap: 8, clip: true });
function sectionTitle(parent, text, trailing) {
  const row = frame(parent, 'section ' + text, { w: 320, h: 18, dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 });
  t(row, text, { font: F.cn, size: 10, color: C.ink4, lh: 14, ls: 10 });
  frame(row, 'gap', { w: 1, h: 1, grow: 1 });
  if (trailing) t(row, trailing, { font: F.cn, size: 10, color: C.ink4, lh: 14 });
}
function groupCard(parent) {
  return frame(parent, 'group', { w: 320, dir: 'VERTICAL', fill: C.card, stroke: C.hair, radius: 16, clip: true });
}
function cardRule(parent) {
  const r = frame(parent, 'rule', { w: 292, h: 1, fill: C.hair });
  r.layoutAlign = 'CENTER';
}

sectionTitle(body, '实时对话');
const g1 = groupCard(body);
const liveRow = inst(g1, open ? C_['Speaker/Row/Provider/Open'] : C_['Speaker/Row/Provider'], { name: 'Gemini Live', meta: 'gemini-3.8-live', status: '已连通' });
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

sectionTitle(body, '角色提示词');
const prompt = frame(body, 'prompt card', { w: 320, dir: 'VERTICAL', gap: 8, pl: 14, pr: 14, pt: 12, pb: 12, fill: C.paper, stroke: C.hair, radius: 16 });
t(prompt, 'You are Parley, a warm conversation partner. Keep every reply under 40 words, ask one question back, and let the learner pause as long as needed.', { font: F.serifIt, size: 13, lh: 21, color: C.ink2, width: 292 });
const pRow = frame(prompt, 'prompt actions', { w: 292, h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 6 });
frame(pRow, 'gap', { w: 1, h: 1, grow: 1 });
icon(pRow, 'translate', 13, C.ink4);
t(pRow, '编辑提示词', { font: F.cnMd, size: 11, color: C.clayDeep, lh: 15 });

const selfCheck = frame(body, 'row self check', { w: 320, h: 54, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 14, gap: 12, fill: C.card, stroke: C.hair, radius: 16 });
icon(selfCheck, 'sync', 16, C.clayDeep);
const scTexts = frame(selfCheck, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
t(scTexts, '连接自检', { font: F.cnMd, size: 13.5, color: C.ink, lh: 19, name: 'label' });
t(scTexts, '上次 3.2 秒前', { font: F.mono, size: 10.5, color: C.ink4, lh: 14, name: 'meta' });
icon(selfCheck, 'chevRight', 12, C.ink4);
return { body: body, row: liveRow };
}
const cfg = settingsScreen('05 · 配置中心', 1600, false);
const st = cfg.screen;

/* ---------------- 06 scene dropdown ---------------- */
function dropdown(root, anchor, w, items, cnName) {
  const ab = anchor.absoluteBoundingBox, rb = root.absoluteBoundingBox;
  const m = inst(root, C_['Speaker/Select/Menu']);
  m.layoutPositioning = 'ABSOLUTE';
  m.x = Math.round(ab.x - rb.x); m.y = Math.round(ab.y + ab.height - rb.y + 6);
  if (w !== 320) { m.resize(w, m.height); m.primaryAxisSizingMode = 'AUTO'; }
  m.children.forEach((o, i) => {
    const it = items[i]; if (!it) return;
    const nm = o.findOne(n => n.type === 'TEXT' && n.name === 'name');
    const nt = o.findOne(n => n.type === 'TEXT' && n.name === 'note');
    if (nm) {
      nm.characters = it[0];
      if (cnName) { nm.fontName = F.cnMd; nm.fontSize = 13; nm.lineHeight = { unit: 'PIXELS', value: 19 }; }
    }
    if (nt) nt.characters = it[1];
  });
  return m;
}

const sc = screen('06 · 场景下拉', 2000);
frame(sc, 'scrim', { w: 360, h: 792, fill: C.ink, fillA: 0.1, x: 0, y: 0, absolute: true });
const scTop = inst(sc, C_['Speaker/TopBar/Open']);
const scList = frame(sc, 'transcript', { w: 360, h: 656, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 16, gap: 18, clip: true });
echoRow(scList, 'Good morning! How did you start your day? Are you craving an Americano or something else?');
userRow(scList, 'I get up early today. Actually I… 嗯… 没睡好，feeling a bit tired, so I want a cup of hot Americano.', 86, 82);
echoRow(scList, 'An Americano on a tired morning — that is a very honest choice. Do you usually brew it at home, or is there a café you keep going back to?');
inst(sc, C_['Speaker/BottomBar']);
const scPill = scTop.findOne(n => n.name === 'scene pill');
dropdown(sc, scPill || scTop, 232, [
  ['随便聊', '想到什么说什么'],
  ['咖啡点单', '门店与口味'],
  ['面试对练', '自我介绍与追问'],
  ['雅思考官', '第二部分长答']
], true);

/* ---------------- 07 model dropdown ---------------- */
const md = settingsScreen('07 · 模型下拉', 2400, true);
dropdown(md.screen, md.row, 320, [
  ['gemini-3.8-live', '手动回合 · 首包 450 毫秒'],
  ['gemini-3.8-live-extended-thinking', '扩展思考 · 更深推理'],
  ['gemini-3.1-flash-live-preview', '更快首包 · 预览'],
  ['gemini-2.5-flash-native-audio-latest', '原生音频 · 长会话']
], false);

/* ---------------- 08 action sheet ---------------- */
function chatStack(root) {
  const top = inst(root, C_['Speaker/TopBar']);
  const list = frame(root, 'transcript', { w: 360, h: 656, dir: 'VERTICAL', main: 'MAX', pl: 20, pr: 20, pt: 6, pb: 16, gap: 18, clip: true });
  echoRow(list, 'Good morning! How did you start your day? Are you craving an Americano or something else?');
  userRow(list, 'I get up early today. Actually I… 嗯… 没睡好，feeling a bit tired, so I want a cup of hot Americano.', 86, 82);
  echoRow(list, 'An Americano on a tired morning — that is a very honest choice. Do you usually brew it at home, or is there a café you keep going back to?');
  return { top: top, list: list };
}

const as = screen('08 · 动作面板', 2800);
chatStack(as);
frame(as, 'scrim', { w: 360, h: 792, fill: C.ink, fillA: 0.12, x: 0, y: 0, absolute: true });
const asBar = inst(as, C_['Speaker/BottomBar']);
const addBtn = asBar.findOne(n => n.type === 'INSTANCE' && String(n.name).indexOf('Speaker/IconButton/44/add') === 0);
if (addBtn) addBtn.fills = [p(C.clayTint)];

const panel = frame(as, 'action sheet', { w: 360, dir: 'VERTICAL', pl: 20, pr: 20, pt: 12, pb: 20, x: 0, y: 0, absolute: true, fill: C.card, corners: [26, 26, 0, 0], clip: true });
panel.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.07 }, offset: { x: 0, y: -4 }, radius: 20, spread: 0, blendMode: 'NORMAL', visible: true }];
const asHandle = frame(panel, 'handle', { w: 38, h: 4, fill: C.hairStrong, radius: 2 });
asHandle.layoutAlign = 'CENTER';
frame(panel, 'sp 1', { w: 1, h: 16 });
const actions = frame(panel, 'actions', { w: 320, dir: 'VERTICAL', fill: C.paper, radius: 16 });
inst(actions, C_['Speaker/Row/Action/Text']);
frame(actions, 'rule', { w: 292, h: 1, fill: C.hair });
inst(actions, C_['Speaker/Row/Action/Summary']);
frame(actions, 'rule', { w: 292, h: 1, fill: C.hair });
inst(actions, C_['Speaker/Row/Action/Records']);
panel.y = 792 - 80 - panel.height;
asBar.layoutPositioning = 'ABSOLUTE'; asBar.x = 0; asBar.y = 792 - 80;

/* ---------------- 09 session summary ---------------- */
const sm = screen('09 · 会话小结', 3200);
chatStack(sm);
frame(sm, 'scrim', { w: 360, h: 792, fill: C.ink, fillA: 0.18, x: 0, y: 0, absolute: true });
const sumSheet = frame(sm, 'summary sheet', { w: 360, dir: 'VERTICAL', pl: 20, pr: 20, pt: 12, pb: 24, x: 0, y: 0, absolute: true, fill: C.card, corners: [26, 26, 0, 0], clip: true });
sumSheet.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.07 }, offset: { x: 0, y: -4 }, radius: 20, spread: 0, blendMode: 'NORMAL', visible: true }];
const smHandle = frame(sumSheet, 'handle', { w: 38, h: 4, fill: C.hairStrong, radius: 2 });
smHandle.layoutAlign = 'CENTER';
frame(sumSheet, 'sp 1', { w: 1, h: 16 });
const smHead = frame(sumSheet, 'head', { w: 320, dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 });
t(smHead, '没睡好的早晨', { font: F.serifCnMd, size: 18, color: C.ink, lh: 26 });
frame(smHead, 'gap', { w: 1, h: 1, grow: 1 });
inst(smHead, C_['Speaker/IconButton/30/edit']);
inst(smHead, C_['Speaker/IconButton/30/dismiss']);
frame(sumSheet, 'sp 2', { w: 1, h: 14 });
const stats = frame(sumSheet, 'stats', { w: 320, dir: 'HORIZONTAL', cross: 'MIN', fill: C.paper, radius: 14, pl: 16, pr: 16, pt: 12, pb: 12 });
function stat(parent, label, value, unit) {
  const col = frame(parent, 'stat ' + label, { dir: 'VERTICAL', gap: 2, grow: 1 });
  t(col, label, { font: F.cn, size: 10, color: C.ink4, lh: 14, ls: 6 });
  const row = frame(col, 'v', { dir: 'HORIZONTAL', cross: 'CENTER', gap: 3 });
  t(row, value, { font: F.serifMd, size: 22, color: C.ink, lh: 26 });
  if (unit) t(row, unit, { font: F.cn, size: 10, color: C.ink4, lh: 14 });
}
stat(stats, '轮次', '12', '');
frame(stats, 'divider', { w: 1, h: 40, fill: C.hair });
stat(stats, '发音', '84', '分');
frame(stats, 'divider', { w: 1, h: 40, fill: C.hair });
stat(stats, '语法', '81', '分');
frame(stats, 'divider', { w: 1, h: 40, fill: C.hair });
stat(stats, '新词', '5', '条');
frame(sumSheet, 'sp 3', { w: 1, h: 14 });
const smHint = frame(sumSheet, 'hint', { w: 320, dir: 'HORIZONTAL', cross: 'CENTER', gap: 10, pl: 14, pr: 12, pt: 12, pb: 12, fill: C.oliveTint, stroke: '#DEE4CF', radius: 14 });
icon(smHint, 'bulb', 14, C.olive);
t(smHint, 'slept badly 这一轮复现三次，下次试着自己先用上。', { font: F.cn, size: 12.5, color: C.ink2, lh: 19, width: 262 });
frame(sumSheet, 'sp 4', { w: 1, h: 18 });
const smCta = frame(sumSheet, 'cta', { w: 320, h: 48, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.clay, radius: 999 });
t(smCta, '开始新会话', { font: F.cnMd, size: 14, color: C.card, lh: 20, ls: 6 });
sumSheet.y = 792 - sumSheet.height;

/* ---------------- 10 session records ---------------- */
const rc = screen('10 · 会话记录', 3600);
const rh = frame(rc, 'header', { w: 360, h: 64, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, pt: 8, pb: 8, gap: 10 });
inst(rh, C_['Speaker/IconButton/32/arrowLeft']);
t(rh, '会话记录', { font: F.serifCnMd, size: 22, color: C.ink, lh: 30 });
const rlist = frame(rc, 'list', { w: 360, h: 728, dir: 'VERTICAL', pl: 20, pr: 20, pt: 4, gap: 12, clip: true });
function recordGroup(text) {
  const row = frame(rlist, 'group ' + text, { w: 320, h: 18, dir: 'HORIZONTAL', cross: 'CENTER', gap: 10 });
  t(row, text, { font: F.cn, size: 10, color: C.ink4, lh: 14, ls: 10 });
  frame(row, 'rule', { w: 1, h: 1, grow: 1, fill: C.hair });
}
function sessionRow(name, meta) {
  return inst(rlist, C_['Speaker/Row/Session'], { name: name, meta: meta });
}
recordGroup('今天');
sessionRow('没睡好的早晨', '07:40 · 12 轮 · 发音 84');
sessionRow('咖啡店点单', '06:20 · 8 轮 · 发音 81');
recordGroup('更早');
sessionRow('面试自我介绍', '昨天 22:10 · 15 轮 · 发音 79');
sessionRow('雅思口语第三部分', '昨天 07:05 · 10 轮 · 发音 77');
sessionRow('周末出行计划', '周二 21:30 · 9 轮 · 发音 83');

return { screens: [chat.id, rec.id, ev.id, vo.id, st.id, sc.id, md.screen.id, as.id, sm.id, rc.id], sheetH: Math.round(sheet.height), settingsBody: Math.round(cfg.body.height) };
