package com.example.chess

import android.content.Context
import com.example.chessreviewer.StockfishEngine
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object ChessEngine {

  private const val MATE_SCORE = 50_000
  private const val INF = 1_000_000
  private const val MAX_CACHE_SIZE = 128

  @Volatile
  private var nativeEngine: StockfishEngine? = null

  fun initNativeEngine(context: Context) {
    if (nativeEngine != null) return
    val eng = StockfishEngine(context.applicationContext)
    if (eng.available()) {
      nativeEngine = eng
    }
  }

  fun isNativeStockfishAvailable(): Boolean = nativeEngine?.available() == true

  private enum class TTFlag {
    EXACT,
    LOWERBOUND,
    UPPERBOUND,
  }

  private data class TTEntry(
    val depth: Int,
    val score: Int,
    val flag: TTFlag,
    val bestMoveUci: String?,
  )

  private val analysisCache =
    object : LinkedHashMap<String, EngineAnalysis>(MAX_CACHE_SIZE, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EngineAnalysis>?): Boolean {
        return size > MAX_CACHE_SIZE
      }
    }

  private fun getCached(key: String): EngineAnalysis? =
    synchronized(analysisCache) { analysisCache[key] }

  private fun putCached(key: String, value: EngineAnalysis) {
    synchronized(analysisCache) { analysisCache[key] = value }
  }

  // Piece-square tables from White's perspective (index 0 = a1, index 63 = h8; flip rank for Black)
  private val PAWN_PST =
    intArrayOf(
      0, 0, 0, 0, 0, 0, 0, 0,
      5, 10, 10, -20, -20, 10, 10, 5,
      5, -5, -10, 0, 0, -10, -5, 5,
      0, 0, 0, 22, 22, 0, 0, 0,
      5, 5, 12, 26, 26, 12, 5, 5,
      12, 12, 20, 32, 32, 20, 12, 12,
      50, 50, 50, 50, 50, 50, 50, 50,
      0, 0, 0, 0, 0, 0, 0, 0,
    )

  private val KNIGHT_PST =
    intArrayOf(
      -50, -40, -30, -30, -30, -30, -40, -50,
      -40, -20, 0, 5, 5, 0, -20, -40,
      -30, 5, 12, 16, 16, 12, 5, -30,
      -30, 0, 16, 22, 22, 16, 0, -30,
      -30, 5, 16, 22, 22, 16, 5, -30,
      -30, 0, 12, 16, 16, 12, 0, -30,
      -40, -20, 0, 0, 0, 0, -20, -40,
      -50, -40, -30, -30, -30, -30, -40, -50,
    )

  private val BISHOP_PST =
    intArrayOf(
      -20, -10, -10, -10, -10, -10, -10, -20,
      -10, 5, 0, 0, 0, 0, 5, -10,
      -10, 10, 10, 10, 10, 10, 10, -10,
      -10, 0, 10, 14, 14, 10, 0, -10,
      -10, 5, 5, 14, 14, 5, 5, -10,
      -10, 0, 5, 10, 10, 5, 0, -10,
      -10, 0, 0, 0, 0, 0, 0, -10,
      -20, -10, -10, -10, -10, -10, -10, -20,
    )

  private val ROOK_PST =
    intArrayOf(
      0, 0, 4, 8, 8, 4, 0, 0,
      -5, 0, 0, 0, 0, 0, 0, -5,
      -5, 0, 0, 0, 0, 0, 0, -5,
      -5, 0, 0, 0, 0, 0, 0, -5,
      -5, 0, 0, 0, 0, 0, 0, -5,
      -5, 0, 0, 0, 0, 0, 0, -5,
      10, 16, 16, 16, 16, 16, 16, 10,
      0, 0, 0, 0, 0, 0, 0, 0,
    )

  private val QUEEN_PST =
    intArrayOf(
      -20, -10, -10, -5, -5, -10, -10, -20,
      -10, 0, 5, 0, 0, 0, 0, -10,
      -10, 5, 5, 5, 5, 5, 0, -10,
      0, 0, 5, 5, 5, 5, 0, -5,
      -5, 0, 5, 5, 5, 5, 0, -5,
      -10, 0, 5, 5, 5, 5, 0, -10,
      -10, 0, 0, 0, 0, 0, 0, -10,
      -20, -10, -10, -5, -5, -10, -10, -20,
    )

  private val KING_MID_PST =
    intArrayOf(
      20, 30, 10, 0, 0, 10, 30, 20,
      20, 20, -5, -10, -10, -5, 20, 20,
      -10, -20, -20, -20, -20, -20, -20, -10,
      -20, -30, -30, -40, -40, -30, -30, -20,
      -30, -40, -40, -50, -50, -40, -40, -30,
      -30, -40, -40, -50, -50, -40, -40, -30,
      -30, -40, -40, -50, -50, -40, -40, -30,
      -30, -40, -40, -50, -50, -40, -40, -30,
    )

  private val KING_END_PST =
    intArrayOf(
      -50, -30, -30, -30, -30, -30, -30, -50,
      -30, -20, -10, 0, 0, -10, -20, -30,
      -30, -10, 20, 30, 30, 20, -10, -30,
      -30, -10, 30, 40, 40, 30, -10, -30,
      -30, -10, 30, 40, 40, 30, -10, -30,
      -30, -10, 20, 30, 30, 20, -10, -30,
      -30, -30, 0, 0, 0, 0, -30, -30,
      -50, -30, -30, -30, -30, -30, -30, -50,
    )

  private data class BookEntry(val name: String, val preferredUcis: List<String>)

  private val OPENING_BOOK: Map<String, BookEntry> =
    mapOf(
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -" to
        BookEntry("Starting Position", listOf("e2e4", "d2d4", "c2c4")),
      "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3" to
        BookEntry("King's Pawn Opening", listOf("c7c5", "e7e5", "e7e6")),
      "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6" to
        BookEntry("Open Game (1.e4 e5)", listOf("g1f3", "f1c4", "b1c3")),
      "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6" to
        BookEntry("Sicilian Defense", listOf("g1f3", "b1c3", "c2c3")),
      "rnbqkbnr/pppp1ppp/4p3/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq -" to
        BookEntry("French Defense", listOf("d2d4", "g1f3", "b1c3")),
      "rnbqkbnr/pp1ppppp/2p5/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq -" to
        BookEntry("Caro-Kann Defense", listOf("d2d4", "b1c3", "g1f3")),
      "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq -" to
        BookEntry("King's Knight Opening", listOf("b8c6", "g8f6", "d7d6")),
      "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq -" to
        BookEntry("Ruy Lopez / Italian Setup", listOf("f1b5", "f1c4", "d2d4")),
      "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq -" to
        BookEntry("Ruy Lopez (Spanish Game)", listOf("a7a6", "g8f6", "f8c5")),
      "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq -" to
        BookEntry("Italian Game", listOf("f8c5", "g8f6", "f8e7")),
      "rnbqkbnr/pppppppp/8/8/3P4/8/PPPP1PPP/RNBQKBNR b KQkq d3" to
        BookEntry("Queen's Pawn Opening", listOf("g8f6", "d7d5", "e7e6")),
      "rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPPP1PPP/RNBQKBNR w KQkq d6" to
        BookEntry("Closed Game (1.d4 d5)", listOf("c2c4", "c1f4", "g1f3")),
      "rnbqkbnr/ppp1pppp/8/3p4/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3" to
        BookEntry("Queen's Gambit", listOf("e7e6", "c7c6", "d5c4")),
      "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPPP1PPP/RNBQKBNR w KQkq -" to
        BookEntry("Indian Defense", listOf("c2c4", "g1f3", "c1f4")),
      "rnbqkb1r/pppppppp/5n2/8/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3" to
        BookEntry("Indian Game (1.d4 Nf6 2.c4)", listOf("e7e6", "g7g6", "c7c5")),
      "rnbqkbnr/ppp1pppp/8/3p4/3P1B2/8/PPP1PPPP/RN1QKBNR b KQkq -" to
        BookEntry("London System", listOf("g8f6", "c7c5", "e7e6")),
    )

  /**
   * Fast synchronous/local analysis with Transposition Table & Iterative Deepening time budget.
   * Safe for unit tests and offline fallback.
   */
  suspend fun analyze(
    board: BoardState,
    depth: Int = 3,
    targetElo: Int = 1800,
    timeBudgetMs: Long = 220L,
  ): EngineAnalysis {
    coroutineContext.ensureActive()
    val startNs = System.nanoTime()
    val fen = board.toFen()
    val legalMoves = ChessRules.generateLegalMoves(board)
    val status = ChessRules.evaluateGameStatus(board, legalMoves)
    val opening = OPENING_BOOK[board.positionKey()]

    if (legalMoves.isEmpty()) {
      return terminalAnalysis(fen, status, opening?.name, targetElo)
    }

    val tt = HashMap<String, TTEntry>(4096)
    val maxDepth = depth.coerceIn(1, 4)
    val deadlineNs = startNs + timeBudgetMs * 1_000_000L

    var bestIterationScores = emptyList<Pair<Move, Int>>()
    var depthReached = 1
    var pvHintUci: String? = null

    for (d in 1..maxDepth) {
      coroutineContext.ensureActive()
      val ordered =
        orderMoves(
          board = board,
          moves = legalMoves,
          bookUcis = opening?.preferredUcis.orEmpty(),
          ttMoveUci = pvHintUci,
        )
      val currentScores = ArrayList<Pair<Move, Int>>(ordered.size)
      var timedOut = false

      for ((idx, move) in ordered.withIndex()) {
        coroutineContext.ensureActive()
        if (d > 1 && idx > 0 && System.nanoTime() >= deadlineNs) {
          timedOut = true
          break
        }
        val next = ChessRules.applyMove(board, move)
        var score = -alphaBeta(next, d - 1, -INF, INF, 1, tt, deadlineNs, allowTimeout = d > 1)
        val bookIdx = opening?.preferredUcis?.indexOf(move.uci) ?: -1
        if (bookIdx >= 0) {
          score += (3 - bookIdx) * 14
        }
        currentScores.add(move to score)
      }

      if (!timedOut && currentScores.isNotEmpty()) {
        currentScores.sortByDescending { it.second }
        bestIterationScores = currentScores
        depthReached = d
        pvHintUci = currentScores.first().first.uci
        // Stop early if forced mate already found
        if (abs(currentScores.first().second) >= MATE_SCORE - 200) break
      } else {
        break
      }
    }

    if (bestIterationScores.isEmpty()) {
      bestIterationScores =
        legalMoves.map { it to -evaluateStatic(ChessRules.applyMove(board, it)) }
          .sortedByDescending { it.second }
    }

    val topMoves =
      bestIterationScores.take(3).mapIndexed { index, (move, rawScoreForMover) ->
        val whiteScore =
          if (board.sideToMove == PieceColor.WHITE) rawScoreForMover else -rawScoreForMover
        val matePlies = extractMatePlies(rawScoreForMover)
        val san = ChessRules.toSan(board, move, legalMoves)
        val pv = buildPrincipalVariation(board, move, maxPlies = 4)
        val tag = classifyMove(board, move, san, matePlies, index == 0, opening?.preferredUcis)
        SuggestedMove(
          rank = index + 1,
          move = move,
          san = san,
          scoreCp = whiteScore,
          mateIn = matePlies?.let { (it + 1) / 2 },
          scoreDisplay = formatScore(whiteScore, matePlies, board.sideToMove),
          pvLine = pv,
          tacticalTag = tag,
        )
      }

    val best = topMoves.first()
    val threat = computeOpponentThreat(board, tt)
    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

    return EngineAnalysis(
      fen = fen,
      evalCp = best.scoreCp,
      mateIn = best.mateIn,
      scoreDisplay = best.scoreDisplay,
      topMoves = topMoves,
      opponentThreat = threat,
      openingName = opening?.name,
      depthReached = depthReached,
      status = status,
      engineSource = "Local D$depthReached",
      analysisTimeMs = elapsedMs,
      targetElo = targetElo,
    )
  }

  /**
   * Two-stage hybrid analyzer:
   * 1) Returns/emits an instant fast preview (< 20ms) with zero UI lag.
   * 2) Queries Stockfish Online API at Elo-mapped depth (1..15) or deeper local fallback,
   *    so higher Elo = deeper depth & slower processing, while lower Elo = instant.
   */
  suspend fun analyzeHybrid(
    board: BoardState,
    targetElo: Int,
    useStockfishApi: Boolean,
    antiLagEnabled: Boolean = true,
    onQuickPreview: ((EngineAnalysis) -> Unit)? = null,
  ): EngineAnalysis {
    coroutineContext.ensureActive()
    val clampedElo = EloDepthMapper.clampElo(targetElo)
    val sfDepth = EloDepthMapper.eloToStockfishDepth(clampedElo)
    val fen = board.toFen()
    val cacheKey = "$fen|$sfDepth|$useStockfishApi"

    getCached(cacheKey)?.let { cached ->
      return cached.copy(
        engineSource = "${cached.engineSource} (Cached)",
        analysisTimeMs = 1L,
        targetElo = clampedElo,
      )
    }

    val legalMoves = ChessRules.generateLegalMoves(board)
    val status = ChessRules.evaluateGameStatus(board, legalMoves)
    val opening = OPENING_BOOK[board.positionKey()]
    if (legalMoves.isEmpty()) {
      return terminalAnalysis(fen, status, opening?.name, clampedElo)
    }

    val totalStartNs = System.nanoTime()

    // Stage 1: Instant local pass; Anti-Lag tightens budget to <= 22ms so UI/overlay never drops frames
    val (localTargetDepth, rawBudgetMs) = EloDepthMapper.eloToLocalBudget(clampedElo)
    val localBudgetMs = if (antiLagEnabled) minOf(rawBudgetMs, 140L) else rawBudgetMs
    val quickDepth = if (useStockfishApi || localTargetDepth > 2) 2 else localTargetDepth
    val quickBudgetMs = if (antiLagEnabled) 22L else 35L
    val quickLocal =
      analyze(
        board = board,
        depth = quickDepth,
        targetElo = clampedElo,
        timeBudgetMs = quickBudgetMs,
      )

    if (!useStockfishApi && localTargetDepth <= 2) {
      putCached(cacheKey, quickLocal)
      return quickLocal
    }

    onQuickPreview?.invoke(quickLocal)

    // Stage 2: Native Stockfish UCI (if libstockfish.so bundled) -> Stockfish Online API (depth 1..15)
    val native = nativeEngine
    if (useStockfishApi && native != null && native.available()) {
      val nativeElo = if (clampedElo >= 3190) null else clampedElo.coerceIn(600, 3190)
      val moveTimeMs = (sfDepth * 65).coerceIn(120, if (antiLagEnabled) 650 else 1100)
      val nativeResult =
        withContext(Dispatchers.IO) {
          native.setElo(nativeElo)
          native.analyze(fen, moveTimeMs)
        }
      coroutineContext.ensureActive()

      if (nativeResult != null && nativeResult.best.length >= 4) {
        val matched = legalMoves.find { it.uci.equals(nativeResult.best, ignoreCase = true) }
        if (matched != null) {
          val san = ChessRules.toSan(board, matched, legalMoves)
          val whiteCp =
            when {
              nativeResult.mate != null -> {
                val m = nativeResult.mate
                val sign = if (board.sideToMove == PieceColor.WHITE) 1 else -1
                if (m > 0) sign * (50_000 - m * 10) else -sign * (50_000 + m * 10)
              }
              nativeResult.cp != null ->
                if (board.sideToMove == PieceColor.WHITE) nativeResult.cp else -nativeResult.cp
              else -> quickLocal.evalCp
            }
          val mateAbs = nativeResult.mate?.let { kotlin.math.abs(it) }
          val display = formatScore(whiteCp, mateAbs, board.sideToMove)
          val pv = buildPrincipalVariation(board, matched, maxPlies = 4)
          val tag = classifyMove(board, matched, san, mateAbs, true, opening?.preferredUcis)
          val mergedTop =
            buildList {
              add(
                SuggestedMove(
                  rank = 1,
                  move = matched,
                  san = san,
                  scoreCp = whiteCp,
                  mateIn = mateAbs,
                  scoreDisplay = display,
                  pvLine = pv,
                  tacticalTag = tag,
                ),
              )
              addAll(
                quickLocal.topMoves
                  .filter { it.move.uci != matched.uci }
                  .take(2)
                  .mapIndexed { idx, m -> m.copy(rank = idx + 2) },
              )
            }
          val elapsedMs = ((System.nanoTime() - totalStartNs) / 1_000_000L).coerceAtLeast(1L)
          val combined =
            quickLocal.copy(
              evalCp = whiteCp,
              mateIn = mateAbs,
              scoreDisplay = display,
              topMoves = mergedTop,
              depthReached = sfDepth,
              engineSource = "Stockfish Native",
              analysisTimeMs = elapsedMs,
              targetElo = clampedElo,
            )
          putCached(cacheKey, combined)
          return combined
        }
      }
    }

    if (useStockfishApi) {
      val sfResult =
        withContext(Dispatchers.IO) {
          StockfishApiClient.fetchBestMove(board, depth = sfDepth)
        }
      coroutineContext.ensureActive()

      if (sfResult != null) {
        val mergedTop =
          buildList {
            add(
              SuggestedMove(
                rank = 1,
                move = sfResult.bestMove,
                san = sfResult.san,
                scoreCp = sfResult.evalCp,
                mateIn = sfResult.mateIn,
                scoreDisplay = sfResult.scoreDisplay,
                pvLine = sfResult.pvLine,
                tacticalTag = sfResult.tacticalTag,
              ),
            )
            val secondary =
              quickLocal.topMoves
                .filter { it.move.uci != sfResult.bestMove.uci }
                .take(2)
                .mapIndexed { idx, m -> m.copy(rank = idx + 2) }
            addAll(secondary)
          }

        val elapsedMs = ((System.nanoTime() - totalStartNs) / 1_000_000L).coerceAtLeast(1L)
        val combined =
          quickLocal.copy(
            evalCp = sfResult.evalCp,
            mateIn = sfResult.mateIn,
            scoreDisplay = sfResult.scoreDisplay,
            topMoves = mergedTop,
            depthReached = sfResult.depth,
            engineSource = "Stockfish D${sfResult.depth}",
            analysisTimeMs = elapsedMs,
            targetElo = clampedElo,
          )
        putCached(cacheKey, combined)
        return combined
      }
    }

    // Fallback to deeper local search when offline or Stockfish disabled
    val deepLocal =
      if (localTargetDepth > quickDepth || localBudgetMs > 40L) {
        analyze(
          board = board,
          depth = localTargetDepth,
          targetElo = clampedElo,
          timeBudgetMs = localBudgetMs,
        )
      } else {
        quickLocal
      }
    val elapsedMs = ((System.nanoTime() - totalStartNs) / 1_000_000L).coerceAtLeast(1L)
    val finalLocal = deepLocal.copy(analysisTimeMs = elapsedMs, targetElo = clampedElo)
    putCached(cacheKey, finalLocal)
    return finalLocal
  }

  private fun terminalAnalysis(
    fen: String,
    status: GameStatus,
    openingName: String?,
    targetElo: Int,
  ): EngineAnalysis {
    val mateScore =
      when (status) {
        GameStatus.CHECKMATE_WHITE_WINS -> MATE_SCORE
        GameStatus.CHECKMATE_BLACK_WINS -> -MATE_SCORE
        else -> 0
      }
    val display =
      when (status) {
        GameStatus.CHECKMATE_WHITE_WINS -> "1-0"
        GameStatus.CHECKMATE_BLACK_WINS -> "0-1"
        else -> "0.0"
      }
    return EngineAnalysis(
      fen = fen,
      evalCp = mateScore,
      mateIn =
        if (status == GameStatus.CHECKMATE_WHITE_WINS || status == GameStatus.CHECKMATE_BLACK_WINS)
          0
        else null,
      scoreDisplay = display,
      topMoves = emptyList(),
      opponentThreat = null,
      openingName = openingName,
      depthReached = 0,
      status = status,
      engineSource = "Terminal",
      analysisTimeMs = 1L,
      targetElo = targetElo,
    )
  }

  private suspend fun computeOpponentThreat(
    board: BoardState,
    tt: MutableMap<String, TTEntry>,
  ): SuggestedMove? {
    if (ChessRules.isInCheck(board, board.sideToMove)) return null
    val flipped = board.copy(sideToMove = board.sideToMove.opposite(), enPassantTarget = null)
    val oppMoves = ChessRules.generateLegalMoves(flipped)
    if (oppMoves.isEmpty()) return null

    var bestOppMove: Move? = null
    var bestOppScore = -INF
    val staticEval = evaluateStatic(flipped)
    val deadlineNs = System.nanoTime() + 30_000_000L

    for (move in orderMoves(flipped, oppMoves, emptyList()).take(10)) {
      coroutineContext.ensureActive()
      val next = ChessRules.applyMove(flipped, move)
      val score = -alphaBeta(next, 1, -INF, INF, 1, tt, deadlineNs, allowTimeout = true)
      if (score > bestOppScore) {
        bestOppScore = score
        bestOppMove = move
      }
    }

    val threat = bestOppMove ?: return null
    if (bestOppScore - staticEval < 110 && bestOppScore < MATE_SCORE - 100) return null

    val san = ChessRules.toSan(flipped, threat, oppMoves)
    val whiteScore = if (flipped.sideToMove == PieceColor.WHITE) bestOppScore else -bestOppScore
    val matePlies = extractMatePlies(bestOppScore)
    return SuggestedMove(
      rank = 0,
      move = threat,
      san = san,
      scoreCp = whiteScore,
      mateIn = matePlies?.let { (it + 1) / 2 },
      scoreDisplay = formatScore(whiteScore, matePlies, flipped.sideToMove),
      pvLine = listOf(san),
      tacticalTag = if (matePlies != null) "Mate Threat" else "Opponent Threat",
    )
  }

  private suspend fun alphaBeta(
    board: BoardState,
    depth: Int,
    alphaInit: Int,
    beta: Int,
    ply: Int,
    tt: MutableMap<String, TTEntry>,
    deadlineNs: Long,
    allowTimeout: Boolean,
  ): Int {
    if (ply % 2 == 0) coroutineContext.ensureActive()
    if (allowTimeout && System.nanoTime() >= deadlineNs) {
      return evaluateStatic(board)
    }

    if (board.halfmoveClock >= 100) return 0

    val posKey = board.positionKey()
    val ttEntry = tt[posKey]
    if (ttEntry != null && ttEntry.depth >= depth) {
      when (ttEntry.flag) {
        TTFlag.EXACT -> return ttEntry.score
        TTFlag.LOWERBOUND -> if (ttEntry.score >= beta) return ttEntry.score
        TTFlag.UPPERBOUND -> if (ttEntry.score <= alphaInit) return ttEntry.score
      }
    }

    val legalMoves = ChessRules.generateLegalMoves(board)
    if (legalMoves.isEmpty()) {
      return if (ChessRules.isInCheck(board, board.sideToMove)) {
        -(MATE_SCORE - ply)
      } else {
        0
      }
    }

    if (depth <= 0) {
      return quiescence(board, alphaInit, beta, qDepth = 2)
    }

    var alpha = alphaInit
    val origAlpha = alphaInit
    val ordered = orderMoves(board, legalMoves, emptyList(), ttEntry?.bestMoveUci)
    var bestMoveUci: String? = null
    var bestScore = -INF

    for (move in ordered) {
      val next = ChessRules.applyMove(board, move)
      val score = -alphaBeta(next, depth - 1, -beta, -alpha, ply + 1, tt, deadlineNs, allowTimeout)
      if (score > bestScore) {
        bestScore = score
        bestMoveUci = move.uci
      }
      if (score >= beta) {
        if (tt.size < 12_000) {
          tt[posKey] = TTEntry(depth, beta, TTFlag.LOWERBOUND, bestMoveUci)
        }
        return beta
      }
      if (score > alpha) alpha = score
    }

    if (tt.size < 12_000) {
      val flag =
        when {
          bestScore <= origAlpha -> TTFlag.UPPERBOUND
          else -> TTFlag.EXACT
        }
      tt[posKey] = TTEntry(depth, bestScore, flag, bestMoveUci)
    }
    return alpha
  }

  private fun quiescence(
    board: BoardState,
    alphaInit: Int,
    beta: Int,
    qDepth: Int,
  ): Int {
    val standPat = evaluateStatic(board)
    if (qDepth <= 0) return standPat
    var alpha = alphaInit
    if (standPat >= beta) return beta
    if (standPat > alpha) alpha = standPat

    val captures = ChessRules.generateLegalCaptures(board)
    if (captures.isEmpty()) return alpha

    val ordered = orderMoves(board, captures, emptyList())
    for (move in ordered) {
      val next = ChessRules.applyMove(board, move)
      val score = -quiescence(next, -beta, -alpha, qDepth - 1)
      if (score >= beta) return beta
      if (score > alpha) alpha = score
    }
    return alpha
  }

  private fun orderMoves(
    board: BoardState,
    moves: List<Move>,
    bookUcis: List<String>,
    ttMoveUci: String? = null,
  ): List<Move> {
    return moves.sortedByDescending { move ->
      var score = 0
      if (ttMoveUci != null && move.uci == ttMoveUci) score += 30_000
      if (move.uci in bookUcis) score += 20_000
      val captured = move.captured
      if (captured != null) {
        val attacker = board.pieceAt(move.from)?.type?.valueCp ?: 100
        score += 10_000 + captured.type.valueCp * 10 - attacker
      }
      if (move.promotion != null) {
        score += 9_000 + move.promotion.valueCp
      }
      if (move.isCastle) score += 450
      if (move.to.file in 2..5 && move.to.rank in 2..5) score += 40
      score
    }
  }

  private fun evaluateStatic(board: BoardState): Int {
    var totalNonPawnMaterial = 0
    for (idx in 0 until 64) {
      val p = board.squares[idx] ?: continue
      if (p.type != PieceType.PAWN && p.type != PieceType.KING) {
        totalNonPawnMaterial += p.type.valueCp
      }
    }
    val isEndgame = totalNonPawnMaterial <= 2600

    var whiteScore = 0
    var blackScore = 0
    var whiteBishops = 0
    var blackBishops = 0

    for (idx in 0 until 64) {
      val p = board.squares[idx] ?: continue
      val sq = Square.fromIndex(idx)
      val pstIdx =
        if (p.color == PieceColor.WHITE) sq.rank * 8 + sq.file
        else (7 - sq.rank) * 8 + sq.file

      val positional =
        when (p.type) {
          PieceType.PAWN -> PAWN_PST[pstIdx]
          PieceType.KNIGHT -> KNIGHT_PST[pstIdx]
          PieceType.BISHOP -> BISHOP_PST[pstIdx]
          PieceType.ROOK -> ROOK_PST[pstIdx]
          PieceType.QUEEN -> QUEEN_PST[pstIdx]
          PieceType.KING -> if (isEndgame) KING_END_PST[pstIdx] else KING_MID_PST[pstIdx]
        }

      val value = p.type.valueCp + positional
      if (p.color == PieceColor.WHITE) {
        whiteScore += value
        if (p.type == PieceType.BISHOP) whiteBishops++
      } else {
        blackScore += value
        if (p.type == PieceType.BISHOP) blackBishops++
      }
    }

    if (whiteBishops >= 2) whiteScore += 35
    if (blackBishops >= 2) blackScore += 35

    val diff = whiteScore - blackScore
    return if (board.sideToMove == PieceColor.WHITE) diff else -diff
  }

  private fun buildPrincipalVariation(
    board: BoardState,
    firstMove: Move,
    maxPlies: Int,
  ): List<String> {
    val line = mutableListOf<String>()
    var currBoard = board
    var currMove: Move? = firstMove
    repeat(maxPlies) {
      val m = currMove ?: return@repeat
      val legal = ChessRules.generateLegalMoves(currBoard)
      if (m !in legal) return@repeat
      line.add(ChessRules.toSan(currBoard, m, legal))
      currBoard = ChessRules.applyMove(currBoard, m)
      val nextLegal = ChessRules.generateLegalMoves(currBoard)
      if (nextLegal.isEmpty()) return@repeat
      currMove =
        orderMoves(currBoard, nextLegal, emptyList())
          .take(8)
          .maxByOrNull { reply -> evaluateStatic(ChessRules.applyMove(currBoard, reply)) * -1 }
    }
    return line
  }

  private fun classifyMove(
    board: BoardState,
    move: Move,
    san: String,
    matePlies: Int?,
    isTop: Boolean,
    bookUcis: List<String>?,
  ): String {
    if (san.endsWith('#') || matePlies != null) return "Mate Sequence"
    if (san.endsWith('+')) return "Check"
    if (bookUcis != null && move.uci in bookUcis) return "Book Theory"
    if (move.isCastle) return "King Safety"
    if (move.promotion != null) return "Promotion"
    val captured = move.captured
    val moving = board.pieceAt(move.from)
    if (captured != null && moving != null) {
      return if (captured.type.valueCp > moving.type.valueCp) "Wins Material" else "Tactical Capture"
    }
    if (isTop && move.to.file in 2..5 && move.to.rank in 2..5) return "Best Central Play"
    return if (isTop) "Best Move" else "Strong Line"
  }

  private fun extractMatePlies(scoreFromMover: Int): Int? {
    val absScore = abs(scoreFromMover)
    return if (absScore >= MATE_SCORE - 200) {
      (MATE_SCORE - absScore).coerceAtLeast(1)
    } else null
  }

  private fun formatScore(whiteScoreCp: Int, matePlies: Int?, mover: PieceColor): String {
    if (matePlies != null) {
      val fullMoves = (matePlies + 1) / 2
      val winningWhite =
        (mover == PieceColor.WHITE && whiteScoreCp > 0) ||
          (mover == PieceColor.BLACK && whiteScoreCp > 0)
      return if (winningWhite) "#M$fullMoves" else "-#M$fullMoves"
    }
    val pawns = whiteScoreCp / 100.0
    return if (pawns >= 0) String.format("+%.1f", pawns) else String.format("%.1f", pawns)
  }
}
