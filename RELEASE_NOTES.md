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

- **Library**: Large libraries load noticeably faster and stay responsive while updating. Cover-cleanup work no longer runs a second full library scan at the same moment the library is first built, duplicate background library queries were collapsed into one, and title sorting now prepares each title once instead of on every comparison.

### Fix

- **Book reading**: The typography rows in *Settings → Book reading* were drawn on top of each other into an unreadable pile, because they relied on a scrolling container they were not given there. They now stack normally.
