# FoxBox known bugs and possible fixes

## S10+ low audio bug

**Report:** On a Galaxy S10+, while using Reflections, the app's volume gets lower over time.

**Status:** Speaker-protection fixes made (see the ticked items below). Needs a retest on the S10+.

### What was ruled out

- Reflections was rendered offline for 3 minutes in each mode: plain, loop, time stretch, time stretch + loop, and reverse. Notes were played and released continuously and the level was logged every 15 s.
- The level held steady in every mode (plain stayed at 0.213 RMS throughout), and voices and grains were always freed. There is no voice or grain leak and no envelope or filter drift.
- Nothing in the code lowers the volume over time:
  - master volume, block volume, the limiter (`audio/Dsp.kt`)
  - the XY pad (`audio/Xy.kt`), MIDI mapping, the step sequencer (`seq/NoteSeq.kt`)
  - the app never changes the system media volume.

### Likely causes (most likely first)

1. **Device speaker protection.** The S10+ smart amp turns the speaker down when it is driven hard for a long time. FoxBox's output runs hot: the master defaults to 0.7 and the limiter only catches peaks around 0.89. Reflections' built-in sample is close to a pure tone, and sustained, pitched-down notes concentrate energy into the small speaker.
2. **DC offset in loaded samples.** Nothing in FoxBox removes DC. `AudioFiles.normalize()` only scales to the peak, so mic recordings and imported files keep any offset. Held or looping Reflections notes then send a steady offset to the speaker, which uses up limiter headroom and heats the speaker coil (feeding cause 1).
3. **Device settings outside the app.** Samsung's Media volume limiter, Adapt Sound, Dolby Atmos, or Bluetooth absolute volume.

### Possible fixes

- [ ] Add a DC blocker (one-pole high-pass, about 10–20 Hz) on each sample engine's output (`SampleEngine.render` in `engines/Sampler.kt`). Probably not needed now that the master and the sample loaders remove DC.
- [x] DC blocker (8 Hz) on the master output, inside the limiter (`Limiter` in `audio/Dsp.kt`).
- [x] Speaker guard: while the sound comes out of the phone's own speaker, a 4th-order 110 Hz high-pass removes sub-bass the speaker can't reproduce, and the limiter ceiling drops from 0.89 to 0.80. The route is tracked with an `AudioDeviceCallback` (`DeviceAudio.updateRoute`), so headphones, USB and Bluetooth still get the full range.
- [x] The limiter's release is now a smooth ~120 ms curve instead of a fast exponential decay, so it doesn't pump.
- [x] Remove DC from samples when they are recorded or imported: `AudioFiles.normalize()` subtracts the mean before peak-normalising.
- [x] Lower ceiling on the speaker (0.80). The master default stays at 0.7.
- [ ] Add a debug readout (or crash-log entry) of output peak, limiter gain reduction, xruns and AAudio stream state. This shows whether the level drop happens inside FoxBox or after it on the device.

### Questions for the next test on the S10+

- Phone speaker or headphones? If it doesn't happen on wired headphones, that points to speaker protection.
- Did FoxBox's own level meter drop, or only the loudness? A steady meter means the drop is on the device side.
- Built-in Reflections sample, or a recorded/imported one? A recorded or imported sample points to DC offset.
- Does the volume come back after a short pause, or only after restarting the app? Recovery after a rest fits speaker protection.
