package com.example.chessreviewer

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Automatically locates the 8x8 chessboard rectangle on a full-screen screenshot.
 * Works across Chess.com, Lichess, Chess24, web boards, dark/light themes, and any screen size.
 */
object BoardFinder {

    data class BoardRect(
        val x: Int,
        val y: Int,
        val size: Int,
        val confidence: Int,
    )

    private const val WORK_MAX_DIM = 260

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

        // Fast luminance array
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
        val minSq = ((minDim * 0.48f) / 8f).toInt().coerceAtLeast(6)
        val maxSq = (minDim / 8).coerceAtLeast(minSq)

        data class Cand(val bx: Int, val by: Int, val size: Int, val gridScore: Int)
        val topCandidates = ArrayList<Cand>(16)

        val rowDensity = IntArray(h)

        for (sq in maxSq downTo minSq) {
            val boardSize = sq * 8
            if (boardSize > w || boardSize > h) continue

            val maxBx = w - boardSize
            val xCandidates = LinkedHashSet<Int>()
            val stepX = maxOf(1, minOf(4, sq / 5))
            var x = 0
            while (x <= maxBx) {
                xCandidates.add(x)
                x += stepX
            }
            xCandidates.add((maxBx / 2).coerceAtLeast(0))
            if (maxBx > 0) xCandidates.add(maxBx)

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
                        val lineY = by + k * sq
                        hScore += maxOf(
                            rowDensity[lineY],
                            if (lineY + 1 < h) rowDensity[lineY + 1] else 0,
                        )
                    }
                    if (hScore < minHThreshold) continue

