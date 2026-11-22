let page = figma.root.children.find(pn => pn.name === '01 · 组件');
await figma.setCurrentPageAsync(page);
// 只清本脚本重建的那几组，连同它们的说明文字；别人的组件标签一个不动。
const mine = ['Speaker/TopBar', 'Speaker/BottomBar', 'Speaker/RecordSheet', 'Speaker/Waveform', 'Speaker/PressButton'];
const owns = n => mine.some(m => n.name.indexOf(m) === 0) || mine.some(m => n.name.indexOf('cap ' + m) === 0);
page.children.filter(owns).forEach(n => n.remove());

const fonts = [F.serif, F.serifIt, F.serifMd, F.serifSb, F.serifCn, F.serifCnMd, F.sans, F.sansMd, F.sansSb, F.cn, F.cnMd, F.mono, F.monoMd];
for (const f of fonts) { try { await figma.loadFontAsync(f); } catch (e) {} }

const byName = name => page.children.find(n => n.type === 'COMPONENT' && String(n.name) === name);
const A = {
  book32: byName('Speaker/IconButton/32/book'),
  back32: byName('Speaker/IconButton/32/arrowLeft'),
  new32: byName('Speaker/IconButton/32/chatAdd'),
};

/* 顶栏：空着的新会话左上角空着；对话进行中左上角是气泡加号，点一下开新的一段； */
/* 回看换成返回箭头。名字那一小块看着不像可点，实际上点开才是抽屉，所以画板上也不加箭头。 */
function topBar(suffix, o) {
  o = o || {};
  return comp(page, 'Speaker/TopBar' + suffix, { w: 360, h: 44, dir: 'HORIZONTAL', cross: 'CENTER', pl: 20, pr: 14, gap: 6 }, c => {
    if (o.review) {
      const b = A.back32.createInstance(); c.appendChild(b);
    } else if (o.newSession) {
      const n = A.new32.createInstance(); c.appendChild(n);
    }
    const mid = frame(c, 'mid', { h: 44, dir: 'HORIZONTAL', cross: 'CENTER', gap: 3, grow: 1 });
    if (o.name) {
      const name = frame(mid, 'session', { dir: 'HORIZONTAL', cross: 'CENTER', gap: 3 });
      t(name, o.name, { font: F.serifCnMd, size: 13.5, color: C.ink2, lh: 19, name: 'session name' });
    }
    frame(c, 'gap', { w: 1, h: 1, grow: 1 });
    const bookWrap = frame(c, 'book wrap', { w: 32, h: 32 });
    const b = A.book32.createInstance(); bookWrap.appendChild(b);
    const badge = frame(bookWrap, 'badge', { w: 16, h: 16, fill: C.clay, radius: 999, dir: 'HORIZONTAL', main: 'CENTER', cross: 'CENTER' });
    badge.x = 15; badge.y = -3;
    t(badge, '3', { font: F.monoMd, size: 9, color: C.card, lh: 12, name: 'badge count' });
  });
}
const top = topBar('', {});
const topChat = topBar('/Chat', { newSession: true });
const topNamed = topBar('/Named', { newSession: true, name: '没睡好的早晨' });
const topReview = topBar('/Review', { review: true, name: '没睡好的早晨' });

/* 组织层不再单列底栏与收音面板：输入条本身就是组件，见 01 · 组件页 */
const items = [
  ['Speaker/TopBar', top], ['Speaker/TopBar/Chat', topChat], ['Speaker/TopBar/Named', topNamed], ['Speaker/TopBar/Review', topReview]
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
