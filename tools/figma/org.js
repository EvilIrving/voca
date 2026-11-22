let page = figma.root.children.find(pn => pn.name === '01 · 组件');
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('Speaker/TopBar') === 0 || n.name.indexOf('Speaker/BottomBar') === 0 || n.name.indexOf('Speaker/RecordSheet') === 0 || n.name.indexOf('Speaker/Waveform') === 0 || n.name.indexOf('Speaker/PressButton') === 0 || n.name.indexOf('cap Speaker/') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const byName = name => page.children.find(n => n.type === 'COMPONENT' && String(n.name) === name);
const A = { avatar: byName('Speaker/Avatar'), add44: byName('Speaker/IconButton/44/add'), end44: byName('Speaker/IconButton/44/callEnd'), gear34: byName('Speaker/IconButton/34/settings'), book32: byName('Speaker/IconButton/32/book'), close30: byName('Speaker/IconButton/30/dismiss'), spk30: byName('Speaker/IconButton/30/speaker'), talk: byName('Speaker/Button/Primary/Talk'), primary: byName('Speaker/Button/Primary/Label'), ghost: byName('Speaker/Button/Ghost') };

/* waveform with a hollow capsule for the timer */
function splitBars(c, color, scale) {
  const bars = 34, slot = 300 / bars, barW = 3, mid = 38;
  const capHalfW = 42, capHalfH = 11, capFlat = 31;
  for (let i = 0; i < bars; i++) {
    const env = Math.pow(Math.sin(((i / (bars - 1)) * Math.PI)), 1.35);
    const ripple = 0.78 + 0.22 * Math.sin(i * 2.2 + 0.4);
    const h = Math.max(6, Math.round((6 + 54 * env * ripple) * scale));
    const top = mid - h / 2, bottom = mid + h / 2;
    const cx = 10 + i * slot + (slot - barW) / 2 + barW / 2;
    const dx = Math.abs(cx - 160);
    let bandHalf = 0;
    if (dx <= capHalfW) bandHalf = dx <= capFlat ? capHalfH : Math.sqrt(Math.max(0, capHalfH * capHalfH - Math.pow(dx - capFlat, 2)));
    const opacity = Math.round((0.5 + 0.5 * env) * 0.78 * 100) / 100;
    const pieces = [];
    if (bandHalf === 0) pieces.push([top, bottom]);
    else {
      if (mid - bandHalf - top >= 4) pieces.push([top, mid - bandHalf]);
      if (bottom - (mid + bandHalf) >= 4) pieces.push([mid + bandHalf, bottom]);
    }
    pieces.forEach((seg, k) => {
      const bar = frame(c, 'bar ' + i + '-' + k, { w: barW, h: Math.round(seg[1] - seg[0]), fill: color, radius: 2 });
      bar.x = Math.round(10 + i * slot + (slot - barW) / 2); bar.y = Math.round(seg[0]);
      bar.opacity = opacity;
    });
  }
}
const waveSplit = comp(page, 'Speaker/Waveform/Split', { w: 320, h: 76 }, c => splitBars(c, C.clay, 1));
const waveSplitCancel = comp(page, 'Speaker/Waveform/Split/Cancel', { w: 320, h: 76 }, c => splitBars(c, C.danger, 1));

/* press button */
const pressBtn = comp(page, 'Speaker/PressButton', { w: 62, h: 62, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.clay, radius: 999 }, c => {
  icon(c, 'mic', 26, C.card);
});
const pressBtnCancel = comp(page, 'Speaker/PressButton/Cancel', { w: 62, h: 62, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', fill: C.danger, radius: 999 }, c => {
  icon(c, 'mic', 26, C.card);
});

