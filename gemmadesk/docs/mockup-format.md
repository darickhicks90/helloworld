# Gemma Desk mockup format (version 1)

Gemma Desk shares a mockup as a **picture** (`*-mockup.png`) plus a **brief**
(`*-brief.md`). The brief has the request, a plain outline, and the exact
layout as JSON. Claude (or any developer) can build from it directly, and can
send a design back in the same JSON: Gemma Desk opens it with
**Make → Import**.

```json
{
  "format": "gemma-desk-mockup",
  "version": 1,
  "project": "Rosa's Bakery",
  "page": {
    "name": "Home",
    "frame": { "name": "Phone", "w": 390, "h": 844 },
    "background": "#FFF8F0",
    "items": [
      { "type": "box", "x": 0, "y": 0, "w": 390, "h": 64, "background": "#5D4037" },
      { "type": "heading", "x": 24, "y": 14, "w": 240, "h": 36, "text": "Rosa's Bakery",
        "fontSize": 24, "bold": true, "color": "#FFF8F0" },
      { "type": "button", "x": 24, "y": 632, "w": 342, "h": 52, "text": "Order for pickup",
        "fontSize": 17, "bold": true, "color": "#FFFFFF", "background": "#C62828", "radius": 12,
        "link": "order.html" }
    ]
  }
}
```

* `x`, `y`, `w`, `h` are CSS pixels from the page's top-left; the page is
  `frame.w` wide and grows downward to fit its lowest item.
* `type`: `heading`, `text` (paragraph; `\n` for line breaks), `button`,
  `image` (placeholder; `text` describes the picture), `box` (a panel drawn
  behind later items), `input` (`text` is the placeholder), `link`, `divider`.
* Optional per item: `fontSize` (px), `bold`, `color`, `background`
  (hex; empty = none), `radius` (px), `align` (`left`/`center`/`right`),
  `link` (a URL, `#`, `mailto:` or another page's file name like `about.html`),
  `id`.
* Items are drawn in order: later items sit on top of earlier ones.
* Import also accepts a whole project (`{"name": …, "pages": [ … ]}`) or a
  bare page (`{"items": [ … ]}`), on its own or inside a ```json block.
