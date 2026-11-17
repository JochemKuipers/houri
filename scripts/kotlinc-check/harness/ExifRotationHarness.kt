// Runs the REAL `rotateRgbaForExif` (compiled straight from app/.../WebGpuExif.kt) against every
// orientation, and asserts the properties that have to hold rather than a second copy of the
// index algebra.
//
// EXIF rotation is the quietest failure in the decode path: a wrong mapping produces a page that
// still renders, still has the right pixels, and is mirrored or transposed - so a reader sees a
// scan that "looks odd" and nothing anywhere says why. Each orientation's formula is documented in
// the source; this pins the invariants (pixels preserved, dimensions swapped for 5-8, four
// quarter-turns are the identity) plus hand-computed corner pixels, which is what would catch a
// sign flip in one of the four loops.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.rotateRgbaForExif
import java.nio.ByteBuffer

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

/** A [width]x[height] RGBA buffer whose pixel (x, y) is the Int `y * width + x`. */
private fun gradient(width: Int, height: Int): ByteBuffer {
    val buf = ByteBuffer.allocateDirect(width * height * 4)
    val ints = buf.asIntBuffer()
    for (y in 0 until height) {
        for (x in 0 until width) {
            ints.put(y * width + x)
        }
    }
    buf.rewind()
    return buf
}

private fun pixelAt(buf: ByteBuffer, width: Int, x: Int, y: Int): Int {
    val copy = buf.duplicate()
    val ints = copy.asIntBuffer()
    return ints.get(y * width + x)
}

/** Every pixel value in [buf], as a sorted list - used to prove no pixel was dropped or invented. */
private fun pixelMultiset(buf: ByteBuffer, width: Int, height: Int): List<Int> {
    val copy = buf.duplicate()
    val ints = copy.asIntBuffer()
    val out = ArrayList<Int>(width * height)
    for (i in 0 until width * height) out.add(ints.get(i))
    return out.sorted()
}

private fun sameMultiset(a: ByteBuffer, aw: Int, ah: Int, b: ByteBuffer, bw: Int, bh: Int): Boolean =
    pixelMultiset(a, aw, ah) == pixelMultiset(b, bw, bh)

