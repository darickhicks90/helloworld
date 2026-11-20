# ✎ SketchSolve

SketchSolve is a maths helper that runs in your web browser. You write maths
by hand and circle what you want solved. You can also draw a shape, mark what
you know, and circle what you want to find. If there isn't enough information
to work out the answer, it tells you so, and it says which extra value would
be enough.

| Tab | What it does |
| --- | --- |
| ✍️ **Equations** | Write equations by hand. Circle a letter to solve for it, circle a piece such as `3 + 4` to work it out, or circle a whole equation. Several equations on the board are solved together as a system. |
| 📐 **Shapes** | **1** draw a shape · **2** bracket the sides or angles you know and write their numbers · **3** circle what you want (a side, an angle, or the whole shape for its area or volume). |
| 📚 **Formulas** | 112 formulas from geometry, trigonometry, algebra, electrical, physics, chemistry, money and everyday conversions. Fill in what you know and tap ◯ next to what you want. |
| ⚙️ **Teach & help** | Teach it your handwriting, switch between degrees and radians, and read the how-to. |

## How to run it

It's just files. You don't need to install or build anything.

* **Quickest way:** open `mathsketch/index.html` in a browser.
* **Locally with a server:** run `python3 -m http.server` in the `mathsketch`
  folder and open <http://localhost:8000>.
* **Over the internet:** turn on GitHub Pages for this repository, then open
  `/mathsketch/` on your phone or tablet. A stylus works best. When you use a
  pen, touches from your palm are ignored.

Everything runs on your device. Nothing is sent anywhere. Your boards and
handwriting examples are saved in your browser only.

## What it understands

**Handwritten maths:** the digits 0–9, `+ - = ( ) /`, a decimal point, a
raised dot for multiplication, `√`, `π`, and the letters `x y a b c n t`.
It also reads exponents (written small and raised), fractions (a line with
something above and below it), and square roots with a roof over the inside.
You can teach it other letters, such as `V`, `R` or `I`, on the Teach tab.

**Shapes:** triangle, right triangle, rectangle, square, parallelogram,
trapezoid, regular pentagon/hexagon/heptagon/octagon, circle, ellipse, cube,
rectangular box, cylinder, cone, sphere, and square pyramid. If it guesses
wrong, pick the right shape from the list. It then puts a guide shape over
your drawing.

**Brackets:** draw a bracket or a line alongside a side, then write the number
next to it. For an angle, draw a small arc inside the corner from one side to
the other. Writing `?` instead of a number marks that part as what you want.
You can also tap any label on the drawing to type a value.

## How "not enough information" works

The solver doesn't just count unknowns. It finds a solution, then checks
whether the thing you asked for could still change without breaking any of
the equations. If it could, the answer isn't pinned down. In that case it
tries each other unknown in turn to find which single extra value would pin
it down, and lists those for you. It also tells you when:

* two different answers fit, such as a quadratic or the "two possible
  triangles" case;
* your values contradict each other, such as a 3 × 4 rectangle with an area
  of 20;
* no real answer exists, such as triangle sides 1, 2 and 10.

## When it misreads your writing

Every symbol it read appears under **What I read**. Tap one to fix it. You can
pick another reading, say two strokes are separate symbols, or type it
yourself. With **Learn from my corrections** turned on, each fix is saved as
an example of your handwriting.

## Code layout

| File | Job |
| --- | --- |
| `js/mathcore.js` | Parser, rearranging equations, the numeric solver and the "is it pinned down?" check |
| `js/recognizer.js` | Single-symbol handwriting recognition ($P point-cloud matching plus simple rules) |
| `js/layout.js` | Turns a page of strokes into lines of maths: fractions, roots, exponents |
| `js/shapes.js` | Shape recognition, shape models (parts and formulas), and reading brackets and circles |
| `js/formulas.js` | The formula library |
| `js/ink.js` | The drawing board: pen, eraser, undo |
| `js/app.js` | The user interface |

## Tests

```sh
cd mathsketch
node tests/mathcore.test.js     # solver
node tests/recognizer.test.js   # symbol recogniser
node tests/layout.test.js       # reading lines of maths
node tests/shapes.test.js       # shape recognition and brackets
# browser test (needs Playwright):
python3 -m http.server 8765 &   # then:
node tests/e2e.js
```
