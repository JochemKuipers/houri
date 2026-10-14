<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Toggle for the Cloudflare bypass banner** (Advanced → Network, on by default). Turn it off to
  stop the banner naming the host and solver while a challenge is being worked around. The status
  bar follows the same setting, so nothing is left showing.

### Improve

### Fix

- **Chapter counts were inflated by duplicate uploads.** A series carried by several scanlator
  groups reported several times its real length — in the library entry, in the unread badge, and in
  the History tab's progress. The count now counts chapters rather than uploads. Chapters with no
  number (extras, one-shots) are still counted individually, and the achievements that read the
  chapter total now reflect the real chapter count rather than the upload count.

- **Crash when re-entering a settings screen.** Opening a settings category, going back, and
  re-opening it before the previous animation finished could crash the app with
  `IllegalArgumentException: Key ... was used multiple times`. The settings categories are
  singletons, so the second visit reused the first one's saveable-state key.
- **Library entries showing "source not installed" after a restart.** Manga already in your
  library could show the red warning badge until you restarted the app a second time, even
  though the source was installed. The app was reading its source list before the installed
  extensions had finished loading, so every library entry briefly resolved to a placeholder.
- **"Clear logs" not clearing anything.** The crash logs are written to the external cache
  directory, but both the auto-pruning and "clear logs" were scanning the internal one, so old
  logs were never deleted and the "clear logs" action reported a count of zero.
- **WebGPU reader losing the device after a recovery attempt** (`CreateAndroidSurfaceKHR` /
  `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`), blank pages on some devices. The retry built a new
  swapchain before releasing the old one, so it collided with itself.
- **Blacklisting a chapter hid every upload of it.** When a scanlator had published the same chapter
  number twice, blacklisting one version took the other with it. Existing blacklists are untouched.
- **Edit Categories and the Library disagreed on category order.** Only the subcategory rows were
  being sorted by the selected mode, so with any mode other than Manual the top-level rows stayed in
  database order while the Library's tabs used the chosen one (#40).
- **Editing a cover left the previous one on screen until the app restarted.** Every library layout
  remembered the cover for a series keyed on its id alone, so the new cover's cache key was never
  seen and the already-loaded image kept being reused.
