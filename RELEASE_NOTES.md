<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New
- **Books can live in the manga library.** An `.epub` you drop into an ordinary library folder used to
  open as a strip of illustrations and nothing else, because the reader asked the file's format and
  never asked what the entry *was*. Entries now carry a content type, and one that is a novel opens as
  a book — your own stylesheet, your own fonts, your illustrations inline — in the same library as
  everything else.
  - **Nothing is taken away.** A folder whose chapters are art rather than prose still opens as a
    comic, because that is what those files are for and some readers depend on it.
  - **The guess is wrong sometimes, and it is easy to overrule.** Detection reads the book's spine and
    is a heuristic; a book it calls a comic, or an art book it calls a novel, is one tap from being
    fixed in the manga's info screen. What you choose there sticks — a later library update will not
    quietly undo it.
  - **Extensions can say what their entries are**, through a new optional interface. Nothing existing
    changed shape, so every extension you have installed keeps working untouched.
- **Illustrations in a book can be turned off.** Reading a heavily illustrated novel, decoding every
  plate is the slowest part of turning the page. Default is on, which is how it already behaved.

- **Books have a contents list and a chapter search.** Both were implemented on the EPUB side and had
  no way to reach them. The bottom bar now carries a **Contents** and a **Search** button while you are
  reading a book, and they hide themselves again for a chapter that cannot answer — an extension with
  no table of contents simply does not get the button, rather than getting one that opens nothing.
  - **Contents** follows the book's own structure, indenting nested entries the way a printed one
    does, and opens scrolled to wherever you are in the book. Tapping an entry goes to that section.
    Entries belonging to a different chapter of the book are still listed, greyed out, so you can see
    the shape of the volume even though you would have to open that chapter to reach them.
  - **Search** looks through the chapter you are in as you type, and lists the surrounding text of
    every match — the snippet is the only way to tell two hits of the same word apart, and it is
    selectable so you can copy a passage out. Tapping a result jumps to it. Matching is
    case-insensitive.

- **A book's reading appearance is adjustable at last.** Font size and scale, family, text and page
  colours, line and paragraph spacing, letter spacing, margins, justification, hyphenation, vertical
  writing and inline illustrations were all stored settings with no way to reach them — they now
  live in a **Reading appearance** tab in the reader's settings, offered exactly when the open
  chapter is a book. Colours are a fixed palette, and picking a page colour also picks a text colour
  that stays readable on it, so a dark page can never again mean invisible text.
- **You can add your own fonts to the book reader.** The font picker in the Reading appearance tab
  now has an **Add a font** option: pick a `.ttf`, `.otf` or `.woff` file from your device and it
  is offered beside the built-in families, for every book you read. The file is copied into the
  app's own storage once — nothing is left pointing at your files.
- **The chapter selector now knows what a book's chapters are.** A book is one library chapter, so
  the selector offered a single row — or, for a folder of novels, a list of *other books*. While
  reading a book it now lists the volume's own sections from its table of contents, and tapping one
  jumps there. Books whose source has no table of contents get the old list back rather than a
  dead button.

### Improve
- **A book's settings only offer what a book can use.** While reading an epub, the reader's settings
  were the manga reader's: scale types, crop borders, page spreads, tap-zone layouts, page flash,
  chapter transitions, colour filters — none of which do anything to text, and several of which
  were visible for no reason. A book now shows General, trimmed to the options that apply, plus its
  own Reading appearance tab, and nothing it cannot act on.
- **A book's chapter buttons now step its chapters.** The previous/next chapter buttons skipped a
  whole book — one library chapter — so inside a novel they were useless. They now move through the
  book's own sections, and leave for the next volume only at either end of the book.
- **A book reads like the continuous manga reader.** Scrolling used to stop dead at the end of each
  chapter, and the only way onward was a tap or a button. The next chapter now flows in as you
  reach the bottom of the one you are reading — and the previous one flows back in above you when
  you scroll up — so a book reads as one long page. Pushing past the very end of the book moves on
  to the next volume.
- **The page slider now shows chapter progress.** For a book it used to offer the chapter list as a
  row of "pages" — a chapter navigator wearing the wrong label, competing with the contents list.
  It now shows how far through the chapter on screen you are, as a percentage, and dragging it
  moves through that chapter.
