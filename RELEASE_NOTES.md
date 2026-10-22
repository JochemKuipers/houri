<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Subcategory types**: when creating a subcategory (Edit Categories or from library
  selection) you can label it Author, Artist, Illustrator or Designer, so two same-named
  subcategories under one parent are tellable apart; the label shows as a colored tag in
  Edit Categories and in the Set Categories dialog.

### Improve
- **Set Categories**: the dialog now has the same navigation aids as Edit Categories —
  a search field, a sort dropdown (manual / name / date-added / date-modified), and
  expand/collapse chevrons with Expand all / Collapse all, so large category trees are
  browsable without leaving the dialog.
- **Anime-Planet**: rewired against the site's current pages — refreshing now reads your status,
  chapter progress and score back from your Anime-Planet list (true two-way sync), and freshly
  bound entries pick up the manga's total chapter count.

### Fix
- **Google Drive**: a revoked/expired refresh token no longer fails every sync attempt
  forever — the app signs itself out cleanly so you can sign in again; and on devices
  without a browser, tapping Sign-in toasts a friendly error instead of crashing.
- **Anime-Planet**: rating writes now hit the site's real `rate` endpoint and use its half-star
  (0.5–5.0) scale — scores previously never persisted.
- **Anime-Planet**: starting a re-read no longer marks the entry "Won't Read" on the site.
