const L = require('../js/layout.js');
const S = require('./synth.js');
const assert = require('assert');
let pass = 0, fail = 0;
function t(name, f) { try { f(); pass++; } catch (e) { fail++; console.log('FAIL', name, e.message); } }
const read = (strokes) => L.analyze(strokes).lines.map((l) => l.text);
t('linear', () => assert.deepStrictEqual(read(S.write('2x+3=11', 50, 50).strokes), ['2x+3=11']));
t('power', () => assert.deepStrictEqual(read(S.write('x^2+1', 50, 50).strokes), ['x^(2)+1']));
t('two lines', () => assert.deepStrictEqual(read([...S.write('y=2x', 50, 50).strokes, ...S.write('x=3', 50, 150).strokes]), ['y=2x', 'x=3']));
t('decimal', () => assert.deepStrictEqual(read(S.write('2.5', 50, 50).strokes), ['2.5']));
t('fraction', () => {
  const num = S.write('1', 100, 30).strokes, den = S.write('2', 95, 100).strokes;
  const left = S.write('x=', 20, 60).strokes;
  assert.deepStrictEqual(read([...left, ...num, S.bar(85, 135, 88), ...den]), ['x=(1)/(2)']);
});
t('balance', () => { assert.deepStrictEqual(L.balanceRange('(1+2)/(3)', 1, 4), [1, 4]); assert.deepStrictEqual(L.balanceRange('(1+2)/(3)', 0, 3), [0, 9]); assert.deepStrictEqual(L.balanceRange('x^(2)+1', 0, 1), [0, 5]); });
console.log(`${pass} passed, ${fail} failed`);
