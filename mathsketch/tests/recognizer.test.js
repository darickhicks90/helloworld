const R = require('../js/recognizer.js');
let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
// Distort a template like a hand would: jitter, slight rotation, random scale, wobble.
function hand(strokes) {
  const rot = (rnd() - 0.5) * 0.25, sc = 40 + rnd() * 30, sx = 0.85 + rnd() * 0.3;
  return strokes.map((s) => {
    const out = [];
    for (let i = 0; i < s.length; i++) {
      const [x, y] = s[i];
      for (let k = 0; k < 3; k++) {
        const nx = i + 1 < s.length ? x + (s[i + 1][0] - x) * k / 3 : x, ny = i + 1 < s.length ? y + (s[i + 1][1] - y) * k / 3 : y;
        const X = nx * sx, Y = ny;
        out.push([100 + sc * (X * Math.cos(rot) - Y * Math.sin(rot)) + (rnd() - .5) * 1.5, 100 + sc * (X * Math.sin(rot) + Y * Math.cos(rot)) + (rnd() - .5) * 1.5]);
        if (i + 1 >= s.length) break;
      }
    }
    return out;
  });
}
let ok = 0, tot = 0; const conf = {};
for (const [label, variants] of Object.entries(R.BASE)) {
  for (const v of variants) for (let k = 0; k < 6; k++) {
    const res = R.recognize(hand(v));
    tot++;
    if (res[0].label === label) ok++; else conf[label + '→' + res[0].label] = (conf[label + '→' + res[0].label] || 0) + 1;
  }
}
console.log(`self-consistency ${ok}/${tot} = ${(100 * ok / tot).toFixed(1)}%`, conf);
