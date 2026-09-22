# Brand assets

The marks themselves live with the application: `frontend/public/favicon.svg` is option
`3a`, and `frontend/src/components/Wordmark.tsx` is `2c` (`FZ-196`). This directory is for
assets that are **uploaded somewhere else** and therefore have to be exported by hand.

## `slack-app-icon.svg` — the Slack application's profile picture (`FZ-192`)

Announcements arrive in a channel where everything else is also a bot. Without a picture,
FreezeHub is the one with the default grey square.

**It is `3a` at 512**, not a new mark — every value is the favicon's multiplied by eight. A
Slack avatar does the same job as a favicon, and a product whose avatar differs from its
favicon has two marks rather than one.

### Exporting the PNG

Slack wants a **512×512 PNG** and will not take an SVG.

> **Do not use ImageMagick.** It is the obvious tool and it is silently wrong here: its
> SVG renderer drops `<text>` altogether, so the export is a magenta square on ink with
> **no letterform at all**. It exits `0`. `docs/ui/FZ-192/bad-export-imagemagick.png` is
> the actual output, kept so nobody has to rediscover it.

Use one of these instead:

```bash
# librsvg — renders <text> with the system serif
rsvg-convert -w 512 -h 512 docs/brand/slack-app-icon.svg -o slack-app-icon.png

# Inkscape
inkscape docs/brand/slack-app-icon.svg -w 512 -h 512 -o slack-app-icon.png

# a browser, which is what renders the favicon anyway
# open the SVG, set the window to 512x512, screenshot
```

**Then look at the PNG before uploading it.** That is not a formality. The letterform is
`<text>`, so it depends on a serif being installed on whichever machine runs the export,
and unlike the favicon — which re-renders on every viewer's machine and degrades to Georgia
at the 16px `3a` was chosen to survive — **a PNG cannot degrade, only be wrong**, and it is
wrong permanently once uploaded.

It should look like `docs/ui/FZ-192/after.jpg`: paper `F` on ink, magenta point.

Upload at <https://api.slack.com/apps> → your app → **Basic Information** → **Display
Information** → **App icon**.

### The durable fix, when someone wants it

Outline the glyph to a `<path>` and the font dependency disappears for both this and the
favicon. `FZ-196` already named that as its own job: it needs the Source Serif 4 binary,
which is not in this repository, plus a tool to extract the path.

### If you change it

It is seen at **20 px**, beside a message. Shrink any proposed change to 20 px and look at
it before committing — and change the favicon with it, or the product has two marks.

Colours are the Broadsheet tokens written out, because a file rendered outside the document
cannot read the app's CSS custom properties. Retune the tokens and both files must be
retuned.
