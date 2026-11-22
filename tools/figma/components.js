let page = figma.root.children.find(pn => pn.name === '01 · 组件');
if (!page) { page = figma.createPage(); page.name = '01 · 组件'; }
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('cmp') === 0 || n.name.indexOf('Speaker/') === 0 || n.name.indexOf('cap ') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const made = {};
function makeInst(parent, name, x, y) {
  const src = page.children.find(n => n.type === 'COMPONENT' && String(n.name) === name);
  const i = src.createInstance();
  parent.appendChild(i);
  if (x !== undefined) { if (parent.layoutMode && parent.layoutMode !== 'NONE') i.layoutPositioning = 'ABSOLUTE'; i.x = x; i.y = y; }
  return i;
}
let colY = 0;
function place(c, label, colX) {
  c.x = colX; c.y = colY;
  const lb = t(page, label, { font: F.mono, size: 10, color: C.ink4, lh: 14 });
  lb.name = 'cap ' + label;
  lb.x = colX; lb.y = colY - 20;
  made[label] = c;
  colY += c.height + 64;
  return c;
}
let yStart = 120;
// column A: atoms
const A = 0, B = 420, D = 900;
let col = A;
let ys = { A: yStart, B: yStart, D: yStart };
function put(c, label, colKey) {
  const x = colKey === 'A' ? A : colKey === 'B' ? B : D;
  c.x = x; c.y = ys[colKey];
  const lb = t(page, label, { font: F.mono, size: 10, color: C.ink4, lh: 14 });
  lb.name = 'cap ' + label;
  lb.x = x; lb.y = ys[colKey] - 18;
  made[label] = c;
  ys[colKey] += c.height + 56;
  return c;
}

/* ---------------- atoms ---------------- */

function iconBtn(size, glyph, iconSize, opts) {
  return comp(page, 'Speaker/IconButton/' + size + '/' + glyph, { w: size, h: size, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: opts.fill || C.card, stroke: opts.stroke || C.hair, radius: 999 }, c => {
    icon(c, glyph, iconSize, opts.color || C.ink3);
  });
}
put(iconBtn(34, 'settings', 18, {}), 'Speaker/IconButton/34/Gear', 'A');
put(iconBtn(32, 'book', 18, { fill: C.paper, stroke: C.paper }), 'Speaker/IconButton/32/Book', 'A');
put(iconBtn(30, 'dismiss', 16, { stroke: C.paper, fill: C.paper, color: C.ink4 }), 'Speaker/IconButton/30/Dismiss', 'A');
put(iconBtn(32, 'arrowLeft', 18, { fill: C.paper, stroke: C.paper, color: C.ink2 }), 'Speaker/IconButton/32/Back', 'A');
put(iconBtn(30, 'checkMark', 15, { fill: C.clay, stroke: C.clay, color: C.card }), 'Speaker/IconButton/30/Confirm', 'A');
put(iconBtn(30, 'speaker', 15, { fill: C.clayTint, stroke: C.clayTint, color: C.clayDeep }), 'Speaker/IconButton/30/Speaker', 'A');
put(iconBtn(30, 'edit', 15, { fill: C.paper, stroke: C.paper, color: C.ink4 }), 'Speaker/IconButton/30/Edit', 'A');



put(comp(page, 'Speaker/Button/Primary/Label', { h: 48, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 20, pr: 20, fill: C.clay, radius: 999 }, c => {
  t(c, '继续对话', { font: F.cnMd, size: 14, color: C.card, lh: 20, ls: 6, name: 'label' });
}), 'Speaker/Button/Primary/Label', 'A');

put(comp(page, 'Speaker/Button/Ghost', { h: 48, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', gap: 6, pl: 16, pr: 16, fill: C.card, stroke: C.hair, radius: 999 }, c => {
  icon(c, 'bookmark', 15, C.clayDeep);
  t(c, '收进生词本', { font: F.cnMd, size: 13, color: C.ink2, lh: 18, ls: 2, name: 'label' });
}), 'Speaker/Button/Ghost', 'A');

