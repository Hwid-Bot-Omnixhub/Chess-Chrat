package com.example.chessreviewer

import kotlin.math.abs

class Templates(val tpl: Array<FloatArray>, val lumThreshold: Float, val occThreshold: Float) {
    fun serialize(): String =
        "$lumThreshold,$occThreshold\n" + tpl.joinToString("\n") { t -> t.joinToString(",") }

    companion object {
        fun parse(s: String): Templates? {
            return try {
                val lines = s.split("\n")
                if (lines.size != 7) return null
                val head = lines[0].split(",")
                if (head.size != 2) return null
                val t = Array(6) { i -> lines[i + 1].split(",").map { it.toFloat() }.toFloatArray() }
                if (t.any { it.size != Vision.G * Vision.G }) null
                else Templates(t, head[0].toFloat(), head[1].toFloat())
            } catch (_: Exception) {
                null
            }
        }
    }
}

class LearnResult(val templates: Templates?, val whiteBottom: Boolean, val error: String?)

/**
 * Turns a screenshot of the board into a position.
 * For every square: find pixels that differ from that square's own background colour, fill holes to get the
 * piece silhouette, and compare it with templates learned from the starting position.
 */
object Vision {
    const val N = 40          // pixels per square after downscaling
    const val B = N * 8       // board size after downscaling
    const val G = N / 2       // feature grid per square
    private const val DIFF = 60

    /** why the last readPosition() returned null (shown to the user) */
    @Volatile
    var lastProblem: String = ""

    /** score = how "piece-like" the square is (higher = more likely a piece is on it) */
    class Sq(val score: Float, val feat: FloatArray, val lum: Float)

