# 🦊 FoxBox

FoxBox is a music app for Android, built to be played sideways (landscape).
You **write** loops in five sound engines, then **perform** them in the
Valley, which feels a bit like playing a game.

## How it works

1. **Projects:** FoxBox opens on your projects. Tap **＋ New project** or
   open an existing one. Long-press a project to rename or delete it.
2. **The studio:** the engines are on the left rail. Each engine page has:
   - an **XY pad** on the left, with a moving scene that plays the role of
     MPE-style expression (on Teeth it drives the effects instead),
   - a **chord selector** in the middle, built from the key, scale, octave
     and 7th settings at the top. Tap a chord to play it, or hold it and
     swipe the strum pad to strum it,
   - a **strum pad** on the right, laid out by scale degree. It isn't a
     piano: there are no wrong notes.
3. **Recording loops:**
   - **● Rec All** (top bar) records every engine for the loop length. You
     can switch tabs while it records. When it stops, each engine that
     played anything gets its own clip.
   - **● Rec ‹Engine›** (bottom bar) records just that engine. **Clip
     length** sets how many bars the next clip will be.
   - With the **Metronome** on, recording starts after a one-bar count-in
     and clicks while it records. **Quantize** snaps notes to 1/16.
   - New clips start looping straight away, so you can stack engines. Tap
     a clip to play or stop it. Long-press it to send it to the Valley,
     change its length, rename it, save its MIDI file or delete it.
4. **The Valley:** each clip you send becomes a little critter. Drag from
   the **fox** to a critter (or from critter to critter) to link them, then
   tap the fox and everything linked starts together on the next bar. Tap a
   critter to play it on its own. Tap a line to remove it. Long-press a
   critter to move it, or drop it in the basket to take it out of the
   Valley.

## The engines

| Engine | What it is | Sound-design controls |
| --- | --- | --- |
| 🌬 **Gusts** | Virtual analog synth | Osc 1/2 Wave, Osc Mix, Detune, Sub Osc, Noise Level/Resonance, Cutoff, Resonance, Env Amount, Filter Decay, Key Tracking, ADSR, LFO Rate, Vibrato Depth, LFO > Cutoff, Glide |
| 💧 **Reflections** | Sampler with a de-click ramp at every start, end and loop seam | Root Note, Fine Tune, Sample Start/End, Loop, Reverse, Cutoff, Resonance, ADSR, Vibrato |
| 🍃 **Leaves** | Granular synth with a Drone mode | Source, Position, Position Spray, Scan Speed, Grain Size, Density, Pitch Spray, Octave Chance, Stereo Width, Cutoff, Attack, Release |
| 🍄 **Minds** | Generative + hand-drawn drums with an on/off sequencer | Density, Swing, Mutation, Chaos, Glitch, Bitcrush; per drum: Tune, Decay, Tone, Level |
| 🦷 **Teeth** | Rhythmic master-bus mangler | Pitch Shift, Grain Window, Delay Time, Feedback, Delay Tone, Pitch > Delay, Flanger, Ring Mod, a 16-step bite gate, and XY axes you can assign |

Open an engine's **Sound** drawer for all its controls. Drag a knob up or
down to change it, and double-tap it to reset. On Minds, tap the grid to
sequence by hand and tap a drum's name to hear it and edit its sound.
**Generate** grows a new pattern.

**Reflections** and **Leaves** can **Load** any audio file from your phone.
Reflections can also record from the **Mic**.

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
* **CC1** moves the XY pad's Y axis and **CC74** moves its X axis.

## Getting the app

GitHub builds the APK whenever the app changes. Open the **Actions** tab,
click the latest **Android app** run, and download **FoxBox-android**. Unzip
it, copy `FoxBox.apk` to your phone and open it to install.

To build it yourself, open `android/` in Android Studio, or run
`./gradlew assembleRelease` in `android/`.

## Code layout (`android/app/src/main/java/app/foxbox`)

```
MainActivity.kt   screens, file picker, mic, permissions, dialogs
Studio.kt         the controller: project, routing, chords, recording, clips, Valley, MIDI input
audio/            DSP blocks, parameters, engine base class, the audio engine (AudioTrack)
engines/          Gusts, Reflections, Leaves, Minds, Teeth
seq/              Transport (clock, recording, count-in, metronome), clips + MIDI files, music theory
data/             projects, WAV files, decoding audio files, audio + mic recording
midi/             USB and Bluetooth MIDI input
ui/               pixel-art toolkit, XY pad, chords & strum pad, Minds grid, studio, Valley, projects
```

The pixel fonts (Press Start 2P and Pixelify Sans, SIL Open Font License)
are in `assets/fonts`. The included `foxbox-debug.keystore` signs test
builds so new versions install over old ones. Make your own private key
before publishing to the Play Store.
