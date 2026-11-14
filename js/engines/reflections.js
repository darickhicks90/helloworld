/* Reflections — a sampler with a water-inspired interface.
   Load a sound (drag & drop, file picker, microphone, or resample FoxBox
   itself), then play it chromatically ("Keys") or chopped into 16 slices
   for pads ("Slices"). The waveform floats on a lake and every note sends
   ripples across the surface. */
(function (FB) {
  'use strict';
  const U = FB.util;

  class Reflections extends FB.Engine {
    constructor() {
      super('reflections', 'Reflections');
      this.color = '#4fd1c5';
      this.modParam = 'surface';
      const P = this.param.bind(this);
      P('mode', { label: 'Mode', type: 'choice', options: ['Keys', 'Slices'], def: 'Keys' });
      P('root', { label: 'Root', min: 24, max: 96, def: 60, step: 1, unit: 'note', help: 'The note at which the sample plays at its original pitch' });
      P('tune', { label: 'Tune', min: -24, max: 24, def: 0, step: 1, unit: 'st' });
      P('start', { label: 'Shore', min: 0, max: 1, def: 0, unit: '%', help: 'Sample start' });
      P('end', { label: 'Horizon', min: 0, max: 1, def: 1, unit: '%', help: 'Sample end' });
      P('loop', { label: 'Loop', type: 'bool', def: false, help: 'Loop between Shore and Horizon while held' });
      P('reverse', { label: 'Reverse', type: 'bool', def: false, help: 'Play the sample backwards' });
      P('surface', { label: 'Surface', min: 120, max: 20000, def: 14000, curve: 'exp', unit: 'Hz', help: 'Low-pass filter — lower sounds deeper under water' });
      P('ripple', { label: 'Ripple', min: 0, max: 1, def: 0.12, unit: '%', help: 'Wobbly vibrato per note' });
      P('tide', { label: 'Tide in', min: 0.001, max: 3, def: 0.004, curve: 'exp', unit: 's', help: 'Attack' });
      P('ebb', { label: 'Ebb', min: 0.01, max: 8, def: 0.35, curve: 'exp', unit: 's', help: 'Release' });
      P('depth', { label: 'Depth', min: 0, max: 1, def: 0.25, unit: '%', help: 'Reverb — how deep the lake is' });
      P('level', { label: 'Level', min: 0, max: 1, def: 0.8, unit: '%' });

      const ctx = this.ctx;
      this.filter = ctx.createBiquadFilter();
      this.filter.type = 'lowpass';
      this.filter.Q.value = 0.8;
      this.bus = ctx.createGain();
      this.send = ctx.createGain();
      this.bus.connect(this.filter);
      this.filter.connect(this.output);
      this.filter.connect(this.send);
      this.send.connect(FB.audio.reverbIn);
      ['surface', 'depth', 'level'].forEach((k) => this.onParam(k, this.get(k)));

      this.voices = new Map();
      this.ripples = [];
      this.peaks = null;
      this.rec = null;
      this.setBuffer(this.makeDroplet(), 'Droplet (built-in)');
    }

    makeDroplet() {
      const f = U.mtof(60);
      let ph1 = 0, ph2 = 0, ph3 = 0;
      return FB.audio.render(2.6, (t, i, sr) => {
        const drop = 1 + 0.04 * Math.exp(-t * 35);
        ph1 += (2 * Math.PI * f * drop) / sr;
        ph2 += (2 * Math.PI * f * 3.01 * drop) / sr;
        ph3 += (2 * Math.PI * f * 5.43) / sr;
        const click = t < 0.004 ? (Math.random() * 2 - 1) * (1 - t / 0.004) * 0.3 : 0;
        return Math.sin(ph1) * Math.exp(-t * 2.1) + 0.32 * Math.sin(ph2) * Math.exp(-t * 6) + 0.14 * Math.sin(ph3) * Math.exp(-t * 11) + click;
      });
    }

    makeGlass() {
      const f = U.mtof(60);
      let ph = 0, mph = 0;
      return FB.audio.render(3.5, (t, i, sr) => {
        mph += (2 * Math.PI * f * 3.5) / sr;
        const idx = 2.2 * Math.exp(-t * 3);
        ph += (2 * Math.PI * f) / sr;
        const s = Math.sin(ph + idx * Math.sin(mph));
        return s * Math.exp(-t * 1.1) * Math.min(1, t * 400);
      });
    }

    setBuffer(buf, name) {
      if (!buf) return;
      this.allOff();
      this.buffer = buf;
      this.rev = U.reverseBuffer(this.ctx, buf);
      this.sampleName = name || 'Sample';
      this.peaks = U.peaks(buf, 1500);
      if (this.nameEl) this.nameEl.textContent = this.sampleName + ' · ' + buf.duration.toFixed(2) + 's';
      if (FB.engines && FB.engines.leaves && FB.engines.leaves.onReflectionsBuffer) FB.engines.leaves.onReflectionsBuffer();
    }

    onParam(key, v) {
      const t = this.now();
      if (key === 'surface') this.filter.frequency.setTargetAtTime(v, t, 0.02);
      else if (key === 'depth') this.send.gain.setTargetAtTime(v * 0.9, t, 0.02);
      else if (key === 'level') this.bus.gain.setTargetAtTime(v, t, 0.02);
      else if (key === 'mode') FB.input && FB.input.refreshPads();
    }

    region(note) {
      const dur = this.buffer.duration;
      let s = Math.min(this.get('start'), this.get('end')) * dur;
      let e = Math.max(this.get('start'), this.get('end')) * dur;
      if (e - s < 0.005) e = Math.min(dur, s + 0.005);
      let rate;
      let slice = -1;
      if (this.get('mode') === 'Slices') {
        slice = (((note - 36) % 16) + 16) % 16;
        const len = (e - s) / 16;
        s = s + slice * len;
        e = s + len;
        rate = Math.pow(2, this.get('tune') / 12);
      } else {
        rate = Math.pow(2, (note - this.get('root') + this.get('tune')) / 12);
      }
      return { s, e, rate, slice, dur };
    }

    noteOn(key, note, vel, time) {
      if (!this.buffer) return;
      const ctx = this.ctx;
      const t = Math.max(time || 0, this.now());
      if (this.voices.has(key)) this.noteOff(key, t);
      if (this.voices.size >= 16) {
        const k = this.voices.keys().next().value;
        this.noteOff(k, t);
      }
      const r = this.region(note);
      const rev = this.get('reverse');
      const loop = this.get('loop');
      let s = r.s, e = r.e;
      if (rev) {
        s = r.dur - r.e;
        e = r.dur - r.s;
      }
      const src = ctx.createBufferSource();
      src.buffer = rev ? this.rev : this.buffer;
      src.playbackRate.value = r.rate;
      const ripple = this.get('ripple');
      src.detune.value = (Math.random() - 0.5) * ripple * 14;
      const lfo = ctx.createOscillator();
      lfo.frequency.value = 3.5 + Math.random() * 3;
      const lfoG = ctx.createGain();
      lfoG.gain.value = ripple * 38;
      lfo.connect(lfoG).connect(src.detune);
      const vf = ctx.createBiquadFilter();
      vf.type = 'lowpass';
      vf.frequency.value = 20000;
      const g = ctx.createGain();
      const ex = ctx.createGain();
      const peak = 0.2 + 0.8 * vel;
      g.gain.setValueAtTime(0, t);
      g.gain.linearRampToValueAtTime(peak, t + this.get('tide'));
      src.connect(vf).connect(g).connect(ex).connect(this.bus);
      if (loop) {
        src.loop = true;
        src.loopStart = s;
        src.loopEnd = e;
        src.start(t, s);
      } else {
        src.start(t, s, e - s);
      }
      lfo.start(t);
      const v = { src, lfo, g, ex, vf, t, s, e, rate: r.rate, loop, rev, dur: r.dur, note, slice: r.slice, ended: false };
      src.onended = () => {
        v.ended = true;
        try {
          lfo.stop();
        } catch (_) {}
        ex.disconnect();
        if (this.voices.get(key) === v) this.voices.delete(key);
      };
      this.voices.set(key, v);
      const mid = rev ? r.dur - s : s;
      this.ripples.push({ x: mid / r.dur, t0: performance.now() + Math.max(0, (t - this.now()) * 1000), p: vel });
    }

    noteOff(key, time) {
      const v = this.voices.get(key);
      if (!v) return;
      this.voices.delete(key);
      const t = Math.max(time || 0, this.now());
      const rel = this.get('ebb');
      U.releaseParam(v.g.gain, t, rel / 4);
      try {
        v.src.stop(t + rel * 1.4 + 0.05);
      } catch (_) {}
    }

    expr(key, kind, val) {
      const v = this.voices.get(key);
      if (!v) return;
      const t = this.now();
      if (kind === 'bend') v.src.detune.setTargetAtTime(val * 100, t, 0.01);
      else if (kind === 'pressure') v.ex.gain.setTargetAtTime(0.6 + val * 0.8, t, 0.02);
      else if (kind === 'slide') v.vf.frequency.setTargetAtTime(300 * Math.pow(2, val * 6), t, 0.02);
    }

    allOff() {
      if (!this.voices) return;
      Array.from(this.voices.keys()).forEach((k) => this.noteOff(k));
    }

    padDown(i, vel) {
      if (this.get('mode') === 'Slices') FB.input.noteOn('pad' + i, 36 + i, vel, { eng: this });
      else super.padDown(i, vel);
    }
    padLabel(i) {
      return this.get('mode') === 'Slices' ? 'Slice ' + (i + 1) : super.padLabel(i);
    }

    async toggleRecord(kind, btn) {
      try {
        if (this.rec) {
          const r = this.rec;
          this.rec = null;
          btn.classList.remove('on');
          btn.textContent = r.kind === 'mic' ? '🎙 Record mic' : '⟲ Resample FoxBox';
          const res = await r.r.stop();
          this.setBuffer(res.buffer, r.kind === 'mic' ? 'Mic recording' : 'Resampled FoxBox');
          this.set('start', 0);
          this.set('end', 1);
          return;
        }
        const r = kind === 'mic' ? await FB.audio.captureMic() : FB.audio.captureMaster();
        r.start();
        this.rec = { r, kind };
        btn.classList.add('on');
        btn.textContent = '■ Stop & use';
        FB.app.toast(kind === 'mic' ? 'Recording from your microphone…' : 'Recording everything FoxBox plays…');
      } catch (e) {
        FB.app.toast('Could not record: ' + e.message);
      }
    }

    async loadFile(file) {
      try {
        const buf = await FB.audio.decodeFile(file);
        this.setBuffer(buf, file.name);
        this.set('start', 0);
        this.set('end', 1);
        FB.app.toast('Loaded ' + file.name);
      } catch (e) {
        FB.app.toast('Could not read that audio file');
      }
    }

    buildUI(root) {
      const UI = FB.ui;
      const top = U.el('div', { class: 'viz-wrap' });
      root.appendChild(top);
      this.cv = UI.canvas(top, 'viz');
      const bar = U.el('div', { class: 'toolbar' });
      const file = U.el('input', { type: 'file', accept: 'audio/*', style: 'display:none' });
      file.addEventListener('change', () => file.files[0] && this.loadFile(file.files[0]));
      bar.appendChild(file);
      bar.appendChild(U.el('button', { text: '📂 Load sound', onclick: () => file.click() }));
      const mic = U.el('button', { text: '🎙 Record mic' });
      mic.onclick = () => this.toggleRecord('mic', mic);
      const res = U.el('button', { text: '⟲ Resample FoxBox', title: 'Record what FoxBox is playing and turn it into a sample' });
      res.onclick = () => this.toggleRecord('master', res);
      bar.appendChild(mic);
      bar.appendChild(res);
      bar.appendChild(U.el('button', { text: '💧 Droplet', onclick: () => this.setBuffer(this.makeDroplet(), 'Droplet (built-in)') }));
      bar.appendChild(U.el('button', { text: '🔔 Glass', onclick: () => this.setBuffer(this.makeGlass(), 'Glass (built-in)') }));
      this.nameEl = U.el('span', { class: 'sample-name', text: this.sampleName + ' · ' + this.buffer.duration.toFixed(2) + 's' });
      bar.appendChild(this.nameEl);
      top.appendChild(bar);
      top.appendChild(U.el('div', { class: 'viz-caption', text: 'Drag the bright markers to set where the sound starts and ends. Drop an audio file anywhere here.' }));

      // Drag & drop
      root.addEventListener('dragover', (e) => {
        e.preventDefault();
        root.classList.add('drop');
      });
      root.addEventListener('dragleave', () => root.classList.remove('drop'));
      root.addEventListener('drop', (e) => {
        e.preventDefault();
        root.classList.remove('drop');
        const f = e.dataTransfer.files[0];
        if (f) this.loadFile(f);
      });

      // Drag start/end markers on the canvas.
      const c = this.cv.canvas;
      let drag = null;
      const pos = (e) => U.clamp((e.clientX - c.getBoundingClientRect().left) / this.cv.w, 0, 1);
      c.addEventListener('pointerdown', (e) => {
        const x = pos(e);
        drag = Math.abs(x - this.get('start')) <= Math.abs(x - this.get('end')) ? 'start' : 'end';
        c.setPointerCapture(e.pointerId);
        this.set(drag, x);
      });
      c.addEventListener('pointermove', (e) => drag && this.set(drag, pos(e)));
      c.addEventListener('pointerup', () => (drag = null));

      const grid = U.el('div', { class: 'sections' });
      root.appendChild(grid);
      const play = UI.section(grid, 'How it plays');
      UI.choice(play, this, 'mode');
      UI.toggle(play, this, 'loop');
      UI.toggle(play, this, 'reverse');
      UI.knobs(play, this, ['root', 'tune']);
      UI.knobs(UI.section(grid, 'Shoreline'), this, ['start', 'end']);
      UI.knobs(UI.section(grid, 'Water'), this, ['surface', 'ripple', 'depth']);
      UI.knobs(UI.section(grid, 'Tide (envelope)'), this, ['tide', 'ebb', 'level']);
    }

    draw(time) {
      const cv = this.cv;
      if (!cv || !this.peaks) return;
      const g = cv.g, W = cv.w, H = cv.h;
      const hz = H * 0.52;
      const tt = time / 1000;
      const sky = g.createLinearGradient(0, 0, 0, hz);
      sky.addColorStop(0, '#04131c');
      sky.addColorStop(1, '#0c2c3a');
      g.fillStyle = sky;
      g.fillRect(0, 0, W, hz);
      const water = g.createLinearGradient(0, hz, 0, H);
      water.addColorStop(0, '#0d3b4d');
      water.addColorStop(1, '#03161f');
      g.fillStyle = water;
      g.fillRect(0, hz, W, H - hz);

      const s = Math.min(this.get('start'), this.get('end'));
      const e = Math.max(this.get('start'), this.get('end'));
      const n = this.peaks.length;
      const cols = Math.min(W, 700);
      const bw = W / cols;
      for (let i = 0; i < cols; i++) {
        const x = i / cols;
        const pk = this.peaks[Math.floor(x * n)] || 0;
        const inside = x >= s && x <= e;
        const h = Math.max(1, pk * hz * 0.85);
        g.fillStyle = inside ? '#8ff3e6' : 'rgba(143,243,230,0.22)';
        g.fillRect(i * bw, hz - h, Math.max(1, bw - 0.3), h);
        // Reflection: shimmering, compressed, fading.
        const wob = Math.sin(i * 0.09 + tt * 2.2) * 2.5 + Math.sin(i * 0.031 - tt * 1.3) * 1.5;
        g.fillStyle = inside ? 'rgba(79,209,197,0.35)' : 'rgba(79,209,197,0.08)';
        g.fillRect(i * bw + wob, hz + 1, Math.max(1, bw - 0.3), h * 0.75);
      }
      // Slice lines
      if (this.get('mode') === 'Slices') {
        g.strokeStyle = 'rgba(255,255,255,0.18)';
        g.lineWidth = 1;
        for (let k = 1; k < 16; k++) {
          const x = (s + ((e - s) * k) / 16) * W;
          g.beginPath();
          g.moveTo(x, hz * 0.15);
          g.lineTo(x, hz);
          g.stroke();
        }
      }
      // Horizon line
      g.fillStyle = 'rgba(200,255,250,0.25)';
      g.fillRect(0, hz, W, 1);
      // Markers
      [['start', s], ['end', e]].forEach(([, v]) => {
        const x = v * W;
        g.strokeStyle = '#ffd27a';
        g.lineWidth = 2;
        g.beginPath();
        g.moveTo(x, 6);
        g.lineTo(x, H - 6);
        g.stroke();
        g.fillStyle = '#ffd27a';
        g.beginPath();
        g.arc(x, hz, 6, 0, Math.PI * 2);
        g.fill();
      });
      // Playheads
      const now = this.now();
      this.voices.forEach((v) => {
        const el = now - v.t;
        if (el < 0 || v.ended) return;
        let p = v.s + el * v.rate;
        if (v.loop) {
          const len = v.e - v.s;
          p = v.s + ((p - v.s) % len);
        } else if (p > v.e) return;
        const x = (v.rev ? v.dur - p : p) / v.dur;
        g.strokeStyle = '#ffffff';
        g.lineWidth = 1.5;
        g.beginPath();
        g.moveTo(x * W, hz * 0.2);
        g.lineTo(x * W, hz);
        g.stroke();
        if (Math.random() < 0.06) this.ripples.push({ x, t0: performance.now(), p: 0.35 });
      });
      // Ripples on the water
      const pn = performance.now();
      this.ripples = this.ripples.filter((r) => pn - r.t0 < 2200);
      this.ripples.forEach((r) => {
        const age = (pn - r.t0) / 2200;
        if (age < 0) return;
        for (let k = 0; k < 3; k++) {
          const a = age - k * 0.12;
          if (a <= 0) continue;
          g.strokeStyle = 'rgba(180,255,245,' + (1 - a) * 0.6 * r.p + ')';
          g.lineWidth = 1.2;
          g.beginPath();
          g.ellipse(r.x * W, hz + 8 + a * 20, a * 140, a * 18, 0, 0, Math.PI * 2);
          g.stroke();
        }
      });
    }
  }

  FB.Reflections = Reflections;
})(window.FB);
