package com.example.chess

enum class PieceType(val fenChar: Char, val valueCp: Int, val displayName: String) {
  PAWN('p', 100, "Pawn"),
  KNIGHT('n', 320, "Knight"),
  BISHOP('b', 330, "Bishop"),
  ROOK('r', 500, "Rook"),
  QUEEN('q', 900, "Queen"),
  KING('k', 20000, "King");

  companion object {
    fun fromFenChar(c: Char): PieceType? = entries.find { it.fenChar == c.lowercaseChar() }
  }
}

enum class PieceColor {
  WHITE,
  BLACK;

  fun opposite(): PieceColor = if (this == WHITE) BLACK else WHITE
}

data class Piece(val type: PieceType, val color: PieceColor) {
  val fenChar: Char
    get() = if (color == PieceColor.WHITE) type.fenChar.uppercaseChar() else type.fenChar

  val unicodeSymbol: String
    get() =
      when (color) {
        PieceColor.WHITE ->
          when (type) {
            PieceType.KING -> "♔"
            PieceType.QUEEN -> "♕"
            PieceType.ROOK -> "♖"
            PieceType.BISHOP -> "♗"
            PieceType.KNIGHT -> "♘"
            PieceType.PAWN -> "♙"
          }
        PieceColor.BLACK ->
          when (type) {
            PieceType.KING -> "♚"
            PieceType.QUEEN -> "♛"
            PieceType.ROOK -> "♜"
            PieceType.BISHOP -> "♝"
            PieceType.KNIGHT -> "♞"
            PieceType.PAWN -> "♟"
          }
      }
}

data class Square(val file: Int, val rank: Int) {
  val index: Int
    get() = rank * 8 + file

  val algebraic: String
    get() = "${'a' + file}${'1' + rank}"

  val isLight: Boolean
    get() = (file + rank) % 2 == 1

  companion object {
    fun fromIndex(index: Int): Square = Square(index % 8, index / 8)

    fun fromAlgebraic(str: String): Square? {
      if (str.length != 2) return null
      val f = str[0].lowercaseChar() - 'a'
      val r = str[1] - '1'
      if (f !in 0..7 || r !in 0..7) return null
      return Square(f, r)
    }
  }
}

data class Move(
  val from: Square,
  val to: Square,
  val promotion: PieceType? = null,
  val isCastle: Boolean = false,
  val isEnPassant: Boolean = false,
  val captured: Piece? = null,
) {
  val uci: String
    get() = "${from.algebraic}${to.algebraic}${promotion?.fenChar ?: ""}"
}

data class CastlingRights(
  val whiteKingSide: Boolean = true,
  val whiteQueenSide: Boolean = true,
  val blackKingSide: Boolean = true,
  val blackQueenSide: Boolean = true,
) {
  fun toFen(): String {
    val sb = StringBuilder()
    if (whiteKingSide) sb.append('K')
    if (whiteQueenSide) sb.append('Q')
    if (blackKingSide) sb.append('k')
    if (blackQueenSide) sb.append('q')
    return if (sb.isEmpty()) "-" else sb.toString()
  }

  companion object {
    val NONE = CastlingRights(false, false, false, false)

    fun fromFen(str: String): CastlingRights =
      if (str == "-") NONE
      else
        CastlingRights(
          whiteKingSide = 'K' in str,
          whiteQueenSide = 'Q' in str,
          blackKingSide = 'k' in str,
          blackQueenSide = 'q' in str,
        )
  }
}

enum class MoveQuality(
  val label: String,
  val symbol: String,
) {
  BRILLIANT("Brilliant", "!!"),
  EXCELLENT("Excellent", "!"),
  GOOD("Good", "✓"),
  INACCURACY("Inaccuracy", "?!"),
  MISTAKE("Mistake", "?"),
  BLUNDER("Blunder", "??"),
}

data class MoveQualityStats(
  val brilliantCount: Int = 0,
  val excellentCount: Int = 0,
  val goodCount: Int = 0,
  val inaccuracyCount: Int = 0,
  val mistakeCount: Int = 0,
  val blunderCount: Int = 0,
  val totalGradedMoves: Int = 0,
  val accuracyPercent: Int = 100,
  val lastMoveSan: String? = null,
  val lastMoveQuality: MoveQuality? = null,
  val lastGradedBy: String = "Stockfish",
)

data class MoveRecord(
  val move: Move,
  val san: String,
  val fenAfter: String,
  val moverColor: PieceColor = PieceColor.WHITE,
  val evalCpAfter: Int = 0,
  val quality: MoveQuality? = null,
  val centipawnLoss: Int? = null,
  val gradedBy: String? = null,
)

enum class GameStatus {
  ONGOING,
  CHECK,
  CHECKMATE_WHITE_WINS,
  CHECKMATE_BLACK_WINS,
  STALEMATE,
  DRAW_FIFTY_MOVE,
  DRAW_INSUFFICIENT_MATERIAL,
}

