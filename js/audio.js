/* FoxBox — audio core: context, master bus, shared reverb, mixer
   channels, custom DSP (AudioWorklets) and recording. */
(function (FB) {
  'use strict';

  // AudioWorklet DSP is written here as a string and loaded from a Blob URL,
  // so it works without a web server.
  const WORKLET_SRC = `
class FBPitch extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    return [
      { name: 'semitones', defaultValue: 0, minValue: -36, maxValue: 36, automationRate: 'k-rate' },
      { name: 'window', defaultValue: 0.07, minValue: 0.01, maxValue: 0.4, automationRate: 'k-rate' },
    ];
  }
  constructor() {
    super();
    this.size = Math.floor(sampleRate);
    this.buf = [new Float32Array(this.size), new Float32Array(this.size)];
    this.w = 0;
    this.phase = 0;
  }
  read(c, d) {
    let p = this.w - d;
    while (p < 0) p += this.size;
    const i = Math.floor(p);
    const f = p - i;
    const b = this.buf[c];
    return b[i % this.size] * (1 - f) + b[(i + 1) % this.size] * f;
  }
  process(inputs, outputs, params) {
    const inp = inputs[0];
    const out = outputs[0];
    const ratio = Math.pow(2, params.semitones[0] / 12);
    const win = Math.max(64, Math.floor(params.window[0] * sampleRate));
    const n = out[0].length;
    for (let i = 0; i < n; i++) {
      for (let c = 0; c < 2; c++) {
        const ch = inp.length ? inp[Math.min(c, inp.length - 1)] : null;
        this.buf[c][this.w] = ch ? ch[i] : 0;
      }
      this.phase += (1 - ratio) / win;
      this.phase -= Math.floor(this.phase);
      const p2 = (this.phase + 0.5) % 1;
      const g1 = Math.sin(Math.PI * this.phase);
      const g2 = Math.sin(Math.PI * p2);
      for (let c = 0; c < out.length; c++) {
        const cc = Math.min(c, 1);
        out[c][i] = this.read(cc, this.phase * win) * g1 + this.read(cc, p2 * win) * g2;
      }
      this.w = (this.w + 1) % this.size;
    }
    return true;
  }
}
registerProcessor('fb-pitch', FBPitch);

class FBCrush extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    return [
      { name: 'bits', defaultValue: 16, minValue: 1, maxValue: 16, automationRate: 'k-rate' },
      { name: 'down', defaultValue: 1, minValue: 1, maxValue: 64, automationRate: 'k-rate' },
      { name: 'mix', defaultValue: 0, minValue: 0, maxValue: 1, automationRate: 'k-rate' },
    ];
  }
  constructor() { super(); this.hold = [0, 0]; this.count = 0; }
  process(inputs, outputs, params) {
    const inp = inputs[0], out = outputs[0];
    const levels = Math.pow(2, params.bits[0]) / 2;
    const down = Math.max(1, Math.floor(params.down[0]));
    const mix = params.mix[0];
    const n = out[0].length;
    for (let i = 0; i < n; i++) {
      const take = this.count % down === 0;
      this.count++;
      for (let c = 0; c < out.length; c++) {
        const ch = inp.length ? inp[Math.min(c, inp.length - 1)] : null;
        const x = ch ? ch[i] : 0;
        if (take) this.hold[c & 1] = Math.round(x * levels) / levels;
        out[c][i] = x * (1 - mix) + this.hold[c & 1] * mix;
      }
    }
    return true;
  }
}
registerProcessor('fb-crush', FBCrush);
`;

  const A = (FB.audio = {
    ctx: null,
    hasWorklets: false,
    channels: {},
  });

  A.init = async function () {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    const ctx = (A.ctx = new Ctx({ latencyHint: 'interactive' }));

    // Master section: everything sums into masterBus.
    A.masterBus = ctx.createGain();
    A.limiter = ctx.createDynamicsCompressor();
    A.limiter.threshold.value = -6;
    A.limiter.knee.value = 6;
    A.limiter.ratio.value = 12;
    A.limiter.attack.value = 0.003;
    A.limiter.release.value = 0.15;
    A.master = ctx.createGain();
    A.master.gain.value = 0.8;
    A.analyser = ctx.createAnalyser();
    A.analyser.fftSize = 1024;
    A.masterBus.connect(A.limiter);
    A.limiter.connect(A.master);
    A.master.connect(A.analyser);
    A.master.connect(ctx.destination);
    if (ctx.createMediaStreamDestination) {
      A.streamDest = ctx.createMediaStreamDestination();
      A.master.connect(A.streamDest);
    }

    // Everything that should pass through Teeth goes into teethSend.
    // Teeth connects itself between teethSend and masterBus when it is built.
    A.teethSend = ctx.createGain();

    // Shared reverb ("the lake") used by Reflections and Leaves.
    A.reverbIn = ctx.createGain();
    A.reverb = ctx.createConvolver();
    A.reverb.buffer = A.makeImpulse(3.2, 2.6);
    A.reverbOut = ctx.createGain();
    A.reverbOut.gain.value = 0.9;
    A.reverbIn.connect(A.reverb);
    A.reverb.connect(A.reverbOut);
    A.reverbOut.connect(A.teethSend);

    // A couple of seconds of white noise shared by engines.
    A.noise = ctx.createBuffer(1, ctx.sampleRate * 2, ctx.sampleRate);
    const d = A.noise.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;

    // Blob URLs work when served over http(s); data: URLs work when the
    // page is opened straight from disk (file://).
    A.hasWorklets = false;
    const urls = [];
    try {
      urls.push(URL.createObjectURL(new Blob([WORKLET_SRC], { type: 'application/javascript' })));
    } catch (_) {}
    urls.push('data:application/javascript;base64,' + btoa(WORKLET_SRC));
    if (ctx.audioWorklet) {
      for (const url of urls) {
        try {
          await ctx.audioWorklet.addModule(url);
          A.hasWorklets = true;
          break;
        } catch (e) {}
      }
    }
    if (!A.hasWorklets) console.info('FoxBox: using ScriptProcessor fallback for pitch/crush');
  };

  A.worklet = function (name, params) {
    if (!A.hasWorklets) return null;
    try {
      const n = new AudioWorkletNode(A.ctx, name, { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2] });
      if (params) for (const k in params) n.parameters.get(k).value = params[k];
      return n;
    } catch (e) {
      console.warn(e);
      return null;
    }
  };

  A.makeImpulse = function (seconds, decay) {
    const ctx = A.ctx;
    const len = Math.floor(ctx.sampleRate * seconds);
    const b = ctx.createBuffer(2, len, ctx.sampleRate);
    for (let c = 0; c < 2; c++) {
      const d = b.getChannelData(c);
      let lp = 0;
      for (let i = 0; i < len; i++) {
        const t = i / len;
        // Darken over time like sound across water.
        const k = 0.15 + 0.8 * t;
        lp = lp + (Math.random() * 2 - 1 - lp) * (1 - k);
        d[i] = lp * Math.pow(1 - t, decay) * (i < 200 ? i / 200 : 1);
      }
    }
    return b;
  };

  // A mixer channel for an engine: level, mute, and whether it goes
  // through Teeth or straight to the master.
  A.channel = function (engine) {
    const ctx = A.ctx;
    const ch = {
      engine,
      gain: ctx.createGain(),
      toTeeth: ctx.createGain(),
      direct: ctx.createGain(),
      meter: ctx.createAnalyser(),
      level: 0.8,
      muted: false,
      teeth: true,
    };
    ch.meter.fftSize = 256;
    engine.output.connect(ch.gain);
    ch.gain.connect(ch.toTeeth);
    ch.gain.connect(ch.direct);
    ch.gain.connect(ch.meter);
    ch.toTeeth.connect(A.teethSend);
    ch.direct.connect(A.masterBus);
    ch.apply = function () {
      const t = ctx.currentTime;
      ch.gain.gain.setTargetAtTime(ch.muted ? 0 : ch.level, t, 0.02);
      ch.toTeeth.gain.setTargetAtTime(ch.teeth ? 1 : 0, t, 0.02);
      ch.direct.gain.setTargetAtTime(ch.teeth ? 0 : 1, t, 0.02);
    };
    ch.levelNow = function () {
      const buf = new Float32Array(ch.meter.fftSize);
      ch.meter.getFloatTimeDomainData(buf);
      let m = 0;
      for (let i = 0; i < buf.length; i++) m = Math.max(m, Math.abs(buf[i]));
      return m;
    };
    ch.apply();
    A.channels[engine.id] = ch;
    return ch;
  };

  // Record a MediaStream (the master output or a microphone) and decode it
  // into an AudioBuffer. Returns {start(), stop() -> Promise<AudioBuffer>}.
  A.recorder = function (stream) {
    if (!window.MediaRecorder) throw new Error('Recording is not supported in this browser');
    const chunks = [];
    const rec = new MediaRecorder(stream);
    rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
    return {
      rec,
      start() {
        rec.start();
      },
      stop() {
        return new Promise((resolve, reject) => {
          rec.onstop = async () => {
            try {
              const blob = new Blob(chunks, { type: rec.mimeType });
              const arr = await blob.arrayBuffer();
              const buf = await A.ctx.decodeAudioData(arr);
              resolve({ buffer: buf, blob });
            } catch (e) {
              reject(e);
            }
          };
          rec.stop();
        });
      },
    };
  };

  A.captureMaster = function () {
    if (!A.streamDest) throw new Error('Capturing audio is not supported in this browser');
    return A.recorder(A.streamDest.stream);
  };

  A.captureMic = async function () {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } });
    const r = A.recorder(stream);
    const stop = r.stop;
    r.stop = async () => {
      const res = await stop();
      stream.getTracks().forEach((t) => t.stop());
      return res;
    };
    return r;
  };

  A.decodeFile = async function (file) {
    const arr = await file.arrayBuffer();
    return await A.ctx.decodeAudioData(arr);
  };

  // Render a mono buffer with a function f(t, i, sr) -> sample.
  A.render = function (seconds, f) {
    const sr = A.ctx.sampleRate;
    const len = Math.floor(sr * seconds);
    const b = A.ctx.createBuffer(1, len, sr);
    const d = b.getChannelData(0);
    for (let i = 0; i < len; i++) d[i] = f(i / sr, i, sr);
    let max = 0;
    for (let i = 0; i < len; i++) max = Math.max(max, Math.abs(d[i]));
    if (max > 0) for (let i = 0; i < len; i++) d[i] *= 0.9 / max;
    return b;
  };
})(window.FB);
