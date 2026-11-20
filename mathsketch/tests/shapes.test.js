const Sh = require('../js/shapes.js');
const M = require('../js/mathcore.js');
const S = require('./synth.js');
const assert = require('assert');
let seed = 3; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
const wob = (p, k = 1.5) => [p[0] + (rnd() - .5) * k * 2, p[1] + (rnd() - .5) * k * 2];
const line = (a, b, n = 20) => Array.from({ length: n + 1 }, (_, i) => wob([a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n]));
const poly = (pts, closed = true) => { let out = []; const P = closed ? pts.concat([pts[0]]) : pts; for (let i = 1; i < P.length; i++) out = out.concat(line(P[i - 1], P[i]).slice(i > 1 ? 1 : 0)); return out; };
const ell = (cx, cy, rx, ry, a0 = 0, a1 = 360, n = 60) => Array.from({ length: n + 1 }, (_, i) => { const a = (a0 + (a1 - a0) * i / n) * Math.PI / 180; return wob([cx + rx * Math.cos(a), cy + ry * Math.sin(a)]); });
let pass = 0, fail = 0;
function t(name, strokes, want) { const r = Sh.recognise(strokes); const got = r && r.type; if (got === want) pass++; else { fail++; console.log('FAIL', name, 'got', got, 'want', want); } return r; }
t('triangle', [poly([[100, 300], [320, 300], [180, 120]])], 'triangle');
t('right triangle', [poly([[100, 300], [300, 300], [100, 150]])], 'rightTriangle');
t('rect 4 lines', [line([100, 100], [300, 100]), line([300, 100], [300, 220]), line([300, 220], [100, 220]), line([100, 220], [100, 100])], 'rectangle');
t('square', [poly([[100, 100], [250, 100], [250, 250], [100, 250]])], 'square');
t('parallelogram', [poly([[150, 100], [350, 100], [300, 220], [100, 220]])], 'parallelogram');
t('trapezoid', [poly([[150, 100], [270, 100], [340, 220], [100, 220]])], 'trapezoid');
t('hexagon', [poly(Array.from({ length: 6 }, (_, i) => [200 + 100 * Math.cos(i * Math.PI / 3), 200 + 100 * Math.sin(i * Math.PI / 3)]))], 'regular6');
t('pentagon', [poly(Array.from({ length: 5 }, (_, i) => [200 + 100 * Math.cos(-Math.PI / 2 + i * 2 * Math.PI / 5), 200 + 100 * Math.sin(-Math.PI / 2 + i * 2 * Math.PI / 5)]))], 'regular5');
t('circle', [ell(200, 200, 100, 96, -80, 285)], 'circle');
t('ellipse', [ell(200, 200, 140, 60)], 'ellipse');
t('sphere', [ell(200, 200, 100, 100), ell(200, 200, 100, 28, 0, 180)], 'sphere');
t('cylinder', [ell(200, 100, 80, 22), line([120, 100], [120, 300]), line([280, 100], [280, 300]), ell(200, 300, 80, 22, 0, 180)], 'cylinder');
t('cylinder U', [ell(200, 100, 80, 22), [...line([120, 100], [120, 300]).slice(0, -1), ...ell(200, 300, 80, 22, 180, 0).slice(0, -1), ...line([280, 300], [280, 100])]], 'cylinder');
t('cone', [ell(200, 300, 90, 25), line([110, 300], [200, 80]), line([200, 80], [290, 300])], 'cone');
t('cone one stroke', [ell(200, 300, 90, 25), poly([[110, 300], [200, 80], [290, 300]], false)], 'cone');
const F = [[100, 300], [250, 300], [250, 150], [100, 150]], o = [70, -60];
const Bk = F.map((p) => [p[0] + o[0], p[1] + o[1]]);
t('cube', [poly(F), line(F[3], Bk[3]), line(F[2], Bk[2]), line(F[1], Bk[1]), line(Bk[3], Bk[2]), line(Bk[2], Bk[1])], 'cube');
const F2 = [[100, 300], [350, 300], [350, 200], [100, 200]], B2 = F2.map((p) => [p[0] + 60, p[1] - 50]);
t('box', [poly(F2), line(F2[3], B2[3]), line(F2[2], B2[2]), line(F2[1], B2[1]), line(B2[3], B2[2]), line(B2[2], B2[1])], 'box');
const base = [[100, 320], [260, 320], [320, 270], [160, 270]], apex = [210, 80];
t('pyramid', [poly(base), line(apex, base[0]), line(apex, base[1]), line(apex, base[2])], 'pyramid');

