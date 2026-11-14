/* FoxBox — note routing and the controller-free play surface:
   on-screen expressive keyboard, 4x4 pads, computer keyboard,
   hold/latch, sustain pedal and a tempo-synced arpeggiator. */
(function (FB) {
  'use strict';
  const U = FB.util;

  const I = (FB.input = {
    held: new Map(), // key -> {note, vel, eng, dev, ch, phys, bend, pressure, slide}
    hold: false,
    sustainOn: false,
    octave: 0,
    scale: { root: 0, name: 'Minor pentatonic' },
    expressive: true,
    arp: { on: false, mode: 'Up', rate: 2, octaves: 1, gate: 0.6, i: 0, dir: 1, n: 0 },
  });

  const subs = [];
  I.onChange = (fn) => subs.push(fn);
  const emit = () => subs.forEach((fn) => fn());

  const target = () => FB.app.target();

  function anyPhysical() {
    for (const e of I.held.values()) if (e.phys) return true;
    return false;
  }

  // ------------------------------------------------------------------
  // Core note on/off with hold, sustain and arp
  // ------------------------------------------------------------------
  I.noteOn = function (key, note, vel, meta) {
    meta = meta || {};
    if (I.hold && !anyPhysical()) I.releaseLatched();
    if (I.held.has(key)) finish(key);
    const eng = meta.eng || target();
    const e = { note, vel, eng, dev: meta.dev || null, ch: meta.ch != null ? meta.ch : -1, phys: true, bend: 0, pressure: 0, slide: 0.5 };
    I.held.set(key, e);
    if (!I.arp.on) eng.noteOn(key, note, vel);
    emit();
  };

  I.noteOff = function (key) {
    const e = I.held.get(key);
    if (!e) return;
    e.phys = false;
    if (I.hold || I.sustainOn) return emit();
    finish(key);
    emit();
  };

  function finish(key) {
    const e = I.held.get(key);
    if (!e) return;
    I.held.delete(key);
    if (!I.arp.on) e.eng.noteOff(key);
  }

  I.releaseLatched = function () {
    for (const [k, e] of Array.from(I.held)) if (!e.phys) finish(k);
    emit();
  };

  I.sustain = function (on) {
    I.sustainOn = on;
    if (!on && !I.hold) I.releaseLatched();
    emit();
  };

  I.setHold = function (on) {
    I.hold = on;
    if (!on && !I.sustainOn) I.releaseLatched();
    emit();
  };

  I.panic = function () {
    for (const k of Array.from(I.held.keys())) finish(k);
    I.held.clear();
    Object.values(FB.engines).forEach((e) => e.allOff());
    emit();
  };

  I.expr = function (key, kind, v) {
    const e = I.held.get(key);
    if (!e) return;
    e[kind] = v;
    if (!I.arp.on) e.eng.expr(key, kind, v);
  };

  I.modWheel = function (v) {
    const eng = target();
    if (eng.modParam) eng.setNorm(eng.modParam, v);
  };

  // ------------------------------------------------------------------
  // MIDI entry points (called by FB.midi)
  // ------------------------------------------------------------------
  I.midiNoteOn = function (dev, ch, note, vel) {
    if (dev.role === 'pads') {
      const i = (((note - dev.padBase) % 16) + 16) % 16;
      dev.padNotes.set(note, i);
      return I.padDown(i, vel);
    }
    I.noteOn('m:' + dev.id + ':' + ch + ':' + note, note, vel, { dev, ch });
  };
  I.midiNoteOff = function (dev, ch, note) {
    if (dev.role === 'pads') {
      const i = dev.padNotes.get(note);
      dev.padNotes.delete(note);
      if (i != null) I.padUp(i);
      return;
    }
    I.noteOff('m:' + dev.id + ':' + ch + ':' + note);
  };
  // Pitch bend / pressure / slide arrive per channel. With MPE each note
  // has its own channel; channel 1 (index 0) is the MPE master channel.
  I.midiChannelExpr = function (dev, ch, kind, v) {
    const masterMPE = dev.role === 'mpe' && ch === 0;
    for (const [k, e] of I.held) if (e.dev === dev && (e.ch === ch || masterMPE)) I.expr(k, kind, v);
  };
  I.midiBend = function (dev, ch, semis) {
    I.midiChannelExpr(dev, ch, 'bend', semis);
  };
  I.midiPolyPressure = function (dev, ch, note, v) {
    I.expr('m:' + dev.id + ':' + ch + ':' + note, 'pressure', v);
  };
  I.releaseDevice = function (dev) {
    for (const [k, e] of Array.from(I.held)) if (e.dev === dev) finish(k);
    emit();
  };

  // ------------------------------------------------------------------
  // Pads
  // ------------------------------------------------------------------
  I.padDown = function (i, vel) {
    target().padDown(i, vel);
    I.flashPad(i, true);
  };
  I.padUp = function (i) {
    target().padUp(i);
    I.flashPad(i, false);
  };

  // ------------------------------------------------------------------
  // Arpeggiator (runs on the transport clock)
  // ------------------------------------------------------------------
  I.setArp = function (on) {
    const a = I.arp;
    if (on === a.on) return;
    // Hand currently sounding notes over to the new mode.
    for (const [k, e] of I.held) {
      if (on) e.eng.noteOff(k);
    }
    a.on = on;
    a.i = 0;
    a.dir = 1;
    if (on && !FB.clock.playing) FB.clock.start();
    emit();
  };

  FB.clock.onStep(function (step, time, dur) {
    const a = I.arp;
    if (!a.on || step < 0) return;
    if (step % a.rate !== 0) return;
    const notes = Array.from(I.held.values()).sort((x, y) => x.note - y.note);
    if (!notes.length) return;
    let seq = [];
    for (let o = 0; o < a.octaves; o++) notes.forEach((n) => seq.push({ note: n.note + 12 * o, vel: n.vel, eng: n.eng }));
    if (a.mode === 'Down') seq.reverse();
    let pick;
    if (a.mode === 'Random') pick = seq[Math.floor(Math.random() * seq.length)];
    else if (a.mode === 'Up/Down' && seq.length > 1) {
      const cyc = seq.concat(seq.slice(1, -1).reverse());
      pick = cyc[a.i % cyc.length];
    } else if (a.mode === 'Chord') {
      seq.forEach((n) => {
        const id = 'arp' + a.n++;
        n.eng.noteOn(id, n.note, n.vel, time);
        n.eng.noteOff(id, time + dur * a.rate * a.gate);
      });
      a.i++;
      return;
    } else pick = seq[a.i % seq.length];
    a.i++;
    const id = 'arp' + a.n++;
    pick.eng.noteOn(id, pick.note, pick.vel, time);
    pick.eng.noteOff(id, time + dur * a.rate * a.gate);
  });

  // ------------------------------------------------------------------
  // On-screen keyboard (Seaboard-style: slide sideways to bend,
  // up/down for timbre, press harder on pressure-sensitive screens)
  // ------------------------------------------------------------------
  let kbEl = null;
  const pointers = new Map();

  I.buildKeyboard = function (el) {
    kbEl = el;
    const render = () => {
      el.innerHTML = '';
      const width = el.clientWidth || 800;
      const whiteW = width < 600 ? 40 : 46;
      const whites = U.clamp(Math.floor(width / whiteW), 8, 36);
      let note = 48 + I.octave * 12;
      const keys = [];
      let w = 0;
      while (w < whites) {
        const pc = note % 12;
        const black = [1, 3, 6, 8, 10].indexOf(pc) >= 0;
        keys.push({ note, black, w });
        if (!black) w++;
        note++;
      }
      const ww = 100 / whites;
      keys.forEach((k) => {
        const d = U.el('div', { class: 'key ' + (k.black ? 'black' : 'white'), 'data-note': k.note });
        if (k.black) {
          d.style.left = (k.w - 0.32) * ww + '%';
          d.style.width = ww * 0.64 + '%';
        } else {
          d.style.left = k.w * ww + '%';
          d.style.width = ww + '%';
          if (k.note % 12 === 0) d.appendChild(U.el('span', { class: 'kname', text: U.noteName(k.note) }));
        }
        el.appendChild(d);
      });
      I.refreshKeyboard();
    };
    I.renderKeyboard = render;
    render();
    if (window.ResizeObserver) new ResizeObserver(U.debounce(render, 120)).observe(el);

    el.addEventListener('pointerdown', (e) => {
      const keyEl = e.target.closest('.key');
      if (!keyEl) return;
      e.preventDefault();
      try {
        el.setPointerCapture(e.pointerId);
      } catch (_) {}
      const rect = keyEl.getBoundingClientRect();
      const yRel = U.clamp((e.clientY - rect.top) / rect.height, 0, 1);
      const vel = 0.3 + 0.7 * yRel;
      const whiteW = el.clientWidth / Math.max(1, el.querySelectorAll('.key.white').length);
      const st = { key: 't' + e.pointerId, note: +keyEl.dataset.note, x0: e.clientX, y0: e.clientY, whiteW };
      pointers.set(e.pointerId, st);
      I.noteOn(st.key, st.note, vel, { src: 'touch' });
      if (e.pointerType !== 'mouse' && e.pressure > 0 && e.pressure !== 0.5) I.expr(st.key, 'pressure', e.pressure);
    });
    el.addEventListener('pointermove', (e) => {
      const st = pointers.get(e.pointerId);
      if (!st) return;
      if (I.expressive) {
        const semis = (e.clientX - st.x0) / ((st.whiteW * 7) / 12);
        I.expr(st.key, 'bend', U.clamp(semis, -24, 24));
        I.expr(st.key, 'slide', U.clamp(0.5 + (st.y0 - e.clientY) / 140, 0, 1));
        if (e.pointerType !== 'mouse' && e.pressure > 0 && e.pressure !== 0.5) I.expr(st.key, 'pressure', e.pressure);
      } else {
        const over = document.elementFromPoint(e.clientX, e.clientY);
        const k = over && over.closest && over.closest('.key');
        if (k && +k.dataset.note !== st.note) {
          I.noteOff(st.key);
          st.note = +k.dataset.note;
          I.noteOn(st.key, st.note, 0.75, { src: 'touch' });
        }
      }
    });
    const up = (e) => {
      const st = pointers.get(e.pointerId);
      if (!st) return;
      pointers.delete(e.pointerId);
      I.noteOff(st.key);
    };
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);
    el.addEventListener('contextmenu', (e) => e.preventDefault());
  };

  I.refreshKeyboard = function () {
    if (!kbEl) return;
    const on = new Set();
    for (const e of I.held.values()) on.add(e.note);
    kbEl.querySelectorAll('.key').forEach((k) => {
      const n = +k.dataset.note;
      k.classList.toggle('on', on.has(n));
      k.classList.toggle('latched', on.has(n) && !Array.from(I.held.values()).some((e) => e.note === n && e.phys));
    });
  };
  I.onChange(() => I.refreshKeyboard());

  I.shiftOctave = function (d) {
    I.octave = U.clamp(I.octave + d, -3, 4);
    I.renderKeyboard && I.renderKeyboard();
    FB.app && FB.app.refreshHeader();
  };

  // ------------------------------------------------------------------
  // On-screen pads (4x4, like the Synido TempoPad C16)
  // ------------------------------------------------------------------
  let padEls = [];
  I.buildPads = function (el) {
    el.innerHTML = '';
    padEls = [];
    for (let row = 3; row >= 0; row--) {
      for (let col = 0; col < 4; col++) {
        const i = row * 4 + col;
        const p = U.el('div', { class: 'pad', 'data-i': i }, [U.el('span', { class: 'pnum', text: i + 1 }), U.el('span', { class: 'plabel' })]);
        padEls[i] = p;
        el.appendChild(p);
      }
    }
    const active = new Map();
    el.addEventListener('pointerdown', (e) => {
      const p = e.target.closest('.pad');
      if (!p) return;
      e.preventDefault();
      try {
        el.setPointerCapture(e.pointerId);
      } catch (_) {}
      const r = p.getBoundingClientRect();
      const dx = (e.clientX - (r.left + r.width / 2)) / (r.width / 2);
      const dy = (e.clientY - (r.top + r.height / 2)) / (r.height / 2);
      let vel = U.clamp(1 - 0.5 * Math.hypot(dx, dy), 0.35, 1);
      if (e.pointerType !== 'mouse' && e.pressure > 0 && e.pressure !== 0.5) vel = U.clamp(e.pressure * 1.2, 0.2, 1);
      const i = +p.dataset.i;
      active.set(e.pointerId, i);
      I.padDown(i, vel);
    });
    const up = (e) => {
      const i = active.get(e.pointerId);
      if (i == null) return;
      active.delete(e.pointerId);
      I.padUp(i);
    };
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);
    el.addEventListener('contextmenu', (e) => e.preventDefault());
    I.refreshPads();
  };

  I.refreshPads = function () {
    const eng = target();
    padEls.forEach((p, i) => {
      p.querySelector('.plabel').textContent = eng.padLabel(i);
      const st = eng.padState ? eng.padState(i) : false;
      p.classList.toggle('lit', !!st);
    });
  };

  I.flashPad = function (i, on) {
    const p = padEls[i];
    if (!p) return;
    p.classList.toggle('hit', on);
    if (!on) setTimeout(I.refreshPads, 0);
  };

  // ------------------------------------------------------------------
  // Computer keyboard
  // ------------------------------------------------------------------
  const KEYMAP = { KeyA: 0, KeyW: 1, KeyS: 2, KeyE: 3, KeyD: 4, KeyF: 5, KeyT: 6, KeyG: 7, KeyY: 8, KeyH: 9, KeyU: 10, KeyJ: 11, KeyK: 12, KeyO: 13, KeyL: 14, KeyP: 15, Semicolon: 16, Quote: 17 };
  const PADKEYS = { Digit1: 0, Digit2: 1, Digit3: 2, Digit4: 3, Digit5: 4, Digit6: 5, Digit7: 6, Digit8: 7, Digit9: 8, Digit0: 9, Minus: 10, Equal: 11 };
  const down = new Set();

  I.initKeys = function () {
    const typing = (e) => {
      const t = e.target;
      return t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA' || t.isContentEditable);
    };
    window.addEventListener('keydown', (e) => {
      if (typing(e) || e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.repeat) return e.code in KEYMAP || e.code in PADKEYS ? e.preventDefault() : undefined;
      if (e.code === 'Space') {
        e.preventDefault();
        return FB.clock.toggle();
      }
      if (e.code === 'KeyZ') return I.shiftOctave(-1);
      if (e.code === 'KeyX') return I.shiftOctave(1);
      if (e.code === 'Escape') return I.panic();
      if (e.code in KEYMAP) {
        down.add(e.code);
        I.noteOn('k' + e.code, 60 + I.octave * 12 + KEYMAP[e.code], 0.8, { src: 'kbd' });
      } else if (e.code in PADKEYS) {
        down.add(e.code);
        I.padDown(PADKEYS[e.code], 0.85);
      }
    });
    window.addEventListener('keyup', (e) => {
      if (!down.has(e.code)) return;
      down.delete(e.code);
      if (e.code in KEYMAP) I.noteOff('k' + e.code);
      else if (e.code in PADKEYS) I.padUp(PADKEYS[e.code]);
    });
    window.addEventListener('blur', () => {
      down.forEach((c) => (c in KEYMAP ? I.noteOff('k' + c) : I.padUp(PADKEYS[c])));
      down.clear();
    });
  };
})(window.FB);
