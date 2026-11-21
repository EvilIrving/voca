let page = figma.root.children.find(pn => pn.name === '01 · 组件');
if (!page) { page = figma.createPage(); page.name = '01 · 组件'; }
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('cmp') === 0 || n.name.indexOf('Speaker/') === 0 || n.name.indexOf('cap ') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const made = {};
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
}

/* ---------------- atoms ---------------- */
put(comp(page, 'Speaker/Avatar', { w: 30, h: 30, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.clay, radius: 999 }, c => {
  t(c, 'E', { font: F.serifSb, size: 15, color: C.card, lh: 18, name: 'label' });
}), 'Speaker/Avatar · 30', 'A');

function iconBtn(size, glyph, iconSize, opts) {
  return comp(page, 'Speaker/IconButton/' + size + '/' + glyph, { w: size, h: size, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: opts.fill || C.card, stroke: opts.stroke || C.hair, radius: 999 }, c => {
    icon(c, glyph, iconSize, opts.color || C.ink3);
  });
}
put(iconBtn(44, 'add', 19, {}), 'Speaker/IconButton/44/Add', 'A');
put(iconBtn(44, 'callEnd', 19, {}), 'Speaker/IconButton/44/CallEnd', 'A');
put(iconBtn(34, 'settings', 18, {}), 'Speaker/IconButton/34/Gear', 'A');
put(iconBtn(32, 'book', 18, { fill: C.paper, stroke: C.paper }), 'Speaker/IconButton/32/Book', 'A');
put(iconBtn(30, 'dismiss', 16, { stroke: C.paper, fill: C.paper, color: C.ink4 }), 'Speaker/IconButton/30/Dismiss', 'A');
put(iconBtn(30, 'speaker', 15, { fill: C.clayTint, stroke: C.clayTint, color: C.clayDeep }), 'Speaker/IconButton/30/Speaker', 'A');

put(comp(page, 'Speaker/Button/Primary/Talk', { h: 48, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', gap: 8, pl: 20, pr: 20, fill: C.clay, radius: 999 }, c => {
  icon(c, 'mic', 19, C.card);
  t(c, '按住说话', { font: F.cnMd, size: 14, color: C.card, lh: 20, ls: 2, name: 'label' });
}), 'Speaker/Button/Primary/Talk', 'A');

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
put(comp(page, 'Speaker/Bubble/Echo', { w: 258, dir: 'VERTICAL', pl: 14, pr: 14, pt: 13, pb: 11, gap: 9, fill: C.card, stroke: C.hair, corners: [5, 18, 18, 18] }, c => {
  t(c, 'Good morning! How did you start your day? Are you craving an Americano or something else?', { font: F.serif, size: 15, lh: 23, color: C.ink, width: 230, name: 'text' });
  const acts = frame(c, 'actions', { w: 230, h: 16, dir: 'HORIZONTAL', gap: 16, cross: 'CENTER' });
  icon(acts, 'speaker', 16, C.ink4);
  icon(acts, 'translate', 16, C.ink4);
  icon(acts, 'bookmark', 16, C.ink4);
}), 'Speaker/Bubble/Echo', 'B');

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

put(comp(page, 'Speaker/Row/Provider', { w: 320, h: 58, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 14, gap: 12 }, c => {
  const radio = frame(c, 'radio', { w: 18, h: 18, fill: C.card, stroke: C.hairStrong, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
  frame(radio, 'dot', { w: 9, h: 9, fill: C.clay, radius: 999 });
  const txts = frame(c, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
  t(txts, 'Gemini Live', { font: F.cnMd, size: 13.5, color: C.ink, lh: 19, name: 'name' });
  t(txts, 'gemini-3.8-live · 手动回合', { font: F.mono, size: 10.5, color: C.ink4, lh: 14, name: 'meta' });
  const state = frame(c, 'state', { h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 5 });
  frame(state, 'dot', { w: 5, h: 5, fill: C.olive, radius: 9 });
  t(state, '已连通', { font: F.cn, size: 10.5, color: C.olive, lh: 14, name: 'status' });
}), 'Speaker/Row/Provider', 'B');

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

put(comp(page, 'Speaker/Row/Provider/Off', { w: 320, h: 58, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 14, gap: 12 }, c => {
  const radio = frame(c, 'radio', { w: 18, h: 18, fill: C.card, stroke: C.hairStrong, radius: 999 });
  const txts = frame(c, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
  t(txts, 'OpenAI Realtime', { font: F.cnMd, size: 13.5, color: C.ink3, lh: 19, name: 'name' });
  t(txts, 'gpt-realtime · 手动回合', { font: F.mono, size: 10.5, color: C.ink4, lh: 14, name: 'meta' });
  const state = frame(c, 'state', { h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 5 });
  t(state, '未配置', { font: F.cn, size: 10.5, color: C.ink4, lh: 14, name: 'status' });
}), 'Speaker/Row/Provider/Off', 'B');

put(comp(page, 'Speaker/Tab/On', { h: 30, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 14, pr: 14, fill: C.clay, radius: 999 }, c => {
  t(c, '地道表达', { font: F.cnMd, size: 12, color: C.card, lh: 16, ls: 4, name: 'label' });
}), 'Speaker/Tab/On', 'B');
put(comp(page, 'Speaker/Tab/Off', { h: 30, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 14, pr: 14, fill: C.card, stroke: C.hair, radius: 999 }, c => {
  t(c, '高级表达', { font: F.cnMd, size: 12, color: C.ink3, lh: 16, ls: 4, name: 'label' });
}), 'Speaker/Tab/Off', 'B');

put(comp(page, 'Speaker/Panel/Original', { w: 320, dir: 'HORIZONTAL', cross: 'CENTER', gap: 12, pl: 14, pr: 14, pt: 12, pb: 12, fill: C.paper, radius: 14 }, c => {
  t(c, 'I get up early today. Actually I… 嗯… 没睡好, so I want a cup of hot Americano.', { font: F.cn, size: 13.5, lh: 21, color: C.ink2, width: 232, grow: 1, name: 'text' });
  const b = frame(c, 'btn play', { w: 32, h: 32, fill: C.card, stroke: C.hair, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
  icon(b, 'speaker', 16, C.clayDeep);
}), 'Speaker/Panel/Original', 'B');

put(comp(page, 'Speaker/Panel/Rewrite', { w: 320, dir: 'VERTICAL', gap: 8, pl: 15, pr: 15, pt: 13, pb: 12, fill: C.paper, radius: 14 }, c => {
  t(c, 'I got up early, but I slept badly — so a hot Americano is exactly what I need.', { font: F.serif, size: 15, lh: 23, color: C.ink, width: 290, name: 'text' });
  const acts = frame(c, 'actions', { w: 290, h: 24, dir: 'HORIZONTAL', cross: 'CENTER', gap: 16 });
  icon(acts, 'speaker', 15, C.ink4);
  icon(acts, 'translate', 15, C.ink4);
  icon(acts, 'bookmark', 15, C.ink4);
}), 'Speaker/Panel/Rewrite', 'B');

return { count: Object.keys(made).length, labels: Object.keys(made) };
