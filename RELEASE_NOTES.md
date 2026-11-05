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
- **Reworked the translation pipeline from the ground up** — the on-device translation engine was rebuilt for faster startup, more reliable translations when providers change or go offline, and a smarter cache that reuses translated pages until the source or provider settings change.
- **Title and description translations no longer show stale results** — cached translations are now validated against the manga's current metadata and your provider settings, so edited entries re-translate instead of showing outdated text.
- **Clearer provider status in translation settings** — the Gemini Nano device status row now reflects the actual on-device availability reported by the engine.
- **Legacy translation caches are cleared on upgrade** — one-time cleanup of the old saved-page store; translations of pages will re-run on demand.

### Fix
