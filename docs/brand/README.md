# Brand assets

The marks themselves live with the application: `frontend/public/favicon.svg` is option
`3a`, and `frontend/src/components/Wordmark.tsx` is `2c` (`FZ-196`). This directory is for
assets that are **uploaded somewhere else**.

**The exports are committed** (`FZ-199`), in `exports/`. `FZ-192` argued against that — an
SVG can be diffed and a PNG cannot — and the objection was right about the risk without
being right about the remedy. What it was protecting against is a raster drifting from its
source unnoticed. Regenerating by hand on whichever machine happens to have a renderer is
not less prone to that; it just moves the drift somewhere nobody can see it, and it made
every consumer of the mark wait on a human step. Committed exports at least make staleness
**visible in a diff**, which is the property the objection actually wanted.

The obligation that comes with it: **if you change the SVG, regenerate `exports/` in the
same commit.** The commands are below, and a changed SVG with an unchanged `exports/` is a
review comment.

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

## `exports/` — the rendered set (`FZ-199`)

| | |
|---|---|
| `png/icon-{16,32,48,64,128,180,192,256,512,1024}.png` | ink plate, paper `F`, magenta point |
| `favicon.ico` | multi-resolution: 16, 32, 48, 64, 128, 256 |
| `icon-512.webp` · `icon-512.jpg` | q95 · q92 |
| `icon-1024.pdf` | a raster inside a PDF wrapper, **not** vector |

`180` is `apple-touch-icon`, `192` the common Android and PWA size, `512` what Slack wants.

**Each size is rendered from the SVG, never downscaled from one large PNG.** `3a` was chosen
to survive at 16 px and downscaling a serif is exactly how that is lost. At 16 px the
middle arm softens and the point becomes a 2×2 blob; that is the documented floor, not a
broken export.

### Regenerating

```bash
for s in 16 32 48 64 128 180 192 256 512 1024; do
  d=$(mktemp -d)
  qlmanage -t -s $s -o "$d" docs/brand/slack-app-icon.svg >/dev/null 2>&1
  cp "$d"/*.png "docs/brand/exports/png/icon-${s}.png"; rm -rf "$d"
done

magick docs/brand/exports/png/icon-{16,32,48,64,128,256}.png docs/brand/exports/favicon.ico
cwebp -q 95 docs/brand/exports/png/icon-512.png -o docs/brand/exports/icon-512.webp
magick docs/brand/exports/png/icon-512.png -background "#201e1d" -flatten -quality 92 \
  docs/brand/exports/icon-512.jpg
magick docs/brand/exports/png/icon-1024.png docs/brand/exports/icon-1024.pdf
```

`qlmanage` is macOS's WebKit renderer — the "a browser, which is what renders the favicon
anyway" option above. **ImageMagick is used only for PNG→something**, never on the SVG, for
the reason in the warning above: it drops `<text>` and exits `0`.

On a machine without `qlmanage`, use `rsvg-convert -w $s -h $s` or Inkscape instead. Then
look at the output, for the same reason as before.

## The transparent variants

`icon-transparent-on-dark.svg` and `icon-transparent-on-light.svg` are the same mark with
the plate removed and the letterform recoloured — a subtraction, not a third mark.

**SVG only, deliberately.** Transparent PNGs were attempted and are not here because
`qlmanage` composites onto opaque white: the corner pixel of its output reads alpha `1`, so
the files were named `transparent` and were not. ImageMagick cannot stand in, for the reason
above. Rasterise these with `rsvg-convert` or Inkscape on a machine that has one — and check
the corner pixel, because this failure is silent:

```bash
magick identify -format '%[pixel:p{5,5}]\n' out.png   # want alpha 0
```

**They have not been through the deck.** `FZ-192` is explicit that the mark is the
operator's choice from the design deck, and these were derived rather than chosen. Treat
them as convenience, not as approved brand.
