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
put(iconBtn(32, 'book', 18, { fill: C.paper, stroke: C.paper }), 'Speaker/IconButton/32/Book', 'A');
put(iconBtn(30, 'dismiss', 16, { stroke: C.paper, fill: C.paper, color: C.ink4 }), 'Speaker/IconButton/30/Dismiss', 'A');
put(iconBtn(32, 'arrowLeft', 18, { fill: C.paper, stroke: C.paper, color: C.ink2 }), 'Speaker/IconButton/32/Back', 'A');
put(iconBtn(32, 'chatAdd', 18, { fill: C.paper, stroke: C.paper, color: C.ink2 }), 'Speaker/IconButton/32/NewSession', 'A');
put(iconBtn(30, 'checkMark', 15, { fill: C.clay, stroke: C.clay, color: C.card }), 'Speaker/IconButton/30/Confirm', 'A');
put(iconBtn(30, 'speaker', 15, { fill: C.clayTint, stroke: C.clayTint, color: C.clayDeep }), 'Speaker/IconButton/30/Speaker', 'A');
put(iconBtn(30, 'edit', 15, { fill: C.paper, stroke: C.paper, color: C.ink4 }), 'Speaker/IconButton/30/Edit', 'A');

/* ---------------- indicator: 双箭头向下循环跳动动画 ---------------- */
async function makeDoubleChevronDown() {
  const c1 = figma.createComponent();
  c1.name = 'State=Rest';
  c1.resize(20, 32);
  c1.fills = [];
  page.appendChild(c1);

  function addChevrons(parent, y1, a1, y2, a2) {
    const v1 = figma.createVector();
    v1.name = 'chevTop';
    v1.vectorPaths = [{ windingRule: 'NONZERO', data: 'M 3 3 L 10 10 L 17 3' }];
    v1.strokes = [p(C.clay, 1)];
    v1.strokeWeight = 2;
    v1.strokeCap = 'ROUND';
    v1.strokeJoin = 'ROUND';
    v1.opacity = a1;
    v1.x = 0; v1.y = y1;
    parent.appendChild(v1);

    const v2 = figma.createVector();
    v2.name = 'chevBottom';
    v2.vectorPaths = [{ windingRule: 'NONZERO', data: 'M 3 3 L 10 10 L 17 3' }];
    v2.strokes = [p(C.clay, 1)];
    v2.strokeWeight = 2;
    v2.strokeCap = 'ROUND';
    v2.strokeJoin = 'ROUND';
    v2.opacity = a2;
    v2.x = 0; v2.y = y2;
    parent.appendChild(v2);
  }

  addChevrons(c1, 3, 0.35, 11, 0.85);

  const c2 = figma.createComponent();
  c2.name = 'State=Bounce';
  c2.resize(20, 32);
  c2.fills = [];
  page.appendChild(c2);

  addChevrons(c2, 9, 0.75, 17, 1.0);

  const set = figma.combineAsVariants([c1, c2], page);
  set.name = 'Speaker/Indicator/DoubleChevronDown';

  await c1.setReactionsAsync([{
    actions: [{
      type: 'NODE',
      destinationId: c2.id,
      navigation: 'CHANGE_TO',
      transition: {
        type: 'SMART_ANIMATE',
        easing: { type: 'EASE_IN_AND_OUT' },
        duration: 0.55
      }
    }],
    trigger: {
      type: 'AFTER_TIMEOUT',
      timeout: 0.6
    }
  }]);

  await c2.setReactionsAsync([{
    actions: [{
      type: 'NODE',
      destinationId: c1.id,
      navigation: 'CHANGE_TO',
      transition: {
        type: 'SMART_ANIMATE',
        easing: { type: 'EASE_IN_AND_OUT' },
        duration: 0.65
      }
    }],
    trigger: {
      type: 'AFTER_TIMEOUT',
      timeout: 0.1
    }
  }]);

  return put(set, 'Speaker/Indicator/DoubleChevronDown', 'A');
}
await makeDoubleChevronDown();



put(comp(page, 'Speaker/Button/Primary/Label', { h: 48, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 20, pr: 20, fill: C.clay, radius: 999 }, c => {
  t(c, '继续对话', { font: F.cnMd, size: 14, color: C.card, lh: 20, ls: 6, name: 'label' });
}), 'Speaker/Button/Primary/Label', 'A');

put(comp(page, 'Speaker/Button/Ghost', { h: 48, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', gap: 6, pl: 16, pr: 16, fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 999 }, c => {
  icon(c, 'bookmark', 15, C.clayDeep);
  t(c, '收进生词本', { font: F.cnMd, size: 13, color: C.ink2, lh: 18, ls: 2, name: 'label' });
}), 'Speaker/Button/Ghost', 'A');

