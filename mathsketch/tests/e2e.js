// Browser test: draws equations and shapes with the mouse and checks the answers.
// Run:  python3 -m http.server 8765   (in mathsketch/)   then   node tests/e2e.js [screenshot-dir]
const { chromium } = require('playwright');
const OUT = process.argv[2] || '.';
const URL = 'http://localhost:8765/index.html';

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(URL);
  await page.evaluate(() => localStorage.clear());
  await page.reload();

  async function stroke(pts, sel) {
    const box = await page.locator(sel + ' canvas').boundingBox();
    await page.mouse.move(box.x + pts[0][0], box.y + pts[0][1]);
    await page.mouse.down();
    for (const p of pts.slice(1)) await page.mouse.move(box.x + p[0], box.y + p[1]);
    await page.mouse.up();
  }
  // "Handwriting" made from the built-in templates, with a little wobble.
  async function glyphs(str, x, y, h, sel) {
    const strokes = await page.evaluate(([str, x, y, h]) => {
      const W = { '1': 0.35, '-': 0.7, '=': 0.7, '+': 0.8, 'x': 0.8 };
      const out = [];
      let sup = false, s = 1;
      const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647;
      for (const ch of str) {
        if (ch === '^') { sup = true; continue; }
        const small = '-=+'.includes(ch);
        const hh = sup ? h * 0.55 : small ? h * 0.6 : h;
        const yy = sup ? y - h * 0.3 : small ? y + h * 0.2 : y;
        for (const st of Recognizer.BASE[ch][0]) out.push(st.map(([px, py]) => [x + hh * px + (rnd() - 0.5) * 1.5, yy + hh * py + (rnd() - 0.5) * 1.5]));
        x += hh * (W[ch] || 0.75) + h * 0.3; sup = false;
      }
      return out;
    }, [str, x, y, h]);
    for (const st of strokes) await stroke(st, sel);
  }
  const ring = (cx, cy, rx, ry) => Array.from({ length: 41 }, (_, i) => { const a = -Math.PI / 2 + (i / 40) * Math.PI * 2.08; return [cx + rx * Math.cos(a), cy + ry * Math.sin(a)]; });
  const line = (a, b) => Array.from({ length: 21 }, (_, k) => [a[0] + ((b[0] - a[0]) * k) / 20, a[1] + ((b[1] - a[1]) * k) / 20]);
  const text = async (sel) => (await page.locator(sel).innerText()).replace(/\n+/g, ' | ');

  // ---- Equation: 2x+3=11, then circle the x
  await glyphs('2x+3=11', 80, 120, 60, '#eq-board');
  await page.waitForTimeout(150);
  console.log('read:', await page.evaluate(() => SketchSolve.state.eq.A.lines.map((l) => l.text)));
  console.log('quick:', await text('#eq-answer'));
  await stroke(ring(160, 150, 42, 48), '#eq-board');
  await page.waitForTimeout(200);
  console.log('circled x:', await text('#eq-answer'));
  await page.screenshot({ path: OUT + '/eq1.png' });

  // ---- Not enough information: x+y=10, circle x
  await page.click('#view-eq [data-act="clear"]');
  await glyphs('x+y=10', 80, 120, 60, '#eq-board');
  await page.waitForTimeout(100);
  console.log('read2:', await page.evaluate(() => SketchSolve.state.eq.A.lines.map((l) => l.text)));
  await stroke(ring(105, 150, 40, 48), '#eq-board');
  await page.waitForTimeout(200);
  console.log('under:', await text('#eq-answer'));
  await page.screenshot({ path: OUT + '/eq2.png' });

  // ---- Shape: triangle, two brackets with numbers, circle the third side
  await page.click('[data-tab="shape"]');
  await page.waitForTimeout(100);
  const T = [[150, 420], [520, 420], [280, 140]];
  const tri = [];
  for (let i = 0; i < 3; i++) for (let k = 0; k <= 20; k++) { const a = T[i], b = T[(i + 1) % 3]; tri.push([a[0] + ((b[0] - a[0]) * k) / 20 + Math.random() * 2, a[1] + ((b[1] - a[1]) * k) / 20 + Math.random() * 2]); }
  tri.push(T[0]);
  await stroke(tri, '#shape-board');
  await page.waitForTimeout(1700);
  console.log('tool after auto switch:', await page.evaluate(() => SketchSolve.shapeBoard.tool), '| toast:', await page.locator('#shape-toast').innerText());
  await stroke(line([150, 445], [520, 445]), '#shape-board');
  await glyphs('9', 320, 455, 34, '#shape-board');
  await stroke(line([130, 405], [262, 130]), '#shape-board');
  await glyphs('6', 150, 230, 34, '#shape-board');
  await page.click('#view-shape [data-tool="find"]');
  await stroke(ring(410, 280, 45, 45), '#shape-board');
  await page.waitForTimeout(250);
  console.log('shape (two sides):', await text('#shape-answer'));
  // add the angle at A: a small arc inside the corner, then its number
  await page.click('#view-shape [data-tool="mark"]');
  const arc = Array.from({ length: 13 }, (_, i) => { const a = (-62 + (62 * i) / 12) * Math.PI / 180; return [150 + 55 * Math.cos(a), 420 + 55 * Math.sin(a)]; });
  await stroke(arc, '#shape-board');
  await glyphs('5', 222, 375, 22, '#shape-board');
  await glyphs('0', 240, 375, 22, '#shape-board');
  await page.waitForTimeout(250);
  console.log('shape (SAS):', await text('#shape-answer'));
  await page.screenshot({ path: OUT + '/shape1.png' });
  // circle the whole shape → area
  await page.click('#view-shape [data-tool="find"]');
  await stroke(ring(335, 290, 260, 210), '#shape-board');
  await page.waitForTimeout(250);
  console.log('shape (whole):', await text('#shape-answer'));

  // ---- Library: Ohm's law
  await page.click('[data-tab="lib"]');
  await page.fill('#lib-search', 'ohm');
  await page.click('.lib-card .head');
  await page.fill('[data-lv="I"]', '2');
  await page.waitForTimeout(100);
  console.log('lib partial:', await text('[data-res]'));
  await page.fill('[data-lv="R"]', '6');
  await page.waitForTimeout(100);
  console.log('lib full:', await text('[data-res]'));
  await page.screenshot({ path: OUT + '/lib.png' });

  // ---- Phone layout
  const m = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  await m.goto(URL);
  await m.waitForTimeout(200);
  await m.screenshot({ path: OUT + '/mobile.png', fullPage: true });
  console.log('phone horizontal overflow:', await m.evaluate(() => document.documentElement.scrollWidth > window.innerWidth));
  console.log('errors:', errors);
  await browser.close();
})();