- **The book reader can be navigated at all.** Taps, swipes and volume keys did nothing in an EPUB, so
  a chapter with no text on the first screen — which is what most books open on, since their first
  file is usually the cover — left you stuck. Tapping an edge now moves, tapping the middle opens the
  menu, and holding opens the page actions. Running off the end of a chapter turns to the next one;
  running off the end of the book opens the menu, which is where the chapter list already was.
- **Books reopen where you stopped.** It always opened at the first page of a chapter regardless of
  how far you had read. Position is now remembered *within* a chapter as well as between them, and is
  measured in characters rather than pages — so a book reopens on the same paragraph at a different
  font size, on a tablet, or in landscape, instead of jumping to a page boundary that means something
  different on each.
- **Books in other encodings render correctly.** Chapters relied on the XML declaration inside the file
  to say what encoding they were in, and the reader was discarding it. It says so itself now, so a
  Shift_JIS or EUC-JP book is no longer mojibake.
- **Chapter dates are no longer off by centuries in some languages.** A chapter's publication date was
  interpreted using the phone's own calendar, which on some devices shifted every year. Dates are now
  read the same way everywhere.
- **A book with a broken reference no longer stops your library scanning.** One malformed file could
  fail the whole scan of the folder it sat in.
- **Changing the reading appearance now changes the page you are on.** Font size, colour, margins and
  the rest re-apply to the open chapter immediately, instead of waiting for you to reopen it — which is
  what made those settings look broken rather than lazy.
- **Searching a chapter is no longer re-read from scratch on every keystroke.** The search followed
  typing, and each pause re-read and re-parsed the whole book from disk, so typing a word meant reading
  the chapter several times over. It is now prepared once and reused.
- **Long novels open as fast as short ones.** Opening a book read every file it contained before the
  first screen could appear, so a five-hundred-file novel took visibly longer to open than a short
  one — the longer the book, the longer the wait. The section you open on is read first now and the
  rest as you turn, so a novel opens on its first page almost immediately however long it is.

### Fix
- **Reading many books no longer slows down or runs out of memory.** Each book you opened kept its file
  mapped open for as long as the app was running, and nothing ever gave it back, so a reading session
  that got through fifty books was holding fifty of them. It is released when you leave a chapter now.
  Opening a book also got measurably faster: every stylesheet, illustration and font it asked for was
  found by re-scanning the whole archive from the beginning, so a book with a lot of files paid for
  every one of them.
- **A damaged book can no longer take the app down with it.** Sizes inside a book file are whatever
  that file claims they are, and they were being trusted without a limit. A single oversized or
  deliberately crafted file could exhaust memory, or push the reader off a chapter, or crash it while
  drawing a cover. There are now ceilings on how much of a chapter, one file, or one cover the reader
  will hold at once — set far above anything a real book uses.
- **A book cannot silently redirect you off the page.** A file carrying a "refresh to this address"
  instruction replaced the page you were reading, without you tapping anything.
- **Links inside a book no longer take over the reader.** Tapping a link to a website used to load that
  website *inside* the reader, on top of the page you were part-way through. They now open in your
  browser instead, and the book stays where you left it.
- **The contents and search buttons no longer appear before they can work.** They were offered as soon
  as the chapter started loading, and opening one in that window showed an empty list and reported the
  book as having no contents. The contents list also failed to notice when a chapter's pages arrived
  after it had already decided which entries were reachable.
- **A font that broke a line no longer made the appearance settings look broken.** Choosing a font whose
  name contained certain characters left the style update silently failing, so the page kept its old
  appearance and the setting seemed to do nothing.
- **Opening a book shows the book again.** The reader resolved every relative reference inside a
  chapter against the *root of the archive* instead of against that chapter's own folder, so a chapter
  kept in `OEBPS/Text/chapter1.xhtml` looked for its stylesheet at `styles/main.css` rather than
  `OEBPS/styles/main.css` and never found it. Every illustration, embedded font and relative link in
  the book missed the same way, and silently — no error anywhere. Books were rendering as bare text
  wearing only the reader's own styling, which is what made the reader look simply broken.
- **A book can no longer end up in the picture reader.** The reader picked which viewer to use before it
  had loaded the chapter, so it could never tell a book from a comic, and handed every EPUB to the image
  reader — which then had nothing to draw. It now decides from what loaded the chapter, and once a
  chapter is known to be a book nothing else is allowed to try. If the book reader somehow cannot handle
  it the reader says so, instead of quietly showing a blank page that looks like a problem with your file.