put(comp(page, 'Speaker/Chip/Filter/On', { h: 28, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 12, pr: 12, fill: C.clay, radius: 999 }, c => {
  t(c, '全部 24', { font: F.cnMd, size: 11.5, color: C.card, lh: 16, ls: 3, name: 'label' });
}), 'Speaker/Chip/Filter/On', 'A');
put(comp(page, 'Speaker/Chip/Filter/Off', { h: 28, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 12, pr: 12, fill: C.card, stroke: C.hair, radius: 999 }, c => {
  t(c, '生词 15', { font: F.cnMd, size: 11.5, color: C.ink3, lh: 16, ls: 3, name: 'label' });
}), 'Speaker/Chip/Filter/Off', 'A');
put(comp(page, 'Speaker/Chip/Tag/Olive', { h: 22, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 8, pr: 8, fill: C.oliveTint, stroke: '#DEE4CF', radius: 6 }, c => {
  t(c, '地道搭配', { font: F.cn, size: 10, color: C.olive, lh: 14, ls: 2, name: 'label' });
}), 'Speaker/Chip/Tag/Olive', 'A');
put(comp(page, 'Speaker/Chip/Tag/Neutral', { h: 22, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 8, pr: 8, fill: C.paper, stroke: C.hair, radius: 6 }, c => {
  t(c, '复现 3 次', { font: F.cn, size: 10, color: C.ink4, lh: 14, ls: 2, name: 'label' });
}), 'Speaker/Chip/Tag/Neutral', 'A');
put(comp(page, 'Speaker/Chip/Word', { h: 26, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 9, pr: 9, fill: C.paper, stroke: C.hair, radius: 8 }, c => {
  t(c, 'early', { font: F.monoMd, size: 12, color: C.ink2, lh: 16, name: 'label' });
}), 'Speaker/Chip/Word', 'A');

put(comp(page, 'Speaker/Score/Value', { dir: 'VERTICAL', gap: 2 }, c => {
  t(c, '发音', { font: F.cn, size: 11, color: C.ink4, lh: 15, ls: 6, name: 'label' });
  const row = frame(c, 'value row', { h: 30, dir: 'HORIZONTAL', cross: 'CENTER', gap: 5 });
  t(row, '86', { font: F.serifMd, size: 27, color: C.clayDeep, lh: 30, name: 'value' });
  t(row, '分', { font: F.cn, size: 10, color: C.ink4, lh: 14, name: 'unit' });
  t(c, '元音偏扁，收尾略赶', { font: F.cn, size: 11, color: C.ink3, lh: 17, width: 148, name: 'note' });
}), 'Speaker/Score/Value', 'A');

/* ---------------- molecules ---------------- */
function echoBubble(suffix, translated) {
  return put(comp(page, 'Speaker/Bubble/Echo' + suffix, { w: 258, dir: 'VERTICAL', pl: 14, pr: 14, pt: 13, pb: 12, gap: 8, fill: C.card, stroke: C.hair, corners: [5, 18, 18, 18] }, c => {
    t(c, 'Good morning! How did you start your day? Are you craving an Americano or something else?', { font: F.serif, size: 15, lh: 23, color: C.ink, width: 230, name: 'text' });
    if (translated) t(c, '早上好，你今天是怎么开始的？想喝美式还是别的什么？', { font: F.cn, size: 12.5, lh: 19, color: C.ink3, width: 230, name: 'gloss' });
  }), 'Speaker/Bubble/Echo' + suffix, 'B');
}
echoBubble('', false);
echoBubble('/Translated', true);

put(comp(page, 'Speaker/Bubble/User', { dir: 'VERTICAL', pl: 14, pr: 14, pt: 12, pb: 12, fill: C.tint, stroke: '#E6E1D2', corners: [18, 5, 18, 18] }, c => {
  t(c, 'I get up early today. Actually I… 嗯… 没睡好，feeling a bit tired, so I want a cup of hot Americano.', { font: F.cn, size: 14, lh: 22, color: C.ink2, width: 232, name: 'text' });
}), 'Speaker/Bubble/User', 'B');

put(comp(page, 'Speaker/ScorePill', { h: 27, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 11, pr: 9, gap: 5, fill: C.card, stroke: C.hair, radius: 999 }, c => {
  t(c, '发音', { font: F.cn, size: 11, color: C.ink4, lh: 15 });
  t(c, '86', { font: F.monoMd, size: 11, color: C.clayDeep, lh: 15, name: 'pron' });
  t(c, '·', { font: F.sans, size: 11, color: C.hairStrong, lh: 15 });
  t(c, '语法', { font: F.cn, size: 11, color: C.ink4, lh: 15 });
  t(c, '82', { font: F.monoMd, size: 11, color: C.olive, lh: 15, name: 'gram' });
  icon(c, 'chevRight', 11, C.ink4);
}), 'Speaker/ScorePill', 'B');