put(comp(page, 'Speaker/Chip/Filter/On', { h: 28, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 12, pr: 12, fill: C.clay, radius: 999 }, c => {
  t(c, '全部 24', { font: F.cnMd, size: 11.5, color: C.card, lh: 16, ls: 3, name: 'label' });
}), 'Speaker/Chip/Filter/On', 'A');
put(comp(page, 'Speaker/Chip/Filter/Off', { h: 28, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 12, pr: 12, fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 999 }, c => {
  t(c, '生词 15', { font: F.cnMd, size: 11.5, color: C.ink3, lh: 16, ls: 3, name: 'label' });
}), 'Speaker/Chip/Filter/Off', 'A');
put(comp(page, 'Speaker/Chip/Tag/Olive', { h: 22, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 8, pr: 8, fill: C.oliveTint, stroke: '#DEE4CF', radius: 6 }, c => {
  t(c, '地道搭配', { font: F.cn, size: 10, color: C.olive, lh: 14, ls: 2, name: 'label' });
}), 'Speaker/Chip/Tag/Olive', 'A');
put(comp(page, 'Speaker/Chip/Tag/Neutral', { h: 22, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 8, pr: 8, fill: C.paper, stroke: C.hair, strokeA: 0.5, radius: 6 }, c => {
  t(c, '复现 3 次', { font: F.cn, size: 10, color: C.ink4, lh: 14, ls: 2, name: 'label' });
}), 'Speaker/Chip/Tag/Neutral', 'A');
put(comp(page, 'Speaker/Chip/Word', { h: 26, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 9, pr: 9, fill: C.paper, stroke: C.hair, strokeA: 0.5, radius: 8 }, c => {
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

/* 综合分角标：只放数字，不写「综合」「分」，也不带箭头。
   白纸底加一圈细边与极轻暖影，压在自己气泡右下角的底边线上，下半 8 像素落在气泡外。
   它绝对定位，不进纵向流，气泡内文字的印刷位置不动。
   数字按分数分三档着色，阈值与色值同 00 规范页的「综合分色阶」。 */
function scoreBadgeVariant(state, ink) {
  return comp(page, 'State=' + state, { h: 21, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 9, pr: 9, fill: C.card, stroke: C.hair, radius: 999 }, c => {
    c.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.1 }, offset: { x: 0, y: 1 }, radius: 4, spread: 0, blendMode: 'NORMAL', visible: true }];
    t(c, '84', { font: F.monoMd, size: 12.5, color: ink, lh: 17, name: 'score' });
  });
}
const badgeHigh = scoreBadgeVariant('High', C.olive);
const badgeMid = scoreBadgeVariant('Mid', C.clayDeep);
const badgeLow = scoreBadgeVariant('Low', C.danger);
const badgeSet = figma.combineAsVariants([badgeHigh, badgeMid, badgeLow], page);
badgeSet.name = 'Speaker/Score/Badge';
badgeSet.children.forEach((v, i) => { v.x = 0; v.y = i * 45; });
badgeSet.resizeWithoutConstraints(35, 111);
put(badgeSet, 'Speaker/Score/Badge', 'B');

put(comp(page, 'Speaker/HintCard', { w: 320, dir: 'HORIZONTAL', cross: 'CENTER', gap: 10, pl: 14, pr: 12, pt: 12, pb: 12, fill: C.oliveTint, stroke: '#DEE4CF', radius: 14 }, c => {
  icon(c, 'bulb', 14, C.olive);
  t(c, 'Actually, I didn’t sleep a wink last night, so a hot Americano is exactly what I need.', { font: F.serifIt, size: 14, lh: 21, color: C.ink2, width: 258, name: 'text' });
  icon(c, 'chevRight', 12, C.olive, 0.7);
}), 'Speaker/HintCard', 'B');

/* ---------------- 打卡热力图 ----------------
   二十六周乘七天，档位只由当日练习分钟决定：空、一至四分钟、五至十四、十五至二十九、三十分钟以上。
   卡片三百二十宽、内缩十四；左边一列十个宽的周标只标一三五，上面一行月份只在跨月且离上一条至少四列时给字，
   格 7.8 圆角 2 间距 3。右下那行读天数与总时长，点中某一格就换成那天的日期与长度，不足一分钟写秒，格上套一圈墨色细边。
   样张按一条确定性序列铺，锚点是 2026 年 3 月 11 日周三，最后三天是本周还没到的日子，留空。 */
