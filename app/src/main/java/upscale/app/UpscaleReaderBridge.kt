package upscale.app

import kotlinx.coroutines.CancellationException
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Display-time upscale seam shared by the pager, webtoon, and WebGPU readers — the same
 * service-locator shape as the old `UpscaleReaderHook`, now pointing at the spine's
 * [upscale.UpscaleController]. Returns upscaled bytes, or null when the page must be shown
 * untouched. Callers keep their original bytes on null, so translation and fallback paths
 * never observe this helper.
 *
 * Every entry is suspend: the full enablement ladder (global, per-manga, category, new-entry
 * default) runs on the caller's IO context, never on the main thread.
 */
object UpscaleReaderBridge {

    /**
     * Whether [mangaId] would actually be upscaled. Cheap config reads plus one memoized
     * category lookup — no byte inspection, no session setup — so the reader can use it to
     * skip capturing a page's encoded bytes when upscaling cannot apply.
     */
    suspend fun isUpscaleActive(mangaId: Long?): Boolean {
        if (mangaId == null || mangaId <= 0) return false
        val controller = try {
            globalAppGraph.upscaleController
        } catch (_: Exception) {
            return false
        }
        return try {
            controller.isEnabledForManga(mangaId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun upscaleDisplayBytes(
        mangaId: Long?,
        chapterId: Long,
        pageIndex: Int,
        bytes: ByteArray?,
        readerKind: upscale.core.ReaderKind = upscale.core.ReaderKind.PAGER,
    ): ByteArray? {
        if (mangaId == null || mangaId <= 0 || bytes == null || bytes.isEmpty()) return null
        return try {
            val controller = globalAppGraph.upscaleController
            // Cheap animated-image pre-check before the spine touches the bytes: the header
            // parse copies up to 64KB on the calling thread, and callers hold a page's bytes
            // for MTL alone far more often than for upscaling.
            if (ImageUtil.isAnimatedAndSupported(bytes)) return null
            controller.upscaleDisplayBytes(
                mangaId = mangaId,
                chapterId = chapterId,
                pageIndex = pageIndex,
                bytes = bytes,
                readerKind = readerKind,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
