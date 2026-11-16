// Runs the REAL PageBytesValidator (compiled straight from app/.../WebGpuImageValidation.kt)
// through its contract: name the container, read the dimensions where the header is cheap, and
// refuse what cannot be a page.
//
// This is the one gate that stands between a source answering a page request with a captcha page
// and the native decoder, so the failure it exists for is not "it crashed" but "it showed
// `Unsupported image format` for an HTML error body". Every case below is built from the actual
// byte layout each format's header promises, which is what makes a signature regression visible
// here rather than as a mysterious decode failure on a phone.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.ImageBytesFault
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.ImageFormat
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.PageBytesValidator

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

private fun concat(vararg parts: ByteArray): ByteArray =
    parts.fold(ByteArray(0)) { acc, part -> acc + part }

private fun beU32(value: Int): ByteArray = bytes(
    (value ushr 24) and 0xFF,
    (value ushr 16) and 0xFF,
    (value ushr 8) and 0xFF,
    value and 0xFF,
)

private fun leU32(value: Int): ByteArray = bytes(
    value and 0xFF,
    (value ushr 8) and 0xFF,
    (value ushr 16) and 0xFF,
    (value ushr 24) and 0xFF,
)

private fun leU16(value: Int): ByteArray = bytes(value and 0xFF, (value ushr 8) and 0xFF)

private fun beU16(value: Int): ByteArray = bytes((value ushr 8) and 0xFF, value and 0xFF)

/** Pads [body] out to at least [size], so a valid-but-short header still passes inspect's floor. */
private fun padded(body: ByteArray, size: Int = 64): ByteArray = body.copyOf(maxOf(size, body.size))

/** FFD8FF + a single SOF0 segment carrying [w]x[h], per the marker walk in jpegDimensions. */
private fun jpeg(w: Int, h: Int): ByteArray = padded(
    concat(
        bytes(0xFF, 0xD8, 0xFF, 0xC0), // SOI, then SOF0
        bytes(0x00, 0x11, 0x08), // segment length (17), precision
        beU16(h),
        beU16(w),
        bytes(0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01), // components
    ),
)

private fun png(w: Int, h: Int): ByteArray = padded(
    concat(
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        beU32(13),
        ascii("IHDR"),
        beU32(w),
        beU32(h),
    ),
)

private fun gif(w: Int, h: Int): ByteArray = padded(
    concat(ascii("GIF89a"), leU16(w), leU16(h), bytes(0x00, 0x00, 0x00)),
)

private fun bmp(w: Int, h: Int): ByteArray = padded(
    concat(
        ascii("BM"),
        leU32(0), // file size
        leU32(0), // reserved
        leU32(0), // pixel offset
        leU32(40), // BITMAPINFOHEADER
        leU32(w),
        leU32(h),
        bytes(0x01, 0x00, 0x18, 0x00),
    ),
)

private fun webp(w: Int, h: Int): ByteArray {
    val body = padded(
        concat(
            ascii("RIFF"),
            leU32(0),
            ascii("WEBP"),
            ascii("VP8X"),
            leU32(10),
            bytes(0x00, 0x00, 0x00, 0x00), // flags
            bytes(0, 0, 0), // canvas width-1, 24-bit LE
            bytes(0, 0, 0), // canvas height-1, 24-bit LE
        ),
    )
    // Canvas width-1 and height-1 are 24-bit little-endian, written after the fixed part.
    val w24 = (w - 1) and 0xFFFFFF
    val h24 = (h - 1) and 0xFFFFFF
    body[24] = (w24 and 0xFF).toByte()
    body[25] = ((w24 ushr 8) and 0xFF).toByte()
    body[26] = ((w24 ushr 16) and 0xFF).toByte()
    body[27] = (h24 and 0xFF).toByte()
    body[28] = ((h24 ushr 8) and 0xFF).toByte()
    body[29] = ((h24 ushr 16) and 0xFF).toByte()
    return body
}

