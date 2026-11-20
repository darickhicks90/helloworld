/* SketchSolve — math core
 *
 * Reads maths written as text (typed, or produced by the handwriting
 * recogniser), rearranges equations, and solves one equation or a whole
 * set of them. It also works out when there is NOT enough information to
 * pin an answer down, and which extra value would fix that.
 *
 * Works in the browser (window.MathCore) and in Node (require).
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.MathCore = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /* ------------------------------------------------------------------ *
   * Names
   * ------------------------------------------------------------------ */
  const FUNCS = ['asinh', 'acosh', 'atanh', 'asin', 'acos', 'atan', 'sinh', 'cosh', 'tanh',
    'sin', 'cos', 'tan', 'sqrt', 'cbrt', 'ln', 'log', 'abs', 'exp'];
  const ALIASES = { arcsin: 'asin', arccos: 'acos', arctan: 'atan' };
  const GREEK = {
    alpha: 'α', beta: 'β', gamma: 'γ', delta: 'δ', Delta: 'Δ', epsilon: 'ε', eta: 'η',
    theta: 'θ', lambda: 'λ', mu: 'μ', rho: 'ρ', sigma: 'σ', tau: 'τ', phi: 'φ',
    omega: 'ω', Omega: 'Ω'
  };
  const CONSTANTS = { pi: Math.PI, e: Math.E };
  const NAMES = [...FUNCS, ...Object.keys(ALIASES), ...Object.keys(GREEK), 'pi']
    .sort((a, b) => b.length - a.length);

  const isConst = (name) => Object.prototype.hasOwnProperty.call(CONSTANTS, name);

  /* ------------------------------------------------------------------ *
   * Tokenizer
   *   words:false (default) — handwriting style: "2xy" is 2·x·y, "R_1"
   *                           is one name, "sin" is the sine function.
   *   words:true            — formula style: "Area", "V_out" are names.
   * ------------------------------------------------------------------ */
  function normalise(src) {
    return String(src)
      .replace(/[×✕✖]/g, '*').replace(/[·⋅∙•]/g, '*').replace(/[÷∕]/g, '/')
      .replace(/[−–—]/g, '-').replace(/²/g, '^2').replace(/³/g, '^3')
      .replace(/√/g, ' sqrt ').replace(/π/g, ' pi ').replace(/[\[{]/g, '(').replace(/[\]}]/g, ')');
  }

  function tokenize(src, opts) {
    const words = !!(opts && opts.words);
    const s = normalise(src);
    const toks = [];
    const letterRe = /[A-Za-zͰ-Ͽ_]/;
    const wordRe = /[A-Za-z0-9_Ͱ-Ͽ]/;
    let i = 0;
    while (i < s.length) {
      const c = s[i];
      if (/\s/.test(c)) { i++; continue; }
      if (/[0-9.]/.test(c)) {
        let j = i, dot = false;
        while (j < s.length && /[0-9.]/.test(s[j])) {
          if (s[j] === '.') { if (dot) break; dot = true; }
          j++;
        }
        const txt = s.slice(i, j);
        if (txt === '.') throw new SyntaxError('There is a dot on its own');
        toks.push({ t: 'num', v: parseFloat(txt) });
        i = j; continue;
      }
      if (letterRe.test(c)) {
        let j = i;
        while (j < s.length && wordRe.test(s[j])) j++;
        const run = s.slice(i, j);
        if (words) pushWord(run, toks); else splitRun(run, toks);
        i = j; continue;
      }
      if ('+-*/^=(),!|'.includes(c)) { toks.push({ t: 'op', v: c }); i++; continue; }
      throw new SyntaxError(`I don't know the symbol "${c}"`);
    }
    return toks;
  }

  function pushName(nm, toks) {
    if (FUNCS.includes(nm)) toks.push({ t: 'fn', v: nm });
    else if (ALIASES[nm]) toks.push({ t: 'fn', v: ALIASES[nm] });
    else toks.push({ t: 'id', v: nm });
  }

  function pushWord(run, toks) {
    if (/^[0-9]/.test(run)) {
      const m = run.match(/^[0-9.]+/);
      toks.push({ t: 'num', v: parseFloat(m[0]) });
      if (m[0].length < run.length) pushWord(run.slice(m[0].length), toks);
      return;
    }
    pushName(run, toks);
  }

  function splitRun(run, toks) {
    let k = 0;
    while (k < run.length) {
      const ch = run[k];
      if (/[0-9]/.test(ch)) {
        let j = k;
        while (j < run.length && /[0-9.]/.test(run[j])) j++;
        toks.push({ t: 'num', v: parseFloat(run.slice(k, j)) });
        k = j; continue;
      }
      if (ch === '_') { k++; continue; }
      let matched = null;
      for (const nm of NAMES) if (run.startsWith(nm, k)) { matched = nm; break; }
      if (matched) { pushName(matched, toks); k += matched.length; continue; }
      let name = ch, j = k + 1;
      if (run[j] === '_') {
        let q = j + 1;
        while (q < run.length && /[A-Za-z0-9Ͱ-Ͽ]/.test(run[q])) q++;
        if (q > j + 1) { name = run.slice(k, q); j = q; }
      }
      toks.push({ t: 'id', v: name });
      k = j;
    }
  }

  /* ------------------------------------------------------------------ *
   * Parser → AST
   *   {type:'num', v} {type:'var', name} {type:'neg', a}
   *   {type:'op', op:'+|-|*|/|^', a, b} {type:'fn', name, a}
   *   {type:'eq', lhs, rhs}
   * ------------------------------------------------------------------ */
  function parse(src, opts) {
    const toks = tokenize(src, opts);
    if (!toks.length) throw new SyntaxError('There is nothing to read');
    let p = 0;
    const peek = () => toks[p];
    const next = () => toks[p++];
    const isOp = (tk, v) => tk && tk.t === 'op' && tk.v === v;
    const expect = (v) => {
      const tk = next();
      if (!isOp(tk, v)) throw new SyntaxError(`A "${v}" seems to be missing`);
    };
    const startsPrimary = (tk) => tk && (tk.t === 'num' || tk.t === 'id' || tk.t === 'fn' || isOp(tk, '('));

    function parseExpr() {
      let left = parseTerm();
      while (isOp(peek(), '+') || isOp(peek(), '-')) {
        const op = next().v;
        left = { type: 'op', op, a: left, b: parseTerm() };
      }
      return left;
    }
    function parseTerm() {
      let left = parseUnary();
      for (;;) {
        const tk = peek();
        if (isOp(tk, '*') || isOp(tk, '/')) {
          next();
          left = { type: 'op', op: tk.v, a: left, b: parseUnary() };
        } else if (startsPrimary(tk)) {
          left = { type: 'op', op: '*', a: left, b: parsePower(), implicit: true };
        } else break;
      }
      return left;
    }
    function parseUnary() {
      if (isOp(peek(), '-')) { next(); return { type: 'neg', a: parseUnary() }; }
      if (isOp(peek(), '+')) { next(); return parseUnary(); }
      return parsePower();
    }
    function parsePower() {
      const base = parsePostfix();
      if (isOp(peek(), '^')) { next(); return { type: 'op', op: '^', a: base, b: parseUnary() }; }
      return base;
    }
    function parsePostfix() {
      let e = parsePrimary();
      while (isOp(peek(), '!')) { next(); e = { type: 'fn', name: 'fact', a: e }; }
      return e;
    }
    function parsePrimary() {
      const tk = next();
      if (!tk) throw new SyntaxError('The line stops too early');
      if (tk.t === 'num') return { type: 'num', v: tk.v };
      if (tk.t === 'id') return { type: 'var', name: tk.v };
      if (tk.t === 'fn') {
        let arg;
        if (isOp(peek(), '(')) { next(); arg = parseExpr(); expect(')'); }
        else arg = parsePower();
        return { type: 'fn', name: tk.v, a: arg };
      }
      if (isOp(tk, '(')) { const e = parseExpr(); expect(')'); return e; }
      if (isOp(tk, '|')) { const e = parseExpr(); expect('|'); return { type: 'fn', name: 'abs', a: e }; }
      if (isOp(tk, '=')) throw new SyntaxError('Something is missing before "="');
      throw new SyntaxError(`I didn't expect "${tk.v}" there`);
    }

    const lhs = parseExpr();
    let node = lhs;
    if (isOp(peek(), '=')) {
      next();
      if (!peek()) throw new SyntaxError('Something is missing after "="');
      node = { type: 'eq', lhs, rhs: parseExpr() };
    }
    if (p < toks.length) {
      const tk = toks[p];
      if (isOp(tk, '=')) throw new SyntaxError('Only one "=" per line, please');
      throw new SyntaxError(`I didn't expect "${tk.v}" there`);
    }
    return node;
  }

  /* ------------------------------------------------------------------ *
   * Helpers on ASTs
   * ------------------------------------------------------------------ */
  const num = (v) => ({ type: 'num', v });
  const vr = (name) => ({ type: 'var', name });
  const op = (o, a, b) => ({ type: 'op', op: o, a, b });
  const fn = (name, a) => ({ type: 'fn', name, a });
  const neg = (a) => ({ type: 'neg', a });

  function vars(n, out) {
    out = out || new Set();
    if (!n) return out;
    if (n.type === 'var') { if (!isConst(n.name)) out.add(n.name); }
    else if (n.type === 'eq') { vars(n.lhs, out); vars(n.rhs, out); }
    else { if (n.a) vars(n.a, out); if (n.b) vars(n.b, out); }
    return out;
  }

  function count(n, name) {
    if (!n) return 0;
    if (n.type === 'var') return n.name === name ? 1 : 0;
    if (n.type === 'eq') return count(n.lhs, name) + count(n.rhs, name);
    return count(n.a, name) + count(n.b, name);
  }

  function substitute(n, map) {
    if (!n) return n;
    if (n.type === 'var') return Object.prototype.hasOwnProperty.call(map, n.name) ? map[n.name] : n;
    if (n.type === 'eq') return { type: 'eq', lhs: substitute(n.lhs, map), rhs: substitute(n.rhs, map) };
    const c = Object.assign({}, n);
    if (n.a) c.a = substitute(n.a, map);
    if (n.b) c.b = substitute(n.b, map);
    return c;
  }

  /* ------------------------------------------------------------------ *
   * Evaluation (compiled to closures for speed)
   * ------------------------------------------------------------------ */
  const D2R = Math.PI / 180;
  function fact(x) {
    if (x < 0 || !Number.isInteger(x) || x > 170) return NaN;
    let r = 1; for (let i = 2; i <= x; i++) r *= i; return r;
  }
  function fnImpl(name, deg) {
    const k = deg ? D2R : 1;
    switch (name) {
      case 'sin': return (x) => Math.sin(x * k);
      case 'cos': return (x) => Math.cos(x * k);
      case 'tan': return (x) => Math.tan(x * k);
      case 'asin': return (x) => Math.asin(x) / k;
      case 'acos': return (x) => Math.acos(x) / k;
      case 'atan': return (x) => Math.atan(x) / k;
      case 'sinh': return Math.sinh; case 'cosh': return Math.cosh; case 'tanh': return Math.tanh;
      case 'asinh': return Math.asinh; case 'acosh': return Math.acosh; case 'atanh': return Math.atanh;
      case 'sqrt': return Math.sqrt; case 'cbrt': return Math.cbrt;
      case 'ln': return Math.log; case 'log': return Math.log10;
      case 'abs': return Math.abs; case 'exp': return Math.exp;
      case 'fact': return fact;
      default: return () => NaN;
    }
  }

  function compile(n, deg) {
    switch (n.type) {
      case 'num': { const v = n.v; return () => v; }
      case 'var': {
        const name = n.name;
        const c = isConst(name) ? CONSTANTS[name] : NaN;
        return (env) => { const v = env[name]; return v === undefined ? c : v; };
      }
      case 'neg': { const a = compile(n.a, deg); return (env) => -a(env); }
      case 'fn': { const a = compile(n.a, deg); const f = fnImpl(n.name, deg); return (env) => f(a(env)); }
      case 'op': {
        const a = compile(n.a, deg), b = compile(n.b, deg);
        switch (n.op) {
          case '+': return (env) => a(env) + b(env);
          case '-': return (env) => a(env) - b(env);
          case '*': return (env) => a(env) * b(env);
          case '/': return (env) => a(env) / b(env);
          case '^': return (env) => powReal(a(env), b(env));
        }
      }
    }
    throw new Error('Cannot evaluate this expression');
  }

  // Real power that also handles odd roots of negatives, e.g. (-8)^(1/3) = -2.
  function powReal(x, y) {
    const r = Math.pow(x, y);
    if (!isNaN(r) || x >= 0) return r;
    const inv = 1 / y;
    if (Math.abs(inv - Math.round(inv)) < 1e-9 && Math.round(inv) % 2 !== 0) return -Math.pow(-x, y);
    return r;
  }

  // "Size" of an expression: the sum of the absolute values of its
  // top-level terms. Used to judge whether a difference counts as zero.
  function compileMag(n, deg) {
    if (n.type === 'op' && (n.op === '+' || n.op === '-')) {
      const a = compileMag(n.a, deg), b = compileMag(n.b, deg);
      return (env) => a(env) + b(env);
    }
    if (n.type === 'neg') return compileMag(n.a, deg);
    const f = compile(n, deg);
    return (env) => Math.abs(f(env));
  }

  function evaluate(n, env, deg) {
    return compile(n, deg !== false)(env || {});
  }

  /* ------------------------------------------------------------------ *
   * Printing
   * ------------------------------------------------------------------ */
  const SUP = { '0': '⁰', '1': '¹', '2': '²', '3': '³', '4': '⁴', '5': '⁵', '6': '⁶', '7': '⁷', '8': '⁸', '9': '⁹', '-': '⁻', '−': '⁻', 'n': 'ⁿ', 'x': 'ˣ', 'y': 'ʸ', '.': '·' };
  const SUB = { '0': '₀', '1': '₁', '2': '₂', '3': '₃', '4': '₄', '5': '₅', '6': '₆', '7': '₇', '8': '₈', '9': '₉' };

  function fmt(v, sig) {
    sig = sig || 6;
    if (typeof v !== 'number' || isNaN(v)) return 'undefined';
    if (!isFinite(v)) return v > 0 ? '∞' : '−∞';
    if (v === 0) return '0';
    const av = Math.abs(v);
    const r = Math.round(v);
    if (Math.abs(v - r) <= 1e-9 * Math.max(1, av) && av < 1e15) return (r < 0 ? '−' : '') + String(Math.abs(r));
    if (av >= 1e9 || av < 1e-4) {
      const [m, e] = v.toExponential(sig - 1).split('e');
      const mant = String(parseFloat(m)).replace('-', '−');
      const ex = String(parseInt(e, 10)).split('').map((ch) => SUP[ch] || ch).join('');
      return `${mant}×10${ex}`;
    }
    let s = v.toPrecision(sig);
    if (s.includes('.')) s = s.replace(/0+$/, '').replace(/\.$/, '');
    return s.replace('-', '−');
  }

  function nameOut(name, html) {
    if (name === 'pi') return 'π';
    if (name === '__Q') return html ? '<i>circled part</i>' : 'circled part';
    if (GREEK[name]) return GREEK[name];
    const m = name.match(/^([^_]+)_(.+)$/);
    if (m) {
      const base = GREEK[m[1]] || m[1];
      if (html) return `${base}<sub>${m[2]}</sub>`;
      if (/^[0-9]+$/.test(m[2])) return base + m[2].split('').map((c) => SUB[c]).join('');
      return `${base}_${m[2]}`;
    }
    return name;
  }

  function prec(n) {
    if (n.type === 'op') return { '+': 1, '-': 1, '*': 2, '/': 2, '^': 4 }[n.op];
    if (n.type === 'neg') return 3;
    if (n.type === 'num' && n.v < 0) return 3;
    return 5;
  }

  function show(n, html) {
    const P = (x) => show(x, html);
    const wrap = (s) => `(${s})`;
    switch (n.type) {
      case 'eq': return `${P(n.lhs)} = ${P(n.rhs)}`;
      case 'num': return fmt(n.v);
      case 'var': return nameOut(n.name, html);
      case 'neg': { const s = P(n.a); return '−' + (prec(n.a) <= 3 ? wrap(s) : s); }
      case 'fn': {
        const s = P(n.a);
        if (n.name === 'abs') return `|${s}|`;
        if (n.name === 'sqrt') return '√' + (prec(n.a) >= 5 ? s : wrap(s));
        if (n.name === 'fact') return (prec(n.a) >= 5 ? s : wrap(s)) + '!';
        return `${n.name}(${s})`;
      }
      case 'op': {
        const a = P(n.a);
        switch (n.op) {
          case '+': {
            if (n.b.type === 'neg') return `${a} − ${prec(n.b.a) <= 1 ? wrap(P(n.b.a)) : P(n.b.a)}`;
            if (n.b.type === 'num' && n.b.v < 0) return `${a} − ${fmt(-n.b.v)}`;
            return `${a} + ${P(n.b)}`;
          }
          case '-': { const b = P(n.b); return `${a} − ${prec(n.b) <= 1 || prec(n.b) === 3 ? wrap(b) : b}`; }
          case '*': {
            const sa = prec(n.a) < 2 ? wrap(a) : a;
            const b = P(n.b);
            const sb = prec(n.b) <= 3 && !(n.b.type === 'num' && n.b.v >= 0) ? (prec(n.b) < 2 || prec(n.b) === 3 ? wrap(b) : b) : b;
            const implicitOk = n.a.type === 'num' && n.a.v >= 0 && n.b.type !== 'num' &&
              !(n.b.type === 'op' && (n.b.op === '*' || n.b.op === '/') && n.b.a.type === 'num') && prec(n.b) !== 3;
            return implicitOk ? sa + sb : `${sa}·${sb}`;
          }
          case '/': {
            const b = P(n.b);
            const sa = prec(n.a) < 2 || prec(n.a) === 3 ? wrap(a) : a;
            const sb = prec(n.b) <= 3 ? wrap(b) : b;
            return `${sa}/${sb}`;
          }
          case '^': {
            const sa = prec(n.a) < 5 ? wrap(a) : a;
            const b = P(n.b);
            if (html) return `${sa}<sup>${b}</sup>`;
            if (/^[0-9−\-nxy.]+$/.test(b)) return sa + b.split('').map((c) => SUP[c] || c).join('');
            return `${sa}^(${b})`;
          }
        }
      }
    }
    return '?';
  }

  // Light clean-up for display: removes ×1, +0, double negatives…
  // (constants are NOT folded, so steps stay readable).
  function tidy(n) {
    if (!n || n.type === 'num' || n.type === 'var') return n;
    if (n.type === 'eq') return { type: 'eq', lhs: tidy(n.lhs), rhs: tidy(n.rhs) };
    if (n.type === 'neg') {
      const a = tidy(n.a);
      if (a.type === 'neg') return a.a;
      if (a.type === 'num') return num(-a.v);
      return neg(a);
    }
    if (n.type === 'fn') return fn(n.name, tidy(n.a));
    const a = tidy(n.a), b = tidy(n.b);
    const isN = (x, v) => x.type === 'num' && x.v === v;
    switch (n.op) {
      case '+': if (isN(a, 0)) return b; if (isN(b, 0)) return a; break;
      case '-': if (isN(b, 0)) return a; if (isN(a, 0)) return tidy(neg(b)); break;
      case '*': if (isN(a, 1)) return b; if (isN(b, 1)) return a; break;
      case '/': if (isN(b, 1)) return a; break;
      case '^': if (isN(b, 1)) return a; break;
    }
    return op(n.op, a, b);
  }

  /* ------------------------------------------------------------------ *
   * Rearranging: get `target` on its own (works when it appears once)
   * ------------------------------------------------------------------ */
  const INVERSE = { sin: 'asin', cos: 'acos', tan: 'atan', asin: 'sin', acos: 'cos', atan: 'tan',
    sinh: 'asinh', cosh: 'acosh', tanh: 'atanh', asinh: 'sinh', acosh: 'cosh', atanh: 'tanh',
    ln: 'exp', exp: 'ln' };

  function isolate(eq, target) {
    if (!eq || eq.type !== 'eq') return null;
    let L = eq.lhs, R = eq.rhs;
    const cl = count(L, target), cr = count(R, target);
    if (cl + cr !== 1) return null;
    if (cr === 1) { const t = L; L = R; R = t; }
    let plusMinus = false, periodic = false;
    for (let guard = 0; guard < 200; guard++) {
      if (L.type === 'var' && L.name === target) return { target, expr: tidy(R), plusMinus, periodic };
      if (L.type === 'neg') { R = neg(R); L = L.a; continue; }
      if (L.type === 'op') {
        const inA = count(L.a, target) > 0;
        const a = L.a, b = L.b;
        switch (L.op) {
          case '+': R = op('-', R, inA ? b : a); L = inA ? a : b; break;
          case '-': if (inA) { R = op('+', R, b); L = a; } else { R = op('-', a, R); L = b; } break;
          case '*': R = op('/', R, inA ? b : a); L = inA ? a : b; break;
          case '/': if (inA) { R = op('*', R, b); L = a; } else { R = op('/', a, R); L = b; } break;
          case '^':
            if (inA) {
              if (b.type === 'num' && b.v === 2) R = fn('sqrt', R);
              else if (b.type === 'num' && b.v === 3) R = fn('cbrt', R);
              else R = op('^', R, op('/', num(1), b));
              if (b.type === 'num' && Number.isInteger(b.v) && b.v % 2 === 0) plusMinus = true;
              L = a;
            } else {
              R = a.type === 'var' && a.name === 'e' ? fn('ln', R) : op('/', fn('ln', R), fn('ln', a));
              L = b;
            }
            break;
          default: return null;
        }
        continue;
      }
      if (L.type === 'fn') {
        if (L.name === 'sqrt') R = op('^', R, num(2));
        else if (L.name === 'cbrt') R = op('^', R, num(3));
        else if (L.name === 'log') R = op('^', num(10), R);
        else if (L.name === 'abs') plusMinus = true;
        else if (INVERSE[L.name]) {
          R = fn(INVERSE[L.name], R);
          if (['sin', 'cos', 'tan'].includes(L.name)) periodic = true;
        } else return null;
        L = L.a; continue;
      }
      return null;
    }
    return null;
  }

  /* ------------------------------------------------------------------ *
   * Small numeric toolbox
   * ------------------------------------------------------------------ */
  function mulberry32(a) {
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  function solveLinear(A, b) {
    const n = b.length;
    const M = A.map((row, i) => row.concat([b[i]]));
    for (let c = 0; c < n; c++) {
      let piv = c;
      for (let r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[piv][c])) piv = r;
      if (Math.abs(M[piv][c]) < 1e-300) return null;
      [M[c], M[piv]] = [M[piv], M[c]];
      for (let r = c + 1; r < n; r++) {
        const f = M[r][c] / M[c][c];
        if (f) for (let k = c; k <= n; k++) M[r][k] -= f * M[c][k];
      }
    }
    const x = new Array(n).fill(0);
    for (let r = n - 1; r >= 0; r--) {
      let s = M[r][n];
      for (let k = r + 1; k < n; k++) s -= M[r][k] * x[k];
      x[r] = s / M[r][r];
    }
    return x;
  }

  // Jacobi eigen-decomposition of a small symmetric matrix.
  function symEig(A) {
    const n = A.length;
    const a = A.map((r) => r.slice());
    const V = a.map((_, i) => a.map((__, j) => (i === j ? 1 : 0)));
    let norm = 0;
    for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) norm += a[i][j] * a[i][j];
    for (let sweep = 0; sweep < 60; sweep++) {
      let off = 0;
      for (let p = 0; p < n; p++) for (let q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
      if (off <= 1e-30 * norm || off === 0) break;
      for (let p = 0; p < n; p++) {
        for (let q = p + 1; q < n; q++) {
          if (Math.abs(a[p][q]) < 1e-300) continue;
          const theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
          const t = Math.sign(theta || 1) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
          const c = 1 / Math.sqrt(t * t + 1), s = t * c;
          for (let k = 0; k < n; k++) {
            const akp = a[k][p], akq = a[k][q];
            a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq;
          }
          for (let k = 0; k < n; k++) {
            const apk = a[p][k], aqk = a[q][k];
            a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk;
          }
          for (let k = 0; k < n; k++) {
            const vkp = V[k][p], vkq = V[k][q];
            V[k][p] = c * vkp - s * vkq; V[k][q] = s * vkp + c * vkq;
          }
        }
      }
    }
    return { values: a.map((r, i) => r[i]), vectors: V };
  }

  /* ------------------------------------------------------------------ *
   * Equation solving
   * ------------------------------------------------------------------ */
  function prepEq(e, deg) {
    const src = e.type === 'eq' ? e : e.eq;
    const label = e.label || null;
    return {
      src, label, origin: e.origin,
      fL: compile(src.lhs, deg), fR: compile(src.rhs, deg),
      mL: compileMag(src.lhs, deg), mR: compileMag(src.rhs, deg),
      vars: vars(src)
    };
  }

  function holds(eq, env) {
    const l = eq.fL(env), r = eq.fR(env);
    if (!isFinite(l) || !isFinite(r)) return false;
    const m = eq.mL(env) + eq.mR(env);
    return Math.abs(l - r) <= 1e-7 * m + 1e-13;
  }

  function inDomain(x, d) {
    if (!isFinite(x)) return false;
    if (d === 'positive') return x > 0;
    if (d === 'nonneg') return x >= 0;
    if (d === 'angle') return x > 0 && x < 180;
    return true;
  }

  const GRIDS = {};
  function grid(domain) {
    if (GRIDS[domain]) return GRIDS[domain];
    const g = [];
    if (domain === 'angle') {
      for (let x = 0.05; x < 180; x += 0.1) g.push(x);
    } else if (domain === 'positive' || domain === 'nonneg') {
      if (domain === 'nonneg') g.push(0);
      for (let k = -13; k <= 13.0001; k += 0.02) g.push(Math.pow(10, k));
    } else {
      const pos = [];
      for (let k = -10; k <= 10.0001; k += 0.025) pos.push(Math.pow(10, k));
      for (let i = pos.length - 1; i >= 0; i--) g.push(-pos[i]);
      g.push(0);
      for (const x of pos) g.push(x);
    }
    GRIDS[domain] = g;
    return g;
  }

  // All roots of one equation in one unknown (others fixed in env0).
  function solve1D(eq, v, env0, domain, deg) {
    const env = Object.assign({}, env0);
    const g = (x) => { env[v] = x; return eq.fL(env) - eq.fR(env); };
    const ok = (x) => { env[v] = x; return holds(eq, env); };
    const roots = [];
    const add = (x) => {
      if (!inDomain(x, domain) || !ok(x)) return;
      if (roots.some((r) => Math.abs(r - x) <= 1e-7 * Math.max(Math.abs(x), Math.abs(r)) || (Math.abs(r) < 1e-12 && Math.abs(x) < 1e-12))) return;
      roots.push(x);
    };
    // 1) exact rearrangement when possible
    const iso = isolate(eq.src, v);
    if (iso) {
      try {
        const val = compile(iso.expr, deg)(env0);
        if (isFinite(val)) {
          add(val);
          if (iso.plusMinus) add(-val);
          if (iso.periodic && deg) { add(180 - val); add(-val); add(360 - val); }
        }
      } catch (e) { /* ignore */ }
    }
    // 2) scan for sign changes and near-touching minima
    const xs = grid(domain);
    const gs = xs.map(g);
    const refineBisect = (lo, hi, glo) => {
      for (let it = 0; it < 200; it++) {
        const mid = 0.5 * (lo + hi);
        if (mid === lo || mid === hi) break;
        const gm = g(mid);
        if (gm === 0) return mid;
        if ((gm < 0) === (glo < 0)) { lo = mid; glo = gm; } else hi = mid;
      }
      return 0.5 * (lo + hi);
    };
    let minima = 0;
    for (let i = 1; i < xs.length; i++) {
      const a = gs[i - 1], b = gs[i];
      if (!isFinite(a) || !isFinite(b)) continue;
      if (b === 0) { add(xs[i]); continue; }
      if (a !== 0 && (a < 0) !== (b < 0)) add(refineBisect(xs[i - 1], xs[i], a));
      if (i < xs.length - 1 && minima < 300) {
        const c = gs[i + 1];
        if (isFinite(c) && Math.abs(b) <= Math.abs(a) && Math.abs(b) <= Math.abs(c) && (b < 0) === (a < 0) && (b < 0) === (c < 0)) {
          minima++;
          let lo = xs[i - 1], hi = xs[i + 1];
          const gr = 0.6180339887;
          for (let it = 0; it < 120; it++) {
            const x1 = hi - gr * (hi - lo), x2 = lo + gr * (hi - lo);
            if (Math.abs(g(x1)) < Math.abs(g(x2))) hi = x2; else lo = x1;
          }
          add(0.5 * (lo + hi));
        }
      }
    }
    roots.sort((p, q) => p - q);
    return roots;
  }

  function scaleOf(known, domains) {
    let s = 0, k = 0;
    for (const [name, v] of Object.entries(known)) {
      if (domains[name] === 'angle' || !v) continue;
      s += Math.log(Math.abs(v)); k++;
    }
    return k ? Math.exp(s / k) : 1;
  }

  // Levenberg–Marquardt with random restarts. Unknowns live in a
  // transformed space so positive / angle values stay in range.
  function lmSolve(eqList, U, baseEnv, domOf, scale, rng, starts) {
    const n = U.length, m = eqList.length;
    const doms = U.map(domOf);
    const toU = (z, j) => doms[j] === 'positive' ? Math.exp(z) : doms[j] === 'angle' ? 180 / (1 + Math.exp(-z)) : z;
    const env = Object.assign({}, baseEnv);
    const resid = (z) => {
      for (let j = 0; j < n; j++) env[U[j]] = toU(z[j], j);
      const r = new Array(m);
      for (let i = 0; i < m; i++) {
        const eq = eqList[i];
        const l = eq.fL(env), rr = eq.fR(env);
        const w = eq.mL(env) + eq.mR(env) + 1e-30;
        r[i] = (l - rr) / w;
      }
      return r;
    };
    const cost = (r) => { let s = 0; for (const x of r) s += x * x; return isFinite(s) ? s : Infinity; };
    const out = [];
    for (let st = 0; st < starts; st++) {
      let z = U.map((_, j) => {
        const d = doms[j];
        if (d === 'positive') return Math.log(scale * Math.pow(10, rng() * 3 - 1.5));
        if (d === 'angle') { const p = 0.03 + 0.94 * rng(); return Math.log(p / (1 - p)); }
        return (rng() < 0.5 ? -1 : 1) * scale * Math.pow(10, rng() * 3 - 1.5);
      });
      let r = resid(z), c = cost(r);
      if (!isFinite(c)) continue;
      let lambda = 1e-3;
      for (let it = 0; it < 200 && c > 1e-26; it++) {
        const J = [];
        for (let i = 0; i < m; i++) J.push(new Array(n));
        for (let j = 0; j < n; j++) {
          const h = 1e-7 * Math.max(1, Math.abs(z[j]));
          const zj = z[j]; z[j] = zj + h;
          const r2 = resid(z); z[j] = zj;
          for (let i = 0; i < m; i++) J[i][j] = (r2[i] - r[i]) / h;
        }
        const A = [], g = new Array(n).fill(0);
        for (let p = 0; p < n; p++) {
          A.push(new Array(n).fill(0));
          for (let q = 0; q < n; q++) { let s = 0; for (let i = 0; i < m; i++) s += J[i][p] * J[i][q]; A[p][q] = s; }
          for (let i = 0; i < m; i++) g[p] += J[i][p] * r[i];
        }
        let improved = false;
        for (let tries = 0; tries < 12; tries++) {
          const Al = A.map((row, p) => row.map((v, q) => (p === q ? v * (1 + lambda) + lambda * 1e-9 : v)));
          const delta = solveLinear(Al, g.map((x) => -x));
          if (!delta || delta.some((x) => !isFinite(x))) { lambda *= 10; continue; }
          const zn = z.map((x, j) => x + delta[j]);
          const rn = resid(zn), cn = cost(rn);
          if (cn < c) {
            const small = delta.every((d, j) => Math.abs(d) < 1e-14 * (1 + Math.abs(z[j])));
            z = zn; r = rn; c = cn; lambda = Math.max(lambda / 3, 1e-15); improved = true;
            if (small) it = 1e9;
            break;
          }
          lambda *= 4;
          if (lambda > 1e15) break;
        }
        if (!improved) break;
      }
      for (let j = 0; j < n; j++) env[U[j]] = toU(z[j], j);
      if (eqList.every((eq) => holds(eq, env)) && U.every((v, j) => inDomain(env[v], doms[j]) && !nearEdge(env[v], doms[j], scale))) {
        out.push(Object.assign({}, env));
      }
    }
    return out;
  }

  // Solutions squashed against a limit (a 180° "triangle", a zero-length
  // side) are degenerate: they fool the pinned-down test, so skip them.
  function nearEdge(x, d, scale) {
    if (d === 'positive') return x < 1e-6 * scale;
    if (d === 'angle') return x < 0.01 || x > 179.99;
    return false;
  }

  // Is `target` pinned down near this solution? Looks at the null space
  // of the Jacobian: if you can wiggle the unknowns without breaking any
  // equation AND that wiggle moves the target, the target is not fixed.
  function jacobianNormal(eqList, U, env, scale) {
    const n = U.length;
    const s = U.map((v) => Math.max(Math.abs(env[v]), 1e-9 * scale, 1e-15));
    const rows = [];
    for (const eq of eqList) {
      const w = 1 / (eq.mL(env) + eq.mR(env) + 1e-300);
      const row = new Array(n);
      for (let j = 0; j < n; j++) {
        const v = U[j], x0 = env[v], h = 1e-6 * s[j];
        env[v] = x0 + h; const fp = eq.fL(env) - eq.fR(env);
        env[v] = x0 - h; const fm = eq.fL(env) - eq.fR(env);
        env[v] = x0;
        row[j] = ((fp - fm) / (2 * h)) * s[j] * w;
      }
      rows.push(row);
    }
    const M = [];
    for (let p = 0; p < n; p++) {
      M.push(new Array(n).fill(0));
      for (let q = 0; q < n; q++) { let sum = 0; for (const row of rows) sum += row[p] * row[q]; M[p][q] = sum; }
    }
    return M;
  }

  function targetFixed(M, t) {
    const { values, vectors } = symEig(M);
    const lmax = Math.max(0, ...values);
    const n = values.length;
    for (let k = 0; k < n; k++) {
      if (values[k] <= 1e-9 * lmax + 1e-300) {
        if (Math.abs(vectors[t][k]) > 1e-3) return false;
      }
    }
    return true;
  }

  function withPinned(M, idx) {
    const lmax = Math.max(1e-300, ...symEig(M).values);
    const M2 = M.map((r) => r.slice());
    for (const j of idx) M2[j][j] += lmax;
    return M2;
  }

  /**
   * Solve for one target.
   * opts: { equations:[{type:'eq'...} | {eq, label}], knowns:{name:value},
   *         target, domains:{name:'real'|'positive'|'angle'}, deg:true }
   * Returns { status, value, values, steps, need, needMore, conflicts }
   *   status: 'given' | 'solved' | 'multiple' | 'underdetermined'
   *           | 'inconsistent' | 'no-solution' | 'no-equation'
   */
  function solveFor(opts) {
    const deg = opts.deg !== false;
    const domains = opts.domains || {};
    const domOf = (v) => domains[v] || opts.defaultDomain || 'real';
    const target = opts.target;
    const known = {};
    for (const [k, v] of Object.entries(opts.knowns || {})) if (typeof v === 'number' && isFinite(v)) known[k] = v;
    if (Object.prototype.hasOwnProperty.call(known, target)) return { status: 'given', target, value: known[target], values: [known[target]], steps: [] };

    const eqs = (opts.equations || []).map((e) => prepEq(e, deg));

    // Only the equations linked to the target (through unknowns) matter.
    const comp = [];
    const seen = new Set([target]);
    const queue = [target];
    const used = new Set();
    while (queue.length) {
      const v = queue.shift();
      eqs.forEach((eq, i) => {
        if (used.has(i) || !eq.vars.has(v)) return;
        used.add(i); comp.push(eq);
        for (const w of eq.vars) if (!(w in known) && !seen.has(w)) { seen.add(w); queue.push(w); }
      });
    }
    if (!comp.length) return { status: 'no-equation', target };

    const K = Object.assign({}, known);
    const steps = [];
    // 1) Chain: repeatedly solve equations that have exactly one unknown.
    for (let progress = true; progress;) {
      progress = false;
      for (const eq of comp) {
        const unk = [...eq.vars].filter((v) => !(v in K));
        if (unk.length !== 1) continue;
        const v = unk[0];
        const roots = solve1D(eq, v, K, domOf(v), deg);
        if (roots.length === 1) {
          K[v] = roots[0];
          steps.push({ eq, v, value: roots[0] });
          progress = true;
        } else if (roots.length === 0) {
          return { status: 'no-solution', target, eq: eq.src, label: eq.label, about: v, values: [], steps };
        }
      }
    }
    // 2) Anything fully known must agree.
    const conflicts = eqs.filter((eq) => [...eq.vars].every((v) => v in K) && !holds(eq, K));
    if (conflicts.length) {
      return { status: 'inconsistent', target, conflicts: conflicts.map((c) => ({ eq: c.src, label: c.label })), values: [], steps };
    }
    if (target in K) {
      return { status: 'solved', target, value: K[target], values: [K[target]], steps: relevantSteps(steps, target, known), env: K };
    }

    // 3) Solve what is left as a system.
    const rest = comp.filter((eq) => [...eq.vars].some((v) => !(v in K)));
    const U = [...new Set(rest.flatMap((eq) => [...eq.vars].filter((v) => !(v in K))))];
    U.sort((a, b) => (a === target ? -1 : b === target ? 1 : 0));
    const scale = scaleOf(known, domains);
    const rng = mulberry32(opts.seed || 12345);
    const sols = lmSolve(rest, U, K, domOf, scale, rng, opts.starts || 36);
    if (!sols.length) {
      return { status: 'no-solution', target, values: [], steps: relevantSteps(steps, target, known) };
    }
    const t = U.indexOf(target);
    const fixedSols = [], loose = [];
    for (const env of sols) {
      const M = jacobianNormal(rest, U, env, scale);
      (targetFixed(M, t) ? fixedSols : loose).push({ env, M });
    }
    // If most attempts land on solutions where the target can still slide,
    // the few "fixed" ones are numerical flukes.
    if (!fixedSols.length || loose.length >= Math.max(2, 0.3 * sols.length)) {
      const { M } = loose[0];
      const need = [];
      U.forEach((v, j) => { if (j !== t && targetFixed(withPinned(M, [j]), t)) need.push(v); });
      const others = U.filter((v) => v !== target);
      return { status: 'underdetermined', target, need, needMore: !need.length, unknowns: others, values: [], steps: [] };
    }
    const values = [];
    for (const { env } of fixedSols) {
      const x = env[target];
      if (!values.some((y) => Math.abs(y - x) <= 1e-6 * Math.max(Math.abs(x), Math.abs(y), 1e-12))) values.push(x);
    }
    values.sort((a, b) => a - b);
    const sysStep = { system: rest.map((e) => ({ eq: e.src, label: e.label })), unknowns: U };
    const baseSteps = relevantSteps(steps, null, known);
    if (values.length === 1) {
      return { status: 'solved', target, value: values[0], values, steps: baseSteps.concat([sysStep]), env: fixedSols[0].env };
    }
    return { status: 'multiple', target, value: values[0], values, steps: baseSteps.concat([sysStep]) };
  }

  // Keep only the chain steps the target actually depends on.
  function relevantSteps(steps, target, known) {
    if (!target) return steps.map(stepOut);
    const need = new Set([target]);
    const keep = [];
    for (let i = steps.length - 1; i >= 0; i--) {
      const s = steps[i];
      if (!need.has(s.v)) continue;
      keep.unshift(s);
      for (const w of s.eq.vars) if (w !== s.v && !(w in known)) need.add(w);
    }
    return keep.map(stepOut);
  }

  function stepOut(s) {
    const iso = isolate(s.eq.src, s.v);
    return { v: s.v, value: s.value, eq: s.eq.src, label: s.eq.label, rearranged: iso ? iso.expr : null, plusMinus: iso ? iso.plusMinus : false };
  }

  return {
    parse, tokenize, evaluate, compile, vars, count, substitute, isolate, tidy,
    show: (n) => show(n, false), showHTML: (n) => show(n, true), nameOut, fmt,
    solveFor, solve1D: (eq, v, env, domain, deg) => solve1D(prepEq(eq, deg !== false), v, env, domain || 'real', deg !== false),
    CONSTANTS, GREEK, num, vr, op, fn, neg
  };
});
