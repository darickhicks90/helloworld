# ✎ SketchSolve for Android

A maths helper that looks like a pad of graph paper on a wooden desk. It
runs in landscape, works offline, and suits a stylus. Write or draw on the
paper, then circle what you want to know. Answers appear on the yellow legal
pad to the right. When there isn't enough information, it says so and tells
you which extra value would be enough.

| Sticky-note tab | What it does |
| --- | --- |
| **Ask** | Hold the microphone and say a question, or type it. Plain sums and equations are solved straight away. Word problems are read by Gemma 4 running on the device, checked, and written out on the page with a diagram, the equations, the working and a boxed answer. |
| **Equations** | Write equations by hand. Circle a letter to solve for it, a piece like `3 + 4` to work it out, or a whole equation. Several lines are solved together as a system. |
| **Shapes** | **1** draw a 2D or 3D shape · **2** bracket the sides or angles you know and write their numbers · **3** circle a side, an angle, or the whole shape (for area, perimeter, volume or surface area). |
| **Circuits** | **1** draw a schematic · **2** write each value next to its part · **3** circle a part to get its current, voltage and power, or circle the whole circuit for its totals. |
| **Formulas** | 112 formulas from geometry, trigonometry, algebra, electrical, physics, chemistry, money and conversions. Fill in what you know and tap ◯ next to what you want. |
| **Teach & help** | Teach it your handwriting, choose degrees or radians, and read the how-to. |

## Tutorial

* A guided tour opens on first launch. It has one page per feature, each
  with a drawn illustration and short steps, and ends by leading into the
  model setup.
* Most pages have a **Try it** button that opens the tab with an example
  ready to finish:
  * a word problem to solve
  * "2x + 3 = 11" to circle
  * a triangle to label
  * a circuit to add values to
* The Circuits page includes a symbol cheat sheet. The Formulas page has an
  example formula pack you can copy.
* Every tab's tool tray has a **?** token that opens that tab's page.
  Teach & help has **Take the guided tour again**.

## What it understands

* **Handwriting:** digits, `+ - = ( ) /`, a decimal point, a raised dot for
  multiplication, `√`, `π`, and `x y a b c n t`. It reads exponents (small
  and raised), fractions (a bar with something above and below) and square
  roots. You can teach it more symbols on the Teach tab, and every correction
  you make can be kept as an example of your writing.
* **Shapes:** triangle, right triangle, rectangle, square, parallelogram,
  trapezoid, regular 5–8 sided polygons, circle, ellipse, cube, box,
  cylinder, cone, sphere and square pyramid. If it guesses wrong, pick the
  right shape from the list.
* **Schematics:** wires (including T-junctions), resistors (zigzag or box),
  lamps (a circle with an X), cells and batteries (long and short plates; the
  long plate is +), capacitors (two equal plates) and voltage sources (a
  circle). It labels them R1, R2, B1… and works out series, parallel and
  mixed circuits with node analysis. It treats capacitors as fully charged
  (steady DC). With no battery, leave two wire ends open and it gives the
  resistance (or capacitance) between them. Values are in ohms, volts and µF.

## Word problems and voice (Ask tab)

* **Gemma 4 E2B** (Apache 2.0) runs on the phone through Google's LiteRT-LM.
  It only *sets up* the problem, as JSON that is forced to follow a schema.
  It never does the maths.
* SketchSolve then checks the setup:
  * every number used must appear in the question
  * every number in the question should be used
  * units must match (no adding hours to miles; minutes and hours need converting)
  * the asked-for quantity must be unknown
  * no equation may be true for every value
* It solves with its own solver, and reads the problem a second (and third)
  time to make sure independent readings agree.
* The answer is stamped **CHECKED**, or **PLEASE CHECK** with the reasons.
* **whisper.cpp** (MIT) does push-to-talk speech recognition on the device.
  It's built from source in `app/src/main/cpp`.
* Spoken maths such as "x squared plus three equals twelve" or "fifteen
  percent of eighty" is turned into maths text directly.
* Models download once on first use (about 2.7 GB: Gemma 2.6 GB plus Whisper
  60–190 MB). The download is resumable and checksum-verified, with an
  optional Wi-Fi-only setting. After that the app is fully offline.

### If the download is blocked

* Before downloading, the app checks that the phone can look up the download
  site. If it can't (a VPN, ad blocker, Private DNS or filtered network), it
  says so and opens **Download the models yourself**.
* That page lists every model file with Copy link and Open in browser
  buttons, and explains how to move the files from a computer.
* Then **Import…** on the Ask tab installs a file from the phone's storage.
  The app works out which model it is, checks the SHA-256 while copying, and
  refuses wrong or damaged files.

