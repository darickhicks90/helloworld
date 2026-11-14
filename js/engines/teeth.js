/* Teeth — a rhythmic audio mangler.
   Everything FoxBox plays runs through Teeth's jaw. Three effects —
   Pitch ("Grind"), Delay ("Echo") and Modulation ("Gnash": flanger +
   ring mod) — are blended on faders, played on an XY touchpad, and
   chopped rhythmically by a 16-step "bite" pattern locked to the clock.
   Touch the pad to bite; lock the jaw to keep biting. */
(function (FB) {
  'use strict';
  const U = FB.util;
  const DIVS = { '1/32': 0.5, '1/16': 1, '1/16.': 1.5, '1/8t': 4 / 3, '1/8': 2, '1/8.': 3, '1/4': 4, '1/2': 8 };
  const XY_TARGETS = ['pitch', 'grind', 'division', 'feedback', 'tone', 'modRate', 'modDepth', 'ring', 'ringFreq', 'chain', 'mix', 'pitchLvl', 'delayLvl', 'modLvl', 'snap'];

  class Teeth extends FB.Engine {
    constructor() {
      super('teeth', 'Teeth');
      this.isInstrument = false;
      this.color = '#7a4a2a';
      this.modParam = 'mix';
      const P = this.param.bind(this);
      P('engaged', { label: 'Lock jaw', type: 'bool', def: false, help: 'Keep Teeth biting all the time (otherwise it bites while you touch the XY pad)' });
      P('mix', { label: 'Bite', min: 0, max: 1, def: 0.85, unit: '%', help: 'Effect (wet) level' });
      P('clench', { label: 'Clench', min: 0, max: 1, def: 0.35, unit: '%', help: 'How much the clean sound ducks while biting' });
      P('pitch', { label: 'Pitch', min: -24, max: 24, def: 7, unit: 'st', help: 'Pitch shift in semitones' });
      P('grind', { label: 'Grind', min: 0.015, max: 0.25, def: 0.06, curve: 'exp', unit: 's', help: 'Pitch shifter grain size — small is glitchy, big is smooth' });
      P('pitchLvl', { label: 'Pitch', min: 0, max: 1, def: 0.6, unit: '%' });
      P('chain', { label: 'Chain', min: 0, max: 1, def: 0.35, unit: '%', help: 'Feed the pitch shifter into the delay for spiralling echoes' });
      P('division', { label: 'Echo time', type: 'choice', options: Object.keys(DIVS), def: '1/8.' });
      P('feedback', { label: 'Feedback', min: 0, max: 0.95, def: 0.5, unit: '%' });
      P('tone', { label: 'Echo tone', min: 300, max: 14000, def: 3500, curve: 'exp', unit: 'Hz' });
      P('delayLvl', { label: 'Echo', min: 0, max: 1, def: 0.6, unit: '%' });
      P('modRate', { label: 'Gnash rate', min: 0.05, max: 20, def: 0.45, curve: 'exp', unit: 'Hz', help: 'Flanger speed' });
      P('modDepth', { label: 'Gnash depth', min: 0, max: 1, def: 0.6, unit: '%' });
      P('modFb', { label: 'Gnash fb', min: 0, max: 0.92, def: 0.55, unit: '%' });
      P('ring', { label: 'Ring', min: 0, max: 1, def: 0, unit: '%', help: 'Ring-modulation amount — metallic' });
      P('ringFreq', { label: 'Ring freq', min: 20, max: 2500, def: 110, curve: 'exp', unit: 'Hz' });
      P('modLvl', { label: 'Gnash', min: 0, max: 1, def: 0.5, unit: '%' });
      P('gateMode', { label: 'Rhythm', type: 'choice', options: ['Open', 'Pattern'], def: 'Pattern', help: 'Open: bite continuously. Pattern: bite on the lit teeth, in time.' });
      P('snap', { label: 'Snap', min: 0.1, max: 1, def: 0.6, unit: '%', help: 'How long each bite stays open within its step' });
      P('xTarget', { label: 'X axis', type: 'choice', options: XY_TARGETS, def: 'pitch' });
      P('yTarget', { label: 'Y axis', type: 'choice', options: XY_TARGETS, def: 'feedback' });
      P('spring', { label: 'Spring back', type: 'bool', def: true, help: 'Return the XY settings to where they were when you let go' });

      this.bites = [1, 0, 0, 1, 0, 0, 1, 0, 1, 0, 1, 1, 0, 0, 1, 0];
      this.touching = false;
      this.momentary = 0;
      this.trail = [];
      this.build();
      for (const k in this.params) this.onParam(k, this.get(k));
      FB.clock.onStep((s, t, d) => this.step(s, t, d));
    }

    build() {
      const ctx = this.ctx;
      const g = () => ctx.createGain();
      this.input = FB.audio.teethSend;
      this.dry = g();
      this.wetIn = g();
      this.wetSum = g();
      this.gate = g();
      this.wetOut = g();
      this.wetOut.gain.value = 0;
      this.out = g();
      this.input.connect(this.dry).connect(this.out);
      this.input.connect(this.wetIn);

      // Pitch
      this.pitchNode = FB.audio.worklet('fb-pitch') || g();
      this.pitchLvlG = g();
      this.wetIn.connect(this.pitchNode);
      this.pitchNode.connect(this.pitchLvlG).connect(this.wetSum);

      // Delay (with optional pitch chained in)
      this.delayIn = g();
      this.delay = ctx.createDelay(4);
      this.delayTone = ctx.createBiquadFilter();
      this.delayTone.type = 'lowpass';
      this.delayFb = g();
      this.delayLvlG = g();
      this.chainG = g();
      this.wetIn.connect(this.delayIn);
      this.pitchNode.connect(this.chainG).connect(this.delayIn);
      this.delayIn.connect(this.delay);
      this.delay.connect(this.delayTone);
      this.delayTone.connect(this.delayFb).connect(this.delay);
      this.delayTone.connect(this.delayLvlG).connect(this.wetSum);

      // Modulation: flanger + ring modulator
      this.flange = ctx.createDelay(0.05);
      this.flange.delayTime.value = 0.004;
      this.flangeFb = g();
      this.lfo = ctx.createOscillator();
      this.lfoG = g();
      this.lfo.connect(this.lfoG).connect(this.flange.delayTime);
      this.lfo.start();
      this.wetIn.connect(this.flange);
      this.flange.connect(this.flangeFb).connect(this.flange);
      this.ringVCA = g();
      this.ringOsc = ctx.createOscillator();
      this.ringDepth = g();
      this.ringOsc.connect(this.ringDepth).connect(this.ringVCA.gain);
      this.ringOsc.start();
      this.flangeMix = g();
      this.wetIn.connect(this.flangeMix);
      this.flange.connect(this.flangeMix);
      this.flangeMix.gain.value = 0.6;
      this.flangeMix.connect(this.ringVCA);
      this.modLvlG = g();
      this.ringVCA.connect(this.modLvlG).connect(this.wetSum);

      this.wetSum.connect(this.gate).connect(this.wetOut).connect(this.out);

      // Stutter (momentary loop capture)
      this.stutIn = g();
      this.stutIn.gain.value = 0;
      this.stutDelay = ctx.createDelay(4);
      this.stutFb = g();
      this.stutFb.gain.value = 0;
      this.stutOut = g();
      this.stutOut.gain.value = 0;
      this.input.connect(this.stutIn).connect(this.stutDelay);
      this.stutDelay.connect(this.stutFb).connect(this.stutDelay);
      this.stutDelay.connect(this.stutOut).connect(this.out);

      this.out.connect(FB.audio.masterBus);
    }

    active() {
      return this.get('engaged') || this.touching || this.momentary > 0;
    }

    applyActive(t) {
      t = t || this.now();
      const on = this.active();
      this.wetOut.gain.setTargetAtTime(on ? this.get('mix') : 0, t, 0.015);
      const patt = this.get('gateMode') === 'Pattern' && FB.clock.playing;
      if (!patt || !on) {
        this.gate.gain.setTargetAtTime(1, t, 0.01);
        this.dry.gain.setTargetAtTime(on ? 1 - this.get('clench') : 1, t, 0.015);
      }
    }

    delaySeconds() {
      return Math.min(3.9, DIVS[this.get('division')] * FB.clock.stepDur());
    }

    onParam(key, v) {
      const t = this.now();
      const s = (param, val) => param.setTargetAtTime(val, t, 0.02);
      switch (key) {
        case 'engaged':
        case 'mix':
        case 'clench':
        case 'gateMode':
          this.applyActive();
          break;
        case 'pitch':
          if (this.pitchNode.parameters) s(this.pitchNode.parameters.get('semitones'), v);
          break;
        case 'grind':
          if (this.pitchNode.parameters) this.pitchNode.parameters.get('window').setValueAtTime(v, t);
          break;
        case 'pitchLvl':
          s(this.pitchLvlG.gain, FB.audio.hasWorklets ? v : 0);
          break;
        case 'chain':
          s(this.chainG.gain, v);
          break;
        case 'division':
          this.delay.delayTime.setTargetAtTime(this.delaySeconds(), t, 0.05);
          break;
        case 'feedback':
          s(this.delayFb.gain, v);
          break;
        case 'tone':
          s(this.delayTone.frequency, v);
          break;
        case 'delayLvl':
          s(this.delayLvlG.gain, v);
          break;
        case 'modRate':
          s(this.lfo.frequency, v);
          break;
        case 'modDepth':
          s(this.lfoG.gain, v * 0.0035);
          break;
        case 'modFb':
          s(this.flangeFb.gain, v);
          break;
        case 'ring':
          s(this.ringVCA.gain, 1 - v);
          s(this.ringDepth.gain, v);
          break;
        case 'ringFreq':
          s(this.ringOsc.frequency, v);
          break;
        case 'modLvl':
          s(this.modLvlG.gain, v);
          break;
        case 'xTarget':
        case 'yTarget':
          this.updateAxisLabels && this.updateAxisLabels();
          break;
      }
    }

    step(s, t, dur) {
      if (s < 0) {
        this.applyActive();
        return;
      }
      // Keep the echo locked to tempo.
      this.delay.delayTime.setTargetAtTime(this.delaySeconds(), t, 0.05);
      if (this.get('gateMode') !== 'Pattern' || !this.active()) return;
      const on = this.bites[s % 16];
      const clench = this.get('clench');
      if (on) {
        this.gate.gain.setTargetAtTime(1, t, 0.003);
        this.gate.gain.setTargetAtTime(0, t + dur * this.get('snap'), 0.012);
        this.dry.gain.setTargetAtTime(1 - clench, t, 0.004);
        this.dry.gain.setTargetAtTime(1, t + dur * this.get('snap'), 0.012);
      } else {
        this.gate.gain.setTargetAtTime(0, t, 0.006);
        this.dry.gain.setTargetAtTime(1, t, 0.006);
      }
    }

    // Capture a slice of audio at the next step and loop it.
    stutter(steps) {
      const ctx = this.ctx;
      const dur = steps * FB.clock.stepDur();
      const t = FB.clock.playing ? Math.max(FB.clock.nextTime, ctx.currentTime + 0.005) : ctx.currentTime + 0.005;
      this.stutDelay.delayTime.setValueAtTime(dur, t);
      [this.stutIn.gain, this.stutFb.gain, this.stutOut.gain].forEach((p) => p.cancelScheduledValues(t));
      this.stutIn.gain.setValueAtTime(1, t);
      this.stutIn.gain.setValueAtTime(0, t + dur);
      this.stutFb.gain.setValueAtTime(1, t);
      this.stutOut.gain.setValueAtTime(0, t);
      this.stutOut.gain.setTargetAtTime(1, t + dur, 0.003);
      this.dry.gain.setTargetAtTime(0, t + dur, 0.004);
      this.wetOut.gain.setTargetAtTime(0, t + dur, 0.004);
      this.stuttering = true;
    }
    stutterOff() {
      if (!this.stuttering) return;
      this.stuttering = false;
      const t = this.now();
      [this.stutIn.gain, this.stutFb.gain, this.stutOut.gain].forEach((p) => {
        p.cancelScheduledValues(t);
      });
      this.stutIn.gain.setValueAtTime(0, t);
      this.stutOut.gain.setTargetAtTime(0, t, 0.01);
      this.stutFb.gain.setValueAtTime(0, t + 0.06);
      this.dry.gain.cancelScheduledValues(t);
      this.wetOut.gain.cancelScheduledValues(t);
      this.applyActive(t);
      this.dry.gain.setTargetAtTime(this.active() ? 1 - this.get('clench') : 1, t, 0.01);
    }

    // Momentary performance moves (used by pads).
    hold(changes) {
      const saved = {};
      for (const k in changes) {
        saved[k] = this.get(k);
        this.set(k, changes[k]);
      }
      this.momentary++;
      this.applyActive();
      return () => {
        for (const k in saved) this.set(k, saved[k]);
        this.momentary = Math.max(0, this.momentary - 1);
        this.applyActive();
      };
    }

    // Teeth doesn't make notes; keys keep playing the last instrument.
    noteOn() {}
    noteOff() {}

    padDown(i) {
      this.padRel = this.padRel || {};
      if (this.padRel[i]) this.padRel[i]();
      const H = (c) => (this.padRel[i] = this.hold(c));
      switch (i) {
        case 0:
          this.stutter(1);
          this.padRel[i] = () => this.stutterOff();
          break;
        case 1:
          this.stutter(2);
          this.padRel[i] = () => this.stutterOff();
          break;
        case 2:
          this.stutter(4);
          this.padRel[i] = () => this.stutterOff();
          break;
        case 3:
          H({ pitch: -12, pitchLvl: 1, delayLvl: 0.2, modLvl: 0 });
          break;
        case 4:
          H({ pitch: 12, pitchLvl: 1, delayLvl: 0.2, modLvl: 0 });
          break;
        case 5:
          H({ delayLvl: 1, feedback: 0.85, pitchLvl: 0, gateMode: 'Open' });
          break;
        case 6:
          H({ ring: 1, modLvl: 1, ringFreq: 300 + Math.random() * 900 });
          break;
        case 7:
          H({ gateMode: 'Open', mix: 1 });
          break;
        case 8:
          this.bites = this.bites.map(() => 1);
          break;
        case 9:
          this.bites = this.bites.map((_, k) => (k % 4 === 2 ? 1 : 0));
          break;
        case 10:
          this.bites = this.bites.map((_, k) => (k % 2 === 0 ? 1 : 0));
          break;
        case 11:
          this.bites = this.bites.map(() => (Math.random() < 0.45 ? 1 : 0));
          break;
        case 12:
          this.set('engaged', !this.get('engaged'));
          break;
        case 13:
          this.set('pitch', [-12, -7, -5, 5, 7, 12, 19][Math.floor(Math.random() * 7)]);
          break;
        case 14:
          this.set('division', Object.keys(DIVS)[Math.floor(Math.random() * 8)]);
          break;
        case 15:
          this.randomize();
          break;
      }
      if (i >= 8) {
        this.renderTeeth && this.renderTeeth();
        FB.state.dirty();
      }
    }
    padUp(i) {
      if (this.padRel && this.padRel[i]) {
        this.padRel[i]();
        this.padRel[i] = null;
      }
    }
    padLabel(i) {
      return ['Stutter 16', 'Stutter 8', 'Stutter 4', 'Drop 8va', 'Rise 8va', 'Echo throw', 'Ring', 'Full bite', 'All teeth', 'Offbeat', 'Eighths', 'Random', 'Lock jaw', 'New pitch', 'New time', 'Randomize'][i];
    }
    padState(i) {
      return i === 12 && this.get('engaged');
    }

    randomize() {
      const r = Math.random;
      ['pitch', 'grind', 'feedback', 'tone', 'modRate', 'modDepth', 'chain'].forEach((k) => this.setNorm(k, r()));
      this.set('ring', r() < 0.3 ? r() : 0);
      this.set('pitch', Math.round(this.get('pitch')));
      this.bites = this.bites.map(() => (r() < 0.5 ? 1 : 0));
      this.renderTeeth && this.renderTeeth();
    }

    serialize() {
      const o = super.serialize();
      o.bites = this.bites.slice();
      o.engaged = false;
      return o;
    }
    restore(o) {
      super.restore(o);
      if (o && o.bites) this.bites = o.bites.slice(0, 16);
      this.renderTeeth && this.renderTeeth();
    }

    buildUI(root) {
      const UI = FB.ui;
      const top = U.el('div', { class: 'teeth-top' });
      root.appendChild(top);

      // XY pad
      const xyWrap = U.el('div', { class: 'xy-wrap' });
      top.appendChild(xyWrap);
      const xyBox = U.el('div', { class: 'xy-box' });
      xyWrap.appendChild(xyBox);
      this.xy = UI.canvas(xyBox, 'xy');
      this.xyLabels = { x: U.el('div', { class: 'xy-label xl' }), y: U.el('div', { class: 'xy-label yl' }), hint: U.el('div', { class: 'xy-hint', text: 'Touch & drag to bite' }) };
      xyBox.appendChild(this.xyLabels.x);
      xyBox.appendChild(this.xyLabels.y);
      xyBox.appendChild(this.xyLabels.hint);
      const axes = U.el('div', { class: 'xy-axes' });
      UI.select(axes, this, 'xTarget', { label: 'X →', names: this.axisNames() });
      UI.select(axes, this, 'yTarget', { label: 'Y ↑', names: this.axisNames() });
      UI.toggle(axes, this, 'spring');
      xyWrap.appendChild(axes);
      this.bindXY();

      // Jaw: bite pattern + effect faders
      const right = U.el('div', { class: 'teeth-right' });
      top.appendChild(right);
      const jawBar = U.el('div', { class: 'toolbar' });
      UI.toggle(jawBar, this, 'engaged', { text: 'Lock jaw' });
      UI.choice(jawBar, this, 'gateMode', { noLabel: true });
      const play = U.el('button', { class: 'accent', text: '▶ Play' });
      play.onclick = () => FB.clock.toggle();
      FB.clock.onChange(() => (play.textContent = FB.clock.playing ? '■ Stop' : '▶ Play'));
      jawBar.appendChild(play);
      right.appendChild(jawBar);
      const jaw = U.el('div', { class: 'jaw', title: 'Bite pattern — click teeth to toggle' });
      right.appendChild(jaw);
      this.toothEls = [];
      this.renderTeeth = () => {
        jaw.innerHTML = '';
        this.toothEls = this.bites.map((b, i) => {
          const t = U.el('div', { class: 'tooth' + (b ? ' on' : '') + (i % 4 === 0 ? ' beat' : '') });
          t.onpointerdown = (e) => {
            e.preventDefault();
            this.bites[i] = this.bites[i] ? 0 : 1;
            t.classList.toggle('on', !!this.bites[i]);
            FB.state.dirty();
          };
          jaw.appendChild(t);
          return t;
        });
      };
      this.renderTeeth();

      const faders = U.el('div', { class: 'faders' });
      right.appendChild(faders);
      [['pitchLvl', 'Grind', 'pitch'], ['delayLvl', 'Echo', 'delay'], ['modLvl', 'Gnash', 'mod'], ['mix', 'Bite', 'bite']].forEach(([k, name, cls]) => this.fader(faders, k, name, cls));

      const grid = U.el('div', { class: 'sections' });
      root.appendChild(grid);
      const pitch = UI.section(grid, 'Grind (pitch)');
      UI.knobs(pitch, this, ['pitch', 'grind', 'chain']);
      if (!FB.audio.hasWorklets) pitch.appendChild(U.el('div', { class: 'note', text: 'Pitch shifting needs AudioWorklet support (Chrome, Edge, Firefox, Safari 14.1+).' }));
      const echo = UI.section(grid, 'Echo (delay)');
      UI.select(echo, this, 'division');
      UI.knobs(echo, this, ['feedback', 'tone']);
      UI.knobs(UI.section(grid, 'Gnash (modulation)'), this, ['modRate', 'modDepth', 'modFb', 'ring', 'ringFreq']);
      UI.knobs(UI.section(grid, 'Jaw'), this, ['clench', 'snap']);
    }

    axisNames() {
      const n = {};
      XY_TARGETS.forEach((k) => {
        const lbl = this.params[k].label;
        n[k] = { pitchLvl: 'Grind level', delayLvl: 'Echo level', modLvl: 'Gnash level', mix: 'Bite (wet)', pitch: 'Pitch shift' }[k] || lbl;
      });
      return n;
    }

    fader(parent, key, name, cls) {
      const p = this.params[key];
      const wrap = U.el('div', { class: 'fader ' + cls, title: name + ' — right-click to MIDI-learn' });
      const track = U.el('div', { class: 'ftrack' });
      const fill = U.el('div', { class: 'ffill' });
      const knob = U.el('div', { class: 'fknob' });
      track.appendChild(fill);
      track.appendChild(knob);
      wrap.appendChild(track);
      const val = U.el('div', { class: 'kval' });
      wrap.appendChild(val);
      wrap.appendChild(U.el('div', { class: 'klabel', text: name }));
      const update = () => {
        const n = this.norm(key);
        fill.style.height = n * 100 + '%';
        knob.style.bottom = 'calc(' + n * 100 + '% - 7px)';
        val.textContent = FB.ui.format(p);
      };
      p.listeners.add(update);
      update();
      const setFrom = (e) => {
        const r = track.getBoundingClientRect();
        this.setNorm(key, 1 - (e.clientY - r.top) / r.height);
      };
      let drag = false;
      track.addEventListener('pointerdown', (e) => {
        if (e.button === 2) return;
        drag = true;
        track.setPointerCapture(e.pointerId);
        setFrom(e);
      });
      track.addEventListener('pointermove', (e) => drag && setFrom(e));
      track.addEventListener('pointerup', () => (drag = false));
      wrap.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        FB.midi.learn(this, key, wrap);
      });
      wrap.addEventListener('dblclick', () => this.set(key, p.def));
      parent.appendChild(wrap);
    }

    bindXY() {
      const c = this.xy.canvas;
      let saved = null;
      let pid = null;
      const apply = (e) => {
        const r = c.getBoundingClientRect();
        const x = U.clamp((e.clientX - r.left) / r.width, 0, 1);
        const y = U.clamp(1 - (e.clientY - r.top) / r.height, 0, 1);
        this.setNorm(this.get('xTarget'), x);
        if (this.get('yTarget') !== this.get('xTarget')) this.setNorm(this.get('yTarget'), y);
        this.trail.push({ x, y, t: performance.now() });
        if (this.trail.length > 80) this.trail.shift();
      };
      c.addEventListener('pointerdown', (e) => {
        if (pid != null) return;
        e.preventDefault();
        pid = e.pointerId;
        c.setPointerCapture(e.pointerId);
        const xk = this.get('xTarget'), yk = this.get('yTarget');
        saved = { [xk]: this.get(xk), [yk]: this.get(yk) };
        this.touching = true;
        apply(e);
        this.applyActive();
      });
      c.addEventListener('pointermove', (e) => e.pointerId === pid && apply(e));
      const up = (e) => {
        if (e.pointerId !== pid) return;
        pid = null;
        this.touching = false;
        if (this.get('spring') && saved) for (const k in saved) this.set(k, saved[k]);
        saved = null;
        this.applyActive();
      };
      c.addEventListener('pointerup', up);
      c.addEventListener('pointercancel', up);
    }

    draw() {
      // Highlight the current tooth.
      const vs = FB.clock.visualStep;
      if (this.toothEls) this.toothEls.forEach((t, i) => t.classList.toggle('now', vs >= 0 && i === vs % 16));

      const cv = this.xy;
      if (!cv) return;
      const UI = FB.ui, PAL = FB.PAL, P = UI.PX;
      const g = cv.g, W = cv.w, H = cv.h;
      const on = this.active();
      if (!this.spr) this.spr = { acorn: UI.sprite(UI.SPRITES.acorn, null, true) };
      // Gingham tablecloth: aqua when resting, orange when biting.
      g.fillStyle = PAL.cream;
      g.fillRect(0, 0, W, H);
      const cell = P * 8;
      g.fillStyle = on ? 'rgba(232,116,44,0.28)' : 'rgba(63,184,175,0.22)';
      for (let x = 0; x < W; x += cell * 2) g.fillRect(x, 0, cell, H);
      for (let y = 0; y < H; y += cell * 2) g.fillRect(0, y, W, cell);
      if (on && this.get('gateMode') === 'Pattern' && FB.clock.playing && vs >= 0 && this.bites[vs % 16]) {
        g.fillStyle = 'rgba(232,116,44,0.18)';
        g.fillRect(0, 0, W, H);
      }
      const now = performance.now();
      this.trail = this.trail.filter((p) => now - p.t < 900);
      this.trail.forEach((p) => {
        const a = 1 - (now - p.t) / 900;
        g.fillStyle = a > 0.5 ? PAL.orange : PAL.orangeL;
        UI.dot(g, p.x * W - P, (1 - p.y) * H - P, a > 0.5 ? 3 : 2);
      });
      const x = this.norm(this.get('xTarget')) * W;
      const y = (1 - this.norm(this.get('yTarget'))) * H;
      g.fillStyle = PAL.brown;
      for (let k = 0; k < H; k += P * 3) UI.dot(g, x, k);
      for (let k = 0; k < W; k += P * 3) UI.dot(g, k, y);
      UI.drawSprite(g, this.spr.acorn, x - 21, y - 24 - (on ? Math.round(Math.sin(now / 90) + 1) * P : 0), 2);
      // Labels live in the DOM so the pixel font stays crisp.
      if (this.xyLabels) {
        const names = this.axisNames();
        const xt = this.get('xTarget'), yt = this.get('yTarget');
        const xs = 'X: ' + names[xt] + ' ' + FB.ui.format(this.params[xt]);
        const ys = 'Y: ' + names[yt] + ' ' + FB.ui.format(this.params[yt]);
        if (this.xyLabels.x.textContent !== xs) this.xyLabels.x.textContent = xs;
        if (this.xyLabels.y.textContent !== ys) this.xyLabels.y.textContent = ys;
        this.xyLabels.hint.style.display = on ? 'none' : '';
      }
    }
  }

  FB.Teeth = Teeth;
})(window.FB);
