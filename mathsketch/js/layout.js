/* SketchSolve — reads a page of handwritten strokes as lines of maths.
 *
 *   strokes ─► lines (by vertical gaps)
 *           ─► fractions (a bar with ink above and below)
 *           ─► square roots (a √ with ink under its roof)
 *           ─► symbols (strokes that overlap left-to-right)
 *           ─► text such as  "2x^(2)+(1)/(3)=√(9)"
 *
 * Every symbol remembers which characters of the text it produced, so a
 * circle drawn around some strokes can be turned back into a piece of
 * the equation.
 */
(function (root, factory) {
  const api = factory(root.Recognizer || (typeof require === 'function' ? require('./recognizer.js') : null));
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.Layout = api;
})(typeof self !== 'undefined' ? self : this, function (R) {
  'use strict';

  const median = (arr) => {
    if (!arr.length) return 0;
    const s = arr.slice().sort((a, b) => a - b);
    return s[Math.floor(s.length / 2)];
  };

  function boxOf(strokes) {
    return R.bbox(strokes.flatMap((s) => s.pts));
  }

  function unionBox(boxes) {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const b of boxes) { x0 = Math.min(x0, b.x0); y0 = Math.min(y0, b.y0); x1 = Math.max(x1, b.x1); y1 = Math.max(y1, b.y1); }
    return { x0, y0, x1, y1, w: x1 - x0, h: y1 - y0, cx: (x0 + x1) / 2, cy: (y0 + y1) / 2 };
  }

  const keyOf = (strokes) => strokes.map((s) => s.id).sort((a, b) => a - b).join(',');

  const DIGITS = '0123456789';
  const BASEABLE = new Set([...DIGITS, 'x', 'y', 'a', 'b', 'c', 'n', 't', ')', 'π']);

  /**
   * strokes: [{id, pts:[[x,y]...]}]
   * opts: { overrides: {groupKey: label | '__split'}, allowed: Set|null, cache: Map }
   */
  function analyze(strokes, opts) {
    opts = opts || {};
    const items = strokes.filter((s) => s.pts && s.pts.length).map((s) => ({ s, b: R.bbox(s.pts) }));
    if (!items.length) return { lines: [], ref: 30 };
    const ref = Math.max(14, median(items.map((o) => Math.max(o.b.h, o.b.w * 0.35)).filter((h) => h > 6)) || 30);

    // --- split into lines by vertical gaps
    items.sort((a, b) => a.b.y0 - b.b.y0);
    const lineGroups = [];
    for (const o of items) {
      const cur = lineGroups[lineGroups.length - 1];
      if (cur && o.b.y0 <= cur.y1 + 0.5 * ref) { cur.items.push(o); cur.y1 = Math.max(cur.y1, o.b.y1); }
      else lineGroups.push({ items: [o], y1: o.b.y1 });
    }

    const lines = lineGroups.map((lg, li) => {
      const strokesIn = lg.items.map((o) => o.s);
      const tree = parseRegion(strokesIn, ref, opts);
      const out = { text: '', syms: [] };
      emitList(tree, out, li);
      return { index: li, text: out.text, syms: out.syms, box: boxOf(strokesIn), strokeIds: strokesIn.map((s) => s.id) };
    });
    return { lines, ref };
  }

  function isBar(s, ref) {
    const st = R.strokeStats(s.pts);
    return st.straight > 0.88 && st.ang < 20 && st.box.w > 0.35 * ref;
  }

  // Returns an array of items sorted left→right.
  function parseRegion(strokes, ref, opts) {
    let pool = strokes.slice();
    const items = [];

    // Fractions: widest bar first.
    const bars = pool.filter((s) => isBar(s, ref)).sort((a, b) => R.bbox(b.pts).w - R.bbox(a.pts).w);
    for (const bar of bars) {
      if (!pool.includes(bar)) continue;
      const bb = R.bbox(bar.pts);
      const barY = bb.cy;
      const pad = 0.12 * bb.w;
      const within = (b) => b.cx > bb.x0 - pad && b.cx < bb.x1 + pad;
      const others = pool.filter((s) => s !== bar);
      const above = others.filter((s) => { const b = R.bbox(s.pts); return within(b) && b.cy < barY && b.y1 < barY + 0.25 * ref && barY - b.y1 < 2.2 * ref; });
      const below = others.filter((s) => { const b = R.bbox(s.pts); return within(b) && b.cy > barY && b.y0 > barY - 0.25 * ref && b.y0 - barY < 2.2 * ref; });
      const solid = (arr) => arr.filter((s) => !isBar(s, ref) || R.bbox(s.pts).w < 0.7 * bb.w);
      if (!solid(above).length || !solid(below).length) continue;
      // An "=" sign is two bars of the same width — not a fraction.
      const twin = others.find((s) => isBar(s, ref) && Math.abs(R.bbox(s.pts).w - bb.w) < 0.3 * bb.w && Math.abs(R.bbox(s.pts).cy - barY) < 0.8 * ref);
      if (twin && (above.length + below.length) <= 2) continue;
      const take = new Set([bar, ...above, ...below]);
      pool = pool.filter((s) => !take.has(s));
      const all = [bar, ...above, ...below];
      items.push({ kind: 'frac', bar, num: parseRegion(above, ref, opts), den: parseRegion(below, ref, opts), box: boxOf(all), strokes: all, barBox: bb });
    }

    // Square roots: a √ stroke with ink under its roof.
    for (const s of pool.slice()) {
      if (!pool.includes(s)) continue;
      const b = R.bbox(s.pts);
      if (b.w < 0.8 * ref) continue;
      const inside = pool.filter((o) => o !== s && (() => { const ob = R.bbox(o.pts); return ob.cx > b.x0 + 0.28 * b.w && ob.cx < b.x1 + 0.1 * ref && ob.cy > b.y0 && ob.cy < b.y1 + 0.2 * ref; })());
      if (!inside.length) continue;
      const override = opts.overrides && opts.overrides[keyOf([s])];
      const cands = R.recognize([s.pts]);
      const isRoot = override ? override === '√' : cands[0].label === '√' || (cands[1] && cands[1].label === '√' && cands[0].score - cands[1].score < 0.04);
      if (!isRoot) continue;
      const take = new Set([s, ...inside]);
      pool = pool.filter((o) => !take.has(o));
      items.push({ kind: 'sqrt', stroke: s, body: parseRegion(inside, ref, opts), box: boxOf([s, ...inside]), strokes: [s, ...inside], cands });
    }

    // Ordinary symbols: group strokes that overlap left-to-right.
    pool.sort((a, b) => R.bbox(a.pts).x0 - R.bbox(b.pts).x0);
    const groups = [];
    for (const s of pool) {
      const b = R.bbox(s.pts);
      let merged = false;
      for (let k = groups.length - 1; k >= Math.max(0, groups.length - 2); k--) {
        const g = groups[k];
        const ov = Math.min(g.box.x1, b.x1) - Math.max(g.box.x0, b.x0);
        const minW = Math.max(2, Math.min(g.box.w, b.w));
        const vertOk = b.cy > g.box.y0 - 0.9 * ref && b.cy < g.box.y1 + 0.9 * ref;
        const tiny = Math.max(b.w, b.h) < 0.18 * ref || Math.max(g.box.w, g.box.h) < 0.18 * ref;
        if (ov > 0.35 * minW && vertOk && g.strokes.length < 4 && !tiny) {
          g.strokes.push(s); g.box = boxOf(g.strokes); merged = true; break;
        }
      }
      if (!merged) groups.push({ strokes: [s], box: b });
    }
    // Apply "split" corrections.
    const finalGroups = [];
    for (const g of groups) {
      if (g.strokes.length > 1 && opts.overrides && opts.overrides[keyOf(g.strokes)] === '__split') {
        for (const s of g.strokes) finalGroups.push({ strokes: [s], box: R.bbox(s.pts) });
      } else finalGroups.push(g);
    }
    for (const g of finalGroups) items.push(recogniseGroup(g, ref, opts));

    items.sort((a, b) => (a.kind === 'frac' ? a.barBox.x0 : a.box.x0) - (b.kind === 'frac' ? b.barBox.x0 : b.box.x0));
    resolveDots(items, ref);
    return items;
  }

  function recogniseGroup(g, ref, opts) {
    const key = keyOf(g.strokes);
    const size = Math.max(g.box.w, g.box.h);
    let cands;
    if (g.strokes.length === 1 && size < 0.2 * ref) {
      cands = [{ label: '.', score: 1, dot: true }, { label: '·', score: 0.9 }];
    } else {
      const cache = opts.cache;
      const ck = key + '|' + (opts.allowed ? [...opts.allowed].join('') : '');
      if (cache && cache.has(ck)) cands = cache.get(ck);
      else {
        cands = R.recognize(g.strokes.map((s) => s.pts), opts.allowed || null);
        cands = contextFix(cands, g, ref);
        if (cache) cache.set(ck, cands);
      }
    }
    let label = cands.length ? cands[0].label : '?';
    const override = opts.overrides && opts.overrides[key];
    if (override && override !== '__split') label = override;
    return { kind: 'sym', key, strokes: g.strokes, box: g.box, label, cands, overridden: !!override };
  }

  // Size-based fixes for look-alike pairs.
  function contextFix(cands, g, ref) {
    if (!cands.length) return cands;
    const top = cands[0].label;
    const tall = g.box.h > 0.95 * ref;
    const swap = (a, b) => {
      const i = cands.findIndex((c) => c.label === b);
      if (top === a && i > 0 && cands[0].score - cands[i].score < 0.08) {
        const c = cands.splice(i, 1)[0];
        cands.unshift(c);
      }
    };
    if (tall) swap('c', '('); else swap('(', 'c');
    return cands;
  }

  // A small dot is a decimal point when it sits low, a multiplication dot
  // when it sits in the middle of its neighbours.
  function resolveDots(items, ref) {
    items.forEach((it, i) => {
      if (it.kind !== 'sym' || !it.cands[0] || !it.cands[0].dot || it.overridden) return;
      const nb = [items[i - 1], items[i + 1]].filter((x) => x && !(x.kind === 'sym' && x.cands[0] && x.cands[0].dot));
      if (!nb.length) return;
      const lowHits = nb.filter((x) => it.box.cy > x.box.y0 + 0.68 * x.box.h).length;
      if (lowHits === 0) { it.label = '·'; it.cands = [{ label: '·', score: 1 }, { label: '.', score: 0.9 }]; }
    });
  }

  /* ---------------- text output ---------------- */
  function isBaseable(it) {
    if (it.kind === 'frac' || it.kind === 'sqrt') return true;
    return BASEABLE.has(it.label);
  }

  function isSuperscript(it, base) {
    if (it.kind === 'sym' && ['=', '-', '+', '·', '.'].includes(it.label) && it.kind !== 'frac') {
      // operators can't start an exponent (keeps "x - 1" sane)
      return false;
    }
    const bb = base.box, b = it.box;
    return b.y1 < bb.y0 + 0.6 * bb.h && b.h < 0.9 * bb.h && b.x0 > bb.x0 + 0.45 * bb.w;
  }

  function symText(label) {
    if (label === '·') return '*';
    return label;
  }

  function emitList(items, out, line) {
    let i = 0;
    while (i < items.length) {
      const it = items[i];
      emitItem(it, out, line);
      if (isBaseable(it)) {
        let j = i + 1;
        const sup = [];
        while (j < items.length && isSuperscript(items[j], it)) { sup.push(items[j]); j++; }
        if (sup.length) {
          out.text += '^(';
          emitList(sup, out, line);
          out.text += ')';
          i = j; continue;
        }
      }
      i++;
    }
  }

  function emitItem(it, out, line) {
    const start = out.text.length;
    if (it.kind === 'sym') {
      out.text += symText(it.label);
      out.syms.push({ key: it.key, strokeIds: it.strokes.map((s) => s.id), label: it.label, cands: it.cands, box: it.box, start, end: out.text.length, line, overridden: it.overridden });
    } else if (it.kind === 'frac') {
      const sym = { key: keyOf([it.bar]), strokeIds: [it.bar.id], label: '—', cands: [{ label: '—', score: 1 }], box: it.barBox, start, end: 0, line, role: 'fraction bar' };
      out.syms.push(sym);
      out.text += '(';
      emitList(it.num, out, line);
      out.text += ')/(';
      emitList(it.den, out, line);
      out.text += ')';
      sym.end = out.text.length;
    } else if (it.kind === 'sqrt') {
      const sym = { key: keyOf([it.stroke]), strokeIds: [it.stroke.id], label: '√', cands: it.cands, box: R.bbox(it.stroke.pts), start, end: 0, line, role: 'square root' };
      out.syms.push(sym);
      out.text += '√(';
      emitList(it.body, out, line);
      out.text += ')';
      sym.end = out.text.length;
    }
  }

  // Grow [s,e) until brackets balance, so a circled piece is valid maths.
  function balanceRange(text, s, e) {
    const bal = (a, b) => {
      let d = 0;
      for (let i = a; i < b; i++) { if (text[i] === '(') d++; else if (text[i] === ')') { d--; if (d < 0) return false; } }
      return d === 0;
    };
    for (let guard = 0; guard < 200 && !bal(s, e); guard++) {
      let d = 0, minD = 0;
      for (let i = s; i < e; i++) { if (text[i] === '(') d++; else if (text[i] === ')') { d--; minD = Math.min(minD, d); } }
      if (minD < 0 && s > 0) s--;
      else if (e < text.length) e++;
      else if (s > 0) s--;
      else break;
    }
    // Pull in a "^(...)" exponent or "/(...)" that directly follows.
    while (e < text.length && (text[e] === '^' || (text[e] === '/' && text[e + 1] === '(')) && text[e + 1] === '(') {
      let d = 0, k = e + 1;
      for (; k < text.length; k++) { if (text[k] === '(') d++; else if (text[k] === ')') { d--; if (d === 0) break; } }
      e = k + 1;
    }
    return [s, e];
  }

  return { analyze, balanceRange, keyOf };
});
