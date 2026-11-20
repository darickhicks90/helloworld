/* SketchSolve — shapes
 *
 * 1. recognise(strokes)   what shape did the person draw?
 * 2. buildModel(type,…)   the shape's parts (sides, angles, radius…), the
 *                         formulas that link them, and where each part is
 *                         on screen.
 * 3. annotate(model,…)    which part each bracket / number / circle is about.
 */
(function (root, factory) {
  const api = factory(
    root.Recognizer || (typeof require === 'function' ? require('./recognizer.js') : null),
    root.Layout || (typeof require === 'function' ? require('./layout.js') : null)
  );
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.Shapes = api;
})(typeof self !== 'undefined' ? self : this, function (R, Layout) {
  'use strict';

  /* ================= geometry helpers ================= */
  const hyp = Math.hypot;
  const d2 = (p, q) => hyp(p[0] - q[0], p[1] - q[1]);
  const mid = (p, q) => [(p[0] + q[0]) / 2, (p[1] + q[1]) / 2];
  const sub = (p, q) => [p[0] - q[0], p[1] - q[1]];
  const add = (p, q) => [p[0] + q[0], p[1] + q[1]];
  const mul = (p, k) => [p[0] * k, p[1] * k];
  const norm = (v) => { const l = hyp(v[0], v[1]) || 1; return [v[0] / l, v[1] / l]; };
  const DEG = 180 / Math.PI;
  const clamp = (x, a, b) => Math.max(a, Math.min(b, x));

  function bbox(pts) { return R.bbox(pts); }

  function resampleSp(pts, sp) {
    if (pts.length < 2) return pts.slice();
    const out = [pts[0]];
    let D = 0;
    let prev = pts[0];
    for (let i = 1; i < pts.length; i++) {
      let cur = pts[i];
      let d = d2(prev, cur);
      while (D + d >= sp && d > 0) {
        const t = (sp - D) / d;
        const q = [prev[0] + t * (cur[0] - prev[0]), prev[1] + t * (cur[1] - prev[1])];
        out.push(q);
        prev = q; d = d2(prev, cur); D = 0;
      }
      D += d; prev = cur;
    }
    if (d2(out[out.length - 1], pts[pts.length - 1]) > sp * 0.3) out.push(pts[pts.length - 1]);
    return out;
  }

  function lineDir(a, b) { return Math.atan2(b[1] - a[1], b[0] - a[0]); }
  function angDiff180(a, b) { let d = Math.abs(a - b) % Math.PI; if (d > Math.PI / 2) d = Math.PI - d; return d * DEG; }
  function distPtSeg(p, a, b) {
    const v = sub(b, a), w = sub(p, a);
    const L2 = v[0] * v[0] + v[1] * v[1] || 1;
    const t = clamp((w[0] * v[0] + w[1] * v[1]) / L2, 0, 1);
    return d2(p, add(a, mul(v, t)));
  }
  function foot(p, a, b) {
    const v = sub(b, a), w = sub(p, a);
    const L2 = v[0] * v[0] + v[1] * v[1] || 1;
    const t = (w[0] * v[0] + w[1] * v[1]) / L2;
    return add(a, mul(v, t));
  }
  function pointInPoly(p, poly) {
    let inside = false;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
      const xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1];
      if (((yi > p[1]) !== (yj > p[1])) && (p[0] < (xj - xi) * (p[1] - yi) / (yj - yi) + xi)) inside = !inside;
    }
    return inside;
  }
  function centroid(pts) {
    let x = 0, y = 0;
    for (const p of pts) { x += p[0]; y += p[1]; }
    return [x / pts.length, y / pts.length];
  }
  function interiorAngle(prev, v, next) {
    const a = norm(sub(prev, v)), b = norm(sub(next, v));
    return Math.acos(clamp(a[0] * b[0] + a[1] * b[1], -1, 1)) * DEG;
  }
  function sortAround(pts) {
    const c = centroid(pts);
    return pts.slice().sort((p, q) => Math.atan2(p[1] - c[1], p[0] - c[0]) - Math.atan2(q[1] - c[1], q[0] - c[0]));
  }

  /* ================= stroke → primitives ================= */
  function turning(P, k, cyclic) {
    const n = P.length;
    const out = new Array(n).fill(0);
    for (let i = 0; i < n; i++) {
      let ia = i - k, ic = i + k;
      if (cyclic) { ia = (ia + n) % n; ic = ic % n; } else if (ia < 0 || ic >= n) continue;
      const v1 = sub(P[i], P[ia]), v2 = sub(P[ic], P[i]);
      const l1 = hyp(v1[0], v1[1]), l2 = hyp(v2[0], v2[1]);
      if (!l1 || !l2) continue;
      out[i] = Math.acos(clamp((v1[0] * v2[0] + v1[1] * v2[1]) / (l1 * l2), -1, 1)) * DEG;
    }
    return out;
  }

  function corners(P, cyclic, thresh) {
    const n = P.length;
    const k = Math.max(2, Math.round(n / 22));
    const T = turning(P, k, cyclic);
    const idx = [];
    for (let i = 0; i < n; i++) {
      if (T[i] < thresh) continue;
      let isMax = true;
      for (let j = -k; j <= k; j++) {
        if (!j) continue;
        let q = i + j;
        if (cyclic) q = (q + n) % n; else if (q < 0 || q >= n) continue;
        if (T[q] > T[i] || (T[q] === T[i] && q < i)) { isMax = false; break; }
      }
      if (isMax) idx.push(i);
    }
    return { idx, T };
  }

  // Ellipse from the principal axes and the extents along them — robust
  // for the very flat ellipses people draw for cylinder tops.
  function fitEllipse(P) {
    const c = centroid(P);
    let sxx = 0, syy = 0, sxy = 0;
    for (const p of P) { const dx = p[0] - c[0], dy = p[1] - c[1]; sxx += dx * dx; syy += dy * dy; sxy += dx * dy; }
    const ang = 0.5 * Math.atan2(2 * sxy, sxx - syy);
    const ca = Math.cos(ang), sa = Math.sin(ang);
    let u0 = Infinity, u1 = -Infinity, v0 = Infinity, v1 = -Infinity;
    for (const p of P) {
      const dx = p[0] - c[0], dy = p[1] - c[1];
      const u = dx * ca + dy * sa, v = -dx * sa + dy * ca;
      u0 = Math.min(u0, u); u1 = Math.max(u1, u); v0 = Math.min(v0, v); v1 = Math.max(v1, v);
    }
    const a = (u1 - u0) / 2 || 1, b = (v1 - v0) / 2 || 1;
    const cu = (u0 + u1) / 2, cv = (v0 + v1) / 2;
    const cx = c[0] + cu * ca - cv * sa, cy = c[1] + cu * sa + cv * ca;
    let s = 0, s2 = 0;
    for (const p of P) {
      const dx = p[0] - cx, dy = p[1] - cy;
      const u = dx * ca + dy * sa, v = -dx * sa + dy * ca;
      const rho = Math.sqrt((u / a) ** 2 + (v / b) ** 2);
      s += rho; s2 += rho * rho;
    }
    const m = s / P.length;
    const sd = Math.sqrt(Math.max(0, s2 / P.length - m * m));
    const B = bbox(P);
    return { cx, cy, a: Math.max(a, b), b: Math.min(a, b), ratio: Math.min(a, b) / Math.max(a, b), rx: B.w / 2, ry: B.h / 2, residual: sd / (m || 1), box: B };
  }

  function decompose(stroke, diag) {
    const sb = bbox(stroke);
    const sDiag = hyp(sb.w, sb.h);
    if (sDiag < 0.04 * diag || stroke.length < 2) return [];
    const P = resampleSp(stroke, Math.max(sDiag / 60, 1.5));
    if (P.length < 4) return [{ kind: 'seg', a: stroke[0], b: stroke[stroke.length - 1], len: d2(stroke[0], stroke[stroke.length - 1]) }];
    const len = R.pathLen(P);
    const chord = d2(P[0], P[P.length - 1]);
    const closed = chord < Math.max(0.22 * sDiag, 10) && len > 1.6 * sDiag;

    if (closed) {
      // drop the overshoot so the loop is a clean cycle
      let Q = P;
      let best = Q.length - 1, bestD = Infinity;
      for (let i = Math.floor(Q.length * 0.7); i < Q.length; i++) { const dd = d2(Q[i], Q[0]); if (dd < bestD) { bestD = dd; best = i; } }
      Q = Q.slice(0, best + 1);
      if (d2(Q[0], Q[Q.length - 1]) < 1e-6) Q = Q.slice(0, -1);
      const { idx } = corners(Q, true, 42);
      const ell = fitEllipse(Q);
      if (idx.length >= 3) {
        return [{ kind: 'poly', verts: idx.map((i) => Q[i]), closed: true }];
      }
      if (ell.residual < 0.2) return [Object.assign({ kind: 'ellipse', pts: Q }, ell)];
      if (idx.length >= 3) return [{ kind: 'poly', verts: idx.map((i) => Q[i]), closed: true }];
      return [Object.assign({ kind: 'ellipse', pts: Q }, ell)];
    }

    if (chord / len > 0.94) return [{ kind: 'seg', a: P[0], b: P[P.length - 1], len: chord }];
    const { idx } = corners(P, false, 42);
    const cuts = [0, ...idx, P.length - 1];
    const out = [];
    for (let i = 1; i < cuts.length; i++) {
      const piece = P.slice(cuts[i - 1], cuts[i] + 1);
      if (piece.length < 2) continue;
      const pl = R.pathLen(piece), pc = d2(piece[0], piece[piece.length - 1]);
      if (pl < 0.03 * diag) continue;
      if (pc / pl > 0.9) out.push({ kind: 'seg', a: piece[0], b: piece[piece.length - 1], len: pc });
      else out.push({ kind: 'arc', pts: piece, box: bbox(piece), len: pl });
    }
    return out;
  }

  /* ================= endpoint graph ================= */
  function buildGraph(segs, tol) {
    const nodes = [];
    const nodeFor = (p) => {
      let bi = -1, bd = Infinity;
      nodes.forEach((n, i) => { const dd = d2(n.p, p); if (dd < bd) { bd = dd; bi = i; } });
      if (bi >= 0 && bd < tol) {
        const n = nodes[bi];
        n.p = [(n.p[0] * n.k + p[0]) / (n.k + 1), (n.p[1] * n.k + p[1]) / (n.k + 1)]; n.k++;
        return bi;
      }
      nodes.push({ p: p.slice(), k: 1 });
      return nodes.length - 1;
    };
    const edges = [];
    const ekeys = new Set();
    for (const s of segs) {
      const i = nodeFor(s.a), j = nodeFor(s.b);
      if (i === j) continue;
      const key = i < j ? `${i}-${j}` : `${j}-${i}`;
      if (ekeys.has(key)) continue;
      ekeys.add(key);
      edges.push([i, j]);
    }
    const adj = nodes.map(() => []);
    for (const [i, j] of edges) { adj[i].push(j); adj[j].push(i); }
    let cycle = null;
    if (nodes.length >= 3 && edges.length === nodes.length && adj.every((a) => a.length === 2)) {
      const order = [0];
      let prev = -1, cur = 0;
      for (let k = 0; k < nodes.length; k++) {
        const nx = adj[cur][0] === prev ? adj[cur][1] : adj[cur][0];
        if (nx === 0) break;
        order.push(nx); prev = cur; cur = nx;
      }
      if (order.length === nodes.length) cycle = order.map((i) => nodes[i].p);
    }
    return { nodes: nodes.map((n) => n.p), edges, adj, cycle };
  }

  function convexHull(points) {
    const P = points.slice().sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    if (P.length < 3) return P;
    const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    const lower = [], upper = [];
    for (const p of P) { while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) lower.pop(); lower.push(p); }
    for (let i = P.length - 1; i >= 0; i--) { const p = P[i]; while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) upper.pop(); upper.push(p); }
    upper.pop(); lower.pop();
    return lower.concat(upper);
  }

  function simplifyPoly(verts, tol, angTol) {
    let V = verts.slice();
    // merge close vertices
    for (let changed = true; changed && V.length > 3;) {
      changed = false;
      for (let i = 0; i < V.length; i++) {
        const j = (i + 1) % V.length;
        if (d2(V[i], V[j]) < tol) { V[i] = mid(V[i], V[j]); V.splice(j, 1); changed = true; break; }
      }
    }
    // drop nearly-straight vertices
    for (let changed = true; changed && V.length > 3;) {
      changed = false;
      for (let i = 0; i < V.length; i++) {
        const a = V[(i - 1 + V.length) % V.length], b = V[i], c = V[(i + 1) % V.length];
        if (interiorAngle(a, b, c) > 180 - angTol) { V.splice(i, 1); changed = true; break; }
      }
    }
    return V;
  }

  /* ================= recognition ================= */
  const segClass = (s) => {
    const ang = Math.atan2(Math.abs(s.b[1] - s.a[1]), Math.abs(s.b[0] - s.a[0])) * DEG;
    return ang < 20 ? 'H' : ang > 68 ? 'V' : 'O';
  };

  function polygonType(verts, diag) {
    let V = simplifyPoly(sortAround(verts), 0.08 * diag, 25);
    const n = V.length;
    if (n < 3) return null;
    if (n === 3) {
      const angs = V.map((v, i) => interiorAngle(V[(i + 2) % 3], v, V[(i + 1) % 3]));
      const right = angs.some((a) => Math.abs(a - 90) < 9);
      return { type: right ? 'rightTriangle' : 'triangle', fit: { verts: V }, confidence: 0.85 };
    }
    if (n === 4) {
      const E = V.map((v, i) => [v, V[(i + 1) % 4]]);
      const dir = E.map(([a, b]) => lineDir(a, b));
      const len = E.map(([a, b]) => d2(a, b));
      const par02 = angDiff180(dir[0], dir[2]) < 13, par13 = angDiff180(dir[1], dir[3]) < 13;
      const angs = V.map((v, i) => interiorAngle(V[(i + 3) % 4], v, V[(i + 1) % 4]));
      const allRight = angs.every((a) => Math.abs(a - 90) < 13);
      if (allRight) {
        const r = Math.min(len[0], len[1]) / Math.max(len[0], len[1]);
        return { type: r > 0.86 ? 'square' : 'rectangle', fit: { verts: V }, confidence: 0.85 };
      }
      if (par02 && par13) return { type: 'parallelogram', fit: { verts: V }, confidence: 0.75 };
      if (par02 || par13 || angDiff180(dir[0], dir[2]) < 20 || angDiff180(dir[1], dir[3]) < 20) return { type: 'trapezoid', fit: { verts: V }, confidence: 0.7 };
      return { type: 'quadrilateral', fit: { verts: V }, confidence: 0.6 };
    }
    if (n <= 8) return { type: 'regular' + n, fit: { verts: V }, confidence: 0.6 };
    return null;
  }

  /**
   * strokes: arrays of [x,y]. Returns {type, fit, confidence} or null.
   */
  function recognise(strokes) {
    const all = strokes.flat();
    if (all.length < 3) return null;
    const B = bbox(all);
    const diag = hyp(B.w, B.h);
    if (diag < 25) return null;
    const prims = strokes.flatMap((s) => decompose(s, diag));
    const ells = prims.filter((p) => p.kind === 'ellipse').sort((a, b) => b.a * b.b - a.a * a.b);
    const arcs = prims.filter((p) => p.kind === 'arc');
    const polys = prims.filter((p) => p.kind === 'poly');
    const segs = prims.filter((p) => p.kind === 'seg');
    const polySegs = polys.flatMap((p) => p.verts.map((v, i) => ({ kind: 'seg', a: v, b: p.verts[(i + 1) % p.verts.length], len: d2(v, p.verts[(i + 1) % p.verts.length]) })));
    const allSegs = segs.concat(polySegs).filter((s) => s.len > 0.06 * diag);

    if (!ells.length) {
      if (!arcs.length || arcs.every((a) => a.len < 0.15 * diag)) {
        if (polys.length === 1 && !segs.some((s) => s.len > 0.12 * diag)) return polygonType(polys[0].verts, diag);
        const g = buildGraph(allSegs, 0.13 * diag);
        if (g.cycle) return polygonType(g.cycle, diag);
        const pyr = detectPyramid(g, B);
        if (pyr) return pyr;
        const box = detectBox(allSegs);
        if (box) return box;
        if (allSegs.length >= 3) return polygonType(convexHull(allSegs.flatMap((s) => [s.a, s.b])), diag);
        return null;
      }
      // open curves only: a circle that wasn't closed neatly
      const pts = arcs.flatMap((a) => a.pts).concat(segs.flatMap((s) => [s.a, s.b]));
      const e = fitEllipse(pts);
      if (e.residual < 0.2) return ellipseType(e);
      return null;
    }

    const main = ells[0];
    const mainW = main.box.w, mainH = main.box.h;
    const extras = ells.slice(1).concat(arcs).filter((o) => (o.box.w > 0.25 * mainW));
    const longSegs = allSegs.filter((s) => s.len > 0.2 * diag);

    // Sphere: a round outline with an "equator" inside it.
    const inner = extras.find((o) => {
      const ob = o.box;
      return ob.w >= 0.5 * mainW && ob.w <= 1.12 * mainW && ob.h <= 0.65 * ob.w &&
        Math.abs(ob.cx - main.box.cx) < 0.25 * mainW && ob.cy > main.box.y0 + 0.15 * mainH && ob.cy < main.box.y1 - 0.1 * mainH;
    });
    if (main.ratio >= 0.7 && inner && longSegs.length === 0 && main.box.h > 0.6 * B.h) {
      return { type: 'sphere', fit: { c: [main.cx, main.cy], r: (main.box.w + main.box.h) / 4 }, confidence: 0.75 };
    }

    if (longSegs.length >= 2 || (main.box.h < 0.6 * B.h)) {
      // Cone: the slanted sides meet at one point away from the ellipse.
      const ec = [main.cx, main.cy];
      const far = longSegs.map((s) => (d2(s.a, ec) > d2(s.b, ec) ? s.a : s.b));
      const near = longSegs.map((s) => (d2(s.a, ec) > d2(s.b, ec) ? s.b : s.a));
      let maxFar = 0;
      for (let i = 0; i < far.length; i++) for (let j = i + 1; j < far.length; j++) maxFar = Math.max(maxFar, d2(far[i], far[j]));
      const nearOk = near.every((p) => Math.abs(p[1] - main.cy) < 0.9 * mainH + 0.1 * mainW);
      if (far.length >= 2 && maxFar < 0.2 * diag && nearOk) {
        const apex = centroid(far);
        return { type: 'cone', fit: { apex, base: { cx: main.cx, cy: main.cy, rx: mainW / 2, ry: mainH / 2 } }, confidence: 0.75 };
      }
      const verticals = longSegs.filter((s) => segClass(s) === 'V');
      const lowerCurve = extras.find((o) => o.box.w > 0.6 * mainW && Math.abs(o.box.cx - main.box.cx) < 0.3 * mainW && Math.abs(o.box.cy - main.box.cy) > 0.5 * mainH);
      if (verticals.length >= 2 || (verticals.length >= 1 && lowerCurve) || (lowerCurve && lowerCurve.box.h > mainH)) {
        return { type: 'cylinder', fit: null, confidence: 0.7 };
      }
    }
    const lowerCurve = extras.find((o) => o.box.w > 0.6 * mainW && Math.abs(o.box.cx - main.box.cx) < 0.3 * mainW && o.box.cy - main.box.cy > 0.5 * mainH);
    if (lowerCurve) return { type: 'cylinder', fit: null, confidence: 0.65 };
    if (!extras.length && !longSegs.length) return ellipseType(main);
    return Object.assign(ellipseType(main), { confidence: 0.4 });
  }

  function ellipseType(e) {
    if (e.ratio >= 0.78) return { type: 'circle', fit: { c: [e.cx, e.cy], r: (e.box.w + e.box.h) / 4 }, confidence: 0.85 };
    return { type: 'ellipse', fit: { c: [e.box.cx, e.box.cy], rx: e.box.w / 2, ry: e.box.h / 2 }, confidence: 0.75 };
  }

  function detectPyramid(g, B) {
    if (g.nodes.length < 4) return null;
    let apex = 0;
    g.nodes.forEach((p, i) => { if (p[1] < g.nodes[apex][1]) apex = i; });
    const nb = g.adj[apex];
    if (nb.length < 3) return null;
    if (!nb.every((j) => g.nodes[j][1] > g.nodes[apex][1] + 0.35 * B.h)) return null;
    const base = g.nodes.filter((_, i) => i !== apex);
    return { type: 'pyramid', fit: { apex: g.nodes[apex], base: sortAround(base) }, confidence: 0.7 };
  }

  function detectBox(segs) {
    const H = segs.filter((s) => segClass(s) === 'H'), V = segs.filter((s) => segClass(s) === 'V'), O = segs.filter((s) => segClass(s) === 'O');
    if (H.length < 2 || V.length < 2 || O.length < 2 || segs.length < 6) return null;
    const med = (arr) => arr.map((s) => s.len).sort((a, b) => a - b)[Math.floor(arr.length / 2)];
    const l = med(H), h = med(V);
    const cube = Math.abs(l - h) / Math.max(l, h) < 0.2;
    return { type: cube ? 'cube' : 'box', fit: { segs: segs.map((s) => ({ a: s.a, b: s.b, cls: segClass(s) })) }, confidence: 0.7 };
  }

  /* ================= models ================= */
  const TRI_EQS = ['A + B + C = 180', 'a^2 = b^2 + c^2 - 2*b*c*cos(A)', 'b^2 = a^2 + c^2 - 2*a*c*cos(B)',
    'c^2 = a^2 + b^2 - 2*a*b*cos(C)', 'Area = 1/2*a*b*sin(C)', 'Area = 1/2*b*c*sin(A)', 'Area = 1/2*a*c*sin(B)',
    'Perimeter = a + b + c', 'Area = 1/2*c*h'];

  const V = (name, desc, kind, extra) => Object.assign({ name, desc, kind }, extra || {});

  const DEFS = {
    triangle: { name: 'Triangle', dim: 2, vars: [V('a', 'side a (opposite angle A)', 'length'), V('b', 'side b (opposite angle B)', 'length'), V('c', 'side c — the base', 'length'), V('A', 'angle A', 'angle'), V('B', 'angle B', 'angle'), V('C', 'angle C', 'angle'), V('h', 'height to the base c', 'length'), V('Area', 'area', 'area', { whole: true }), V('Perimeter', 'perimeter', 'length', { whole: true })], eqs: TRI_EQS },
    rightTriangle: { name: 'Right triangle', dim: 2, vars: [V('a', 'leg a', 'length'), V('b', 'leg b', 'length'), V('c', 'hypotenuse c', 'length'), V('A', 'angle A', 'angle'), V('B', 'angle B', 'angle'), V('h', 'height to the hypotenuse', 'length'), V('Area', 'area', 'area', { whole: true }), V('Perimeter', 'perimeter', 'length', { whole: true })], eqs: TRI_EQS, fixed: { C: 90 } },
    rectangle: { name: 'Rectangle', dim: 2, vars: [V('w', 'width', 'length'), V('h', 'height', 'length'), V('d', 'diagonal', 'length'), V('Area', 'area', 'area', { whole: true }), V('Perimeter', 'perimeter', 'length', { whole: true })], eqs: ['Area = w*h', 'Perimeter = 2*w + 2*h', 'd^2 = w^2 + h^2'] },
    square: { name: 'Square', dim: 2, vars: [V('s', 'side', 'length'), V('d', 'diagonal', 'length'), V('Area', 'area', 'area', { whole: true }), V('Perimeter', 'perimeter', 'length', { whole: true })], eqs: ['Area = s^2', 'Perimeter = 4*s', 'd = s*sqrt(2)'] },
    parallelogram: { name: 'Parallelogram', dim: 2, vars: [V('b', 'base', 'length'), V('s', 'slanted side', 'length'), V('h', 'height', 'length'), V('theta', 'angle θ', 'angle'), V('Area', 'area', 'area', { whole: true }), V('Perimeter', 'perimeter', 'length', { whole: true })], eqs: ['Area = b*h', 'h = s*sin(theta)', 'Perimeter = 2*b + 2*s'] },
    trapezoid: { name: 'Trapezoid', dim: 2, vars: [V('a', 'top (parallel side)', 'length'), V('b', 'bottom (parallel side)', 'length'), V('h', 'height', 'length'), V('c', 'left side', 'length'), V('d', 'right side', 'length'), V('Area', 'area', 'area', { whole: true }), V('Perimeter', 'perimeter', 'length', { whole: true })], eqs: ['Area = (a + b)/2*h', 'Perimeter = a + b + c + d'] },
    quadrilateral: { name: 'Quadrilateral', dim: 2, vars: [V('a', 'side a', 'length'), V('b', 'side b', 'length'), V('c', 'side c', 'length'), V('d', 'side d', 'length'), V('Perimeter', 'perimeter', 'length', { whole: true })], eqs: ['Perimeter = a + b + c + d'], note: 'A general four-sided shape only links its sides through the perimeter. For area, redraw it as a rectangle, parallelogram or trapezoid, or split it into two triangles.' },
    circle: { name: 'Circle', dim: 2, vars: [V('r', 'radius', 'length'), V('d', 'diameter', 'length'), V('Circumference', 'circumference', 'length', { whole: true }), V('Area', 'area', 'area', { whole: true })], eqs: ['d = 2*r', 'Circumference = 2*pi*r', 'Area = pi*r^2'] },
    ellipse: { name: 'Ellipse', dim: 2, vars: [V('a', 'half-width', 'length'), V('b', 'half-height', 'length'), V('Area', 'area', 'area', { whole: true }), V('Perimeter', 'perimeter (very close estimate)', 'length', { whole: true })], eqs: ['Area = pi*a*b', 'Perimeter = pi*(3*(a + b) - sqrt((3*a + b)*(a + 3*b)))'] },
    cube: { name: 'Cube', dim: 3, vars: [V('s', 'edge', 'length'), V('d', 'space diagonal', 'length'), V('Volume', 'volume', 'volume', { whole: true }), V('Surface', 'surface area', 'area', { whole: true })], eqs: ['Volume = s^3', 'Surface = 6*s^2', 'd = s*sqrt(3)'] },
    box: { name: 'Rectangular prism (box)', dim: 3, vars: [V('l', 'length', 'length'), V('w', 'depth', 'length'), V('h', 'height', 'length'), V('d', 'space diagonal', 'length'), V('Volume', 'volume', 'volume', { whole: true }), V('Surface', 'surface area', 'area', { whole: true })], eqs: ['Volume = l*w*h', 'Surface = 2*(l*w + l*h + w*h)', 'd^2 = l^2 + w^2 + h^2'] },
    cylinder: { name: 'Cylinder', dim: 3, vars: [V('r', 'radius', 'length'), V('d', 'diameter', 'length'), V('h', 'height', 'length'), V('Volume', 'volume', 'volume', { whole: true }), V('Surface', 'total surface area', 'area', { whole: true }), V('Lateral', 'side (curved) area', 'area', { whole: true })], eqs: ['d = 2*r', 'Volume = pi*r^2*h', 'Lateral = 2*pi*r*h', 'Surface = 2*pi*r^2 + Lateral'] },
    cone: { name: 'Cone', dim: 3, vars: [V('r', 'base radius', 'length'), V('d', 'base diameter', 'length'), V('h', 'height', 'length'), V('l', 'slant height', 'length'), V('Volume', 'volume', 'volume', { whole: true }), V('Surface', 'total surface area', 'area', { whole: true })], eqs: ['d = 2*r', 'l^2 = r^2 + h^2', 'Volume = 1/3*pi*r^2*h', 'Surface = pi*r^2 + pi*r*l'] },
    sphere: { name: 'Sphere', dim: 3, vars: [V('r', 'radius', 'length'), V('d', 'diameter', 'length'), V('Volume', 'volume', 'volume', { whole: true }), V('Surface', 'surface area', 'area', { whole: true })], eqs: ['d = 2*r', 'Volume = 4/3*pi*r^3', 'Surface = 4*pi*r^2'] },
    pyramid: { name: 'Square pyramid', dim: 3, vars: [V('s', 'base edge', 'length'), V('h', 'height', 'length'), V('l', 'slant height (middle of a face)', 'length'), V('edge', 'slanted edge', 'length'), V('Volume', 'volume', 'volume', { whole: true }), V('Surface', 'total surface area', 'area', { whole: true })], eqs: ['l^2 = h^2 + (s/2)^2', 'edge^2 = l^2 + (s/2)^2', 'Volume = 1/3*s^2*h', 'Surface = s^2 + 2*s*l'] }
  };
  for (const n of [5, 6, 7, 8]) {
    const nm = { 5: 'Regular pentagon', 6: 'Regular hexagon', 7: 'Regular heptagon', 8: 'Regular octagon' }[n];
    DEFS['regular' + n] = {
      name: nm, dim: 2, n,
      vars: [V('s', 'side', 'length'), V('ap', 'apothem (centre to middle of a side)', 'length'), V('R', 'radius (centre to a corner)', 'length'), V('Area', 'area', 'area', { whole: true }), V('Perimeter', 'perimeter', 'length', { whole: true })],
      eqs: [`Perimeter = ${n}*s`, `ap = s/(2*tan(180/${n}))`, `R = s/(2*sin(180/${n}))`, 'Area = Perimeter*ap/2']
    };
  }
  const TYPES = Object.keys(DEFS);

  /* ---------- default geometry from a bounding box ---------- */
  function defaultFit(type, B) {
    const { x0, y0, x1, y1, w: W, h: H } = B;
    const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
    switch (type) {
      case 'triangle': return { verts: [[x0, y1], [x1, y1], [x0 + 0.58 * W, y0]] };
      case 'rightTriangle': return { verts: [[x0, y1], [x1, y1], [x0, y0]] };
      case 'rectangle': return { verts: [[x0, y0], [x1, y0], [x1, y1], [x0, y1]] };
      case 'square': { const s = Math.min(W, H); return { verts: [[x0, y0], [x0 + s, y0], [x0 + s, y0 + s], [x0, y0 + s]] }; }
      case 'parallelogram': return { verts: [[x0 + 0.25 * W, y0], [x1, y0], [x1 - 0.25 * W, y1], [x0, y1]] };
      case 'trapezoid': return { verts: [[x0 + 0.22 * W, y0], [x1 - 0.22 * W, y0], [x1, y1], [x0, y1]] };
      case 'quadrilateral': return { verts: [[x0 + 0.1 * W, y0], [x1, y0 + 0.2 * H], [x1 - 0.15 * W, y1], [x0, y1 - 0.1 * H]] };
      case 'circle': return { c: [cx, cy], r: Math.min(W, H) / 2 };
      case 'ellipse': return { c: [cx, cy], rx: W / 2, ry: Math.max(H / 2, 8) };
      case 'sphere': return { c: [cx, cy], r: Math.min(W, H) / 2 };
      default:
        if (/^regular\d$/.test(type)) {
          const n = DEFS[type].n, r = Math.min(W, H) / 2;
          return { verts: Array.from({ length: n }, (_, i) => { const a = -Math.PI / 2 + 2 * Math.PI * i / n; return [cx + r * Math.cos(a), cy + r * Math.sin(a)]; }) };
        }
        return {};
    }
  }

  /* ---------- build parts / outline ---------- */
  function seg(v, a, b, extra) { return Object.assign({ v, kind: 'seg', a, b }, extra || {}); }
  function angle(v, o, p, q, extra) { return Object.assign({ v, kind: 'angle', o, p, q }, extra || {}); }

  function buildModel(type, fit, B) {
    const def = DEFS[type];
    if (!def) return null;
    const ownFit = fit && Object.keys(fit).length ? fit : null;
    const F = ownFit || defaultFit(type, B);
    const parts = [];
    const outline = [];
    const guideOnly = !ownFit; // the drawing didn't give us the geometry: show a guide
    let vertsForCentroid = [];

    const polyOutline = (verts) => outline.push({ k: 'poly', pts: verts, closed: true });

    if (type === 'triangle' || type === 'rightTriangle') {
      let Vv = F.verts.slice();
      let Ai, Bi, Ci;
      if (type === 'rightTriangle') {
        const angs = Vv.map((v, i) => interiorAngle(Vv[(i + 2) % 3], v, Vv[(i + 1) % 3]));
        Ci = angs.map((a) => Math.abs(a - 90)).indexOf(Math.min(...angs.map((a) => Math.abs(a - 90))));
        const others = [0, 1, 2].filter((i) => i !== Ci);
        [Ai, Bi] = Vv[others[0]][0] <= Vv[others[1]][0] ? others : [others[1], others[0]];
      } else {
        let best = 0, by = -Infinity;
        for (let i = 0; i < 3; i++) { const y = (Vv[i][1] + Vv[(i + 1) % 3][1]) / 2; if (y > by) { by = y; best = i; } }
        const e = [best, (best + 1) % 3];
        [Ai, Bi] = Vv[e[0]][0] <= Vv[e[1]][0] ? e : [e[1], e[0]];
        Ci = 3 - Ai - Bi;
      }
      const A = Vv[Ai], Bp = Vv[Bi], C = Vv[Ci];
      vertsForCentroid = [A, Bp, C];
      polyOutline([A, Bp, C]);
      parts.push(seg('a', Bp, C), seg('b', A, C), seg('c', A, Bp));
      parts.push(angle('A', A, Bp, C), angle('B', Bp, A, C), angle('C', C, A, Bp, type === 'rightTriangle' ? { right: true, fixed: true } : {}));
      parts.push(seg('h', C, foot(C, A, Bp), { guide: true }));
    } else if (['rectangle', 'square', 'parallelogram', 'trapezoid', 'quadrilateral'].includes(type)) {
      const Vv = sortAround(F.verts);
      vertsForCentroid = Vv;
      polyOutline(Vv);
      const E = Vv.map((v, i) => [v, Vv[(i + 1) % 4]]);
      const horiz = (e) => Math.abs(e[1][0] - e[0][0]) / (d2(e[0], e[1]) || 1);
      const pairA = [0, 2], pairB = [1, 3];
      const hPair = horiz(E[0]) + horiz(E[2]) >= horiz(E[1]) + horiz(E[3]) ? pairA : pairB;
      const vPair = hPair === pairA ? pairB : pairA;
      const avgY = (e) => (e[0][1] + e[1][1]) / 2, avgX = (e) => (e[0][0] + e[1][0]) / 2;
      const bottom = avgY(E[hPair[0]]) > avgY(E[hPair[1]]) ? E[hPair[0]] : E[hPair[1]];
      const top = bottom === E[hPair[0]] ? E[hPair[1]] : E[hPair[0]];
      const left = avgX(E[vPair[0]]) < avgX(E[vPair[1]]) ? E[vPair[0]] : E[vPair[1]];
      const right = left === E[vPair[0]] ? E[vPair[1]] : E[vPair[0]];
      if (type === 'rectangle') {
        parts.push(seg('w', ...bottom), seg('w', ...top, { label: false }), seg('h', ...right), seg('h', ...left, { label: false }));
        parts.push(seg('d', Vv[0], Vv[2], { guide: true }));
      } else if (type === 'square') {
        parts.push(seg('s', ...bottom), seg('s', ...top, { label: false }), seg('s', ...right, { label: false }), seg('s', ...left, { label: false }));
        parts.push(seg('d', Vv[0], Vv[2], { guide: true }));
      } else if (type === 'parallelogram') {
        parts.push(seg('b', ...bottom), seg('b', ...top, { label: false }), seg('s', ...right), seg('s', ...left, { label: false }));
        const bl = bottom[0][0] < bottom[1][0] ? bottom[0] : bottom[1];
        const br = bl === bottom[0] ? bottom[1] : bottom[0];
        const tl = d2(left[0], bl) < d2(left[1], bl) ? left[1] : left[0];
        parts.push(angle('theta', bl, br, tl));
        parts.push(seg('h', tl, foot(tl, bottom[0], bottom[1]), { guide: true }));
      } else if (type === 'trapezoid') {
        // the parallel pair are the bases; pick the more parallel pair
        const par = angDiff180(lineDir(...E[0]), lineDir(...E[2])) <= angDiff180(lineDir(...E[1]), lineDir(...E[3])) ? pairA : pairB;
        const other = par === pairA ? pairB : pairA;
        const bot = avgY(E[par[0]]) > avgY(E[par[1]]) ? E[par[0]] : E[par[1]];
        const tp = bot === E[par[0]] ? E[par[1]] : E[par[0]];
        const lf = avgX(E[other[0]]) < avgX(E[other[1]]) ? E[other[0]] : E[other[1]];
        const rt = lf === E[other[0]] ? E[other[1]] : E[other[0]];
        parts.push(seg('b', ...bot), seg('a', ...tp), seg('c', ...lf), seg('d', ...rt));
        const tl = tp[0][0] < tp[1][0] ? tp[0] : tp[1];
        parts.push(seg('h', tl, foot(tl, bot[0], bot[1]), { guide: true }));
      } else {
        parts.push(seg('a', ...bottom), seg('b', ...right), seg('c', ...top), seg('d', ...left));
      }
    } else if (/^regular\d$/.test(type)) {
      const Vv = sortAround(F.verts);
      vertsForCentroid = Vv;
      polyOutline(Vv);
      const c = centroid(Vv);
      const E = Vv.map((v, i) => [v, Vv[(i + 1) % Vv.length]]);
      let bi = 0;
      E.forEach((e, i) => { if ((e[0][1] + e[1][1]) > (E[bi][0][1] + E[bi][1][1])) bi = i; });
      E.forEach((e, i) => parts.push(seg('s', e[0], e[1], i === bi ? {} : { label: false })));
      parts.push(seg('ap', c, mid(...E[bi]), { guide: true }));
      let vi = 0;
      Vv.forEach((v, i) => { if (v[0] - v[1] > Vv[vi][0] - Vv[vi][1]) vi = i; });
      parts.push(seg('R', c, Vv[vi], { guide: true }));
    } else if (type === 'circle') {
      const { c, r } = F;
      vertsForCentroid = [c];
      outline.push({ k: 'ell', c, rx: r, ry: r });
      const a = -35 / DEG;
      parts.push(seg('r', c, [c[0] + r * Math.cos(a), c[1] + r * Math.sin(a)], { guide: true }));
      parts.push(seg('d', [c[0] - r, c[1]], [c[0] + r, c[1]], { guide: true, labelAt: 0.25 }));
      parts.push({ v: 'Circumference', kind: 'rim', c, rx: r, ry: r });
    } else if (type === 'ellipse') {
      const { c, rx, ry } = F;
      vertsForCentroid = [c];
      outline.push({ k: 'ell', c, rx, ry });
      parts.push(seg('a', c, [c[0] + rx, c[1]], { guide: true }));
      parts.push(seg('b', c, [c[0], c[1] - ry], { guide: true }));
      parts.push({ v: 'Perimeter', kind: 'rim', c, rx, ry });
    } else if (type === 'sphere') {
      const { c, r } = F;
      vertsForCentroid = [c];
      outline.push({ k: 'ell', c, rx: r, ry: r });
      outline.push({ k: 'ell', c, rx: r, ry: r * 0.28, backDash: true });
      parts.push(seg('r', c, [c[0] + r, c[1]], { guide: true }));
      parts.push(seg('d', [c[0], c[1] - r], [c[0], c[1] + r], { guide: true, labelAt: 0.25 }));
    } else if (type === 'cylinder') {
      const rx = B.w / 2, ry = clamp(B.w * 0.16, 5, B.h * 0.2), cx = B.cx;
      const ty = B.y0 + ry, by = B.y1 - ry;
      vertsForCentroid = [[cx, ty], [cx, by]];
      outline.push({ k: 'ell', c: [cx, ty], rx, ry });
      outline.push({ k: 'ell', c: [cx, by], rx, ry, backDash: true });
      outline.push({ k: 'line', a: [cx - rx, ty], b: [cx - rx, by] }, { k: 'line', a: [cx + rx, ty], b: [cx + rx, by] });
      parts.push(seg('r', [cx, ty], [cx + rx, ty], { guide: true }));
      parts.push(seg('h', [cx + rx, ty], [cx + rx, by]), seg('h', [cx - rx, ty], [cx - rx, by], { label: false }));
      parts.push(seg('d', [cx - rx, by], [cx + rx, by], { guide: true, labelAt: 0.3 }));
    } else if (type === 'cone') {
      let apex, base;
      if (ownFit && ownFit.apex) { apex = ownFit.apex; base = ownFit.base; }
      else { const ry = clamp(B.w * 0.16, 5, B.h * 0.2); apex = [B.cx, B.y0]; base = { cx: B.cx, cy: B.y1 - ry, rx: B.w / 2, ry }; }
      const bc = [base.cx, base.cy];
      vertsForCentroid = [apex, bc];
      const apexUp = apex[1] < base.cy;
      outline.push({ k: 'ell', c: bc, rx: base.rx, ry: base.ry, backDash: apexUp });
      outline.push({ k: 'line', a: apex, b: [base.cx - base.rx, base.cy] }, { k: 'line', a: apex, b: [base.cx + base.rx, base.cy] });
      parts.push(seg('r', bc, [base.cx + base.rx, base.cy], { guide: true }));
      parts.push(seg('h', apex, bc, { guide: true }));
      parts.push(seg('l', apex, [base.cx + base.rx, base.cy]), seg('l', apex, [base.cx - base.rx, base.cy], { label: false }));
    } else if (type === 'box' || type === 'cube') {
      const name = (cls) => (type === 'cube' ? 's' : { H: 'l', V: 'h', O: 'w' }[cls]);
      if (ownFit && ownFit.segs) {
        const reps = {};
        for (const s of ownFit.segs) {
          const v = name(s.cls);
          const score = s.cls === 'H' ? (s.a[1] + s.b[1]) : s.cls === 'V' ? (s.a[0] + s.b[0]) : (s.a[0] + s.b[0] + s.a[1] + s.b[1]);
          if (!reps[s.cls] || score > reps[s.cls].score) reps[s.cls] = { s, score };
        }
        for (const s of ownFit.segs) {
          const isRep = reps[s.cls].s === s;
          const show = type === 'cube' ? (s.cls === 'H' && isRep) : isRep;
          parts.push(seg(name(s.cls), s.a, s.b, show ? {} : { label: false }));
        }
        vertsForCentroid = ownFit.segs.flatMap((s) => [s.a, s.b]);
      } else {
        const fw = (type === 'cube' ? Math.min(B.w, B.h) : B.w) * 0.7, fh = (type === 'cube' ? Math.min(B.w, B.h) : B.h) * 0.7;
        const ox = (type === 'cube' ? Math.min(B.w, B.h) : B.w) * 0.3, oy = (type === 'cube' ? Math.min(B.w, B.h) : B.h) * 0.3;
        const x0 = B.x0, y1 = B.y0 + oy + fh;
        const Fbl = [x0, y1], Fbr = [x0 + fw, y1], Ftr = [x0 + fw, y1 - fh], Ftl = [x0, y1 - fh];
        const o = [ox, -oy];
        const Bbl = add(Fbl, o), Bbr = add(Fbr, o), Btr = add(Ftr, o), Btl = add(Ftl, o);
        vertsForCentroid = [Fbl, Fbr, Ftr, Ftl, Bbl, Bbr, Btr, Btl];
        outline.push({ k: 'poly', pts: [Fbl, Fbr, Ftr, Ftl], closed: true });
        outline.push({ k: 'line', a: Ftl, b: Btl }, { k: 'line', a: Ftr, b: Btr }, { k: 'line', a: Fbr, b: Bbr });
        outline.push({ k: 'line', a: Btl, b: Btr }, { k: 'line', a: Btr, b: Bbr });
        outline.push({ k: 'line', a: Fbl, b: Bbl, dash: true }, { k: 'line', a: Bbl, b: Bbr, dash: true }, { k: 'line', a: Bbl, b: Btl, dash: true });
        parts.push(seg(name('H'), Fbl, Fbr), seg(name('H'), Ftl, Ftr, { label: false }), seg(name('H'), Btl, Btr, { label: false }));
        parts.push(seg(name('V'), Fbr, Ftr, type === 'cube' ? { label: false } : {}), seg(name('V'), Fbl, Ftl, { label: false }), seg(name('V'), Bbr, Btr, { label: false }));
        parts.push(seg(name('O'), Fbr, Bbr, type === 'cube' ? { label: false } : {}), seg(name('O'), Ftr, Btr, { label: false }), seg(name('O'), Ftl, Btl, { label: false }));
        parts.push(seg('d', Fbl, Btr, { guide: true, label: false }));
      }
    } else if (type === 'pyramid') {
      let apex, base;
      if (ownFit && ownFit.apex && ownFit.base.length >= 3) { apex = ownFit.apex; base = ownFit.base; }
      else {
        const { x0, y0, x1, y1, w: W, h: H } = B;
        base = [[x0, y1], [x0 + 0.72 * W, y1], [x1, y1 - 0.26 * H], [x0 + 0.28 * W, y1 - 0.26 * H]];
        apex = [x0 + 0.5 * W, y0];
        outline.push({ k: 'line', a: base[0], b: base[1] }, { k: 'line', a: base[1], b: base[2] });
        outline.push({ k: 'line', a: base[2], b: base[3], dash: true }, { k: 'line', a: base[3], b: base[0], dash: true });
        outline.push({ k: 'line', a: apex, b: base[0] }, { k: 'line', a: apex, b: base[1] }, { k: 'line', a: apex, b: base[2] }, { k: 'line', a: apex, b: base[3], dash: true });
      }
      const bc = centroid(base);
      vertsForCentroid = base.concat([apex]);
      let fi = 0;
      base.forEach((p, i) => { const q = base[(i + 1) % base.length]; const f = base[fi], g = base[(fi + 1) % base.length]; if (p[1] + q[1] > f[1] + g[1]) fi = i; });
      base.forEach((p, i) => parts.push(seg('s', p, base[(i + 1) % base.length], i === fi ? {} : { label: false })));
      let ri = 0;
      base.forEach((p, i) => { if (p[0] > base[ri][0]) ri = i; });
      base.forEach((p, i) => parts.push(seg('edge', apex, p, i === ri ? {} : { label: false })));
      parts.push(seg('h', apex, bc, { guide: true }));
      parts.push(seg('l', apex, mid(base[fi], base[(fi + 1) % base.length]), { guide: true }));
    }

    const center = vertsForCentroid.length ? centroid(vertsForCentroid) : [B.cx, B.cy];
    for (const p of parts) p.labelPos = labelPos(p, center);
    const allPts = outlinePoints(outline, parts);
    const MB = allPts.length ? bbox(allPts) : B;
    return {
      type, name: def.name, dim: def.dim, def, vars: def.vars, eqs: def.eqs, fixed: def.fixed || {}, note: def.note,
      parts, outline, guideOnly, center, box: MB, diag: hyp(MB.w, MB.h) || 1,
      whole: def.vars.filter((v) => v.whole).map((v) => v.name)
    };
  }

  function labelPos(p, center) {
    if (p.kind === 'seg') {
      const t = p.labelAt !== undefined ? p.labelAt : 0.5;
      const m = add(p.a, mul(sub(p.b, p.a), t));
      const dir = norm(sub(p.b, p.a));
      let nrm = [-dir[1], dir[0]];
      const out = sub(m, center);
      if (nrm[0] * out[0] + nrm[1] * out[1] < 0) nrm = mul(nrm, -1);
      if (p.guide) {
        // keep guide labels just beside the dashed line
        return add(m, mul([-dir[1], dir[0]], dir[0] >= 0 ? -12 : 12));
      }
      return add(m, mul(nrm, 18));
    }
    if (p.kind === 'angle') {
      const a = norm(sub(p.p, p.o)), b = norm(sub(p.q, p.o));
      const bis = norm(add(a, b));
      const r = clamp(0.22 * Math.min(d2(p.p, p.o), d2(p.q, p.o)), 20, 44);
      return add(p.o, mul(bis, r));
    }
    if (p.kind === 'rim') {
      const a = 225 / DEG;
      return [p.c[0] + p.rx * 1.08 * Math.cos(a) - 6, p.c[1] + p.ry * 1.08 * Math.sin(a) - 6];
    }
    return center;
  }

  function outlinePoints(outline, parts) {
    const pts = [];
    for (const o of outline) {
      if (o.k === 'poly') pts.push(...o.pts);
      else if (o.k === 'line') pts.push(o.a, o.b);
      else if (o.k === 'ell') for (let i = 0; i < 16; i++) { const a = i / 16 * 2 * Math.PI; pts.push([o.c[0] + o.rx * Math.cos(a), o.c[1] + o.ry * Math.sin(a)]); }
    }
    for (const p of parts) {
      if (p.kind === 'seg' && !p.guide) pts.push(p.a, p.b);
      if (p.kind === 'angle') pts.push(p.o);
    }
    return pts;
  }

  /* ================= annotations ================= */
  const DIGIT_SET = new Set([...'0123456789', '?', 'x', '-']);

  function strokeLoopy(pts) {
    const b = bbox(pts);
    const sd = hyp(b.w, b.h);
    const len = R.pathLen(pts);
    const chord = d2(pts[0], pts[pts.length - 1]);
    return { closed: chord < Math.max(0.3 * sd, 12) && len > 1.8 * sd, sd, b, len, chord };
  }

  function partAnchor(p) {
    if (p.kind === 'seg') return mid(p.a, p.b);
    if (p.kind === 'angle') return p.labelPos;
    return p.labelPos;
  }

  function angleArcPart(pts, model) {
    const st = R.strokeStats(pts);
    const c = centroid(pts);
    let best = null;
    for (const p of model.parts) {
      if (p.kind !== 'angle' || p.fixed) continue;
      const arm = Math.min(d2(p.p, p.o), d2(p.q, p.o));
      const dv = d2(c, p.o);
      if (dv > 0.5 * arm) continue;
      const a = norm(sub(p.p, p.o)), b = norm(sub(p.q, p.o)), w = norm(sub(c, p.o));
      const cross = (u, v) => u[0] * v[1] - u[1] * v[0];
      const inside = cross(a, w) * cross(a, b) >= 0 && cross(b, w) * cross(b, a) >= 0;
      if (!inside) continue;
      // an angle arc runs from one side of the angle to the other
      const e0 = pts[0], e1 = pts[pts.length - 1];
      const sd = Math.max(8, hyp(st.box.w, st.box.h));
      const near = (e, arm) => distPtSeg(e, p.o, add(p.o, mul(sub(arm, p.o), 1.2))) < 0.35 * sd + 6;
      const spans = (near(e0, p.p) && near(e1, p.q)) || (near(e0, p.q) && near(e1, p.p));
      if (!spans || st.straight < 0.45) continue;
      if (!best || dv < best.dv) best = { part: p, dv };
    }
    return best && best.part;
  }

  function matchBracket(pts, model) {
    const D = model.diag;
    const c = centroid(pts);
    const st = R.strokeStats(pts);
    const e0 = pts[0], e1 = pts[pts.length - 1];
    const blen = Math.max(st.chord, 1);
    const bdir = lineDir(e0, e1);
    let best = null;
    for (const p of model.parts) {
      let score = Infinity;
      if (p.kind === 'seg') {
        const sl = d2(p.a, p.b);
        const dd = distPtSeg(c, p.a, p.b) / D;
        const ad = angDiff180(bdir, lineDir(p.a, p.b));
        if (ad > 40) continue;
        score = 3 * dd + ad / 45 + 0.6 * Math.abs(Math.log(blen / sl));
        if (p.guide) score += 0.15;
      } else if (p.kind === 'angle') {
        if (p.fixed) continue;
        const arm = Math.min(d2(p.p, p.o), d2(p.q, p.o));
        if (blen > 0.7 * arm) continue;
        if (angleArcPart(pts, model) !== p) continue;
        score = 3 * d2(c, p.o) / D + (st.straight > 0.9 ? 0.5 : 0);
      } else if (p.kind === 'rim') {
        const rr = (p.rx + p.ry) / 2;
        const dc = Math.abs(d2(c, p.c) / rr - 1.1);
        score = 2 * dc + (st.straight > 0.9 ? 1 : 0);
      }
      if (!best || score < best.score) best = { part: p, score };
    }
    return best && best.score < 1.6 ? best.part : null;
  }

  function readNumber(cluster, cache) {
    const res = Layout.analyze(cluster.map((s) => ({ id: s.id, pts: s.pts })), { allowed: DIGIT_SET, cache });
    let text = res.lines.map((l) => l.text).join('');
    text = text.replace(/\^\(0\)$/, '').replace(/\^\(\)$/, ''); // a raised small 0 is a degree sign
    const value = /^-?\d*\.?\d+$/.test(text) ? parseFloat(text) : null;
    const unknown = value === null && /^[?x]+$/.test(text);
    return { text, value, unknown };
  }

  /**
   * model: from buildModel
   * marks: strokes made with the "Known" tool [{id, pts}]
   * finds: strokes made with the "Find" tool  [{id, pts}]
   */
  function annotate(model, marks, finds, opts) {
    opts = opts || {};
    const D = model.diag;
    const brackets = [], digits = [], loops = finds.slice();
    for (const s of marks) {
      const L = strokeLoopy(s.pts);
      if (L.closed && L.sd > Math.max(0.1 * D, 40)) { loops.push(s); continue; }
      if (L.sd > Math.max(0.16 * D, 40)) { brackets.push({ stroke: s, part: matchBracket(s.pts, model) }); continue; }
      const ang = L.sd > 14 && angleArcPart(s.pts, model);
      if (ang) { brackets.push({ stroke: s, part: ang }); continue; }
      digits.push(s);
    }

    // group digit strokes into numbers
    const clusters = [];
    const boxes = digits.map((s) => bbox(s.pts));
    const parent = digits.map((_, i) => i);
    const findP = (i) => (parent[i] === i ? i : (parent[i] = findP(parent[i])));
    for (let i = 0; i < digits.length; i++) {
      for (let j = i + 1; j < digits.length; j++) {
        const a = boxes[i], b = boxes[j];
        const h = Math.max(a.h, b.h, 12);
        const gx = Math.max(0, Math.max(a.x0, b.x0) - Math.min(a.x1, b.x1));
        const gy = Math.max(0, Math.max(a.y0, b.y0) - Math.min(a.y1, b.y1));
        if (gx < 0.6 * h && gy < 0.35 * h) parent[findP(i)] = findP(j);
      }
    }
    const byRoot = {};
    digits.forEach((s, i) => { (byRoot[findP(i)] = byRoot[findP(i)] || []).push(s); });
    for (const group of Object.values(byRoot)) {
      const num = readNumber(group, opts.cache);
      clusters.push(Object.assign({ strokes: group, box: bbox(group.flatMap((s) => s.pts)) }, num));
    }

    // numbers → brackets (closest first), then leftovers → nearest part
    const pairs = [];
    clusters.forEach((cl, ci) => {
      const cc = [cl.box.cx, cl.box.cy];
      brackets.forEach((br, bi) => {
        let dmin = Infinity;
        for (const p of br.stroke.pts) dmin = Math.min(dmin, d2(p, cc));
        pairs.push({ ci, bi, d: dmin });
      });
    });
    pairs.sort((a, b) => a.d - b.d);
    const usedC = new Set(), usedB = new Set();
    for (const pr of pairs) {
      if (usedC.has(pr.ci) || usedB.has(pr.bi) || pr.d > 0.35 * D + 50) continue;
      usedC.add(pr.ci); usedB.add(pr.bi);
      clusters[pr.ci].bracket = brackets[pr.bi];
      brackets[pr.bi].number = clusters[pr.ci];
      clusters[pr.ci].part = brackets[pr.bi].part;
    }
    clusters.forEach((cl, ci) => {
      if (usedC.has(ci)) return;
      const cc = [cl.box.cx, cl.box.cy];
      let best = null;
      for (const p of model.parts) {
        if (p.fixed) continue;
        const dd = p.kind === 'seg' ? Math.min(distPtSeg(cc, p.a, p.b), d2(cc, p.labelPos)) : d2(cc, p.labelPos);
        if (!best || dd < best.d) best = { p, d: dd };
      }
      if (best && best.d < 0.3 * D + 30) cl.part = best.p;
    });

    // find-loops → what is being asked for
    const findsOut = loops.map((s) => {
      const poly = s.pts;
      const shapePts = outlinePoints(model.outline, model.parts);
      const inside = shapePts.filter((p) => pointInPoly(p, poly)).length;
      if (shapePts.length && inside / shapePts.length >= 0.6) return { stroke: s, whole: true };
      // a "?" number inside the loop → its part
      const cl = clusters.find((c) => c.part && pointInPoly([c.box.cx, c.box.cy], poly));
      if (cl) return { stroke: s, part: cl.part };
      const lc = centroid(poly);
      let best = null;
      for (const p of model.parts) {
        if (p.fixed) continue;
        const anchors = [partAnchor(p), p.labelPos];
        for (const a of anchors) {
          const ins = pointInPoly(a, poly);
          const dd = d2(a, lc) / D + (ins ? 0 : 1);
          if (!best || dd < best.d) best = { p, d: dd };
        }
      }
      if (best && best.d < 1.3) return { stroke: s, part: best.p };
      return { stroke: s, part: null };
    });

    // collect values and target
    const values = {}, issues = [];
    for (const cl of clusters) {
      if (!cl.part) { issues.push({ kind: 'stray-number', text: cl.text, box: cl.box }); continue; }
      if (cl.unknown) { findsOut.push({ stroke: null, part: cl.part, fromMark: true }); continue; }
      if (cl.value === null) { issues.push({ kind: 'unreadable', text: cl.text, part: cl.part.v, box: cl.box, strokes: cl.strokes }); continue; }
      values[cl.part.v] = { value: cl.value, text: cl.text, strokes: cl.strokes };
    }
    for (const br of brackets) {
      if (!br.part) issues.push({ kind: 'bracket-unmatched', stroke: br.stroke });
      else if (!br.number) issues.push({ kind: 'bracket-no-number', part: br.part.v, stroke: br.stroke });
    }
    const lastFind = findsOut[findsOut.length - 1] || null;
    return { brackets, clusters, finds: findsOut, values, target: lastFind, issues };
  }

  return { recognise, buildModel, annotate, defaultFit, DEFS, TYPES, pointInPoly, bbox, centroid };
});
