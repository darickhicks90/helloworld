/* SketchSolve — handwriting recogniser for single symbols.
 *
 * Uses the $P point-cloud recogniser (Vatavu, Anthony & Wobbrock, 2012),
 * which does not care about stroke order or direction, plus a few simple
 * geometric rules for the most common, easy-to-confuse symbols (-, =, 1,
 * +, /). The built-in templates are drawn here in code; samples the user
 * teaches the app are added on top and stored in the browser.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.Recognizer = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const N = 32;

  /* ---------------- geometry helpers ---------------- */
  const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);

  function bbox(pts) {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const p of pts) {
      const x = p.x !== undefined ? p.x : p[0], y = p.y !== undefined ? p.y : p[1];
      if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
    }
    return { x0, y0, x1, y1, w: x1 - x0, h: y1 - y0, cx: (x0 + x1) / 2, cy: (y0 + y1) / 2 };
  }

  function pathLen(pts) {
    let L = 0;
    for (let i = 1; i < pts.length; i++) L += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
    return L;
  }

  /* ---------------- $P core ---------------- */
  function toCloud(strokes) {
    const pts = [];
    strokes.forEach((s, id) => {
      if (s.length === 1) pts.push({ x: s[0][0], y: s[0][1], id }, { x: s[0][0] + 0.01, y: s[0][1] + 0.01, id });
      else for (const p of s) pts.push({ x: p[0], y: p[1], id });
    });
    return normalize(pts);
  }

  function normalize(pts) {
    let r = resample(pts, N);
    r = scale(r);
    return translateToOrigin(r);
  }

  function resample(points, n) {
    const pts = points.map((p) => ({ x: p.x, y: p.y, id: p.id }));
    let total = 0;
    for (let i = 1; i < pts.length; i++) if (pts[i].id === pts[i - 1].id) total += dist(pts[i - 1], pts[i]);
    if (total === 0) return Array.from({ length: n }, () => ({ x: pts[0].x, y: pts[0].y, id: pts[0].id }));
    const I = total / (n - 1);
    let D = 0;
    const out = [pts[0]];
    for (let i = 1; i < pts.length; i++) {
      if (pts[i].id === pts[i - 1].id) {
        const d = dist(pts[i - 1], pts[i]);
        if (D + d >= I && d > 0) {
          const t = (I - D) / d;
          const q = { x: pts[i - 1].x + t * (pts[i].x - pts[i - 1].x), y: pts[i - 1].y + t * (pts[i].y - pts[i - 1].y), id: pts[i].id };
          out.push(q);
          pts.splice(i, 0, q);
          D = 0;
        } else D += d;
      }
    }
    while (out.length < n) out.push(Object.assign({}, pts[pts.length - 1]));
    return out.slice(0, n);
  }

  function scale(pts) {
    const b = bbox(pts);
    const s = Math.max(b.w, b.h) || 1;
    return pts.map((p) => ({ x: (p.x - b.x0) / s, y: (p.y - b.y0) / s, id: p.id }));
  }

  function translateToOrigin(pts) {
    let cx = 0, cy = 0;
    for (const p of pts) { cx += p.x; cy += p.y; }
    cx /= pts.length; cy /= pts.length;
    return pts.map((p) => ({ x: p.x - cx, y: p.y - cy, id: p.id }));
  }

  function cloudDistance(a, b, start, minSoFar) {
    const n = a.length;
    const matched = new Uint8Array(n);
    let sum = 0, i = start;
    do {
      let min = Infinity, idx = -1;
      for (let j = 0; j < n; j++) {
        if (matched[j]) continue;
        const dx = a[i].x - b[j].x, dy = a[i].y - b[j].y;
        const d = dx * dx + dy * dy;
        if (d < min) { min = d; idx = j; }
      }
      matched[idx] = 1;
      const weight = 1 - ((i - start + n) % n) / n;
      sum += weight * Math.sqrt(min);
      if (sum >= minSoFar) return sum;
      i = (i + 1) % n;
    } while (i !== start);
    return sum;
  }

  function greedyMatch(a, b, minSoFar) {
    const step = Math.floor(Math.pow(N, 0.5));
    let min = minSoFar;
    for (let i = 0; i < N; i += step) {
      const d1 = cloudDistance(a, b, i, min);
      if (d1 < min) min = d1;
      const d2 = cloudDistance(b, a, i, min);
      if (d2 < min) min = d2;
    }
    return min;
  }

  /* ---------------- built-in templates ----------------
   * Coordinates: x to the right, y downwards, a full-height symbol spans
   * y = 0..1. Angles in degrees: 0 = right, 90 = down, 270 = up.        */
  const L = (x1, y1, x2, y2, n = 8) => Array.from({ length: n + 1 }, (_, i) => [x1 + (x2 - x1) * i / n, y1 + (y2 - y1) * i / n]);
  const A = (cx, cy, rx, ry, a0, a1, n) => {
    n = n || Math.max(6, Math.round(Math.abs(a1 - a0) / 12));
    return Array.from({ length: n + 1 }, (_, i) => {
      const a = (a0 + (a1 - a0) * i / n) * Math.PI / 180;
      return [cx + rx * Math.cos(a), cy + ry * Math.sin(a)];
    });
  };
  const S = (...parts) => parts.reduce((acc, p) => acc.concat(acc.length ? p.slice(1) : p), []);

  const BASE = {
    '0': [[A(.35, .5, .35, .5, -90, 270)], [A(.3, .5, .3, .5, -60, 300)]],
    '1': [[L(.1, 0, .1, 1)], [S(L(0, .25, .3, 0, 4), L(.3, 0, .3, 1))], [S(L(0, .25, .3, 0, 4), L(.3, 0, .3, 1)), L(0, 1, .6, 1)]],
    '2': [[S(A(.3, .3, .3, .3, 200, 390), L(.56, .45, 0, 1), L(0, 1, .65, 1))],
      [S(A(.32, .28, .3, .28, 190, 380), L(.6, .38, .05, 1), L(.05, 1, .7, .98))]],
    '3': [[S(A(.3, .25, .3, .25, 210, 450), A(.3, .75, .33, .25, 270, 510))],
      [S(L(0, 0, .6, 0), L(.6, 0, .25, .42), A(.3, .72, .32, .3, 270, 510))]],
    '4': [[S(L(.5, 0, 0, .65), L(0, .65, .75, .65)), L(.55, .2, .55, 1)],
      [S(L(.05, 0, 0, .6), L(0, .6, .75, .6)), L(.55, 0, .55, 1)],
      [S(L(.55, 1, .55, 0), L(.55, 0, 0, .65), L(0, .65, .75, .65))]],
    '5': [[S(L(.09, .05, .09, .45), A(.3, .7, .3, .3, 225, 500)), L(.09, .05, .6, .05)],
      [S(L(.62, 0, .1, 0), L(.1, 0, .08, .45), A(.3, .7, .3, .3, 225, 500))]],
    '6': [[S(A(.55, .6, .5, .6, 268, 180), A(.3, .72, .25, .27, 180, -170))],
      [S(L(.5, 0, .1, .55), A(.3, .72, .24, .27, 200, -150))]],
    '7': [[S(L(0, 0, .6, 0), L(.6, 0, .2, 1))], [S(L(0, 0, .6, 0), L(.6, 0, .2, 1)), L(.15, .5, .55, .5)],
      [S(L(0, .15, 0, 0, 3), L(0, 0, .6, 0), L(.6, 0, .25, 1))]],
    '8': [[S(A(.3, .25, .22, .25, 90, 450), A(.3, .73, .28, .27, 270, -90))],
      [S(A(.3, .25, .22, .25, 0, 270), A(.3, .25, .22, .25, 270, 140), L(.12, .4, .5, .62), A(.3, .74, .26, .26, 315, 45), L(.5, .9, .1, .62), L(.1, .62, .45, .4))]],
    '9': [[S(A(.3, .28, .28, .28, 360, 0), L(.58, .28, .55, 1))],
      [S(A(.3, .28, .28, .28, 360, 0), L(.58, .28, .58, .8), A(.35, .8, .23, .2, 0, 150))]],
    '+': [[L(.5, 0, .5, 1), L(0, .5, 1, .5)]],
    '-': [[L(0, .5, 1, .5)]],
    '=': [[L(0, .3, 1, .3), L(0, .7, 1, .7)]],
    '(': [[A(.6, .5, .5, .55, 245, 115)], [A(.9, .5, .8, .55, 240, 120)]],
    ')': [[A(-.1, .5, .5, .55, -65, 65)], [A(-.4, .5, .8, .55, -60, 60)]],
    '/': [[L(0, 1, .6, 0)]],
    'x': [[L(0, 0, .8, 1), L(.8, 0, 0, 1)], [A(-.05, .5, .42, .5, -70, 70), A(.85, .5, .42, .5, 250, 110)],
      [S(L(0, .1, .1, 0, 2), L(.1, 0, .8, 1)), L(.8, 0, 0, 1)]],
    'y': [[L(0, 0, .4, .5), L(.75, 0, .15, 1)],
      [S(L(0, 0, 0, .3), A(.27, .3, .27, .22, 180, 0), L(.54, .3, .54, 0, 4), L(.54, 0, .54, .85), A(.32, .85, .22, .15, 0, 160))]],
    'a': [[S(A(.3, .5, .28, .42, 300, 30), L(.54, .71, .58, .05, 5), L(.58, .05, .62, 1))],
      [A(.3, .5, .3, .42, 0, 360), L(.62, .08, .62, 1)]],
    'b': [[S(L(0, 0, 0, .72), A(.28, .72, .28, .28, 180, 540))], [L(0, 0, 0, 1), A(.28, .72, .28, .28, 180, 540)]],
    'c': [[A(.4, .5, .4, .45, 320, 40)]],
    'n': [[S(L(0, 0, 0, 1), L(0, 1, 0, .35, 4), A(.3, .35, .3, .3, 180, 360), L(.6, .35, .6, 1))]],
    't': [[L(.3, 0, .3, 1), L(0, .3, .65, .3)], [S(L(.3, 0, .3, .82), A(.5, .82, .2, .18, 180, 90)), L(0, .3, .65, .3)]],
    '√': [[S(L(0, .6, .15, .5, 3), L(.15, .5, .35, 1), L(.35, 1, .6, 0), L(.6, 0, 1.4, 0))],
      [S(L(.1, .55, .35, 1), L(.35, 1, .6, 0), L(.6, 0, 1.6, 0))]],
    'π': [[L(0, .1, 1, .08), L(.3, .1, .25, 1), L(.7, .1, .75, 1)], [S(L(0, .2, .1, .1, 2), L(.1, .1, 1, .08)), L(.3, .1, .25, 1), L(.7, .1, .75, 1)]],
    '?': [[S(A(.35, .25, .33, .25, 190, 450), L(.35, .5, .35, .72)), [[.35, .95], [.355, .96], [.35, .97]]]]
  };

  // Small distortions so the templates cover more handwriting styles.
  function augment(strokes) {
    const out = [strokes];
    const tf = (f) => strokes.map((s) => s.map(([x, y]) => f(x, y)));
    out.push(tf((x, y) => [x + 0.18 * (1 - y), y]));   // slant right
    out.push(tf((x, y) => [x - 0.12 * (1 - y), y]));   // slant left
    out.push(tf((x, y) => [x * 1.25, y]));              // wider
    out.push(tf((x, y) => [x * 0.8, y]));               // narrower
    return out;
  }

  /* ---------------- the recogniser ---------------- */
  const templates = [];

  function addTemplate(label, strokes, user) {
    if (!strokes.length) return;
    templates.push({ label, cloud: toCloud(strokes), user: !!user });
  }

  for (const [label, variants] of Object.entries(BASE)) {
    for (const v of variants) for (const a of augment(v)) addTemplate(label, a, false);
  }

  function clearUser() {
    for (let i = templates.length - 1; i >= 0; i--) if (templates[i].user) templates.splice(i, 1);
  }

  // strokes: array of arrays of [x,y]. Returns [{label, score}] best first.
  function matchCloud(strokes, allowed) {
    const cloud = toCloud(strokes);
    const best = {};
    for (const t of templates) {
      if (allowed && !allowed.has(t.label)) continue;
      const cur = best[t.label] === undefined ? Infinity : best[t.label];
      // user samples get a small bonus: they are the person's own writing
      const d = greedyMatch(cloud, t.cloud, cur / (t.user ? 0.85 : 1)) * (t.user ? 0.85 : 1);
      if (d < cur) best[t.label] = d;
    }
    return Object.entries(best)
      .map(([label, d]) => ({ label, score: Math.max(0, (2.5 - d) / 2.5) }))
      .sort((a, b) => b.score - a.score);
  }

  /* --- Simple rules for straight-line symbols. These beat template
   *     matching for the shapes that are easy to describe exactly.  --- */
  function strokeStats(s) {
    const len = pathLen(s);
    const a = s[0], b = s[s.length - 1];
    const chord = Math.hypot(b[0] - a[0], b[1] - a[1]);
    const ang = Math.atan2(Math.abs(b[1] - a[1]), Math.abs(b[0] - a[0])) * 180 / Math.PI;
    // rising = going up as x increases
    const rising = (b[0] - a[0]) * (b[1] - a[1]) < 0;
    return { len, chord, straight: len > 0 ? chord / len : 1, ang, rising, box: bbox(s) };
  }

  function segIntersect(p1, p2, p3, p4) {
    const d = (p2[0] - p1[0]) * (p4[1] - p3[1]) - (p2[1] - p1[1]) * (p4[0] - p3[0]);
    if (!d) return null;
    const t = ((p3[0] - p1[0]) * (p4[1] - p3[1]) - (p3[1] - p1[1]) * (p4[0] - p3[0])) / d;
    const u = ((p3[0] - p1[0]) * (p2[1] - p1[1]) - (p3[1] - p1[1]) * (p2[0] - p1[0])) / d;
    return { t, u };
  }

  function rules(strokes) {
    if (strokes.length === 1) {
      const st = strokeStats(strokes[0]);
      if (st.straight > 0.92) {
        if (st.ang < 22) return '-';
        if (st.ang > 68) return '1';
        if (st.rising && st.ang > 30) return '/';
      }
      return null;
    }
    if (strokes.length === 2) {
      const s1 = strokeStats(strokes[0]), s2 = strokeStats(strokes[1]);
      if (s1.straight > 0.88 && s2.straight > 0.88) {
        const h1 = s1.ang < 25, h2 = s2.ang < 25, v1 = s1.ang > 60, v2 = s2.ang > 60;
        if (h1 && h2) return '=';
        const [p1, p2] = [strokes[0][0], strokes[0][strokes[0].length - 1]];
        const [p3, p4] = [strokes[1][0], strokes[1][strokes[1].length - 1]];
        const X = segIntersect(p1, p2, p3, p4);
        const crosses = X && X.t > -0.1 && X.t < 1.1 && X.u > -0.1 && X.u < 1.1;
        if (crosses && ((h1 && v2) || (v1 && h2))) {
          const hv = h1 ? X.u : X.t; // where along the vertical stroke the bar crosses
          const vert = h1 ? strokes[1] : strokes[0];
          const goesDown = vert[vert.length - 1][1] > vert[0][1];
          const fromTop = goesDown ? hv : 1 - hv;
          return fromTop < 0.38 ? 't' : '+';
        }
        const d1 = s1.ang >= 25 && s1.ang <= 65, d2 = s2.ang >= 25 && s2.ang <= 65;
        if (crosses && d1 && d2 && s1.rising !== s2.rising && X.t > 0.2 && X.t < 0.8 && X.u > 0.2 && X.u < 0.8) return 'x';
      }
    }
    return null;
  }

  function recognize(strokes, allowed) {
    const res = matchCloud(strokes, allowed);
    const r = rules(strokes);
    if (r && (!allowed || allowed.has(r))) {
      const i = res.findIndex((c) => c.label === r);
      if (i >= 0) res.splice(i, 1);
      res.unshift({ label: r, score: 0.99, rule: true });
    }
    return res;
  }

  /* ---------------- user samples (browser storage) ---------------- */
  const KEY = 'sketchsolve.samples.v1';
  let userSamples = [];

  function loadUser() {
    try {
      const raw = typeof localStorage !== 'undefined' && localStorage.getItem(KEY);
      userSamples = raw ? JSON.parse(raw) : [];
    } catch (e) { userSamples = []; }
    clearUser();
    for (const s of userSamples) addTemplate(s.label, s.strokes, true);
  }
  function saveUser() {
    try { localStorage.setItem(KEY, JSON.stringify(userSamples)); } catch (e) { /* storage unavailable */ }
  }
  function teach(label, strokes) {
    // Keep it compact: normalise to a 0..100 box, round coordinates.
    const b = bbox(strokes.flat());
    const s = Math.max(b.w, b.h) || 1;
    const norm = strokes.map((st) => st.map(([x, y]) => [Math.round((x - b.x0) / s * 100), Math.round((y - b.y0) / s * 100)]));
    userSamples.push({ label, strokes: norm });
    if (userSamples.length > 400) userSamples.shift();
    addTemplate(label, norm, true);
    saveUser();
  }
  function forgetAll() { userSamples = []; saveUser(); clearUser(); }
  function sampleCounts() {
    const c = {};
    for (const s of userSamples) c[s.label] = (c[s.label] || 0) + 1;
    return c;
  }

  const LABELS = Object.keys(BASE);

  return { recognize, matchCloud, rules, teach, loadUser, forgetAll, sampleCounts, bbox, pathLen, strokeStats, LABELS, BASE, templates };
});
