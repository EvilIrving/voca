// 这一份必须排在 tokens 之后跑：tokens 按 SPK 前缀清本页，先跑会被整块抹掉。
let page = figma.root.children.find(pn => pn.name === '00 · 规范');
if (!page) { page = figma.createPage(); page.name = '00 · 规范'; }
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('SPK 输入条') === 0 || n.name.indexOf('cap 输入条') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifMd, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const X = 40, W = 820;
const top = frame(page, 'SPK 输入条规范', { w: W, h: 1960, x: X, y: 3060, fill: C.card, stroke: C.hair, radius: 20 });
top.clipsContent = true;
frame(top, 'inner', { w: 1, h: 32 });
const title = t(top, '输入条规范', { font: F.serifCnMd, size: 26, color: C.ink, lh: 36 });
title.x = 32; title.y = 32;
const sub = t(top, '三层容器三百六乘一百二十：贴底的胶囊行在最下，双层 blob 光晕压在胶囊之上，八十二的圆钮再压光晕，锁条与提示气泡最上。几何按画板定稿与真机实测（1080×2376、密度 3x，单位 dp），手势进度、锁定行程与动效曲线取自 `ChatActivityEnterView.java`。', { font: F.cn, size: 12, color: C.ink3, lh: 20, width: W - 64 });
sub.x = 32; sub.y = 74;

/* ---------- 尺寸图 ---------- */
function diagRow(y, label, draw) {
  const lb = t(top, label, { font: F.monoMd, size: 11, color: C.ink4, lh: 16 });
  lb.x = 32; lb.y = y;
  const canvas = frame(top, 'diagram ' + label, { w: 360, h: 150, x: 32, y: y + 26, clip: false });
  draw(canvas);
  return y + 26 + 150 + 8;
}

// 胶囊行从容器第六十四行起，往上六十四给圆钮与光晕长出来，底边留八。
function pillBox(parent, o) {
  o = o || {};
  return frame(parent, 'pill', {
    w: o.w === undefined ? 288 : o.w, h: 48, x: o.x === undefined ? 7 : o.x, y: o.y === undefined ? 64 : o.y,
    fill: C.card, stroke: C.hair, radius: 24,
  });
}
function dial(parent, d, x, y, fill, glyph, gs, gcolor, fillA) {
  const c = frame(parent, 'dial ' + d, { w: d, h: d, x: x, y: y, fill: fill, fillA: fillA, radius: 999 });
  if (glyph) {
    const g = icon(c, glyph, gs, gcolor);
    g.x = (d - gs) / 2; g.y = (d - gs) / 2;
  }
  return c;
}
function bars(parent, x, y, count, w, step, min, max, color, alpha, seed) {
  let s = seed || 7;
  for (let i = 0; i < count; i++) {
    s = (s * 1103515245 + 12345) % 2147483648;
    const level = ((s >>> 16) % 100) / 100;
    const h = min + (max - min) * level;
    const b = frame(parent, 'bar ' + i, { w: w, h: h, x: x + i * step, y: y + (max - h) / 2, fill: color, fillA: alpha === undefined ? 1 : alpha, radius: 1.5 });
  }
}

let y = 112;

y = diagRow(y, '① 空态 / 文字态：胶囊 288×48 贴底，四十八的陶土圆钮收在右侧，两者间隔十', c => {
  const pl = pillBox(c);
  const ph = t(pl, '说点什么...', { font: F.cn, size: 15, color: C.ink3, lh: 20 });
  ph.x = 20; ph.y = 14;
  dial(c, 48, 305, 64, C.clay, 'mic', 24, C.card);
});

y = diagRow(y, '② 按住：圆钮一百毫秒长到八十二，外面两层 blob 随电平呼吸；锁条 36×56 与墨色气泡浮在胶囊上方十六', c => {
  const pl = pillBox(c);
  const dot = dial(pl, 10, 16, 19, C.clay);
  const clock = t(pl, '0:04,6', { font: F.sansSb, size: 15, color: C.ink, lh: 18 });
  clock.x = 36; clock.y = 15;
  const chev = icon(pl, 'chevRight', 14, C.ink3);
  chev.x = 156; chev.y = 17;
  chev.rotation = 180;
  const hint = t(pl, '滑动以取消', { font: F.cn, size: 14, color: C.ink3, lh: 18 });
  hint.x = 174; hint.y = 15;
  dial(c, 110, 274, 33, C.clay, null, 0, null, 0.18);
  dial(c, 94, 282, 41, C.clay, null, 0, null, 0.28);
  dial(c, 82, 288, 47, C.clay, 'mic', 34, C.card);
  const chip = frame(c, 'lock chip', { w: 36, h: 56, x: 311, y: 8, fill: C.card, stroke: C.hair, radius: 18 });
  const up = icon(chip, 'chevUp', 12, C.ink3); up.x = 12; up.y = 10;
  const lock = icon(chip, 'lockOpen', 16, C.clay); lock.x = 10; lock.y = 28;
  const tip = frame(c, 'tooltip', { w: 96, h: 32, x: 205, y: 20, fill: C.ink, radius: 6 });
  const tt = t(tip, '上滑以锁定', { font: F.cn, size: 12, color: C.card, lh: 16 });
  tt.x = 18; tt.y = 8;
});