put(comp(page, 'Speaker/HintCard', { w: 320, dir: 'HORIZONTAL', cross: 'CENTER', gap: 10, pl: 14, pr: 12, pt: 12, pb: 12, fill: C.oliveTint, stroke: '#DEE4CF', radius: 14 }, c => {
  icon(c, 'bulb', 14, C.olive);
  t(c, 'Actually, I didn’t sleep a wink last night, so a hot Americano is exactly what I need.', { font: F.serifIt, size: 14, lh: 21, color: C.ink2, width: 258, name: 'text' });
  icon(c, 'chevRight', 12, C.olive, 0.7);
}), 'Speaker/HintCard', 'B');

put(comp(page, 'Speaker/SectionLabel', { w: 320, h: 18, dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 }, c => {
  icon(c, 'bulb', 13, C.olive);
  t(c, '母语者表达', { font: F.cn, size: 11, color: C.ink4, lh: 15, ls: 6, name: 'label' });
  frame(c, 'gap', { w: 1, h: 1, grow: 1 });
  t(c, 'AI 改写', { font: F.cn, size: 11, color: C.ink4, lh: 15, name: 'trailing' });
}), 'Speaker/SectionLabel', 'B');

put(comp(page, 'Speaker/SectionLabel/Mic', { w: 320, h: 18, dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 }, c => {
  icon(c, 'mic', 13, C.ink4);
  t(c, '音素建议', { font: F.cn, size: 11, color: C.ink4, lh: 15, ls: 6, name: 'label' });
  frame(c, 'gap', { w: 1, h: 1, grow: 1 });
  t(c, '仅 1 处', { font: F.cn, size: 11, color: C.ink4, lh: 15, name: 'trailing' });
}), 'Speaker/SectionLabel/Mic', 'B');

put(comp(page, 'Speaker/Field/Search', { w: 320, h: 40, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 12, gap: 8, fill: C.card, stroke: C.hair, radius: 12 }, c => {
  icon(c, 'search', 15, C.ink4);
  t(c, '搜单词或例句', { font: F.cn, size: 12.5, color: C.ink4, lh: 18, grow: 1, name: 'placeholder' });
}), 'Speaker/Field/Search', 'B');

