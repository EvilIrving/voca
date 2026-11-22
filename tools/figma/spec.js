let page = figma.root.children.find(pn => pn.name === '00 · 规范');
if (!page) { page = figma.createPage(); page.name = '00 · 规范'; }
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('SPK 输入条') === 0 || n.name.indexOf('cap 输入条') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifMd, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const X = 40, W = 820;
const top = frame(page, 'SPK 输入条规范', { w: W, h: 1780, x: X, y: 3060, fill: C.card, stroke: C.hair, radius: 20 });
top.clipsContent = true;
frame(top, 'inner', { w: 1, h: 32 });
const title = t(top, '输入条规范', { font: F.serifCnMd, size: 26, color: C.ink, lh: 36 });
title.x = 32; title.y = 32;
const sub = t(top, '对齐 Telegram Android 客户端的语音条：几何取自本机实测（1080×2376、密度 3x，单位 dp），手势进度与锁定阈值取自 `ChatActivityEnterView.java`。颜色换成我们的羊皮纸体系，几何与状态机不改。', { font: F.cn, size: 12, color: C.ink3, lh: 20, width: W - 64 });
sub.x = 32; sub.y = 74;

/* ---------- 尺寸图 ---------- */
function diagRow(y, label, draw) {
  const lb = t(top, label, { font: F.monoMd, size: 11, color: C.ink4, lh: 16 });
  lb.x = 32; lb.y = y;
  const canvas = frame(top, 'diagram ' + label, { w: 360, h: 180, x: 32, y: y + 26, clip: false });
  draw(canvas);
  return y + 26 + 180 + 12;
}

