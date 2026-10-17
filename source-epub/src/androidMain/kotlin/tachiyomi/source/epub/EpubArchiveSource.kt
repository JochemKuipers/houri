package tachiyomi.source.epub

import eu.kanade.tachiyomi.source.text.TextSource

/**
 * A [TextSource] that keeps archives mapped and therefore has to be told when a chapter is over.
 *
 * ### Why this exists
 *
 * [EpubResourceReader] holds a memory map and a file descriptor per open book, because a 40 MB book
 * cannot be re-opened and re-parsed for every one of its images. That is a lease, and a lease needs
 * a release — without one, a session that reads fifty books holds fifty maps for the lifetime of the
 * process, and the user is the only thing that ever frees them by killing the app.
 *
 * The release has to come from somewhere with a real answer to "is this chapter finished?", and
 * there is exactly one: the loader, whose lifetime *is* the chapter's. The viewer cannot supply it
 * (a viewer is rebuilt on an orientation change while the chapter stays), and the source cannot
 * (it is asked for pages, not told when they are no longer wanted).
 *
 * ### Why an interface and not a cast to [LocalEpubSource]
 *
 * `TextPageLoader` holds a [TextSource], because that is all the extension API promises. Narrowing
 * it to a concrete class would make the built-in source the only text chapter this reader can open —
 * which is exactly the mistake the [eu.kanade.tachiyomi.source.text.TextBaseUrlSource] rule exists
 * to prevent. A narrow optional interface keeps an extension that maps its own archives able to
 * opt in, and keeps every extension that does not completely unaffected.
 *
 * ### Safe to call at any time
 *
 * Both methods may be called while the renderer is still streaming resources out of the book. See
 * [EpubResourceReader.release] for why the unmap is deferred rather than immediate — calling these is
 * never the thing that crashes, which is the property that lets the loader call them unconditionally
 * from `recycle()`.
 */
interface EpubArchiveSource {

    /**
     * Retires the archive for [chapterUrl].
     *
     * Idempotent, and safe to call for a chapter that was never opened.
     */
    fun releaseResourceReader(chapterUrl: String)

    /** Retires every archive this source holds open. */
    fun releaseAllResourceReaders()
}