function providerRow(suffix, open) {
  return put(comp(page, 'Speaker/Row/Provider' + suffix, { w: 320, h: 54, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 14, gap: 12 }, c => {
    const radio = frame(c, 'radio', { w: 18, h: 18, fill: C.card, stroke: C.hairStrong, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
    frame(radio, 'dot', { w: 9, h: 9, fill: C.clay, radius: 999 });
    const txts = frame(c, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
    t(txts, 'Gemini Live', { font: F.cnMd, size: 13.5, color: C.ink, lh: 19, name: 'name' });
    const metaRow = frame(txts, 'meta row', { dir: 'HORIZONTAL', cross: 'CENTER', gap: 4 });
    t(metaRow, 'gemini-3.8-live', { font: F.mono, size: 10.5, color: open ? C.clayDeep : C.ink4, lh: 14, name: 'meta' });
    icon(metaRow, open ? 'chevUp' : 'chevDown', 12, open ? C.clayDeep : C.ink4);
    const state = frame(c, 'state', { h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 5 });
    frame(state, 'dot', { w: 5, h: 5, fill: C.olive, radius: 9 });
    t(state, '已连通', { font: F.cn, size: 10.5, color: C.olive, lh: 14, name: 'status' });
  }), 'Speaker/Row/Provider' + suffix, 'B');
}
providerRow('', false);
providerRow('/Open', true);

function vocabVariant(suffix, tags) {
  return comp(page, 'Speaker/VocabCard/' + suffix, { w: 320, dir: 'VERTICAL', pl: 14, pr: 14, pt: 12, pb: 12, gap: 7, fill: C.card, stroke: C.hair, radius: 16 }, c => {
    const top = frame(c, 'top', { w: 292, dir: 'HORIZONTAL', cross: 'MIN', gap: 8 });
    const wCol = frame(top, 'word col', { dir: 'VERTICAL', gap: 1, grow: 1 });
    t(wCol, 'slept badly', { font: F.serifMd, size: 19, color: C.ink, lh: 26, name: 'word' });
    t(wCol, '/slept ˈbædli/', { font: F.mono, size: 10.5, color: C.ink4, lh: 15, name: 'phonetic' });
    const sp = frame(top, 'btn say', { w: 30, h: 30, fill: C.clayTint, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
    icon(sp, 'speaker', 15, C.clayDeep);
    t(c, '睡得不好。比 I did not sleep well 更口语，常和 but 搭配转折。', { font: F.cn, size: 11.5, color: C.ink3, lh: 17, width: 292, name: 'definition' });
    const example = 'I got up early, but I slept badly — so a hot Americano is exactly what I need.';
    const ex = t(c, example, { font: F.serifIt, size: 13.5, color: C.ink2, lh: 21, width: 292, name: 'example' });
    const idx = example.indexOf('slept badly');
    ex.setRangeFills(idx, idx + 11, [p(C.clayDeep)]);
    const tagRow = frame(c, 'tags', { h: 22, dir: 'HORIZONTAL', cross: 'CENTER', gap: 6 });
    for (let i = 0; i < tags.length; i++) {
      const isOlive = i === 0;
      const chip = frame(tagRow, 'tag ' + tags[i], { h: 22, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 8, pr: 8, fill: isOlive ? C.oliveTint : C.paper, radius: 6, stroke: isOlive ? '#DEE4CF' : C.hair });
      t(chip, tags[i], { font: F.cn, size: 10, color: isOlive ? C.olive : C.ink4, lh: 14, ls: 2 });
    }
  });
}
put(vocabVariant('Collocation', ['地道搭配', '复现 3 次']), 'Speaker/VocabCard/Collocation', 'B');
put(vocabVariant('Word', ['生词', '复现 2 次']), 'Speaker/VocabCard/Word', 'B');
put(vocabVariant('New', ['地道搭配', '新收录']), 'Speaker/VocabCard/New', 'B');

put(comp(page, 'Speaker/Row/Provider/Off', { w: 320, h: 54, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 14, gap: 12 }, c => {
  const radio = frame(c, 'radio', { w: 18, h: 18, fill: C.card, stroke: C.hairStrong, radius: 999 });
  const txts = frame(c, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
  t(txts, 'OpenAI Realtime', { font: F.cnMd, size: 13.5, color: C.ink3, lh: 19, name: 'name' });
  t(txts, 'gpt-realtime', { font: F.mono, size: 10.5, color: C.ink4, lh: 14, name: 'meta' });
  const state = frame(c, 'state', { h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 5 });
  t(state, '未配置', { font: F.cn, size: 10.5, color: C.ink4, lh: 14, name: 'status' });
}), 'Speaker/Row/Provider/Off', 'B');

function selectOption(suffix, on, single) {
  return comp(page, 'Speaker/Select/Option' + suffix, { w: 292, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 10, pr: 10, gap: 8, fill: on ? C.clayTint : undefined, radius: 8 }, c => {
    const col = frame(c, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
    if (single) {
      t(col, on ? '随便聊' : '咖啡点单', { font: F.cnMd, size: 13, color: on ? C.ink : C.ink2, lh: 19, name: 'name' });
    } else {
      t(col, on ? 'gemini-3.8-live' : 'gemini-3.1-flash-live-preview', { font: F.monoMd, size: 11.5, color: on ? C.ink : C.ink2, lh: 16, name: 'name' });
      t(col, on ? '手动回合 · 首包 450 毫秒' : '更快首包 · 预览', { font: F.cn, size: 10.5, color: C.ink4, lh: 14, name: 'note' });
    }
    if (on) icon(c, 'checkMark', 13, C.clayDeep);
  });
}
const optOn = put(selectOption('/On', true, false), 'Speaker/Select/Option/On', 'A');
const optOff = put(selectOption('/Off', false, false), 'Speaker/Select/Option/Off', 'A');
const optSceneOn = put(selectOption('/Single/On', true, true), 'Speaker/Select/Option/Single/On', 'A');
const optSceneOff = put(selectOption('/Single/Off', false, true), 'Speaker/Select/Option/Single/Off', 'A');


function voiceRow(suffix, open) {
  return put(comp(page, 'Speaker/Row/Voice' + suffix, { w: 320, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 44, pr: 14, gap: 8 }, c => {
    t(c, '音色', { font: F.cn, size: 12, color: C.ink4, lh: 17, name: 'label' });
    frame(c, 'gap', { w: 1, h: 1, grow: 1 });
    t(c, 'Aoede', { font: F.monoMd, size: 11.5, color: open ? C.clayDeep : C.ink2, lh: 16, name: 'value' });
    icon(c, open ? 'chevUp' : 'chevDown', 12, open ? C.clayDeep : C.ink4);
  }), 'Speaker/Row/Voice' + suffix, 'B');
}
voiceRow('', false);
voiceRow('/Open', true);

/* 抽屉：检索三十起、新建会话五十二的胶囊、会话行四十四，右下角配置中心四十四 */
put(iconBtn(36, 'search', 20, { fill: C.paper, stroke: C.paper, color: C.ink2 }), 'Speaker/IconButton/36/Search', 'A');
put(iconBtn(44, 'settings', 22, { fill: C.card, stroke: C.hair, color: C.ink2 }), 'Speaker/IconButton/44/Settings', 'A');

put(comp(page, 'Speaker/Button/NewSession', { w: 259, h: 52, dir: 'HORIZONTAL', cross: 'CENTER', pl: 18, pr: 18, gap: 10, fill: C.card, stroke: C.hair, radius: 999 }, c => {
  icon(c, 'chatAdd', 24, C.ink);
  t(c, '新会话', { font: F.sansMd, size: 16, color: C.ink, lh: 22, name: 'label' });
}), 'Speaker/Button/NewSession', 'B');

function drawerRow(suffix, current) {
  return put(comp(page, 'Speaker/Row/DrawerSession' + suffix, { w: 259, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 6, pr: 12, gap: 8, fill: current ? C.clayTint : '', radius: 10 }, c => {
    icon(c, 'chat', 16, current ? C.clayDeep : C.ink4);
    t(c, '没睡好的早晨', { font: F.sansMd, size: 14, color: current ? C.ink : C.ink2, lh: 20, name: 'name', grow: 1 });
  }), 'Speaker/Row/DrawerSession' + suffix, 'B');
}
drawerRow('', false);
drawerRow('/Current', true);

put(comp(page, 'Speaker/Select/Menu/Voice', { w: 320, dir: 'VERTICAL', pl: 6, pr: 6, pt: 6, pb: 6, fill: C.card, stroke: C.hair, radius: 14 }, c => {
  c.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.07 }, offset: { x: 0, y: 6 }, radius: 18, spread: 0, blendMode: 'NORMAL', visible: true }];
  [['Aoede', '轻快 · 默认'], ['Puck', '活泼'], ['Charon', '稳重'], ['Sulafat', '温暖']].forEach(([name, note], i) => {
    const row = (i === 0 ? optOn : optOff).createInstance();
    c.appendChild(row);
    row.layoutAlign = 'STRETCH';
    const n = row.findOne(x => x.type === 'TEXT' && x.name === 'name');
    const d = row.findOne(x => x.type === 'TEXT' && x.name === 'note');
    if (n) n.characters = name;
    if (d) d.characters = note;
  });
}), 'Speaker/Select/Menu/Voice', 'B');

put(comp(page, 'Speaker/Toast', { h: 34, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 14, pr: 14, fill: C.tint, stroke: C.hair, radius: 999 }, c => {
  t(c, '已收进生词本', { font: F.cn, size: 12.5, color: C.ink2, lh: 18, name: 'label' });
}), 'Speaker/Toast', 'B');


function menuComponent(name, opts) {
  return comp(page, name, { w: 320, dir: 'VERTICAL', pl: 6, pr: 6, pt: 6, pb: 6, fill: C.card, stroke: C.hair, radius: 14 }, c => {
    c.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.07 }, offset: { x: 0, y: 6 }, radius: 18, spread: 0, blendMode: 'NORMAL', visible: true }];
    opts.forEach(o => {
      const i = o.createInstance();
      c.appendChild(i);
      i.layoutAlign = 'STRETCH';
    });
  });
}
put(menuComponent('Speaker/Select/Menu', [optOn, optOff, optOff, optOff]), 'Speaker/Select/Menu', 'B');
put(menuComponent('Speaker/Select/Menu/Scene', [optSceneOn, optSceneOff, optSceneOff, optSceneOff]), 'Speaker/Select/Menu/Scene', 'B');

put(comp(page, 'Speaker/Tab/On', { h: 30, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 14, pr: 14, fill: C.clay, radius: 999 }, c => {
  t(c, '地道表达', { font: F.cnMd, size: 12, color: C.card, lh: 16, ls: 4, name: 'label' });
}), 'Speaker/Tab/On', 'B');
put(comp(page, 'Speaker/Tab/Off', { h: 30, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 14, pr: 14, fill: C.card, stroke: C.hair, radius: 999 }, c => {
  t(c, '高级表达', { font: F.cnMd, size: 12, color: C.ink3, lh: 16, ls: 4, name: 'label' });
}), 'Speaker/Tab/Off', 'B');

put(comp(page, 'Speaker/Panel/Original', { w: 320, dir: 'HORIZONTAL', cross: 'CENTER', gap: 12, pl: 14, pr: 14, pt: 12, pb: 12, fill: C.paper, radius: 14 }, c => {
  t(c, 'I get up early today. Actually I… 嗯… 没睡好, so I want a cup of hot Americano.', { font: F.cn, size: 13.5, lh: 21, color: C.ink2, width: 232, grow: 1, name: 'text' });
  const b = frame(c, 'btn play', { w: 32, h: 32, fill: C.card, stroke: C.hair, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
  icon(b, 'play', 14, C.clayDeep);
}), 'Speaker/Panel/Original', 'B');

put(comp(page, 'Speaker/Panel/Rewrite', { w: 320, dir: 'VERTICAL', gap: 8, pl: 15, pr: 15, pt: 13, pb: 12, fill: C.paper, radius: 14 }, c => {
  t(c, 'I got up early, but I slept badly — so a hot Americano is exactly what I need.', { font: F.serif, size: 15, lh: 23, color: C.ink, width: 290, name: 'text' });
  const acts = frame(c, 'actions', { w: 290, h: 24, dir: 'HORIZONTAL', cross: 'CENTER', gap: 16 });
  icon(acts, 'speaker', 15, C.ink4);
  icon(acts, 'translate', 15, C.ink4);
  icon(acts, 'bookmark', 15, C.ink4);
}), 'Speaker/Panel/Rewrite', 'B');


/* ---------------- input bar atoms（尺寸取自真机实测 1080×2376 @3x） ---------------- */
put(comp(page, 'Speaker/Mic/44', { w: 44, h: 44, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.clay, radius: 999 }, c => {
  icon(c, 'mic', 19, C.card);
}), 'Speaker/Mic/44', 'A');

put(comp(page, 'Speaker/Mic/87', { w: 87, h: 87, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.clay, radius: 999 }, c => {
  icon(c, 'mic', 33, C.card);
}), 'Speaker/Mic/87', 'A');

put(comp(page, 'Speaker/Send/44', { w: 44, h: 44, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.ink, radius: 999 }, c => {
  icon(c, 'send', 18, C.card);
}), 'Speaker/Send/44', 'A');

put(comp(page, 'Speaker/Send/87', { w: 87, h: 87, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.ink, radius: 999 }, c => {
  icon(c, 'send', 30, C.card);
}), 'Speaker/Send/87', 'A');

put(comp(page, 'Speaker/Pause/36', { w: 36, h: 36, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.card, stroke: C.hair, radius: 999 }, c => {
  icon(c, 'pause', 12, C.ink2);
}), 'Speaker/Pause/36', 'A');

put(comp(page, 'Speaker/Resume/36', { w: 36, h: 36, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.card, stroke: C.hair, radius: 999 }, c => {
  icon(c, 'mic', 13, C.ink2);
}), 'Speaker/Resume/36', 'A');

put(comp(page, 'Speaker/Trash/44', { w: 44, h: 44, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' }, c => {
  icon(c, 'delete', 20, C.ink3);
}), 'Speaker/Trash/44', 'A');

put(comp(page, 'Speaker/LockChip', { w: 30, h: 66, dir: 'VERTICAL', cross: 'CENTER', pt: 10, pb: 8, gap: 6, fill: C.card, stroke: C.hair, radius: 15 }, c => {
  icon(c, 'lockOpen', 14, C.ink2);
  icon(c, 'chevUp', 10, C.ink4);
}), 'Speaker/LockChip', 'A');

put(comp(page, 'Speaker/PreviewBar', { w: 252, h: 32, dir: 'HORIZONTAL', cross: 'CENTER', pl: 6, pr: 8, gap: 7, fill: C.ink, radius: 999 }, c => {
  const playWrap = frame(c, 'play wrap', { h: 22, dir: 'HORIZONTAL', cross: 'CENTER', pl: 6, pr: 8, gap: 3, fill: '#FFFFFF', fillA: 0.16, radius: 999 });
  icon(playWrap, 'play', 9, C.card);
  t(playWrap, '0:06', { font: F.mono, size: 9.5, color: C.card, lh: 13, name: 'duration' });
  const bars = frame(c, 'bars', { h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 2, grow: 1 });
  const hs = [7, 12, 17, 9, 20, 13, 18, 8, 15, 21, 10, 17, 12, 19, 9, 14, 18, 11];
  hs.forEach((h, i) => {
    const b = frame(bars, 'b' + i, { w: 2, h: h, fill: C.card, radius: 1 });
    b.opacity = 0.5 + 0.5 * (h / 21);
  });
}), 'Speaker/PreviewBar', 'A');

/* ---------------- message menu ---------------- */
function menuRow(name, glyph, label, danger) {
  return put(comp(page, 'Speaker/Row/Menu/' + name, { w: 268, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 12, gap: 12, radius: 8 }, c => {
    icon(c, glyph, 16, danger ? C.danger : C.ink3);
    t(c, label, { font: F.cnMd, size: 13, color: danger ? C.danger : C.ink, lh: 19, grow: 1, name: 'label' });
  }), 'Speaker/Row/Menu/' + name, 'A');
}
const mrSpeak = menuRow('Speak', 'speaker', '朗读这一轮');
const mrTranslate = menuRow('Translate', 'translate', '翻译');
const mrSave = menuRow('Save', 'bookmark', '收进生词本');
const mrCopy = menuRow('Copy', 'copy', '复制原文');
const mrRetry = menuRow('Retry', 'sync', '重新分析');
const mrDelete = menuRow('Delete', 'delete', '删除这一轮', true);

put(comp(page, 'Speaker/Menu/Message', { w: 280, dir: 'VERTICAL', pl: 6, pr: 6, pt: 6, pb: 6, gap: 2, fill: C.card, stroke: C.hair, radius: 14 }, c => {
  c.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.08 }, offset: { x: 0, y: 8 }, radius: 22, spread: 0, blendMode: 'NORMAL', visible: true }];
  [mrSpeak, mrTranslate, mrSave, mrCopy, mrRetry, mrDelete].forEach((row, i) => {
    if (i === 4) { const r = frame(c, 'rule', { w: 244, h: 1, fill: C.hair }); r.layoutAlign = 'CENTER'; }
    const r = row.createInstance();
    c.appendChild(r);
    r.layoutAlign = 'STRETCH';
  });
}), 'Speaker/Menu/Message', 'B');