data class BoardState(
  val squares: List<Piece?>,
  val sideToMove: PieceColor = PieceColor.WHITE,
  val castlingRights: CastlingRights = CastlingRights(),
  val enPassantTarget: Square? = null,
  val halfmoveClock: Int = 0,
  val fullmoveNumber: Int = 1,
) {
  fun pieceAt(square: Square): Piece? = squares[square.index]

  fun pieceAt(file: Int, rank: Int): Piece? =
    if (file in 0..7 && rank in 0..7) squares[rank * 8 + file] else null

  fun toFen(): String {
    val sb = StringBuilder()
    for (rank in 7 downTo 0) {
      var empty = 0
      for (file in 0..7) {
        val p = pieceAt(file, rank)
        if (p == null) {
          empty++
        } else {
          if (empty > 0) {
            sb.append(empty)
            empty = 0
          }
          sb.append(p.fenChar)
        }
      }
      if (empty > 0) sb.append(empty)
      if (rank > 0) sb.append('/')
    }
    sb.append(' ')
    sb.append(if (sideToMove == PieceColor.WHITE) 'w' else 'b')
    sb.append(' ')
    sb.append(castlingRights.toFen())
    sb.append(' ')
    sb.append(enPassantTarget?.algebraic ?: "-")
    sb.append(' ')
    sb.append(halfmoveClock)
    sb.append(' ')
    sb.append(fullmoveNumber)
    return sb.toString()
  }

  /** Position key ignoring halfmove/fullmove clocks for opening book & repetition checks. */
  fun positionKey(): String {
    val parts = toFen().split(' ')
    return "${parts[0]} ${parts[1]} ${parts[2]} ${parts[3]}"
  }

  companion object {
    const val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    fun startingPosition(): BoardState = fromFen(STARTING_FEN)!!

    fun emptyBoard(): BoardState =
      BoardState(
        squares = List(64) { null },
        sideToMove = PieceColor.WHITE,
        castlingRights = CastlingRights.NONE,
      )

    fun fromFen(fen: String): BoardState? {
      val trimmed = fen.trim()
      if (trimmed.isEmpty()) return null
      val parts = trimmed.split(Regex("\\s+"))
      if (parts.isEmpty()) return null

      val ranks = parts[0].split('/')
      if (ranks.size != 8) return null

      val grid = MutableList<Piece?>(64) { null }
      for (rIdx in 0..7) {
        val fenRank = ranks[rIdx]
        val boardRank = 7 - rIdx
        var file = 0
        for (ch in fenRank) {
          if (ch.isDigit()) {
            val count = ch.digitToInt()
            if (count !in 1..8) return null
            file += count
          } else {
            val type = PieceType.fromFenChar(ch) ?: return null
            val color = if (ch.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK
            if (file !in 0..7) return null
            grid[boardRank * 8 + file] = Piece(type, color)
            file++
          }
        }
        if (file != 8) return null
      }

      val side =
        when (parts.getOrNull(1)) {
          null,
          "w" -> PieceColor.WHITE
          "b" -> PieceColor.BLACK
          else -> return null
        }
      val castling = CastlingRights.fromFen(parts.getOrNull(2) ?: "-")
      val epStr = parts.getOrNull(3) ?: "-"
      val ep = if (epStr == "-") null else Square.fromAlgebraic(epStr)
      val halfmove = parts.getOrNull(4)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
      val fullmove = parts.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

      return BoardState(
        squares = grid,
        sideToMove = side,
        castlingRights = castling,
        enPassantTarget = ep,
        halfmoveClock = halfmove,
        fullmoveNumber = fullmove,
      )
    }
  }
}

data class SuggestedMove(
  val rank: Int,
  val move: Move,
  val san: String,
  val scoreCp: Int,
  val mateIn: Int? = null,
  val scoreDisplay: String,
  val pvLine: List<String> = emptyList(),
  val tacticalTag: String = "",
)

data class EngineAnalysis(
  val fen: String,
  val evalCp: Int = 0,
  val mateIn: Int? = null,
  val scoreDisplay: String = "0.0",
  val topMoves: List<SuggestedMove> = emptyList(),
  val opponentThreat: SuggestedMove? = null,
  val openingName: String? = null,
  val depthReached: Int = 0,
  val status: GameStatus = GameStatus.ONGOING,
  val engineSource: String = "Local",
  val analysisTimeMs: Long = 0L,
  val targetElo: Int = 1800,
)

object EloDepthMapper {
  const val MIN_ELO = 800
  const val MAX_ELO = 3200
  const val ELO_STEP = 100

  fun clampElo(elo: Int): Int {
    val clamped = elo.coerceIn(MIN_ELO, MAX_ELO)
    return ((clamped + 50) / 100) * 100
  }

  /** Maps 800..3200 Elo to Stockfish API depth 1..15 (higher = deeper & slower). */
  fun eloToStockfishDepth(elo: Int): Int {
    val norm = (clampElo(elo) - MIN_ELO).toFloat() / (MAX_ELO - MIN_ELO).toFloat()
    return (1 + kotlin.math.round(norm * 14f).toInt()).coerceIn(1, 15)
  }

  /** Maps 800..3200 Elo to local fallback depth 1..4 and time budget in ms. */
  fun eloToLocalBudget(elo: Int): Pair<Int, Long> =
    when (clampElo(elo)) {
      in 800..1000 -> 1 to 20L
      in 1100..1400 -> 2 to 55L
      in 1500..2100 -> 3 to 140L
      in 2200..2600 -> 3 to 260L
      else -> 4 to 480L
    }

  fun tierLabel(elo: Int): String =
    when (clampElo(elo)) {
      in 800..1100 -> "Casual"
      in 1200..1500 -> "Club"
      in 1600..2000 -> "Expert"
      in 2100..2500 -> "Master"
      in 2600..2900 -> "Grandmaster"
      else -> "Super-GM"
    }

  fun speedLabel(elo: Int): String =
    when (clampElo(elo)) {
      in 800..1200 -> "Instant"
      in 1300..1800 -> "Fast"
      in 1900..2400 -> "Balanced"
      in 2500..2800 -> "Deep (Slower)"
      else -> "Max Depth (Slowest)"
    }
}

