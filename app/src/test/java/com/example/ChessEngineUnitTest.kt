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

  @Test
  fun visionLearnsTemplatesReadsStartBoardAndTrackerInfersMoves() {
    val px = IntArray(com.example.chessreviewer.Vision.B * com.example.chessreviewer.Vision.B)
    val startPos = arrayOf(
      "rnbqkbnr",
      "pppppppp",
      "........",
      "........",
      "........",
      "........",
      "PPPPPPPP",
      "RNBQKBNR",
    )

    fun drawSquare(row: Int, col: Int, piece: Char) {
      val n = com.example.chessreviewer.Vision.N
      val isLight = (row + col) % 2 == 0
      val bg = if (isLight) 0xFFEEEED2.toInt() else 0xFF769656.toInt()
      val fg = if (piece.isUpperCase()) 0xFFFAFAFA.toInt() else 0xFF1F1F1F.toInt()
      for (y in 0 until n) {
        for (x in 0 until n) {
          val gx = col * n + x
          val gy = row * n + y
          var drawFg = false
          val cx = x - n / 2
          val cy = y - n / 2
          when (piece.lowercaseChar()) {
            'p' -> drawFg = cx * cx + cy * cy <= 64
            'r' -> drawFg = kotlin.math.abs(cx) <= 8 && kotlin.math.abs(cy) <= 11
            'n' -> drawFg = kotlin.math.abs(cx) <= 10 && kotlin.math.abs(cy) <= 10 && cx + cy <= 8
            'b' -> drawFg = kotlin.math.abs(cx) * 2 + kotlin.math.abs(cy) <= 22
            'q' -> drawFg = (cx * cx + cy * cy <= 144) && !(kotlin.math.abs(cx) <= 3 && cy < -8)
            'k' -> drawFg = kotlin.math.abs(cx) <= 12 && kotlin.math.abs(cy) <= 12
          }
          px[gy * com.example.chessreviewer.Vision.B + gx] = if (drawFg) fg else bg
        }
      }
    }

    for (r in 0 until 8) {
      for (c in 0 until 8) {
        drawSquare(r, c, startPos[r][c])
      }
    }

    val learnResult = com.example.chessreviewer.Vision.learn(px)
    assertNotNull("Expected learn() to succeed: ${learnResult.error}", learnResult.templates)
    assertTrue(learnResult.whiteBottom)

    val serialized = learnResult.templates!!.serialize()
    val parsed = com.example.chessreviewer.Templates.parse(serialized)
    assertNotNull(parsed)

    val readStart = com.example.chessreviewer.Vision.readPosition(px, parsed!!, whiteAtBottom = true)
    assertNotNull(readStart)
    assertEquals(com.example.chessreviewer.Chess.START, com.example.chessreviewer.Chess.placement(readStart!!))

    val tracker = com.example.chessreviewer.Tracker()
    tracker.update(readStart, whiteAtBottom = true)
    assertEquals(
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      tracker.fen(readStart),
    )

    // Simulate 1. e4 visually (clear e2 at row 6 col 4, draw 'P' on e4 at row 4 col 4)
    drawSquare(6, 4, '.')
    drawSquare(4, 4, 'P')
    val afterE4 = com.example.chessreviewer.Vision.readPosition(px, parsed, whiteAtBottom = true)
    assertNotNull(afterE4)
    tracker.update(afterE4!!, whiteAtBottom = true)
    assertTrue(tracker.blackToMove)
    assertEquals(
      "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
      tracker.fen(afterE4),
    )
  }

  @Test
  fun boardFinderLocates8x8BoardSurroundedByUiChrome() {
    val w = 216
    val h = 420
    val px = IntArray(w * h) { 0xFF1E222A.toInt() } // dark app background

    // Draw top header bar and bottom controls outside the board
    for (y in 0 until 60) {
      for (x in 0 until w) px[y * w + x] = 0xFF2B313C.toInt()
    }
    for (y in 320 until h) {
      for (x in 0 until w) px[y * w + x] = 0xFF252A34.toInt()
    }

    // Place an 8x8 chessboard of size 192x192 (sq = 24) at (12, 96)
    val bx = 12
    val by = 96
    val sq = 24
    for (r in 0 until 8) {
      for (c in 0 until 8) {
        val isLight = (r + c) % 2 == 0
        var bg = if (isLight) 0xFFEEEED2.toInt() else 0xFF769656.toInt()
        if (r == 6 && c == 4) bg = 0xFFBACA44.toInt() // highlighted last-move square
        for (dy in 0 until sq) {
          for (dx in 0 until sq) {
            val pxX = bx + c * sq + dx
            val pxY = by + r * sq + dy
            // Draw pieces on top 2 and bottom 2 ranks
            val inCenter = dx in 6 until 18 && dy in 6 until 18
            val color =
              if ((r <= 1 || r >= 6) && inCenter) {
                if (r <= 1) 0xFF202020.toInt() else 0xFFF8F8F8.toInt()
              } else {
                bg
              }
            px[pxY * w + pxX] = color
          }
        }
      }
    }

    val rect = com.example.chessreviewer.BoardFinder.findInPixels(px, w, h)
    assertNotNull("Expected BoardFinder to detect the 8x8 board", rect)
    assertTrue("Expected x close to $bx, got ${rect!!.x}", kotlin.math.abs(rect.x - bx) <= 4)
    assertTrue("Expected y close to $by, got ${rect.y}", kotlin.math.abs(rect.y - by) <= 4)
    assertTrue("Expected size close to ${sq * 8}, got ${rect.size}", kotlin.math.abs(rect.size - sq * 8) <= 8)
  }

  @Test
  fun boardFinderLocatesFullWidthPortraitBoardNotDivisibleBy8() {
    val w = 260
    val h = 577
    val px = IntArray(w * h) { 0xFF262421.toInt() }
    val by = 148
    val sqF = w / 8f

    for (y in by until (by + w)) {
      val r = ((y - by) / sqF).toInt().coerceIn(0, 7)
      for (x in 0 until w) {
        val c = (x / sqF).toInt().coerceIn(0, 7)
        val isLight = (r + c) % 2 == 0
        val bg = if (isLight) 0xFFEEEED2.toInt() else 0xFF769656.toInt()
        val sqLocalX = x - c * sqF
        val sqLocalY = (y - by) - r * sqF
        val inPiece =
          (r <= 1 || r >= 6) &&
            sqLocalX in (sqF * 0.25f)..(sqF * 0.75f) &&
            sqLocalY in (sqF * 0.25f)..(sqF * 0.75f)
        px[y * w + x] =
          if (inPiece) (if (r <= 1) 0xFF222222.toInt() else 0xFFFAFAFA.toInt()) else bg
      }
    }

    val rect = com.example.chessreviewer.BoardFinder.findInPixels(px, w, h)
    assertNotNull("Expected BoardFinder to detect full-width 260px Chess.com board", rect)
    assertTrue("Expected bx <= 3, got ${rect!!.x}", rect.x <= 3)
    assertTrue("Expected by near $by, got ${rect.y}", kotlin.math.abs(rect.y - by) <= 4)
    assertTrue("Expected full-width size >= 254, got ${rect.size}", rect.size >= 254)
  }

  @Test
  fun boardFinderRejectsBotPickerMenuSoOverlayStaysWaiting() {
    val w = 216
    val h = 440
    val px = IntArray(w * h) { 0xFF262421.toInt() }
    // Simulate a 4-column bot avatar grid (not an 8x8 chessboard)
    val cardW = 46
    val cardH = 54
    for (row in 0 until 4) {
      for (col in 0 until 4) {
        val x0 = 10 + col * 50
        val y0 = 70 + row * 68
        val cardColor = if ((row + col) % 2 == 0) 0xFF3A3835.toInt() else 0xFF4B4844.toInt()
        for (dy in 0 until cardH) {
          for (dx in 0 until cardW) {
            px[(y0 + dy) * w + (x0 + dx)] = cardColor
          }
        }
      }
    }
    val rect = com.example.chessreviewer.BoardFinder.findInPixels(px, w, h)
    assertEquals("Menu screens must return null so overlay shows Waiting...", null, rect)
  }

  @Test
  fun hubSyncsFastTwoPlyBotReplyPreservingTurnAndHistory() {
    com.example.data.ChessAssistantHub.resetBoard()
    // Simulate 1. d4 d5 occurring between scan frames
    val afterD4D5Fen = "rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 2"
    val synced = com.example.data.ChessAssistantHub.applyScannedBoard(afterD4D5Fen, whiteBottom = true)
    assertTrue(synced)
    val st = com.example.data.ChessAssistantHub.state.value
    assertEquals(com.example.chess.PieceColor.WHITE, st.board.sideToMove)
    assertEquals(listOf("d4", "d5"), st.moveHistory.map { it.san })
    com.example.data.ChessAssistantHub.resetBoard()
  }

  @Test
  fun startingPositionHasNoPlantedBookTheoryTags() = runTest {
    val start = BoardState.startingPosition()
    val local = ChessEngine.analyze(start, depth = 2)
    assertTrue(local.topMoves.isNotEmpty())
    assertTrue(local.topMoves.none { it.tacticalTag.contains("Book", ignoreCase = true) })
    assertEquals(null, local.openingName)
  }

  @Test
  fun visionRejectsAlreadyPlayedE4DuringLearnAndDisambiguatesKingOverlap() {
    val px = IntArray(com.example.chessreviewer.Vision.B * com.example.chessreviewer.Vision.B)
    val startPos = arrayOf(
      "rnbqkbnr",
      "pppppppp",
      "........",
      "........",
      "........",
      "........",
      "PPPPPPPP",
      "RNBQKBNR",
    )

    fun drawSquare(row: Int, col: Int, piece: Char) {
      val n = com.example.chessreviewer.Vision.N
      val isLight = (row + col) % 2 == 0
      val bg = if (isLight) 0xFFEEEED2.toInt() else 0xFF769656.toInt()
      val fg = if (piece.isUpperCase()) 0xFFFAFAFA.toInt() else 0xFF1F1F1F.toInt()
      for (y in 0 until n) {
        for (x in 0 until n) {
          val gx = col * n + x
          val gy = row * n + y
          var drawFg = false
          val cx = x - n / 2
          val cy = y - n / 2
          when (piece.lowercaseChar()) {
            'p' -> drawFg = cx * cx + cy * cy <= 64
            'r' -> drawFg = kotlin.math.abs(cx) <= 8 && kotlin.math.abs(cy) <= 11
            'n' -> drawFg = kotlin.math.abs(cx) <= 10 && kotlin.math.abs(cy) <= 10 && cx + cy <= 8
            'b' -> drawFg = kotlin.math.abs(cx) * 2 + kotlin.math.abs(cy) <= 22
            'q' -> drawFg = (cx * cx + cy * cy <= 144) && !(kotlin.math.abs(cx) <= 3 && cy < -8)
            'k' -> drawFg = kotlin.math.abs(cx) <= 12 && kotlin.math.abs(cy) <= 12
          }
          px[gy * com.example.chessreviewer.Vision.B + gx] = if (drawFg) fg else bg
        }
      }
    }

    for (r in 0 until 8) {
      for (c in 0 until 8) {
        drawSquare(r, c, startPos[r][c])
      }
    }
    val learned = com.example.chessreviewer.Vision.learn(px).templates!!

    // Play 1.e4 and make d1 look slightly King-like (same box silhouette as K)
    drawSquare(6, 4, '.')
    drawSquare(4, 4, 'P')
    drawSquare(7, 3, 'K')

    // Must NOT treat 1.e4 as a fresh unplayed starting layout
    val relearnOnE4 = com.example.chessreviewer.Vision.learn(px)
    assertEquals(null, relearnOnE4.templates)

    // Must still read a valid position with exactly 1 White King and 1 Black King
    val pos = com.example.chessreviewer.Vision.readPosition(px, learned, whiteAtBottom = true)
    assertNotNull(pos)
    assertEquals(1, pos!!.count { it == 'K' })
    assertEquals(1, pos.count { it == 'k' })
    assertEquals('P', pos[3 * 8 + 4]) // e4 occupied by white pawn
    assertEquals('.', pos[1 * 8 + 4]) // e2 empty
  }

  @Test
  fun boardVisionScannerAdaptiveDelayIncreasesWhenBoardIsUnchanged() {
    val active = com.example.service.BoardVisionScanner.recommendedLoopDelayMs(false, streak = 0)
    val idle1 = com.example.service.BoardVisionScanner.recommendedLoopDelayMs(false, streak = 2)
    val idle2 = com.example.service.BoardVisionScanner.recommendedLoopDelayMs(false, streak = 5)
    val deepIdle = com.example.service.BoardVisionScanner.recommendedLoopDelayMs(false, streak = 10)
    assertTrue(active < idle1)
    assertTrue(idle1 < idle2)
    assertTrue(idle2 < deepIdle)

    val antiLagActive = com.example.service.BoardVisionScanner.recommendedLoopDelayMs(true, streak = 0)
    val antiLagDeepIdle = com.example.service.BoardVisionScanner.recommendedLoopDelayMs(true, streak = 10)
    assertTrue(antiLagActive >= active)
    assertTrue(antiLagDeepIdle >= deepIdle)
  }
}

