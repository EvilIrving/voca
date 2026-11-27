// 朗读流光：声音读到哪，哪一段字被陶土色点亮，走过的字停在墨色，没读到的字保持淡色。
// 这一份排在 tokens 之后跑：tokens 按 SPK 前缀清页，先跑会被整块抹掉。
let page = figma.root.children.find(pn => pn.name === '00 · 规范');
if (!page) throw new Error('missing page 00 · 规范');
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('SPK 朗读流光') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifMd, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const X = 940, W = 900;
const card = frame(page, 'SPK 朗读流光规范', { w: W, h: 1100, x: X, y: 0, fill: C.paper, stroke: C.hair, radius: 20 });
card.clipsContent = true;

const title = t(card, '朗读流光', { font: F.serifMd, size: 26, color: C.ink, lh: 36 });
title.x = 40; title.y = 36;

const sub = t(card, '文字先以淡色铺出，声音读到哪，哪一段被一束流光点亮，读过的字停在墨色。整段里只有这束颜色在动，看起来是光推着字往前走。', { font: F.cn, size: 12, color: C.ink3, lh: 20, width: W - 80 });
sub.x = 40; sub.y = 80;

const sample = 'That sounds like a much lighter morning than the one you described last week. What made the difference — did you sleep better, or did you skip the early train?';

function blend(a, b, f) {
  const x = hx(a), y = hx(b);
  return { type: 'SOLID', color: { r: x.r + (y.r - x.r) * f, g: x.g + (y.g - x.g) * f, b: x.b + (y.b - x.b) * f } };
}

// 光带半宽 10 字，中心 0.95，按距离平方衰减；走过的字取墨色，没读到的字取四级灰。
function paintReveal(node, text, head) {
  const n = text.length;
  head = Math.max(0, Math.min(n, head));
  const half = 10, peak = 0.95;
  let start = 0, fill = null, key = '';
  for (let i = 0; i < n; i++) {
    const lift = Math.max(0, 1 - Math.pow(Math.abs(i - head) / half, 2)) * peak;
    const base = i < head ? C.ink : C.ink4;
    const next = lift > 0.01 ? blend(base, C.clay, lift) : { type: 'SOLID', color: hx(base) };
    const nextKey = JSON.stringify(next.color);
    if (fill === null) { fill = next; key = nextKey; start = i; }
    else if (nextKey !== key) {
      node.setRangeFills(start, i, [fill]);
      fill = next; key = nextKey; start = i;
    }
  }
  if (fill !== null) node.setRangeFills(start, n, [fill]);
}

function sampleBubble(x, y, progress, caption) {
  const cap = t(card, caption, { font: F.mono, size: 10, color: C.ink4, lh: 14 });
  cap.x = x; cap.y = y;
  const bubble = frame(card, 'bubble ' + Math.round(progress * 100), {
    w: 258, x: x, y: y + 18, dir: 'VERTICAL',
    pl: 14, pr: 14, pt: 13, pb: 12,
    fill: C.card, stroke: C.hair, corners: [5, 18, 18, 18],
  });
  const body = t(bubble, sample, { font: F.serif, size: 15, color: C.ink, lh: 23, width: 230 });
  paintReveal(body, sample, progress * sample.length);
  return bubble;
}

const rowY = 140;
const b1 = sampleBubble(40, rowY, 0.18, '播放 18% · 声音在第一行');
const b2 = sampleBubble(321, rowY, 0.52, '播放 52% · 走到第三行');
const b3 = sampleBubble(602, rowY, 0.86, '播放 86% · 收尾');
const rowBottom = Math.max(b1.y + b1.height, b2.y + b2.height, b3.y + b3.height);

// 光带亮度剖面：白卡上一排色块，中间最亮，向两侧平方衰减到零。
const chartLabel = t(card, '流光亮度', { font: F.serifMd, size: 17, color: C.ink, lh: 24 });
chartLabel.x = 40; chartLabel.y = rowBottom + 36;
const chartSub = t(card, '流光中心 0.95，前后各 10 字，按距离平方衰减；两端归零之后就是已读与未读的本色。', { font: F.cn, size: 11.5, color: C.ink3, lh: 18 });
chartSub.x = 40; chartSub.y = rowBottom + 64;

const plot = frame(card, 'profile', { w: 820, h: 92, x: 40, y: rowBottom + 92, fill: C.card, stroke: C.hair, radius: 12 });
const baseY = 66;
for (let d = -10; d <= 10; d++) {
  const lift = Math.max(0, 1 - Math.pow(Math.abs(d) / 10, 2)) * 0.95;
  const h = 3 + lift * 48;
  const chip = frame(plot, 'step ' + d, {
    w: 16, h: h, x: 410 + d * 19 - 8, y: baseY - h,
    fill: C.clay, fillA: Math.max(0.04, lift), radius: 3,
  });
  if (d % 10 === 0) {
    const lb = t(plot, d > 0 ? '+' + d : String(d), { font: F.mono, size: 9, color: C.ink4, lh: 12 });
    lb.x = 410 + d * 19 - (d < 0 ? 10 : 3); lb.y = 72;
  }
}

const tableLabel = t(card, '参数', { font: F.serifMd, size: 17, color: C.ink, lh: 24 });
tableLabel.x = 40; tableLabel.y = rowBottom + 212;

const rows = [
  ['进度源', '播放头 ÷ 本轮音频总帧数', '跟着实际出声的位置走，暂停就停'],
  ['文字位置', '播放比例 × 本轮字符数，向下取整', '只落在整字上，不切半个字'],
  ['流光范围', '当前位置前后各 10 字', '共 21 字，跨行照走'],
  ['流光亮度', '0.95 × (1 − (d ÷ 10)²)', '中心最亮，向两侧平方衰减'],
  ['未读的字', '#9C978C', '字可以先出来，等声音读到才变实'],
  ['已读的字', '#141413', '流光走过，停在实色'],
  ['刷新', '每帧读播放头，16 毫秒', '不另起定时器'],
  ['收尾', '播完或取消，整段回到实色', '离开这一轮同样收掉，不留半截淡字'],
];
let ty = rowBottom + 244;
for (const r of rows) {
  const a = t(card, r[0], { font: F.monoMd, size: 11, color: C.ink4, lh: 16 });
  a.x = 40; a.y = ty + 1;
  const b = t(card, r[1], { font: F.cn, size: 13, color: C.ink, lh: 18, width: 330 });
  b.x = 170; b.y = ty;
  const c = t(card, r[2], { font: F.cn, size: 12, color: C.ink3, lh: 18, width: 400 });
  c.x = 440; c.y = ty + 1;
  const line = frame(card, 'rule ' + r[0], { w: 820, h: 1, x: 40, y: ty + 33, fill: C.hair });
  ty += 38;
}

const note = t(card, '文字还在长的时候，流光不越过已经出现的那个字，停在末尾等字；新的音频到了按更新后的总长度重算，流光只往前，不倒退。没有音频的一轮，例如纯文本回复与历史消息，不画流光，整段从第一眼就是实色。流光只换字的颜色，没有底色，也没有投影，页面上不添新的色块。', { font: F.cn, size: 12, color: C.ink3, lh: 20, width: W - 80 });
note.x = 40; note.y = ty + 6;

const last = note.y + note.height + 36;
card.resize(W, Math.round(last));

return {
  createdNodeIds: [card.id, b1.id, b2.id, b3.id, plot.id],
  card: { id: card.id, w: Math.round(card.width), h: Math.round(card.height) },
  bubbles: [b1, b2, b3].map(b => ({ id: b.id, h: Math.round(b.height) })),
  sampleChars: sample.length,
};
