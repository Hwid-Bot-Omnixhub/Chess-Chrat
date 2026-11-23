package com.example.chess

import kotlin.math.abs

object ChessRules {

  private val KNIGHT_OFFSETS =
    arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
  private val BISHOP_DIRS = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
  private val ROOK_DIRS = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
  private val KING_OFFSETS =
    arrayOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)

  fun findKing(board: BoardState, color: PieceColor): Square? {
    for (idx in 0 until 64) {
      val p = board.squares[idx]
      if (p != null && p.color == color && p.type == PieceType.KING) {
        return Square.fromIndex(idx)
      }
    }
    return null
  }

  fun isSquareAttacked(board: BoardState, target: Square, byColor: PieceColor): Boolean {
    val tf = target.file
    val tr = target.rank

    // Pawns: a pawn of byColor attacks target from rank - dir
    val pawnDir = if (byColor == PieceColor.WHITE) 1 else -1
    val pawnRank = tr - pawnDir
    if (pawnRank in 0..7) {
      for (df in intArrayOf(-1, 1)) {
        val pf = tf + df
        if (pf in 0..7) {
          val p = board.pieceAt(pf, pawnRank)
          if (p != null && p.color == byColor && p.type == PieceType.PAWN) return true
        }
      }
    }

    // Knights
    for ((df, dr) in KNIGHT_OFFSETS) {
      val nf = tf + df
      val nr = tr + dr
      if (nf in 0..7 && nr in 0..7) {
        val p = board.pieceAt(nf, nr)
        if (p != null && p.color == byColor && p.type == PieceType.KNIGHT) return true
      }
    }

    // King
    for ((df, dr) in KING_OFFSETS) {
      val kf = tf + df
      val kr = tr + dr
      if (kf in 0..7 && kr in 0..7) {
        val p = board.pieceAt(kf, kr)
        if (p != null && p.color == byColor && p.type == PieceType.KING) return true
      }
    }

    // Bishop / Queen diagonals
    for ((df, dr) in BISHOP_DIRS) {
      var f = tf + df
      var r = tr + dr
      while (f in 0..7 && r in 0..7) {
        val p = board.pieceAt(f, r)
        if (p != null) {
          if (p.color == byColor && (p.type == PieceType.BISHOP || p.type == PieceType.QUEEN)) {
            return true
          }
          break
        }
        f += df
        r += dr
      }
    }

    // Rook / Queen straight lines
    for ((df, dr) in ROOK_DIRS) {
      var f = tf + df
      var r = tr + dr
      while (f in 0..7 && r in 0..7) {
        val p = board.pieceAt(f, r)
        if (p != null) {
          if (p.color == byColor && (p.type == PieceType.ROOK || p.type == PieceType.QUEEN)) {
            return true
          }
          break
        }
        f += df
        r += dr
      }
    }

    return false
  }

  fun isInCheck(board: BoardState, color: PieceColor): Boolean {
    val kingSq = findKing(board, color) ?: return false
    return isSquareAttacked(board, kingSq, color.opposite())
  }

  fun generateLegalMoves(board: BoardState): List<Move> {
    val pseudo = generatePseudoLegalMoves(board)
    val us = board.sideToMove
    return pseudo.filter { move ->
      val next = applyMove(board, move)
      !isInCheck(next, us)
    }
  }

  fun generatePseudoLegalMoves(board: BoardState): List<Move> {
    val moves = ArrayList<Move>(48)
    val us = board.sideToMove
    val them = us.opposite()

    for (idx in 0 until 64) {
      val piece = board.squares[idx] ?: continue
      if (piece.color != us) continue
      val from = Square.fromIndex(idx)

      when (piece.type) {
        PieceType.PAWN -> generatePawnMoves(board, from, us, them, moves)
        PieceType.KNIGHT -> {
          for ((df, dr) in KNIGHT_OFFSETS) {
            val nf = from.file + df
            val nr = from.rank + dr
            if (nf in 0..7 && nr in 0..7) {
              val target = board.pieceAt(nf, nr)
              if (target == null || target.color == them) {
                moves.add(Move(from, Square(nf, nr), captured = target))
              }
            }
          }
        }
        PieceType.BISHOP -> addSlidingMoves(board, from, us, them, BISHOP_DIRS, moves)
        PieceType.ROOK -> addSlidingMoves(board, from, us, them, ROOK_DIRS, moves)
        PieceType.QUEEN -> {
          addSlidingMoves(board, from, us, them, BISHOP_DIRS, moves)
          addSlidingMoves(board, from, us, them, ROOK_DIRS, moves)
        }
        PieceType.KING -> {
          for ((df, dr) in KING_OFFSETS) {
            val kf = from.file + df
            val kr = from.rank + dr
            if (kf in 0..7 && kr in 0..7) {
              val target = board.pieceAt(kf, kr)
              if (target == null || target.color == them) {
                moves.add(Move(from, Square(kf, kr), captured = target))
              }
            }
          }
          addCastlingMoves(board, from, us, them, moves)
        }
      }
    }
    return moves
  }

  /** Lightweight capture + promotion generator for fast quiescence search without quiet move allocation. */
  fun generateLegalCaptures(board: BoardState): List<Move> {
    val captures = ArrayList<Move>(16)
    val us = board.sideToMove
    val them = us.opposite()

    for (idx in 0 until 64) {
      val piece = board.squares[idx] ?: continue
      if (piece.color != us) continue
      val from = Square.fromIndex(idx)

      when (piece.type) {
        PieceType.PAWN -> {
          val dir = if (us == PieceColor.WHITE) 1 else -1
          val promoRank = if (us == PieceColor.WHITE) 7 else 0
          val nextRank = from.rank + dir
          if (nextRank in 0..7) {
            if (nextRank == promoRank && board.pieceAt(from.file, nextRank) == null) {
              captures.add(Move(from, Square(from.file, nextRank), promotion = PieceType.QUEEN))
            }
            for (df in intArrayOf(-1, 1)) {
              val cf = from.file + df
              if (cf !in 0..7) continue
              val to = Square(cf, nextRank)
              val target = board.pieceAt(cf, nextRank)
              if (target != null && target.color == them) {
                if (nextRank == promoRank) {
                  captures.add(Move(from, to, promotion = PieceType.QUEEN, captured = target))
                } else {
                  captures.add(Move(from, to, captured = target))
                }
              } else if (board.enPassantTarget == to) {
                val epCap = board.pieceAt(cf, from.rank)
                if (epCap != null && epCap.color == them && epCap.type == PieceType.PAWN) {
                  captures.add(Move(from, to, isEnPassant = true, captured = epCap))
                }
              }
            }
          }
        }
        PieceType.KNIGHT -> {
          for ((df, dr) in KNIGHT_OFFSETS) {
            val nf = from.file + df
            val nr = from.rank + dr
            if (nf in 0..7 && nr in 0..7) {
              val target = board.pieceAt(nf, nr)
              if (target != null && target.color == them) {
                captures.add(Move(from, Square(nf, nr), captured = target))
              }
            }
          }
        }
        PieceType.BISHOP -> addSlidingCaptures(board, from, them, BISHOP_DIRS, captures)
        PieceType.ROOK -> addSlidingCaptures(board, from, them, ROOK_DIRS, captures)
        PieceType.QUEEN -> {
          addSlidingCaptures(board, from, them, BISHOP_DIRS, captures)
          addSlidingCaptures(board, from, them, ROOK_DIRS, captures)
        }
        PieceType.KING -> {
          for ((df, dr) in KING_OFFSETS) {
            val kf = from.file + df
            val kr = from.rank + dr
            if (kf in 0..7 && kr in 0..7) {
              val target = board.pieceAt(kf, kr)
              if (target != null && target.color == them) {
                captures.add(Move(from, Square(kf, kr), captured = target))
              }
            }
          }
        }
      }
    }

    return captures.filter { move -> !isInCheck(applyMove(board, move), us) }
  }

  private fun addSlidingCaptures(
    board: BoardState,
    from: Square,
    them: PieceColor,
    dirs: Array<Pair<Int, Int>>,
    out: MutableList<Move>,
  ) {
    for ((df, dr) in dirs) {
      var f = from.file + df
      var r = from.rank + dr
      while (f in 0..7 && r in 0..7) {
        val target = board.pieceAt(f, r)
        if (target != null) {
          if (target.color == them) {
            out.add(Move(from, Square(f, r), captured = target))
          }
          break
        }
        f += df
        r += dr
      }
    }
  }

  private fun generatePawnMoves(
    board: BoardState,
    from: Square,
    us: PieceColor,
    them: PieceColor,
    out: MutableList<Move>,
  ) {
    val dir = if (us == PieceColor.WHITE) 1 else -1
    val startRank = if (us == PieceColor.WHITE) 1 else 6
    val promoRank = if (us == PieceColor.WHITE) 7 else 0
    val nextRank = from.rank + dir

    if (nextRank !in 0..7) return

    // Single push
    if (board.pieceAt(from.file, nextRank) == null) {
      val to = Square(from.file, nextRank)
      if (nextRank == promoRank) {
        for (promo in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
          out.add(Move(from, to, promotion = promo))
        }
      } else {
        out.add(Move(from, to))
        // Double push
        if (from.rank == startRank) {
          val twoRank = from.rank + dir * 2
          if (board.pieceAt(from.file, twoRank) == null) {
            out.add(Move(from, Square(from.file, twoRank)))
          }
        }
      }
    }

    // Captures & En Passant
    for (df in intArrayOf(-1, 1)) {
      val cf = from.file + df
      if (cf !in 0..7) continue
      val to = Square(cf, nextRank)
      val target = board.pieceAt(cf, nextRank)
      if (target != null && target.color == them) {
        if (nextRank == promoRank) {
          for (promo in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
            out.add(Move(from, to, promotion = promo, captured = target))
          }
        } else {
          out.add(Move(from, to, captured = target))
        }
      } else if (board.enPassantTarget == to) {
        val epCaptured = board.pieceAt(cf, from.rank)
        if (epCaptured != null && epCaptured.color == them && epCaptured.type == PieceType.PAWN) {
          out.add(Move(from, to, isEnPassant = true, captured = epCaptured))
        }
      }
    }
  }

  private fun addSlidingMoves(
    board: BoardState,
    from: Square,
    us: PieceColor,
    them: PieceColor,
    dirs: Array<Pair<Int, Int>>,
    out: MutableList<Move>,
  ) {
    for ((df, dr) in dirs) {
      var f = from.file + df
      var r = from.rank + dr
      while (f in 0..7 && r in 0..7) {
        val target = board.pieceAt(f, r)
        if (target == null) {
          out.add(Move(from, Square(f, r)))
        } else {
          if (target.color == them) {
            out.add(Move(from, Square(f, r), captured = target))
          }
          break
        }
        f += df
        r += dr
      }
    }
  }

  private fun addCastlingMoves(
    board: BoardState,
    from: Square,
    us: PieceColor,
    them: PieceColor,
    out: MutableList<Move>,
  ) {
    val expectedRank = if (us == PieceColor.WHITE) 0 else 7
    if (from.file != 4 || from.rank != expectedRank) return
    if (isSquareAttacked(board, from, them)) return

    val canKingSide =
      if (us == PieceColor.WHITE) board.castlingRights.whiteKingSide
      else board.castlingRights.blackKingSide
    if (canKingSide) {
      val rook = board.pieceAt(7, expectedRank)
      if (
        rook != null &&
          rook.color == us &&
          rook.type == PieceType.ROOK &&
          board.pieceAt(5, expectedRank) == null &&
          board.pieceAt(6, expectedRank) == null &&
          !isSquareAttacked(board, Square(5, expectedRank), them) &&
          !isSquareAttacked(board, Square(6, expectedRank), them)
      ) {
        out.add(Move(from, Square(6, expectedRank), isCastle = true))
      }
    }

    val canQueenSide =
      if (us == PieceColor.WHITE) board.castlingRights.whiteQueenSide
      else board.castlingRights.blackQueenSide
    if (canQueenSide) {
      val rook = board.pieceAt(0, expectedRank)
      if (
        rook != null &&
          rook.color == us &&
          rook.type == PieceType.ROOK &&
          board.pieceAt(3, expectedRank) == null &&
          board.pieceAt(2, expectedRank) == null &&
          board.pieceAt(1, expectedRank) == null &&
          !isSquareAttacked(board, Square(3, expectedRank), them) &&
          !isSquareAttacked(board, Square(2, expectedRank), them)
      ) {
        out.add(Move(from, Square(2, expectedRank), isCastle = true))
      }
    }
  }

  fun applyMove(board: BoardState, move: Move): BoardState {
    val grid = board.squares.toMutableList()
    val moving = grid[move.from.index] ?: return board
    val captured = grid[move.to.index]

    grid[move.from.index] = null

    if (move.isEnPassant) {
      val epCapSq = Square(move.to.file, move.from.rank)
      grid[epCapSq.index] = null
    }

    if (move.isCastle) {
      val rank = move.from.rank
      if (move.to.file == 6) {
        grid[Square(5, rank).index] = grid[Square(7, rank).index]
        grid[Square(7, rank).index] = null
      } else if (move.to.file == 2) {
        grid[Square(3, rank).index] = grid[Square(0, rank).index]
        grid[Square(0, rank).index] = null
      }
    }

    grid[move.to.index] =
      if (move.promotion != null) Piece(move.promotion, moving.color) else moving

    var rights = board.castlingRights
    if (moving.type == PieceType.KING) {
      rights =
        if (moving.color == PieceColor.WHITE) {
          rights.copy(whiteKingSide = false, whiteQueenSide = false)
        } else {
          rights.copy(blackKingSide = false, blackQueenSide = false)
        }
    }
    if (move.from == Square(0, 0) || move.to == Square(0, 0)) {
      rights = rights.copy(whiteQueenSide = false)
    }
    if (move.from == Square(7, 0) || move.to == Square(7, 0)) {
      rights = rights.copy(whiteKingSide = false)
    }
    if (move.from == Square(0, 7) || move.to == Square(0, 7)) {
      rights = rights.copy(blackQueenSide = false)
    }
    if (move.from == Square(7, 7) || move.to == Square(7, 7)) {
      rights = rights.copy(blackKingSide = false)
    }

    val newEp =
      if (moving.type == PieceType.PAWN && abs(move.to.rank - move.from.rank) == 2) {
        Square(move.from.file, (move.from.rank + move.to.rank) / 2)
      } else {
        null
      }

    val newHalfmove =
      if (moving.type == PieceType.PAWN || captured != null || move.isEnPassant) 0
      else board.halfmoveClock + 1
    val newFullmove =
      if (board.sideToMove == PieceColor.BLACK) board.fullmoveNumber + 1 else board.fullmoveNumber

    return BoardState(
      squares = grid,
      sideToMove = board.sideToMove.opposite(),
      castlingRights = rights,
      enPassantTarget = newEp,
      halfmoveClock = newHalfmove,
      fullmoveNumber = newFullmove,
    )
  }

  fun toSan(board: BoardState, move: Move, legalMoves: List<Move> = generateLegalMoves(board)): String {
    val moving = board.pieceAt(move.from) ?: return move.uci
    val base = StringBuilder()

    if (move.isCastle) {
      base.append(if (move.to.file == 6) "O-O" else "O-O-O")
    } else if (moving.type == PieceType.PAWN) {
      val isCapture = move.captured != null || move.isEnPassant
      if (isCapture) {
        base.append('a' + move.from.file).append('x')
      }
      base.append(move.to.algebraic)
      if (move.promotion != null) {
        base.append('=').append(move.promotion.fenChar.uppercaseChar())
      }
    } else {
      base.append(moving.type.fenChar.uppercaseChar())
      val ambiguous =
        legalMoves.filter {
          it.from != move.from &&
            it.to == move.to &&
            board.pieceAt(it.from)?.type == moving.type
        }
      if (ambiguous.isNotEmpty()) {
        val sameFile = ambiguous.any { it.from.file == move.from.file }
        val sameRank = ambiguous.any { it.from.rank == move.from.rank }
        if (!sameFile) {
          base.append('a' + move.from.file)
        } else if (!sameRank) {
          base.append('1' + move.from.rank)
        } else {
          base.append(move.from.algebraic)
        }
      }
      if (move.captured != null) {
        base.append('x')
      }
      base.append(move.to.algebraic)
    }

    val next = applyMove(board, move)
    if (isInCheck(next, next.sideToMove)) {
      val replies = generateLegalMoves(next)
      base.append(if (replies.isEmpty()) '#' else '+')
    }
    return base.toString()
  }

  fun evaluateGameStatus(
    board: BoardState,
    legalMoves: List<Move> = generateLegalMoves(board),
  ): GameStatus {
    val inCheck = isInCheck(board, board.sideToMove)
    if (legalMoves.isEmpty()) {
      return if (inCheck) {
        if (board.sideToMove == PieceColor.WHITE) GameStatus.CHECKMATE_BLACK_WINS
        else GameStatus.CHECKMATE_WHITE_WINS
      } else {
        GameStatus.STALEMATE
      }
    }
    if (board.halfmoveClock >= 100) return GameStatus.DRAW_FIFTY_MOVE
    if (isInsufficientMaterial(board)) return GameStatus.DRAW_INSUFFICIENT_MATERIAL
    return if (inCheck) GameStatus.CHECK else GameStatus.ONGOING
  }

  private fun isInsufficientMaterial(board: BoardState): Boolean {
    val minorPieces = mutableListOf<Pair<Piece, Square>>()
    for (i in 0 until 64) {
      val p = board.squares[i] ?: continue
      when (p.type) {
        PieceType.PAWN,
        PieceType.ROOK,
        PieceType.QUEEN -> return false
        PieceType.KNIGHT,
        PieceType.BISHOP -> minorPieces.add(p to Square.fromIndex(i))
        PieceType.KING -> Unit
      }
    }
    if (minorPieces.size <= 1) return true
    if (
      minorPieces.size == 2 &&
        minorPieces[0].first.type == PieceType.BISHOP &&
        minorPieces[1].first.type == PieceType.BISHOP
    ) {
      return minorPieces[0].second.isLight == minorPieces[1].second.isLight
    }
    return false
  }

  fun parseMoveToken(board: BoardState, rawToken: String): Move? {
    val legal = generateLegalMoves(board)
    val clean = rawToken.trim().trimEnd('+', '#', '!', '?')
    if (clean.isEmpty()) return null

    // Try UCI match first (e.g. e2e4, e7e8q)
    val uciMatch = legal.find { it.uci.equals(clean, ignoreCase = true) }
    if (uciMatch != null) return uciMatch

    // Try exact SAN match (ignoring +/#)
    return legal.find { move ->
      toSan(board, move, legal).trimEnd('+', '#') == clean
    }
  }

  fun parsePgnMoves(pgnText: String, initialBoard: BoardState = BoardState.startingPosition()): List<MoveRecord>? {
    // Strip PGN headers [Event "..."], comments {...}, variations (...), and move numbers 1. / 1...
    val withoutHeaders = pgnText.replace(Regex("\\[[^\\]]*\\]"), " ")
    val withoutComments = withoutHeaders.replace(Regex("\\{[^}]*\\}"), " ")
    val tokens =
      withoutComments
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { token ->
          token.isNotEmpty() &&
            !token.matches(Regex("^\\d+\\.{1,3}$")) &&
            token !in setOf("1-0", "0-1", "1/2-1/2", "*")
        }

    var board = initialBoard
    val records = mutableListOf<MoveRecord>()
    for (token in tokens) {
      val move = parseMoveToken(board, token) ?: return null
      val legal = generateLegalMoves(board)
      val san = toSan(board, move, legal)
      board = applyMove(board, move)
      records.add(MoveRecord(move = move, san = san, fenAfter = board.toFen()))
    }
    return records
  }
}
