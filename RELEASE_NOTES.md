<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Built-in Komga source**: browse, search and read straight from a Komga server without installing an extension. EPUB books open in the book reader (downloaded to the app cache once, then read offline), comics read as image pages, and reading progress syncs through the existing Komga tracker. Configure the server address (and optional API key) in the source's settings.
- **Page-turn animations for books** (paged reading): the old page now slides off the edge you are heading for, dissolves, or swings over on its spine — with an adjustable speed. Off by default; pick one in Reading appearance → Page turn.
- **Footnote popups in books**: tapping a note reference now shows the note beside the text — a small window wearing the book's own typography, images included — instead of turning to the notes document. Notes that live in another file are read out of the book on the spot; anything that cannot be shown falls back to the ordinary link behaviour. Toggleable in Reading appearance.
- **Auto-scroll for books** (continuous reading): the page scrolls itself at a pace you set — dp per second, from a slow drift to a brisk walk — pausing the moment you touch the screen, and running off the end of a chapter exactly as a finger drag would. In paged mode the same toggle becomes Moon+'s timed page-turn.
- **Word spacing and text-outline** settings for books (Reading appearance), alongside letter spacing and italic — Moon+ Reader's typography set, engine-native.
- **Status line for books**: a quiet line at the top or bottom of the page showing the clock, your progress through the chapter, the chapter's name and the battery level, each toggleable, with its own size and colour. Off by default.
- **Webnovel plain-text support**: sources that serve chapters as plain text now render as real paragraphs — line breaks become paragraph breaks, and three cleanup passes (intelligent paragraphs, blank-line removal, space trimming — Moon+ Reader's suite) strip the artefacts of machine-assembled text. Each pass is toggleable in Reading appearance → Webnovels.
- **Highlights and selection actions in books**: select any text to get four pen colours, a copy action and a look-up (the system web search, or ColorDict/GoldenDict when installed). Highlights persist per chapter, survive font changes and reflow, and re-mark themselves on every page. Tap inside a highlight to remove it. Toggleable in Reading appearance.
- **Read-aloud for books**: the window's text is spoken by the system text-to-speech engine, in order, with an adjustable rate — and it chains across the book, turning the page itself when a document is spoken out (works in continuous and paged modes). Reading appearance → Read-aloud.
- **Ignore publisher styles** for books (Reading appearance): drops the book's own stylesheets and keeps the reader's — the escape hatch for books that hardcode pixel font sizes, page-width boxes or paper-tuned letter-spacing. Your typography settings win from the first page, and flipping it back restores the book's styling instantly.
- **Upscale new entries by default and per category**: the AI upscaler's per-manga toggle gains two wider controls - a category default (on/off per library category, with the per-manga toggle still winning) and an opt-in default for newly added entries - so whole collections can be upscaled without touching each entry.
- **Prefetch upscaling ahead of the page you read**: the next pages are upscaled into the cache while you read, so turning the page shows the sharpened image instantly. Depth is adjustable (0-5) in the upscaler settings.
- **Import your own ONNX upscaler models**: pick any .onnx file; it is validated by the runtime before acceptance and usable as the NPU/CPU engine's model.
- **Live upscaler status**: the upscaler settings screen now shows what actually happened - served pages, cache hits, fallbacks, failures, average inference time and per-backend readiness.
- **AI upscaling for everyone**: native model upscaling (Real-CUGAN / Real-ESRGAN / Waifu2x) no longer requires AI Translation — every build and device can now upscale, falling back to a plain resample when models are not installed.
- **Upscale category defaults**: choose per category whether its entries get upscaled (on, off, or no override), with the per-manga toggle still winning over everything.
- **Prefetch pages ahead while reading**: the next pages are upscaled in the background before you reach them, memory-gated so low-RAM devices keep the page turn smooth.
- **Bring your own upscaler**: import your own ONNX super-resolution models from storage — they are probed by the runtime before acceptance and join the fallback chain like the built-in ones.
- **Discord RPC: log in with a token.** The Discord login screen's menu now takes a raw account token directly, validated against Discord and stored locally — a way in when the embedded-WebView login is flagged or fails.

### Improve

- **Granular extension content warnings replace the single NSFW toggle.** Extensions can now be flagged Safe, Mixed or NSFW (an extension's sites can carry more than one label), and you choose which warnings you allow in Settings → Browse — several at once. The old "show NSFW sources" switch migrates to the new setting; everything you had enabled stays visible.
- **MangaDex and the remaining delegated sources have been removed.** MangaDex (including its login, follows sync and recommendations) plus the NHentai, 8Muses, Pururin and LANraragi in-app sources are gone — only E-Hentai/ExHentai and the built-in Komga source remain. Library entries from the removed sources are kept and their metadata remains readable; MDList (MangaDex) tracker rows are cleaned up on upgrade. If you read from MangaDex, install the community MangaDex extension instead.
- The discontinued Mullvad DNS-over-HTTPS option has been removed from the DNS-over-HTTPS picker; anyone on it is switched to the default automatically.

### Fix

- **Chapter blacklist entries now stay hidden.** The per-source chapter blacklist (hidden chapters) used to reset when you left a series and came back — the filters screen now persists its state, so blacklisted chapters stay blacklisted.
- **Rotating achievements now announce themselves again.** The rotating achievement toast did not fire when a tier completed.
- **The Discord RPC settings no longer crash the app on open.**
- App: fixed a `NoSuchMethodError` crash (`HttpException.getCode`) that killed the whole app when an extension hit an HTTP error — the 1.25.3 package shipped an inconsistent build of that exception class; any build of the current sources resolves it.

- **Upscaling no longer requires AI Translation**: the upscaler was rebuilt as its own pipeline (like the translation rewrite) — the no-MTL build now gets native model inference too, the mode gains an Auto option that falls back to simple resampling when a model or backend is unavailable, and existing per-manga opt-ins and the page cache carry over untouched.
- **Real-CUGAN 3x model added**: the upscaler catalog now offers a 3x Real-CUGAN alongside 2x/4x options, with 3x/4x artifacts verified by hash.
- **Reworked the translation pipeline from the ground up** — the on-device translation engine was rebuilt for faster startup, more reliable translations when providers change or go offline, and a smarter cache that reuses translated pages until the source or provider settings change.
- **Title and description translations no longer show stale results** — cached translations are now validated against the manga's current metadata and your provider settings, so edited entries re-translate instead of showing outdated text.
- **Clearer provider status in translation settings** — the Gemini Nano device status row now reflects the actual on-device availability reported by the engine.
- **Legacy translation caches are cleared on upgrade** — one-time cleanup of the old saved-page store; translations of pages will re-run on demand.

- WebGPU devices now run in full core mode wherever the driver supports it (`core-features-and-limits`), instead of staying in Compatibility mode with its lower limits — removes a class of silently-broken pipelines that could draw nothing (black screen) on capable devices.
- Reader: extremely high-resolution comic pages (250MB-comic territory) no longer crash the WebGPU reader out of memory. Pages are now clamped to what your screen can actually show (with zoom headroom) instead of uploading every pixel of a huge scan to the GPU — so the reader survives instead of dying with a lost GPU device mid-chapter, and on small or e-ink panels it uses a fraction of the memory it did. On a struggling device page detail eases off and recovers with available memory rather than the reader failing; 32-bit devices get a deliberately smaller memory budget across the reader.
- Reader: a WebGPU page could render at a tiny fraction of its size (a few dozen pixels) after the surface was rebuilt underneath it — seen on e-ink readers around refresh-mode switches and on sleep/resume: view a page, swipe away and back, and it came back postage-stamp. The degenerate scale is no longer latched; the page returns at its proper size.
- Reader: WebGPU sessions whose GPU device keeps dying without recovering now fall back to the standard reader on the next launches, instead of opening the same black screen every time — the easter egg on the About screen's version entry resets that fallback after a driver update fixes the device.
- **Reader: pages load in the order you read.** The WebGPU reader now ranks its background loading by where you are and where you are heading — the page on screen first, then the side you are scrolling toward — so turning into an unfetched page finds it already decoded far more often, especially right after opening a chapter.
- **Reader: the WebGPU loading placeholder now says what it is doing** — the percentage is joined by the current stage (fetching, decoding, uploading…), so a slow page tells you whether it is waiting on the network or already rendering.
- **Reader: pages reserve their true height before they finish loading** — as soon as the app can know a page's shape (the moment its bytes are read, or — for long strips cut into segments — at the moment they are cut), the loading placeholder occupies exactly the space the finished page will. Turning into an unfetched page or scrolling through a loading long strip no longer reflows the pages around you as they finish.

### Fix

- Reader (WebGPU): the first pages of a chapter could "run away" from you while you scrolled down — right after opening, or whenever the display's size shifted (menu bar hide/unhide, foldable posture change, e-ink refresh), any page still loading silently re-sized its reserved space, moving the content under your finger without you scrolling. Loading pages now reserve a fixed height until they decode, so the document only ever moves when you move it.
- Reader (WebGPU): a thin dark line could appear between two pages, rarely, while a neighbouring page was still decoding or just finished (the two painters that redraw those boundary rows each computed the boundary their own way, and the two computations could disagree by less than a pixel). Every boundary painter now shares one chained value, closing the last two ways the line could form.
- Reader (WebGPU): a saved reading position could be re-applied over a chapter that had already changed (a chapter swap or a seek landing while the restore was still waiting for its page to decode). Restores are now invalidated the moment the chapter set changes.
- **Books: paged and two-page reading modes now work.** The layout engine's script had a syntax error that silently disabled itself — and the rest of the reader's page plumbing with it — so picking Paged or Two-page did nothing and every book kept scrolling. All three modes behave now.
- **Books: the reader options sheet scrolls again on first open.** The settings sheet could come up with dead scrolling after entering from the reader menu (worst on the first open of a session); closing and reopening it used to be the only cure.
- **Books: the status line's selected colour now shows as selected immediately** instead of only after closing and reopening the settings, and the status line now paints a solid background so the book's text no longer bleeds through it.
- **Books: the status line's chapter field now shows the section you are reading** (from the book's table of contents, falling back to the document's name) instead of repeating the novel's title everywhere.
- **Books: corrupt downloaded chapters now explain themselves.** A download interrupted by a full disk or a dead storage provider used to surface as "Truncated ZIP file data" or "mmap failed: EINVAL (invalid argument)" every time you opened the chapter. Downloads are now verified the moment they finish — a broken archive is deleted and the download fails with a message telling you to try again, instead of leaving a permanently unreadable chapter behind.
- Reader: a chapter whose very tall page gets cut into screen-sized chunks used to land you at the *end* of that page once the chunks finished loading — the classic webtoon "opened the chapter and I'm at the bottom" bug. Opening it now starts you at the top, where the page began.
- Reader: WebGPU devices that black-screen without ever killing their GPU device (seen on PowerVR 32-bit phones) are now counted and remembered, and the reader falls back to the standard viewer on later launches instead of opening the same black screen again.
- App: installing extensions no longer crashes the app on Android 15+ after the install service's daily background-data budget runs out — the service now degrades gracefully instead of taking the process down.
- Library: completing a series that is not in your library no longer counts toward the "clear the backlog" achievements — the backlog is your shelved pile, so only shelved series can clear it.

### Improve

- Tapping the version entry on the About screen now copies the full device info block (the same one crash reports lead with), not just the version line — bug reports finally arrive with a device to reproduce on.
- Extension update errors are now only recorded for HTTP 401 and 404 — the statuses that mean the source needs your attention. The rest (Cloudflare challenges, rate limits, server hiccups) are logged but no longer flood the errors screen, and any recorded HTTP error's message tells you to check the site in WebView.
- Migration: **"Fetch chapters in manual search" is now off by default** — each result's latest-chapter lookup is opt-in traffic, and slow sources no longer leave every result card spinning.
- Books: turning pages now reports what the layout engine actually answered, so a "pages don't turn" report can be diagnosed from the log.
