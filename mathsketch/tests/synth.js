// Turns symbols into fake "handwritten" strokes using the built-in templates.
const R = require('../js/recognizer.js');
let seed = 11; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
let nextId = 1;
function glyph(label, x, y, h, variant = 0) {
  const v = R.BASE[label][variant % R.BASE[label].length];
  const rot = (rnd() - 0.5) * 0.12;
  return v.map((s) => ({ id: nextId++, pts: s.map(([px, py]) => [x + h * (px * Math.cos(rot) - py * Math.sin(rot)) + (rnd() - .5), y + h * (px * Math.sin(rot) + py * Math.cos(rot)) + (rnd() - .5)]) }));
}
const WIDTH = { '1': .35, '-': .7, '=': .7, '+': .8, '(': .5, ')': .5, '.': .2, 'x': .8, '√': 1.5 };
// Write a flat string left-to-right; '^' raises the next char.
function write(str, x, y, h = 40) {
  const out = []; let sup = false;
  for (const ch of str) {
    if (ch === '^') { sup = true; continue; }
    if (ch === ' ') { x += h * 0.4; continue; }
    if (ch === '.') { out.push({ id: nextId++, pts: [[x, y + h * 0.95], [x + 1, y + h * 0.97], [x + 2, y + h]] }); x += h * 0.3; continue; }
    const hh = sup ? h * 0.55 : (ch === '-' || ch === '=' || ch === '+') ? h * 0.6 : h;
    const yy = sup ? y - h * 0.3 : (ch === '-' || ch === '=' || ch === '+') ? y + h * 0.2 : y;
    out.push(...glyph(ch, x, yy, hh));
    x += hh * (WIDTH[ch] || 0.75) + h * 0.25; sup = false;
  }
  return { strokes: out, x };
}
function bar(x0, x1, y) { return { id: nextId++, pts: Array.from({ length: 10 }, (_, i) => [x0 + (x1 - x0) * i / 9, y + (rnd() - .5)]) }; }
module.exports = { glyph, write, bar, newId: () => nextId++ };
