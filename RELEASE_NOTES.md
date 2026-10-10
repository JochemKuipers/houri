<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

### Improve

### Fix

- **WebGPU long-strip reader:** very tall pages (long webtoon strips) no longer fail to build their preview levels — zoomed-out reading through them is sharp again instead of sampling the full-resolution image
- **WebGPU long-strip reader:** scrolling down through a page no longer drops an unrelated page in below it, cutting the current page off, until you scroll far enough for it to correct itself
