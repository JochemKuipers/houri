package mtl.context

import mtl.core.TranslationContext

/**
 * Where per-manga translation context comes from: the rolling breadcrumb notes a chapter
 * accumulates as pages translate, and the manga grounding (title/description/tags) the
 * metadata path already has.
 *
 * The spine reads context through this interface; the app supplies the implementation that
 * talks to stores and the DB. In-memory implementation for tests.
 */
interface ContextStore {

    /**
     * The grounding for [mangaId]: first line is the title, then description, then tags.
     * Doubles as the saved-page sidecar's title source — one lookup, two uses.
     */
    suspend fun mangaGrounding(mangaId: Long): String?

    /** The accumulated breadcrumb notes for this chapter, within [budgetChars]. */
    suspend fun breadcrumbs(mangaId: Long, chapterId: Long, budgetChars: Int): String

    /** Folds a page's translated text into the chapter's rolling notes. */
    suspend fun appendNotes(mangaId: Long, chapterId: Long, translatedLines: List<String>)
}

/**
 * Budget policy for breadcrumbs: a fraction of the model's usable context, clamped.
 * Carried here because it is a pipeline decision, not a provider detail.
 */
object BreadcrumbBudget {
    fun forContextLength(contextLength: Int?): Int {
        val base = contextLength ?: return DEFAULT_BUDGET
        return (contextLength * 0.25).toInt().coerceIn(MIN_BUDGET, MAX_BUDGET)
    }

    const val DEFAULT_BUDGET = 1000
    const val MIN_BUDGET = 500
    const val MAX_BUDGET = 3000
}

/**
 * Trims breadcrumbs to [budgetChars], keeping the most recent lines (the notes build forward
 * through a chapter — the newest notes are the most relevant to the page being translated).
 * Pure; the port of the old manager's inline trimming.
 */
fun trimBreadcrumbs(raw: String, budgetChars: Int): String {
    if (raw.length <= budgetChars * 1.2) return raw
    val tail = raw.takeLast(budgetChars)
    val cut = tail.indexOf('\n')
    // Drop the partial first line when the cut lands within a reasonable distance; otherwise
    // keep the tail as-is (a single overlong line is still better than dropping it all).
    return if (cut in 0..200) tail.substring(cut + 1) else tail
}

class InMemoryContextStore : ContextStore {
    private val grounding = HashMap<Long, String>()
    private val notes = HashMap<Long, StringBuilder>()

    fun setGrounding(mangaId: Long, value: String?) {
        if (value == null) grounding.remove(mangaId) else grounding[mangaId] = value
    }

    override suspend fun mangaGrounding(mangaId: Long): String? = grounding[mangaId]

    override suspend fun breadcrumbs(mangaId: Long, chapterId: Long, budgetChars: Int): String =
        trimBreadcrumbs(notes[mangaId]?.toString().orEmpty(), budgetChars)

    override suspend fun appendNotes(mangaId: Long, chapterId: Long, translatedLines: List<String>) {
        if (translatedLines.isEmpty()) return
        notes.getOrPut(mangaId) { StringBuilder() }
            .appendLine(translatedLines.joinToString("\n"))
    }

    suspend fun contextFor(mangaId: Long, chapterId: Long, budgetChars: Int = BreadcrumbBudget.DEFAULT_BUDGET): TranslationContext =
        TranslationContext(
            mangaGrounding = grounding[mangaId].orEmpty(),
            breadcrumbs = breadcrumbs(mangaId, chapterId, budgetChars),
        )
}
