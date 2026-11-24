package com.example.chessreviewer

/** Positions are CharArray(64), index = rank * 8 + file (a1 = 0, h8 = 63). Uppercase = white, '.' = empty. */
object Chess {
    const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    fun placement(pos: CharArray): String {
        val sb = StringBuilder()
        for (rk in 7 downTo 0) {
            var e = 0
            for (f in 0..7) {
                val ch = pos[rk * 8 + f]
                if (ch == '.') {
                    e++
                } else {
                    if (e > 0) { sb.append(e); e = 0 }
                    sb.append(ch)
                }
            }
            if (e > 0) sb.append(e)
            if (rk > 0) sb.append('/')
        }
        return sb.toString()
    }
}

/** Works out whose turn it is and which castling rights are left, by watching positions change. */
class Tracker {
    private var prev: CharArray? = null
    private val castle = booleanArrayOf(true, true, true, true) // K Q k q
    var blackToMove = false

    fun reset() {
        prev = null
        castle.fill(true)
        blackToMove = false
    }

    fun update(pos: CharArray, whiteAtBottom: Boolean) {
        val p = prev
        if (Chess.placement(pos) == Chess.START) {
            castle.fill(true)
            blackToMove = false
        } else if (p != null) {
            var moverIsWhite: Boolean? = null
            for (i in 0 until 64) {
                if (pos[i] != '.' && pos[i] != p[i]) {
                    moverIsWhite = pos[i].isUpperCase()
                    break
                }
            }
            if (moverIsWhite != null) blackToMove = moverIsWhite
        } else {
            // first position we see mid-game: assume it's the turn of the player at the bottom
            blackToMove = !whiteAtBottom
        }
        if (pos[4] != 'K') { castle[0] = false; castle[1] = false }
        if (pos[7] != 'R') castle[0] = false
        if (pos[0] != 'R') castle[1] = false
        if (pos[60] != 'k') { castle[2] = false; castle[3] = false }
        if (pos[63] != 'r') castle[2] = false
        if (pos[56] != 'r') castle[3] = false
        prev = pos.copyOf()
    }

    fun fen(pos: CharArray): String {
        val c = StringBuilder()
        if (castle[0] && pos[4] == 'K' && pos[7] == 'R') c.append('K')
        if (castle[1] && pos[4] == 'K' && pos[0] == 'R') c.append('Q')
        if (castle[2] && pos[60] == 'k' && pos[63] == 'r') c.append('k')
        if (castle[3] && pos[60] == 'k' && pos[56] == 'r') c.append('q')
        val cs = if (c.isEmpty()) "-" else c.toString()
        return Chess.placement(pos) + (if (blackToMove) " b " else " w ") + cs + " - 0 1"
    }
}