y = diagRow(y, '③ 左滑取消中：左端换垃圾桶、计时右移四十八，进度压过零点四五转陶土深色并换成「松手取消」，圆钮缩到五十七跟手左移', c => {
  const pl = pillBox(c);
  const trash = icon(pl, 'delete', 22, C.clayDeep); trash.x = 16; trash.y = 13;
  const clock = t(pl, '0:07,8', { font: F.sansSb, size: 15, color: C.clayDeep, lh: 18 });
  clock.x = 48; clock.y = 15;
  const hint = t(pl, '松手取消', { font: F.cnMd, size: 14, color: C.clayDeep, lh: 18 });
  hint.x = 174; hint.y = 15;
  hint.opacity = 0.35;
  dial(c, 57, 261, 59, C.clay, 'mic', 24, C.card);
});

y = diagRow(y, '④ 锁定：可点「取消」、圆点、计时，一百六十二起十八根实时电平柱；上方四十的暂停键，底边离胶囊顶八', c => {
  const pl = pillBox(c);
  const cancel = t(pl, '取消', { font: F.cnMd, size: 14, color: C.clayDeep, lh: 18 });
  cancel.x = 18; cancel.y = 15;
  const dot = dial(pl, 10, 74, 19, C.clayDeep);
  const clock = t(pl, '0:16,4', { font: F.sansSb, size: 15, color: C.ink, lh: 18 });
  clock.x = 94; clock.y = 15;
  bars(pl, 162, 14, 18, 3, 6, 4, 20, C.clay, 0.75, 11);
  dial(c, 48, 305, 64, C.ink, 'send', 22, C.card);
  const pause = dial(c, 40, 309, 16, C.card, 'pause', 18, C.clay);
  pause.strokes = [p(C.hair)];
});

y = diagRow(y, '⑤ 暂停预览：左端二十二垃圾桶、中间 236×32 时间轴（二十四播放键与三十二根波形柱），右端仍是四十八发送，上方三十六恢复键', c => {
  const pl = pillBox(c);
  const trash = icon(pl, 'delete', 22, C.ink3); trash.x = 14; trash.y = 13;
  const tl = frame(pl, 'timeline', { w: 236, h: 32, x: 44, y: 8, fill: C.ink, radius: 16 });
  const play = dial(tl, 24, 4, 4, C.clay, 'play', 12, C.card);
  bars(tl, 36, 6, 32, 3, 4.6, 3, 21, C.clay, 1, 5);
  const dur = t(tl, '0:28', { font: F.sansSb, size: 11, color: C.card, colorA: 0.8, lh: 13 });
  dur.x = 194; dur.y = 10;
  dur.textAutoResize = 'WIDTH_AND_HEIGHT';
  dial(c, 48, 305, 64, C.ink, 'send', 22, C.card);
  const resume = dial(c, 36, 311, 20, C.card, 'mic', 18, C.clay);
  resume.strokes = [p(C.hair)];
});

/* ---------- 标注 ---------- */
function note(y2, text) {
  const n = t(top, text, { font: F.mono, size: 10.5, color: C.ink3, lh: 16, width: W - 64 });
  n.x = 32; n.y = y2;
  return y2 + Math.ceil(n.height) + 6;
}
y = note(y, '横向：条左右各留 7，胶囊 288、间隔 10、圆钮 48，三百六的屏正好铺满；圆钮圆心 x=329，长到 82 后右缘越过屏幕十。纵向：胶囊行从 64 起、高 48、底留 8；锁条 36×56 底边离八十二圆钮顶十六，提示气泡 96×32 与锁条同中心；暂停 40 与恢复 36 的底边都离胶囊顶 8。');
y = note(y, '层级：贴底胶囊行 < 双层 blob 光晕 < 八十二圆钮 < 悬浮控制层。光晕垫在胶囊底下时会被纸白胶囊切掉半边，实机上那圈呼吸只剩一条弧。');

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
  ['空态', '288×48，左内 20', '48 陶土麦克风', '无'],
  ['文字', '288×48', '48 墨色纸飞机', '无'],
  ['录音', '288×48，左内 16', '82 陶土，长到胶囊右端外侧', '红点 10、锁条 36×56、气泡 96×32'],
  ['左滑取消', '288×48，提示跟着淡', '82 缩到 57 跟手左移，126 就地作废', '垃圾桶 22、计时右移 48'],
  ['锁定', '288×48，左端「取消」', '48 墨色纸飞机', '暂停 40，延迟 560 淡入'],
  ['暂停预览', '288×48，左端垃圾桶', '48 墨色纸飞机', '时间轴 236×32、恢复 36'],
], [86, 220, 240, 240]);

