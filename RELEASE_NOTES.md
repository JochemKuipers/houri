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
- **The chapter selector now knows what a book's chapters are.** A book is one library chapter, so
  the selector offered a single row — or, for a folder of novels, a list of *other books*. While
  reading a book it now lists the volume's own sections from its table of contents, and tapping one
  jumps there. Books whose source has no table of contents get the old list back rather than a
  dead button.

### Improve
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
- **Illustrations, stylesheets and embedded fonts render again.** A book's file without a
  conventional extension — a cover named by a query string, a WebP, an image stored as `.bin` — was
  handed to the renderer with a type it refuses to decode, so it silently did not appear while the
  page around it did. Files are now identified by their contents when their name does not say.
- **Reaching the end of a page now turns it.** The reader decided a tap should turn by asking "did
  the page move?", and a page with nowhere left to scroll answers "no" — so the last screen of a
  section could only be left with the chapter buttons. The reader now asks *which* end the
  document ran out of room at, and turns that way.
