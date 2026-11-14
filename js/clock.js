/* FoxBox — transport clock. Schedules 16th-note steps slightly ahead of
   time against the audio clock so rhythms stay tight. */
(function (FB) {
  'use strict';
  const C = (FB.clock = {
    bpm: 112,
    swing: 0,
    playing: false,
    step: 0,
    nextTime: 0,
    visualStep: -1,
    listeners: [],
    _timer: null,
    _visual: [],
  });

  C.stepDur = function () {
    return 60 / C.bpm / 4;
  };

  C.onStep = function (fn) {
    C.listeners.push(fn);
  };

  C.start = function () {
    if (C.playing) return;
    const ctx = FB.audio.ctx;
    C.playing = true;
    C.step = 0;
    C.nextTime = ctx.currentTime + 0.06;
    C._timer = setInterval(C._tick, 25);
    C._tick();
    C._emit();
  };

  C.stop = function () {
    if (!C.playing) return;
    C.playing = false;
    clearInterval(C._timer);
    C._visual.length = 0;
    C.visualStep = -1;
    C.listeners.forEach((fn) => fn(-1, FB.audio.ctx.currentTime, C.stepDur()));
    C._emit();
  };

  C.toggle = function () {
    C.playing ? C.stop() : C.start();
  };

  C._tick = function () {
    const ctx = FB.audio.ctx;
    while (C.nextTime < ctx.currentTime + 0.12) {
      const dur = C.stepDur();
      let t = C.nextTime;
      if (C.step % 2 === 1) t += C.swing * dur * 0.5;
      const s = C.step;
      for (let i = 0; i < C.listeners.length; i++) {
        try {
          C.listeners[i](s, t, dur);
        } catch (e) {
          console.error(e);
        }
      }
      C._visual.push({ s, t });
      C.nextTime += dur;
      C.step++;
    }
  };

  // Called from the animation loop.
  C.updateVisual = function () {
    const now = FB.audio.ctx.currentTime;
    while (C._visual.length && C._visual[0].t <= now) C.visualStep = C._visual.shift().s;
  };

  const subs = [];
  C.onChange = (fn) => subs.push(fn);
  C._emit = () => subs.forEach((fn) => fn());
})(window.FB);
