/* FoxBox — app shell: boot, header, tabs, the Den (mixer + MIDI setup),
   saving settings, recording and the animation loop. */
(function (FB) {
  'use strict';
  const U = FB.util;
  const $ = (s) => document.querySelector(s);

  const App = (FB.app = { current: 'gusts', lastInstrument: 'gusts' });
  FB.engines = {};

  const TABS = [
    { id: 'gusts', icon: '🌬', name: 'Gusts', sub: 'analog synth' },
    { id: 'reflections', icon: '💧', name: 'Reflections', sub: 'sampler' },
    { id: 'leaves', icon: '🍃', name: 'Leaves', sub: 'granular' },
    { id: 'minds', icon: '🧠', name: 'Minds', sub: 'generative drums' },
    { id: 'teeth', icon: '🦷', name: 'Teeth', sub: 'mangler' },
    { id: 'den', icon: '🦊', name: 'Den', sub: 'mixer & MIDI' },
  ];

  // Which engine receives notes and pads right now.
  App.target = function () {
    const cur = FB.engines[App.current];
    if (cur && (cur.isInstrument || App.current === 'teeth')) {
      // On Teeth, pads perform Teeth moves but keys play the last instrument.
      return cur;
    }
    return FB.engines[App.lastInstrument];
  };

  // ------------------------------------------------------------------
  // Settings persistence (localStorage)
  // ------------------------------------------------------------------
  const KEY = 'foxbox.v1';
  FB.state = {
    dirty: U.debounce(() => FB.state.save(), 600),
    save() {
      if (!App.ready) return;
      const o = {
        bpm: FB.clock.bpm,
        swing: FB.clock.swing,
        scale: FB.input.scale,
        octave: FB.input.octave,
        expressive: FB.input.expressive,
        arp: { mode: FB.input.arp.mode, rate: FB.input.arp.rate, octaves: FB.input.arp.octaves },
        tab: App.current,
        engines: {},
        mixer: {},
        learn: FB.midi.learnMap,
        midiPrefs: FB.midi.prefs,
        master: FB.audio.master.gain.value,
      };
      for (const id in FB.engines) o.engines[id] = FB.engines[id].serialize();
      for (const id in FB.audio.channels) {
        const c = FB.audio.channels[id];
        o.mixer[id] = { level: c.level, muted: c.muted, teeth: c.teeth };
      }
      try {
        localStorage.setItem(KEY, JSON.stringify(o));
      } catch (_) {}
    },
    load() {
      try {
        return JSON.parse(localStorage.getItem(KEY) || 'null');
      } catch (_) {
        return null;
      }
    },
    reset() {
      try {
        localStorage.removeItem(KEY);
      } catch (_) {}
      location.reload();
    },
  };

  // ------------------------------------------------------------------
  // Toasts
  // ------------------------------------------------------------------
  let toastTimer = null;
  App.toast = function (msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 3200);
  };

  // ------------------------------------------------------------------
  // Boot
  // ------------------------------------------------------------------
  App.boot = async function () {
    await FB.audio.init();
    const saved = FB.state.load();
    if (saved) {
      FB.midi.prefs = saved.midiPrefs || {};
      FB.midi.learnMap = saved.learn || {};
    }

    FB.engines.gusts = new FB.Gusts();
    FB.engines.reflections = new FB.Reflections();
    FB.engines.leaves = new FB.Leaves();
    FB.engines.minds = new FB.Minds();
    FB.engines.teeth = new FB.Teeth();
    ['gusts', 'reflections', 'leaves', 'minds'].forEach((id) => FB.audio.channel(FB.engines[id]));

    buildHeader();
    buildTabs();
    for (const id in FB.engines) {
      const panel = U.el('section', { class: 'panel', id: 'panel-' + id, 'data-engine': id });
      panel.style.setProperty('--accent', FB.engines[id].color);
      $('#panels').appendChild(panel);
      FB.engines[id].buildUI(panel);
    }
    const den = U.el('section', { class: 'panel', id: 'panel-den' });
    $('#panels').appendChild(den);
    buildDen(den);

    if (saved) {
      FB.clock.bpm = saved.bpm || FB.clock.bpm;
      FB.clock.swing = saved.swing || 0;
      if (saved.scale) FB.input.scale = saved.scale;
      FB.input.octave = saved.octave || 0;
      if (saved.expressive != null) FB.input.expressive = saved.expressive;
      if (saved.arp) Object.assign(FB.input.arp, saved.arp);
      if (saved.master != null) FB.audio.master.gain.value = saved.master;
      for (const id in saved.engines || {}) if (FB.engines[id]) FB.engines[id].restore(saved.engines[id]);
      for (const id in saved.mixer || {}) {
        const c = FB.audio.channels[id];
        if (c) {
          Object.assign(c, saved.mixer[id]);
          c.apply();
        }
      }
    }

    FB.input.buildKeyboard($('#keyboard'));
    FB.input.buildPads($('#pads'));
    FB.input.initKeys();
    App.select(saved && saved.tab ? saved.tab : 'gusts');
    App.refreshHeader();
    App.ready = true;

    // MIDI: try USB/OS MIDI straight away (no prompt in most browsers).
    if (FB.midi.supported()) {
      FB.midi.initWebMIDI().then(() => {
        const n = Array.from(FB.midi.devices.values()).filter((d) => d.connected).length;
        if (n) App.toast('🎹 ' + n + ' MIDI device' + (n > 1 ? 's' : '') + ' connected');
      }).catch(() => {});
    }
    FB.midi.onChange(() => {
      App.refreshHeader();
      App.renderDevices && App.renderDevices();
    });
    let ledTimer = null;
    FB.midi.onActivity(() => {
      const led = $('#midiLed');
      led.classList.add('on');
      clearTimeout(ledTimer);
      ledTimer = setTimeout(() => led.classList.remove('on'), 80);
    });

    requestAnimationFrame(loop);
  };

  // ------------------------------------------------------------------
  // Header
  // ------------------------------------------------------------------
  function buildHeader() {
    const play = $('#play');
    play.onclick = () => FB.clock.toggle();
    FB.clock.onChange(() => {
      play.classList.toggle('on', FB.clock.playing);
      play.textContent = FB.clock.playing ? '■' : '▶';
    });
    const bpm = $('#bpm');
    bpm.onchange = () => {
      FB.clock.bpm = U.clamp(+bpm.value || 112, 40, 240);
      bpm.value = FB.clock.bpm;
      FB.state.dirty();
    };
    let tapTimes = [];
    $('#tap').onclick = () => {
      const now = performance.now();
      tapTimes = tapTimes.filter((t) => now - t < 2500);
      tapTimes.push(now);
      if (tapTimes.length >= 2) {
        const avg = (tapTimes[tapTimes.length - 1] - tapTimes[0]) / (tapTimes.length - 1);
        FB.clock.bpm = Math.round(U.clamp(60000 / avg, 40, 240));
        bpm.value = FB.clock.bpm;
        FB.state.dirty();
      }
    };
    const swing = $('#swing');
    swing.oninput = () => {
      FB.clock.swing = +swing.value / 100;
      FB.state.dirty();
    };

    $('#octDown').onclick = () => FB.input.shiftOctave(-1);
    $('#octUp').onclick = () => FB.input.shiftOctave(1);
    $('#hold').onclick = () => {
      FB.input.setHold(!FB.input.hold);
      App.refreshHeader();
    };
    $('#expr').onclick = () => {
      FB.input.expressive = !FB.input.expressive;
      App.refreshHeader();
      FB.state.dirty();
    };
    const arpMode = $('#arpMode');
    ['Off', 'Up', 'Down', 'Up/Down', 'Random', 'Chord'].forEach((m) => arpMode.appendChild(U.el('option', { value: m, text: m === 'Off' ? 'Arp off' : 'Arp ' + m })));
    arpMode.onchange = () => {
      if (arpMode.value === 'Off') FB.input.setArp(false);
      else {
        FB.input.arp.mode = arpMode.value;
        FB.input.setArp(true);
      }
      FB.state.dirty();
    };
    const arpRate = $('#arpRate');
    [[1, '1/16'], [2, '1/8'], [3, '1/8.'], [4, '1/4']].forEach(([v, n]) => arpRate.appendChild(U.el('option', { value: v, text: n })));
    arpRate.onchange = () => {
      FB.input.arp.rate = +arpRate.value;
      FB.state.dirty();
    };
    const root = $('#scaleRoot');
    U.NOTE_NAMES.forEach((n, i) => root.appendChild(U.el('option', { value: i, text: n })));
    const scale = $('#scaleName');
    Object.keys(U.SCALES).forEach((n) => scale.appendChild(U.el('option', { value: n, text: n })));
    const onScale = () => {
      FB.input.scale = { root: +root.value, name: scale.value };
      FB.input.refreshPads();
      FB.state.dirty();
    };
    root.onchange = onScale;
    scale.onchange = onScale;

    $('#midiBtn').onclick = () => App.select('den');
    $('#bleBtn').onclick = connectBLE;
    $('#recBtn').onclick = toggleRecord;
    $('#panic').onclick = () => FB.input.panic();
    FB.input.onChange(() => App.refreshHeader());
  }

  App.refreshHeader = function () {
    $('#bpm').value = FB.clock.bpm;
    $('#swing').value = Math.round(FB.clock.swing * 100);
    $('#octVal').textContent = (FB.input.octave >= 0 ? '+' : '') + FB.input.octave;
    $('#hold').classList.toggle('on', FB.input.hold);
    $('#expr').classList.toggle('on', FB.input.expressive);
    $('#arpMode').value = FB.input.arp.on ? FB.input.arp.mode : 'Off';
    $('#arpRate').value = FB.input.arp.rate;
    $('#scaleRoot').value = FB.input.scale.root;
    $('#scaleName').value = FB.input.scale.name;
    const n = Array.from(FB.midi.devices.values()).filter((d) => d.connected).length;
    $('#midiCount').textContent = n ? n : '';
  };

  async function connectBLE() {
    try {
      const dev = await FB.midi.connectBLE();
      App.toast('🔵 Connected ' + dev.name + ' over Bluetooth (' + roleName(dev.role) + ')');
    } catch (e) {
      if (e && e.name === 'NotFoundError') return; // user closed the chooser
      App.toast('Bluetooth MIDI: ' + (e.message || e));
    }
  }

  // ------------------------------------------------------------------
  // Recording the master output to a file
  // ------------------------------------------------------------------
  let rec = null;
  async function toggleRecord() {
    const b = $('#recBtn');
    try {
      if (rec) {
        const r = rec;
        rec = null;
        b.classList.remove('on');
        b.textContent = '● Rec';
        const res = await r.stop();
        const url = URL.createObjectURL(res.blob);
        const ext = (res.blob.type.split('/')[1] || 'webm').split(';')[0];
        const a = U.el('a', { href: url, download: 'foxbox-' + new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-') + '.' + ext });
        document.body.appendChild(a);
        a.click();
        a.remove();
        App.toast('Saved your recording');
        return;
      }
      rec = FB.audio.captureMaster();
      rec.start();
      b.classList.add('on');
      b.textContent = '■ Stop rec';
    } catch (e) {
      rec = null;
      App.toast('Recording failed: ' + e.message);
    }
  }

  // ------------------------------------------------------------------
  // Tabs
  // ------------------------------------------------------------------
  function buildTabs() {
    const nav = $('#tabs');
    TABS.forEach((t) => {
      const b = U.el('button', { class: 'tab', 'data-tab': t.id }, [U.el('span', { class: 'ticon', text: t.icon }), U.el('span', { class: 'tname', text: t.name }), U.el('span', { class: 'tsub', text: t.sub })]);
      if (FB.engines[t.id]) b.style.setProperty('--accent', FB.engines[t.id].color);
      b.onclick = () => App.select(t.id);
      nav.appendChild(b);
    });
  }

  App.select = function (id) {
    if (!document.getElementById('panel-' + id)) id = 'gusts';
    App.current = id;
    const eng = FB.engines[id];
    if (eng && eng.isInstrument) App.lastInstrument = id;
    document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('on', b.dataset.tab === id));
    document.querySelectorAll('.panel').forEach((p) => p.classList.toggle('on', p.id === 'panel-' + id));
    document.body.dataset.tab = id;
    const accent = eng ? eng.color : '#ff8a3d';
    document.body.style.setProperty('--accent', accent);
    const tgt = FB.engines[App.lastInstrument];
    $('#playTarget').textContent = id === 'teeth' ? 'Keys → ' + tgt.name + ' · Pads → Teeth moves' : id === 'den' ? 'Keys & pads → ' + tgt.name : 'Playing ' + (eng ? eng.name : tgt.name);
    FB.input.refreshPads();
    requestAnimationFrame(() => {
      const p = document.getElementById('panel-' + id);
      p && p.querySelectorAll('canvas').forEach(() => window.dispatchEvent(new Event('resize')));
    });
    FB.state.dirty();
  };

  // Keys on the Teeth/Den tab keep playing the last instrument.
  const origNoteOn = FB.input.noteOn;
  FB.input.noteOn = function (key, note, vel, meta) {
    meta = meta || {};
    if (!meta.eng && (App.current === 'teeth' || App.current === 'den')) meta.eng = FB.engines[App.lastInstrument];
    return origNoteOn(key, note, vel, meta);
  };

  // ------------------------------------------------------------------
  // The Den: mixer + MIDI devices + help
  // ------------------------------------------------------------------
  function roleName(r) {
    return { keys: 'Keys', mpe: 'MPE keys', pads: 'Pads' }[r] || r;
  }

  function buildDen(root) {
    const wrap = U.el('div', { class: 'den' });
    root.appendChild(wrap);

    // Mixer
    const mix = U.el('div', { class: 'sect den-mixer' }, [U.el('h3', { text: 'Mixer' })]);
    const strips = U.el('div', { class: 'strips' });
    mix.appendChild(strips);
    wrap.appendChild(mix);
    App.meters = [];
    ['gusts', 'reflections', 'leaves', 'minds'].forEach((id) => {
      const eng = FB.engines[id];
      const ch = FB.audio.channels[id];
      const s = U.el('div', { class: 'strip' });
      s.style.setProperty('--accent', eng.color);
      const meter = U.el('div', { class: 'meter' }, [U.el('div', { class: 'mfill' })]);
      const range = U.el('input', { type: 'range', min: 0, max: 100, value: Math.round(ch.level * 100), class: 'vfader' });
      range.oninput = () => {
        ch.level = range.value / 100;
        ch.apply();
        FB.state.dirty();
      };
      const mute = U.el('button', { class: 'toggle' + (ch.muted ? ' on' : ''), text: 'Mute' });
      mute.onclick = () => {
        ch.muted = !ch.muted;
        mute.classList.toggle('on', ch.muted);
        ch.apply();
        FB.state.dirty();
      };
      const teeth = U.el('button', { class: 'toggle' + (ch.teeth ? ' on' : ''), text: '🦷 Teeth', title: 'Send this engine through Teeth' });
      teeth.onclick = () => {
        ch.teeth = !ch.teeth;
        teeth.classList.toggle('on', ch.teeth);
        ch.apply();
        FB.state.dirty();
      };
      s.appendChild(U.el('div', { class: 'faderwrap' }, [range, meter]));
      s.appendChild(mute);
      s.appendChild(teeth);
      s.appendChild(U.el('div', { class: 'sname', text: eng.name }));
      strips.appendChild(s);
      App.meters.push({ ch, fill: meter.firstChild, range, mute, teeth });
    });
    // Master
    const ms = U.el('div', { class: 'strip master' });
    const mr = U.el('input', { type: 'range', min: 0, max: 100, value: Math.round(FB.audio.master.gain.value * 100), class: 'vfader' });
    mr.oninput = () => {
      FB.audio.master.gain.setTargetAtTime(mr.value / 100, FB.audio.ctx.currentTime, 0.02);
      FB.state.dirty();
    };
    const mm = U.el('div', { class: 'meter' }, [U.el('div', { class: 'mfill' })]);
    App.masterMeter = mm.firstChild;
    ms.appendChild(U.el('div', { class: 'faderwrap' }, [mr, mm]));
    ms.appendChild(U.el('div', { class: 'sname', text: 'Master' }));
    strips.appendChild(ms);

    // MIDI
    const midi = U.el('div', { class: 'sect den-midi' }, [U.el('h3', { text: 'MIDI controllers' })]);
    const btns = U.el('div', { class: 'toolbar' });
    btns.appendChild(U.el('button', {
      text: '🔌 Enable USB MIDI',
      onclick: () => FB.midi.initWebMIDI().then(() => App.toast('USB / system MIDI ready')).catch((e) => App.toast(e.message)),
    }));
    btns.appendChild(U.el('button', { text: '🔵 Pair Bluetooth MIDI', onclick: connectBLE }));
    btns.appendChild(U.el('button', { text: 'Forget MIDI-learn mappings', onclick: () => {
      FB.midi.clearLearn();
      App.toast('MIDI-learn mappings cleared');
    } }));
    midi.appendChild(btns);
    const list = U.el('div', { class: 'devices' });
    midi.appendChild(list);
    App.renderDevices = () => {
      list.innerHTML = '';
      const devs = Array.from(FB.midi.devices.values());
      if (!devs.length) {
        list.appendChild(U.el('p', { class: 'muted', text: FB.midi.supported() ? 'No controllers yet. Plug one in over USB, or pair over Bluetooth.' : 'This browser has no Web MIDI — use Chrome or Edge for controllers. You can still play with the screen and computer keyboard.' }));
        return;
      }
      devs.forEach((d) => {
        const row = U.el('div', { class: 'device' + (d.connected ? '' : ' gone') });
        row.appendChild(U.el('span', { class: 'dname', text: (d.kind === 'ble' ? '🔵 ' : '🔌 ') + d.name + (d.connected ? '' : ' (disconnected)') }));
        const sel = U.el('select');
        ['keys', 'mpe', 'pads'].forEach((r) => sel.appendChild(U.el('option', { value: r, text: roleName(r) })));
        sel.value = d.role;
        sel.onchange = () => FB.midi.setRole(d, sel.value);
        row.appendChild(U.el('label', { class: 'sel' }, [U.el('span', { text: 'Plays as' }), sel]));
        if (d.role === 'pads') {
          const lb = U.el('button', { text: d.learnPadBase ? 'Hit pad 1 now…' : 'Pad 1 = ' + U.noteName(d.padBase) + ' (learn)' });
          lb.onclick = () => {
            d.learnPadBase = true;
            lb.textContent = 'Hit pad 1 now…';
          };
          row.appendChild(lb);
        } else {
          const br = U.el('input', { type: 'number', min: 1, max: 96, value: d.bendRange, class: 'small' });
          br.onchange = () => FB.midi.setPref(d, 'bendRange', U.clamp(+br.value || 2, 1, 96));
          row.appendChild(U.el('label', { class: 'sel' }, [U.el('span', { text: 'Bend ±st' }), br]));
        }
        if (d.kind === 'ble' && d.connected) row.appendChild(U.el('button', { text: 'Disconnect', onclick: () => FB.midi.disconnectBLE(d) }));
        list.appendChild(row);
      });
    };
    App.renderDevices();
    wrap.appendChild(midi);

    // Help
    const help = U.el('div', { class: 'sect den-help' }, [U.el('h3', { text: 'Field guide' })]);
    help.appendChild(U.el('div', {
      class: 'help',
      html:
        '<p><b>ROLI Seaboard M</b> — choose <i>MPE keys</i> (picked automatically when the name matches). Each note gets its own glide (pitch), press (pressure) and slide (timbre). Set the Seaboard to MPE mode in ROLI Dashboard / Equator; bend range 48 is the MPE default.</p>' +
        '<p><b>Synido TempoPad C16</b> — choose <i>Pads</i>. Its 16 pads drive the on-screen pads: notes, slices, drum neurons or Teeth moves depending on the tab. If pad 1 isn’t pad 1, press <i>learn</i> and hit pad 1. Map its knobs with MIDI learn.</p>' +
        '<p><b>MIDI learn</b> — right-click (or long-press) any knob or fader, then move a knob on your controller.</p>' +
        '<p><b>Bluetooth</b> — <i>Pair Bluetooth MIDI</i> connects directly in Chrome/Edge. On macOS, iOS, Windows or Android you can also pair in the system Bluetooth/MIDI settings and the device shows up like a USB one.</p>' +
        '<p><b>No controller?</b> Play the on-screen keyboard (drag sideways to bend, up/down to change tone), tap the pads, or use your computer keys: <kbd>A W S E D F T G Y H U J K</kbd> play notes, <kbd>Z</kbd>/<kbd>X</kbd> octave, <kbd>1</kbd>–<kbd>=</kbd> pads, <kbd>Space</kbd> play/stop, <kbd>Esc</kbd> stop all sound. Try Hold + Arp, Leaves’ Evergreen drone and the Minds sequencer.</p>',
    }));
    help.appendChild(U.el('div', { class: 'toolbar' }, [U.el('button', { text: 'Reset FoxBox to defaults', onclick: () => confirm('Reset all FoxBox settings?') && FB.state.reset() })]));
    wrap.appendChild(help);
  }

  // ------------------------------------------------------------------
  // Animation loop
  // ------------------------------------------------------------------
  const scopeBuf = new Float32Array(1024);
  function loop(time) {
    requestAnimationFrame(loop);
    FB.clock.updateVisual();
    const eng = FB.engines[App.current];
    if (eng) {
      try {
        eng.cv && eng.cv.fit();
        eng.xy && eng.xy.fit();
        eng.draw(time);
      } catch (e) {
        console.error(e);
      }
    }
    // Scope in header
    const c = $('#scope');
    const g = c.getContext('2d');
    const w = c.width, h = c.height;
    FB.audio.analyser.getFloatTimeDomainData(scopeBuf);
    g.clearRect(0, 0, w, h);
    g.strokeStyle = getComputedStyle(document.body).getPropertyValue('--accent') || '#ff8a3d';
    g.lineWidth = 1.5;
    g.beginPath();
    let peak = 0;
    for (let i = 0; i < scopeBuf.length; i += 4) {
      const x = (i / scopeBuf.length) * w;
      const y = h / 2 - scopeBuf[i] * h * 0.45;
      peak = Math.max(peak, Math.abs(scopeBuf[i]));
      i === 0 ? g.moveTo(x, y) : g.lineTo(x, y);
    }
    g.stroke();
    if (App.current === 'den' && App.meters) {
      App.meters.forEach((m) => (m.fill.style.height = Math.min(100, m.ch.levelNow() * 100) + '%'));
      App.masterMeter.style.height = Math.min(100, peak * 100) + '%';
    }
    // Step LED
    const led = $('#stepLed');
    led.textContent = FB.clock.playing && FB.clock.visualStep >= 0 ? Math.floor((FB.clock.visualStep % 16) / 4) + 1 + '.' + ((FB.clock.visualStep % 4) + 1) : '—';
  }

  // ------------------------------------------------------------------
  // Start: browsers need a tap before audio can play.
  // ------------------------------------------------------------------
  window.addEventListener('DOMContentLoaded', () => {
    const start = $('#wake');
    start.onclick = async () => {
      start.disabled = true;
      start.textContent = 'Waking…';
      try {
        await App.boot();
        await FB.audio.ctx.resume();
        $('#boot').classList.add('gone');
      } catch (e) {
        console.error(e);
        start.textContent = 'Something went wrong: ' + e.message;
      }
    };
    // Resume audio if the browser suspends it.
    document.addEventListener('pointerdown', () => {
      if (FB.audio.ctx && FB.audio.ctx.state === 'suspended') FB.audio.ctx.resume();
    });
  });
})(window.FB);