const HEAT_FILL = [C.heat0, C.heat1, C.heat2, C.heat3, C.heat4];
const HEAT_MINUTES = [0, 3, 9, 21, 42];
const HEAT_WEEKS = 26;
const HEAT_TODAY_ROW = 2;
const HEAT_CELL = 7.85;
const HEAT_GAP = 3;
const HEAT_AXIS_W = 10;
const HEAT_AXIS_GAP = 3;
const HEAT_PITCH = HEAT_CELL + HEAT_GAP;
function heatStart() {
  const d = new Date(2026, 2, 11);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7) - (HEAT_WEEKS - 1) * 7);
  return d;
}
function heatDate(week, day) {
  const d = heatStart();
  d.setDate(d.getDate() + week * 7 + day);
  return d;
}
function heatLevel(week, day) {
  const v = (week * 7 + day * 13 + (week % 3) * 5) % 19;
  if (day > 4) return v > 13 ? 1 : 0;
  if (v < 4) return 0;
  if (v < 9) return 1;
  if (v < 13) return 2;
  if (v < 16) return 3;
  return 4;
}
function heatFuture(week, day) { return week === HEAT_WEEKS - 1 && day > HEAT_TODAY_ROW; }
function heatMonthLabels() {
  const out = [];
  let lastMonth = -1;
  let lastColumn = -99;
  for (let week = 0; week < HEAT_WEEKS; week++) {
    const month = heatDate(week, 0).getMonth();
    if (month === lastMonth) continue;
    lastMonth = month;
    if (week - lastColumn < 4) continue;
    out.push([week, (month + 1) + '月']);
    lastColumn = week;
  }
  return out;
}
function heatmapCard(suffix, picked) {
  return put(comp(page, 'Speaker/Heatmap' + suffix, { w: 320, dir: 'VERTICAL', pl: 14, pr: 14, pt: 12, pb: 12, gap: 0, fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 16 }, c => {
    const months = frame(c, 'months', { w: 292, h: 12 });
    heatMonthLabels().forEach(([week, label]) => {
      const n = t(months, label, { font: F.mono, size: 8.5, color: C.ink4, lh: 12, name: 'month ' + label });
      n.x = HEAT_AXIS_W + HEAT_AXIS_GAP + week * HEAT_PITCH;
      n.y = 0;
    });
    frame(c, 'sp month', { w: 1, h: 2 });
    const row = frame(c, 'grid row', { w: 292, dir: 'HORIZONTAL', gap: HEAT_AXIS_GAP });
    const axis = frame(row, 'weekdays', { w: HEAT_AXIS_W, h: 7 * HEAT_CELL + 6 * HEAT_GAP });
    ['一', '三', '五'].forEach((label, i) => {
      const n = t(axis, label, { font: F.mono, size: 8.5, color: C.ink4, lh: 12, name: 'weekday ' + label, align: 'RIGHT' });
      n.x = 0;
      n.y = i * 2 * HEAT_PITCH - 1;
    });
    const grid = frame(row, 'grid', { dir: 'VERTICAL', gap: HEAT_GAP });
    let days = 0;
    let minutes = 0;
    for (let day = 0; day < 7; day++) {
      const line = frame(grid, 'row ' + day, { dir: 'HORIZONTAL', gap: HEAT_GAP });
      for (let week = 0; week < HEAT_WEEKS; week++) {
        const level = heatFuture(week, day) ? 0 : heatLevel(week, day);
        if (level > 0) { days += 1; minutes += HEAT_MINUTES[level]; }
        frame(line, 'cell ' + week + '/' + day + ' L' + level, { w: HEAT_CELL, h: HEAT_CELL, fill: HEAT_FILL[level], radius: 2 });
      }
    }
    frame(c, 'sp readout', { w: 1, h: 8 });
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    const readout = picked
      ? (heatDate(picked[0], picked[1]).getMonth() + 1) + '月' + heatDate(picked[0], picked[1]).getDate() + '日 · ' + HEAT_MINUTES[heatLevel(picked[0], picked[1])] + ' 分'
      : '共 ' + days + ' 天 · ' + (hours > 0 ? hours + ' 时 ' + rest + ' 分' : rest + ' 分');
    t(c, readout, { font: F.mono, size: 10.5, color: picked ? C.ink2 : C.ink4, lh: 14, name: 'readout' });
    if (picked) {
      frame(c, 'picked ring', { w: HEAT_CELL + 2, h: HEAT_CELL + 2, radius: 3, stroke: C.ink, x: 27 + picked[0] * HEAT_PITCH, y: 26 + picked[1] * HEAT_PITCH, absolute: true });
    }
  }), 'Speaker/Heatmap' + suffix, 'B');
}
let heatPicked = null;
for (let week = HEAT_WEEKS - 1; week >= 0 && !heatPicked; week--) {
  for (let day = 6; day >= 0; day--) {
    if (heatFuture(week, day)) continue;
    if (heatLevel(week, day) === 3) { heatPicked = [week, day]; break; }
  }
}
heatmapCard('', null);
heatmapCard('/Selected', heatPicked);

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