- **One missing image no longer holds up a whole chapter.** A file the book referred to but did not
  contain used to leave the engine waiting on a URL scheme nothing could answer, which held up the
  chapter's layout. It now shows a broken image and carries on.
- **Searching no longer lands you in the wrong part of the chapter.** A book with a file that could not
  be read — a broken download, one file too large — left a gap in its numbering, and every search result
  after that gap pointed at the wrong section or at nothing at all. Results now resolve against the
  document they were actually found in.
- **A section name containing a percent sign no longer throws off the contents jump.** Following a
  contents entry whose target was named something like `100%d` sent the reader to the wrong place,
  because the scroll instruction was assembled by substituting into a template that had more than one
  placeholder.
- **The contents sheet no longer leaks what it holds open.** Closing it left the reader's search and
  contents work running in the background for as long as the app stayed open, each holding on to the
  book it was working through. That work is now stopped when the sheet closes.
- **A contents list that lists nothing from this chapter says so.** A book-wide contents whose entries
  all point at other chapters was reported as a book with no contents at all, rather than as one whose
  contents are somewhere else.
- **A non-white page no longer hides the text.** Setting a page colour left the book's own text
  colour standing, so a dark page meant dark text on it — unreadable, and with nothing in the
  settings to suggest why. Choosing a page colour now also chooses a text colour that contrasts
  with it, unless you picked a text colour yourself.
- **A book's illustrations, stylesheets and embedded fonts render.** Books were being shown at a
  made-up internal address, and the reader's engine only loads the files a page asks for — its
  images, its fonts, its own styling — when the page's address is a real web one. Every file a
  book asked for was being refused before the reader ever saw the request, so a novel showed its
  text and nothing else, with no error anywhere: the reader looked simply broken rather than short
  one illustration. Books now live at a reserved address the engine accepts — and the illustrations
  and stylesheets of the chapter you are reading are carried inside the page itself, so they
  arrive the same way the text does and cannot be refused by anything. (A file without a
  conventional extension — a WebP, an image stored as `.bin` — is also identified by its contents
  when its name does not say.)
- **Reaching the end of a section now turns it.** A tap's job — scroll, and turn the page when
  there is nothing left to scroll — was delegated to a script inside the book's own page, and when
  that script did not run, every tap silently did nothing: the page slider was the only way from
  one chapter of a book to the next. Turning is now decided by the reader itself, so it cannot be
  disabled by the book, the engine, or anything else.
- **The black background works on books, and a dark background no longer hides the text.** Choosing
  black in the reader's general settings painted only the window behind the page and left the
  book's own text colour standing — which for most books is black text on a black page, i.e.
  invisible. A dark background now colours the page itself and the text switches to the colour
  that reads on it. A page colour you picked yourself in the Reading appearance tab still wins.
- **MangaBaka knows the last chapter.** The tracker never read how many chapters a series has, so
  progress showed as a bare number with no total, the chapter picker offered a ten-thousand-entry
  range with no end in sight, and finishing a series never marked it completed — the reader could
  not tell which chapter was the last one. The count now comes from MangaBaka's own series data,
  for newly bound series and existing ones alike.
- **Achievements no longer unlock during onboarding.** The preferences behind them are evaluated
  the moment the app starts, so a value that was true only because it *shipped* true could earn
  its achievement before you had chosen anything — including whether to have achievements at
  all, which is one of the things onboarding asks. Nothing unlocks until onboarding is finished.
  A backup restore is unaffected, and still brings back everything you had.
- **A book's real title, author and description show in the library.** Books whose publisher
  bound the metadata to the `dcterms:` prefix instead of the usual `dc:` showed nothing but their
  file names — the local source was reading the book's own catalogue with a parser that only
  understood one spelling of it. It now reads the catalogue the book engine already parsed, which
  understands both spellings, and a book with several authors lists them all.
- **A book's cover is its declared cover.** The library thumbnail was whatever image turned up
  first in the book — for most novels that is a publisher logo. It is now the cover the book
  itself declares, falling back to the first illustration only when the book declares none, and
  a DRM-locked book no longer writes a thumbnail of noise.