/* ---------------- input bar ----------------
   结构照 Telegram：胶囊始终 346×44，右端 44 是按钮槽；圆钮长到 87 时覆盖在槽上并向屏幕外溢出。
   录音与锁定时右侧留 44 占位，读字区实际 302。真机实测：胶囊左右 7，红点 28 距左 13，圆钮 87，锁条 30×66，暂停 36。 */
const BAR_W = 346, PILL_Y = 40, PILL_H = 44;

function barShell(c) {
  return frame(c, 'pill', { w: BAR_W, h: PILL_H, x: 7, y: PILL_Y, absolute: true, fill: C.card, stroke: C.hair, radius: 999, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 0, gap: 8 });
}
function ring(c, name, x, y) {
  const src = page.children.find(n => n.type === 'COMPONENT' && String(n.name) === name);
  const i = src.createInstance();
  c.appendChild(i);
  i.x = x; i.y = y;
  return i;
}
/* 录音读字区：点 28、计时、提示居中，右端留 44 给按钮槽 */
function recReadout(pill, hint, opts) {
  opts = opts || {};
  const dot = frame(pill, 'dot', { w: 28, h: 28, fill: C.clayDeep, radius: 999 });
  dot.layoutAlign = 'CENTER';
  if (opts.dotAlpha !== undefined) dot.opacity = opts.dotAlpha;
  t(pill, '0:02,4', { font: F.monoMd, size: 12, color: C.ink2, lh: 16, name: 'timer', colorA: opts.timerAlpha });
  const box = frame(pill, 'hint box', { w: 176, h: 1, grow: 1 });
  if (opts.hintShift) box.x = box.x - opts.hintShift;
  const h = t(box, hint, { font: F.cnMd, size: 12.5, color: C.ink3, lh: 18, name: 'hint', align: 'CENTER', width: 176 });
  if (opts.hintAlpha !== undefined) h.opacity = opts.hintAlpha;
  frame(pill, 'btn slot', { w: 44, h: 44 });
}

