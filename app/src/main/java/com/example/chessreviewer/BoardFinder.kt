package com.example.chessreviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.DeadObjectException
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Automatically localizes the 8x8 chessboard on a screen frame, including direct
 * capture & localization over the [ISecureShot] Binder interface.
 *
 * Works across edge-to-edge full-width Chess.com boards, Lichess boards, dark/light
 * themes, and cleanly rejects non-board UI screens (e.g., bot selection menus).
 */
object BoardFinder {

    data class BoardRect(
        val x: Int,
        val y: Int,
        val size: Int,
        val confidence: Int,
    )

    data class SecureFrameResult(
        val frame: Bitmap?,
        val boardRect: BoardRect?,
        val error: String = "",
    )

    private const val WORK_MAX_DIM = 260
    private const val SECURE_SHOT_CHUNK_BYTES = 256 * 1024

    /**
     * Captures a display frame through [ISecureShot] (`capture` + `chunk` + `lastError`)
     * and localizes the 8x8 chessboard on screen in a single pass.
     */
    fun findFromSecureShot(
        shot: ISecureShot,
        width: Int = 0,
        height: Int = 0,
    ): SecureFrameResult {
        val frame = captureBitmapFromSecureShot(shot, width, height)
            ?: return SecureFrameResult(
                frame = null,
                boardRect = null,
                error = runCatching { shot.lastError() }.getOrNull().orEmpty()
                    .ifBlank { "SecureShot capture returned no frame" },
            )
        val rect = find(frame)
        return SecureFrameResult(frame = frame, boardRect = rect, error = "")
    }

