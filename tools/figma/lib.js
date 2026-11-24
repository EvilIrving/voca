// ---- shared tokens & helpers (inlined per script) ----
const C = {
  paper: '#FAF9F5', paperAlt: '#F5F2E9', card: '#FFFFFF', tint: '#F0EEE6',
  hair: '#E8E3D6', hairStrong: '#D8D2C0',
  ink: '#141413', ink2: '#3B3934', ink3: '#6C6860', ink4: '#9C978C',
  clay: '#D97757', clayDeep: '#B85B3C', clayTint: '#F7ECE5',
  olive: '#75855A', oliveTint: '#EEF1E5',
  blue: '#6A9BCC', blueTint: '#ECF2F8',
  danger: '#A8503C',
  // 打卡热力五档：空、1–4 分、5–14 分、15–29 分、30 分以上，只由当日练习分钟决定。
  heat0: '#F0EEE6', heat1: '#F8E7E1', heat2: '#F0C9BC', heat3: '#E6A792', heat4: '#D97757'
};
const F = {
  serif: { family: 'Newsreader', style: 'Regular' },
  serifIt: { family: 'Newsreader', style: 'Italic' },
  serifMd: { family: 'Newsreader', style: 'Medium' },
  serifSb: { family: 'Newsreader', style: 'SemiBold' },
  serifCn: { family: 'Noto Serif SC', style: 'Regular' },
  serifCnMd: { family: 'Noto Serif SC', style: 'Medium' },
  sans: { family: 'Inter', style: 'Regular' },
  sansMd: { family: 'Inter', style: 'Medium' },
  sansSb: { family: 'Inter', style: 'Semi Bold' },
  cn: { family: 'Noto Sans SC', style: 'Regular' },
  cnMd: { family: 'Noto Sans SC', style: 'Medium' },
  mono: { family: 'IBM Plex Mono', style: 'Regular' },
  monoMd: { family: 'IBM Plex Mono', style: 'Medium' }
};
const S = { x: 0, y: 56, w: 320 };
const p = (hex, a) => ({ type: 'SOLID', color: hx(hex), opacity: a === undefined ? 1 : a });
function hx(hex) {
  const h = hex.replace('#', '');
  return { r: parseInt(h.slice(0, 2), 16) / 255, g: parseInt(h.slice(2, 4), 16) / 255, b: parseInt(h.slice(4, 6), 16) / 255 };
}
function frame(parent, name, o) {
  const f = figma.createFrame();
  f.name = name;
  parent.appendChild(f);
  f.fills = o.fill ? [p(o.fill, o.fillA)] : [];
  f.strokes = o.stroke ? [p(o.stroke, o.strokeA)] : [];
  if (o.stroke) { f.strokeWeight = 1; f.strokeAlign = o.strokeAlign || 'INSIDE'; }
  f.clipsContent = o.clip === undefined ? false : o.clip;
  const vert = o.dir === 'VERTICAL';
  if (o.dir) {
    f.layoutMode = o.dir;
    f.primaryAxisAlignItems = o.main || 'MIN';
    f.counterAxisAlignItems = o.cross || 'MIN';
    f.itemSpacing = o.gap || 0;
    f.paddingLeft = o.pl || 0; f.paddingRight = o.pr || 0;
    f.paddingTop = o.pt || 0; f.paddingBottom = o.pb || 0;
  }
  f.resize(o.w === undefined ? 10 : o.w, o.h === undefined ? 10 : o.h);
  if (o.dir) {
    if (vert) {
      if (o.h === undefined) f.primaryAxisSizingMode = 'AUTO';
      if (o.w === undefined) f.counterAxisSizingMode = 'AUTO';
    } else {
      if (o.w === undefined) f.primaryAxisSizingMode = 'AUTO';
      if (o.h === undefined) f.counterAxisSizingMode = 'AUTO';
    }
  }
  if (o.radius) f.cornerRadius = o.radius;
  else if (o.rad) f.cornerRadius = o.rad;
  if (o.corners) {
    if (o.corners[0] !== undefined) f.topLeftRadius = o.corners[0];
    if (o.corners[1] !== undefined) f.topRightRadius = o.corners[1];
    if (o.corners[2] !== undefined) f.bottomRightRadius = o.corners[2];
    if (o.corners[3] !== undefined) f.bottomLeftRadius = o.corners[3];
  }
  if (o.grow) f.layoutGrow = 1;
  if (o.x !== undefined) { if (o.absolute && parent.layoutMode && parent.layoutMode !== 'NONE') f.layoutPositioning = 'ABSOLUTE'; f.x = o.x; f.y = o.y; }
  if (o.shadow) f.effects = [{ type: 'DROP_SHADOW', color: { ...hx('#141413'), a: o.shadow }, offset: { x: 0, y: o.shadowY || 2 }, radius: o.shadowR || 6, spread: 0, blendMode: 'NORMAL', visible: true }];
  return f;
}
function t(parent, chars, o) {
  o = o || {};
  const n = figma.createText();
  parent.appendChild(n);
  n.fontName = o.font || F.sans;
  n.fontSize = o.size || 13;
  n.lineHeight = { unit: 'PIXELS', value: o.lh || Math.round((o.size || 13) * 1.5) };
  n.letterSpacing = { unit: 'PERCENT', value: o.ls || 0 };
  n.fills = [p(o.color || C.ink, o.colorA)];
  n.textAlignHorizontal = o.align || 'LEFT';
  n.characters = chars;
  n.textAutoResize = 'WIDTH_AND_HEIGHT';
  if (o.width) {
    n.textAutoResize = 'HEIGHT';
    n.resize(o.width, n.height);
  }
  if (o.grow) n.layoutGrow = 1;
  n.name = o.name || ('txt/' + chars.slice(0, 14).replace(/\s+/g, '_'));
  return n;
}
function icon(parent, name, size, color, opacity) {
  const fl = FLUENT[name];
  if (!fl) throw new Error('missing icon ' + name);
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + size + '" height="' + size + '" viewBox="' + fl.vb + '" fill="' + color + '">' + fl.d + '</svg>';
  const n = figma.createNodeFromSvg(svg);
  parent.appendChild(n);
  n.name = 'icon/' + name;
  if (opacity !== undefined) n.opacity = opacity;
  return n;
}

