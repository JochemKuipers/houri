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
- Library unread badge no longer disagrees with the chapter list. When several scanlator groups
  upload the same chapter, the badge counted a chapter as read as soon as *any* group had it, but
  the merged chapter list showed whichever group ranked best for coverage - so a series could read
  as 7 unread on the cover while every chapter in its list looked unread. The merge now keeps the
  upload you already read or bookmarked, so the two always agree.
- History tab no longer counts blacklisted chapters towards "read of total", matching the library
  badge and the chapter list.
- History tab's bookmarked progress no longer misses bookmarked extras and oneshots, which have no
  chapter number to be counted by.
- The **Smart scanlator merge** switch now takes effect straight away instead of needing a restart,
  including for the library's continue-reading button. Turning it off now really does list every
  upload from every group, duplicates included, so the chapter list behaves like a stock reader.