    /** Reads an encoded frame in chunks from [ISecureShot] and decodes it to a [Bitmap]. */
    fun captureBitmapFromSecureShot(
        shot: ISecureShot,
        width: Int = 0,
        height: Int = 0,
    ): Bitmap? {
        return try {
            val totalBytes = shot.capture(width, height)
            if (totalBytes <= 0) return null
            val out = ByteArrayOutputStream(totalBytes)
            var offset = 0
            while (offset < totalBytes) {
                val part = shot.chunk(offset, minOf(SECURE_SHOT_CHUNK_BYTES, totalBytes - offset))
                if (part == null || part.isEmpty()) break
                out.write(part)
                offset += part.size
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: DeadObjectException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun find(screen: Bitmap): BoardRect? {
        val fw = screen.width
        val fh = screen.height
        if (fw < 160 || fh < 160) return null

        val minDim = minOf(fw, fh)
        val scale = if (minDim > WORK_MAX_DIM) WORK_MAX_DIM.toFloat() / minDim.toFloat() else 1f
        val dw = (fw * scale).roundToInt().coerceAtLeast(64)
        val dh = (fh * scale).roundToInt().coerceAtLeast(64)

        val scaled = if (dw != fw || dh != fh) {
            Bitmap.createScaledBitmap(screen, dw, dh, true)
        } else {
            screen
        }

        val pixels = IntArray(dw * dh)
        scaled.getPixels(pixels, 0, dw, 0, 0, dw, dh)
        if (scaled !== screen) scaled.recycle()

        val smallRect = findInPixels(pixels, dw, dh) ?: return null

        val invScaleX = fw.toFloat() / dw.toFloat()
        val invScaleY = fh.toFloat() / dh.toFloat()
        val rawX = (smallRect.x * invScaleX).roundToInt()
        val rawY = (smallRect.y * invScaleY).roundToInt()
        val rawSize = (smallRect.size * ((invScaleX + invScaleY) * 0.5f)).roundToInt()
            .coerceIn(64, minOf(fw, fh))

        return refineAtFullRes(screen, rawX, rawY, rawSize, smallRect.confidence)
    }

    /** Pure pixel-array board finder (unit-testable without Android Bitmap). */
    fun findInPixels(px: IntArray, w: Int, h: Int): BoardRect? {
        if (w < 64 || h < 64 || px.size < w * h) return null

        val lum = IntArray(w * h)
        for (i in 0 until w * h) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            lum[i] = (r * 3 + g * 6 + b) / 10
        }

        // Prefix sums for vertical-neighbor transitions along horizontal rows
        val vRowPref = IntArray(h * (w + 1))
        for (y in 1 until h) {
            val rowOff = y * w
            val prevOff = (y - 1) * w
            val prefOff = y * (w + 1)
            var run = 0
            for (x in 0 until w) {
                if (abs(lum[rowOff + x] - lum[prevOff + x]) >= 10) run++
                vRowPref[prefOff + x + 1] = run
            }
        }

        // Prefix sums for horizontal-neighbor transitions along vertical columns
        val hColPref = IntArray(w * (h + 1))
        for (x in 1 until w) {
            val prefOff = x * (h + 1)
            var run = 0
            for (y in 0 until h) {
                val idx = y * w + x
                if (abs(lum[idx] - lum[idx - 1]) >= 10) run++
                hColPref[prefOff + y + 1] = run
            }
        }

        val minDim = minOf(w, h)
        val minBoard = (minDim * 0.52f).roundToInt().coerceAtLeast(48)

        data class Cand(val bx: Int, val by: Int, val size: Int, val gridScore: Int)
        val topCandidates = ArrayList<Cand>(32)
        val rowDensity = IntArray(h)

        // Include exact full-width (w) down to minBoard with fine steps so non-multiple-of-8 widths
        // (such as Chess.com full-screen boards scaled to WORK_MAX_DIM=260) fit seamlessly.
        val sizeList = LinkedHashSet<Int>()
        sizeList.add(minDim)
        if (minDim - 2 >= minBoard) sizeList.add(minDim - 2)
        if (minDim - 4 >= minBoard) sizeList.add(minDim - 4)
        var sWalk = minDim
        while (sWalk >= minBoard) {
            sizeList.add(sWalk)
            sWalk -= maxOf(4, minDim / 36)
        }

        for (boardSize in sizeList) {
            if (boardSize > w || boardSize > h) continue
            val sqF = boardSize / 8f
            val maxBx = w - boardSize

            val xCandidates = LinkedHashSet<Int>()
            xCandidates.add(0)
            xCandidates.add((maxBx / 2).coerceAtLeast(0))
            if (maxBx > 0) {
                val stepX = maxOf(2, (sqF / 4f).roundToInt())
                var x = 0
                while (x <= maxBx) {
                    xCandidates.add(x)
                    x += stepX
                }
                xCandidates.add(maxBx)
            }

            for (bx in xCandidates) {
                val xEnd = bx + boardSize
                for (y in 1 until h) {
                    val prefOff = y * (w + 1)
                    rowDensity[y] = vRowPref[prefOff + xEnd] - vRowPref[prefOff + bx]
                }

                val maxBy = h - boardSize
                val minHThreshold = (boardSize * 7 * 0.22f).toInt()
                var bestLocalBy = -1
                var bestLocalScore = 0

                for (by in 0..maxBy) {
                    var hScore = 0
                    for (k in 1..7) {
                        val lineY = (by + k * sqF).roundToInt().coerceIn(1, h - 1)
                        hScore += maxOf(
                            rowDensity[lineY],
                            if (lineY + 1 < h) rowDensity[lineY + 1] else 0,
                        )
                    }
                    if (hScore < minHThreshold) continue

                    var vScore = 0
                    for (k in 1..7) {
                        val lineX = (bx + k * sqF).roundToInt().coerceIn(1, w - 1)
                        val prefOff = lineX * (h + 1)
                        val prefOffNext = if (lineX + 1 < w) (lineX + 1) * (h + 1) else prefOff
                        val s0 = hColPref[prefOff + by + boardSize] - hColPref[prefOff + by]
                        val s1 = hColPref[prefOffNext + by + boardSize] - hColPref[prefOffNext + by]
                        vScore += maxOf(s0, s1)
                    }

                    val totalGrid = hScore + vScore
                    if (totalGrid > bestLocalScore) {
                        bestLocalScore = totalGrid
                        bestLocalBy = by
                    }
                }

                if (bestLocalBy >= 0) {
                    topCandidates.add(Cand(bx, bestLocalBy, boardSize, bestLocalScore))
                }
            }
        }

        // Direct fallback sweep for full-width & centered low-contrast boards
        val sweepSizes = listOf(minDim, ((w / 8) * 8).coerceAtLeast(minBoard)).distinct()
        for (boardSize in sweepSizes) {
            if (boardSize > w || boardSize > h) continue
            val bx = ((w - boardSize) / 2).coerceAtLeast(0)
            val stepY = maxOf(2, (boardSize / 24f).roundToInt())
            var by = 0
            while (by <= h - boardSize) {
                topCandidates.add(Cand(bx, by, boardSize, 0))
                by += stepY
            }
        }

        topCandidates.sortByDescending { it.gridScore }

        val toCheck = ArrayList<Cand>(44)
        for (c in topCandidates) {
            if (toCheck.size >= 40) break
            val duplicate = toCheck.any {
                abs(it.bx - c.bx) <= 3 && abs(it.by - c.by) <= 3 && abs(it.size - c.size) <= 6
            }
            if (!duplicate) toCheck.add(c)
        }

        var bestRect: BoardRect? = null
        var bestTotalScore = Int.MIN_VALUE

        for (cand in toCheck) {
            for (dy in -3..3) {
                for (dx in -2..2) {
                    val bx = (cand.bx + dx).coerceIn(0, w - cand.size)
                    val by = (cand.by + dy).coerceIn(0, h - cand.size)
                    val parity = checkerboardScore(px, lum, w, h, bx, by, cand.size) ?: continue
                    val score = parity + (cand.gridScore / 4) + cand.size / 3
                    if (score > bestTotalScore) {
                        bestTotalScore = score
                        bestRect = BoardRect(bx, by, cand.size, score)
                    }
                }
            }
        }

        return bestRect
    }

    /**
     * Validates all 64 squares for 2-color checkerboard corner parity + internal seam transitions.
     * Immune to center pieces and 1-6 highlighted squares, while strictly rejecting bot grids & menus.
     */
    private fun checkerboardScore(
        px: IntArray,
        lum: IntArray,
        w: Int,
        h: Int,
        bx: Int,
        by: Int,
        boardSize: Int,
    ): Int? {
        val sq = boardSize / 8f
        if (sq < 5f) return null

        val evenR = IntArray(32); val evenG = IntArray(32); val evenB = IntArray(32)
        val oddR = IntArray(32); val oddG = IntArray(32); val oddB = IntArray(32)
        var ei = 0
        var oi = 0

        val cr = IntArray(4); val cg = IntArray(4); val cb = IntArray(4)

        for (r in 0 until 8) {
            val y0 = (by + (r + 0.14f) * sq).toInt().coerceIn(0, h - 1)
            val y1 = (by + (r + 0.86f) * sq).toInt().coerceIn(0, h - 1)
            for (c in 0 until 8) {
                val x0 = (bx + (c + 0.14f) * sq).toInt().coerceIn(0, w - 1)
                val x1 = (bx + (c + 0.86f) * sq).toInt().coerceIn(0, w - 1)

                val pts = intArrayOf(
                    px[y0 * w + x0],
                    px[y0 * w + x1],
                    px[y1 * w + x0],
                    px[y1 * w + x1],
                )
                for (i in 0..3) {
                    cr[i] = (pts[i] shr 16) and 255
                    cg[i] = (pts[i] shr 8) and 255
                    cb[i] = pts[i] and 255
                }
                var bestPairDist = Int.MAX_VALUE
                var sr = cr[0]; var sg = cg[0]; var sb = cb[0]
                for (a in 0..2) {
                    for (b in a + 1..3) {
                        val d = abs(cr[a] - cr[b]) + abs(cg[a] - cg[b]) + abs(cb[a] - cb[b])
                        if (d < bestPairDist) {
                            bestPairDist = d
                            sr = (cr[a] + cr[b]) / 2
                            sg = (cg[a] + cg[b]) / 2
                            sb = (cb[a] + cb[b]) / 2
                        }
                    }
                }

                if ((r + c) and 1 == 0) {
                    evenR[ei] = sr; evenG[ei] = sg; evenB[ei] = sb
                    ei++
                } else {
                    oddR[oi] = sr; oddG[oi] = sg; oddB[oi] = sb
                    oi++
                }
            }
        }

        val eMedR = medianOf32(evenR); val eMedG = medianOf32(evenG); val eMedB = medianOf32(evenB)
        val oMedR = medianOf32(oddR); val oMedG = medianOf32(oddG); val oMedB = medianOf32(oddB)

        val contrast = abs(eMedR - oMedR) + abs(eMedG - oMedG) + abs(eMedB - oMedB)
        if (contrast < 36) return null

        var matchEven = 0
        var matchOdd = 0
        for (i in 0 until 32) {
            if (abs(evenR[i] - eMedR) + abs(evenG[i] - eMedG) + abs(evenB[i] - eMedB) <= 36) {
                matchEven++
            }
            if (abs(oddR[i] - oMedR) + abs(oddG[i] - oMedG) + abs(oddB[i] - oMedB) <= 36) {
                matchOdd++
            }
        }

        if (matchEven < 26 || matchOdd < 26) return null

        // Verify actual square-seam color transitions across the 7 vertical & 7 horizontal seams
        var seamHits = 0
        for (k in 1..7) {
            val sx = (bx + k * sq).roundToInt().coerceIn(2, w - 3)
            var vJump = 0
            for (r in 0 until 8) {
                val sy = (by + (r + 0.16f) * sq).roundToInt().coerceIn(0, h - 1)
                if (abs(lum[sy * w + sx + 1] - lum[sy * w + sx - 2]) >= 12) vJump++
            }
            if (vJump >= 5) seamHits++

            val sy = (by + k * sq).roundToInt().coerceIn(2, h - 3)
            var hJump = 0
            for (c in 0 until 8) {
                val sx2 = (bx + (c + 0.16f) * sq).roundToInt().coerceIn(0, w - 1)
                if (abs(lum[(sy + 1) * w + sx2] - lum[(sy - 2) * w + sx2]) >= 12) hJump++
            }
            if (hJump >= 5) seamHits++
        }
        if (seamHits < 9) return null

        return (matchEven + matchOdd) * 140 + seamHits * 110 + minOf(contrast, 260)
    }

    private fun medianOf32(arr: IntArray): Int {
        val copy = arr.copyOf()
        copy.sort()
        return (copy[15] + copy[16]) / 2
    }

    /** Snaps candidate rectangle edges to exact pixel boundaries at full display resolution. */
    private fun refineAtFullRes(
        screen: Bitmap,
        approxX: Int,
        approxY: Int,
        approxSize: Int,
        baseConf: Int,
    ): BoardRect {
        val fw = screen.width
        val fh = screen.height
        val minDim = minOf(fw, fh)
        val padX = 28
        val padY = 28
        val rx0 = (approxX - padX).coerceAtLeast(0)
        val ry0 = (approxY - padY).coerceAtLeast(0)
        val rx1 = (approxX + approxSize + padX).coerceAtMost(fw)
        val ry1 = (approxY + approxSize + padY).coerceAtMost(fh)
        val rw = rx1 - rx0
        val rh = ry1 - ry0
        if (rw < 64 || rh < 64) {
            return BoardRect(
                approxX.coerceIn(0, (fw - approxSize).coerceAtLeast(0)),
                approxY.coerceIn(0, (fh - approxSize).coerceAtLeast(0)),
                approxSize,
                baseConf,
            )
        }

        val crop = IntArray(rw * rh)
        screen.getPixels(crop, 0, rw, rx0, ry0, rw, rh)

        val candSizes = LinkedHashSet<Int>()
        if (approxSize >= minDim * 0.94f) {
            candSizes.add(minDim)
            val snappedMin = (minDim / 8) * 8
            if (snappedMin >= 64) candSizes.add(snappedMin)
        }
        for (ds in -20..20 step 4) {
            val s = approxSize + ds
            if (s in 64..minOf(rw, rh)) candSizes.add(s)
        }

        var bestX = (approxX - rx0).coerceIn(0, (rw - approxSize).coerceAtLeast(0))
        var bestY = (approxY - ry0).coerceIn(0, (rh - approxSize).coerceAtLeast(0))
        var bestSize = approxSize.coerceIn(64, minOf(rw, rh))
        var bestEdge = Int.MIN_VALUE

        // Coarse pass (3px step)
        for (s in candSizes) {
            val maxY = rh - s
            val maxX = rw - s
            for (dy in -20..20 step 3) {
                val y = (approxY - ry0 + dy).coerceIn(0, maxY)
                for (dx in -12..12 step 3) {
                    val x = (approxX - rx0 + dx).coerceIn(0, maxX)
                    val score = interiorGridEdgeScore(crop, rw, rh, x, y, s)
                    if (score > bestEdge) {
                        bestEdge = score
                        bestX = x
                        bestY = y
                        bestSize = s
                    }
                }
            }
        }

        // Fine 1px snap pass around the best coarse candidate
        val centerX = bestX
        val centerY = bestY
        val centerS = bestSize
        for (ds in -3..3) {
            val s = centerS + ds
            if (s < 64 || s > minOf(rw, rh)) continue
            for (dy in -3..3) {
                val y = (centerY + dy).coerceIn(0, rh - s)
                for (dx in -3..3) {
                    val x = (centerX + dx).coerceIn(0, rw - s)
                    val score = interiorGridEdgeScore(crop, rw, rh, x, y, s)
                    if (score > bestEdge) {
                        bestEdge = score
                        bestX = x
                        bestY = y
                        bestSize = s
                    }
                }
            }
        }

        val finalSize = bestSize.coerceIn(64, minDim)
        val finalX = (rx0 + bestX).coerceIn(0, (fw - finalSize).coerceAtLeast(0))
        val finalY = (ry0 + bestY).coerceIn(0, (fh - finalSize).coerceAtLeast(0))
        return BoardRect(finalX, finalY, finalSize, baseConf)
    }

    private fun interiorGridEdgeScore(
        px: IntArray,
        rw: Int,
        rh: Int,
        bx: Int,
        by: Int,
        s: Int,
    ): Int {
        var score = 0
        val sampleStep = maxOf(3, s / 42)
        val sqF = s / 8f
        for (k in 1..7) {
            val gx = (bx + k * sqF).roundToInt().coerceIn(1, rw - 2)
            val gy = (by + k * sqF).roundToInt().coerceIn(1, rh - 2)
            var y = by + 6
            while (y < by + s - 6) {
                val p0 = px[y * rw + gx - 1]
                val p1 = px[y * rw + gx + 1]
                score += abs(((p1 shr 16) and 255) - ((p0 shr 16) and 255)) +
                    abs(((p1 shr 8) and 255) - ((p0 shr 8) and 255)) +
                    abs((p1 and 255) - (p0 and 255))
                y += sampleStep
            }
            var x = bx + 6
            while (x < bx + s - 6) {
                val p0 = px[(gy - 1) * rw + x]
                val p1 = px[(gy + 1) * rw + x]
                score += abs(((p1 shr 16) and 255) - ((p0 shr 16) and 255)) +
                    abs(((p1 shr 8) and 255) - ((p0 shr 8) and 255)) +
                    abs((p1 and 255) - (p0 and 255))
                x += sampleStep
            }
        }
        // Slight bonus for top/bottom boundary edge transitions cleanly framing row 0 & row 7
        if (by >= 2 && by + s + 2 < rh) {
            var x = bx + 10
            while (x < bx + s - 10) {
                val tOut = px[(by - 2) * rw + x]
                val tIn = px[(by + 3) * rw + x]
                score += (abs(((tIn shr 16) and 255) - ((tOut shr 16) and 255)) +
                    abs(((tIn shr 8) and 255) - ((tOut shr 8) and 255)) +
                    abs((tIn and 255) - (tOut and 255))) / 3
                val bIn = px[(by + s - 3) * rw + x]
                val bOut = px[(by + s + 2) * rw + x]
                score += (abs(((bIn shr 16) and 255) - ((bOut shr 16) and 255)) +
                    abs(((bIn shr 8) and 255) - ((bOut shr 8) and 255)) +
                    abs((bIn and 255) - (bOut and 255))) / 3
                x += sampleStep * 2
            }
        }
        return score
    }
}