/* ---------- component helpers ---------- */
function comp(page, name, o, build) {
  const c = figma.createComponent();
  c.name = name;
  page.appendChild(c);
  c.fills = [];
  c.strokes = [];
  c.clipsContent = o.clip || false;
  const vert = o.dir === 'VERTICAL';
  if (o.dir) {
    c.layoutMode = o.dir;
    c.primaryAxisAlignItems = o.main || 'MIN';
    c.counterAxisAlignItems = o.cross || 'MIN';
    c.itemSpacing = o.gap || 0;
    c.paddingLeft = o.pl || 0; c.paddingRight = o.pr || 0;
    c.paddingTop = o.pt || 0; c.paddingBottom = o.pb || 0;
  }
  c.resize(o.w === undefined ? 10 : o.w, o.h === undefined ? 10 : o.h);
  if (o.dir) {
    if (vert) {
      if (o.h === undefined) c.primaryAxisSizingMode = 'AUTO';
      if (o.w === undefined) c.counterAxisSizingMode = 'AUTO';
    } else {
      if (o.w === undefined) c.primaryAxisSizingMode = 'AUTO';
      if (o.h === undefined) c.counterAxisSizingMode = 'AUTO';
    }
  }
  if (o.fill) c.fills = [p(o.fill, o.fillA)];
  if (o.stroke) { c.strokes = [p(o.stroke, o.strokeA)]; c.strokeWeight = 1; c.strokeAlign = 'INSIDE'; }
  if (o.radius) c.cornerRadius = o.radius;
  if (o.rad) c.cornerRadius = o.rad;
  if (o.corners) { c.topLeftRadius = o.corners[0]; c.topRightRadius = o.corners[1]; c.bottomRightRadius = o.corners[2]; c.bottomLeftRadius = o.corners[3]; }
  build(c);
  return c;
}
function inst(parent, component, overrides) {
  const comp = component.type === 'COMPONENT_SET' ? component.defaultVariant : component;
  const i = comp.createInstance();
  parent.appendChild(i);
  if (overrides) {
    for (const key of Object.keys(overrides)) {
      const target = i.findOne(n => n.type === 'TEXT' && n.name === key);
      if (target) target.characters = overrides[key];
    }
  }
  return i;
}
/* 综合分角标：85 以上取 High，70 到 84 取 Mid，70 以下取 Low。 */
function scoreBadge(parent, set, value) {
  const state = value >= 85 ? 'High' : value >= 70 ? 'Mid' : 'Low';
  const variant = set.children.find(n => n.type === 'COMPONENT' && n.name === 'State=' + state) || set.defaultVariant;
  const i = variant.createInstance();
  parent.appendChild(i);
  const sc = i.findOne(n => n.type === 'TEXT' && n.name === 'score');
  if (sc) sc.characters = String(value);
  return i;
}
function cap(page, text, x, y) {
  const n = t(page, text, { font: F.mono, size: 10, color: C.ink4, lh: 14 });
  n.x = x; n.y = y;
  return n;
}