fun main() {
    val w = 4
    val h = 3
    val src = gradient(w, h)

    // --- 1 and out-of-range: the buffer comes back untouched -------------------------------
    val identity = rotateRgbaForExif(src, w, h, 1)
    check("orientation 1 returns the same buffer", identity.buffer === src)
    check("orientation 1 keeps dimensions", identity.width == w && identity.height == h)

    for (bad in intArrayOf(0, -1, 9, 99, Int.MIN_VALUE)) {
        val r = rotateRgbaForExif(src, w, h, bad)
        check("orientation $bad returns the same buffer", r.buffer === src)
        check("orientation $bad keeps dimensions", r.width == w && r.height == h)
    }

    // --- degenerate geometry is a noop, never a crash or a zero-sized upload ---------------
    val zero = rotateRgbaForExif(src, 0, 0, 6)
    check("zero width is a noop", zero.buffer === src && zero.width == 0 && zero.height == 0)

    // A buffer too short for the declared geometry must not read past its end.
    val short = ByteBuffer.allocateDirect(4 * 4)
    val shortResult = rotateRgbaForExif(short, w, h, 6)
    check("short buffer is a noop", shortResult.buffer === short)

    // --- 2: horizontal mirror --------------------------------------------------------------
    val flipH = rotateRgbaForExif(src, w, h, 2)
    check("orientation 2 keeps dimensions", flipH.width == w && flipH.height == h)
    check("orientation 2 mirrors row 0", pixelAt(flipH.buffer, w, 0, 0) == 3 && pixelAt(flipH.buffer, w, 3, 0) == 0)
    check("orientation 2 mirrors row 1", pixelAt(flipH.buffer, w, 0, 1) == 7 && pixelAt(flipH.buffer, w, 3, 1) == 4)
    check("orientation 2 is an involution", rotateRgbaForExif(flipH.buffer, w, h, 2).buffer.let {
        pixelAt(it, w, 0, 0) == 0 && pixelAt(it, w, 3, 0) == 3
    })

    // --- 3: 180 degrees ---------------------------------------------------------------------
    val rot180 = rotateRgbaForExif(src, w, h, 3)
    check("orientation 3 keeps dimensions", rot180.width == w && rot180.height == h)
    check("orientation 3 reverses the whole buffer", pixelAt(rot180.buffer, w, 0, 0) == w * h - 1)
    check("orientation 3 lands the last pixel last", pixelAt(rot180.buffer, w, w - 1, h - 1) == 0)
    check("orientation 3 is an involution", rotateRgbaForExif(rot180.buffer, w, h, 3).buffer.let {
        pixelAt(it, w, 0, 0) == 0 && pixelAt(it, w, w - 1, h - 1) == w * h - 1
    })

    // --- 4: vertical mirror -----------------------------------------------------------------
    val flipV = rotateRgbaForExif(src, w, h, 4)
    check("orientation 4 keeps dimensions", flipV.width == w && flipV.height == h)
    check("orientation 4 flips rows", pixelAt(flipV.buffer, w, 0, 0) == (h - 1) * w)
    check("orientation 4 flips columns within a row", pixelAt(flipV.buffer, w, 3, 0) == (h - 1) * w + 3)
    check("orientation 4 is an involution", rotateRgbaForExif(flipV.buffer, w, h, 4).buffer.let {
        pixelAt(it, w, 0, 0) == 0
    })

    // --- 5..8: axis swaps -------------------------------------------------------------------
    for (o in 5..8) {
        val r = rotateRgbaForExif(src, w, h, o)
        check("orientation $o swaps dimensions", r.width == h && r.height == w)
        check("orientation $o preserves every pixel", sameMultiset(src, w, h, r.buffer, r.width, r.height))
    }

    // Hand-computed from the documented formula, so a sign flip in one loop cannot hide behind
    // "the multiset is unchanged".
    // 5: dst(dx, dy) = src(dy, dx)  -> dst(0,0) = src(0,0)
    val o5 = rotateRgbaForExif(src, w, h, 5)
    check("orientation 5 first pixel", pixelAt(o5.buffer, o5.width, 0, 0) == 0)
    // 6: dst(dx, dy) = src(dy, height-1-dx) -> dst(0,0) = src(0, height-1)
    val o6 = rotateRgbaForExif(src, w, h, 6)
    check("orientation 6 first pixel", pixelAt(o6.buffer, o6.width, 0, 0) == (h - 1) * w)
    check("orientation 6 first row is the left column, bottom-up", listOf(
        pixelAt(o6.buffer, o6.width, 0, 0),
        pixelAt(o6.buffer, o6.width, 1, 0),
        pixelAt(o6.buffer, o6.width, 2, 0),
    ) == listOf((h - 1) * w, (h - 2) * w, (h - 3) * w))
    // 7: dst(dx, dy) = src(width-1-dy, height-1-dx) -> dst(0,0) = src(width-1, height-1)
    val o7 = rotateRgbaForExif(src, w, h, 7)
    check("orientation 7 first pixel", pixelAt(o7.buffer, o7.width, 0, 0) == w * h - 1)
    // 8: dst(dx, dy) = src(width-1-dy, dx) -> dst(0,0) = src(width-1, 0)
    val o8 = rotateRgbaForExif(src, w, h, 8)
    check("orientation 8 first pixel", pixelAt(o8.buffer, o8.width, 0, 0) == w - 1)

    // Four clockwise quarter-turns are the identity - the cheapest end-to-end proof that the
    // source and destination index steps in rotateQuarterTurn agree.
    var turned = src
    var tw = w
    var th = h
    for (i in 0 until 4) {
        val r = rotateRgbaForExif(turned, tw, th, 6)
        turned = r.buffer
        tw = r.width
        th = r.height
    }
    check("four 90-degree turns restore the geometry", tw == w && th == h)
    check("four 90-degree turns restore every pixel", sameMultiset(src, w, h, turned, tw, th))
    check("four 90-degree turns restore pixel 0", pixelAt(turned, tw, 0, 0) == 0)
    check("four 90-degree turns restore the last pixel", pixelAt(turned, tw, w - 1, h - 1) == w * h - 1)

    // Composition pins the index algebra in a way the multiset check cannot: the four mirrored
    // quarter-turns pair up as two involutions and two mutual inverses. If any loop's source or
    // destination step were off, one of these would move a pixel.
    val o5Twice = rotateRgbaForExif(o5.buffer, o5.width, o5.height, 5)
    check("5 is an involution: geometry", o5Twice.width == w && o5Twice.height == h)
    check("5 is an involution: pixel 0", pixelAt(o5Twice.buffer, w, 0, 0) == 0)
    check("5 is an involution: last pixel", pixelAt(o5Twice.buffer, w, w - 1, h - 1) == w * h - 1)

    val o7Twice = rotateRgbaForExif(o7.buffer, o7.width, o7.height, 7)
    check("7 is an involution: geometry", o7Twice.width == w && o7Twice.height == h)
    check("7 is an involution: pixel 0", pixelAt(o7Twice.buffer, w, 0, 0) == 0)
    check("7 is an involution: last pixel", pixelAt(o7Twice.buffer, w, w - 1, h - 1) == w * h - 1)

    // 6 (clockwise) and 8 (counter-clockwise) are inverses, so either order is the identity.
    val o6ThenO8 = rotateRgbaForExif(o6.buffer, o6.width, o6.height, 8)
    check("6 then 8 restores pixel 0", pixelAt(o6ThenO8.buffer, w, 0, 0) == 0)
    check("6 then 8 restores the last pixel", pixelAt(o6ThenO8.buffer, w, w - 1, h - 1) == w * h - 1)
    val o8ThenO6 = rotateRgbaForExif(o8.buffer, o8.width, o8.height, 6)
    check("8 then 6 restores pixel 0", pixelAt(o8ThenO6.buffer, w, 0, 0) == 0)
    check("8 then 6 restores the last pixel", pixelAt(o8ThenO6.buffer, w, w - 1, h - 1) == w * h - 1)
    check("8 then 6 restores every pixel", sameMultiset(o8ThenO6.buffer, w, h, src, w, h))

    // And the pair that must NOT be the identity, so a broken dispatch that always returned the
    // source would fail something: 6 then 6 is a half turn.
    val o6Twice = rotateRgbaForExif(o6.buffer, o6.width, o6.height, 6)
    check("6 then 6 is a half turn, not the identity", pixelAt(o6Twice.buffer, w, 0, 0) == w * h - 1)

    // --- a single pixel and a single row/column must survive too ----------------------------
    val one = gradient(1, 1)
    val oneTurn = rotateRgbaForExif(one, 1, 1, 6)
    check("1x1 survives a turn", oneTurn.width == 1 && oneTurn.height == 1)
    check("1x1 keeps its pixel", pixelAt(oneTurn.buffer, 1, 0, 0) == 0)

    val row = gradient(5, 1)
    val rowTurn = rotateRgbaForExif(row, 5, 1, 6)
    check("a 5x1 row turns into a 1x5 column", rowTurn.width == 1 && rowTurn.height == 5)
    check("a 5x1 row keeps every pixel", sameMultiset(row, 5, 1, rowTurn.buffer, 1, 5))
    check("a 5x1 row turns clockwise", pixelAt(rowTurn.buffer, 1, 0, 0) == 0 && pixelAt(rowTurn.buffer, 1, 0, 4) == 4)

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}