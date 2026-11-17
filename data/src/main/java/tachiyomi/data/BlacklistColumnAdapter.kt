package tachiyomi.data

import app.cash.sqldelight.ColumnAdapter
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lossless storage for the chapter blacklist keys (domain `scanlatorBlacklistKey`s).
 *
 * The shared [StringListColumnAdapter] joins on ", " — and a key is free text: a scanlator
 * name like "Group A, Group B" (or a narrowed key's embedded url carrying one) was
 * indistinguishable from the separator once written, so the decoded list no longer held the
 * key that went in, `isChapterBlacklisted` silently stopped matching, and the blacklisted
 * chapter came back the next time the series was opened (issue #68).
 *
 * JSON-array encoding round-trips any key exactly. Rows written before this adapter hold the
 * old ", "-joined text; `decode` recognises them (not a JSON array) and applies the same split
 * the previous adapter did, so existing data reads unchanged and the next edit re-encodes it
 * losslessly. The column type is unchanged (TEXT), so no schema migration rides along.
 *
 * The discriminator is safe: a legacy row always starts with its first key, and a key always
 * starts with the rounded chapter number — a digit — never with `[`.
 */
object BlacklistColumnAdapter : ColumnAdapter<List<String>, String> {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    override fun decode(databaseValue: String): List<String> {
        if (databaseValue.isEmpty()) return emptyList()
        if (!databaseValue.startsWith('[')) {
            return databaseValue.split(", ")
        }
        return runCatching {
            json.parseToJsonElement(databaseValue).jsonArray.map { it.jsonPrimitive.content }
        }.getOrElse {
            // Unparseable JSON is treated as legacy text rather than dropping every
            // key over one malformed row.
            databaseValue.split(", ")
        }
    }

    override fun encode(value: List<String>): String = buildJsonArray {
        value.forEach { add(it) }
    }.toString()
}