function inputBar(name, kind) {
  return put(comp(page, 'Speaker/InputBar' + name, { w: 360, h: 112 }, c => {
    if (kind === 'preview') {
      const pill = barShell(c);
      pill.paddingLeft = 0;
      const trash = page.children.find(n => n.type === 'COMPONENT' && String(n.name) === 'Speaker/Trash/44');
      const ti = trash.createInstance(); pill.appendChild(ti);
      const pb = page.children.find(n => n.type === 'COMPONENT' && String(n.name) === 'Speaker/PreviewBar');
      const pi = pb.createInstance(); pill.appendChild(pi);
      frame(pill, 'btn slot', { w: 44, h: 44 });
      ring(c, 'Speaker/Send/87', 288, 18);
      ring(c, 'Speaker/Resume/36', 313, -34);
      return;
    }
    if (kind === 'cancel') {
      /* 左滑取消：圆钮与提示一起左移并缩到 0.4，越过 150 就地作废，不等松手 */
      const pill = frame(c, 'pill', { w: BAR_W, h: PILL_H, x: 7, y: PILL_Y, absolute: true, fill: C.card, stroke: C.hair, radius: 999, clip: true, dir: 'HORIZONTAL', cross: 'CENTER' });
      frame(pill, 'dot', { w: 28, h: 28, x: 13, y: 8, fill: C.clayDeep, radius: 999 });
      const timerNode = t(pill, '0:02,4', { font: F.monoMd, size: 12, color: C.ink2, lh: 16, name: 'timer' });
      timerNode.layoutPositioning = 'ABSOLUTE'; timerNode.x = 49; timerNode.y = 14;
      /* 左滑到底（126）：提示淡没，圆钮缩到 0.7 并停在新位置 */
      const hintNode = t(pill, '‹ 滑动以取消', { font: F.cnMd, size: 12.5, color: C.ink3, lh: 18, name: 'hint', width: 176, align: 'CENTER' });
      hintNode.layoutPositioning = 'ABSOLUTE'; hintNode.x = 92; hintNode.y = 13; hintNode.opacity = 0;
      const mini = frame(c, 'mic half', { w: 61, h: 61, x: 174, y: 32, absolute: true, fill: C.clay, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
      icon(mini, 'mic', 26, C.card);
      return;
    }
    if (kind === 'recording' || kind === 'locked') {
      const pill = barShell(c);
      pill.paddingLeft = 13;
      recReadout(pill, kind === 'locked' ? '取消' : '‹ 滑动以取消');
      if (kind === 'locked') {
        ring(c, 'Speaker/Send/87', 288, 18);
        ring(c, 'Speaker/Pause/36', 313, -34);
      } else {
        ring(c, 'Speaker/Mic/87', 288, 18);
        ring(c, 'Speaker/LockChip', 315, -68);
      }
      return;
    }
    const pill = barShell(c);
    const withText = kind === 'text';
    t(pill, withText ? '今天想聊聊工作上的事' : '说点什么', { font: F.cn, size: 13, color: withText ? C.ink : C.ink4, lh: 19, grow: 1, name: withText ? 'draft' : 'placeholder' });
    const btn = page.children.find(n => n.type === 'COMPONENT' && String(n.name) === (withText ? 'Speaker/Send/44' : 'Speaker/Mic/44'));
    const bi = btn.createInstance(); pill.appendChild(bi);
  }), 'Speaker/InputBar' + name, 'A');
}
const barEmpty = inputBar('', 'empty');
const barText = inputBar('/Text', 'text');
const barRec = inputBar('/Recording', 'recording');
const barLock = inputBar('/Locked', 'locked');
const barCancel = inputBar('/Cancel', 'cancel');
const barPreview = inputBar('/Preview', 'preview');

return { count: Object.keys(made).length, labels: Object.keys(made) };
