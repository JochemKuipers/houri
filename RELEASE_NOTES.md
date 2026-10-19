<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Backup restore**: A new "Update library after restore" toggle (on by default) controls whether a restored library immediately kicks off a full background refresh. Previously this always ran and could not be turned off.

### Improve

- **Backup restore**: The restore screen stays responsive while a large backup is validated, and the database folds its write-ahead log when a restore finishes so the restored data is fully settled on disk instead of sitting in a large log file.

### Fix

- **Backup restore**: A restore could silently skip the last series in the file (up to one internal batch, 249 entries) when no full batch remained before the merged entries were written. The leftover batch is now written, so the restored library matches the backup and the progress bar reaches 100% again.

- **Library**: Large libraries load noticeably faster and stay responsive while updating. Cover-cleanup work no longer runs a second full library scan at the same moment the library is first built, duplicate background library queries were collapsed into one, and title sorting now prepares each title once instead of on every comparison.

- **Book reading**: The typography rows in *Settings → Book reading* were drawn on top of each other into an unreadable pile, because they relied on a scrolling container they were not given there. They now stack normally.
