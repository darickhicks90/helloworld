# 🦊 FoxBox

FoxBox is a music app for Android, built to be played sideways (landscape).
You **write** loops with up to six **Fox Box Blocks** (instruments), record
audio takes from a class-compliant interface, then **perform** it all in the
clip launcher.

## How it works

1. **Projects:** FoxBox opens on your projects. Tap **＋ New project** or
   open one. Long-press a project to rename, duplicate or delete it.
2. **Blocks:** a session holds up to **6 blocks**. New projects start with
   **Gusts** and **Minds**.
   - Tap **＋** on the left rail (it's the size of a block icon) to add a
     block. It disappears when the session is full.
   - To remove a block, hold its icon until it clicks, then drag it onto the
     **R.I.P.** tombstone. Its clips are kept and come back if you add the
     block again.
   - **Flowers** (effects) and the **clip launcher** are always on the rail.
3. **Melodic blocks** (Gusts, Reflections, Leaves, Bumble) have:
   - an **XY pad**. Tap **X ▾ / Y ▾** to choose what each axis controls. Tap
     **STAY / HOME** to switch modes. **Stay** keeps the values where you
     let go. **Home** springs back to the home position, which you set by
     double-tapping the crosshair while in stay mode.
   - the **note selector** (chords, key, scale, octave, 7th). At its foot,
     **Seq ON/OFF** starts the block's **16-step sequencer** and
     **Steps / Strum** swaps the right column between the sequencer and the
     strum pad. Steps are scale degrees that follow the chord you tap.
   - the **strum pad**. Hold a chord and swipe: the chord keeps ringing
     while you strum over it.
4. **The top bar:**
   - the project name (back to your projects)
   - the MIDI transport: **▶ play, ■ stop, ● record**. Record captures the
     block in front for its **Next clip** length.
   - **audio rec**, which records audio takes (see below)
   - the bar.beat counter, **BPM** and **Click** (the metronome)
   - **File:** undo/redo, duplicate project, audio save location (Music,
     Downloads, Documents or any folder you pick), sample packs, blocks &
     add-ons, and preset import (songs are exported from the Master strip)
   - **Audio:** output and input device (USB interfaces, headsets, built-in
     mic), sample rate, bit depth and buffer size (defaults 48 kHz, 24-bit,
     128 frames), which input each audio channel records, recording offset, an
     **audio test** that suggests the best buffer for your phone, and a
     **troubleshooting wizard**
   - **MIDI:** controllers and roles, Bluetooth pairing, and **mapping with
     MIDI learn**
5. **Presets:** every block has **Presets**: factory sounds, your own, and
   import/share. Presets are `.foxpreset` files. Sampler presets
   (Reflections, Leaves, Pickup) include their samples, so a shared preset
   sounds the same on another phone.
6. **Flowers** are effects you design:
   - **Pitch** shift
   - **Resonators:** three tuned combs playing a chord
   - **Modulation:** chorus/flanger, phaser, ring mod, decimator, tremolo,

   - **Delay & Reverb** (with shimmer)
   - **Pan:** position, stereo width, Haas spread, and auto-pan with
     depth, rate and shape (sine, triangle, square, random)
   - **Stutter:** slice repeats with rate, length, chance and fade
   - **Gate:** a 16-step pattern with depth, rate, length, swing and smoothing
   - a **Mod Matrix:** 6 slots

   **Audition** hears the flower on everything. **Plant in garden** puts it
   to work in the clip launcher.
7. **The clip launcher** is where you perform (the button under Flowers on
   the rail):
   - Plain black boxes with white text over detailed, retro-game-style
     grass.
   - MIDI clips, planted flowers and a mossy poetry stone are spread out
     at random over the grass. Each keeps its place until you tap
     **⤨ Shuffle**, which deals everything out to new spots. A small
     coloured square shows which block a clip belongs to.
   - Tap the **poetry stone** for a line of Romantic poetry across the top
     of the screen.
   - **Audio takes** become clip boxes too, drawn as their waveform in
     their track's colour, and lie on the grass with the other clips.
   - **The mixer** (bottom left, kept clear) has a vertical strip for each
     audio track (**Red, Yellow, Blue**): a dB meter, **● Arm**, and the
     input it records (In 1, In 2 or In 1+2). **● Rec** counts in two bars
     and records the armed tracks. Takes are rounded to whole bars, and each
     track holds up to **6 minutes**.
   - **Master** strip: a dB meter, the **master fader** (the level of
     everything FoxBox plays) and **● Tape**. Exporting a song works like
     mixing down to tape: arm the master, press play, and FoxBox records the
     mix; stopping saves the song to your audio save location.
   - **Tap** a clip or take to queue it and select it. A **volume** fader, a
     horizontal **pan** slider and **✎ Rename** appear. **Double-tap** to
     mute.
   - The top row: **▶ Play** starts the queued clips (when the current loop
     comes round), **■ Stop** stops, **● Rec** records audio, and
     **⤨ Shuffle** moves everything to new spots.
   - Latency is compensated automatically; fine-tune it with **Recording
     offset**.
   - **Daisy chains:** planted flowers grow in the grass. Drag a line
     from one through any boxes: they all go through that flower's effects.
     Up to 3 chains, traced in red, yellow and blue (they redraw
     themselves when you shuffle). Double-tap a flower to undo its chain;
     select it for a **bloom** fader.
   - Hold a box until it clicks and drag it to **R.I.P.** to lay it to rest.

## The blocks

Every block has an **amp envelope** and a **filter with its own envelope**.

| Block | What it is | Highlights |
| --- | --- | --- |
| 🌬 **Gusts** | Virtual analog synth | 2 oscillators, sub, noise, LFO, glide, step sequencer |
| 💧 **Reflections** | Sampler | Time stretch, start/end/loop/reverse, de-click ramps, step sequencer |
| 🍃 **Leaves** | Granular synth | Position, spray, scan, grain size, density, drone, step sequencer |
| 🍄 **Minds** | Generative drums | Generative 0–100%, density, swing, mutation, chaos, glitch, crush |
| 🐝 **Bumble** | Mono bass synth in the spirit of the classic analog bass synths | See below |
| 🛻 **Pickup** | Vintage 16-pad sampler (MPC-style) | See below |

**Bumble** (an analog-style mono bass synth):
- two oscillators (sine/triangle/saw/pulse, with PWM and sync)
- sub oscillator (-1/-2 octaves), noise and ring mod
- 12/24 dB low-pass, band-pass, high-pass and an "acid" ladder filter
- overdrive and distortion
- a mod envelope to pitch and pulse width, and two LFOs
- glide, accent, and the step sequencer

**Pickup** (a vintage MPC-style 16-pad sampler):
- **Load to pad** and **● Rec** to pad
- per-pad tune, level, decay, start, pan and reverse
- a vintage sample rate and bit depth (SP-1200/MPC grit), saturation and
  swing
- a 16-step pattern per pad, plus a **Sequencer ON/OFF** switch for the
  whole instrument (off by default)
- its XY pad is a 1990s truck dashboard with the ignition key as the
  cursor

**Low latency:** FoxBox plays through AAudio (MMAP/exclusive where
supported) with the buffer you choose, or "Automatic". The audio thread
allocates no memory, so garbage collection can't cause dropouts.

## Where your files go

Everything is saved as you work. Exports go to the location chosen in
**File** (default `Music/FoxBox/<project>/`):
* `MIDI/`: clips as `.mid`
* `Audio/`: takes, bounces, samples, at your bit depth

Presets are saved under `FoxBox/Presets`.

## Controllers and audio interfaces

* **Class-compliant USB audio interfaces and MIDI controllers** work with a
  USB-C / OTG adapter. Bus-powered interfaces may need a powered hub.
* **Bluetooth MIDI:** MIDI → Pair a Bluetooth MIDI controller.
* **ROLI Seaboard M** is detected as *MPE keys*, the **Synido TempoPad C16**
  as *Pads*.
* **Standard mapping:**
  - CC1 → XY Y, CC74 → XY X
  - CC7 → block volume, CC91 → reverb
  - CC115/116/117 → play/stop/record
  - MIDI Start/Stop messages run the transport

  Use **MIDI → Mapping & MIDI learn** to map anything else, including any
  block parameter.

## Add-ons

`data/Store.kt` has the add-on hooks:
- extra blocks (each block type carries an optional product id)
- app looks
- sample packs

Sample packs can already be imported from a `.zip` of WAVs. To sell add-ons
on Google Play:
1. Add the Play Billing Library.
2. Create in-app products with the catalog ids.
3. Implement `Store.purchase` / `refreshPurchases`.

## Publishing on Google Play

Everything for the store is in `android/play/`: the listing text
(`listing.md`), a privacy policy (`privacy-policy.md`), the 512 px icon, the
feature graphic, the script that draws the icon (`make_art.py`), and a
step-by-step `release-checklist.md` covering the upload key, GitHub secrets,
App Bundle and Play Console. The app targets Android 16 (API 36), is shrunk
with R8, has 16 KB-aligned native code, collects no data, and saves a local
crash log it offers to share on the next launch.

## Getting the app

GitHub builds the app whenever it changes. Open the **Actions** tab, click
the latest **Android app** run, and download **FoxBox-android** (an APK to
install) or **FoxBox-play-bundle** (the `.aab` for Google Play). Unzip
it, copy `FoxBox.apk` to your phone and open it to install.

To build it yourself, open `android/` in Android Studio, or run
`./gradlew assembleRelease` in `android/`.

## Code layout (`android/app/src/main/java/app/foxbox`)

```
MainActivity.kt   screens, dialogs (File, Audio settings/test/wizard, MIDI mapping, presets), files, permissions
Studio.kt         the controller: project, blocks, routing, chords, recording, audio takes, the clip launcher, undo, MIDI map
audio/            DSP blocks, parameters, XY pad state, shared envelopes/filter, the audio engine
                  (AAudio via cpp/foxbox_audio.c, AudioTrack fallback)
blocks/           the block registry (a block HAS an engine; max 6 per session)
engines/          Gusts, Sampler (Reflections + Leaves), Minds, Bumble, Pickup
fx/               Flowers: effects + modulation matrix, the rack and the daisy chains
seq/              Transport, clips + MIDI files, audio takes, the shared step sequencer, music theory
data/             projects, presets, add-ons/packs, WAV files, decoding, audio input, recording
midi/             USB and Bluetooth MIDI input, CC mapping and MIDI learn
ui/               pixel art, close-up grass, clip launcher, XY pad, chords & strum, block pages, studio, projects
```

The pixel fonts (Press Start 2P and Pixelify Sans, SIL Open Font License)
are in `assets/fonts`. The included `foxbox-debug.keystore` signs test
builds so new versions install over old ones. Make your own private key
before publishing to the Play Store.
