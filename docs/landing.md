# Landing page

Public page for Engram, served at https://engram.cam via GitHub Pages (Actions
source, `.github/workflows/pages.yml`; custom domain in `site/CNAME`).

## Motivation

Engram installs by APK, not through an app store, so the page gives friends and
family a plain, trustworthy first impression and one obvious Download button. It
states the honest metadata-survival limit up front instead of burying it.

## What's there (`site/`)

- `index.html`: one self-contained static page (inline CSS, theme-aware light and
  dark, responsive, no external requests). Hero with the Download-APK and GitHub
  buttons, four feature cards (offline, private, English and Russian,
  the-file-is-the-memory), and the honest-limit callout.
- `home.png`: the hero screenshot, from the emulator.
- `favicon.svg`: site icon, a note badge on a photo in the app's mauve.
- `og.svg`: social-share card (og:image and twitter:image).
- `CNAME`, `.nojekyll`: custom domain and the raw-serve marker.

## Updating

These are plain static files, edit them directly. Replace `home.png` to change the
hero shot, edit copy in `index.html`. Pages redeploys on push when `site/**` changes.

## Known limitation

`og:image` is an SVG; some social platforms only render raster cards. Swap in a
1200x630 PNG if rich link previews start to matter.
