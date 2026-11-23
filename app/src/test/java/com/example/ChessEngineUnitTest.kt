package com.example

import com.example.chess.BoardState
import com.example.chess.ChessEngine
import com.example.chess.ChessLibraryData
import com.example.chess.ChessRules
import com.example.chess.GameStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessEngineUnitTest {

  @Test
  fun startingPositionHas20LegalMovesAndPreservesFen() {
    val start = BoardState.startingPosition()
    val legal = ChessRules.generateLegalMoves(start)
    assertEquals(20, legal.size)
    assertEquals(BoardState.STARTING_FEN, start.toFen())
  }

  @Test
  fun engineFindsBackRankMateInOne() = runTest {
    val puzzle = ChessLibraryData.puzzles.first { it.id == "p1" }
    val board = BoardState.fromFen(puzzle.fen)
    assertNotNull(board)
    val analysis = ChessEngine.analyze(board!!, depth = 3)
    assertEquals("d1d8", analysis.topMoves.first().move.uci)
    assertEquals("Rd8#", analysis.topMoves.first().san)
  }

  @Test
  fun engineFindsScholarMateBattery() = runTest {
    val puzzle = ChessLibraryData.puzzles.first { it.id == "p6" }
    val board = BoardState.fromFen(puzzle.fen)
    assertNotNull(board)
    val analysis = ChessEngine.analyze(board!!, depth = 3)
    assertEquals("h5f7", analysis.topMoves.first().move.uci)
    assertEquals("Qxf7#", analysis.topMoves.first().san)
  }

  @Test
  fun pgnParsingProducesValidOpeningPositions() {
    for (opening in ChessLibraryData.openings) {
      val records = ChessRules.parsePgnMoves(opening.movesPgn)
      assertNotNull("Failed to parse PGN for ${opening.name}", records)
      assertTrue(records!!.isNotEmpty())
    }
  }

  @Test
  fun scholarMateLeadsToCheckmateStatus() {
    val pgn = "1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#"
    val records = ChessRules.parsePgnMoves(pgn)
    assertNotNull(records)
    val finalBoard = BoardState.fromFen(records!!.last().fenAfter)!!
    assertEquals(GameStatus.CHECKMATE_WHITE_WINS, ChessRules.evaluateGameStatus(finalBoard))
  }

  @Test
  fun eloDepthMapperScalesMonotonicallyFrom800To3200() {
    val lowDepth = com.example.chess.EloDepthMapper.eloToStockfishDepth(800)
    val midDepth = com.example.chess.EloDepthMapper.eloToStockfishDepth(2000)
    val maxDepth = com.example.chess.EloDepthMapper.eloToStockfishDepth(3200)
    assertEquals(1, lowDepth)
    assertEquals(15, maxDepth)
    assertTrue(midDepth in (lowDepth + 1) until maxDepth)
  }

  @Test
  fun stockfishBestMoveParserExtractsUciMove() {
    assertEquals(
      "b7b6",
      com.example.chess.StockfishApiClient.parseUciFromBestMove("bestmove b7b6 ponder f3e5"),
    )
    assertEquals(
      "e7e8q",
      com.example.chess.StockfishApiClient.parseUciFromBestMove("bestmove e7e8q"),
    )
  }

  @Test
  fun hybridAnalysisLocalFallbackIsFastAndAccurate() = runTest {
    val puzzle = ChessLibraryData.puzzles.first { it.id == "p1" }
    val board = BoardState.fromFen(puzzle.fen)!!
    val result =
      ChessEngine.analyzeHybrid(
        board = board,
        targetElo = 1800,
        useStockfishApi = false,
      )
    assertEquals("d1d8", result.topMoves.first().move.uci)
  }

  @Test
  fun moveQualityEvaluatorGradesTopAndSacrificeAndAggregatesCounts() {
    val start = BoardState.startingPosition()
    val e4 = ChessRules.parseMoveToken(start, "e4")!!
    val snap =
      com.example.chess.MoveQualityEvaluator.PreMoveSnapshot(
        boardBefore = start,
        preBestWhiteCp = 25,
        preBestMateIn = null,
        bestMoveUci = "e2e4",
        secondBestUci = "d2d4",
      )
    val postGood =
      com.example.chess.EngineAnalysis(
        fen = ChessRules.applyMove(start, e4).toFen(),
        evalCp = 22,
      )
    val (q1, cpl1) = com.example.chess.MoveQualityEvaluator.classifyMove(snap, e4, postGood)
    assertEquals(com.example.chess.MoveQuality.EXCELLENT, q1)
    assertTrue(cpl1 <= 5)

    // Greek Gift Bxh7+ sacrifice detection
    val greekBoard =
      BoardState.fromFen("r1bq1rk1/pppn1ppp/4pn2/3p4/2PP4/2NBPN2/PP3PPP/R1BQK2R w KQ - 0 7")!!
    val bxh7 = ChessRules.parseMoveToken(greekBoard, "Bxh7+")!!
    assertTrue(com.example.chess.MoveQualityEvaluator.isMaterialSacrifice(greekBoard, bxh7))
    val greekSnap =
      com.example.chess.MoveQualityEvaluator.PreMoveSnapshot(
        boardBefore = greekBoard,
        preBestWhiteCp = 180,
        preBestMateIn = null,
        bestMoveUci = "d3h7",
        secondBestUci = "e1g1",
      )
    val postGreek =
      com.example.chess.EngineAnalysis(
        fen = ChessRules.applyMove(greekBoard, bxh7).toFen(),
        evalCp = 210,
      )
    val (qSac, _) = com.example.chess.MoveQualityEvaluator.classifyMove(greekSnap, bxh7, postGreek)
    assertEquals(com.example.chess.MoveQuality.BRILLIANT, qSac)

    val history =
      listOf(
        com.example.chess.MoveRecord(bxh7, "Bxh7+", postGreek.fen, com.example.chess.PieceColor.WHITE, 210, qSac, 0),
        com.example.chess.MoveRecord(e4, "e4", postGood.fen, com.example.chess.PieceColor.WHITE, 22, q1, cpl1),
        com.example.chess.MoveRecord(e4, "Nf3", postGood.fen, com.example.chess.PieceColor.WHITE, -15, com.example.chess.MoveQuality.GOOD, 45),
      )
    val stats = com.example.chess.MoveQualityEvaluator.aggregateStats(history, com.example.chess.PieceColor.WHITE)
    assertEquals(1, stats.brilliantCount)
    assertEquals(1, stats.excellentCount)
    assertEquals(1, stats.goodCount)
    assertEquals(3, stats.totalGradedMoves)
  }
}
