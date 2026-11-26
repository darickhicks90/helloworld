# Gemma Desk for Android

An everyday helper and a web page designer, both powered by Gemma running
**on the phone**. No account, no server: your ledger, calendar and designs
never leave the device. Internet is only used once, to download the model.

## Everyday tab

* **Ask Gemma** in plain words: “Paid $45 for gas yesterday”, “Got my
  paycheck, $1,850”, “Dentist next Tuesday at 3pm”, “How much did I spend
  on groceries this month?”, “What's on my calendar next week?”.
* Gemma turns the message into a ledger entry, an appointment or a question,
  and the app **checks it before anything is saved**:
  * every amount must appear in what you wrote, or the card warns you,
  * dates and times are read again by ordinary code (“next Tuesday”, “3pm”,
    “Dec 3rd”), and the code wins if they disagree,
  * totals are added up by the app from the ledger, never by the AI,
  * the card shows exactly what will be saved; nothing is saved until you
    tap **Save** (or **Edit** first).
* **Ledger:** balance, this month in and out, spending by category, entries
  by month, editing, accounts, and a CSV export. Amounts are kept in cents,
  so totals are exact.
* **Calendar:** a month view, appointments with place and notes, overlap
  warnings, reminders as notifications (they survive a restart), and “Add
  to my phone's calendar”.
* Voice: use the microphone on the phone's keyboard.

## Make tab (websites and app screens)

* Start a project (phone app, website or tablet). Describe a page and
  **Gemma lays it out**: the target design.
* The **design screen** shows every item on the page at its real position
  and size. Tap to select, **drag to move**, **drag a corner or edge dot to
  resize**. Items snap to a 4 px grid and to each other's edges and centres,
  with pink guide lines. Pinch to zoom; drag empty space to scroll.
* Edit words (double-tap), size, bold, alignment, colours, corners, links,
  and exact X/Y/W/H. Duplicate, delete, bring to front or send to back
  (long-press). Undo and Redo.
* Ask Gemma for changes (“make the button green and put it under the
  photo”); it returns the whole page, keeping unchanged items as they were.
  One Undo takes the change back.
* Several pages per project; buttons and links can go to another page.
* **Preview** shows the real HTML in the phone's web engine, with a “HTML”
  view of the code. **Export** shares a page (.html), the whole site (.zip
  with index.html), or copies the HTML.

### How the design becomes HTML

Gemma never writes HTML. It returns a list of items (type, x, y, w, h, words,
colours), the same list the design screen edits. The app tidies that list
(known item types only, valid colours, everything inside the frame, text
boxes tall enough for their words) and generates the HTML itself:

* each item is absolutely positioned at its exact pixel position and size
  inside a page as wide as the frame, so the browser shows the layout you made,
* on a narrower screen a tiny script scales the whole page down to fit,
  so the layout never breaks,
* words are escaped and only plain web, page or e-mail links are allowed,
  so nothing in a design can run code.

The trade-off: the page is a faithful fixed layout, not a "responsive" one
that rearranges itself into a different layout on different screens. Make a
Phone page and a Desktop page if you want both.

## The model

On first launch the app suggests a model for the phone: Gemma 4 E4B
(3.7 GB) for phones with about 12 GB of memory, like the Galaxy S24 Ultra,
otherwise Gemma 4 E2B (2.6 GB). It downloads the model once, checks the
SHA-256, and can **import** a file you downloaded yourself, for example the
copy you downloaded for SketchSolve if it's still in Downloads. (Android
doesn't let one app read another app's private files, so it can't use
SketchSolve's installed copy directly.)

## Building and testing

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # logic, design screen and screenshot tests
```

Screenshots go to `app/build/screens`. To try the real model on a computer:

```
./gradlew testDebugUnitTest --tests '*GemmaPromptsTest'    # writes app/build/eval/prompts.jsonl
python tools/run_gemma.py gemma-4-E2B-it.litertlm app/build/eval/prompts.jsonl outputs.jsonl
GEMMA_OUTPUTS=outputs.jsonl ./gradlew testDebugUnitTest --tests '*GemmaRepliesTest'
```

That writes `app/build/eval/gemma-report.txt`, plus a picture and the HTML
of every design Gemma made.
