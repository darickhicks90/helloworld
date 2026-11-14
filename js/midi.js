/* FoxBox — MIDI input.
   - USB MIDI (and Bluetooth MIDI already paired in the OS) via the Web MIDI API.
   - Bluetooth LE MIDI directly from the browser via Web Bluetooth.
   - Per-device roles: Keys, MPE keys (ROLI Seaboard), or Pads (Synido TempoPad C16).
   - MIDI learn: right-click / long-press any knob, then move a controller knob. */
(function (FB) {
  'use strict';
  const BLE_MIDI_SERVICE = '03b80e5a-ede8-4b33-a751-6ce34ec4c700';
  const BLE_MIDI_CHAR = '7772e5db-3868-4112-a1a9-f2669d106bf3';

  const M = (FB.midi = {
    access: null,
    devices: new Map(), // id -> device record
    learnMap: {}, // 'cc:NUM' -> 'engineId.paramKey'
    learning: null,
    prefs: {}, // name -> {role, padBase, bendRange}
  });

  const subs = [];
  M.onChange = (fn) => subs.push(fn);
  M._emit = () => subs.forEach((fn) => fn());
  let activityFn = null;
  M.onActivity = (fn) => (activityFn = fn);

  // Inside the FoxBox Android app, MIDI comes from Android's own MIDI
  // system through a bridge object the app adds to the page.
  const ANDROID = window.FoxBoxAndroid || null;
  M.isAndroidApp = !!ANDROID;
  M.supported = () => !!ANDROID || !!navigator.requestMIDIAccess;
  M.bleSupported = () => !!ANDROID || !!(navigator.bluetooth && navigator.bluetooth.requestDevice);

  M.autoRole = function (name) {
    const n = (name || '').toLowerCase();
    if (/seaboard|roli|lumi|mpe|osmose|linnstrument|continuum/.test(n)) return 'mpe';
    if (/tempopad|c16|synido|pad/.test(n)) return 'pads';
    return 'keys';
  };

  function makeDevice(id, name, kind) {
    const pref = M.prefs[name] || {};
    const role = pref.role || M.autoRole(name);
    return {
      id,
      name: name || 'MIDI device',
      kind,
      role,
      padBase: pref.padBase != null ? pref.padBase : 36,
      bendRange: pref.bendRange || (role === 'mpe' ? 48 : 2),
      connected: true,
      learnPadBase: false,
      padNotes: new Map(),
    };
  }

  M.setRole = function (dev, role) {
    dev.role = role;
    dev.bendRange = role === 'mpe' ? 48 : 2;
    M.prefs[dev.name] = Object.assign(M.prefs[dev.name] || {}, { role, bendRange: dev.bendRange });
    FB.state && FB.state.dirty();
    M._emit();
  };
  M.setPref = function (dev, key, v) {
    dev[key] = v;
    M.prefs[dev.name] = Object.assign(M.prefs[dev.name] || {}, { [key]: v });
    FB.state && FB.state.dirty();
    M._emit();
  };

  // ------------------------------------------------------------------
  // Web MIDI (USB + OS-paired Bluetooth)
  // ------------------------------------------------------------------
  M.initWebMIDI = async function () {
    if (ANDROID) return M.initAndroid();
    if (!M.supported()) throw new Error('This browser has no Web MIDI. Try Chrome, Edge or Opera.');
    if (M.access) return M.access;
    M.access = await navigator.requestMIDIAccess({ sysex: false });
    const refresh = () => {
      const seen = new Set();
      M.access.inputs.forEach((input) => {
        seen.add(input.id);
        let dev = M.devices.get(input.id);
        if (!dev) {
          dev = makeDevice(input.id, input.name, 'usb');
          M.devices.set(input.id, dev);
        }
        dev.connected = input.state === 'connected';
        input.onmidimessage = (e) => M.handle(dev, e.data);
      });
      M.devices.forEach((d, id) => {
        if (d.kind === 'usb' && !seen.has(id)) d.connected = false;
      });
      M._emit();
    };
    M.access.onstatechange = refresh;
    refresh();
    return M.access;
  };

  // ------------------------------------------------------------------
  // Android app bridge (USB + Bluetooth MIDI through android.media.midi)
  // ------------------------------------------------------------------
  M.initAndroid = function () {
    if (M._android) return Promise.resolve();
    M._android = true;
    const hooks = (window.FBAndroid = window.FBAndroid || {});
    // batch: [[nativeId, [bytes...]], ...]
    hooks.onMidi = function (batch) {
      for (let i = 0; i < batch.length; i++) {
        let dev = M.devices.get('a' + batch[i][0]);
        if (!dev) {
          M.refreshAndroid();
          dev = M.devices.get('a' + batch[i][0]);
        }
        if (dev) M.parseStream(dev, batch[i][1]);
      }
    };
    hooks.onDevices = () => M.refreshAndroid(true);
    hooks.toast = (msg) => FB.app && FB.app.toast(msg);
    ANDROID.openAllMidi();
    M.refreshAndroid();
    return Promise.resolve();
  };

  M.refreshAndroid = function (announce) {
    let list = [];
    try {
      list = JSON.parse(ANDROID.listMidiDevices());
    } catch (_) {}
    const seen = new Set();
    list.forEach((d) => {
      const id = 'a' + d.id;
      seen.add(id);
      let dev = M.devices.get(id);
      const isNew = !dev || !dev.connected;
      if (!dev) {
        dev = makeDevice(id, d.name, d.kind);
        dev.nativeId = d.id;
        M.devices.set(id, dev);
      }
      dev.connected = !!d.open;
      if (announce && isNew && dev.connected && FB.app) FB.app.toast('Connected ' + dev.name + ' (' + ({ keys: 'Keys', mpe: 'MPE keys', pads: 'Pads' }[dev.role] || dev.role) + ')');
    });
    M.devices.forEach((d, id) => {
      if (id[0] === 'a' && !seen.has(id) && d.connected) {
        d.connected = false;
        FB.input.releaseDevice(d);
      }
    });
    M._emit();
  };

  // Split a raw MIDI byte stream (running status allowed) into messages.
  M.parseStream = function (dev, d) {
    let running = dev._running || 0;
    let msg = dev._partial || null;
    for (let i = 0; i < d.length; i++) {
      const b = d[i];
      if (b >= 0xf8) {
        M.handle(dev, [b]);
        continue;
      }
      if (dev._sysex) {
        if (b === 0xf7) dev._sysex = false;
        continue;
      }
      if (b & 0x80) {
        if (b === 0xf0) {
          dev._sysex = true;
          msg = null;
          continue;
        }
        running = b >= 0xf0 ? 0 : b;
        msg = [b];
        if (dataLen(b) === 0) {
          M.handle(dev, msg);
          msg = null;
        }
        continue;
      }
      if (!msg) {
        if (!running) continue;
        msg = [running];
      }
      msg.push(b);
      if (msg.length === dataLen(msg[0]) + 1) {
        M.handle(dev, msg);
        msg = null;
      }
    }
    dev._running = running;
    dev._partial = msg;
  };

  // ------------------------------------------------------------------
  // Bluetooth LE MIDI via Web Bluetooth
  // ------------------------------------------------------------------
  M.connectBLE = async function () {
    if (ANDROID) {
      // The app shows its own Bluetooth device chooser and reports back.
      await M.initAndroid();
      ANDROID.pairBluetooth();
      return null;
    }
    if (!M.bleSupported()) throw new Error('This browser has no Web Bluetooth. Use Chrome or Edge (desktop or Android), or pair the device in your OS Bluetooth/MIDI settings.');
    const bt = await navigator.bluetooth.requestDevice({ filters: [{ services: [BLE_MIDI_SERVICE] }], optionalServices: [BLE_MIDI_SERVICE] });
    const id = 'ble:' + bt.id;
    let dev = M.devices.get(id);
    if (!dev) {
      dev = makeDevice(id, bt.name, 'ble');
      M.devices.set(id, dev);
    }
    const server = await bt.gatt.connect();
    const service = await server.getPrimaryService(BLE_MIDI_SERVICE);
    const ch = await service.getCharacteristic(BLE_MIDI_CHAR);
    await ch.startNotifications();
    ch.addEventListener('characteristicvaluechanged', (e) => {
      const v = e.target.value;
      M.parseBLE(dev, new Uint8Array(v.buffer, v.byteOffset, v.byteLength));
    });
    bt.addEventListener('gattserverdisconnected', () => {
      dev.connected = false;
      FB.input.releaseDevice(dev);
      M._emit();
    });
    dev.connected = true;
    dev.bt = bt;
    M._emit();
    return dev;
  };

  M.disconnectBLE = function (dev) {
    if (ANDROID && dev.nativeId != null) return ANDROID.disconnect(String(dev.nativeId));
    if (dev.bt && dev.bt.gatt.connected) dev.bt.gatt.disconnect();
  };

  function dataLen(status) {
    const hi = status & 0xf0;
    if (hi === 0xc0 || hi === 0xd0) return 1;
    if (hi >= 0x80 && hi <= 0xe0) return 2;
    if (status === 0xf1 || status === 0xf3) return 1;
    if (status === 0xf2) return 2;
    return 0;
  }

  // BLE-MIDI packet: [header][timestamp][status][data..][timestamp][status]...
  // Running status and multiple messages per packet are allowed.
  M.parseBLE = function (dev, d) {
    if (d.length < 2) return;
    let i = 1;
    let running = 0;
    let inSysex = false;
    while (i < d.length) {
      let b = d[i];
      if (inSysex) {
        if (b === 0xf7) inSysex = false;
        i++;
        continue;
      }
      if (b & 0x80) {
        // Timestamp byte. Next byte may be a status byte or running-status data.
        i++;
        if (i >= d.length) break;
        b = d[i];
        if (b & 0x80) {
          if (b === 0xf0) {
            inSysex = true;
            i++;
            continue;
          }
          if (b === 0xf7) {
            i++;
            continue;
          }
          if (b >= 0xf8) {
            M.handle(dev, [b]);
            i++;
            continue;
          }
          running = b;
          i++;
        }
      }
      if (!running) {
        i++;
        continue;
      }
      const n = dataLen(running);
      const msg = [running];
      for (let k = 0; k < n && i < d.length; k++) msg.push(d[i++]);
      if (msg.length === n + 1) M.handle(dev, msg);
      if (n === 0) i++;
    }
  };

  // ------------------------------------------------------------------
  // Message handling
  // ------------------------------------------------------------------
  M.handle = function (dev, data) {
    const status = data[0];
    if (status >= 0xf0) return; // ignore clock/system for now
    const type = status & 0xf0;
    const ch = status & 0x0f;
    const d1 = data[1] || 0;
    const d2 = data[2] || 0;
    if (activityFn) activityFn(dev);
    const I = FB.input;

    switch (type) {
      case 0x90:
        if (d2 > 0) {
          if (dev.learnPadBase) {
            dev.learnPadBase = false;
            M.setPref(dev, 'padBase', d1);
          }
          I.midiNoteOn(dev, ch, d1, d2 / 127);
        } else I.midiNoteOff(dev, ch, d1);
        break;
      case 0x80:
        I.midiNoteOff(dev, ch, d1);
        break;
      case 0xa0:
        I.midiPolyPressure(dev, ch, d1, d2 / 127);
        break;
      case 0xb0:
        M.cc(dev, ch, d1, d2);
        break;
      case 0xd0:
        I.midiChannelExpr(dev, ch, 'pressure', d1 / 127);
        break;
      case 0xe0: {
        const v = ((d2 << 7) | d1) - 8192;
        const range = dev.role === 'mpe' && ch === 0 ? 2 : dev.bendRange;
        I.midiBend(dev, ch, (v / 8192) * range);
        break;
      }
    }
  };

  M.cc = function (dev, ch, num, val) {
    const I = FB.input;
    // MPE timbre ("slide") on CC74 from MPE devices goes to the note.
    if (num === 74 && dev.role === 'mpe') return I.midiChannelExpr(dev, ch, 'slide', val / 127);
    if (M.learning) {
      const { engine, key, el } = M.learning;
      for (const k in M.learnMap) if (M.learnMap[k] === engine.id + '.' + key) delete M.learnMap[k];
      M.learnMap['cc:' + num] = engine.id + '.' + key;
      el && el.classList.remove('learning');
      el && el.classList.add('learned');
      setTimeout(() => el && el.classList.remove('learned'), 800);
      M.learning = null;
      FB.app && FB.app.toast('Mapped CC ' + num + ' → ' + engine.name + ' ' + engine.params[key].label);
      FB.state && FB.state.dirty();
      return;
    }
    if (num === 64) return I.sustain(val >= 64);
    if (num === 123 || num === 120) return I.panic();
    const target = M.learnMap['cc:' + num];
    if (target) {
      const [eid, key] = target.split('.');
      const eng = FB.engines[eid];
      if (eng) eng.setNorm(key, val / 127);
      return;
    }
    if (num === 74) return I.midiChannelExpr(dev, ch, 'slide', val / 127);
    if (num === 1) return I.modWheel(val / 127);
  };

  M.learn = function (engine, key, el) {
    if (M.learning && M.learning.el) M.learning.el.classList.remove('learning');
    if (M.learning && M.learning.engine === engine && M.learning.key === key) {
      M.learning = null;
      return;
    }
    M.learning = { engine, key, el };
    el && el.classList.add('learning');
    FB.app && FB.app.toast('MIDI learn: move a knob or fader on your controller for “' + engine.params[key].label + '” (right-click again to cancel)');
  };

  M.clearLearn = function () {
    M.learnMap = {};
    FB.state && FB.state.dirty();
  };
})(window.FB);
