let page = figma.root.children.find(pn => pn.name === '02 · 界面');
if (!page) { page = figma.createPage(); page.name = '02 · 界面'; }
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('SPK') === 0 || n.name.indexOf('cap ') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const lib = figma.root.children.find(x => x.name === '01 · 组件');
const C_ = {};
for (const n of lib.children) { if (n.type === 'COMPONENT') C_[String(n.name)] = n; }
const need = ['Speaker/TopBar', 'Speaker/BottomBar', 'Speaker/RecordSheet', 'Speaker/Avatar', 'Speaker/Bubble/Echo', 'Speaker/Bubble/User', 'Speaker/ScorePill', 'Speaker/HintCard', 'Speaker/Field/Search', 'Speaker/Chip/Filter/On', 'Speaker/Chip/Filter/Off', 'Speaker/VocabCard/Collocation', 'Speaker/VocabCard/Word', 'Speaker/VocabCard/New', 'Speaker/Score/Value', 'Speaker/SectionLabel', 'Speaker/SectionLabel/Mic', 'Speaker/Chip/Word', 'Speaker/Panel/Original', 'Speaker/Panel/Rewrite', 'Speaker/Tab/On', 'Speaker/Tab/Off', 'Speaker/Button/Ghost', 'Speaker/Button/Primary/Label', 'Speaker/IconButton/30/dismiss'];
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
const vt = frame(vh, 'titles', { dir: 'VERTICAL', gap: 2, grow: 1 });
t(vt, '生词库', { font: F.serifCnMd, size: 22, color: C.ink, lh: 30 });
inst(vh, C_['Speaker/IconButton/30/dismiss']);
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
const st = screen('05 · 配置中心', 1600);
const sh = frame(st, 'header', { w: 360, h: 64, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, pt: 8, pb: 8, gap: 10 });
const stt = frame(sh, 'titles', { dir: 'VERTICAL', gap: 2, grow: 1 });
t(stt, '配置中心', { font: F.serifCnMd, size: 22, color: C.ink, lh: 30 });
inst(sh, C_['Speaker/IconButton/30/dismiss']);

const body = frame(st, 'body', { w: 360, h: 728, dir: 'VERTICAL', pl: 20, pr: 20, pt: 4, pb: 20, gap: 10, clip: true });
function sectionTitle(parent, text, trailing) {
  const row = frame(parent, 'section ' + text, { w: 320, h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 });
  t(row, text, { font: F.cn, size: 10, color: C.ink4, lh: 14, ls: 10 });
  frame(row, 'gap', { w: 1, h: 1, grow: 1 });
  if (trailing) t(row, trailing, { font: F.cn, size: 10, color: C.ink4, lh: 14 });
}
function groupCard(parent) {
  return frame(parent, 'group', { w: 320, dir: 'VERTICAL', fill: C.card, stroke: C.hair, radius: 16, clip: true });
}


sectionTitle(body, '实时对话');
const g1 = groupCard(body);
inst(g1, C_['Speaker/Row/Provider'], { name: 'Gemini Live', meta: 'gemini-3.8-live', status: '已连通' });
frame(g1, 'rule', { w: 292, h: 1, fill: C.hair });
inst(g1, C_['Speaker/Row/Provider/Off'], { name: 'OpenAI Realtime', meta: 'gpt-realtime', status: '未配置' });
frame(g1, 'rule', { w: 292, h: 1, fill: C.hair });
inst(g1, C_['Speaker/Row/Provider/Off'], { name: '豆包端到端实时语音', meta: 'volc.speech.dialog', status: '未配置' });

frame(body, 'sp', { w: 1, h: 4 });
sectionTitle(body, '识别与分析');
const g2 = groupCard(body);
inst(g2, C_['Speaker/Row/Provider/Off'], { name: '微软 Azure 语音评测', meta: 'F0 · 5 小时/月', status: '未配置' });
frame(g2, 'rule', { w: 292, h: 1, fill: C.hair });
inst(g2, C_['Speaker/Row/Provider/Off'], { name: '讯飞评测流式版', meta: '每日 500 次', status: '未配置' });
frame(g2, 'rule', { w: 292, h: 1, fill: C.hair });
inst(g2, C_['Speaker/Row/Provider'], { name: 'DeepSeek', meta: 'deepseek-chat', status: '已连通' });

frame(body, 'sp', { w: 1, h: 4 });
sectionTitle(body, '角色提示词');
const prompt = frame(body, 'prompt card', { w: 320, dir: 'VERTICAL', gap: 8, pl: 14, pr: 14, pt: 12, pb: 12, fill: C.paper, stroke: C.hair, radius: 16 });
t(prompt, 'You are Parley, a warm conversation partner. Keep every reply under 40 words, ask one question back, and let the learner pause as long as needed.', { font: F.serifIt, size: 13, lh: 21, color: C.ink2, width: 292 });
const pRow = frame(prompt, 'prompt actions', { w: 292, h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 6 });
frame(pRow, 'gap', { w: 1, h: 1, grow: 1 });
icon(pRow, 'translate', 13, C.ink4);
t(pRow, '编辑提示词', { font: F.cnMd, size: 11, color: C.clayDeep, lh: 15 });

frame(body, 'sp', { w: 1, h: 8 });
const selfCheck = frame(body, 'btn self check', { w: 320, h: 48, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', gap: 8, fill: C.clay, radius: 999 });
icon(selfCheck, 'sync', 16, C.card);
t(selfCheck, '连接自检', { font: F.cnMd, size: 14, color: C.card, lh: 20, ls: 6 });
const note = frame(body, 'note', { w: 320, h: 18, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', gap: 6 });
t(note, '上次自检 3.2 秒前', { font: F.cn, size: 10, color: C.ink4, lh: 14 });

return { screens: [chat.id, rec.id, ev.id, vo.id, st.id], sheetH: Math.round(sheet.height) };
