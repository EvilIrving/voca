// 气泡生长：回复按声音进度出字，气泡只量已经出来的那截，换行的高度差走临界阻尼弹簧。
// 这一份排在 reveal 之后跑，两张规范卡并排。
let page = figma.root.children.find(pn => pn.name === '00 · 规范');
if (!page) throw new Error('missing page 00 · 规范');
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('SPK 气泡生长') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifMd, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const X = 940, Y = 1010, W = 900;
const card = frame(page, 'SPK 气泡生长规范', { w: W, h: 1500, x: X, y: Y, fill: C.paper, stroke: C.hair, radius: 20 });
card.clipsContent = true;

const title = t(card, '气泡生长', { font: F.serifMd, size: 26, color: C.ink, lh: 36 });
title.x = 40; title.y = 36;
const sub = t(card, '回复在 2 到 3 秒里出完，气泡只量已经出来的那一截；换行的高度差交给一根临界阻尼的弹簧，底边固定，上沿连续上移，新行从底边滑出来。', { font: F.cn, size: 12, color: C.ink3, lh: 20, width: W - 80 });
sub.x = 40; sub.y = 80;

// 六行样句与画板上那张流光卡同一段，这里按行切开，前缀重排后仍是同样的断行。
const lines = [
  'That sounds like a much lighter',
  'morning than the one you',
  'described last week. What made',
  'the difference — did you sleep',
  'better, or did you skip the early',
  'train?',
];
const prefix = n => lines.slice(0, n).join(' ');
const bubbleH = n => 25 + 23 * n;

function bubble(x, baseline, n, caption) {
  const h = bubbleH(n);
  const b = frame(card, 'bubble ' + n, {
    w: 258, h: h, x: x, y: baseline - h,
    fill: C.card, stroke: C.hair, corners: [5, 18, 18, 18], clip: true,
  });
  const body = t(b, prefix(n), { font: F.serif, size: 15, color: C.ink, lh: 23, width: 230 });
  body.x = 14; body.y = 13;
  const cap = t(card, caption, { font: F.mono, size: 10, color: C.ink4, lh: 14 });
  cap.x = x; cap.y = baseline + 10;
  return b;
}

const series = t(card, '回复往前放，气泡跟着长', { font: F.cn, size: 12.5, color: C.ink3, lh: 18 });
series.x = 40; series.y = 132;

const base1 = 250, base2 = 500;
bubble(40, base1, 1, '0.0s · 一行');
bubble(321, base1, 2, '0.4s · 两行');
bubble(602, base1, 3, '0.8s · 三行');
bubble(40, base2, 4, '1.2s · 四行');
bubble(321, base2, 5, '1.6s · 五行');
bubble(602, base2, 6, '2.0s · 六行');
frame(card, 'base 1', { w: 820, h: 1, x: 40, y: base1 + 2, fill: C.hair });
frame(card, 'base 2', { w: 820, h: 1, x: 40, y: base2 + 2, fill: C.hair });
const baseLabel = t(card, '底边固定', { font: F.mono, size: 9, color: C.ink4, lh: 12 });
baseLabel.x = 790; baseLabel.y = base2 + 12;

const wrapLabel = t(card, '换行那一下', { font: F.serifMd, size: 17, color: C.ink, lh: 24 });
wrapLabel.x = 40; wrapLabel.y = 560;
const wrapSub = t(card, '同一个五行的气泡，高度从四行的 117 追到 140；底边不动，上沿抬起来，新行从底边滑出。', { font: F.cn, size: 11.5, color: C.ink3, lh: 18 });
wrapSub.x = 40; wrapSub.y = 588;

function clipped(x, h, caption, fade) {
  const b = frame(card, 'clip ' + h, {
    w: 258, h: h, x: x, y: 750 - h,
    fill: C.card, stroke: C.hair, corners: [5, 18, 18, 18], clip: true,
  });
  const body = t(b, prefix(5), { font: F.serif, size: 15, color: C.ink, lh: 23, width: 230 });
  body.x = 14; body.y = 13;
  if (fade !== undefined) {
    const start = prefix(4).length + 1;
    body.setRangeFills(start, prefix(5).length, [{ type: 'SOLID', color: hx(C.ink), opacity: fade }]);
  }
  const cap = t(card, caption, { font: F.mono, size: 10, color: C.ink4, lh: 14 });
  cap.x = x; cap.y = 760;
  return b;
}
clipped(40, 117, '0 ms · 新行压在底边', 0.35);
clipped(321, 131, '90 ms · 追到一半', 0.7);
clipped(602, 140, '250 ms · 到位', 1);

