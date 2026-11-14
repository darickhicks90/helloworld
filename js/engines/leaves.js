/* Leaves — a granular synthesis engine with a plant-inspired interface.
   A sound is laid along a branch; each grain is a leaf that sprouts from
   the branch and drifts away. Hold notes to grow clouds of grains at that
   pitch, or switch on "Evergreen" for a self-playing drone. */
(function (FB) {
  'use strict';
  const U = FB.util;
  const HANN = U.hann(256);

  class Leaves extends FB.Engine {
    constructor() {
      super('leaves', 'Leaves');
      this.color = '#8fd16a';
      this.modParam = 'branch';
      const P = this.param.bind(this);
      P('source', { label: 'Seed', type: 'choice', options: ['Moss', 'Reflections'], def: 'Moss', help: 'Which sound the grains are cut from' });
      P('branch', { label: 'Branch', min: 0, max: 1, def: 0.3, unit: '%', help: 'Where along the sound grains are taken from' });
      P('sway', { label: 'Sway', min: 0, max: 1, def: 0.12, unit: '%', help: 'Random scatter of the grain position' });
      P('growth', { label: 'Growth', min: -1, max: 1, def: 0, fmt: (v) => (v === 0 ? 'still' : (v > 0 ? '→ ' : '← ') + Math.round(Math.abs(v) * 100) + '%'), help: 'Slowly moves the Branch position forward or backward' });
      P('canopy', { label: 'Canopy', min: 1, max: 90, def: 22, curve: 'exp', fmt: (v) => Math.round(v) + '/s', help: 'Grain density (grains per second)' });
      P('leaf', { label: 'Leaf size', min: 0.01, max: 0.6, def: 0.11, curve: 'exp', unit: 's', help: 'Length of each grain' });
      P('pollen', { label: 'Pollen', min: 0, max: 12, def: 0, unit: 'st', help: 'Random pitch scatter per grain' });
      P('roots', { label: 'Roots', min: 0, max: 1, def: 0, unit: '%', help: 'Chance a grain jumps an octave down or up' });
      P('spread', { label: 'Spread', min: 0, max: 1, def: 0.6, unit: '%', help: 'Stereo width' });
      P('sun', { label: 'Sunlight', min: 200, max: 18000, def: 9000, curve: 'exp', unit: 'Hz', help: 'Tone (low-pass filter)' });
      P('dew', { label: 'Dew', min: 0, max: 1, def: 0.4, unit: '%', help: 'Reverb' });
      P('sprout', { label: 'Sprout', min: 0.005, max: 5, def: 0.25, curve: 'exp', unit: 's', help: 'Attack' });
      P('fall', { label: 'Fall', min: 0.05, max: 10, def: 1.4, curve: 'exp', unit: 's', help: 'Release' });
      P('evergreen', { label: 'Evergreen', type: 'bool', def: false, help: 'Self-playing drone — makes sound without any controller' });
      P('level', { label: 'Level', min: 0, max: 1, def: 0.75, unit: '%' });

      const ctx = this.ctx;
      this.bus = ctx.createGain();
      this.filter = ctx.createBiquadFilter();
      this.filter.type = 'lowpass';
      this.send = ctx.createGain();
      this.bus.connect(this.filter);
      this.filter.connect(this.output);
      this.filter.connect(this.send);
      this.send.connect(FB.audio.reverbIn);
      ['sun', 'dew', 'level'].forEach((k) => this.onParam(k, this.get(k)));

      this.moss = this.makeMoss();
      this.voices = new Map();
      this.scan = 0;
      this.leaves = [];
      this.peaks = U.peaks(this.moss, 1200);
      this.lastTick = this.now();
      this.timer = setInterval(() => this.tick(), 20);
    }

    makeMoss() {
      // A soft evolving chord: something nice to grow grains from.
      const notes = [48, 55, 60, 63, 67, 70, 74].map(U.mtof);
      const ph = notes.map(() => Math.random() * 6.28);
      let n = 0;
      return FB.audio.render(6, (t, i, sr) => {
        let s = 0;
        for (let k = 0; k < notes.length; k++) {
          ph[k] += (2 * Math.PI * notes[k] * (1 + 0.002 * Math.sin(t * (0.7 + k * 0.3)))) / sr;
          const env = 0.5 + 0.5 * Math.sin(t * (0.5 + k * 0.23) + k);
          s += (Math.sin(ph[k]) + 0.3 * Math.sin(2 * ph[k]) + 0.12 * Math.sin(3 * ph[k])) * env;
        }
        n = n * 0.97 + (Math.random() * 2 - 1) * 0.03;
        return s + n * 3 * (0.5 + 0.5 * Math.sin(t * 2.1));
      });
    }

    buffer() {
      if (this.get('source') === 'Reflections' && FB.engines.reflections && FB.engines.reflections.buffer) return FB.engines.reflections.buffer;
      return this.moss;
    }

    onReflectionsBuffer() {
      if (this.get('source') === 'Reflections') this.peaks = U.peaks(this.buffer(), 1200);
    }

    onParam(key, v) {
      const t = this.now();
      if (key === 'sun') this.filter.frequency.setTargetAtTime(v, t, 0.03);
      else if (key === 'dew') this.send.gain.setTargetAtTime(v, t, 0.03);
      else if (key === 'level') this.bus.gain.setTargetAtTime(v, t, 0.03);
      else if (key === 'source') this.peaks = U.peaks(this.buffer(), 1200);
      else if (key === 'evergreen') {
        if (v) this.noteOn('evergreen', 48 + FB.input.scale.root, 0.6);
        else this.noteOff('evergreen');
      }
    }

    noteOn(key, note, vel, time) {
      const t = Math.max(time || 0, this.now());
      if (this.voices.has(key)) this.noteOff(key, t);
      if (this.voices.size >= 10) this.noteOff(this.voices.keys().next().value, t);
      const g = this.ctx.createGain();
      g.gain.setValueAtTime(0, t);
      g.gain.linearRampToValueAtTime(0.3 + 0.7 * vel, t + this.get('sprout'));
      g.connect(this.bus);
      this.voices.set(key, { note, vel, gain: g, start: t, end: null, next: t, bend: 0, pressure: -1, slide: 0.5 });
    }

    noteOff(key, time) {
      const v = this.voices.get(key);
      if (!v || v.end != null) return;
      const t = Math.max(time || 0, this.now());
      v.end = t;
      v.releaseEnd = t + this.get('fall') * 1.5 + 0.1;
      U.releaseParam(v.gain.gain, t, this.get('fall') / 4);
      // Move to a unique key so the same note can sound again while this one fades.
      this.voices.delete(key);
      this.voices.set(key + '~' + Math.random(), v);
    }

    expr(key, kind, val) {
      const v = this.voices.get(key);
      if (v) v[kind] = val;
    }

    allOff() {
      Array.from(this.voices.keys()).forEach((k) => this.noteOff(k));
      if (this.get('evergreen')) this.set('evergreen', false);
    }

    tick() {
      const ctx = this.ctx;
      if (ctx.state !== 'running') return;
      const now = ctx.currentTime;
      const dt = now - this.lastTick;
      this.lastTick = now;
      this.scan = (this.scan + this.get('growth') * dt * 0.05 + 10) % 1;
      const buf = this.buffer();
      if (!buf) return;
      const horizon = now + 0.1;
      for (const [k, v] of this.voices) {
        if (v.end != null && now > v.releaseEnd) {
          v.gain.disconnect();
          this.voices.delete(k);
          continue;
        }
        let dens = this.get('canopy');
        if (v.pressure >= 0) dens *= 0.4 + v.pressure * 1.6;
        if (v.next < now) v.next = now;
        let guard = 0;
        while (v.next < horizon && guard++ < 40) {
          if (v.end == null || v.next < v.releaseEnd) this.grain(v, v.next, buf);
          v.next += (1 / dens) * (0.6 + Math.random() * 0.8);
        }
      }
    }

    grain(v, t, buf) {
      const ctx = this.ctx;
      const size = this.get('leaf');
      let semis = v.note - 60 + v.bend;
      const pol = this.get('pollen');
      if (pol > 0) semis += Math.round((Math.random() * 2 - 1) * pol);
      if (Math.random() < this.get('roots')) semis += Math.random() < 0.5 ? -12 : 12;
      const rate = Math.pow(2, semis / 12);
      let pos = this.get('branch') + this.scan + (v.slide - 0.5) * 0.5 + (Math.random() * 2 - 1) * this.get('sway') * 0.5;
      pos = ((pos % 1) + 1) % 1;
      const dur = buf.duration;
      const offset = Math.min(pos * dur, Math.max(0, dur - size * rate - 0.01));
      const src = ctx.createBufferSource();
      src.buffer = buf;
      src.playbackRate.value = rate;
      const g = ctx.createGain();
      const overlap = Math.max(1, this.get('canopy') * size);
      const amp = 0.55 / Math.sqrt(overlap);
      g.gain.value = 0;
      try {
        g.gain.setValueCurveAtTime(HANN.map((x) => x * amp), t, size);
      } catch (_) {
        g.gain.setValueAtTime(amp, t);
      }
      const pan = ctx.createStereoPanner ? ctx.createStereoPanner() : null;
      const pv = (Math.random() * 2 - 1) * this.get('spread');
      src.connect(g);
      if (pan) {
        pan.pan.value = pv;
        g.connect(pan).connect(v.gain);
      } else g.connect(v.gain);
      src.start(t, offset);
      src.stop(t + size + 0.01);
      src.onended = () => {
        g.disconnect();
        pan && pan.disconnect();
      };
      if (this.leaves.length < 260) this.leaves.push({ t, x: pos, semis, pan: pv, size, born: 0 });
    }

    padLabel(i) {
      return super.padLabel(i);
    }

    buildUI(root) {
      const UI = FB.ui;
      const top = U.el('div', { class: 'viz-wrap' });
      root.appendChild(top);
      this.cv = UI.canvas(top, 'viz');
      const bar = U.el('div', { class: 'toolbar' });
      UI.choice(bar, this, 'source');
      UI.toggle(bar, this, 'evergreen', { text: '🌲 Evergreen drone' });
      top.appendChild(bar);
      top.appendChild(U.el('div', { class: 'viz-caption', text: 'Each leaf is a tiny grain of sound. Drag along the branch to choose where they grow.' }));

      const c = this.cv.canvas;
      let dragging = false;
      const setPos = (e) => this.set('branch', U.clamp((e.clientX - c.getBoundingClientRect().left) / this.cv.w, 0, 1));
      c.addEventListener('pointerdown', (e) => {
        dragging = true;
        c.setPointerCapture(e.pointerId);
        setPos(e);
      });
      c.addEventListener('pointermove', (e) => dragging && setPos(e));
      c.addEventListener('pointerup', () => (dragging = false));

      const grid = U.el('div', { class: 'sections' });
      root.appendChild(grid);
      UI.knobs(UI.section(grid, 'Branch'), this, ['branch', 'sway', 'growth']);
      UI.knobs(UI.section(grid, 'Foliage'), this, ['canopy', 'leaf', 'spread']);
      UI.knobs(UI.section(grid, 'Pollen'), this, ['pollen', 'roots']);
      UI.knobs(UI.section(grid, 'Climate'), this, ['sun', 'dew', 'sprout', 'fall', 'level']);
    }

    draw(time) {
      const cv = this.cv;
      if (!cv) return;
      const g = cv.g, W = cv.w, H = cv.h;
      const bg = g.createLinearGradient(0, 0, 0, H);
      bg.addColorStop(0, '#0e1f12');
      bg.addColorStop(1, '#1d2b12');
      g.fillStyle = bg;
      g.fillRect(0, 0, W, H);
      const by = H * 0.32;
      // Branch: thickness follows the sound's loudness.
      const n = this.peaks.length;
      g.fillStyle = '#5b3d23';
      g.beginPath();
      g.moveTo(0, by);
      for (let x = 0; x <= W; x += 3) {
        const pk = this.peaks[Math.floor((x / W) * (n - 1))] || 0;
        g.lineTo(x, by - 2 - pk * 14 + Math.sin(x * 0.02) * 2);
      }
      for (let x = W; x >= 0; x -= 3) {
        const pk = this.peaks[Math.floor((x / W) * (n - 1))] || 0;
        g.lineTo(x, by + 2 + pk * 14 + Math.sin(x * 0.02) * 2);
      }
      g.closePath();
      g.fill();
      // Position + sway region
      const pos = (this.get('branch') + this.scan) % 1;
      const sw = this.get('sway') * 0.5;
      g.fillStyle = 'rgba(180,255,140,0.10)';
      g.fillRect((pos - sw) * W, 0, sw * 2 * W, H);
      if (pos + sw > 1) g.fillRect(0, 0, (pos + sw - 1) * W, H);
      if (pos - sw < 0) g.fillRect((1 + pos - sw) * W, 0, sw * W, H);
      g.fillStyle = '#d6ff9e';
      g.beginPath();
      g.arc(pos * W, by, 7, 0, Math.PI * 2);
      g.fill();

      // Leaves (grains)
      const now = this.now();
      const life = 3.2;
      this.leaves = this.leaves.filter((l) => now - l.t < life);
      for (const l of this.leaves) {
        const age = now - l.t;
        if (age < 0) continue;
        const k = age / life;
        const x = l.x * W + l.pan * 60 * k + Math.sin(age * 3 + l.x * 30) * 14 * k;
        const y = by + age * age * 26 + age * 22;
        const hue = U.clamp(110 - l.semis * 4, 10, 140);
        const sz = 4 + Math.min(10, l.size * 40);
        g.save();
        g.translate(x, y);
        g.rotate(age * 2 + l.x * 10);
        g.globalAlpha = 1 - k;
        g.fillStyle = 'hsl(' + hue + ',70%,' + (45 + (1 - k) * 15) + '%)';
        g.beginPath();
        g.moveTo(-sz, 0);
        g.quadraticCurveTo(0, -sz * 0.7, sz, 0);
        g.quadraticCurveTo(0, sz * 0.7, -sz, 0);
        g.fill();
        g.restore();
      }
      g.globalAlpha = 1;
    }
  }

  FB.Leaves = Leaves;
})(window.FB);
