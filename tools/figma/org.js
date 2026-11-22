let page = figma.root.children.find(pn => pn.name === '01 · 组件');
await figma.setCurrentPageAsync(page);
page.children.filter(n => n.name.indexOf('Speaker/TopBar') === 0 || n.name.indexOf('Speaker/BottomBar') === 0 || n.name.indexOf('Speaker/RecordSheet') === 0 || n.name.indexOf('Speaker/Waveform') === 0 || n.name.indexOf('Speaker/PressButton') === 0 || n.name.indexOf('cap Speaker/') === 0).forEach(n => n.remove());

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const byName = name => page.children.find(n => n.type === 'COMPONENT' && String(n.name) === name);
const A = { book32: byName('Speaker/IconButton/32/book'), back32: byName('Speaker/IconButton/32/arrowLeft') };

/* 顶栏：场景胶囊、状态点、只读的会话名、靠最右的生词本 */
function topBar(suffix, o) {
  o = o || {};
  const dot = o.status === 'offline' ? C.danger : o.status === 'connecting' ? C.amber : o.status === 'unconfigured' ? C.ink4 : C.olive;
  return comp(page, 'Speaker/TopBar' + suffix, { w: 360, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 14, gap: 6 }, c => {
    if (o.review) {
      const b = A.back32.createInstance(); c.appendChild(b);
    } else {
      const scene = frame(c, 'scene pill', { h: 32, dir: 'HORIZONTAL', cross: 'CENTER', pl: 11, pr: 9, gap: 5, fill: o.sceneOpen ? C.clayTint : C.card, stroke: o.sceneOpen ? C.clay : C.hair, radius: 999 });
      t(scene, '随便聊', { font: F.cnMd, size: 12, color: o.sceneOpen ? C.clayDeep : C.ink2, lh: 17, name: 'scene' });
      icon(scene, o.sceneOpen ? 'chevUp' : 'chevDown', 12, o.sceneOpen ? C.clayDeep : C.ink4);
    }
    const mid = frame(c, 'mid', { h: 44, dir: 'HORIZONTAL', cross: 'CENTER', gap: 4, grow: 1 });
    const tap = frame(mid, 'status tap', { w: 16, h: 44, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
    const d = frame(tap, 'status dot', { w: 7, h: 7, fill: dot, radius: 999 });
    d.opacity = o.status === 'connecting' ? 0.55 : 1;
    const name = frame(mid, 'session', { dir: 'HORIZONTAL', cross: 'CENTER', gap: 3 });
    t(name, o.name || '本次会话', { font: F.serifCnMd, size: 13.5, color: C.ink2, lh: 19, name: 'session name' });
    frame(c, 'gap', { w: 1, h: 1, grow: 1 });
    const bookWrap = frame(c, 'book wrap', { w: 32, h: 32 });
    const b = A.book32.createInstance(); bookWrap.appendChild(b);
    const badge = frame(bookWrap, 'badge', { w: 16, h: 16, fill: C.clay, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
    badge.x = 15; badge.y = -3;
    t(badge, '3', { font: F.monoMd, size: 9, color: C.card, lh: 12, name: 'badge count' });
  });
}
const top = topBar('', {});
const topOpen = topBar('/Open', { sceneOpen: true });
const topNamed = topBar('/Named', { name: '没睡好的早晨' });
const topOffline = topBar('/Offline', { status: 'offline' });
const topConnecting = topBar('/Connecting', { status: 'connecting' });
const topUnconfigured = topBar('/Unconfigured', { status: 'unconfigured' });
const topReview = topBar('/Review', { review: true, name: '没睡好的早晨' });

/* 组织层不再单列底栏与收音面板：输入条本身就是组件，见 01 · 组件页 */
const items = [
  ['Speaker/TopBar', top], ['Speaker/TopBar/Named', topNamed], ['Speaker/TopBar/Open', topOpen], ['Speaker/TopBar/Connecting', topConnecting], ['Speaker/TopBar/Offline', topOffline], ['Speaker/TopBar/Unconfigured', topUnconfigured], ['Speaker/TopBar/Review', topReview]
];
let y = 120;
for (const [label, c] of items) {
  c.x = 800; c.y = y;
  const lb = t(page, label, { font: F.mono, size: 10, color: C.ink4, lh: 14 });
  lb.name = 'cap ' + label;
  lb.x = 800; lb.y = y - 18;
  y += c.height + 60;
}
return { topW: Math.round(top.width), count: items.length };
