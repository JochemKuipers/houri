package mtl

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class MangaInfoFingerprintTest {

    @Test
    fun `fingerprint is stable and sensitive to title description and language`() {
        val base = mangaInfoFingerprint("JA", "Original", "Desc")

        base shouldBe mangaInfoFingerprint("JA", "Original", "Desc")
        mangaInfoFingerprint("JA", "Changed", "Desc") shouldNotBe base
        mangaInfoFingerprint("JA", "Original", "Other") shouldNotBe base
        mangaInfoFingerprint("EN", "Original", "Desc") shouldNotBe base
    }

    @Test
    fun `fingerprint ignores surrounding whitespace and language case`() {
        mangaInfoFingerprint("JA", "  Original  Title ", "Desc") shouldBe
            mangaInfoFingerprint("JA", "Original  Title", "Desc")
        mangaInfoFingerprint("ja", "Original", "Desc") shouldBe
            mangaInfoFingerprint("JA", "Original", "Desc")
        mangaInfoFingerprint(" ja ", "Original", "Desc") shouldBe
            mangaInfoFingerprint("JA", "Original", "Desc")
    }

    @Test
    fun `null description fingerprints the same as an empty one`() {
        mangaInfoFingerprint("JA", "Original", null) shouldBe
            mangaInfoFingerprint("JA", "Original", "")
    }

    @Test
    fun `source language falls back to JA when undeclared`() {
        mangaInfoSourceLang(null) shouldBe "JA"
        mangaInfoSourceLang("") shouldBe "JA"
        mangaInfoSourceLang("   ") shouldBe "JA"
    }

    @Test
    fun `source language is trimmed uppercased and length-capped`() {
        mangaInfoSourceLang("EN") shouldBe "EN"
        mangaInfoSourceLang(" zh-Hans ") shouldBe "ZH-HANS"
        mangaInfoSourceLang(" ja ") shouldBe "JA"
        mangaInfoSourceLang("X".repeat(30)) shouldBe "X".repeat(20)
    }
}