put(comp(page, 'Speaker/Field/Search', { w: 320, h: 40, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 12, gap: 8, fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 12 }, c => {
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

function vocabVariant(suffix) {
  return comp(page, 'Speaker/VocabCard/' + suffix, { w: 320, dir: 'VERTICAL', pl: 14, pr: 14, pt: 11, pb: 12, gap: 6, fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 16 }, c => {
    const top = frame(c, 'top', { w: 292, dir: 'HORIZONTAL', cross: 'CENTER', gap: 7 });
    t(top, 'slept badly', { font: F.serifMd, size: 19, color: C.ink, lh: 25, name: 'word' });
    t(top, '/slept ˈbædli/', { font: F.mono, size: 10.5, color: C.ink4, lh: 15, name: 'phonetic' });
    frame(top, 'gap', { w: 1, h: 1, grow: 1 });
    const sp = frame(top, 'btn say', { w: 30, h: 30, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
    icon(sp, 'speaker', 15, C.ink2);
    t(c, '睡得不好。', { font: F.cn, size: 11.5, color: C.ink3, lh: 17, width: 292, name: 'definition' });
    const example = 'I got up early, but I slept badly — so a hot Americano is exactly what I need.';
    const ex = t(c, example, { font: F.serifIt, size: 13.5, color: C.ink2, lh: 21, width: 292, name: 'example' });
    const idx = example.indexOf('slept badly');
    ex.setRangeFills(idx, idx + 11, [p(C.clayDeep)]);
  });
}
put(vocabVariant('Collocation'), 'Speaker/VocabCard/Collocation', 'B');
put(vocabVariant('Word'), 'Speaker/VocabCard/Word', 'B');
put(vocabVariant('New'), 'Speaker/VocabCard/New', 'B');

put(comp(page, 'Speaker/VocabCard/SwipeOpen', { w: 320, h: 108, radius: 16, clip: true }, c => {
  const delBtn = frame(c, 'delete-action', { x: 240, y: 0, w: 80, h: 108, fill: C.danger, dir: 'VERTICAL', main: 'CENTER', cross: 'CENTER', gap: 4 });
  icon(delBtn, 'delete', 18, C.card);
  t(delBtn, '删除', { font: F.cnMd, size: 12, color: C.card, lh: 16 });

  const card = frame(c, 'card-content', { x: -80, y: 0, w: 320, h: 108, dir: 'VERTICAL', pl: 14, pr: 14, pt: 11, pb: 12, gap: 6, fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 16 });
  const top = frame(card, 'top', { w: 292, dir: 'HORIZONTAL', cross: 'CENTER', gap: 7 });
  t(top, 'crave', { font: F.serifMd, size: 19, color: C.ink, lh: 25, name: 'word' });
  t(top, '/kreɪv/', { font: F.mono, size: 10.5, color: C.ink4, lh: 15, name: 'phonetic' });
  frame(top, 'gap', { w: 1, h: 1, grow: 1 });
  const sp = frame(top, 'btn say', { w: 30, h: 30, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
  icon(sp, 'speaker', 15, C.ink2);
  t(card, '强烈渴望。', { font: F.cn, size: 11.5, color: C.ink3, lh: 17, width: 292, name: 'definition' });
  const example = 'Are you craving an Americano or something else?';
  const ex = t(card, example, { font: F.serifIt, size: 13.5, color: C.ink2, lh: 21, width: 292, name: 'example' });
  const idx = example.indexOf('craving');
  if (idx >= 0) ex.setRangeFills(idx, idx + 7, [p(C.clayDeep)]);
}), 'Speaker/VocabCard/SwipeOpen', 'B');

put(comp(page, 'Speaker/Row/Provider/Off', { w: 320, h: 54, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 14, gap: 12 }, c => {
  const radio = frame(c, 'radio', { w: 18, h: 18, fill: C.card, stroke: C.hairStrong, radius: 999 });
  const txts = frame(c, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
  t(txts, 'OpenAI Realtime', { font: F.cnMd, size: 13.5, color: C.ink3, lh: 19, name: 'name' });
  t(txts, 'gpt-realtime', { font: F.mono, size: 10.5, color: C.ink4, lh: 14, name: 'meta' });
  const state = frame(c, 'state', { h: 20, dir: 'HORIZONTAL', cross: 'CENTER', gap: 5 });
  t(state, '未配置', { font: F.cn, size: 10.5, color: C.ink4, lh: 14, name: 'status' });
}), 'Speaker/Row/Provider/Off', 'B');

function selectOption(suffix, on) {
  return comp(page, 'Speaker/Select/Option' + suffix, { w: 292, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 10, pr: 10, gap: 8, fill: on ? C.clayTint : undefined, radius: 8 }, c => {
    const col = frame(c, 'texts', { dir: 'VERTICAL', gap: 2, grow: 1 });
    t(col, on ? 'gemini-3.8-live' : 'gemini-3.1-flash-live-preview', { font: F.monoMd, size: 11.5, color: on ? C.ink : C.ink2, lh: 16, name: 'name' });
    t(col, on ? '手动回合 · 首包 450 毫秒' : '更快首包 · 预览', { font: F.cn, size: 10.5, color: C.ink4, lh: 14, name: 'note' });
    if (on) icon(c, 'checkMark', 13, C.clayDeep);
  });
}
const optOn = put(selectOption('/On', true), 'Speaker/Select/Option/On', 'A');
const optOff = put(selectOption('/Off', false), 'Speaker/Select/Option/Off', 'A');


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

/* 抽屉：检索三十起、新建会话五十二的胶囊、会话行四十四，右下角设置页四十四 */
put(iconBtn(36, 'search', 20, { fill: C.paper, stroke: C.paper, color: C.ink2 }), 'Speaker/IconButton/36/Search', 'A');
put(iconBtn(44, 'settings', 22, { fill: C.card, stroke: C.hair, color: C.ink2 }), 'Speaker/IconButton/44/Settings', 'A');

put(comp(page, 'Speaker/Button/NewSession', { w: 259, h: 52, dir: 'HORIZONTAL', cross: 'CENTER', pl: 18, pr: 18, gap: 10, fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 999 }, c => {
  icon(c, 'chatAdd', 24, C.ink);
  t(c, '新会话', { font: F.sansMd, size: 16, color: C.ink, lh: 22, name: 'label' });
}), 'Speaker/Button/NewSession', 'B');

function drawerRow(suffix, current) {
  return put(comp(page, 'Speaker/Row/DrawerSession' + suffix, { w: 259, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 10, pt: 2, pb: 2, fill: current ? C.clayTint : '', radius: 10 }, c => {
    t(c, '没睡好的早晨', { font: F.sansMd, size: 14, color: current ? C.ink : C.ink2, lh: 20, name: 'name', grow: 1 });
    if (current) {
      icon(c, 'moreHorizontal', 16, C.ink2);
    }
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

put(comp(page, 'Speaker/Toast', { h: 34, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 14, pr: 14, fill: C.tint, stroke: C.hair, strokeA: 0.5, radius: 999 }, c => {
  t(c, '已收进生词本', { font: F.cn, size: 12.5, color: C.ink2, lh: 18, name: 'label' });
}), 'Speaker/Toast', 'B');

put(comp(page, 'Speaker/Toast/Undo', { h: 36, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 16, pr: 16, gap: 12, fill: C.card, stroke: C.hair, strokeA: 0.8, radius: 999 }, c => {
  c.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.08 }, offset: { x: 0, y: 4 }, radius: 12, spread: 0, blendMode: 'NORMAL', visible: true }];
  t(c, '已删除生词', { font: F.cn, size: 12.5, color: C.ink2, lh: 18, name: 'label' });
  t(c, '撤销', { font: F.cnMd, size: 12.5, color: C.clayDeep, lh: 18, name: 'action' });
}), 'Speaker/Toast/Undo', 'B');


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

put(comp(page, 'Speaker/Tab/On', { h: 30, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 14, pr: 14, fill: C.clay, radius: 999 }, c => {
  t(c, '地道表达', { font: F.cnMd, size: 12, color: C.card, lh: 16, ls: 4, name: 'label' });
}), 'Speaker/Tab/On', 'B');
put(comp(page, 'Speaker/Tab/Off', { h: 30, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', pl: 14, pr: 14, fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 999 }, c => {
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


/* ---------------- input bar atoms（三层容器里的零件，几何按画板 110:2 与真机实测） ---------------- */
put(comp(page, 'Speaker/Mic/48', { w: 48, h: 48, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.clay, radius: 999 }, c => {
  icon(c, 'mic', 24, C.card);
}), 'Speaker/Mic/48', 'A');

put(comp(page, 'Speaker/Send/48', { w: 48, h: 48, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.ink, radius: 999 }, c => {
  icon(c, 'send', 22, C.card);
}), 'Speaker/Send/48', 'A');

put(comp(page, 'Speaker/Mic/82', { w: 82, h: 82, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.clay, radius: 999 }, c => {
  icon(c, 'mic', 34, C.card);
}), 'Speaker/Mic/82', 'A');

put(comp(page, 'Speaker/Pause/40', { w: 40, h: 40, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.card, stroke: C.hair, radius: 999 }, c => {
  icon(c, 'pause', 18, C.clay);
}), 'Speaker/Pause/40', 'A');

put(comp(page, 'Speaker/Resume/36', { w: 36, h: 36, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.card, stroke: C.hair, radius: 999 }, c => {
  icon(c, 'mic', 18, C.clay);
}), 'Speaker/Resume/36', 'A');

put(comp(page, 'Speaker/Trash/22', { w: 22, h: 22, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' }, c => {
  icon(c, 'delete', 22, C.ink3);
}), 'Speaker/Trash/22', 'A');

/* 锁条：上一枚折角箭头、下一把陶土锁，浮在圆钮顶上十六，上滑时整块收窄 */
put(comp(page, 'Speaker/LockChip', { w: 36, h: 56, dir: 'VERTICAL', cross: 'CENTER', pt: 10, gap: 6, fill: C.card, stroke: C.hair, radius: 18 }, c => {
  icon(c, 'chevUp', 12, C.ink3);
  icon(c, 'lockOpen', 16, C.clay);
}), 'Speaker/LockChip', 'A');

/* 墨色提示气泡：与锁条同中心，走过两成往上就淡掉 */
put(comp(page, 'Speaker/Tooltip', { w: 96, h: 32, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.ink, radius: 6 }, c => {
  t(c, '上滑以锁定', { font: F.cn, size: 12, color: C.card, lh: 16, name: 'label' });
}), 'Speaker/Tooltip', 'A');

/* 暂停态的时间轴：二十四的陶土播放键、三十二根柱、右端时长 */
put(comp(page, 'Speaker/Timeline/236', { w: 236, h: 32, fill: C.ink, radius: 16 }, c => {
  const play = frame(c, 'play', { w: 24, h: 24, x: 4, y: 4, fill: C.clay, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
  icon(play, 'play', 12, C.card);
  const hs = [13, 13, 19, 6, 16, 9, 6, 12, 16, 11, 17, 9, 4, 13, 11, 20, 14, 18, 16, 9, 4, 18, 11, 9, 8, 18, 4, 4, 21, 5, 16, 11];
  hs.forEach((h, i) => {
    const played = i < 9;
    const b = frame(c, 'b' + i, { w: 3, h: h, x: 36 + i * 4.6, y: 6 + (21 - h) / 2, fill: played ? C.clay : C.card, fillA: played ? 1 : 0.45, radius: 1.5 });
  });
  const d = t(c, '0:28', { font: F.sansSb, size: 11, color: C.card, colorA: 0.8, lh: 13, name: 'duration' });
  d.x = 194; d.y = 10;
}), 'Speaker/Timeline/236', 'A');

/* ---------------- message actions (below bubble) ---------------- */
function createMessageActions(compName) {
  return put(comp(page, compName, { h: 28, dir: 'HORIZONTAL', cross: 'CENTER', gap: 4 }, c => {
    const actions = [
      { name: 'speak', glyph: 'speaker', color: C.ink3 },
      { name: 'translate', glyph: 'translate', color: C.ink3 },
      { name: 'save', glyph: 'bookmark', color: C.ink3 },
      { name: 'copy', glyph: 'copy', color: C.ink3 },
      { name: 'retry', glyph: 'sync', color: C.ink3 },
      { name: 'delete', glyph: 'delete', color: C.ink4 },
    ];
    actions.forEach(a => {
      const btn = frame(c, 'btn/' + a.name, { w: 28, h: 28, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', radius: 6 });
      icon(btn, a.glyph, 15, a.color);
    });
  }), compName, 'B');
}
createMessageActions('Speaker/Actions/Message');
createMessageActions('Speaker/Menu/Message');

/* ---------------- session context menu ---------------- */
function menuRow(name, glyph, label, danger) {
  return put(comp(page, 'Speaker/Row/Menu/' + name, { w: 268, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 12, gap: 12, radius: 8 }, c => {
    icon(c, glyph, 16, danger ? C.danger : C.ink3);
    t(c, label, { font: F.cnMd, size: 13, color: danger ? C.danger : C.ink, lh: 19, grow: 1, name: 'label' });
  }), 'Speaker/Row/Menu/' + name, 'A');
}
const mrRename = menuRow('Rename', 'edit', '重命名');
const mrBatchSelect = menuRow('BatchSelect', 'checkMark', '批量选择');
const mrDeleteSession = menuRow('DeleteSession', 'delete', '删除', true);

put(comp(page, 'Speaker/Menu/Session', { w: 232, dir: 'VERTICAL', pl: 6, pr: 6, pt: 6, pb: 6, gap: 2, fill: C.card, stroke: C.hair, radius: 14 }, c => {
  c.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.08 }, offset: { x: 0, y: 8 }, radius: 22, spread: 0, blendMode: 'NORMAL', visible: true }];
  [mrRename, mrBatchSelect, mrDeleteSession].forEach((row, i) => {
    if (i === 2) { const r = frame(c, 'rule', { w: 200, h: 1, fill: C.hair }); r.layoutAlign = 'CENTER'; }
    const r = row.createInstance();
    c.appendChild(r);
    r.layoutAlign = 'STRETCH';
  });
}), 'Speaker/Menu/Session', 'B');

/* ---------------- drawer selectable rows & batch bar ---------------- */
function drawerRowSelectable(suffix, checked) {
  return put(comp(page, 'Speaker/Row/DrawerSessionSelectable' + suffix, { w: 259, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 6, pr: 12, gap: 10, radius: 10 }, c => {
    const box = frame(c, 'checkbox', { w: 18, h: 18, radius: 5, stroke: checked ? C.clay : C.hairStrong, fill: checked ? C.clay : C.card, main: 'CENTER', cross: 'CENTER' });
    if (checked) {
      icon(box, 'checkMark', 12, C.card);
    }
    t(c, '没睡好的早晨', { font: F.sansMd, size: 14, color: C.ink, lh: 20, name: 'name', grow: 1 });
  }), 'Speaker/Row/DrawerSessionSelectable' + suffix, 'B');
}
drawerRowSelectable('', false);
drawerRowSelectable('/Checked', true);

put(comp(page, 'Speaker/Bar/DrawerBatch', { w: 259, h: 44, dir: 'HORIZONTAL', main: 'SPACE_BETWEEN', cross: 'CENTER', pl: 6 }, c => {
  const left = frame(c, 'selectGroup', { dir: 'HORIZONTAL', cross: 'CENTER', gap: 8 });
  frame(left, 'checkbox', { w: 18, h: 18, radius: 5, stroke: C.hairStrong, fill: C.card, main: 'CENTER', cross: 'CENTER' });
  t(left, '全选', { font: F.sansMd, size: 14, color: C.ink, lh: 20, name: 'selectAll' });
  const btn = frame(c, 'deleteBtn', { h: 32, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, pr: 14, fill: C.danger, radius: 999 });
  t(btn, '删除 2 项', { font: F.cnMd, size: 13, color: C.card, lh: 18, name: 'label' });
}), 'Speaker/Bar/DrawerBatch', 'B');

/* ---------------- input bar ----------------
   结构照 Telegram：胶囊始终 346×44，右端 44 是按钮槽；圆钮长到 87 时覆盖在槽上并向屏幕外溢出。
   录音与锁定时右侧留 44 占位，读字区实际 302。真机实测：胶囊左右 7，红点 28 距左 13，圆钮 87，锁条 30×66，暂停 36。 */
const BAR_W = 360, BAR_H = 120, PILL_W = 288, PILL_H = 48, PILL_Y = 64, ACTION_X = 305, ACTION_Y = 64;

/* 贴底的胶囊行：宽高与位置只由容器算出，五档状态共用这一条，不被圆钮的长大挤动 */
function barShell(c, action) {
  const base = frame(c, 'Capsule_Base_Layer', { w: BAR_W, h: PILL_H, x: 0, y: PILL_Y });
  const pill = frame(base, 'pill', { w: PILL_W, h: PILL_H, x: 7, y: 0, fill: C.card, stroke: C.hair, strokeA: 0.5, radius: 24, clip: true });
  if (action) {
    const btn = page.children.find(n => n.type === 'COMPONENT' && String(n.name) === action);
    const bi = btn.createInstance(); base.appendChild(bi);
    bi.x = ACTION_X; bi.y = 0;
  }
  return pill;
}
function auraLayer(c) { return frame(c, 'Aura_Canvas_Layer', { w: BAR_W, h: BAR_H, x: 0, y: 0 }); }
function floatLayer(c) { return frame(c, 'Floating_Controls_Layer', { w: BAR_W, h: BAR_H, x: 0, y: 0 }); }
function at(node, x, y) { node.x = x; node.y = y; return node; }
function dialOf(parent, name, d, x, y, fill, glyph, gs, gcolor, fillA) {
  const n = frame(parent, name, { w: d, h: d, x: x, y: y, fill: fill, fillA: fillA, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
  if (glyph) icon(n, glyph, gs, gcolor);
  return n;
}
function ringIn(parent, name, x, y) {
  const src = page.children.find(n => n.type === 'COMPONENT' && String(n.name) === name);
  const i = src.createInstance(); parent.appendChild(i);
  i.x = x; i.y = y;
  return i;
}
/* 录音读字区：红点十在十六、计时十五号接在三十六、折角箭头一百五十六、提示一百七十四 */
function recReadout(pill, hint, opts) {
  opts = opts || {};
  dialOf(pill, 'RecordDot', 10, opts.dotX === undefined ? 16 : opts.dotX, 19, opts.danger ? C.clayDeep : C.clay);
  const clock = t(pill, '0:04,6', { font: F.sansSb, size: 15, color: opts.danger ? C.clayDeep : C.ink, lh: 18, name: 'timer' });
  clock.x = opts.timerX === undefined ? 36 : opts.timerX; clock.y = 15;
  if (opts.noHint) return;
  const chev = icon(pill, 'chevRight', 14, opts.danger ? C.clayDeep : C.ink3);
  chev.rotation = 180; chev.x = 156; chev.y = 17;
  const h = t(pill, hint, { font: F.cn, size: 14, color: opts.danger ? C.clayDeep : C.ink3, lh: 18, name: 'hint' });
  h.x = 174; h.y = 15;
  if (opts.hintAlpha !== undefined) h.opacity = opts.hintAlpha;
}
/* 锁定态的一串实时电平柱：十八根，三宽六距，高度四到二十 */
function liveBars(pill) {
  const hs = [4, 13, 20, 11, 11, 9, 4, 4, 7, 4, 13, 20, 11, 11, 9, 4, 4, 7];
  hs.forEach((h, i) => {
    frame(pill, 'bar' + i, { w: 3, h: h, x: 162 + i * 6, y: 24 - h / 2, fill: C.clay, fillA: 0.75, radius: 1.5 });
  });
}

function inputBar(name, kind) {
  return put(comp(page, 'Speaker/InputBar' + name, { w: BAR_W, h: BAR_H }, c => {
    if (kind === 'preview') {
      const pill = barShell(c, 'Speaker/Send/48');
      ringIn(pill, 'Speaker/Trash/22', 14, 13);
      ringIn(pill, 'Speaker/Timeline/236', 44, 8);
      ringIn(floatLayer(c), 'Speaker/Resume/36', 311, 20);
      return;
    }
    if (kind === 'cancel') {
      /* 左滑取消：左端换垃圾桶、计时右移四十八，进度压过四成五转陶土深色并换「松手取消」 */
      const pill = barShell(c);
      ringIn(pill, 'Speaker/Trash/22', 16, 13);
      const clock = t(pill, '0:07,8', { font: F.sansSb, size: 15, color: C.clayDeep, lh: 18, name: 'timer' });
      clock.x = 48; clock.y = 15;
      const hint = t(pill, '松手取消', { font: F.cnMd, size: 14, color: C.clayDeep, lh: 18, name: 'hint' });
      hint.x = 174; hint.y = 15; hint.opacity = 0.35;
      dialOf(auraLayer(c), 'RecordCircle_Shrunk', 57, 261, 59, C.clay, 'mic', 24, C.card);
      return;
    }
    if (kind === 'recording' || kind === 'locked') {
      const pill = barShell(c, kind === 'locked' ? 'Speaker/Send/48' : null);
      if (kind === 'locked') {
        const cancel = t(pill, '取消', { font: F.cnMd, size: 14, color: C.clayDeep, lh: 18, name: 'cancel' });
        cancel.x = 18; cancel.y = 15;
        dialOf(pill, 'RecordDot', 10, 74, 19, C.clayDeep);
        const clock = t(pill, '0:16,4', { font: F.sansSb, size: 15, color: C.ink, lh: 18, name: 'timer' });
        clock.x = 94; clock.y = 15;
        liveBars(pill);
        ringIn(floatLayer(c), 'Speaker/Pause/40', 309, 16);
        return;
      }
      recReadout(pill, '滑动以取消');
      const a = auraLayer(c);
      dialOf(a, 'bigWaveDrawable', 110, 274, 33, C.clay, null, 0, null, 0.18);
      dialOf(a, 'tinyWaveDrawable', 94, 282, 41, C.clay, null, 0, null, 0.28);
      ringIn(a, 'Speaker/Mic/82', 288, 47);
      const f = floatLayer(c);
      ringIn(f, 'Speaker/LockChip', 311, -25);
      ringIn(f, 'Speaker/Tooltip', 205, -13);
      return;
    }
    const withText = kind === 'text';
    const pill = barShell(c, withText ? 'Speaker/Send/48' : 'Speaker/Mic/48');
    const tx = t(pill, withText ? '今天想聊聊工作上的事' : '点击输入或长按说点什么', {
      font: F.cn, size: 15, color: withText ? C.ink : C.ink3, lh: 20, name: withText ? 'draft' : 'placeholder',
    });
    tx.x = 20; tx.y = 14;
  }), 'Speaker/InputBar' + name, 'A');
}
const barEmpty = inputBar('', 'empty');
const barText = inputBar('/Text', 'text');
const barRec = inputBar('/Recording', 'recording');
const barLock = inputBar('/Locked', 'locked');
const barCancel = inputBar('/Cancel', 'cancel');
const barPreview = inputBar('/Preview', 'preview');

return { count: Object.keys(made).length, labels: Object.keys(made) };