y = sectionHead(y, '手势阈值（ChatActivityEnterView.java，真机同一份实现）');
y = table(y, ['动作', '阈值', '说明'], [
  ['滑动距离', 'min(屏宽 × 0.35, 140)', '360 宽屏等于 126，提示与圆钮都按这条算'],
  ['取消进度', '1 + 左移 ÷ 126', '从 1 走到 0，归零当即取消，不用抬手'],
  ['抬手作废', '进度 < 0.45', 'ACTION_UP 里 alpha 低于 0.45 就 stopRecording(0)，剩下的发出'],
  ['圆钮收缩', '0.7 + 进度 × 0.3', '82 缩到 57；取消后走 0.7 × easeOut(1 − 进度) 收掉'],
  ['锁定', '纵向 96 且进度 ≥ 0.7', 'Telegram 是 57，按住时拇指压在屏底，太容易蹭上去，这里拉长到接近两倍'],
  ['跟手阻尼', '每帧 current×0.7 + target×0.3', '图形永远落后手指一截，松手自己追上来'],
  ['点击 / 长按', '190 ms 与 400 ms', '按住不足 190 ms 算点击进草稿；两次按压间隔不足 400 ms 直接忽略'],
  ['电平平滑', 'current×0.9 + input×0.1', '光晕呼吸'],
], [110, 250, 420]);

y = sectionHead(y, '动效');
y = table(y, ['时刻', '动作', '参数'], [
  ['按下', '圆钮 48→82', '100 ms；胶囊宽高与位置一格不动'],
  ['录音中', 'blob 呼吸', '顶点数 11 与 12，缩放 0.926 / 0.878 再加 1.4×电平'],
  ['录音中', '提示原地摆动', '±3.6，500 ms 单程，未滑动时才有'],
  ['左滑', '提示跟手左移并淡出', '−宽÷4×(1−进度) + 圆钮位移×0.3，透明度乘进度'],
  ['左滑过 0.45', '「滑动以取消」换「松手取消」', '160 ms 交叉溶解，一句下沉一句上浮各 12'],
  ['上滑', '锁条收窄抬升', '始终正放、收窄一成六、跟手抬升封顶 40；气泡走过两成淡掉'],
  ['锁定', '圆钮换纸飞机', '暂停键 250 ms 淡入，延迟 560 ms'],
  ['作废', '垃圾桶吞噬', 'R.raw.chat_audio_record_delete 播一次约 0.87 s，画布 24 与图元同中心，桶体在画布里占七成宽与八成四高'],
], [90, 240, 450]);

y = sectionHead(y, '颜色映射');
y = table(y, ['元素', '取值', '说明'], [
  ['胶囊底', '#FFFFFF / 描边 #E8E3D6', '页内元素不投影，层次交给细边线'],
  ['麦克风圆钮', '#D97757', '录音态与空态同一色'],
  ['发送圆钮', '#141413', '锁定与暂停都是这一颗'],
  ['红点', '#B85B3C', '录制中；作废态转 #A8503C'],
  ['计时与提示', '#141413 / #6C6860', '作废态一起转 #B85B3C'],
  ['光晕', '#D97757 / 0.18 与 0.28', '大 110，小 94'],
  ['时间轴', '#141413 底，柱 #D97757 与白色 45%', '已播陶土、未播压到四成五'],
], [110, 300, 380]);

const last = t(top, '组件对应：Speaker/InputBar 五态（空、录音、作废、锁定、暂停）、Speaker/IconButton/48 与 /82、Speaker/LockChip、Speaker/Tooltip、Speaker/Pause/40、Speaker/Resume/36、Speaker/Timeline/236。', { font: F.cn, size: 11, color: C.ink3, lh: 19, width: W - 64 });
last.x = 32; last.y = y;
top.resize(W, Math.ceil(y + last.height + 40));
return { specH: Math.round(top.height), specY: 3060 };
