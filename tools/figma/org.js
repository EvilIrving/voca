let page = figma.root.children.find(pn => pn.name === '01 · 组件');
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('Speaker/TopBar') === 0 || n.name.indexOf('Speaker/BottomBar') === 0 || n.name.indexOf('Speaker/RecordSheet') === 0 || n.name.indexOf('Speaker/Waveform') === 0 || n.name.indexOf('Speaker/MicButton') === 0 || n.name.indexOf('cap Speaker/(TopBar|BottomBar|RecordSheet|Waveform|MicButton)') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const byName = name => page.children.find(n => n.type === 'COMPONENT' && String(n.name) === name);
const A = { avatar: byName('Speaker/Avatar'), add44: byName('Speaker/IconButton/44/add'), end44: byName('Speaker/IconButton/44/callEnd'), gear34: byName('Speaker/IconButton/34/settings'), book32: byName('Speaker/IconButton/32/book'), close30: byName('Speaker/IconButton/30/dismiss'), spk30: byName('Speaker/IconButton/30/speaker'), talk: byName('Speaker/Button/Primary/Talk'), primary: byName('Speaker/Button/Primary/Label'), ghost: byName('Speaker/Button/Ghost') };

/* waveform */
const wave = comp(page, 'Speaker/Waveform', { w: 300, h: 76, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', gap: 5 }, c => {
  for (let i = 0; i < 34; i++) {
    const env = Math.pow(Math.sin((i / 33) * Math.PI), 1.35);
    const ripple = 0.78 + 0.22 * Math.sin(i * 2.2 + 0.4);
    const bar = frame(c, 'bar ' + i, { w: 3, h: Math.max(6, Math.round(6 + 54 * env * ripple)), fill: C.clay, radius: 2 });
    bar.opacity = 0.5 + 0.5 * env;
    bar.layoutAlign = 'CENTER';
  }
});

/* mic button */
const micBtn = comp(page, 'Speaker/MicButton', { w: 84, h: 84, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' }, c => {
  frame(c, 'halo', { w: 84, h: 84, fill: C.clay, fillA: 0.1, radius: 999, x: 0, y: 0, absolute: true });
  const b = frame(c, 'core', { w: 62, h: 62, fill: C.clay, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', x: 11, y: 11, absolute: true });
  icon(b, 'mic', 26, C.card);
});

/* top bar */
function topBar(suffix, open) {
  return comp(page, 'Speaker/TopBar' + suffix, { w: 360, h: 56, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, gap: 8 }, c => {
    const scene = frame(c, 'scene pill', { h: 34, dir: 'HORIZONTAL', cross: 'CENTER', pl: 12, pr: 10, gap: 7, fill: open ? C.clayTint : C.card, stroke: open ? C.clay : C.hair, radius: 999 });
    t(scene, '随便聊', { font: F.cnMd, size: 13, color: open ? C.clayDeep : C.ink2, lh: 18, name: 'scene' });
    icon(scene, open ? 'chevUp' : 'chevDown', 14, open ? C.clayDeep : C.ink4);
    frame(c, 'gap', { w: 1, h: 1, grow: 1 });
    const bookWrap = frame(c, 'book wrap', { w: 32, h: 32 });
    const b = A.book32.createInstance(); bookWrap.appendChild(b);
    const badge = frame(bookWrap, 'badge', { w: 16, h: 16, fill: C.clay, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
    badge.x = 18; badge.y = -2;
    t(badge, '3', { font: F.monoMd, size: 9, color: C.card, lh: 12, name: 'badge count' });
    const g = A.gear34.createInstance(); c.appendChild(g);
  });
}
const top = topBar('', false);
const topOpen = topBar('/Open', true);

/* bottom bar */
const bottom = comp(page, 'Speaker/BottomBar', { w: 360, h: 80, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 20, pt: 12, pb: 20, gap: 12, fill: C.paper }, c => {
  c.strokeTopWeight = 1; c.strokeBottomWeight = 0; c.strokeLeftWeight = 0; c.strokeRightWeight = 0;
  c.strokes = [p(C.hair)];
  const a = A.add44.createInstance(); c.appendChild(a);
  const tk = A.talk.createInstance(); c.appendChild(tk); tk.layoutGrow = 1;
  const e = A.end44.createInstance(); c.appendChild(e);
});

/* record sheet */
const sheet = comp(page, 'Speaker/RecordSheet', { w: 360, dir: 'VERTICAL', cross: 'CENTER', pt: 20, pb: 26, fill: C.card, corners: [26, 26, 0, 0], stroke: C.hair }, c => {
  c.strokeBottomWeight = 0; c.strokeLeftWeight = 0; c.strokeRightWeight = 0;
  c.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: 0.06 }, offset: { x: 0, y: -4 }, radius: 18, spread: 0, blendMode: 'NORMAL', visible: true }];
  const h = frame(c, 'handle', { w: 38, h: 4, fill: C.hairStrong, radius: 2 });
  h.layoutAlign = 'CENTER';
  frame(c, 'sp 1', { w: 1, h: 18 });
  const title = t(c, '松手发送', { font: F.serifCnMd, size: 20, color: C.ink, lh: 30, name: 'title' });
  title.layoutAlign = 'CENTER';
  frame(c, 'sp 3', { w: 1, h: 26 });
  const w = wave.createInstance(); c.appendChild(w); w.layoutAlign = 'CENTER';
  frame(c, 'sp 4', { w: 1, h: 18 });
  const m = micBtn.createInstance(); c.appendChild(m); m.layoutAlign = 'CENTER';
  frame(c, 'sp 5', { w: 1, h: 12 });
  const meta = frame(c, 'meta', { h: 20, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER', gap: 8 });
  meta.layoutAlign = 'CENTER';
  frame(meta, 'dot', { w: 5, h: 5, fill: C.clay, radius: 9 });
  t(meta, '00:06', { font: F.monoMd, size: 11, color: C.ink3, lh: 15, name: 'timer' });
});

/* lay out organisms */
const items = [
  ['Speaker/TopBar', top], ['Speaker/TopBar/Open', topOpen], ['Speaker/BottomBar', bottom], ['Speaker/RecordSheet', sheet], ['Speaker/Waveform', wave], ['Speaker/MicButton', micBtn]
];
let y = 120;
for (const [label, c] of items) {
  c.x = 800; c.y = y;
  const lb = t(page, label, { font: F.mono, size: 10, color: C.ink4, lh: 14 });
  lb.name = 'cap ' + label;
  lb.x = 800; lb.y = y - 18;
  y += c.height + 60;
}
return { sheetH: Math.round(sheet.height), topW: Math.round(top.width), waveW: Math.round(wave.width) };
