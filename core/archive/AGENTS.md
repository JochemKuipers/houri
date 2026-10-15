# core/archive/ Module

CBZ/archive reading with optional AES encryption (SY). Package root: `mihon.core.archive.*`.

## Key classes

| Class | Purpose |
|-------|---------|
| `ArchiveReader` | Memory-mapped archive reading via libarchive (JNI). Detects encryption on open. |
| `ArchiveInputStream` | InputStream over mmap'd archive with passphrase support |
| `ArchiveEntry` | Archive entry model (name, isFile, isEncrypted) |
| `CbzCrypto` | AES-256/128/ZipCrypto encryption via Android KeyStore |
| `EpubReader` | EPUB engine over ArchiveReader — parses into an `EpubBook` and streams documents |
| `EpubBook` | Parsed book: metadata, manifest, spine, TOC, encryption state |
| `EpubXmlReader` | Namespace-aware pull parser for the structural documents |
| `EpubOpfParser` / `EpubTocParser` / `EpubEncryptionParser` | One pass each over `content.opf`, the TOC, `encryption.xml` |
| `EpubPaths` | Pure-JVM reference resolution (relative, absolute, `..`, percent-encoding) |
| `ZipWriter` | Archive writing with optional encryption (SY) |

## Dependencies

- `core:common` (ImageUtil, SecurityPreferences, Injekt DI)
- `libarchive` (native C library via JNI)
- `UniFile` (storage abstraction)

## EPUB parsing

Namespace-aware, not Jsoup XML mode. Jsoup keeps the literal prefix in the tag name, so
`getElementsByTag("dc:title")` silently yields an empty title for any book that binds Dublin Core to
`dcterms:` instead of `dc:`. Everything matches on the **namespace URI**.

Handled that the old parser did not:

- **Both TOC formats.** EPUB 3 `nav.xhtml` (`epub:type="toc"`, an EPUB-vocabulary attribute that
  `attr("type")` cannot see with namespaces on) and EPUB 2 `toc.ncx`. Many real books ship only one.
  A `<nav>` without `epub:type` is a landmarks list and must not be read as a TOC.
- **DRM.** `encryption.xml` distinguishes font obfuscation (`http://www.idpf.org/2007/embed`, which
  leaves text readable and is ignored) from real DRM (which locks the content and must be reported).
  `EpubReader.hasDrm` is the answer; `EpubEncryptionParser.affects` checks a specific document.
- **Percent-encoding.** Producers disagree on whether the stored zip name matches the manifest href
  byte for byte. `EpubPaths.lookupCandidates` offers the decoded and re-encoded spellings as
  *fallbacks* — decoding inside `resolve` would turn a file named `%2E%2E` into a directory escape.
- **Charset** from the XML prolog, not assumed UTF-8.
- Spine entries whose idref has no manifest item are dropped (a placeholder would be a chapter that
  can never open).

`EpubPaths` is deliberately pure and Android-free so it can be asserted off-device — see
`scripts/kotlinc-check/harness/PathHarness.kt`.

### Two spines, two meanings

- `textDocuments()` — the book as prose, one page per spine item. **This is what the reader uses.**
- `getImagesFromPages()` — every `<img>` in every spine document. The manga local source treats an
  EPUB as an art book and uses this. Do not confuse them.

## Encryption (SY)

- Two key aliases: `cbzPw` (CBZ archive) and `sqlPw` (SQL database)
- Password stored encrypted in SharedPreferences as Base64-encoded (IV + ciphertext)
- `createComicInfoPadding()` adds random padding to ComicInfo.xml (anti-fingerprinting)

## Conventions

- All archive I/O uses `Os.mmap()` for zero-copy reads
- Fork markers: `// SY -->` for TachiyomiSY encryption additions