// annotation: triangle with brackets on two sides, angle between, circle third side
const tri = Sh.recognise([poly([[100, 300], [340, 300], [180, 120]])]);
const model = Sh.buildModel(tri.type, tri.fit, Sh.bbox([[100, 300], [340, 300], [180, 120]]));
console.log('parts', model.parts.map((p) => p.v + ':' + p.kind).join(' '));
const marks = [
  { id: 900, pts: line([100, 320], [340, 320]) },             // bracket under base c
  ...S.write('8', 210, 330, 30).strokes,                       // "8" under it
  { id: 901, pts: line([90, 290], [170, 110]) },              // bracket along left side b
  ...S.write('5', 95, 170, 30).strokes,
  { id: 902, pts: ell(100, 300, 40, 40, 300, 360, 12) },      // small arc at angle A
  ...S.write('60', 150, 255, 18).strokes,
];
const finds = [{ id: 903, pts: ell(265, 205, 35, 35) }];       // circle side a
const ann = Sh.annotate(model, marks, finds, {});
console.log(ann.brackets.map(b=>[b.part&&b.part.v, !!b.number]), ann.clusters.map(c=>[c.text,c.part&&c.part.v, !!c.bracket]));
console.log('values', JSON.stringify(Object.fromEntries(Object.entries(ann.values).map(([k, v]) => [k, v.value]))), 'target', ann.target && (ann.target.part ? ann.target.part.v : 'whole'), 'issues', ann.issues.map((i) => i.kind));
try {
  assert.deepStrictEqual(Object.fromEntries(Object.entries(ann.values).map(([k, v]) => [k, v.value])), { c: 8, b: 5, A: 60 });
  assert.strictEqual(ann.target.part.v, 'a');
  const r = M.solveFor({ equations: model.eqs.map((e) => M.parse(e, { words: true })), knowns: { c: 8, b: 5, A: 60 }, target: 'a', domains: { A: 'angle', B: 'angle', C: 'angle' }, defaultDomain: 'positive' });
  console.log('a =', r.value, r.steps.map((s) => s.v));
  pass++;
} catch (e) { fail++; console.log('FAIL annotate', e.message); }

// cylinder: bracket the right side (h=10), bracket the top radius (r=3), circle the whole thing → volume
(function () {
  const strokes = [ell(200, 100, 80, 22), line([120, 100], [120, 300]), line([280, 100], [280, 300]), ell(200, 300, 80, 22, 0, 180)];
  const rec = Sh.recognise(strokes);
  const model = Sh.buildModel(rec.type, rec.fit, Sh.bbox(strokes.flat()));
  const marks = [{ id: 950, pts: line([300, 110], [300, 300]) }, ...S.write('10', 310, 190, 26).strokes,
    { id: 951, pts: line([205, 80], [280, 80]) }, ...S.write('3', 230, 40, 26).strokes];
  const ann = Sh.annotate(model, marks, [{ id: 952, pts: ell(200, 200, 150, 160) }], {});
  const vals = Object.fromEntries(Object.entries(ann.values).map(([k, v]) => [k, v.value]));
  try {
    assert.deepStrictEqual(vals, { h: 10, r: 3 });
    assert.ok(ann.target.whole);
    const r = M.solveFor({ equations: model.eqs.map((e) => M.parse(e, { words: true })), knowns: vals, target: 'Volume', defaultDomain: 'positive' });
    assert.ok(Math.abs(r.value - Math.PI * 90) < 1e-6);
    pass++;
  } catch (e) { fail++; console.log('FAIL cylinder annotate', e.message, vals, ann.issues.map((i) => i.kind)); }
})();
// box: brackets on the three edge directions
(function () {
  const F2 = [[100, 300], [350, 300], [350, 200], [100, 200]], B2 = F2.map((p) => [p[0] + 60, p[1] - 50]);
  const strokes = [poly(F2), line(F2[3], B2[3]), line(F2[2], B2[2]), line(F2[1], B2[1]), line(B2[3], B2[2]), line(B2[2], B2[1])];
  const rec = Sh.recognise(strokes);
  const model = Sh.buildModel(rec.type, rec.fit, Sh.bbox(strokes.flat()));
  const marks = [{ id: 960, pts: line([100, 320], [350, 320]) }, ...S.write('8', 220, 330, 26).strokes,
    { id: 961, pts: line([80, 300], [80, 200]) }, ...S.write('2', 50, 235, 26).strokes,
    { id: 962, pts: line([370, 310], [425, 262]) }, ...S.write('3', 410, 290, 26).strokes];
  const ann = Sh.annotate(model, marks, [], {});
  const vals = Object.fromEntries(Object.entries(ann.values).map(([k, v]) => [k, v.value]));
  try { assert.deepStrictEqual(vals, { l: 8, h: 2, w: 3 }); pass++; } catch (e) { fail++; console.log('FAIL box annotate', e.message, vals); }
})();
console.log(`${pass} passed, ${fail} failed`);
