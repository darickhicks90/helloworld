# Gemma Desk for Android

An everyday helper, a web page designer and a reference library, all powered by Gemma running
**on the phone**. No account, no server: your ledger, calendar, designs and
documents never leave the device. Internet is only used once, to download the model.

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

## Make tab (websites and app screens): mockup, adjust, build

1. **Mockup.** Describe a page and Gemma draws a mockup: a list of items
   (type, position, size, words, colours). The app tidies it: items inside
   the screen, nothing stacked on top of anything else, no big empty gaps,
   and text always readable against what's behind it.
2. **Adjust.** The design screen shows every item at its real position and
   size. Tap to select, **drag to move**, **drag a corner or edge dot to
   resize**; items snap to a 4 px grid and to each other (pink guide lines).
   Pinch to zoom. Edit words (double-tap), size, bold, alignment, colours,
   corners, links and exact X/Y/W/H; long-press for duplicate, delete, front
   and back. Undo and Redo. Or ask Gemma for a change to the mockup.
3. **Build.** Tap **✓ Build** and Gemma writes the real page from the
   approved mockup: responsive HTML and CSS with proper headings, sections and
   form fields. The app checks the code before you accept it:
   * every piece of text from the mockup must be there (short labels such as
     “Cakes” must be their own element, not just a word in another sentence),
   * every link, and the mockup's colours,
   * the page must be complete (not cut off),
   * scripts, event handlers, embedded frames and anything loaded from the
     internet are removed.

   You see **Built page**, **Mockup** and **Code** side by side, then choose
   **Accept**, **Try again**, **Ask for changes** (pre-filled with anything
   the check found missing), or **Use the exact mockup**, which uses the
   app's own pixel-exact page. Changing the mockup marks the build out of
   date.

**Share ▾** exports the page (.html), the whole site (.zip with index.html)
or the HTML, and can save the mockup file (.json).

### Working with Claude

**Share ▾ → Send the mockup to Claude** shares two files to the Claude app
(or anywhere): a picture of the mockup, and a brief with your request, a plain
outline and the exact layout as JSON. Claude can build from it directly.
Claude can also send a design back in the same format, which **Make → Import**
opens as a project you can adjust and build. The format is described in
[docs/mockup-format.md](docs/mockup-format.md).

## Library tab

* Add manuals, code books, data sheets and notes: PDF, Word (.docx), EPUB,
  HTML, Markdown or text. Use **+ Add documents**, or share a file to Gemma
  Desk from Chrome, Files or Gmail.
* The text is extracted once and split into passages, all on the phone.
* **Ask** in plain words. The app finds the best passages (keyword ranking
  that handles section numbers like 210.8(A)). Gemma answers **only from
  those passages**, and must quote the words it used. The app checks every
  quote really is in its passage and shows the sources with document and
  page. Tap a source to read the whole passage with the quote highlighted.
* If the passages don't answer the question, Gemma suggests better search
  words and the app searches once more. If it still can't find an answer, it
  says your library doesn't cover it rather than guessing.
* **Just search** finds passages instantly without Gemma.
* Limits: copy-protected (DRM) PDFs, such as many publishers' code book
  apps, can't be read by other apps. Scanned pages with no text can't be
  read yet.

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
