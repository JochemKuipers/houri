<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New
- **AI Translation**: Traditional Chinese (zh-TW) is now available as a target language alongside Simplified Chinese.

- **AI Translation**: When the translation engine is installed but the optional multilingual OCR add-on (PP-OCRv5) is missing, a one-tap "Install multilingual OCR add-on" row appears in the AI models settings. It downloads only the ~16 MB add-on, which is what reads English and other Latin-script pages; Japanese and Chinese work without it.

### Improve

### Fix
- **Library**: Using "Search library" on a manga no longer breaks the back stack — Back from the filtered Library now lands you on the manga you came from, first closing the search filter as usual, instead of dropping the manga screen entirely.

- **Browse**: Leaving a source's browse screen for a manga (and coming back from a chapter) keeps the list/grid scroll position, instead of returning to the top of the page.

- **Manga Translator**: Image-translation failures now say what actually went wrong — the service found no readable text on the page, the page exceeds its 8 MB / 10000 px limits, or composing the translated page failed — instead of a bare "MangaTranslator returned empty result".
