# Brand assets

## `slack-app-icon.svg` — the Slack application's profile picture (`FZ-192`)

Announcements arrive in a channel where everything else is also a bot. Without a picture,
FreezeHub is the one with the default grey square.

### Exporting the PNG

**Slack wants a 512×512 PNG, and it will not take an SVG.** That conversion is a human
step; this repository holds the source, because an SVG can be edited and diffed and a PNG
cannot.

```bash
# any one of these
rsvg-convert -w 512 -h 512 docs/brand/slack-app-icon.svg -o slack-app-icon.png
inkscape docs/brand/slack-app-icon.svg -w 512 -h 512 -o slack-app-icon.png
magick -background none docs/brand/slack-app-icon.svg -resize 512x512 slack-app-icon.png
```

Then: <https://api.slack.com/apps> → your app → **Basic Information** → **Display
Information** → **App icon**.

### If you change it

It is seen at **20 px**, beside a message. That is the constraint, not 512. Shrink any
proposed change to 20 px and look at it before committing — detail that survives a favicon
is the brief, and anything finer is invisible where it actually appears.

The colour is `#0088b0`, `--color-accent` from `frontend/src/index.css`. Note that
`frontend/public/favicon.svg` is **not** brand-consistent: it is a purple `#863bff` shape
left over from a template and matches nothing else in the product. Do not copy it for a new
asset; it is the thing that should eventually be replaced.
