/* FoxBox — shared helpers, parameter model and UI widgets.
   Everything lives on one global object, window.FB, so the app runs
   by just opening index.html (no build step, no server required). */
window.FB = window.FB || {};

(function (FB) {
  'use strict';

  // ------------------------------------------------------------------
  // Small math / music helpers
  // ------------------------------------------------------------------
  const U = (FB.util = {});
  U.clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  U.lerp = (a, b, t) => a + (b - a) * t;
  U.mtof = (n) => 440 * Math.pow(2, (n - 69) / 12);
  U.NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
  U.noteName = (n) => U.NOTE_NAMES[((n % 12) + 12) % 12] + (Math.floor(n / 12) - 1);

  // Seeded random generator (mulberry32) so generative patterns are repeatable.
  U.rng = function (seed) {
    let a = seed >>> 0;
    return function () {
      a = (a + 0x6d2b79f5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  };

  U.SCALES = {
    'Minor pentatonic': [0, 3, 5, 7, 10],
    'Major pentatonic': [0, 2, 4, 7, 9],
    Minor: [0, 2, 3, 5, 7, 8, 10],
    Major: [0, 2, 4, 5, 7, 9, 11],
    Dorian: [0, 2, 3, 5, 7, 9, 10],
    Lydian: [0, 2, 4, 6, 7, 9, 11],
    Phrygian: [0, 1, 3, 5, 7, 8, 10],
    Chromatic: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11],
  };
  // i-th note of a scale, starting at MIDI note `base`.
  U.scaleNote = function (i, base, scaleName) {
    const s = U.SCALES[scaleName] || U.SCALES.Chromatic;
    const oct = Math.floor(i / s.length);
    return base + oct * 12 + s[((i % s.length) + s.length) % s.length];
  };

  // Hann window curve used for grain envelopes.
  U.hann = function (n) {
    const c = new Float32Array(n);
    for (let i = 0; i < n; i++) c[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (n - 1));
    return c;
  };

  // Release an AudioParam smoothly from wherever it currently is.
  U.releaseParam = function (param, t, timeConst, target) {
    target = target || 0;
    if (param.cancelAndHoldAtTime) {
      param.cancelAndHoldAtTime(t);
    } else {
      param.cancelScheduledValues(t);
      param.setValueAtTime(param.value, t);
    }
    param.setTargetAtTime(target, t, Math.max(0.001, timeConst));
  };

  U.el = function (tag, attrs, children) {
    const e = document.createElement(tag);
    if (attrs) {
      for (const k in attrs) {
        const v = attrs[k];
        if (k === 'class') e.className = v;
        else if (k === 'text') e.textContent = v;
        else if (k === 'html') e.innerHTML = v;
        else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
        else if (v !== undefined && v !== null && v !== false) e.setAttribute(k, v === true ? '' : v);
      }
    }
    if (children) (Array.isArray(children) ? children : [children]).forEach((c) => c && e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c));
    return e;
  };

  U.debounce = function (fn, ms) {
    let t = null;
    return function () {
      clearTimeout(t);
      t = setTimeout(fn, ms);
    };
  };

  // Peaks of an AudioBuffer for drawing waveforms.
  U.peaks = function (buffer, count) {
    const out = new Float32Array(count);
    if (!buffer) return out;
    const d = buffer.getChannelData(0);
    const step = Math.max(1, Math.floor(d.length / count));
    for (let i = 0; i < count; i++) {
      let m = 0;
      const s = i * step;
      for (let j = 0; j < step; j += 4) {
        const v = Math.abs(d[s + j] || 0);
        if (v > m) m = v;
      }
      out[i] = m;
    }
    let max = 0;
    for (let i = 0; i < count; i++) max = Math.max(max, out[i]);
    if (max > 0) for (let i = 0; i < count; i++) out[i] /= max;
    return out;
  };

  U.reverseBuffer = function (ctx, buf) {
    const r = ctx.createBuffer(buf.numberOfChannels, buf.length, buf.sampleRate);
    for (let c = 0; c < buf.numberOfChannels; c++) {
      const src = buf.getChannelData(c);
      const dst = r.getChannelData(c);
      for (let i = 0, n = src.length; i < n; i++) dst[i] = src[n - 1 - i];
    }
    return r;
  };

  // ------------------------------------------------------------------
  // Engine base class: holds parameters and the common note interface.
  // ------------------------------------------------------------------
  class Engine {
    constructor(id, name) {
      this.id = id;
      this.name = name;
      this.params = {};
      this.ctx = FB.audio.ctx;
      this.output = this.ctx.createGain();
      this.isInstrument = true;
    }
    now() {
      return this.ctx.currentTime;
    }
    // def: {label, type:'num'|'choice'|'bool', min, max, def, curve:'lin'|'exp', step, unit, options, fmt}
    param(key, def) {
      def.key = key;
      def.type = def.type || 'num';
      def.curve = def.curve || 'lin';
      def.value = def.def;
      def.listeners = new Set();
      this.params[key] = def;
      return def;
    }
    get(key) {
      return this.params[key].value;
    }
    set(key, v, opts) {
      const p = this.params[key];
      if (!p) return;
      if (p.type === 'num') {
        v = U.clamp(+v, p.min, p.max);
        if (p.step) v = Math.round(v / p.step) * p.step;
      } else if (p.type === 'bool') {
        v = !!v;
      } else if (p.type === 'choice') {
        if (p.options.indexOf(v) < 0) v = p.options[0];
      }
      if (v === p.value && !(opts && opts.force)) return;
      p.value = v;
      this.onParam(key, v);
      p.listeners.forEach((fn) => fn(v));
      if (FB.state) FB.state.dirty();
    }
    norm(key) {
      const p = this.params[key];
      if (p.type === 'bool') return p.value ? 1 : 0;
      if (p.type === 'choice') return p.options.indexOf(p.value) / Math.max(1, p.options.length - 1);
      if (p.curve === 'exp') return Math.log(p.value / p.min) / Math.log(p.max / p.min);
      return (p.value - p.min) / (p.max - p.min);
    }
    setNorm(key, n) {
      const p = this.params[key];
      if (!p) return;
      n = U.clamp(n, 0, 1);
      if (p.type === 'bool') return this.set(key, n >= 0.5);
      if (p.type === 'choice') return this.set(key, p.options[Math.round(n * (p.options.length - 1))]);
      const v = p.curve === 'exp' ? p.min * Math.pow(p.max / p.min, n) : p.min + n * (p.max - p.min);
      this.set(key, v);
    }
    onParam() {}
    // Notes: `key` identifies the touch/key/MIDI note so it can be released later.
    noteOn() {}
    noteOff() {}
    expr() {} // kind: 'bend' (semitones), 'pressure' (0..1), 'slide' (0..1)
    padDown(i, vel) {
      const s = FB.input.scale;
      FB.input.noteOn('pad' + i, U.scaleNote(i, s.root + 48 + FB.input.octave * 12, s.name), vel, { eng: this });
    }
    padUp(i) {
      FB.input.noteOff('pad' + i);
    }
    padLabel(i) {
      const s = FB.input.scale;
      return U.noteName(U.scaleNote(i, s.root + 48 + FB.input.octave * 12, s.name));
    }
    step() {}
    allOff() {}
    buildUI() {}
    draw() {}
    serialize() {
      const o = {};
      for (const k in this.params) o[k] = this.params[k].value;
      return o;
    }
    restore(o) {
      if (!o) return;
      for (const k in o) if (this.params[k]) this.set(k, o[k], { force: true });
    }
  }
  FB.Engine = Engine;

  // ------------------------------------------------------------------
  // UI widgets
  // ------------------------------------------------------------------
  const UI = (FB.ui = {});

  UI.format = function (p) {
    const v = p.value;
    if (p.fmt) return p.fmt(v);
    switch (p.unit) {
      case 'Hz':
        return v >= 1000 ? (v / 1000).toFixed(1) + 'k' : Math.round(v) + '';
      case 's':
        return v < 1 ? Math.round(v * 1000) + 'ms' : v.toFixed(2) + 's';
      case '%':
        return Math.round(v * 100) + '%';
      case 'st':
        return (v > 0 ? '+' : '') + (p.step ? v : v.toFixed(1)) + 'st';
      case 'ct':
        return Math.round(v) + 'ct';
      case 'note':
        return U.noteName(Math.round(v));
      default:
        return Math.abs(v) >= 10 ? Math.round(v) + '' : v.toFixed(2);
    }
  };

  // Rotary knob bound to engine param. Drag up/down, double-click resets,
  // right-click (or long press) arms MIDI learn.
  UI.knob = function (parent, engine, key, opts) {
    opts = opts || {};
    const p = engine.params[key];
    const r = 17;
    const C = 2 * Math.PI * r;
    const wrap = U.el('div', { class: 'knob' + (opts.big ? ' big' : ''), title: (p.help || p.label) + ' — drag, double-click to reset, right-click / long-press to MIDI-learn' });
    wrap.innerHTML =
      '<svg viewBox="0 0 44 44"><circle class="track" cx="22" cy="22" r="' + r + '" stroke-dasharray="' + 0.75 * C + ' ' + C + '"/>' +
      '<circle class="val" cx="22" cy="22" r="' + r + '" stroke-dasharray="0 ' + C + '"/>' +
      '<line class="ptr" x1="22" y1="22" x2="22" y2="9"/></svg>' +
      '<div class="kval"></div><div class="klabel">' + p.label + '</div>';
    const val = wrap.querySelector('.val');
    const ptr = wrap.querySelector('.ptr');
    const txt = wrap.querySelector('.kval');
    const update = () => {
      const n = engine.norm(key);
      val.setAttribute('stroke-dasharray', 0.75 * C * n + ' ' + C);
      ptr.setAttribute('transform', 'rotate(' + (-135 + 270 * n) + ' 22 22)');
      txt.textContent = UI.format(p);
    };
    p.listeners.add(update);
    update();

    let startY = 0, startN = 0, dragging = false, lp = null;
    wrap.addEventListener('pointerdown', (e) => {
      if (e.button === 2) return;
      e.preventDefault();
      wrap.setPointerCapture(e.pointerId);
      dragging = true;
      startY = e.clientY;
      startN = engine.norm(key);
      wrap.classList.add('active');
      lp = setTimeout(() => {
        if (Math.abs(e.clientY - startY) < 4 && e.pointerType !== 'mouse') {
          dragging = false;
          FB.midi.learn(engine, key, wrap);
        }
      }, 650);
    });
    wrap.addEventListener('pointermove', (e) => {
      if (!dragging) return;
      const dy = startY - e.clientY;
      if (Math.abs(dy) > 3) clearTimeout(lp);
      const sens = e.shiftKey ? 800 : 180;
      engine.setNorm(key, startN + dy / sens);
    });
    const end = () => {
      dragging = false;
      clearTimeout(lp);
      wrap.classList.remove('active');
    };
    wrap.addEventListener('pointerup', end);
    wrap.addEventListener('pointercancel', end);
    wrap.addEventListener('dblclick', () => engine.set(key, p.def));
    wrap.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      FB.midi.learn(engine, key, wrap);
    });
    wrap.addEventListener(
      'wheel',
      (e) => {
        e.preventDefault();
        engine.setNorm(key, engine.norm(key) - Math.sign(e.deltaY) * 0.02);
      },
      { passive: false }
    );
    wrap.dataset.param = engine.id + '.' + key;
    parent.appendChild(wrap);
    return wrap;
  };

  // Segmented button group for 'choice' params.
  UI.choice = function (parent, engine, key, opts) {
    opts = opts || {};
    const p = engine.params[key];
    const wrap = U.el('div', { class: 'choice' });
    if (!opts.noLabel) wrap.appendChild(U.el('span', { class: 'clabel', text: p.label }));
    const btns = U.el('div', { class: 'cbtns' });
    const map = {};
    p.options.forEach((o) => {
      const b = U.el('button', { text: (opts.names && opts.names[o]) || o, onclick: () => engine.set(key, o) });
      map[o] = b;
      btns.appendChild(b);
    });
    wrap.appendChild(btns);
    const update = () => Object.keys(map).forEach((o) => map[o].classList.toggle('on', o === p.value));
    p.listeners.add(update);
    update();
    parent.appendChild(wrap);
    return wrap;
  };

  // Small dropdown for 'choice' params with many options.
  UI.select = function (parent, engine, key, opts) {
    opts = opts || {};
    const p = engine.params[key];
    const wrap = U.el('label', { class: 'sel' });
    wrap.appendChild(U.el('span', { text: opts.label || p.label }));
    const s = U.el('select');
    p.options.forEach((o) => s.appendChild(U.el('option', { value: o, text: (opts.names && opts.names[o]) || o })));
    s.addEventListener('change', () => engine.set(key, s.value));
    const update = () => (s.value = p.value);
    p.listeners.add(update);
    update();
    wrap.appendChild(s);
    parent.appendChild(wrap);
    return wrap;
  };

  UI.toggle = function (parent, engine, key, opts) {
    opts = opts || {};
    const p = engine.params[key];
    const b = U.el('button', { class: 'toggle', text: opts.text || p.label, title: p.help || p.label });
    b.addEventListener('click', () => engine.set(key, !p.value));
    const update = () => b.classList.toggle('on', !!p.value);
    p.listeners.add(update);
    update();
    parent.appendChild(b);
    return b;
  };

  UI.section = function (parent, title, cls) {
    const s = U.el('div', { class: 'sect ' + (cls || '') });
    if (title) s.appendChild(U.el('h3', { text: title }));
    const body = U.el('div', { class: 'sect-body' });
    s.appendChild(body);
    parent.appendChild(s);
    return body;
  };

  UI.knobs = function (parent, engine, keys) {
    keys.forEach((k) => UI.knob(parent, engine, k));
  };

  // Canvas that tracks its CSS size at device pixel ratio.
  UI.canvas = function (parent, cls) {
    const c = U.el('canvas', { class: cls || '' });
    parent.appendChild(c);
    const o = { canvas: c, g: c.getContext('2d'), w: 1, h: 1, dpr: 1 };
    const fit = () => {
      const r = c.getBoundingClientRect();
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = Math.max(1, Math.round(r.width));
      const h = Math.max(1, Math.round(r.height));
      if (w !== o.w || h !== o.h || dpr !== o.dpr) {
        o.w = w;
        o.h = h;
        o.dpr = dpr;
        c.width = w * dpr;
        c.height = h * dpr;
        o.g.setTransform(dpr, 0, 0, dpr, 0, 0);
        if (o.onResize) o.onResize();
      }
    };
    if (window.ResizeObserver) new ResizeObserver(fit).observe(c);
    o.fit = fit;
    setTimeout(fit, 0);
    return o;
  };

  // Queue of things to draw when the audio clock reaches their time.
  UI.eventQueue = function () {
    const q = [];
    return {
      push(t, data) {
        q.push({ t, data });
        if (q.length > 600) q.splice(0, q.length - 600);
      },
      drain(now, fn) {
        let i = 0;
        while (i < q.length) {
          if (q[i].t <= now) {
            fn(q[i].data, q[i].t);
            q.splice(i, 1);
          } else i++;
        }
      },
    };
  };
})(window.FB);
