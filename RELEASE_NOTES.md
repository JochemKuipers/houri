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
- Restoring a backup is dramatically faster on large libraries. Restoring a backup with tens of
  thousands of series spent most of its time on work that had nothing to do with the data itself:
  it posted a progress notification once per entry (each one a round trip to the system UI), it
  looked up reading history two database queries per history row instead of two per series, it
  re-read the whole category list and the whole merge table for every series, it rebuilt the
  edited-titles cache once per series when a backup carries an edit for most of the library, it
  compared known URLs against a list instead of a set when ordering the restore, and it kicked off
  a full library refresh while the restore was still writing - both fighting over the same
  database. All of that is now proportional to how long the restore takes rather than to how many
  series it contains.

### Fix
