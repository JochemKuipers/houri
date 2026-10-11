<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **HDR reading in the WebGPU reader:** HDR-tagged pages (PQ/HLG and UltraHDR) now display with the display's full brightness on HDR-capable panels, and tone-map cleanly to SDR everywhere else
- **Tracker manager:** swipe a tracker row left to log out of it, even when that tracker still shows as logged in — handy when a session has expired or stale credentials are lingering and tapping the row only reopens the login screen

### Improve

- **WebGPU continuous reader:** border cropping now works in the strip too — with crop-borders on, each page's borders are trimmed away and the page fills the screen edge to edge, like the paged reader already did
- **WebGPU reader:** smoother long sessions — GPU objects are released properly as pages scroll by, tiles cache up to what your device's texture limit allows, and an idle reader no longer redraws frames for nothing
- **WebGPU reader:** the webtoon pinch-to-zoom preference is now honored in the WebGPU reader too, and pinch can be turned off outright
- **WebGPU reader:** more stable on device loss and surface resize — a lost GPU device is recovered without crashing, and rotating or resizing rebuilds the surface instead of showing a stale frame

### Fix

- **Library folders:** folder tiles now render as compact rows in list display mode instead of a single oversized card that swallowed most of the screen — the grid layout is unchanged
- **Discord rich presence:** the presence art and download button are Houri's own — it was still showing Komikku's emoji, and its download button sent you to Komikku's page instead of ours. Every screen shares the app icon for now; distinct per-screen art comes back once Houri has its own assets in the Discord developer portal
- **WebGPU page transitions:** turning a page no longer crashes when the GPU refuses to create a texture view — the transition cache falls back to drawing without cached art instead of failing the whole turn
- **ComicK tracker:** rebuilt on Comick's official API — logging in opens your browser (grant "keep me signed in" and library write access), and your reading status, progress, and ratings sync properly again. Existing ComicK links need a one-time re-login and re-link to your Comick account
- **Google Drive sync:** signing in no longer crashes or gets stuck when Google rejects the authorization code — failures now show a clear message in the app, and re-linking keeps your existing refresh token instead of silently breaking sync
- **WebGPU long-strip reader:** very tall pages (long webtoon strips) no longer fail to build their preview levels — zoomed-out reading through them is sharp again instead of sampling the full-resolution image
- **WebGPU long-strip reader:** scrolling down through a page no longer drops an unrelated page in below it, cutting the current page off, until you scroll far enough for it to correct itself