    /** Box-average a square s x s board down to B x B. */
    fun downscale(src: IntArray, s: Int): IntArray {
        val out = IntArray(B * B)
        for (y in 0 until B) {
            val y0 = y * s / B
            val y1 = maxOf(y0 + 1, (y + 1) * s / B)
            for (x in 0 until B) {
                val x0 = x * s / B
                val x1 = maxOf(x0 + 1, (x + 1) * s / B)
                var r = 0; var g = 0; var b = 0; var n = 0
                for (yy in y0 until y1) {
                    val row = yy * s
                    for (xx in x0 until x1) {
                        val p = src[row + xx]
                        r += (p shr 16) and 255
                        g += (p shr 8) and 255
                        b += p and 255
                        n++
                    }
                }
                out[y * B + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }

    fun analyzeSquare(px: IntArray, row: Int, col: Int): Sq {
        val ox = col * N
        val oy = row * N

        // background colour = median of small patches in the four corners
        val rs = IntArray(36); val gs = IntArray(36); val bs = IntArray(36)
        var k = 0
        val starts = intArrayOf(1, N - 4)
        for (sy in starts) for (sx in starts) for (dy in 0 until 3) for (dx in 0 until 3) {
            val p = px[(oy + sy + dy) * B + ox + sx + dx]
            rs[k] = (p shr 16) and 255
            gs[k] = (p shr 8) and 255
            bs[k] = p and 255
            k++
        }
        rs.sort(); gs.sort(); bs.sort()
        val br = rs[18]; val bg = gs[18]; val bb = bs[18]

        val mask = BooleanArray(N * N)
        var energy = 0
        for (y in 0 until N) for (x in 0 until N) {
            val p = px[(oy + y) * B + ox + x]
            val d = abs(((p shr 16) and 255) - br) + abs(((p shr 8) and 255) - bg) + abs((p and 255) - bb)
            mask[y * N + x] = d > DIFF
            if (x in 10 until 30 && y in 10 until 30) energy += minOf(d, 200)
        }

        // flood-fill the background from the border; whatever isn't reached is part of the piece
        val reached = BooleanArray(N * N)
        val queue = IntArray(N * N)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!mask[i] && !reached[i]) { reached[i] = true; queue[tail] = i; tail++ }
        }
        for (i in 0 until N) { push(i); push((N - 1) * N + i); push(i * N); push(i * N + N - 1) }
        while (head < tail) {
            val i = queue[head]; head++
            val y = i / N; val x = i % N
            if (y > 0) push(i - N)
            if (y < N - 1) push(i + N)
            if (x > 0) push(i - 1)
            if (x < N - 1) push(i + 1)
        }
        val sil = BooleanArray(N * N) { mask[it] || !reached[it] }

        var total = 0
        for (i in 0 until N * N) if (sil[i]) total++
        val score = total + energy / 20f

        val feat = FloatArray(G * G)
        for (gy in 0 until G) for (gx in 0 until G) {
            var c = 0
            for (dy in 0..1) for (dx in 0..1) if (sil[(gy * 2 + dy) * N + gx * 2 + dx]) c++
            feat[gy * G + gx] = c / 4f
        }

        var ls = 0f
        var ln = 0
        for (y in 10 until 30) for (x in 10 until 30) {
            if (sil[y * N + x]) {
                val p = px[(oy + y) * B + ox + x]
                ls += 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
                ln++
            }
        }
        val lum = if (ln > 0) ls / ln else 0f
        return Sq(score, feat, lum)
    }

    /** Learn piece shapes from a screenshot of the starting position. */
    fun learn(px: IntArray): LearnResult {
        val sq = Array(64) { analyzeSquare(px, it / 8, it % 8) }

        // we know exactly 32 squares hold pieces: take the 32 most piece-like squares and compare with the start layout
        val order = (0 until 64).sortedByDescending { sq[it].score }
        val top = order.take(32).toSet()
        val missing = ArrayList<Int>()
        val extra = ArrayList<Int>()
        for (i in 0 until 64) {
            val r = i / 8
            val expected = r <= 1 || r >= 6
            val seen = i in top
            if (expected && !seen) missing.add(i)
            if (!expected && seen) extra.add(i)
        }
        // at most 1 noisy/selected starting square is tolerated; 1 missing + 1 extra (= 2) means a move was already played
        if (missing.size + extra.size > 1) {
            fun lbl(list: List<Int>): String =
                list.take(6).joinToString(", ") { "col ${it % 8 + 1}/row ${it / 8 + 1}" }
            var msg = "Start position not recognised."
            if (missing.isNotEmpty()) msg += " Can't see a piece at: " + lbl(missing) + "."
            if (extra.isNotEmpty()) msg += " Saw something on an empty square at: " + lbl(extra) + "."
            msg += " Make sure the 8x8 frame is aligned on a starting position with no pop-ups covering it."
            return LearnResult(null, true, msg)
        }

        val use = BooleanArray(64)
        for (i in 0 until 64) {
            val r = i / 8
            use[i] = (r <= 1 || r >= 6) && (i in top)
        }

        var minGood = Float.MAX_VALUE
        var maxEmpty = 0f
        for (i in 0 until 64) {
            val r = i / 8
            val expected = r <= 1 || r >= 6
            if (use[i]) minGood = minOf(minGood, sq[i].score)
            else if (!expected && i !in top) maxEmpty = maxOf(maxEmpty, sq[i].score)
        }
        val occ = if (minGood > maxEmpty) (minGood + maxEmpty) / 2f else minGood * 0.85f

        fun avg(rows: IntRange): Float {
            var s = 0f
            var n = 0
            for (r in rows) for (c in 0 until 8) {
                if (use[r * 8 + c]) { s += sq[r * 8 + c].lum; n++ }
            }
            return if (n > 0) s / n else 0f
        }
        val bottomLum = avg(6..7)
        val topLum = avg(0..1)
        val whiteBottom = bottomLum > topLum
        val whiteLum = if (whiteBottom) bottomLum else topLum
        val blackLum = if (whiteBottom) topLum else bottomLum
        if (whiteLum - blackLum < 30f) {
            return LearnResult(null, true, "Can't tell white pieces from black pieces (colours too similar). Try a higher-contrast piece theme.")
        }

        val backW = if (whiteBottom) "RNBQKBNR" else "RNBKQBNR"
        val backB = if (whiteBottom) "rnbqkbnr" else "rnbkqbnr"

        val sum = Array(6) { FloatArray(G * G) }
        val cnt = IntArray(6)
        for (i in 0 until 64) {
            val r = i / 8
            val c = i % 8
            if (r in 2..5) continue
            if (!use[i]) continue
            val ch: Char =
                if (r == 0) (if (whiteBottom) backB[c] else backW[c])
                else if (r == 1) (if (whiteBottom) 'p' else 'P')
                else if (r == 6) (if (whiteBottom) 'P' else 'p')
                else (if (whiteBottom) backW[c] else backB[c])
            val t = "pnbrqk".indexOf(ch.lowercaseChar())
            val f = sq[i].feat
            for (j in f.indices) sum[t][j] += f[j]
            cnt[t]++
        }
        for (t in 0 until 6) if (cnt[t] == 0) {
            return LearnResult(null, true, "Couldn't learn every piece type. Reset the board to the start position and try again.")
        }
        val tpl = Array(6) { t -> FloatArray(G * G) { j -> sum[t][j] / cnt[t] } }
        return LearnResult(Templates(tpl, (whiteLum + blackLum) / 2f, occ), whiteBottom, null)
    }

    private fun distances(s: Sq, t: Templates): FloatArray {
        val out = FloatArray(6)
        for (i in 0 until 6) {
            val tp = t.tpl[i]
            var d = 0f
            for (j in tp.indices) {
                val e = s.feat[j] - tp[j]
                d += e * e
            }
            out[i] = d
        }
        return out
    }

    private fun bestClassIn(dists: FloatArray, range: IntRange): Int {
        var best = range.first
        var bd = Float.MAX_VALUE
        for (i in range) {
            if (dists[i] < bd) {
                bd = dists[i]
                best = i
            }
        }
        return best
    }

    /** Reads the whole board. Returns null if the result isn't a sane chess position. */
    fun readPosition(px: IntArray, t: Templates, whiteAtBottom: Boolean): CharArray? {
        val pos = CharArray(64) { '.' }
        val sqDists = arrayOfNulls<FloatArray>(64)
        val isWhiteSq = BooleanArray(64)
        val typeIdx = IntArray(64) { -1 }

        for (r in 0 until 8) for (c in 0 until 8) {
            val s = analyzeSquare(px, r, c)
            if (s.score < t.occThreshold) continue
            val file = if (whiteAtBottom) c else 7 - c
            val rank = if (whiteAtBottom) 7 - r else r
            val idx = rank * 8 + file
            val d = distances(s, t)
            sqDists[idx] = d
            isWhiteSq[idx] = s.lum > t.lumThreshold
            typeIdx[idx] = bestClassIn(d, 0..5)
        }

        fun ensureSingleKing(white: Boolean): Boolean {
            val sideSquares = (0 until 64).filter { typeIdx[it] >= 0 && isWhiteSq[it] == white }
            if (sideSquares.isEmpty()) return false
            val kingSquares = sideSquares.filter { typeIdx[it] == 5 }
            if (kingSquares.size > 1) {
                // Keep only the strongest King match; demote extras to their next-best non-king piece.
                val bestKing = kingSquares.minByOrNull { sqDists[it]!![5] }!!
                for (sq in kingSquares) {
                    if (sq != bestKing) {
                        typeIdx[sq] = bestClassIn(sqDists[sq]!!, 0..4)
                    }
                }
            } else if (kingSquares.isEmpty()) {
                // Promote the piece closest to King template (smaller excess loss over its top match).
                val bestCandidate = sideSquares.minByOrNull { sq ->
                    val d = sqDists[sq]!!
                    d[5] - d[typeIdx[sq]]
                } ?: return false
                typeIdx[bestCandidate] = 5
            }
            return true
        }

        val hasWhiteKing = ensureSingleKing(white = true)
        val hasBlackKing = ensureSingleKing(white = false)
        if (!hasWhiteKing || !hasBlackKing) {
            val wCnt = (0 until 64).count { typeIdx[it] >= 0 && isWhiteSq[it] }
            val bCnt = (0 until 64).count { typeIdx[it] >= 0 && !isWhiteSq[it] }
            lastProblem = "Board checker saw too few pieces ($wCnt W, $bCnt B)"
            return null
        }

        // Prevent minor template noise from placing a pawn on rank 1 or 8.
        for (f in 0..7) {
            for (idx in intArrayOf(f, 56 + f)) {
                if (typeIdx[idx] == 0) {
                    val d = sqDists[idx]!!
                    typeIdx[idx] = bestClassIn(d, 1..4)
                }
            }
        }

        var wk = 0
        var bk = 0
        var n = 0
        for (idx in 0 until 64) {
            val ti = typeIdx[idx]
            if (ti < 0) continue
            val base = "pnbrqk"[ti]
            val ch = if (isWhiteSq[idx]) base.uppercaseChar() else base
            pos[idx] = ch
            if (ch == 'K') wk++
            if (ch == 'k') bk++
            n++
        }
        if (wk != 1 || bk != 1) {
            lastProblem = "Board checker saw $n pieces ($wk W king, $bk B king)"
            return null
        }
        for (f in 0..7) {
            if (pos[f] == 'P' || pos[f] == 'p' || pos[56 + f] == 'P' || pos[56 + f] == 'p') {
                lastProblem = "Board checker saw a pawn on rank 1 or 8"
                return null
            }
        }
        lastProblem = ""
        return pos
    }
}
