# 🦊 FoxBox

FoxBox is a music app that runs in your web browser. It has five sound
engines, each with its own look, and you can play it with a MIDI controller
or with just your screen and computer keyboard.

| Tab | What it is | Theme |
| --- | --- | --- |
| 🌬 **Gusts** | Virtual analog synth: two oscillators, sub, whistling breath noise, resonant filter, envelopes, flutter LFO, glide | Wind blowing across a night sky |
| 💧 **Reflections** | Sampler: load or record sounds, then play them on the keys or chop them into 16 slices | A waveform floating on a lake, with ripples |
| 🍃 **Leaves** | Granular synth: turns any sound into clouds of tiny grains, with a self-playing "Evergreen" drone | Leaves sprouting from a branch and falling |
| 🧠 **Minds** | Glitchy generative drums: 8 synthesized voices, seeded patterns that mutate, chaos, stutters, bit-crush | Neurons firing |
| 🦷 **Teeth** | Rhythmic audio mangler: pitch, delay and modulation mixed on faders, controlled with an XY touchpad and cut up by a 16-step "bite" pattern | A jaw full of teeth |
| 🦊 **Den** | Mixer, MIDI device setup and help | |

## The look

FoxBox is drawn in a 16-bit, cottagecore pixel-art style using four colours:
orange, aqua, off-white and brown. It has wooden panels, gingham
tablecloths, pixel flowers and mushrooms, and a little pixel fox. The pixel
fonts (Press Start 2P and Pixelify Sans, both under the SIL Open Font
License) are included in the `fonts/` folder, so FoxBox works offline.

## How to run it

FoxBox is just files. You don't need to install or build anything.

* **Quickest way:** double-click `index.html` to open it in **Chrome** or **Edge**.
* **Over the internet:** turn on GitHub Pages for this repository
  (Settings → Pages → deploy from your branch). Then FoxBox works on phones
  and tablets too.
* **Locally with a server (optional):** run `python3 -m http.server` in this
  folder and open <http://localhost:8000>.

Then press **Wake the fox**. Browsers only allow sound to start after you
click something.

## Android app

FoxBox also comes as an Android app (an APK file). It has everything the
web version has, plus:

* **USB MIDI:** plug the TempoPad C16 (or any USB controller) into your
  phone or tablet with a USB-C / OTG adapter. It connects by itself.
* **Bluetooth MIDI:** tap **Bluetooth**, allow the permission, and pick your
  Seaboard M (or other controller) from the list.
* Microphone recording, loading sounds from your phone, and **● Rec**
  (recordings are saved in `Downloads/FoxBox`).
* It works offline and keeps the screen awake while you play.

**Getting the APK:** GitHub builds it automatically whenever the app
changes. Open this repository's **Actions** tab, click the latest
**Android app** run, and download **FoxBox-android** under *Artifacts*.
Unzip it to get `FoxBox.apk`, copy that to your phone, and open it. Android
will ask you to allow installing apps from that source the first time.

**Building it yourself:** open the `android/` folder in Android Studio and
press Run, or run `./gradlew assembleRelease` inside `android/`. The build
copies `index.html`, `css/`, `js/` and `fonts/` into the app, so change the
web app and the Android app picks up the change. The included
`foxbox-debug.keystore` signs test builds so new versions install over old
ones. Make your own private key before putting FoxBox on the Play Store.

## Controllers

* **USB MIDI:** plug it in and it shows up automatically. Check the 🎹 MIDI
  button or the Den tab.
* **Bluetooth MIDI:** press 🔵 **Bluetooth** to pair directly. This needs
  Web Bluetooth, which Chrome and Edge have on desktop and Android. You can
  also pair the controller in your operating system's Bluetooth/MIDI settings
  (macOS Audio MIDI Setup, Windows, Android), and it will then show up like a
  USB device.
* **ROLI Seaboard M:** recognised automatically as **MPE keys**. Each finger
  gets its own pitch glide, pressure and slide (timbre), and all the engines
  respond to them.
* **Synido TempoPad C16:** recognised automatically as **Pads**. The 16 pads
  play the on-screen pads, which do different things on each tab: notes,
  sample slices, drum voices, or Teeth moves. If the pads are out of order,
  go to the Den tab and press *learn*, then hit pad 1.
* **MIDI learn:** right-click (or long-press on a touchscreen) any knob or
  fader, then turn a knob on your controller to link them.
* The sustain pedal (CC64) and mod wheel (CC1) also work.

## No controller? No problem

* **On-screen keyboard:** works like a Seaboard. Drag sideways to bend the
  pitch and up/down to change the tone. Turn **Expressive** off to slide
  across the keys normally instead.
* **4×4 pads:** follow the key and scale chosen in the header.
* **Computer keyboard:** `A W S E D F T G Y H U J K` play notes, `Z`/`X`
  change octave, `1`–`=` play pads, `Space` starts and stops, `Esc` stops
  all sound.
* **Hold** keeps notes ringing after you let go, and the **Arp** (arpeggiator)
  plays them in patterns.
* The **Minds** drums play themselves, and Leaves' **Evergreen** drone plays
  with no input at all.
* **● Rec** records what you play to an audio file. On Reflections,
  **Resample FoxBox** records what FoxBox is playing and turns it into a new
  sample.

Your settings and MIDI mappings are saved in the browser automatically.
Loaded samples are not saved.

## How the code is organised

```
index.html            page layout
css/style.css         all the styling
js/util.js            helpers, the Engine base class, knobs and other controls
js/audio.js           audio setup, mixer, reverb, custom DSP, recording
js/clock.js           tempo clock (16th-note steps)
js/midi.js            USB + Bluetooth MIDI, MPE, MIDI learn
js/input.js           routes notes to the engines, on-screen keyboard and pads, arp
js/engines/*.js       Gusts, Reflections, Leaves, Minds, Teeth
js/app.js             tabs, header, Den, saving, start-up
fonts/                bundled pixel fonts (+ their licences)
android/              Android app: a WebView around the web app, plus
                      native USB/Bluetooth MIDI (app/src/main/java/app/foxbox)
```

Each engine is a class with `noteOn`, `noteOff`, `expr` (bend, pressure,
slide), `padDown`, `buildUI` and `draw` methods. To learn how one works, open
its file and read from the top.