### Formula library folder

Pick any folder on the device. Every `.txt` file in it is read as a formula
pack. The formulas appear in the Formulas tab, and the most relevant ones are
shown to Gemma when it reads a word problem. Here's the format:

```
# Optics
formula: Thin lens equation
equation: 1/f = 1/d_o + 1/d_i
f: focal length (cm)
d_o: distance from the object to the lens (cm)
d_i: distance from the lens to the image (cm)
keywords: lens, focal, magnifying
note: A negative d_i means the image is on the same side as the object.
example: A magnifying glass has a focal length of 10 cm.
---
formula: Magnification
equation: m = -d_i/d_o
m: magnification
```

### Measuring accuracy

`app/src/test/resources/word_eval.json` holds 50 word problems with known
answers, including some that genuinely lack information. To measure Gemma
on them:

1. `./gradlew testDebugUnitTest --tests '*WordEval.exportPrompts*'` writes the prompts.
2. `python tools/run_eval.py MODEL.litertlm app/build/eval/prompts.jsonl app/build/eval/outputs.jsonl`
   (needs `pip install litert-lm-api`) runs Gemma on them.
3. `--tests '*WordEval.score*'` writes `app/build/eval/report.txt`.

## Not enough information?

The solver doesn't just count unknowns. It finds a solution, then checks
whether what you asked for could still change without breaking any of the
equations. If it could, it says so, and it lists each single extra value that
would pin the answer down. It also tells you when two answers fit (such as
the "two possible triangles" case), when your values contradict each other,
and when no answer exists.

## Publishing on Google Play (model delivery)

When the app is installed from Google Play, the models come from Google Play
too, using Play for On-device AI packs, not from Hugging Face. That gets past
most network filters, and you don't pay for or run a download server.

* Play allows at most 1.5 GB per pack, so each Gemma model is split into
  parts of 1.4 GB, one pack each. The app downloads the parts, joins them
  into one file, checks its SHA-256, and removes each pack after copying it.
* Device targeting (`app/device_targeting_config.xml`): phones with about
  12 GB of memory or more (group `large`) get Gemma E4B; the rest
  (`standard`) get Gemma E2B and Whisper base. Everyone gets Whisper small.
  Compressed downloads: about 3.3 GB on a `large` phone and 2.4 GB on a
  `standard` one, under Play's 4 GB per device.
* A model Play doesn't carry for a phone (for example E2B on a 12 GB phone),
  and every model in a copy not installed from Play, is downloaded from the
  internet or imported as before.
* Packs are on demand. The app starts them when the user taps Download, and
  Google Play asks before using mobile data for big files.

Building the bundle (about 5.6 GB):

```
./gradlew preparePlayPacks -Pmodels=/folder/with/the/four/model/files
./gradlew bundleRelease      # app/build/outputs/bundle/release/app-release.aab
```

`preparePlayPacks` checks each file's SHA-256 and writes the parts to
`playpacks/*/src/main/assets` (not committed). Sign the bundle with your
upload key: put `storeFile`, `storePassword`, `keyAlias` and `keyPassword`
in `keystore.properties` (not committed). Without it the bundle is signed
with the debug key, which Google Play rejects.

To check Play delivery on a real phone, upload the bundle to an internal
test track in Play Console and install from the link. Copies installed
any other way (including the debug APK) fall back to internet downloads.

## Install

Download the APK from the session, or build it yourself:

```sh
cd sketchsolve
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

You need JDK 17+ and the Android SDK (platform 35). Android Studio can open
the `sketchsolve` folder directly. The app runs on Android 8.0 and newer, and
works best on a tablet with a stylus. With a stylus, touches from your palm
are ignored, and the stylus's eraser end erases.

## Tests

```sh
./gradlew testDebugUnitTest
```

The tests cover the solver, symbol recognition, reading lines of maths,
shape recognition with brackets, and circuits (series, parallel, a lamp with
a capacitor, and resistance between two open ends). The tests "draw" with
computer-generated strokes, not real handwriting.

## Code layout

| Package | Job |
| --- | --- |
| `math` | Parser, rearranging equations, numeric solver, the "is it pinned down?" check |
| `recog` | Symbol recogniser ($P point clouds plus rules) and the layout reader |
| `shapes` | Shape recognition, shape models, reading brackets, numbers and circles |
| `circuit` | Schematic recognition, connectivity, node equations |
| `data` | The formula library |
| `word` | Spoken maths, units, word-problem setup checks, prompt, formula packs |
| `llm` | Gemma (LiteRT-LM), Whisper speech, model downloads |
| `ui` | Desk, paper, legal pad, written-out working, and one class per tab |
