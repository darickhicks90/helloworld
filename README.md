# 🦊 FoxBox

FoxBox is a music app for Android, built to be played sideways (landscape).
You **write** loops in five sound engines, then **perform** them in the
Valley, which feels a bit like playing a game.

## How it works

1. **Projects:** FoxBox opens on your projects. Tap **＋ New project** or
   open one. Long-press a project to rename or delete it.
2. **The studio:** pick a page on the left rail: five engines and
   **Flowers**. Each melodic engine page has:
   - an **XY pad** on the left. Tap its **X ▾** or **Y ▾** label to choose
     which parameter each axis controls. The pad takes over while your
     finger is on it, then the knob's own value returns,
   - a **chord selector** in the middle (key, scale, octave, 7th). Tap a
     chord to play it, or hold it and swipe the strum pad to strum,
   - a **strum pad** on the right, laid out by scale degree.
3. **Recording loops:**
   - **● Rec All** records every engine for the loop length, and each
     engine that played gets its own clip.
   - **● Rec ‹Engine›** records one engine. **Clip length** sets its bars.
   - The **Metronome** adds a one-bar count-in and clicks while recording.
     **Quantize** snaps notes to 1/16.
   - Tap a clip to play or stop it. Long-press it to send it to the Valley,
     change its length, rename it, save its MIDI or delete it.
4. **Flowers:** design your own effects. Each flower blends:
   - **Modulation:** chorus or flanger, tremolo, pitch shift
   - **Reverb:** size, damping, mix
   - **Rhythm:** a tempo-synced delay and a 16-step gate
   - a **Mod Matrix:** 4 slots routing LFO 1, LFO 2, Bloom, Bar Ramp or
     Random to any effect parameter

   Play some clips and tap **Audition** to hear the flower. **Plant in
   Valley** puts it in the Valley.
5. **The Valley (scenes):** clips are tiny critters and flowers are buds.
   **Draw lines between whatever you want in the next scene**, or tap single
   critters and flowers to add them on their own. Then **tap the nine-tailed
   kitsune**: when the current scene reaches the end of its loop, everything
   changes over on the beat. A banner counts down the beats. Tap the kitsune
   again to cancel. With nothing drawn, tapping it ends the music at the end
   of the loop. Flowers in the playing scene sparkle: **drag up** to bloom
   them (more effect). They stay bloomed until you drag them back down. Tap a
   line to remove it, and long-press to move things or drop them in the
   basket.

## The engines

Every engine has an **amp envelope** and a **filter with its own envelope**
(Attack, Decay, Sustain, Release for each, plus Cutoff, Resonance, Filter
Env Amount and Key Tracking).

| Engine | What it is | Other controls |
| --- | --- | --- |
| 🌬 **Gusts** | Virtual analog synth | Osc 1/2 Wave, Osc Mix, Detune, Sub Osc, Noise Level/Resonance, LFO Rate, LFO > Cutoff, Glide, Pitch Bend, Vibrato |
| 💧 **Reflections** | Sampler (shared sample engine) | Root Note, Fine Tune, Sample Start/End, Loop, Reverse, **Time Stretch** (Stretch Speed, Stretch Grain), de-click ramps at every edge |
| 🍃 **Leaves** | Granular synth (same sample engine) | Source, Drone, Position, Position Spray, Scan Speed, Grain Size, Density, Pitch Spray, Octave Chance, Stereo Width |
| 🍄 **Minds** | Drums with a generative brain | Sequencer on/off, **Generative 0–100%** (0% plays exactly what you drew), Density, Swing, Mutation, Chaos, Glitch, Bitcrush; per drum Tune, Decay, Tone, Level |
| 🐝 **Bumble** | Mono bass with a honeycomb step sequencer | Saw > Square, Sub Osc, Drive, Glide, Accent; Steps, Rate, Gate Length, Octave. Steps are scale degrees that follow the chord you tap |

**Reflections** and **Leaves** can both **Load** an audio file from your
phone or record from the **Mic**.

**Low latency:** FoxBox plays through AAudio, Android's fastest audio path
(MMAP/exclusive mode on phones that support it), at the phone's native
sample rate. The buffer starts at two bursts, grows only if the phone drops
audio, and shrinks again after 20 quiet seconds. The audio thread allocates
no memory, so garbage collection can't cause dropouts. If AAudio isn't
available, FoxBox falls back to AudioTrack.

## Where your files go

Everything is saved on your device as you work.

* MIDI clips: `Music/FoxBox/<project>/MIDI/*.mid`
* Audio: `Music/FoxBox/<project>/Audio/`. This includes **● Audio**
  recordings of everything you play, mic recordings and loaded samples.

## Controllers

* **USB:** plug in with a USB-C / OTG adapter.
* **Bluetooth:** tap **MIDI → Pair a Bluetooth MIDI controller**.
* **ROLI Seaboard M:** detected as *MPE keys*. Each note gets its own glide,
  press and slide.
* **Synido TempoPad C16:** detected as *Pads*. On Minds the pads play the
  drums. Elsewhere pads 1–7 play the chords and the rest play scale notes.
* **CC1** and **CC74** move whatever the current XY pad's Y and X axes control.

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
MainActivity.kt   screens, file picker, mic, permissions, dialogs, back handling, crash report
Studio.kt         the controller: project, routing, chords, recording, clips, Valley, MIDI input
audio/            DSP blocks, parameters, XY pad state, shared envelopes/filter, the audio engine
                  (AAudio via cpp/foxbox_audio.c, AudioTrack fallback)
engines/          Gusts, Sampler (Reflections + Leaves), Minds, Bumble
fx/               Flowers: user-designed effects + modulation matrix, and the rack that runs them
seq/              Transport (clock, recording, count-in, metronome), clips + MIDI files, music theory
data/             projects, WAV files, decoding audio files, audio + mic recording
midi/             USB and Bluetooth MIDI input
ui/               pixel-art toolkit, XY pad, chords & strum pad, Minds/Bumble/Flowers pages, studio, Valley, projects
```

The pixel fonts (Press Start 2P and Pixelify Sans, SIL Open Font License)
are in `assets/fonts`. The included `foxbox-debug.keystore` signs test
builds so new versions install over old ones. Make your own private key
before publishing to the Play Store.