/* top bar */
function topBar(suffix, open, status) {
  return comp(page, 'Speaker/TopBar' + suffix, { w: 360, h: 56, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, gap: 8 }, c => {
    const scene = frame(c, 'scene pill', { h: 34, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 10, gap: 7, fill: open ? C.clayTint : C.card, stroke: open ? C.clay : C.hair, radius: 999 });
    t(scene, '随便聊', { font: F.cnMd, size: 13, color: open ? C.clayDeep : C.ink2, lh: 18, name: 'scene' });
    icon(scene, open ? 'chevUp' : 'chevDown', 14, open ? C.clayDeep : C.ink4);
    frame(c, 'gap l', { w: 1, h: 1, grow: 1 });
    if (status) t(c, status === 'offline' ? '未连接 · 重试' : '连接中', { font: F.cn, size: 11.5, color: status === 'offline' ? C.clayDeep : C.ink4, lh: 16, name: 'status' });
    frame(c, 'gap', { w: 1, h: 1, grow: 1 });
    const bookWrap = frame(c, 'book wrap', { w: 32, h: 32 });
    const b = A.book32.createInstance(); bookWrap.appendChild(b);
    const badge = frame(bookWrap, 'badge', { w: 16, h: 16, fill: C.clay, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
    badge.x = 18; badge.y = -2;
    t(badge, '3', { font: F.monoMd, size: 9, color: C.card, lh: 12, name: 'badge count' });
    const g = A.gear34.createInstance(); c.appendChild(g);
  });
}
const top = topBar('', false, null);
const topOpen = topBar('/Open', true, null);
const topOffline = topBar('/Offline', false, 'offline');
const topConnecting = topBar('/Connecting', false, 'connecting');

/* bottom bar */
const bottom = comp(page, 'Speaker/BottomBar', { w: 360, h: 80, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, pt: 12, pb: 20, gap: 12, fill: C.paper }, c => {
  c.strokeTopWeight = 1; c.strokeBottomWeight = 0; c.strokeLeftWeight = 0; c.strokeRightWeight = 0;
  c.strokes = [p(C.hair)];
  const a = A.add44.createInstance(); c.appendChild(a);
  const tk = A.talk.createInstance(); c.appendChild(tk); tk.layoutGrow = 1;
  const e = A.end44.createInstance(); c.appendChild(e);
});

/* record sheet */
function recordSheet(suffix, cancel) {
  return comp(page, 'Speaker/RecordSheet' + suffix, { w: 360, dir: 'VERTICAL', cross: 'CENTER', pt: 20, pb: 52, fill: C.card, corners: [26, 26, 0, 0], stroke: C.hair }, c => {
    c.strokeBottomWeight = 0; c.strokeLeftWeight = 0; c.strokeRightWeight = 0;
    c.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.06 }, offset: { x: 0, y: -4 }, radius: 18, spread: 0, blendMode: 'NORMAL', visible: true }];
    const h = frame(c, 'handle', { w: 38, h: 4, fill: C.hairStrong, radius: 2 });
    h.layoutAlign = 'CENTER';
    frame(c, 'sp 1', { w: 1, h: 18 });
    const title = t(c, cancel ? '松手取消' : '松手发送', { font: F.serifCnMd, size: 20, color: cancel ? C.danger : C.ink, lh: 30, name: 'title' });
    title.layoutAlign = 'CENTER';
    frame(c, 'sp 3', { w: 1, h: 20 });
    const box = frame(c, 'wave box', { w: 320, h: 76 });
    const w = (cancel ? waveSplitCancel : waveSplit).createInstance();
    box.appendChild(w); w.x = 0; w.y = 0;
    const meta = frame(box, 'timer', { h: 16, dir: 'HORIZONTAL', cross: 'CENTER', gap: 6 });
    frame(meta, 'dot', { w: 5, h: 5, fill: cancel ? C.danger : C.clay, radius: 9 });
    t(meta, '00:06', { font: F.monoMd, size: 12, color: cancel ? C.danger : C.ink2, lh: 16, name: 'timer' });
    meta.x = Math.round((320 - meta.width) / 2); meta.y = 30;
    frame(c, 'sp 4', { w: 1, h: 14 });
    const b = (cancel ? pressBtnCancel : pressBtn).createInstance(); c.appendChild(b); b.layoutAlign = 'CENTER';
  });
}
const sheet = recordSheet('', false);
const sheetCancel = recordSheet('/Cancel', true);

/* lay out organisms */
const items = [
  ['Speaker/TopBar', top], ['Speaker/TopBar/Open', topOpen], ['Speaker/TopBar/Offline', topOffline], ['Speaker/TopBar/Connecting', topConnecting], ['Speaker/BottomBar', bottom], ['Speaker/RecordSheet', sheet], ['Speaker/RecordSheet/Cancel', sheetCancel], ['Speaker/Waveform/Split', waveSplit], ['Speaker/Waveform/Split/Cancel', waveSplitCancel], ['Speaker/PressButton', pressBtn], ['Speaker/PressButton/Cancel', pressBtnCancel]
];
let y = 120;
for (const [label, c] of items) {
  c.x = 800; c.y = y;
  const lb = t(page, label, { font: F.mono, size: 10, color: C.ink4, lh: 14 });
  lb.name = 'cap ' + label;
  lb.x = 800; lb.y = y - 18;
  y += c.height + 60;
}
return { sheetH: Math.round(sheet.height), topW: Math.round(top.width), waveW: Math.round(waveSplit.width) };
