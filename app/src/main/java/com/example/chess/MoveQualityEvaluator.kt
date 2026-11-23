package com.example.chess

import kotlin.math.abs

object MoveQualityEvaluator {

  data class PreMoveSnapshot(
    val boardBefore: BoardState,
    val preBestWhiteCp: Int,
    val preBestMateIn: Int?,
    val bestMoveUci: String?,
    val secondBestUci: String?,
  )

  fun classifyMove(
    snapshot: PreMoveSnapshot,
    playedMove: Move,
    postAnalysis: EngineAnalysis,
  ): Pair<MoveQuality, Int> {
    val mover = snapshot.boardBefore.sideToMove
    val sign = if (mover == PieceColor.WHITE) 1 else -1

    val preMoverCp = snapshot.preBestWhiteCp * sign
    val postMoverCp = postAnalysis.evalCp * sign

    val isTopEngineMove = snapshot.bestMoveUci?.equals(playedMove.uci, ignoreCase = true) == true
    val isSecondEngineMove = snapshot.secondBestUci?.equals(playedMove.uci, ignoreCase = true) == true

    // If mate was delivered or preserved as the top engine choice
    val deliveredMate = postAnalysis.status == GameStatus.CHECKMATE_WHITE_WINS ||
      postAnalysis.status == GameStatus.CHECKMATE_BLACK_WINS
    if (deliveredMate && isTopEngineMove) {
      val wasSacrifice = isMaterialSacrifice(snapshot.boardBefore, playedMove)
      return (if (wasSacrifice) MoveQuality.BRILLIANT else MoveQuality.EXCELLENT) to 0
    }

    val cpl = (preMoverCp - postMoverCp).coerceAtLeast(0)
    val sacrifice = isMaterialSacrifice(snapshot.boardBefore, playedMove)

    val quality =
      when {
        // Brilliant (!!): Top or near-top Stockfish move involving a real piece sacrifice without losing advantage
        sacrifice && (isTopEngineMove || cpl <= 25) && postMoverCp >= -40 -> MoveQuality.BRILLIANT
        // Excellent (!): Top Stockfish move or <= 25cp loss (or #2 move with <= 30cp loss)
        isTopEngineMove || cpl <= 25 || (isSecondEngineMove && cpl <= 30) -> MoveQuality.EXCELLENT
        // Good (✓): Solid playable move within 26..70cp
        cpl <= 70 -> MoveQuality.GOOD
        // Inaccuracy (?!): 71..140cp
        cpl <= 140 -> MoveQuality.INACCURACY
        // Mistake (?): 141..260cp
        cpl <= 260 -> MoveQuality.MISTAKE
        else -> MoveQuality.BLUNDER
      }

    return quality to cpl
  }

  /**
   * Detects whether the played move offers material (non-pawn piece moving to a square attacked by
   * a lesser piece or undefended capture exchange) for tactical compensation.
   */
  fun isMaterialSacrifice(boardBefore: BoardState, move: Move): Boolean {
    val movingPiece = boardBefore.pieceAt(move.from) ?: return false
    if (movingPiece.type == PieceType.KING || movingPiece.type == PieceType.PAWN) return false

    val capturedValue = move.captured?.type?.valueCp ?: 0
    val netSacrificeThreshold = movingPiece.type.valueCp - capturedValue
    if (netSacrificeThreshold < 180) return false

    val after = ChessRules.applyMove(boardBefore, move)
    val opp = movingPiece.color.opposite()
    val isAttacked = ChessRules.isSquareAttacked(after, move.to, opp)
    val isDefended = ChessRules.isSquareAttacked(after, move.to, movingPiece.color)

    // True sacrifice if landing on an attacked square where opponent gains material or leaves higher piece exposed
    return isAttacked && (!isDefended || movingPiece.type.valueCp >= PieceType.ROOK.valueCp)
  }

  fun aggregateStats(
    history: List<MoveRecord>,
    trackedColor: PieceColor?,
  ): MoveQualityStats {
    val relevant =
      history.filter { rec ->
        rec.quality != null && (trackedColor == null || rec.moverColor == trackedColor)
      }
    if (relevant.isEmpty()) {
      return MoveQualityStats()
    }

    var brilliant = 0
    var excellent = 0
    var good = 0
    var inaccuracy = 0
    var mistake = 0
    var blunder = 0
    var accuracySum = 0.0

    for (rec in relevant) {
      when (rec.quality) {
        MoveQuality.BRILLIANT -> {
          brilliant++
          accuracySum += 100.0
        }
        MoveQuality.EXCELLENT -> {
          excellent++
          accuracySum += 98.0
        }
        MoveQuality.GOOD -> {
          good++
          val cpl = rec.centipawnLoss ?: 45
          accuracySum += (92.0 - (cpl - 25) * 0.25).coerceIn(78.0, 94.0)
        }
        MoveQuality.INACCURACY -> {
          inaccuracy++
          accuracySum += 68.0
        }
        MoveQuality.MISTAKE -> {
          mistake++
          accuracySum += 42.0
        }
        MoveQuality.BLUNDER -> {
          blunder++
          accuracySum += 15.0
        }
        null -> Unit
      }
    }

    val last = relevant.last()
    val avgAcc = (accuracySum / relevant.size).toInt().coerceIn(0, 100)
    return MoveQualityStats(
      brilliantCount = brilliant,
      excellentCount = excellent,
      goodCount = good,
      inaccuracyCount = inaccuracy,
      mistakeCount = mistake,
      blunderCount = blunder,
      totalGradedMoves = relevant.size,
      accuracyPercent = avgAcc,
      lastMoveSan = last.san,
      lastMoveQuality = last.quality,
      lastGradedBy = last.gradedBy ?: "Stockfish",
    )
  }
}