                    var vScore = 0
                    for (k in 1..7) {
                        val lineX = bx + k * sq
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

        // Also check clean centered / full-width candidates directly in case board has very low border contrast
        for (sq in maxSq downTo minSq) {
            val boardSize = sq * 8
            val bx = ((w - boardSize) / 2).coerceAtLeast(0)
            val stepY = maxOf(2, sq / 3)
            var by = 0
            while (by <= h - boardSize) {
                topCandidates.add(Cand(bx, by, boardSize, 0))
                by += stepY
            }
        }

        topCandidates.sortByDescending { it.gridScore }

        var bestRect: BoardRect? = null
        var bestTotalScore = Int.MIN_VALUE

        // Evaluate top grid-scored candidates + fallback evenly-spaced candidates
        val toCheck = ArrayList<Cand>(40)
        for (c in topCandidates) {
            if (toCheck.size >= 36) break
            val duplicate = toCheck.any {
                abs(it.bx - c.bx) <= 3 && abs(it.by - c.by) <= 3 && abs(it.size - c.size) <= 6
            }
            if (!duplicate) toCheck.add(c)
        }

        for (cand in toCheck) {
            // Check slight offsets around candidate for exact grid snap
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val bx = (cand.bx + dx).coerceIn(0, w - cand.size)
                    val by = (cand.by + dy).coerceIn(0, h - cand.size)
                    val parity = checkerboardScore(px, w, bx, by, cand.size) ?: continue
                    val score = parity + (cand.gridScore / 4) + cand.size / 4
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
     * Checks 64 squares for 2-color checkerboard corner parity (immune to center pieces and 1-6 highlighted squares).
     * Returns positive score if valid chessboard, or null if rejected.
     */
    private fun checkerboardScore(
        px: IntArray,
        w: Int,
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
            val y0 = (by + (r + 0.14f) * sq).toInt().coerceAtLeast(0)
            val y1 = (by + (r + 0.86f) * sq).toInt().coerceAtLeast(0)
            for (c in 0 until 8) {
                val x0 = (bx + (c + 0.14f) * sq).toInt().coerceAtLeast(0)
                val x1 = (bx + (c + 0.86f) * sq).toInt().coerceAtLeast(0)

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
                // Pick the two closest corner pixels (avoids piece/label corner outlier)
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
        if (contrast < 32) return null

        var matchEven = 0
        var matchOdd = 0
        for (i in 0 until 32) {
            if (abs(evenR[i] - eMedR) + abs(evenG[i] - eMedG) + abs(evenB[i] - eMedB) <= 38) {
                matchEven++
            }
            if (abs(oddR[i] - oMedR) + abs(oddG[i] - oMedG) + abs(oddB[i] - oMedB) <= 38) {
                matchOdd++
            }
        }

        if (matchEven < 25 || matchOdd < 25) return null
        return (matchEven + matchOdd) * 140 + minOf(contrast, 260)
    }

    private fun medianOf32(arr: IntArray): Int {
        val copy = arr.copyOf()
        copy.sort()
        return (copy[15] + copy[16]) / 2
    }

    /** Snaps the candidate rectangle edges to exact pixel boundaries at full resolution. */
    private fun refineAtFullRes(
        screen: Bitmap,
        approxX: Int,
        approxY: Int,
        approxSize: Int,
        baseConf: Int,
    ): BoardRect {
        val fw = screen.width
        val fh = screen.height
        val pad = 12
        val rx0 = (approxX - pad).coerceAtLeast(0)
        val ry0 = (approxY - pad).coerceAtLeast(0)
        val rx1 = (approxX + approxSize + pad).coerceAtMost(fw)
        val ry1 = (approxY + approxSize + pad).coerceAtMost(fh)
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

        var bestX = approxX - rx0
        var bestY = approxY - ry0
        var bestSize = approxSize
        var bestEdge = Int.MIN_VALUE

        for (ds in -6..6 step 2) {
            val s = approxSize + ds
            if (s < 64 || s > minOf(rw, rh)) continue
            for (dy in -6..6 step 2) {
                val y = (approxY - ry0 + dy).coerceIn(0, rh - s)
                for (dx in -6..6 step 2) {
                    val x = (approxX - rx0 + dx).coerceIn(0, rw - s)
                    val score = interiorGridEdgeScore(crop, rw, x, y, s)
                    if (score > bestEdge) {
                        bestEdge = score
                        bestX = x
                        bestY = y
                        bestSize = s
                    }
                }
            }
        }

        // Round size to nearest multiple of 8 for clean square alignment
        val alignedSize = ((bestSize + 4) / 8 * 8).coerceIn(64, minOf(fw, fh))
        val finalX = (rx0 + bestX).coerceIn(0, (fw - alignedSize).coerceAtLeast(0))
        val finalY = (ry0 + bestY).coerceIn(0, (fh - alignedSize).coerceAtLeast(0))
        return BoardRect(finalX, finalY, alignedSize, baseConf)
    }

    private fun interiorGridEdgeScore(px: IntArray, rw: Int, bx: Int, by: Int, s: Int): Int {
        var score = 0
        val sampleStep = maxOf(2, s / 48)
        for (k in 1..7) {
            val gx = bx + k * s / 8
            val gy = by + k * s / 8
            var y = by + 4
            while (y < by + s - 4) {
                val p0 = px[y * rw + (gx - 1).coerceAtLeast(0)]
                val p1 = px[y * rw + (gx + 1).coerceAtMost(rw - 1)]
                score += abs(((p1 shr 16) and 255) - ((p0 shr 16) and 255)) +
                    abs(((p1 shr 8) and 255) - ((p0 shr 8) and 255)) +
                    abs((p1 and 255) - (p0 and 255))
                y += sampleStep
            }
            var x = bx + 4
            while (x < bx + s - 4) {
                val p0 = px[((gy - 1).coerceAtLeast(0)) * rw + x]
                val p1 = px[(gy + 1) * rw + x]
                score += abs(((p1 shr 16) and 255) - ((p0 shr 16) and 255)) +
                    abs(((p1 shr 8) and 255) - ((p0 shr 8) and 255)) +
                    abs((p1 and 255) - (p0 and 255))
                x += sampleStep
            }
        }
        return score
    }
}
