/* Gusts — a virtual analog synth with a wind-inspired interface.
   Two oscillators ("Current" and "Crosswind"), a sub ("Undertow"),
   whistling filtered noise ("Breath"), a resonant low-pass ("Gale"/"Howl")
   with its own envelope ("Gust"), amp envelope (Rise/Linger/Hover/Fade)
   and a "Flutter" LFO. Fully MPE aware: per-note bend, pressure and slide. */
(function (FB) {
  'use strict';
  const U = FB.util;
  const SHAPES = ['sawtooth', 'square', 'triangle', 'sine'];
  const SHAPE_NAMES = { sawtooth: 'Saw', square: 'Square', triangle: 'Tri', sine: 'Sine' };

  class Gusts extends FB.Engine {
    constructor() {
      super('gusts', 'Gusts');
      this.color = '#9fd8ff';
      this.modParam = 'gale';
      const P = this.param.bind(this);
      P('shapeA', { label: 'Current', type: 'choice', options: SHAPES, def: 'sawtooth' });
      P('shapeB', { label: 'Crosswind', type: 'choice', options: SHAPES, def: 'square' });
      P('blend', { label: 'Blend', min: 0, max: 1, def: 0.35, unit: '%', help: 'Mix between Current and Crosswind oscillators' });
      P('drift', { label: 'Drift', min: 0, max: 50, def: 9, unit: 'ct', help: 'Detune between the oscillators' });
      P('undertow', { label: 'Undertow', min: 0, max: 1, def: 0.3, unit: '%', help: 'Sub oscillator, one octave down' });
      P('breath', { label: 'Breath', min: 0, max: 1, def: 0.15, unit: '%', help: 'Airy noise tuned to the note' });
      P('whistle', { label: 'Whistle', min: 0, max: 1, def: 0.45, unit: '%', help: 'How tightly the breath noise whistles at the note pitch' });
      P('gale', { label: 'Gale', min: 60, max: 16000, def: 2200, curve: 'exp', unit: 'Hz', help: 'Filter cutoff' });
      P('howl', { label: 'Howl', min: 0.2, max: 22, def: 3, curve: 'exp', help: 'Filter resonance' });
      P('gust', { label: 'Gust', min: 0, max: 1, def: 0.45, unit: '%', help: 'Filter envelope amount' });
      P('rise', { label: 'Rise', min: 0.001, max: 4, def: 0.012, curve: 'exp', unit: 's', help: 'Attack' });
      P('linger', { label: 'Linger', min: 0.01, max: 4, def: 0.45, curve: 'exp', unit: 's', help: 'Decay' });
      P('hover', { label: 'Hover', min: 0, max: 1, def: 0.6, unit: '%', help: 'Sustain level' });
      P('fade', { label: 'Fade', min: 0.01, max: 8, def: 0.6, curve: 'exp', unit: 's', help: 'Release' });
      P('flutterRate', { label: 'Flutter', min: 0.05, max: 14, def: 4.5, curve: 'exp', unit: 'Hz', help: 'LFO speed' });
      P('flutterDepth', { label: 'Flutter amt', min: 0, max: 1, def: 0.12, unit: '%', help: 'LFO depth on pitch and filter' });
      P('glide', { label: 'Glide', min: 0, max: 1, def: 0, unit: 's', help: 'Portamento between notes' });
      P('level', { label: 'Level', min: 0, max: 1, def: 0.75, unit: '%' });

      const ctx = this.ctx;
      this.bus = ctx.createGain();
      this.bus.gain.value = this.get('level');
      this.bus.connect(this.output);
      this.lfo = ctx.createOscillator();
      this.lfo.frequency.value = this.get('flutterRate');
      this.lfoPitch = ctx.createGain();
      this.lfoFilter = ctx.createGain();
      this.lfo.connect(this.lfoPitch);
      this.lfo.connect(this.lfoFilter);
      this.lfo.start();
      this.applyLfo();
      this.voices = new Map();
      this.lastFreq = null;
      // visuals
      this.particles = [];
      this.gusts = [];
      this.energy = 0;
    }

    applyLfo() {
      const t = this.now();
      const d = this.get('flutterDepth');
      this.lfoPitch.gain.setTargetAtTime(d * 22, t, 0.02);
      this.lfoFilter.gain.setTargetAtTime(d * 1800, t, 0.02);
    }

    onParam(key, v) {
      const t = this.now();
      if (key === 'level') return this.bus.gain.setTargetAtTime(v, t, 0.02);
      if (key === 'flutterRate') return this.lfo.frequency.setTargetAtTime(v, t, 0.02);
      if (key === 'flutterDepth') return this.applyLfo();
      this.voices.forEach((voice) => voice.update(key));
    }

    noteOn(key, note, vel, time) {
      const t = Math.max(time || 0, this.now());
      if (this.voices.has(key)) this.noteOff(key, t);
      if (this.voices.size >= 12) {
        const oldest = this.voices.keys().next().value;
        this.voices.get(oldest).release(t, 0.02);
        this.voices.delete(oldest);
      }
      const v = new GustVoice(this, note, vel, t);
      this.voices.set(key, v);
      this.gusts.push({ y: 1 - U.clamp((note - 24) / 84, 0, 1), power: vel, t0: performance.now() + Math.max(0, (t - this.now()) * 1000) });
    }

    noteOff(key, time) {
      const v = this.voices.get(key);
      if (!v) return;
      this.voices.delete(key);
      v.release(Math.max(time || 0, this.now()));
    }

    expr(key, kind, val) {
      const v = this.voices.get(key);
      if (v) v.expr(kind, val);
    }

    allOff() {
      this.voices.forEach((v) => v.release(this.now(), 0.03));
      this.voices.clear();
    }

    buildUI(root) {
      const UI = FB.ui;
      const top = U.el('div', { class: 'viz-wrap' });
      root.appendChild(top);
      this.cv = UI.canvas(top, 'viz');
      top.appendChild(U.el('div', { class: 'viz-caption', text: 'The sky moves with your sound — play a note to send a gust across it.' }));

      const grid = U.el('div', { class: 'sections' });
      root.appendChild(grid);
      const air = UI.section(grid, 'Air currents');
      UI.choice(air, this, 'shapeA', { names: SHAPE_NAMES });
      UI.choice(air, this, 'shapeB', { names: SHAPE_NAMES });
      UI.knobs(air, this, ['blend', 'drift', 'undertow', 'glide']);
      UI.knobs(UI.section(grid, 'Breath'), this, ['breath', 'whistle']);
      UI.knobs(UI.section(grid, 'Gale filter'), this, ['gale', 'howl', 'gust']);
      UI.knobs(UI.section(grid, 'Weather (envelope)'), this, ['rise', 'linger', 'hover', 'fade']);
      UI.knobs(UI.section(grid, 'Flutter'), this, ['flutterRate', 'flutterDepth', 'level']);
    }

    draw(time) {
      const c = this.cv;
      if (!c) return;
      const g = c.g, W = c.w, H = c.h;
      if (this.particles.length === 0 || c._w !== W || c._h !== H) {
        c._w = W;
        c._h = H;
        this.particles = [];
        for (let i = 0; i < 280; i++) this.particles.push({ x: Math.random() * W, y: Math.random() * H, s: 0.5 + Math.random() });
        const sky = g.createLinearGradient(0, 0, 0, H);
        sky.addColorStop(0, '#0b1830');
        sky.addColorStop(1, '#1b2f4d');
        g.fillStyle = sky;
        g.fillRect(0, 0, W, H);
      }
      // Energy follows number of sounding voices.
      let target = 0;
      this.voices.forEach((v) => (target += v.vel));
      this.energy += (Math.min(2, target) - this.energy) * 0.05;

      g.fillStyle = 'rgba(11,24,48,0.16)';
      g.fillRect(0, 0, W, H);

      const tt = time / 1000;
      const galeN = this.norm('gale');
      const turb = 0.4 + this.norm('howl') * 1.2 + this.get('flutterDepth') * 1.5;
      const base = 0.5 + galeN * 2.6 + this.energy * 2.2;
      const now = performance.now();
      this.gusts = this.gusts.filter((gu) => now - gu.t0 < 2500);

      g.lineWidth = 1.2;
      g.lineCap = 'round';
      for (const p of this.particles) {
        const a = Math.sin(p.x * 0.006 + tt * 0.4) * 0.5 * turb + Math.cos(p.y * 0.012 - tt * 0.3 + p.x * 0.002) * 0.6 * turb;
        let sp = base * p.s;
        let glow = 0;
        for (const gu of this.gusts) {
          const age = (now - gu.t0) / 2500;
          if (age < 0) continue;
          const dy = (p.y / H - gu.y) * 6;
          const k = Math.exp(-dy * dy) * gu.power * (1 - age);
          sp += k * 9;
          glow += k;
        }
        const nx = p.x + Math.cos(a) * sp;
        const ny = p.y + Math.sin(a) * sp * 0.6;
        const alpha = Math.min(1, 0.2 + glow * 0.8 + this.energy * 0.15);
        g.strokeStyle = 'hsla(' + (200 + glow * 20) + ',70%,' + (78 + glow * 15) + '%,' + alpha + ')';
        g.beginPath();
        g.moveTo(p.x, p.y);
        g.lineTo(nx, ny);
        g.stroke();
        p.x = nx;
        p.y = ny;
        if (p.x > W + 5 || p.y < -5 || p.y > H + 5 || p.x < -5) {
          p.x = -2;
          p.y = Math.random() * H;
        }
      }
    }
  }

  class GustVoice {
    constructor(eng, note, vel, t) {
      const ctx = eng.ctx;
      const P = (k) => eng.get(k);
      this.eng = eng;
      this.note = note;
      this.vel = vel;
      this.bend = 0;
      this.pressure = 0;
      this.slide = 0.5;
      this.freq = U.mtof(note);

      this.oscA = ctx.createOscillator();
      this.oscB = ctx.createOscillator();
      this.sub = ctx.createOscillator();
      this.oscA.type = P('shapeA');
      this.oscB.type = P('shapeB');
      this.sub.type = 'sine';
      const glide = P('glide');
      [this.oscA, this.oscB].forEach((o) => {
        if (glide > 0 && eng.lastFreq) {
          o.frequency.setValueAtTime(eng.lastFreq, t);
          o.frequency.exponentialRampToValueAtTime(this.freq, t + glide * 0.8);
        } else o.frequency.setValueAtTime(this.freq, t);
      });
      if (glide > 0 && eng.lastFreq) {
        this.sub.frequency.setValueAtTime(eng.lastFreq / 2, t);
        this.sub.frequency.exponentialRampToValueAtTime(this.freq / 2, t + glide * 0.8);
      } else this.sub.frequency.setValueAtTime(this.freq / 2, t);
      eng.lastFreq = this.freq;

      this.noise = ctx.createBufferSource();
      this.noise.buffer = FB.audio.noise;
      this.noise.loop = true;
      this.bp = ctx.createBiquadFilter();
      this.bp.type = 'bandpass';
      this.bp.frequency.value = Math.min(16000, this.freq * 2);

      this.gA = ctx.createGain();
      this.gB = ctx.createGain();
      this.gSub = ctx.createGain();
      this.gN = ctx.createGain();
      this.filter = ctx.createBiquadFilter();
      this.filter.type = 'lowpass';
      this.amp = ctx.createGain();
      this.exprGain = ctx.createGain();
      this.amp.gain.value = 0;

      this.oscA.connect(this.gA).connect(this.filter);
      this.oscB.connect(this.gB).connect(this.filter);
      this.sub.connect(this.gSub).connect(this.filter);
      this.noise.connect(this.bp).connect(this.gN).connect(this.filter);
      this.filter.connect(this.amp).connect(this.exprGain).connect(eng.bus);

      eng.lfoPitch.connect(this.oscA.detune);
      eng.lfoPitch.connect(this.oscB.detune);
      eng.lfoFilter.connect(this.filter.detune);

      ['blend', 'drift', 'undertow', 'breath', 'whistle', 'howl'].forEach((k) => this.update(k, true));
      this.setCutoff(t, true);

      // Envelopes
      const atk = P('rise'), dec = P('linger'), sus = P('hover');
      const peak = 0.26 * (0.25 + 0.75 * vel);
      this.amp.gain.setValueAtTime(0, t);
      this.amp.gain.linearRampToValueAtTime(peak, t + atk);
      this.amp.gain.setTargetAtTime(peak * sus, t + atk, dec / 3);
      const envC = P('gust') * 6000 * (0.5 + 0.5 * vel);
      this.filter.detune.setValueAtTime(0, t);
      this.filter.detune.linearRampToValueAtTime(envC, t + atk);
      this.filter.detune.setTargetAtTime(envC * sus * 0.6, t + atk, dec / 3);

      const off = Math.random() * 1.5;
      this.oscA.start(t);
      this.oscB.start(t);
      this.sub.start(t);
      this.noise.start(t, off);
      this.oscA.onended = () => this.cleanup();
    }

    setCutoff(t, instant) {
      const e = this.eng;
      const kt = Math.pow(2, ((this.note - 60) / 12) * 0.5);
      const exprOct = this.pressure * 1.6 + (this.slide - 0.5) * 3;
      const f = U.clamp(e.get('gale') * kt * Math.pow(2, exprOct), 30, 20000);
      if (instant) this.filter.frequency.setValueAtTime(f, t);
      else this.filter.frequency.setTargetAtTime(f, t, 0.02);
    }

    update(key, instant) {
      const e = this.eng;
      const t = e.now();
      const set = (param, v) => (instant ? param.setValueAtTime(v, t) : param.setTargetAtTime(v, t, 0.02));
      switch (key) {
        case 'shapeA':
          this.oscA.type = e.get('shapeA');
          break;
        case 'shapeB':
          this.oscB.type = e.get('shapeB');
          break;
        case 'blend': {
          const b = e.get('blend');
          set(this.gA.gain, (1 - b) * 0.5);
          set(this.gB.gain, b * 0.5);
          break;
        }
        case 'drift':
          this.applyPitch(instant);
          break;
        case 'undertow':
          set(this.gSub.gain, e.get('undertow') * 0.55);
          break;
        case 'breath':
        case 'whistle': {
          const w = e.get('whistle');
          set(this.bp.Q, 0.6 + w * w * 60);
          set(this.gN.gain, e.get('breath') * (0.9 + w * 4));
          break;
        }
        case 'howl':
          set(this.filter.Q, e.get('howl'));
          break;
        case 'gale':
          this.setCutoff(t);
          break;
      }
    }

    applyPitch(instant) {
      const t = this.eng.now();
      const d = this.eng.get('drift');
      const b = this.bend * 100;
      const fn = (p, v) => (instant ? p.setValueAtTime(v, t) : p.setTargetAtTime(v, t, 0.008));
      fn(this.oscA.detune, d + b);
      fn(this.oscB.detune, -d + b);
      fn(this.sub.detune, b);
      fn(this.bp.detune, b);
    }

    expr(kind, v) {
      const t = this.eng.now();
      if (kind === 'bend') {
        this.bend = v;
        this.applyPitch();
      } else if (kind === 'pressure') {
        this.pressure = v;
        this.exprGain.gain.setTargetAtTime(0.7 + v * 0.6, t, 0.02);
        this.setCutoff(t);
      } else if (kind === 'slide') {
        this.slide = v;
        this.setCutoff(t);
      }
    }

    release(t, fast) {
      const rel = fast || this.eng.get('fade');
      U.releaseParam(this.amp.gain, t, rel / 4);
      U.releaseParam(this.filter.detune, t, rel / 3);
      const end = t + rel * 1.4 + 0.05;
      [this.oscA, this.oscB, this.sub, this.noise].forEach((o) => {
        try {
          o.stop(end);
        } catch (_) {}
      });
    }

    cleanup() {
      const e = this.eng;
      try {
        e.lfoPitch.disconnect(this.oscA.detune);
        e.lfoPitch.disconnect(this.oscB.detune);
        e.lfoFilter.disconnect(this.filter.detune);
      } catch (_) {}
      this.exprGain.disconnect();
    }
  }

  FB.Gusts = Gusts;
})(window.FB);
