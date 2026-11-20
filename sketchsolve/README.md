# ✎ SketchSolve for Android

A maths helper that looks like a pad of graph paper on a wooden desk. It
runs in landscape, works offline, and suits a stylus. Write or draw on the
paper, then circle what you want to know. Answers appear on the yellow legal
pad to the right. When there isn't enough information, it says so and tells
you which extra value would be enough.

| Sticky-note tab | What it does |
| --- | --- |
| **Equations** | Write equations by hand. Circle a letter to solve for it, a piece like `3 + 4` to work it out, or a whole equation. Several lines are solved together as a system. |
| **Shapes** | **1** draw a 2D or 3D shape · **2** bracket the sides or angles you know and write their numbers · **3** circle a side, an angle, or the whole shape (for area, perimeter, volume or surface area). |
| **Circuits** | **1** draw a schematic · **2** write each value next to its part · **3** circle a part to get its current, voltage and power, or circle the whole circuit for its totals. |
| **Formulas** | 112 formulas from geometry, trigonometry, algebra, electrical, physics, chemistry, money and conversions. Fill in what you know and tap ◯ next to what you want. |
| **Teach & help** | Teach it your handwriting, choose degrees or radians, and read the how-to. |

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

## Not enough information?

The solver doesn't just count unknowns. It finds a solution, then checks
whether what you asked for could still change without breaking any of the
equations. If it could, it says so, and it lists each single extra value that
would pin the answer down. It also tells you when two answers fit (such as
the "two possible triangles" case), when your values contradict each other,
and when no answer exists.

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
| `ui` | Desk, paper, legal pad, and one class per tab |
