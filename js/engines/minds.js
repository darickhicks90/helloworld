/* Minds — a glitchy generative percussion engine.
   Eight synthesized "neurons" (kick, snare, hats, clicks, zaps, crunch,
   metal, ghost) fire patterns grown from a seed. Thoughts mutate over
   time; Chaos adds ratchets and wrong-footed timing; Glitch adds stutters
   and pitch jumps; Crush bit-reduces the whole brain. */
(function (FB) {
  'use strict';
  const U = FB.util;
  const VOICES = [
    { id: 'kick', name: 'Kick', dens: 0.28, color: '#ff6b9a' },
    { id: 'snare', name: 'Snare', dens: 0.16, color: '#ffb36b' },
    { id: 'hat', name: 'Hat', dens: 0.6, color: '#fff27a' },
    { id: 'click', name: 'Click', dens: 0.3, color: '#7affc1' },
    { id: 'zap', name: 'Zap', dens: 0.13, color: '#6be3ff' },
    { id: 'crunch', name: 'Crunch', dens: 0.15, color: '#8f8bff' },
    { id: 'metal', name: 'Metal', dens: 0.1, color: '#d38bff' },
    { id: 'ghost', name: 'Ghost', dens: 0.16, color: '#ff8bd8' },
  ];
  const POLY = [16, 16, 12, 10, 7, 13, 9, 5];

  class Minds extends FB.Engine {
    constructor() {
      super('minds', 'Minds');
      this.color = '#c58bff';
      this.modParam = 'chaos';
      const P = this.param.bind(this);
      P('density', { label: 'Activity', min: 0, max: 1, def: 0.5, unit: '%', help: 'How busy the generated patterns are' });
      P('chaos', { label: 'Chaos', min: 0, max: 1, def: 0.25, unit: '%', help: 'Ratchets, dropped hits, ghost notes and timing jitter' });
      P('glitch', { label: 'Glitch', min: 0, max: 1, def: 0.2, unit: '%', help: 'Stutters and pitch jumps' });
      P('mutate', { label: 'Mutate', min: 0, max: 1, def: 0.15, unit: '%', help: 'How much the pattern changes every bar' });
      P('poly', { label: 'Polymeter', type: 'bool', def: false, help: 'Give each neuron its own loop length for shifting patterns' });
      P('freeze', { label: 'Freeze', type: 'bool', def: false, help: 'Stop mutation and keep the current pattern' });
      P('pitch', { label: 'Pitch', min: -12, max: 12, def: 0, step: 1, unit: 'st' });
      P('decay', { label: 'Decay', min: 0.25, max: 2.5, def: 1, curve: 'exp', fmt: (v) => v.toFixed(2) + '×' });
      P('crush', { label: 'Crush', min: 0, max: 1, def: 0, unit: '%', help: 'Bit-crush and sample-rate reduction' });
      P('level', { label: 'Level', min: 0, max: 1, def: 0.8, unit: '%' });
      P('seed', { label: 'Seed', min: 1, max: 99999, def: 1234, step: 1, fmt: (v) => '#' + v });

      const ctx = this.ctx;
      this.bus = ctx.createGain();
      this.crusher = FB.audio.worklet('fb-crush');
      if (this.crusher) {
        this.bus.connect(this.crusher);
        this.crusher.connect(this.output);
      } else this.bus.connect(this.output);
      this.voiceGains = VOICES.map(() => {
        const g = ctx.createGain();
        g.connect(this.bus);
        return g;
      });
      this.mutes = VOICES.map(() => false);
      this.events = FB.ui.eventQueue();
      this.flash = VOICES.map(() => 0);
      this.synapses = [];
      this.lastFired = -1;
      this.fill = 0;
      this.stutter = null;
      this.rand = Math.random;
      this.generate(this.get('seed'));
      ['level', 'crush'].forEach((k) => this.onParam(k, this.get(k)));
      FB.clock.onStep((s, t, d) => this.step(s, t, d));
    }

    onParam(key, v) {
      const t = this.now();
      if (key === 'level') this.bus.gain.setTargetAtTime(v, t, 0.02);
      else if (key === 'crush' && this.crusher) {
        const P = this.crusher.parameters;
        P.get('mix').setTargetAtTime(v > 0.01 ? 1 : 0, t, 0.02);
        P.get('bits').setTargetAtTime(16 - v * 13, t, 0.02);
        P.get('down').setTargetAtTime(1 + v * v * 30, t, 0.02);
      } else if (key === 'seed' || key === 'poly' || key === 'density') {
        if (!this._restoring) this.generate(this.get('seed'));
      }
    }

    restore(o) {
      this._restoring = true;
      super.restore(o);
      this._restoring = false;
      if (o && o.pattern) this.pattern = o.pattern.map((p) => ({ len: p.len, steps: p.steps.slice() }));
      else this.generate(this.get('seed'));
      if (o && o.mutes) this.mutes = o.mutes.slice();
      this.renderGrid && this.renderGrid();
    }
    serialize() {
      const o = super.serialize();
      o.pattern = this.pattern.map((p) => ({ len: p.len, steps: Array.from(p.steps) }));
      o.mutes = this.mutes.slice();
      return o;
    }

    // Grow a new set of patterns from a seed.
    generate(seed) {
      const r = U.rng(seed);
      const dens = this.get('density');
      const poly = this.get('poly');
      this.pattern = VOICES.map((v, vi) => {
        const len = poly ? POLY[vi] : 16;
        const steps = [];
        for (let i = 0; i < 16; i++) {
          let p = v.dens * (0.3 + dens * 1.6);
          if (v.id === 'kick' && i % 4 === 0) p += 0.35;
          if (v.id === 'snare' && (i === 4 || i === 12)) p += 0.6;
          if (v.id === 'hat' && i % 2 === 0) p += 0.2;
          steps.push(i < len && r() < p ? Math.round((0.35 + r() * 0.65) * 100) / 100 : 0);
        }
        if (v.id === 'kick') steps[0] = 1;
        return { len, steps };
      });
      this.renderGrid && this.renderGrid();
    }

    rethink() {
      this.set('seed', Math.floor(Math.random() * 99998) + 1);
    }

    mutateNow(amount) {
      const r = Math.random;
      this.pattern.forEach((p, vi) => {
        for (let i = 0; i < p.len; i++) {
          if (r() < amount * 0.18) p.steps[i] = p.steps[i] > 0 ? 0 : Math.round((0.3 + r() * 0.6) * 100) / 100;
        }
        if (VOICES[vi].id === 'kick' && r() < 0.8) p.steps[0] = Math.max(p.steps[0], 0.8);
      });
      this.renderGrid && this.renderGrid();
    }

    step(s, t, dur) {
      if (s < 0) return;
      const chaos = this.get('chaos');
      const glitch = this.get('glitch');
      const r = Math.random;

      // Stutter: repeat a captured step for a few steps.
      if (!this.stutter && glitch > 0 && r() < glitch * 0.06) {
        this.stutter = { left: 1 + Math.floor(r() * 3), src: (s + 15) % 16, div: 2 + Math.floor(r() * 4), up: r() < 0.5 };
      }
      const st = this.stutter;
      const fill = this.fill > 0;

      VOICES.forEach((voice, vi) => {
        if (this.mutes[vi]) return;
        const p = this.pattern[vi];
        const idx = st ? st.src % p.len : s % p.len;
        let vel = p.steps[idx];
        if (vel > 0 && r() < chaos * 0.18) vel = 0; // dropped thought
        if (vel === 0 && r() < chaos * 0.05 + (fill ? 0.25 : 0)) vel = 0.25 + r() * 0.3; // ghost thought
        if (vel <= 0) return;
        let hits = 1;
        if (st) hits = st.div;
        else if (fill || r() < chaos * 0.12 + glitch * 0.05) hits = 2 + Math.floor(r() * 3);
        const jitter = (r() - 0.5) * chaos * dur * 0.3;
        for (let h = 0; h < hits; h++) {
          const ht = Math.max(this.now(), t + jitter + (h * dur) / hits);
          let pitch = this.get('pitch');
          if (st) pitch += (st.up ? 1 : -1) * h * 2;
          if (r() < glitch * 0.08) pitch += (r() < 0.5 ? -1 : 1) * (5 + Math.floor(r() * 8));
          this.trigger(vi, vel * (h === 0 ? 1 : 0.75), ht, pitch);
        }
      });

      if (st && --st.left <= 0) this.stutter = null;
      if (this.fill > 0) this.fill--;
      if (s % 16 === 15 && !this.get('freeze') && this.get('mutate') > 0) {
        this.mutateNow(this.get('mutate'));
      }
      this.events.push(t, { step: s });
    }

    // ---- Drum voices ----
    trigger(vi, vel, t, pitch) {
      const ctx = this.ctx;
      const out = this.voiceGains[vi];
      const dk = this.get('decay');
      const pr = Math.pow(2, (pitch || 0) / 12);
      const env = (node, peak, decay) => {
        const g = ctx.createGain();
        g.gain.setValueAtTime(0, t);
        g.gain.linearRampToValueAtTime(peak * vel, t + 0.002);
        g.gain.setTargetAtTime(0, t + 0.002, decay * dk);
        node.connect(g).connect(out);
        return g;
      };
      const noise = () => {
        const n = ctx.createBufferSource();
        n.buffer = FB.audio.noise;
        n.start(t, Math.random() * 1.5);
        n.stop(t + 1.2 * dk + 0.1);
        return n;
      };
      const osc = (type, f) => {
        const o = ctx.createOscillator();
        o.type = type;
        o.frequency.setValueAtTime(f, t);
        o.start(t);
        o.stop(t + 1.2 * dk + 0.1);
        return o;
      };
      const filt = (type, f, q) => {
        const b = ctx.createBiquadFilter();
        b.type = type;
        b.frequency.value = f;
        b.Q.value = q || 0.7;
        return b;
      };
      switch (VOICES[vi].id) {
        case 'kick': {
          const o = osc('sine', 160 * pr);
          o.frequency.exponentialRampToValueAtTime(42 * pr, t + 0.09);
          env(o, 0.95, 0.11);
          const c = osc('square', 900);
          env(c.connect(filt('lowpass', 1800)), 0.12, 0.004);
          break;
        }
        case 'snare': {
          env(noise().connect(filt('highpass', 1300)), 0.5, 0.05);
          const o = osc('triangle', 190 * pr);
          o.frequency.exponentialRampToValueAtTime(140 * pr, t + 0.05);
          env(o, 0.45, 0.035);
          break;
        }
        case 'hat': {
          const open = vel > 0.85 && Math.random() < 0.4;
          env(noise().connect(filt('highpass', 7000 * Math.min(1.6, pr))).connect(filt('peaking', 10500, 1.2)), 0.3, open ? 0.09 : 0.016);
          break;
        }
        case 'click': {
          const f = 1500 + Math.random() * 3500;
          env(noise().connect(filt('bandpass', f * pr, 18)), 1.8, 0.006);
          break;
        }
        case 'zap': {
          const o = osc('sine', 2400 * pr);
          o.frequency.exponentialRampToValueAtTime(70 * pr, t + 0.07 + Math.random() * 0.05);
          env(o, 0.35, 0.04);
          break;
        }
        case 'crunch': {
          // Stepped noise: a cheap lo-fi burst.
          const len = Math.floor(ctx.sampleRate * 0.25);
          const b = ctx.createBuffer(1, len, ctx.sampleRate);
          const d = b.getChannelData(0);
          const hold = 8 + Math.floor(Math.random() * 40);
          let v = 0;
          for (let i = 0; i < len; i++) {
            if (i % hold === 0) v = Math.round((Math.random() * 2 - 1) * 3) / 3;
            d[i] = v;
          }
          const s = ctx.createBufferSource();
          s.buffer = b;
          s.playbackRate.value = pr;
          s.start(t);
          env(s.connect(filt('lowpass', 5000)), 0.32, 0.04);
          break;
        }
        case 'metal': {
          const a = osc('square', 540 * pr);
          const b2 = osc('square', 805 * pr);
          const ring = ctx.createGain();
          ring.gain.value = 0;
          b2.connect(ring.gain);
          a.connect(ring);
          env(ring.connect(filt('bandpass', 3200, 2)), 0.5, 0.07);
          break;
        }
        case 'ghost': {
          const f = (180 + Math.random() * 260) * pr;
          const bp = filt('bandpass', f, 30);
          bp.frequency.setValueAtTime(f * 1.4, t);
          bp.frequency.exponentialRampToValueAtTime(f, t + 0.08);
          env(noise().connect(bp), 4, 0.09);
          break;
        }
      }
      this.events.push(t, { fire: vi, vel });
    }

    noteOn(key, note, vel, time) {
      const vi = (((note - 36) % 8) + 8) % 8;
      this.trigger(vi, vel, Math.max(time || 0, this.now()), this.get('pitch'));
    }
    noteOff() {}

    // Pads: 1-8 play neurons; 9-16 perform.
    padDown(i, vel) {
      if (i < 8) return this.trigger(i, vel, this.now(), this.get('pitch'));
      switch (i) {
        case 8:
          this.rethink();
          FB.app.toast('New thought (seed #' + this.get('seed') + ')');
          break;
        case 9:
          this.mutateNow(1);
          break;
        case 10:
          this.fill = 4;
          break;
        case 11:
          this.set('freeze', !this.get('freeze'));
          break;
        case 12:
          this.toggleMute([0]);
          break;
        case 13:
          this.toggleMute([1, 7]);
          break;
        case 14:
          this.toggleMute([2, 3]);
          break;
        case 15:
          this.toggleMute([4, 5, 6]);
          break;
      }
      FB.input.refreshPads();
    }
    padUp() {}
    padLabel(i) {
      if (i < 8) return VOICES[i].name;
      return ['Rethink', 'Mutate', 'Fill', 'Freeze', 'Mute kick', 'Mute snare', 'Mute hats', 'Mute glitch'][i - 8];
    }
    padState(i) {
      if (i === 11) return this.get('freeze');
      const groups = { 12: [0], 13: [1, 7], 14: [2, 3], 15: [4, 5, 6] };
      if (groups[i]) return groups[i].every((v) => this.mutes[v]);
      return false;
    }
    toggleMute(list) {
      const on = !list.every((v) => this.mutes[v]);
      list.forEach((v) => (this.mutes[v] = on));
      this.renderGrid && this.renderGrid();
      FB.state.dirty();
    }

    buildUI(root) {
      const UI = FB.ui;
      const top = U.el('div', { class: 'viz-wrap' });
      root.appendChild(top);
      this.cv = UI.canvas(top, 'viz');
      const bar = U.el('div', { class: 'toolbar' });
      bar.appendChild(U.el('button', { text: '🧠 Rethink', title: 'Grow a brand new pattern', onclick: () => this.rethink() }));
      bar.appendChild(U.el('button', { text: '🧬 Mutate', onclick: () => this.mutateNow(1) }));
      bar.appendChild(U.el('button', { text: '⚡ Fill', onclick: () => (this.fill = 4) }));
      UI.toggle(bar, this, 'freeze', { text: '❄ Freeze' });
      UI.toggle(bar, this, 'poly', { text: '∿ Polymeter' });
      bar.appendChild(U.el('button', { text: '⌫ Clear', onclick: () => {
        this.pattern.forEach((p) => p.steps.fill(0));
        this.renderGrid();
      } }));
      const play = U.el('button', { class: 'accent', text: '▶ Play' });
      play.onclick = () => FB.clock.toggle();
      FB.clock.onChange(() => (play.textContent = FB.clock.playing ? '■ Stop' : '▶ Play'));
      bar.appendChild(play);
      top.appendChild(bar);

      // Editable grid
      const gridEl = U.el('div', { class: 'mind-grid' });
      root.appendChild(gridEl);
      this.cells = [];
      this.renderGrid = () => {
        gridEl.innerHTML = '';
        this.cells = [];
        VOICES.forEach((v, vi) => {
          const row = U.el('div', { class: 'mrow' + (this.mutes[vi] ? ' muted' : '') });
          const name = U.el('button', { class: 'mname', text: v.name, title: 'Click to mute / unmute' });
          name.style.color = v.color;
          name.onclick = () => this.toggleMute([vi]);
          row.appendChild(name);
          const p = this.pattern[vi];
          const rowCells = [];
          for (let i = 0; i < 16; i++) {
            const c = U.el('div', { class: 'mcell' + (i >= p.len ? ' off' : '') });
            const val = p.steps[i];
            if (val > 0) {
              c.classList.add('on');
              c.style.background = v.color;
              c.style.opacity = 0.35 + val * 0.65;
            }
            c.onpointerdown = (e) => {
              e.preventDefault();
              if (i >= p.len) return;
              p.steps[i] = p.steps[i] > 0 ? 0 : 0.9;
              this.renderGrid();
              FB.state.dirty();
            };
            rowCells.push(c);
            row.appendChild(c);
          }
          this.cells.push(rowCells);
          gridEl.appendChild(row);
        });
      };
      this.renderGrid();

      const grid = U.el('div', { class: 'sections' });
      root.appendChild(grid);
      UI.knobs(UI.section(grid, 'Thoughts'), this, ['density', 'mutate', 'seed']);
      UI.knobs(UI.section(grid, 'Disorder'), this, ['chaos', 'glitch', 'crush']);
      UI.knobs(UI.section(grid, 'Body'), this, ['pitch', 'decay', 'level']);
    }

    draw() {
      const cv = this.cv;
      if (!cv) return;
      const g = cv.g, W = cv.w, H = cv.h;
      const now = this.now();
      this.events.drain(now, (ev) => {
        if (ev.fire != null) {
          this.flash[ev.fire] = Math.max(this.flash[ev.fire], ev.vel);
          if (this.lastFired >= 0 && this.lastFired !== ev.fire) this.synapses.push({ a: this.lastFired, b: ev.fire, life: 1, glitch: Math.random() < this.get('glitch') });
          this.lastFired = ev.fire;
        } else if (ev.step != null && this.cells) {
          this.cells.forEach((row, vi) => {
            const len = this.pattern[vi].len;
            row.forEach((c, i) => c.classList.toggle('play', i === ev.step % len));
          });
        }
      });

      g.fillStyle = 'rgba(14,6,24,0.35)';
      g.fillRect(0, 0, W, H);
      const cx = W / 2, cy = H / 2;
      const R = Math.min(W * 0.36, H * 0.36);
      const pos = VOICES.map((v, i) => {
        const a = (i / VOICES.length) * Math.PI * 2 - Math.PI / 2;
        return [cx + Math.cos(a) * R * 1.25, cy + Math.sin(a) * R];
      });
      // Synapses
      this.synapses = this.synapses.filter((s) => (s.life -= 0.035) > 0);
      this.synapses.forEach((s) => {
        const [x1, y1] = pos[s.a];
        const [x2, y2] = pos[s.b];
        g.strokeStyle = 'rgba(220,170,255,' + s.life * 0.8 + ')';
        g.lineWidth = 1 + s.life * 2;
        g.beginPath();
        g.moveTo(x1, y1);
        if (s.glitch) {
          for (let k = 1; k < 6; k++) {
            const t = k / 6;
            g.lineTo(x1 + (x2 - x1) * t + (Math.random() - 0.5) * 30, y1 + (y2 - y1) * t + (Math.random() - 0.5) * 30);
          }
        } else g.quadraticCurveTo(cx, cy, x2, y2);
        g.lineTo(x2, y2);
        g.stroke();
      });
      // Neurons with their pattern rings
      const vs = FB.clock.visualStep;
      VOICES.forEach((v, i) => {
        const [x, y] = pos[i];
        const f = this.flash[i];
        this.flash[i] *= 0.88;
        const p = this.pattern[i];
        const rr = 18 + Math.min(R * 0.12, 14);
        for (let k = 0; k < p.len; k++) {
          const a = (k / p.len) * Math.PI * 2 - Math.PI / 2;
          const on = p.steps[k] > 0;
          const cur = vs >= 0 && k === vs % p.len;
          g.fillStyle = cur ? '#ffffff' : on ? v.color : 'rgba(255,255,255,0.12)';
          g.beginPath();
          g.arc(x + Math.cos(a) * rr, y + Math.sin(a) * rr, cur ? 3.2 : on ? 2.6 : 1.6, 0, Math.PI * 2);
          g.fill();
        }
        g.globalAlpha = this.mutes[i] ? 0.25 : 1;
        g.fillStyle = v.color;
        g.shadowColor = v.color;
        g.shadowBlur = 8 + f * 30;
        g.beginPath();
        g.arc(x, y, 7 + f * 7, 0, Math.PI * 2);
        g.fill();
        g.shadowBlur = 0;
        g.globalAlpha = 1;
        g.fillStyle = 'rgba(255,255,255,0.7)';
        g.font = '11px system-ui, sans-serif';
        g.textAlign = 'center';
        g.fillText(v.name, x, y + rr + 14);
      });
      if (!FB.clock.playing) {
        g.fillStyle = 'rgba(255,255,255,0.55)';
        g.font = '13px system-ui, sans-serif';
        g.textAlign = 'center';
        g.fillText('Press ▶ Play (or Space) to let the minds think', cx, cy + 4);
      }
    }
  }

  FB.Minds = Minds;
})(window.FB);
