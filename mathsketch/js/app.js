/* SketchSolve — the app: wires the boards, recognisers, solver and panels. */
(function () {
  'use strict';
  const M = window.MathCore, R = window.Recognizer, Lay = window.Layout, Sh = window.Shapes, FL = window.Formulas;
  const $ = (s, el) => (el || document).querySelector(s);
  const $$ = (s, el) => [...(el || document).querySelectorAll(s)];
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const fmt = (v) => M.fmt(v);
  const approx = (v) => (Math.abs(v - Math.round(v)) < 1e-9 * Math.max(1, Math.abs(v)) ? '=' : '≈');

  /* ======================= state & storage ======================= */
  const STORE = 'sketchsolve.state.v1';
  const state = {
    tab: 'eq',
    settings: { deg: true, learn: true },
    eq: { overrides: {}, typed: [], chipTarget: null, A: null },
    shape: { typeOverride: null, manual: {}, manualTarget: null, wholeChoice: null, autoSwitched: false, A: null }
  };
  let saved = null;
  try { saved = JSON.parse(localStorage.getItem(STORE) || 'null'); } catch (e) { saved = null; }
  if (saved) {
    Object.assign(state.settings, saved.settings || {});
    if (saved.eq) { state.eq.overrides = saved.eq.overrides || {}; state.eq.typed = saved.eq.typed || []; }
    if (saved.shape) {
      state.shape.typeOverride = saved.shape.typeOverride || null;
      state.shape.manual = saved.shape.manual || {};
      state.shape.manualTarget = saved.shape.manualTarget || null;
      state.shape.wholeChoice = saved.shape.wholeChoice || null;
      state.shape.autoSwitched = !!saved.shape.autoSwitched;
    }
    if (saved.tab) state.tab = saved.tab;
  }
  try { R.loadUser(); } catch (e) { /* no storage */ }

  let saveTimer = null;
  function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      const pack = (b) => b.strokes.map((s) => ({ id: s.id, kind: s.kind, pts: s.pts.map((p) => [Math.round(p[0] * 10) / 10, Math.round(p[1] * 10) / 10]) }));
      const data = {
        tab: state.tab,
        settings: state.settings,
        eq: { strokes: pack(eqBoard), overrides: state.eq.overrides, typed: state.eq.typed },
        shape: {
          strokes: pack(shapeBoard), typeOverride: state.shape.typeOverride, manual: state.shape.manual,
          manualTarget: state.shape.manualTarget, wholeChoice: state.shape.wholeChoice, autoSwitched: state.shape.autoSwitched
        }
      };
      try { localStorage.setItem(STORE, JSON.stringify(data)); } catch (e) { /* storage full or blocked */ }
    }, 400);
  }

  /* ======================= colours ======================= */
  const C = {};
  function readColors() {
    const cs = getComputedStyle(document.documentElement);
    for (const k of ['ink', 'select', 'known', 'find', 'guide', 'muted', 'paper', 'text', 'warn']) C[k] = cs.getPropertyValue('--' + k).trim();
  }
  readColors();
  if (window.matchMedia) {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { readColors(); eqBoard.render(); shapeBoard.render(); });
  }

  /* ======================= popover ======================= */
  const pop = $('#popover');
  function openPop(anchor, html, bind) {
    pop.innerHTML = html;
    pop.hidden = false;
    const r = anchor.getBoundingClientRect ? anchor.getBoundingClientRect() : anchor;
    const pw = pop.offsetWidth, ph = pop.offsetHeight;
    let x = r.left + r.width / 2 - pw / 2, y = r.bottom + 8;
    if (y + ph > window.innerHeight - 8) y = r.top - ph - 8;
    x = Math.max(8, Math.min(window.innerWidth - pw - 8, x));
    y = Math.max(8, y);
    pop.style.left = x + 'px';
    pop.style.top = y + 'px';
    if (bind) bind(pop);
    const first = pop.querySelector('input');
    if (first) setTimeout(() => first.focus(), 0);
  }
  function closePop() { pop.hidden = true; pop.innerHTML = ''; }
  document.addEventListener('pointerdown', (e) => { if (!pop.hidden && !pop.contains(e.target)) closePop(); }, true);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closePop(); });

  /* ======================= tabs ======================= */
  function setTab(tab) {
    state.tab = tab;
    $$('.tabs button').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.tab === tab)));
    $$('.view').forEach((v) => { v.hidden = v.dataset.view !== tab; });
    closePop();
    save();
  }
  $$('.tabs button').forEach((b) => b.addEventListener('click', () => setTab(b.dataset.tab)));

  function bindToolbar(view, board, onTool) {
    $$('.tool', view).forEach((btn) => btn.addEventListener('click', () => setTool(view, board, btn.dataset.tool, onTool)));
    $$('[data-act]', view).forEach((btn) => btn.addEventListener('click', () => {
      const a = btn.dataset.act;
      if (a === 'undo') board.undo();
      else if (a === 'redo') board.redo();
      else if (a === 'clear') board.clear();
    }));
  }
  function setTool(view, board, tool, onTool) {
    board.tool = tool;
    $$('.tool', view).forEach((b) => b.classList.toggle('active', b.dataset.tool === tool));
    board.wrap.classList.toggle('erase', tool === 'erase');
    if (onTool) onTool(tool);
  }
  document.addEventListener('keydown', (e) => {
    if (!(e.ctrlKey || e.metaKey) || e.target.closest('input, textarea')) return;
    const board = state.tab === 'eq' ? eqBoard : state.tab === 'shape' ? shapeBoard : null;
    if (!board) return;
    const k = e.key.toLowerCase();
    if (k === 'z' && !e.shiftKey) { board.undo(); e.preventDefault(); }
    else if (k === 'y' || (k === 'z' && e.shiftKey)) { board.redo(); e.preventDefault(); }
  });

  /* ======================= shared result rendering ======================= */
  function stepsHTML(steps, name, unit) {
    const items = steps.map((s) => {
      if (s.system) {
        return `<li><small>Solved these together</small>${s.system.map((e) => M.showHTML(e.eq)).join('<br>')}</li>`;
      }
      const from = `<small>from ${M.showHTML(s.eq)}</small>`;
      const showRe = s.rearranged && s.rearranged.type !== 'num';
      const rhs = showRe ? `${s.plusMinus && s.value < 0 ? '−' : ''}${M.showHTML(s.rearranged)} ${approx(s.value)} ` : '= ';
      return `<li>${from}${name(s.v)} ${showRe ? '= ' : ''}${rhs}<b>${fmt(s.value)}${unit(s.v)}</b></li>`;
    });
    return items.length ? `<ol class="steps">${items.join('')}</ol>` : '';
  }

  function resultHTML(r, o) {
    const name = o.name || ((v) => M.nameOut(v, true));
    const unit = o.unit || (() => '');
    const t = name(r.target);
    switch (r.status) {
      case 'given':
        return `<div class="status ok">✓ You gave this</div><div class="result">${t} = ${fmt(r.value)}${unit(r.target)}</div>`;
      case 'solved':
        return `<div class="status ok">✓ Solved</div><div class="result">${t} ${approx(r.value)} ${fmt(r.value)}${unit(r.target)}</div>${o.steps === false ? '' : stepsHTML(r.steps, name, unit)}`;
      case 'multiple': {
        const vals = r.values.slice(0, 4).map((v) => `${t} ${approx(v)} ${fmt(v)}${unit(r.target)}`).join(' <span class="muted">or</span> ');
        return `<div class="status ok">✓ ${r.values.length > 4 ? 'Many' : r.values.length} possible answers</div><div class="result multi">${vals}</div>` +
          `<p class="muted">More than one value fits what you gave.${o.multiNote ? ' ' + o.multiNote : ''}</p>`;
      }
      case 'underdetermined': {
        let body = `<div class="status no">✋ Not enough information</div><p>I can't pin down <b>${t}</b> from what's here.</p>`;
        if (r.need && r.need.length) body += `<p>Give me any <b>one</b> of these as well:</p><div class="need">${r.need.map((v) => `<span>${name(v)}</span>`).join('')}</div>`;
        else body += `<p>You need <b>at least two more</b> values. Still unknown: ${r.unknowns.map(name).join(', ')}.</p>`;
        return body;
      }
      case 'no-equation':
        return `<div class="status no">✋ Not enough information</div><p><b>${t}</b> isn't connected to anything else, so there's nothing to work it out from.</p>`;
      case 'inconsistent':
        return `<div class="status no">⚠ These values don't agree</div><p>With the values given, this can't be true:</p><ol class="steps">${r.conflicts.map((c) => `<li>${M.showHTML(c.eq)}</li>`).join('')}</ol><p class="muted">Check for a misread number or a typo.</p>`;
      case 'no-solution':
        return `<div class="status no">⚠ No answer fits</div><p>No real value of <b>${t}</b> works${r.eq ? ` in ${M.showHTML(r.eq)}` : ''}.</p>${o.noSolutionNote ? `<p class="muted">${o.noSolutionNote}</p>` : ''}`;
    }
    return '';
  }

  // Library formulas that could connect a target to values that are known.
  function suggestFormulas(target, knownNames) {
    const out = [];
    for (const f of FL.LIST) {
      const vs = Object.keys(f.vars);
      if (!vs.includes(target)) continue;
      const others = vs.filter((v) => v !== target);
      const have = others.filter((v) => knownNames.has(v) || f.vars[v][2] !== undefined).length;
      if (have === others.length) out.push({ f, score: 2 });
      else if (have >= 1 && others.length - have === 1) out.push({ f, score: 1 });
    }
    return out.sort((a, b) => b.score - a.score).slice(0, 4).map((o) => o.f);
  }

  /* ======================= EQUATIONS ======================= */
  const eqView = $('#view-eq');
  const eqCache = new Map();
  const eqBoard = new window.InkBoard($('#eq-board'), {
    kindFor: (tool) => (tool === 'select' ? 'select' : 'ink'),
    onStroke: (s, b) => {
      if (s.kind === 'ink' && looksLikeSelection(s, b.strokes)) s.kind = 'select';
      if (s.kind === 'select') {
        b.strokes = b.strokes.filter((o) => o.kind !== 'select' || o === s);
        state.eq.chipTarget = null;
      }
    },
    onChange: () => { scheduleEq(); save(); },
    styleFor: (s) => {
      if (s.kind === 'select') return { color: C.select, width: 2.4, dash: [8, 6] };
      const A = state.eq.A;
      if (A && A.circledIds && A.circledIds.has(s.id)) return { color: C.select, width: 2.8 };
      return { color: C.ink, width: 2.6 };
    }
  });
  bindToolbar(eqView, eqBoard);

  function looksLikeSelection(s, strokes) {
    const b = Sh.bbox(s.pts);
    const sd = Math.hypot(b.w, b.h);
    const len = R.pathLen(s.pts);
    const chord = Math.hypot(s.pts[0][0] - s.pts[s.pts.length - 1][0], s.pts[0][1] - s.pts[s.pts.length - 1][1]);
    if (sd < 40 || !(chord < Math.max(0.35 * sd, 16) && len > 1.8 * sd)) return false;
    let enclosed = 0;
    for (const o of strokes) {
      if (o === s || o.kind !== 'ink') continue;
      const inside = o.pts.filter((p) => Sh.pointInPoly(p, s.pts)).length;
      if (inside / o.pts.length >= 0.6) enclosed++;
    }
    return enclosed >= 1;
  }

  let eqTimer = null;
  function scheduleEq() { clearTimeout(eqTimer); eqTimer = setTimeout(analyzeEq, 30); }

  function parseLine(text, words) {
    try { return { ast: M.parse(text, { words }) }; } catch (e) { return { err: e.message }; }
  }

  function analyzeEq() {
    const strokes = eqBoard.strokes;
    const ink = strokes.filter((s) => s.kind === 'ink');
    const sel = strokes.filter((s) => s.kind === 'select').pop() || null;
    const byId = new Map(strokes.map((s) => [s.id, s]));
    const lay = Lay.analyze(ink, { overrides: state.eq.overrides, cache: eqCache });
    const lines = lay.lines.map((l) => Object.assign(l, parseLine(l.text, false)));
    const typed = state.eq.typed.map((t, i) => Object.assign({ i, text: t.text, words: !!t.words }, parseLine(t.text, !!t.words)));
    const A = { lines, typed, sel, byId, circledIds: new Set(), circled: null };

    // what was circled?
    if (sel) {
      let best = null;
      for (const l of lines) {
        const inside = l.syms.filter((sym) => {
          const pts = sym.strokeIds.flatMap((id) => (byId.get(id) ? byId.get(id).pts : []));
          if (!pts.length) return false;
          return pts.filter((p) => Sh.pointInPoly(p, sel.pts)).length / pts.length >= 0.5;
        });
        if (inside.length && (!best || inside.length > best.syms.length)) best = { line: l, syms: inside };
      }
      if (best) {
        let s = Math.min(...best.syms.map((x) => x.start)), e = Math.max(...best.syms.map((x) => x.end));
        [s, e] = Lay.balanceRange(best.line.text, s, e);
        const text = best.line.text.slice(s, e);
        const inRange = best.line.syms.filter((x) => x.start >= s && x.end <= e);
        inRange.forEach((x) => x.strokeIds.forEach((id) => A.circledIds.add(id)));
        A.circled = { line: best.line, text, syms: new Set(inRange.map((x) => x.key)), parsed: parseLine(text.replace(/^[+*/=]+|[+\-*/=]+$/g, '') || text, false) };
      }
    }
    A.result = eqSolve(A);
    state.eq.A = A;
    renderEqPanel(A);
    renderEqOverlay(A);
    eqBoard.render();
    $('#eq-hint').hidden = strokes.length > 0 || state.eq.typed.length > 0;
  }

  function eqEquations(A) {
    const eqs = [];
    A.lines.forEach((l, i) => { if (l.ast && l.ast.type === 'eq') eqs.push({ eq: l.ast, label: `line ${i + 1}` }); });
    A.typed.forEach((t) => { if (t.ast && t.ast.type === 'eq') eqs.push({ eq: t.ast, label: 'typed' }); });
    return eqs;
  }

  function directlyGiven(A) {
    const out = {};
    for (const l of [...A.lines, ...A.typed]) {
      const a = l.ast;
      if (a && a.type === 'eq' && a.lhs.type === 'var' && M.vars(a.rhs).size === 0) {
        const v = M.evaluate(a.rhs, {}, state.settings.deg);
        if (isFinite(v)) out[a.lhs.name] = v;
      }
    }
    return out;
  }

  function eqSolve(A) {
    const deg = state.settings.deg;
    const eqs = eqEquations(A);
    const solve = (target, extra) => M.solveFor({ equations: extra ? eqs.concat(extra) : eqs, target, deg });

    if (state.eq.chipTarget) {
      return { kind: 'var', name: state.eq.chipTarget, r: solve(state.eq.chipTarget) };
    }
    if (A.sel && !A.circled) return { kind: 'msg', html: '<p class="muted">The circle doesn\'t go around any writing. Draw it around a letter, a piece of an equation, or a whole line.</p>' };
    if (A.circled) {
      const P = A.circled.parsed;
      if (P.err) return { kind: 'msg', html: `<div class="status no">⚠ Can't read the circled part</div><p>I read it as <code>${esc(A.circled.text)}</code>: ${esc(P.err)}.</p><p class="muted">Tap symbols under “What I read” to fix them.</p>` };
      const ast = P.ast;
      if (ast.type === 'var' && !M.CONSTANTS[ast.name]) return { kind: 'var', name: ast.name, r: solve(ast.name) };
      if (ast.type === 'eq') {
        const vs = [...M.vars(ast)];
        if (!vs.length) {
          const l = M.evaluate(ast.lhs, {}, deg), r = M.evaluate(ast.rhs, {}, deg);
          return { kind: 'check', ok: Math.abs(l - r) <= 1e-9 * (Math.abs(l) + Math.abs(r) + 1), l, r, ast };
        }
        const extra = A.circled.text === A.circled.line.text ? null : [{ eq: ast, label: 'circled' }];
        return { kind: 'eq', results: vs.map((v) => solve(v, extra)) };
      }
      const q = { eq: { type: 'eq', lhs: M.vr('__Q'), rhs: ast }, label: 'circled' };
      return { kind: 'expr', ast, r: solve('__Q', [q]) };
    }
    // Nothing circled: give a quick answer when it's obvious.
    const exprs = [...A.lines, ...A.typed].filter((l) => l.ast && l.ast.type !== 'eq');
    if (!eqs.length) {
      const last = exprs[exprs.length - 1];
      if (last && M.vars(last.ast).size === 0) return { kind: 'calc', ast: last.ast, value: M.evaluate(last.ast, {}, deg) };
      return null;
    }
    const given = directlyGiven(A);
    const all = new Set();
    eqs.forEach((e) => M.vars(e.eq).forEach((v) => all.add(v)));
    const unknown = [...all].filter((v) => !(v in given));
    if (!unknown.length) return null;
    const results = unknown.slice(0, 8).map((v) => solve(v));
    return { kind: 'auto', results, unknown };
  }

  function renderEqPanel(A) {
    const box = $('#eq-lines');
    const html = [];
    if (!A.lines.length && !A.typed.length) html.push('<p class="muted">Start writing on the board.</p>');
    A.lines.forEach((l, li) => {
      const circled = A.circled && A.circled.line === l;
      const chips = l.syms.map((sym, si) => {
        const low = !sym.overridden && sym.cands && sym.cands.length > 1 && (sym.cands[0].score < 0.55 || sym.cands[0].score - sym.cands[1].score < 0.03) && !sym.cands[0].rule && !sym.cands[0].dot;
        const cls = ['sym', sym.overridden ? 'fixed' : '', low ? 'low' : '', circled && A.circled.syms.has(sym.key) ? 'in' : ''].join(' ');
        return `<button class="${cls}" data-line="${li}" data-sym="${si}" title="${sym.role ? esc(sym.role) : 'Tap to fix'}">${esc(sym.label === '·' ? '·' : sym.label)}</button>`;
      }).join('');
      html.push(`<div class="line${circled ? ' circled' : ''}"><div class="syms">${chips}</div>${lineBody(l, `l${li}`)}</div>`);
    });
    A.typed.forEach((t) => {
      html.push(`<div class="line"><div class="typed-tag"><span>typed</span><button class="x" data-untype="${t.i}" title="Remove">✕</button></div>${lineBody(t, `t${t.i}`)}</div>`);
    });
    box.innerHTML = html.join('');
    $$('.sym', box).forEach((b) => b.addEventListener('click', () => symMenu(b, A.lines[+b.dataset.line].syms[+b.dataset.sym])));
    $$('.varchip', box).forEach((b) => b.addEventListener('click', () => {
      state.eq.chipTarget = state.eq.chipTarget === b.dataset.var ? null : b.dataset.var;
      scheduleEq();
    }));
    $$('[data-untype]', box).forEach((b) => b.addEventListener('click', () => { state.eq.typed.splice(+b.dataset.untype, 1); save(); scheduleEq(); }));
    $('#eq-answer').innerHTML = eqAnswerHTML(A);
    $$('#eq-answer [data-use]').forEach((b) => b.addEventListener('click', () => {
      const f = FL.LIST.find((x) => x.id === b.dataset.use);
      state.eq.typed.push({ text: f.eq, words: true });
      save(); scheduleEq();
    }));
  }

  function lineBody(l, key) {
    if (l.err) return `<div class="parsed err">⚠ ${esc(l.err)}${l.text ? ` <span class="muted">(read as “${esc(l.text)}”)</span>` : ''}</div>`;
    const vs = [...M.vars(l.ast)];
    const chips = vs.length ? `<div class="varchips">solve for ${vs.map((v) => `<button class="varchip${state.eq.chipTarget === v ? ' on' : ''}" data-var="${esc(v)}">${M.nameOut(v, true)}</button>`).join('')}</div>` : '';
    return `<div class="parsed">${M.showHTML(l.ast)}</div>${chips}`;
  }

  function eqAnswerHTML(A) {
    const res = A.result;
    if (!res) return '<p class="muted">Write an equation, then circle what you want to solve for.</p>';
    if (res.kind === 'msg') return res.html;
    const known = new Set(Object.keys(directlyGiven(A)));
    const sugg = (target) => {
      const list = suggestFormulas(target, known);
      if (!list.length) return '';
      return `<div class="suggest"><b>Formulas that use ${M.nameOut(target, true)}:</b>${list.map((f) => `<button data-use="${f.id}">${esc(f.name)} — <span class="muted">${M.showHTML(M.parse(f.eq, { words: true }))}</span></button>`).join('')}</div>`;
    };
    if (res.kind === 'calc') return `<div class="status ok">✓ Worked out</div><div class="result">${M.showHTML(res.ast)} ${approx(res.value)} ${fmt(res.value)}</div>`;
    if (res.kind === 'check') return `<div class="status ${res.ok ? 'ok' : 'no'}">${res.ok ? '✓ True' : '✗ Not true'}</div><div class="result">${M.showHTML(res.ast)}</div><p class="muted">Left side ${fmt(res.l)}, right side ${fmt(res.r)}.</p>`;
    if (res.kind === 'var') {
      const bad = ['underdetermined', 'no-equation'].includes(res.r.status);
      return resultHTML(res.r, {}) + (bad ? sugg(res.name) : '');
    }
    if (res.kind === 'expr') {
      const r = res.r;
      if (r.status === 'solved' || r.status === 'multiple') {
        const vals = r.values.map((v) => fmt(v)).join(' or ');
        return `<div class="status ok">✓ Worked out the circled part</div><div class="result">${M.showHTML(res.ast)} ${approx(r.value)} ${vals}</div>${stepsHTML(r.steps.filter((s) => s.v !== '__Q'), (v) => M.nameOut(v, true), () => '')}`;
      }
      return resultHTML(r, {});
    }
    if (res.kind === 'eq') {
      const ok = res.results.filter((r) => ['solved', 'multiple', 'given'].includes(r.status));
      if (ok.length) return ok.map((r) => resultHTML(r, {})).join('<hr>');
      return resultHTML(res.results[0], {}) + sugg(res.results[0].target);
    }
    if (res.kind === 'auto') {
      const ok = res.results.filter((r) => r.status === 'solved' || r.status === 'multiple');
      const bad = res.results.filter((r) => !(r.status === 'solved' || r.status === 'multiple'));
      const conflict = res.results.find((r) => r.status === 'inconsistent');
      if (conflict) return resultHTML(conflict, {});
      let h = '';
      if (ok.length) {
        h += `<div class="status ok">✓ Quick answer</div>`;
        h += ok.map((r) => `<div class="result${r.values.length > 1 ? ' multi' : ''}">${r.values.map((v) => `${M.nameOut(r.target, true)} ${approx(v)} ${fmt(v)}`).join(' <span class="muted">or</span> ')}</div>`).join('');
      }
      if (bad.length) h += `<p class="muted">${ok.length ? 'Not enough information yet for' : 'Not enough information to solve for'} ${bad.map((r) => `<b>${M.nameOut(r.target, true)}</b>`).join(', ')}. Circle one to see what's missing.</p>`;
      return h + `<p class="muted">Circle a letter to see the working.</p>`;
    }
    return '';
  }

  function renderEqOverlay(A) {
    const ov = eqBoard.overlay;
    const parts = [];
    for (const l of A.lines) {
      for (const sym of l.syms) {
        if (sym.role) continue;
        parts.push(`<div class="eqlabel" style="left:${sym.box.cx}px;top:${sym.box.y0 - 3}px">${esc(sym.label)}</div>`);
      }
    }
    if (A.sel && A.result) {
      const b = Sh.bbox(A.sel.pts);
      let text = '', bad = false;
      const res = A.result;
      const short = (r) => {
        if (!r) return '';
        if (r.status === 'solved' || r.status === 'given') return `${M.nameOut(r.target)} ${approx(r.value)} ${fmt(r.value)}`;
        if (r.status === 'multiple') return `${M.nameOut(r.target)} ${approx(r.values[0])} ${r.values.slice(0, 3).map(fmt).join(' or ')}`;
        bad = true;
        return r.status === 'inconsistent' ? "values don't agree" : r.status === 'no-solution' ? 'no answer fits' : 'not enough info';
      };
      if (res.kind === 'var') text = short(res.r);
      else if (res.kind === 'expr') text = (res.r.status === 'solved' || res.r.status === 'multiple') ? `= ${res.r.values.map(fmt).join(' or ')}` : short(res.r);
      else if (res.kind === 'eq') { const ok = res.results.find((r) => r.status === 'solved' || r.status === 'multiple'); text = short(ok || res.results[0]); }
      else if (res.kind === 'check') { text = res.ok ? 'true ✓' : 'not true'; bad = !res.ok; }
      if (text) {
        // under the circle (or above it when there's no room below)
        const below = b.y1 + 40 < eqBoard.h;
        const x = Math.max(4, Math.min(b.cx - 40, eqBoard.w - 200));
        parts.push(`<div class="answer-tag${bad ? ' bad' : ''}" style="left:${x}px;top:${below ? b.y1 + 8 : Math.max(4, b.y0 - 38)}px">${esc(text)}</div>`);
      }
    }
    ov.innerHTML = parts.join('');
  }

  // Correct a symbol: pick another reading, split it, or type it.
  function symMenu(btn, sym) {
    if (sym.role) {
      openPop(btn, `<div class="pop-title">This is read as a ${esc(sym.role)}.</div><button class="row-btn" data-erase>🧽 Erase it</button>`, (p) => {
        $('[data-erase]', p).addEventListener('click', () => { eqBoard.removeStrokes(sym.strokeIds); closePop(); });
      });
      return;
    }
    const seen = new Set();
    const alts = [];
    for (const c of sym.cands || []) { if (!seen.has(c.label) && alts.length < 10) { seen.add(c.label); alts.push(c.label); } }
    for (const extra of ['x', '1', '2', '7', '+', '-', '=', '(', ')']) if (!seen.has(extra) && alts.length < 14) { seen.add(extra); alts.push(extra); }
    const html = `<div class="pop-title">I read this as <b>${esc(sym.label)}</b>. It's actually…</div>
      <div class="alts">${alts.map((a) => `<button data-l="${esc(a)}" class="${a === sym.label ? 'cur' : ''}">${esc(a)}</button>`).join('')}</div>
      <input data-other placeholder="something else? type it" maxlength="3" />
      ${sym.strokeIds.length > 1 ? '<button class="row-btn" data-split>✂️ These are separate symbols</button>' : ''}
      ${sym.overridden ? '<button class="row-btn" data-reset>↺ Use my handwriting again</button>' : ''}
      <button class="row-btn" data-erase>🧽 Erase it</button>`;
    openPop(btn, html, (p) => {
      const choose = (label) => {
        if (!label) return;
        state.eq.overrides[sym.key] = label;
        if (state.settings.learn && label !== '__split' && label.length <= 3) {
          const pts = sym.strokeIds.map((id) => eqBoard.strokes.find((s) => s.id === id)).filter(Boolean).map((s) => s.pts);
          if (pts.length) { R.teach(label, pts); eqCache.clear(); renderCounts(); }
        }
        closePop(); save(); scheduleEq();
      };
      $$('[data-l]', p).forEach((b) => b.addEventListener('click', () => choose(b.dataset.l)));
      const other = $('[data-other]', p);
      other.addEventListener('keydown', (e) => { if (e.key === 'Enter') choose(other.value.trim()); });
      const sp = $('[data-split]', p); if (sp) sp.addEventListener('click', () => choose('__split'));
      const rs = $('[data-reset]', p); if (rs) rs.addEventListener('click', () => { delete state.eq.overrides[sym.key]; closePop(); save(); scheduleEq(); });
      $('[data-erase]', p).addEventListener('click', () => { eqBoard.removeStrokes(sym.strokeIds); closePop(); });
    });
  }

  $('#eq-type').addEventListener('submit', (e) => {
    e.preventDefault();
    const inp = $('#eq-type-input');
    const text = inp.value.trim();
    if (!text) return;
    state.eq.typed.push({ text, words: false });
    inp.value = '';
    save(); scheduleEq();
  });

  /* ======================= SHAPES ======================= */
  const shapeView = $('#view-shape');
  const shapeCache = new Map();
  const eqParseCache = {};
  let autoTimer = null;
  const shapeBoard = new window.InkBoard($('#shape-board'), {
    kindFor: (tool) => ({ shape: 'shape', mark: 'mark', find: 'find' }[tool] || 'shape'),
    onStroke: (s) => {
      if (s.kind === 'find') state.shape.manualTarget = null;
      if (s.kind === 'mark') {
        // a big closed loop drawn with the Known tool is a "find" circle
        const b = Sh.bbox(s.pts), sd = Math.hypot(b.w, b.h);
        const chord = Math.hypot(s.pts[0][0] - s.pts[s.pts.length - 1][0], s.pts[0][1] - s.pts[s.pts.length - 1][1]);
        if (chord < Math.max(0.3 * sd, 12) && R.pathLen(s.pts) > 1.8 * sd && state.shape.A && state.shape.A.model && sd > Math.max(0.1 * state.shape.A.model.diag, 40)) state.shape.manualTarget = null;
      }
      if (s.kind === 'shape') {
        clearTimeout(autoTimer);
        autoTimer = setTimeout(maybeAutoSwitch, 1400);
      }
    },
    onChange: () => { scheduleShape(); save(); },
    styleFor: (s) => {
      if (s.kind === 'find') return { color: C.find, width: 2.6 };
      if (s.kind === 'mark') {
        const A = state.shape.A;
        if (A && A.ann && A.ann.finds.some((f) => f.stroke && f.stroke.id === s.id)) return { color: C.find, width: 2.6 };
        return { color: C.known, width: 2.6 };
      }
      return { color: C.ink, width: 2.8 };
    },
    drawBelow: (ctx) => drawShapeGuides(ctx),
    drawAbove: (ctx) => drawShapeHighlights(ctx)
  });
  bindToolbar(shapeView, shapeBoard, () => { clearTimeout(autoTimer); });
  shapeBoard.tool = 'shape';

  // Type picker
  const typeSel = $('#shape-type');
  typeSel.innerHTML = '<option value="">Auto — recognise my drawing</option>' + Sh.TYPES.map((t) => `<option value="${t}">${esc(Sh.DEFS[t].name)}</option>`).join('');
  typeSel.addEventListener('change', () => {
    state.shape.typeOverride = typeSel.value || null;
    state.shape.manualTarget = null;
    save(); scheduleShape();
  });

  function toast(msg) {
    const t = $('#shape-toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { t.hidden = true; }, 4200);
  }

  function maybeAutoSwitch() {
    const A = state.shape.A;
    if (shapeBoard.tool !== 'shape' || state.shape.autoSwitched || !A || !A.model) return;
    if (shapeBoard.strokes.some((s) => s.kind !== 'shape')) return;
    if (!A.rec || A.rec.confidence < 0.6) return;
    state.shape.autoSwitched = true;
    setTool(shapeView, shapeBoard, 'mark');
    toast(`Looks like a ${A.model.name.toLowerCase()} ✓  Now bracket what you know and write the numbers.`);
    save();
  }

  let shapeTimer = null;
  function scheduleShape() { clearTimeout(shapeTimer); shapeTimer = setTimeout(analyzeShape, 30); }

  function shapeEqs(type) {
    if (!eqParseCache[type]) eqParseCache[type] = Sh.DEFS[type].eqs.map((e) => ({ eq: M.parse(e, { words: true }), label: e }));
    return eqParseCache[type];
  }
  const varName = (v) => ({ Surface: 'Surface area', Lateral: 'Side area', theta: 'θ' }[v] || M.nameOut(v, true));
  function varUnit(model, v) {
    const d = model.vars.find((x) => x.name === v);
    const kind = d ? d.kind : (model.fixed[v] !== undefined ? 'angle' : 'length');
    return kind === 'angle' ? '°' : kind === 'area' ? ' sq units' : kind === 'volume' ? ' cubic units' : '';
  }

  function analyzeShape() {
    const S = state.shape;
    const strokes = shapeBoard.strokes;
    const shapeStrokes = strokes.filter((s) => s.kind === 'shape');
    const A = { strokes };
    if (!shapeStrokes.length) {
      S.autoSwitched = false;
      state.shape.A = A;
      renderShapePanel(A);
      shapeBoard.overlay.innerHTML = '';
      shapeBoard.render();
      $('#shape-hint').hidden = strokes.length > 0;
      return;
    }
    $('#shape-hint').hidden = true;
    A.rec = Sh.recognise(shapeStrokes.map((s) => s.pts));
    const type = S.typeOverride || (A.rec && A.rec.type);
    if (type) {
      const B = Sh.bbox(shapeStrokes.flatMap((s) => s.pts));
      const fit = A.rec && A.rec.type === type ? A.rec.fit : null;
      A.model = Sh.buildModel(type, fit, B);
      A.ann = Sh.annotate(A.model, strokes.filter((s) => s.kind === 'mark'), strokes.filter((s) => s.kind === 'find'), { cache: shapeCache });
      const model = A.model;
      const manual = S.manual[type] || {};
      A.known = Object.assign({}, model.fixed);
      A.source = {};
      for (const [v, o] of Object.entries(A.ann.values)) { A.known[v] = o.value; A.source[v] = 'ink'; }
      for (const [v, val] of Object.entries(manual)) { if (typeof val === 'number' && isFinite(val)) { A.known[v] = val; A.source[v] = 'typed'; } }
      // target
      const varNames = model.vars.map((x) => x.name);
      let target = null;
      A.whole = false;
      if (S.manualTarget && varNames.includes(S.manualTarget)) target = S.manualTarget;
      else if (A.ann.target) {
        if (A.ann.target.whole) { A.whole = true; target = model.whole.includes(S.wholeChoice) ? S.wholeChoice : model.whole[0]; }
        else if (A.ann.target.part) target = A.ann.target.part.v;
      }
      A.target = target;
      if (target) {
        const domains = {};
        for (const d of model.vars) domains[d.name] = d.kind === 'angle' ? 'angle' : 'positive';
        for (const k of Object.keys(model.fixed)) domains[k] = 'angle';
        A.result = M.solveFor({ equations: shapeEqs(type), knowns: A.known, target, domains, deg: true });
      }
    }
    state.shape.A = A;
    renderShapePanel(A);
    renderShapeOverlay(A);
    shapeBoard.render();
  }

  function drawShapeGuides(ctx) {
    const A = state.shape.A;
    if (!A || !A.model) return;
    const m = A.model;
    ctx.save();
    ctx.strokeStyle = C.guide;
    ctx.globalAlpha = m.guideOnly ? 0.9 : 0.45;
    ctx.lineWidth = m.guideOnly ? 2 : 1.5;
    ctx.setLineDash(m.guideOnly ? [6, 6] : []);
    for (const o of m.outline) {
      ctx.save();
      if (o.dash) ctx.setLineDash([5, 6]);
      ctx.beginPath();
      if (o.k === 'poly') { ctx.moveTo(o.pts[0][0], o.pts[0][1]); for (const p of o.pts.slice(1)) ctx.lineTo(p[0], p[1]); if (o.closed) ctx.closePath(); ctx.stroke(); }
      else if (o.k === 'line') { ctx.moveTo(o.a[0], o.a[1]); ctx.lineTo(o.b[0], o.b[1]); ctx.stroke(); }
      else if (o.k === 'ell') {
        if (o.backDash) {
          ctx.beginPath(); ctx.ellipse(o.c[0], o.c[1], o.rx, o.ry, 0, 0, Math.PI); ctx.stroke();
          ctx.setLineDash([5, 6]); ctx.beginPath(); ctx.ellipse(o.c[0], o.c[1], o.rx, o.ry, 0, Math.PI, 2 * Math.PI); ctx.stroke();
        } else { ctx.ellipse(o.c[0], o.c[1], o.rx, o.ry, 0, 0, 2 * Math.PI); ctx.stroke(); }
      }
      ctx.restore();
    }
    // dashed helper lines (height, radius…)
    ctx.globalAlpha = 0.75;
    ctx.setLineDash([4, 5]);
    ctx.lineWidth = 1.5;
    for (const p of m.parts) {
      if (p.kind !== 'seg' || !p.guide) continue;
      ctx.beginPath(); ctx.moveTo(p.a[0], p.a[1]); ctx.lineTo(p.b[0], p.b[1]); ctx.stroke();
    }
    // right-angle marks
    ctx.setLineDash([]);
    for (const p of m.parts) {
      if (p.kind === 'angle' && p.right) {
        const u = norm2(p.p, p.o), w = norm2(p.q, p.o), s = 12;
        ctx.beginPath();
        ctx.moveTo(p.o[0] + u[0] * s, p.o[1] + u[1] * s);
        ctx.lineTo(p.o[0] + (u[0] + w[0]) * s, p.o[1] + (u[1] + w[1]) * s);
        ctx.lineTo(p.o[0] + w[0] * s, p.o[1] + w[1] * s);
        ctx.stroke();
      }
    }
    ctx.restore();
  }
  function norm2(p, o) { const dx = p[0] - o[0], dy = p[1] - o[1], l = Math.hypot(dx, dy) || 1; return [dx / l, dy / l]; }

  function drawShapeHighlights(ctx) {
    const A = state.shape.A;
    if (!A || !A.model) return;
    ctx.save();
    ctx.lineCap = 'round';
    for (const p of A.model.parts) {
      const isT = A.target === p.v, isK = A.known && A.known[p.v] !== undefined && !A.model.fixed[p.v];
      if (!isT && !isK) continue;
      ctx.strokeStyle = isT ? C.find : C.known;
      ctx.globalAlpha = 0.28;
      ctx.lineWidth = 9;
      ctx.setLineDash([]);
      if (p.kind === 'seg') { ctx.beginPath(); ctx.moveTo(p.a[0], p.a[1]); ctx.lineTo(p.b[0], p.b[1]); ctx.stroke(); }
      else if (p.kind === 'angle') {
        const a0 = Math.atan2(p.p[1] - p.o[1], p.p[0] - p.o[0]), a1 = Math.atan2(p.q[1] - p.o[1], p.q[0] - p.o[0]);
        let d = a1 - a0; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI;
        ctx.beginPath(); ctx.arc(p.o[0], p.o[1], 22, a0, a0 + d, d < 0); ctx.stroke();
      } else if (p.kind === 'rim') { ctx.beginPath(); ctx.ellipse(p.c[0], p.c[1], p.rx, p.ry, 0, 0, 2 * Math.PI); ctx.stroke(); }
    }
    ctx.restore();
  }

  function renderShapeOverlay(A) {
    const ov = shapeBoard.overlay;
    if (!A.model) { ov.innerHTML = ''; return; }
    const m = A.model;
    const shown = new Set();
    const html = [];
    const solved = A.result && (A.result.status === 'solved' || A.result.status === 'given') ? A.result.value : null;
    for (const p of m.parts) {
      if (p.label === false || shown.has(p.v) || !p.labelPos) continue;
      shown.add(p.v);
      const isT = A.target === p.v;
      const fixed = m.fixed[p.v] !== undefined;
      const val = A.known[p.v];
      let cls = 'plabel', text = varName(p.v);
      if (fixed) { cls += ' fixed'; text += ` = ${fmt(val)}°`; }
      else if (isT && solved !== null) { cls += ' answer'; text += ` ${approx(solved)} ${fmt(solved)}${varUnit(m, p.v)}`; }
      else if (isT) { cls += ' target'; text += ' = ?'; }
      else if (val !== undefined) { cls += ' known'; text += ` = ${fmt(val)}${varUnit(m, p.v)}`; }
      const [x, y] = p.labelPos;
      html.push(`<button class="${cls}" data-part="${esc(p.v)}" style="left:${x}px;top:${y}px">${text}</button>`);
    }
    // whole-shape answer
    if (A.target && m.whole.includes(A.target) && !shown.has(A.target)) {
      const x = m.box.cx, y = Math.max(16, m.box.y0 - 18);
      const text = solved !== null ? `${varName(A.target)} ${approx(solved)} ${fmt(solved)}${varUnit(m, A.target)}` : `${varName(A.target)} = ?`;
      html.push(`<button class="plabel ${solved !== null ? 'answer' : 'target'}" data-part="${esc(A.target)}" style="left:${x}px;top:${y}px">${text}</button>`);
    }
    ov.innerHTML = html.join('');
    $$('[data-part]', ov).forEach((b) => b.addEventListener('click', () => partMenu(b, b.dataset.part)));
  }

  function partMenu(anchor, v) {
    const A = state.shape.A;
    if (!A || !A.model) return;
    const m = A.model;
    if (m.fixed[v] !== undefined) {
      openPop(anchor, `<div class="pop-title">This is a right angle (90°). Pick “Triangle” in the shape list if it isn't.</div>`);
      return;
    }
    const d = m.vars.find((x) => x.name === v);
    const manual = (state.shape.manual[m.type] || {})[v];
    const cur = A.known[v];
    openPop(anchor, `<div class="pop-title"><b>${varName(v)}</b> — ${esc(d ? d.desc : '')}</div>
      <input type="text" inputmode="decimal" data-val placeholder="type its value" value="${cur !== undefined && A.source[v] ? esc(String(+cur.toPrecision(10))) : ''}" />
      <button class="row-btn" data-set>✓ Use this value</button>
      <button class="row-btn" data-find>◯ Find this</button>
      ${manual !== undefined ? '<button class="row-btn" data-clear>↺ Clear typed value</button>' : ''}`, (p) => {
      const inp = $('[data-val]', p);
      const set = () => { setManual(v, inp.value); closePop(); };
      inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') set(); });
      $('[data-set]', p).addEventListener('click', set);
      $('[data-find]', p).addEventListener('click', () => { state.shape.manualTarget = v; closePop(); save(); scheduleShape(); });
      const c = $('[data-clear]', p); if (c) c.addEventListener('click', () => { setManual(v, ''); closePop(); });
    });
  }

  function setManual(v, raw) {
    const A = state.shape.A;
    const type = A.model.type;
    const S = state.shape;
    S.manual[type] = S.manual[type] || {};
    const txt = String(raw).trim();
    if (txt === '') delete S.manual[type][v];
    else {
      let val = NaN;
      try { val = M.evaluate(M.parse(txt), {}, true); } catch (e) { val = NaN; }
      if (!isFinite(val)) { toast(`“${txt}” isn't a number`); return; }
      S.manual[type][v] = val;
      if (S.manualTarget === v) S.manualTarget = null;
    }
    save(); scheduleShape();
  }

  function renderShapePanel(A) {
    const S = state.shape;
    typeSel.value = S.typeOverride || '';
    const auto = typeSel.options[0];
    auto.textContent = A.rec ? `Auto — looks like: ${Sh.DEFS[A.rec.type].name}` : 'Auto — recognise my drawing';
    const note = $('#shape-note');
    const parts = $('#shape-parts');
    const ans = $('#shape-answer');
    if (!A.model) {
      note.textContent = A.strokes && A.strokes.some((s) => s.kind === 'shape') ? "I can't tell what shape this is yet. Keep drawing, or pick it from the list." : '';
      parts.innerHTML = '';
      ans.innerHTML = '<p class="muted">Draw a shape to start.</p>';
      return;
    }
    const m = A.model;
    note.innerHTML = (m.guideOnly && !S.typeOverride ? 'Using a guide shape (dashed). ' : '') + (m.note ? esc(m.note) : '');
    const rows = [];
    for (const [v, val] of Object.entries(m.fixed)) {
      rows.push(`<div class="part fixedrow"><span class="pv">${varName(v)}</span><span class="pd">right angle</span><input value="${val}°" disabled aria-label="${v}" /><span></span></div>`);
    }
    for (const d of m.vars) {
      const isT = A.target === d.name;
      const src = A.source[d.name];
      const val = A.known[d.name];
      const shown = val !== undefined ? String(+val.toPrecision(10)) : '';
      rows.push(`<div class="part${isT ? ' target' : src ? ' known' : ''}">
        <span class="pv">${varName(d.name)}</span>
        <span class="pd" title="${esc(d.desc)}">${esc(d.desc)}</span>
        <input type="text" inputmode="decimal" data-v="${d.name}" class="${src === 'ink' ? 'ink' : ''}" value="${esc(shown)}" placeholder="${isT ? 'find' : '—'}" aria-label="${esc(d.desc)}" title="${src === 'ink' ? 'Read from your handwriting — type to change' : ''}" />
        <button class="findbtn${isT ? ' on' : ''}" data-find="${d.name}" title="Find ${esc(d.desc)}">◯</button></div>`);
    }
    parts.innerHTML = rows.join('');
    $$('input[data-v]', parts).forEach((inp) => inp.addEventListener('change', () => setManual(inp.dataset.v, inp.value)));
    $$('[data-find]', parts).forEach((b) => b.addEventListener('click', () => {
      S.manualTarget = S.manualTarget === b.dataset.find && A.target === b.dataset.find ? null : b.dataset.find;
      save(); scheduleShape();
    }));

    // answer
    let h = '';
    const issues = issueList(A);
    if (!A.target) {
      h = `<p class="muted">${Object.keys(A.ann.values).length ? 'Now circle what you want to find (step 3), or tap ◯ next to it.' : 'Bracket what you know and write the numbers, then circle what you want to find.'}</p>`;
    } else {
      if (A.whole) {
        h += `<div class="chips" style="margin-bottom:8px">${m.whole.map((w) => `<button data-whole="${w}" class="${w === A.target ? 'on' : ''}">${varName(w)}</button>`).join('')}</div>`;
      }
      h += resultHTML(A.result, {
        name: varName,
        unit: (v) => varUnit(m, v),
        multiNote: m.type.includes('riangle') ? 'Two different triangles match these measurements.' : '',
        noSolutionNote: m.type.includes('riangle') ? 'Those measurements can\'t make a triangle (for example, two sides must add up to more than the third).' : ''
      });
    }
    if (issues.length) h += `<ul class="issues">${issues.map((i) => `<li>${i}</li>`).join('')}</ul>`;
    ans.innerHTML = h;
    $$('[data-whole]', ans).forEach((b) => b.addEventListener('click', () => { S.wholeChoice = b.dataset.whole; save(); scheduleShape(); }));
  }

  function issueList(A) {
    const out = [];
    for (const i of A.ann.issues) {
      if (i.kind === 'bracket-no-number') out.push(`The bracket on <b>${varName(i.part)}</b> has no number next to it.`);
      else if (i.kind === 'bracket-unmatched') out.push('A bracket isn\'t lined up with any side. Draw it alongside a side, or as a small arc inside a corner.');
      else if (i.kind === 'unreadable') out.push(`I couldn't read “${esc(i.text)}” for <b>${varName(i.part)}</b>. Tap its label on the drawing to type it.`);
      else if (i.kind === 'stray-number') out.push(`I couldn't tell which part “${esc(i.text)}” belongs to. Write it right next to its bracket.`);
    }
    if (A.ann.finds.some((f) => !f.whole && !f.part)) out.push('Your circle isn\'t around any part of the shape. Circle a side, a corner, or the whole shape.');
    return out;
  }

  /* ======================= FORMULA LIBRARY ======================= */
  const lib = { cat: 'all', q: '', open: null, inputs: {}, target: {} };
  const libList = $('#lib-list');
  $('#lib-cats').innerHTML = [{ id: 'all', name: 'All' }].concat(FL.CATEGORIES).map((c) => `<button data-cat="${c.id}" class="${c.id === 'all' ? 'on' : ''}">${c.icon ? c.icon + ' ' : ''}${esc(c.name)}</button>`).join('');
  $$('#lib-cats button').forEach((b) => b.addEventListener('click', () => {
    lib.cat = b.dataset.cat;
    $$('#lib-cats button').forEach((x) => x.classList.toggle('on', x === b));
    renderLib();
  }));
  $('#lib-search').addEventListener('input', (e) => { lib.q = e.target.value.trim().toLowerCase(); renderLib(); });

  const parsedF = {};
  const fAst = (f) => (parsedF[f.id] = parsedF[f.id] || M.parse(f.eq, { words: true }));

  function renderLib() {
    const q = lib.q;
    const match = (f) => !q || (f.name + ' ' + f.eq + ' ' + Object.values(f.vars).map((v) => v[0] + ' ' + v[1]).join(' ')).toLowerCase().includes(q);
    const html = [];
    for (const c of FL.CATEGORIES) {
      if (lib.cat !== 'all' && lib.cat !== c.id) continue;
      const items = FL.LIST.filter((f) => f.cat === c.id && match(f));
      if (!items.length) continue;
      html.push(`<section class="lib-cat"><h2>${c.icon} ${esc(c.name)}</h2><div class="lib-grid">${items.map(cardHTML).join('')}</div></section>`);
    }
    libList.innerHTML = html.join('') || '<p class="muted">No formulas match that search.</p>';
    $$('.lib-card > .head', libList).forEach((b) => b.addEventListener('click', () => {
      lib.open = lib.open === b.dataset.id ? null : b.dataset.id;
      renderLib();
      if (lib.open) { const el = $(`.lib-card[data-id="${lib.open}"]`, libList); if (el) el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }
    }));
    if (lib.open) bindSolver(FL.LIST.find((f) => f.id === lib.open));
  }

  function cardHTML(f) {
    const open = lib.open === f.id;
    return `<article class="lib-card${open ? ' open' : ''}" data-id="${f.id}">
      <button class="head" data-id="${f.id}" aria-expanded="${open}"><div class="fname">${esc(f.name)}</div><div class="feq">${M.showHTML(fAst(f))}</div></button>
      ${open ? solverHTML(f) : ''}</article>`;
  }

  function libDomain(f, v) {
    const unit = f.vars[v][1];
    if (unit === '°') return 'angle';
    if (['geo2', 'geo3', 'chem', 'money', 'elec'].includes(f.cat)) return 'positive';
    if (f.cat === 'trig') return 'positive';
    return 'real';
  }

  function libTarget(f) {
    if (lib.target[f.id]) return lib.target[f.id];
    const ast = fAst(f);
    const lhs = [...M.vars(ast.lhs)];
    return lhs[0] || Object.keys(f.vars)[0];
  }

  function solverHTML(f) {
    const inputs = lib.inputs[f.id] = lib.inputs[f.id] || {};
    const target = libTarget(f);
    const rows = Object.entries(f.vars).map(([v, [desc, unit, def]]) => {
      const val = inputs[v] !== undefined ? inputs[v] : (def !== undefined ? String(def) : '');
      if (inputs[v] === undefined && def !== undefined) inputs[v] = String(def);
      const isT = v === target;
      return `<div class="lib-var${isT ? ' target' : ''}"><span class="sym2">${M.nameOut(v, true)}</span><span class="unit">${esc(unit)}</span><span class="desc">${esc(desc)}</span>
        <input type="text" inputmode="decimal" data-lv="${esc(v)}" value="${isT ? '' : esc(val)}" placeholder="${isT ? 'answer' : 'value'}" ${isT ? 'disabled' : ''} aria-label="${esc(desc)}" />
        <button class="findbtn${isT ? ' on' : ''}" data-lt="${esc(v)}" title="Find ${esc(desc)}">◯</button></div>`;
    }).join('');
    return `<div class="lib-solver">${f.note ? `<p class="note">${esc(f.note)}</p>` : ''}
      <p class="note">Fill in what you know. Tap ◯ next to what you want to find.</p>
      <div class="lib-vars">${rows}</div>
      <div class="lib-result answer" data-res></div>
      <div class="lib-actions"><button class="small" data-send>✍️ Use on the Equations board</button></div></div>`;
  }

  function bindSolver(f) {
    const card = $(`.lib-card[data-id="${f.id}"]`, libList);
    if (!card) return;
    const inputs = lib.inputs[f.id];
    const update = () => {
      const target = libTarget(f);
      const knowns = {};
      for (const [v, raw] of Object.entries(inputs)) {
        if (v === target || raw === undefined || String(raw).trim() === '') continue;
        try { const val = M.evaluate(M.parse(String(raw)), {}, true); if (isFinite(val)) knowns[v] = val; } catch (e) { /* not a number */ }
      }
      const domains = {};
      for (const v of Object.keys(f.vars)) domains[v] = libDomain(f, v);
      const r = M.solveFor({ equations: [{ eq: fAst(f), label: f.name }], knowns, target, domains, deg: state.settings.deg });
      const unit = (v) => { const u = f.vars[v] && f.vars[v][1]; return u ? (u === '°' ? '°' : ' ' + u) : ''; };
      const name = (v) => M.nameOut(v, true);
      $('[data-res]', card).innerHTML = Object.keys(knowns).length ? resultHTML(r, { name, unit }) : '<p class="muted">Fill in the values you know.</p>';
    };
    $$('[data-lv]', card).forEach((inp) => inp.addEventListener('input', () => { inputs[inp.dataset.lv] = inp.value; update(); }));
    $$('[data-lt]', card).forEach((b) => b.addEventListener('click', () => { lib.target[f.id] = b.dataset.lt; renderLib(); }));
    $('[data-send]', card).addEventListener('click', () => {
      state.eq.typed.push({ text: f.eq, words: true });
      const target = libTarget(f);
      for (const [v, raw] of Object.entries(inputs)) {
        if (v !== target && String(raw).trim() !== '') state.eq.typed.push({ text: `${v} = ${raw}`, words: true });
      }
      state.eq.chipTarget = target;
      save(); setTab('eq'); scheduleEq();
    });
    update();
  }

  /* ======================= TEACH ======================= */
  const teachLabels = [...new Set([...R.LABELS, ...'0123456789xyzabcnt'.split(''), 'r', 'h', 'V', 'I', 'R', 'P', 'A', 'θ'])].filter((l) => l !== '?');
  $('#teach-label').innerHTML = teachLabels.map((l) => `<option>${esc(l)}</option>`).join('');
  const teachBoard = new window.InkBoard($('#teach-pad'), { kindFor: () => 'ink', styleFor: () => ({ color: C.ink, width: 3 }) });
  $('#teach-clear').addEventListener('click', () => teachBoard.clear());
  $('#teach-save').addEventListener('click', () => {
    const label = $('#teach-custom').value.trim() || $('#teach-label').value;
    if (!teachBoard.strokes.length || !label) return;
    R.teach(label, teachBoard.strokes.map((s) => s.pts));
    eqCache.clear(); shapeCache.clear();
    teachBoard.clear();
    $('#teach-custom').value = '';
    renderCounts();
    scheduleEq(); scheduleShape();
  });
  $('#teach-forget').addEventListener('click', () => {
    if (!confirm('Forget every handwriting example you have taught SketchSolve?')) return;
    R.forgetAll(); eqCache.clear(); shapeCache.clear(); renderCounts(); scheduleEq(); scheduleShape();
  });
  function renderCounts() {
    const c = R.sampleCounts();
    const keys = Object.keys(c);
    $('#teach-counts').innerHTML = keys.length ? keys.sort().map((k) => `<span><b>${esc(k)}</b>${c[k]}</span>`).join('') : '<span class="muted">No examples yet.</span>';
  }
  renderCounts();

  const degBox = $('#set-deg'), learnBox = $('#set-learn');
  degBox.checked = state.settings.deg;
  learnBox.checked = state.settings.learn;
  degBox.addEventListener('change', () => { state.settings.deg = degBox.checked; save(); scheduleEq(); });
  learnBox.addEventListener('change', () => { state.settings.learn = learnBox.checked; save(); });

  /* ======================= start ======================= */
  if (saved && saved.eq && saved.eq.strokes) eqBoard.setStrokes(saved.eq.strokes);
  if (saved && saved.shape && saved.shape.strokes) shapeBoard.setStrokes(saved.shape.strokes);
  if (state.shape.A === null) analyzeShape();
  setTab(state.tab);
  renderLib();
  analyzeEq();
  analyzeShape();

  // for debugging / tests
  window.SketchSolve = { state, eqBoard, shapeBoard, analyzeEq, analyzeShape };
})();
