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
- **Anime-Planet**: rewired against the site's current pages — refreshing now reads your status,
  chapter progress and score back from your Anime-Planet list (true two-way sync), and freshly
  bound entries pick up the manga's total chapter count.

### Fix
- **Anime-Planet**: rating writes now hit the site's real `rate` endpoint and use its half-star
  (0.5–5.0) scale — scores previously never persisted.
- **Anime-Planet**: starting a re-read no longer marks the entry "Won't Read" on the site.