// 弹簧曲线：k=290、b=24，4 毫秒一步，从 0 走到 23。
const chartLabel = t(card, '生长曲线', { font: F.serifMd, size: 17, color: C.ink, lh: 24 });
chartLabel.x = 40; chartLabel.y = 810;
const chartSub = t(card, '临界阻尼，23 的高度差在 250 毫秒里走完九成；参数取 GestureGuide 的 DEFAULT。', { font: F.cn, size: 11.5, color: C.ink3, lh: 18 });
chartSub.x = 40; chartSub.y = 838;

const plot = frame(card, 'spring', { w: 820, h: 150, x: 40, y: 866, fill: C.card, stroke: C.hair, radius: 12 });
const px0 = 64, px1 = 760, py0 = 126, py1 = 34;
frame(plot, 'axis x', { w: px1 - px0 + 10, h: 1, x: px0, y: py0, fill: C.hairStrong });
frame(plot, 'axis y', { w: 1, h: py0 - py1 + 8, x: px0, y: py1 - 8, fill: C.hairStrong });
frame(plot, 'target', { w: px1 - px0 + 10, h: 1, x: px0, y: py1, fill: C.clay, fillA: 0.25 });
const targetLabel = t(plot, '23 dp', { font: F.mono, size: 9, color: C.clayDeep, lh: 12 });
targetLabel.x = px1 + 8; targetLabel.y = py1 - 6;
for (const ms of [0, 100, 200, 300]) {
  const x = px0 + (ms / 300) * (px1 - px0);
  const lb = t(plot, ms === 0 ? '0' : ms + ' ms', { font: F.mono, size: 9, color: C.ink4, lh: 12 });
  lb.x = x - 4; lb.y = py0 + 8;
}
const stop = t(plot, '250 ms', { font: F.mono, size: 9, color: C.ink3, lh: 12 });
stop.x = px0 + (250 / 300) * (px1 - px0) - 16; stop.y = py1 - 26;
frame(plot, 'stop line', { w: 1, h: py0 - py1, x: px0 + (250 / 300) * (px1 - px0), y: py1, fill: C.hair });

let pos = 0, vel = 0;
const k = 290, b = 24, dt = 0.004, dest = 23;
for (let ms = 0; ms <= 300; ms += 4) {
  const a = -k * (pos - dest) - b * vel;
  vel += a * dt;
  pos += vel * dt;
  const x = px0 + (ms / 300) * (px1 - px0);
  const y = py0 - (pos / 23) * (py0 - py1);
  frame(plot, 'dot ' + ms, { w: 3, h: 3, x: x - 1.5, y: y - 1.5, fill: C.clay, radius: 999 });
}

const tableLabel = t(card, '参数', { font: F.serifMd, size: 17, color: C.ink, lh: 24 });
tableLabel.x = 40; tableLabel.y = 1046;

const rows = [
  ['出字速度', '每秒 100 字封顶，整段 2 到 3 秒', '声音读完要二十秒上下，字不跟声音走'],
  ['淡入', '最末一个字约 80 毫秒淡到实', '头是连续的，字不硬切'],
  ['气泡高度', '只量已经出到屏幕上的那一截', '没出来的字不占位置'],
  ['生长曲线', '临界阻尼弹簧 k=290 b=24，4 毫秒一步', '取 GestureGuide 的 DEFAULT'],
  ['换行', '底边固定，上沿连续上移，新行从底边滑出', '约 250 毫秒走完九成'],
  ['动作条', '位置不动', '长高只发生在上沿'],
  ['流光', '另算，跟播放头在读过的字上走', '与出字互不绑，见上一张卡'],
  ['打断', '停在这一刻，不补完', '取消与离开这一轮同样'],
];
let ty = 1078;
for (const r of rows) {
  const a = t(card, r[0], { font: F.monoMd, size: 11, color: C.ink4, lh: 16 });
  a.x = 40; a.y = ty + 1;
  const b2 = t(card, r[1], { font: F.cn, size: 13, color: C.ink, lh: 18, width: 330 });
  b2.x = 170; b2.y = ty;
  const c = t(card, r[2], { font: F.cn, size: 12, color: C.ink3, lh: 18, width: 400 });
  c.x = 440; c.y = ty + 1;
  frame(card, 'rule ' + r[0], { w: 820, h: 1, x: 40, y: ty + 33, fill: C.hair });
  ty += 38;
}

const note = t(card, '第一行从零长到一行也走这根弹簧，不做弹出。字按到达的速度放，整段两到三秒出完；流光按播放头在已经出来的字上走，两张卡互不绑。已经读完的一轮进历史时整段直出，不再生长。', { font: F.cn, size: 12, color: C.ink3, lh: 20, width: W - 80 });
note.x = 40; note.y = ty + 8;

card.resize(W, Math.round(note.y + note.height + 36));

return {
  createdNodeIds: [card.id, plot.id],
  card: { id: card.id, w: Math.round(card.width), h: Math.round(card.height) },
  rows: rows.length,
};