function pillBox(parent, w, y) {
  return frame(parent, 'pill', { w: w, h: 44, x: 7, y: y, fill: C.card, stroke: C.hair, radius: 999, dir: 'HORIZONTAL', cross: 'CENTER', pl: 14, gap: 8 });
}
function circle(parent, d, x, y, fill, glyph, gs, gcolor) {
  const c = frame(parent, 'btn ' + d, { w: d, h: d, x: x, y: y, fill: fill, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
  if (glyph) icon(c, glyph, gs, gcolor);
  return c;
}

let y = 112;

y = diagRow(y, '① 空态 / 文字态：胶囊 346×44，圆钮 44 收在胶囊右端', c => {
  const pl = pillBox(c, 346, 100);
  t(pl, '说点什么', { font: F.cn, size: 13, color: C.ink4, lh: 19, grow: 1 });
  circle(pl, 44, 0, 0, C.clay, 'mic', 19, C.card);
});

y = diagRow(y, '② 录音：胶囊收到 302，圆钮长到 87 并从胶囊右端脱离，锁条浮在圆钮上方', c => {
  const pl = pillBox(c, 302, 100);
  pl.paddingLeft = 13;
  circle(pl, 28, 0, 0, C.clayDeep, null);
  t(pl, '0:02,4', { font: F.monoMd, size: 12, color: C.ink2, lh: 16 });
  const box = frame(pl, 'hint', { w: 264, h: 1, grow: 1 });
  t(box, '‹ 滑动以取消', { font: F.cnMd, size: 12.5, color: C.ink3, lh: 18, align: 'CENTER', width: 264 });
  circle(c, 87, 288, 78, C.clay, 'mic', 33, C.card);
  const chip = frame(c, 'lock chip', { w: 30, h: 66, x: 315, y: 4, fill: C.card, stroke: C.hair, radius: 15, dir: 'VERTICAL', cross: 'CENTER', pt: 10, pb: 8, gap: 6 });
  icon(chip, 'lockOpen', 14, C.ink2);
  icon(chip, 'chevUp', 10, C.ink4);
});

y = diagRow(y, '③ 锁定：提示换成「取消」，圆钮换纸飞机，暂停键浮在上方 36', c => {
  const pl = pillBox(c, 302, 100);
  pl.paddingLeft = 13;
  circle(pl, 28, 0, 0, C.clayDeep, null);
  t(pl, '0:02,4', { font: F.monoMd, size: 12, color: C.ink2, lh: 16 });
  const box = frame(pl, 'hint', { w: 264, h: 1, grow: 1 });
  t(box, '取消', { font: F.cnMd, size: 12.5, color: C.ink3, lh: 18, align: 'CENTER', width: 264 });
  circle(c, 87, 288, 78, C.ink, 'send', 30, C.card);
  circle(c, 36, 313, 36, C.card, 'pause', 12, C.ink2).strokes = [p(C.hair)];
});

y = diagRow(y, '④ 暂停预览：胶囊回 346，左端垃圾桶 44、波形条 252×32，右端仍是 87 发送', c => {
  const pl = pillBox(c, 346, 100);
  pl.paddingLeft = 0;
  const tr = frame(pl, 'trash', { w: 44, h: 44, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
  icon(tr, 'delete', 20, C.ink3);
  const bar = frame(pl, 'bar', { w: 252, h: 32, grow: 1, fill: C.ink, radius: 999, dir: 'HORIZONTAL', cross: 'CENTER', pl: 6, pr: 8, gap: 7 });
  const pw = frame(bar, 'play', { h: 22, dir: 'HORIZONTAL', cross: 'CENTER', pl: 6, pr: 8, gap: 3, fill: '#FFFFFF', fillA: 0.16, radius: 999 });
  icon(pw, 'play', 9, C.card);
  t(pw, '0:06', { font: F.mono, size: 9.5, color: C.card, lh: 13 });
  circle(c, 87, 288, 78, C.ink, 'send', 30, C.card);
  circle(c, 36, 313, 36, C.card, 'mic', 13, C.ink2).strokes = [p(C.hair)];
});

/* ---------- 标注 ---------- */
function note(y2, text) {
  const n = t(top, text, { font: F.mono, size: 10.5, color: C.ink3, lh: 16, width: W - 64 });
  n.x = 32; n.y = y2;
  return y2 + Math.ceil(n.height) + 6;
}
y = note(y, '间距：胶囊左右各留 7；输入文字左内边距 14；红点 28 距胶囊左 13，计时紧随；87 圆钮圆心 x=331、y=62（与 44 圆钮同心），右侧溢出屏幕 14；锁条 30×66 底边距圆钮顶 21；暂停与恢复 36，底边贴圆钮顶 21。');
y = note(y, '层级：84..112 为安全区留白；消息列表滚动到 720 为止，胶囊浮在列表之上，不遮最后一条。');

/* ---------- 状态表 ---------- */
function sectionHead(y2, text) {
  const n = t(top, text, { font: F.serifCnMd, size: 16, color: C.ink, lh: 24 });
  n.x = 32; n.y = y2;
  return y2 + 32;
}
function table(y2, headers, rows, widths) {
  let x = 32;
  headers.forEach((h, i) => {
    const n = t(top, h, { font: F.cnMd, size: 10.5, color: C.ink4, lh: 15, width: widths[i] });
    n.x = x; n.y = y2;
    x += widths[i] + 8;
  });
  let yy = y2 + 22;
  rows.forEach(row => {
    x = 32;
    row.forEach((cell, i) => {
      const n = t(top, cell, { font: i === 0 ? F.monoMd : F.cn, size: 10.5, color: i === 0 ? C.ink2 : C.ink3, lh: 16, width: widths[i] });
      n.x = x; n.y = yy;
      x += widths[i] + 8;
    });
    yy += 20;
  });
  return yy + 14;
}

y = sectionHead(y, '状态机');
y = table(y, ['状态', '胶囊', '圆钮', '附属件'], [
  ['空态', '346×44，左内 14', '44 陶土，收在胶囊内', '无'],
  ['文字', '346×44', '44 墨色纸飞机', '无'],
  ['录音', '302×44', '87 陶土，脱离胶囊右端', '红点 28、锁条 30×66'],
  ['左滑取消', '346×44，文案淡到 0', '圆钮缩到 0.7、左移到底；126 就地作废', '无'],
  ['锁定', '302×44，提示换「取消」', '87 墨色纸飞机', '暂停 36'],
  ['暂停预览', '346×44', '87 墨色纸飞机', '垃圾桶 44、波形条 252×32、恢复 36'],
], [86, 220, 240, 240]);

y = sectionHead(y, '手势阈值（ChatActivityEnterView.java，真机同一份实现）');
y = table(y, ['动作', '阈值', '说明'], [
  ['滑动距离', 'min(屏宽 × 0.35, 140)', '360 宽屏等于 126，提示与圆钮都按这条算'],
  ['取消进度', '1 + 左移 ÷ 126', '从 1 走到 0，归零当即取消，不用抬手'],
  ['抬手作废', '进度 < 0.45', 'ACTION_UP 里 alpha 低于 0.45 就 stopRecording(0)，剩下的发出'],
  ['圆钮收缩', '0.7 + 进度 × 0.3', '从 1 缩到 0.7；取消后走 0.7 × easeOut(1 − 进度) 收掉'],
  ['锁定', '纵向 57 且进度 ≥ 0.7', '左滑超过三成才拒绝锁定，避免边滑边锁'],
  ['锁定动画', '每帧 ±0.12', '锁面板与按钮同时收放，无独立时长'],
  ['滑动文字', '随进度横移并淡出', '`setSlideX(progress)`，没有额外文案'],
  ['点击 / 长按', '按住即开始', '抬起不足阈值算点击，切语音与视频模式'],
  ['电平平滑', 'current×0.9 + input×0.1', '光晕呼吸'],
], [110, 250, 420]);

y = sectionHead(y, '动效');
y = table(y, ['时刻', '动作', '参数'], [
  ['按下', '圆钮 44→87，胶囊 346→302', '约 0.10 s 一并换形，收尾带 0.7/0.3 阻尼，像水滴从条上剥离'],
  ['松手', '反向回落', '同时长；被取消时圆钮先吸回原位再回落'],
  ['录音中', '取消提示左右摆', '±6，1 s 往复'],
  ['录音中', '红点闪', '0.5 s 往复，约 1 s 一周期'],
  ['左滑', '圆钮一点点缩小，提示跟着淡出', '缩放 1→0.7 与进度同源；滑满 126 就地作废，进度掉到 0.45 以下抬手也作废'],
  ['上滑', '锁条 66→36 收窄、箭头淡出', '跟随锁进度 /57，无独立时长'],
  ['锁定', '圆钮换纸飞机', '0.3 s 弹入，旧图标缩到 0.001'],
  ['锁定', '暂停键淡入', '0.25 s，延迟 0.56 s'],
  ['暂停', '胶囊换成波形条', '0.2 s，垃圾桶与发送不动'],
  ['恢复', '波形条换回胶囊', '0.2 s，计时继续累加'],
  ['发送', '红色垃圾桶吃掉录音条', 'Lottie 1.34 s，播完即弃'],
  ['扫描删除', '蓝色垃圾桶两口', 'Lottie 1.45 s'],
  ['麦克风 / 摄像头', '图标互变', 'Lottie 0.25 s 播一次'],
], [90, 240, 450]);

y = sectionHead(y, '颜色映射');
y = table(y, ['元素', '取值', '说明'], [
  ['胶囊底', '#FFFFFF / 描边 #E8E3D6', 'Telegram 用纯填充，我们保留一条细边线'],
  ['麦克风圆钮', '#D97757', 'Telegram 是黑，我们取陶土'],
  ['发送圆钮', '#141413', '与 Telegram 一致'],
  ['红点', '#B85B3C', '录制中；取消态转 #A8503C'],
  ['计时与提示', '#3B3934 / #6C6860', '取消态一起转 #A8503C'],
  ['波形条', '#141413 底，柱 #FFFFFF', '播放进度用不透明的白'],
], [110, 300, 380]);

const last = t(top, '组件对应：Speaker/InputBar、/Text、/Recording、/Cancel、/Locked、/Preview 六个变体；圆钮 Speaker/Mic/44、Mic/87、Send/44、Send/87；附属 Speaker/LockChip、Pause/36、Resume/36、Trash/44、PreviewBar。', { font: F.cn, size: 11, color: C.ink3, lh: 19, width: W - 64 });
last.x = 32; last.y = y;
top.resize(W, Math.ceil(y + last.height + 40));
return { specH: Math.round(top.height), specY: 3060 };