/** A JXL ISOBMFF container: the 12-byte signature box. */
private fun jxlContainer(): ByteArray = padded(
    concat(bytes(0x00, 0x00, 0x00, 0x0C), ascii("JXL ")),
)

/** An ISO-BMFF file whose major brand decides AVIF from HEIF. */
private fun isoBmff(brand: String): ByteArray = padded(
    concat(beU32(24), ascii("ftyp"), ascii(brand)),
)

fun main() {
    // --- the happy path: every format this viewer accepts, named and measured -------------
    val jpegVerdict = PageBytesValidator.inspect(jpeg(1600, 2400))
    check("jpeg named", jpegVerdict.format == ImageFormat.JPEG)
    check("jpeg accepted", jpegVerdict.isAcceptable)
    check("jpeg dimensions", jpegVerdict.width == 1600 && jpegVerdict.height == 2400)

    val pngVerdict = PageBytesValidator.inspect(png(800, 1200))
    check("png named", pngVerdict.format == ImageFormat.PNG)
    check("png dimensions", pngVerdict.width == 800 && pngVerdict.height == 1200)

    val gifVerdict = PageBytesValidator.inspect(gif(640, 480))
    check("gif named", gifVerdict.format == ImageFormat.GIF)
    check("gif dimensions", gifVerdict.width == 640 && gifVerdict.height == 480)

    val bmpVerdict = PageBytesValidator.inspect(bmp(1024, 768))
    check("bmp named", bmpVerdict.format == ImageFormat.BMP)
    check("bmp dimensions", bmpVerdict.width == 1024 && bmpVerdict.height == 768)

    // A top-down BMP stores a negative height, read here as a large unsigned value; the magnitude
    // has to be taken before the range check or every top-down scan is rejected as garbage.
    val topDown = bmp(1024, 768).also {
        // -1024 as a little-endian i32: 0xFFFFFC00.
        it[22] = 0x00.toByte(); it[23] = 0xFC.toByte()
        it[24] = 0xFF.toByte(); it[25] = 0xFF.toByte()
    }
    val topDownVerdict = PageBytesValidator.inspect(topDown)
    check("top-down bmp accepted", topDownVerdict.isAcceptable)
    check("top-down bmp height is the magnitude", topDownVerdict.height == 1024)

    val webpVerdict = PageBytesValidator.inspect(webp(1200, 900))
    check("webp named", webpVerdict.format == ImageFormat.WEBP)
    check("webp dimensions", webpVerdict.width == 1200 && webpVerdict.height == 900)

    check("jxl container named", PageBytesValidator.inspect(jxlContainer()).format == ImageFormat.JXL)
    check("jxl raw codestream named", PageBytesValidator.inspect(bytes(0xFF, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).format == ImageFormat.JXL)

    check("avif named", PageBytesValidator.inspect(isoBmff("avif")).format == ImageFormat.AVIF)
    check("heif named", PageBytesValidator.inspect(isoBmff("heic")).format == ImageFormat.HEIF)

    check("tiff (little-endian) named", PageBytesValidator.inspect(bytes(0x49, 0x49, 0x2A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).format == ImageFormat.TIFF)
    check("tiff (big-endian) named", PageBytesValidator.inspect(bytes(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).format == ImageFormat.TIFF)
    check("ico named", PageBytesValidator.inspect(bytes(0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x10, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).format == ImageFormat.ICO)

    // A format whose dimensions are not cheaply reachable is named and left to the decoder - that
    // is not a fault, and reporting one would refuse a whole format.
    check("tiff with no dimensions is accepted", PageBytesValidator.inspect(
        bytes(0x49, 0x49, 0x2A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
    ).isAcceptable)

    // --- the failures it exists to name ---------------------------------------------------
    val html = padded("<!DOCTYPE html><html><body>403 Forbidden</body></html>".toByteArray())
    val htmlVerdict = PageBytesValidator.inspect(html)
    check("html body refused", !htmlVerdict.isAcceptable)
    check("html body named NOT_AN_IMAGE", htmlVerdict.fault == ImageBytesFault.NOT_AN_IMAGE)
    check("html body has no format", htmlVerdict.format == null)
    check("html body says what arrived", (htmlVerdict.detail ?: "").contains("3c 21 44 4f"))

    val empty = PageBytesValidator.inspect(ByteArray(0))
    check("empty body refused", empty.fault == ImageBytesFault.NOT_AN_IMAGE)
    check("empty body detail", empty.detail == "empty body")

    val short = PageBytesValidator.inspect(bytes(0xFF, 0xD8, 0xFF))
    check("three-byte body refused as too short", short.fault == ImageBytesFault.NOT_AN_IMAGE)
    check("short body says how short", (short.detail ?: "").contains("3 byte(s)"))

    val tiny = PageBytesValidator.inspect(png(8, 8))
    check("8x8 png refused", tiny.fault == ImageBytesFault.IMPLAUSIBLE_DIMENSIONS)
    check("8x8 png still names its format", tiny.format == ImageFormat.PNG)
    check("8x8 png keeps its dimensions for the log", tiny.width == 8 && tiny.height == 8)

    val tooWide = PageBytesValidator.inspect(png(50_001, 10))
    check("oversized width refused", tooWide.fault == ImageBytesFault.IMPLAUSIBLE_DIMENSIONS)

    val tooTall = PageBytesValidator.inspect(png(800, 200_001))
    check("oversized height refused", tooTall.fault == ImageBytesFault.IMPLAUSIBLE_DIMENSIONS)

    // A webtoon strip is legitimately tens of thousands of pixels tall, so the height ceiling is
    // far more generous than the width one - this is the case that must keep passing.
    val strip = PageBytesValidator.inspect(png(800, 61_000))
    check("61k-tall strip accepted", strip.isAcceptable)
    check("61k-tall strip measures", strip.height == 61_000)

    // --- robustness: garbage that must not throw or loop -----------------------------------
    val garbage = ByteArray(4096) { (it * 31 + 7).toByte() }
    val garbageVerdict = PageBytesValidator.inspect(garbage)
    check("random bytes refused", garbageVerdict.fault == ImageBytesFault.NOT_AN_IMAGE)

    // A JPEG whose marker walk runs off the end must terminate, not throw and not hang.
    val truncatedJpeg = bytes(0xFF, 0xD8, 0xFF, 0xDA, 0xFF, 0x00, 0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    check("truncated jpeg does not throw", runCatching { PageBytesValidator.inspect(truncatedJpeg) }.isSuccess)

    // A segment length of zero would walk forward by nothing; the scan limit has to stop it.
    val loopingJpeg = ByteArray(4096) { 0xFF.toByte() }
    loopingJpeg[0] = 0xFF.toByte(); loopingJpeg[1] = 0xD8.toByte()
    loopingJpeg[2] = 0xFF.toByte(); loopingJpeg[3] = 0xFE.toByte()
    for (i in 4 until loopingJpeg.size) loopingJpeg[i] = 0xFF.toByte()
    loopingJpeg[5] = 0x00.toByte(); loopingJpeg[6] = 0x02.toByte() // length 2, so the walk advances
    check("pathological jpeg terminates", runCatching { PageBytesValidator.inspect(loopingJpeg) }.isSuccess)

    // A declared length below 2 would otherwise let the walk resynchronise onto itself forever.
    val zeroLength = bytes(0xFF, 0xD8, 0xFF, 0xFE, 0x00, 0x00, 0xFF, 0xFE, 0x00, 0x00, 0xFF, 0xFE, 0x00, 0x00, 0xFF, 0xFE)
    check("zero-length segment terminates", runCatching { PageBytesValidator.inspect(zeroLength) }.isSuccess)

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}