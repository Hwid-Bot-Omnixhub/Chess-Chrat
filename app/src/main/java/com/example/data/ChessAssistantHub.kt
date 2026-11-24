package com.example.data

import com.example.chess.BoardState
import com.example.chess.ChessEngine
import com.example.chess.ChessRules
import com.example.chess.EloDepthMapper
import com.example.chess.EngineAnalysis
import com.example.chess.Move
import com.example.chess.MoveQualityEvaluator
import com.example.chess.MoveQualityStats
import com.example.chess.MoveRecord
import com.example.chess.PieceColor
import com.example.chess.PieceType
import com.example.chess.Square
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BoardInteractionMode {
  ASSIST_ARROWS,
  PLAY_VS_ENGINE,
}

data class AssistantSharedState(
  val board: BoardState = BoardState.startingPosition(),
  val moveHistory: List<MoveRecord> = emptyList(),
  val redoStack: List<MoveRecord> = emptyList(),
  val analysis: EngineAnalysis = EngineAnalysis(fen = BoardState.STARTING_FEN),
  val isAnalyzing: Boolean = false,
  val interactionMode: BoardInteractionMode = BoardInteractionMode.ASSIST_ARROWS,
  val humanPlaySide: PieceColor = PieceColor.WHITE,
  val trackedPlayerSide: PieceColor? = PieceColor.WHITE,
  val moveStats: MoveQualityStats = MoveQualityStats(),
  val flippedBoard: Boolean = false,
  val targetElo: Int = 1800,
  val engineDepth: Int = EloDepthMapper.eloToStockfishDepth(1800),
  val useStockfishApi: Boolean = true,
  val antiLagEnabled: Boolean = true,
  val showFloatingStatsHud: Boolean = true,
  val showSecondaryArrows: Boolean = true,
  val showThreatArrow: Boolean = true,
  val arrowOpacity: Float = 0.90f,
  val watcherEnabled: Boolean = true,
  val watchedPackages: Set<String> =
    setOf("com.chess", "org.lichess.mobileapp", "com.chess24.app", "uk.co.aifactory.chessfree"),
  val overlayServiceRunning: Boolean = false,
  val overlayGridVisible: Boolean = true,
  val overlayPassThrough: Boolean = true,
  val overlayTapSyncActive: Boolean = false,
  val detectedForegroundApp: WatchedChessApp? = null,
  val activeLabel: String? = null,
  val overlayBoardX: Int = 40,
  val overlayBoardY: Int = 260,
  val overlayBoardSizePx: Int = 780,
  val statsHudX: Int = 36,
  val statsHudY: Int = 1080,
  val autoBoardScanEnabled: Boolean = false,
  val visionTemplatesReady: Boolean = false,
  val visionWhiteBottom: Boolean = true,
  val boardCheckerStatus: String = "Align frame over board & tap Learn on starting position",
  val shizukuRunning: Boolean = false,
  val shizukuReady: Boolean = false,
  val mediaProjectionReady: Boolean = false,
)

object ChessAssistantHub {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var analysisJob: Job? = null
  private var engineReplyJob: Job? = null

  private val _state = MutableStateFlow(AssistantSharedState())
  val state: StateFlow<AssistantSharedState> = _state.asStateFlow()

  private val boardSnapshots = mutableListOf(BoardState.startingPosition())
  private var pendingMoveGradeContext: PendingMoveGrade? = null

  private data class PendingMoveGrade(
    val moveIndex: Int,
    val fenAfter: String,
    val playedMove: Move,
    val snapshot: MoveQualityEvaluator.PreMoveSnapshot,
  )

  init {
    triggerAnalysis(debounceMs = 0L)
  }

  fun applyPreferences(prefs: UserPreferences) {
    val clampedElo = EloDepthMapper.clampElo(prefs.targetElo)
    val shouldReanalyze =
      _state.value.targetElo != clampedElo ||
        _state.value.useStockfishApi != prefs.useStockfishApi ||
        _state.value.antiLagEnabled != prefs.antiLagEnabled

    _state.update { current ->
      val updatedStats =
        MoveQualityEvaluator.aggregateStats(current.moveHistory, prefs.trackedPlayerSide)
      current.copy(
        targetElo = clampedElo,
        engineDepth = EloDepthMapper.eloToStockfishDepth(clampedElo),
        useStockfishApi = prefs.useStockfishApi,
        antiLagEnabled = prefs.antiLagEnabled,
        showFloatingStatsHud = prefs.showFloatingStatsHud,
        trackedPlayerSide = prefs.trackedPlayerSide,
        moveStats = updatedStats,
        showSecondaryArrows = prefs.showSecondaryArrows,
        showThreatArrow = prefs.showThreatArrow,
        arrowOpacity = prefs.arrowOpacity,
        watcherEnabled = prefs.watcherEnabled,
        flippedBoard = prefs.flippedBoard,
        watchedPackages = prefs.watchedPackages,
        overlayBoardX = prefs.overlayBoardX,
        overlayBoardY = prefs.overlayBoardY,
        overlayBoardSizePx = prefs.overlayBoardSizePx,
        statsHudX = prefs.statsHudX,
        statsHudY = prefs.statsHudY,
        visionTemplatesReady = !prefs.visionTemplates.isNullOrBlank(),
        visionWhiteBottom = prefs.visionWhiteBottom,
        autoBoardScanEnabled = prefs.autoBoardScan,
      )
    }
    if (shouldReanalyze) triggerAnalysis(debounceMs = 0L)
  }

  fun playMove(move: Move): Boolean {
    val stateNow = _state.value
    val current = stateNow.board
    val legal = ChessRules.generateLegalMoves(current)
    val matched =
      legal.find {
        it.from == move.from &&
          it.to == move.to &&
          (it.promotion == move.promotion ||
            (move.promotion == null && it.promotion == PieceType.QUEEN))
      } ?: return false

    val preSnapshot =
      MoveQualityEvaluator.PreMoveSnapshot(
        boardBefore = current,
        preBestWhiteCp = stateNow.analysis.evalCp,
        preBestMateIn = stateNow.analysis.mateIn,
        bestMoveUci = stateNow.analysis.topMoves.firstOrNull()?.move?.uci,
        secondBestUci = stateNow.analysis.topMoves.getOrNull(1)?.move?.uci,
      )

    val san = ChessRules.toSan(current, matched, legal)
    val nextBoard = ChessRules.applyMove(current, matched)
    val nextFen = nextBoard.toFen()
    boardSnapshots.add(nextBoard)
    val newIdx = stateNow.moveHistory.size
    val record =
      MoveRecord(
        move = matched,
        san = san,
        fenAfter = nextFen,
        moverColor = current.sideToMove,
      )

    pendingMoveGradeContext =
      PendingMoveGrade(
        moveIndex = newIdx,
        fenAfter = nextFen,
        playedMove = matched,
        snapshot = preSnapshot,
      )

    _state.update {
      val updatedHistory = it.moveHistory + record
      it.copy(
        board = nextBoard,
        moveHistory = updatedHistory,
        redoStack = emptyList(),
        activeLabel = null,
        moveStats = MoveQualityEvaluator.aggregateStats(updatedHistory, it.trackedPlayerSide),
      )
    }
    triggerAnalysis(debounceMs = 0L)
    maybeTriggerEngineReply()
    return true
  }

  fun playSquares(from: Square, to: Square): Boolean {
    val current = _state.value.board
    val legal = ChessRules.generateLegalMoves(current)
    val matched =
      legal.firstOrNull {
        it.from == from &&
          it.to == to &&
          (it.promotion == null || it.promotion == PieceType.QUEEN)
      } ?: return false
    return playMove(matched)
  }

  fun undoMove() {
    engineReplyJob?.cancel()
    pendingMoveGradeContext = null
    val current = _state.value
    if (current.moveHistory.isEmpty() || boardSnapshots.size <= 1) return
    val lastRecord = current.moveHistory.last()
    boardSnapshots.removeAt(boardSnapshots.lastIndex)
    val prevBoard = boardSnapshots.last()

    _state.update {
      val trimmed = it.moveHistory.dropLast(1)
      it.copy(
        board = prevBoard,
        moveHistory = trimmed,
        redoStack = listOf(lastRecord) + it.redoStack,
        moveStats = MoveQualityEvaluator.aggregateStats(trimmed, it.trackedPlayerSide),
      )
    }
    triggerAnalysis(debounceMs = 0L)
  }

  fun redoMove() {
    val current = _state.value
    val nextRecord = current.redoStack.firstOrNull() ?: return
    val nextBoard = ChessRules.applyMove(current.board, nextRecord.move)
    boardSnapshots.add(nextBoard)
    _state.update {
      val updated = it.moveHistory + nextRecord
      it.copy(
        board = nextBoard,
        moveHistory = updated,
        redoStack = it.redoStack.drop(1),
        moveStats = MoveQualityEvaluator.aggregateStats(updated, it.trackedPlayerSide),
      )
    }
    triggerAnalysis(debounceMs = 0L)
  }

  fun resetBoard() {
    engineReplyJob?.cancel()
    pendingMoveGradeContext = null
    val start = BoardState.startingPosition()
    boardSnapshots.clear()
    boardSnapshots.add(start)
    _state.update {
      it.copy(
        board = start,
        moveHistory = emptyList(),
        redoStack = emptyList(),
        activeLabel = null,
        moveStats = MoveQualityStats(),
      )
    }
    triggerAnalysis(debounceMs = 0L)
  }

  fun loadFen(fen: String, label: String? = null): Boolean {
    engineReplyJob?.cancel()
    pendingMoveGradeContext = null
    val parsed = BoardState.fromFen(fen) ?: return false
    boardSnapshots.clear()
    boardSnapshots.add(parsed)
    _state.update {
      it.copy(
        board = parsed,
        moveHistory = emptyList(),
        redoStack = emptyList(),
        activeLabel = label,
        moveStats = MoveQualityStats(),
      )
    }
    triggerAnalysis(debounceMs = 0L)
    return true
  }

  fun loadPgn(pgn: String, label: String? = null): Boolean {
    engineReplyJob?.cancel()
    pendingMoveGradeContext = null
    val records = ChessRules.parsePgnMoves(pgn) ?: return false
    boardSnapshots.clear()
    var b = BoardState.startingPosition()
    boardSnapshots.add(b)
    val coloredRecords =
      records.map { r ->
        val mover = b.sideToMove
        b = ChessRules.applyMove(b, r.move)
        boardSnapshots.add(b)
        r.copy(moverColor = mover)
      }
    _state.update {
      it.copy(
        board = b,
        moveHistory = coloredRecords,
        redoStack = emptyList(),
        activeLabel = label,
        moveStats = MoveQualityEvaluator.aggregateStats(coloredRecords, it.trackedPlayerSide),
      )
    }
    triggerAnalysis(debounceMs = 0L)
    return true
  }

  fun setInteractionMode(mode: BoardInteractionMode) {
    engineReplyJob?.cancel()
    _state.update { it.copy(interactionMode = mode) }
    maybeTriggerEngineReply()
  }

  fun setHumanPlaySide(side: PieceColor) {
    _state.update {
      it.copy(
        humanPlaySide = side,
        flippedBoard = side == PieceColor.BLACK,
        trackedPlayerSide = side,
        moveStats = MoveQualityEvaluator.aggregateStats(it.moveHistory, side),
      )
    }
    maybeTriggerEngineReply()
  }

  fun setTrackedPlayerSide(side: PieceColor?) {
    _state.update {
      it.copy(
        trackedPlayerSide = side,
        moveStats = MoveQualityEvaluator.aggregateStats(it.moveHistory, side),
      )
    }
  }

  fun toggleFlipped() {
    _state.update { it.copy(flippedBoard = !it.flippedBoard) }
  }

  fun setFlipped(flipped: Boolean) {
    _state.update { it.copy(flippedBoard = flipped) }
  }

  fun setTargetElo(elo: Int, debounceMs: Long = 160L) {
    val clamped = EloDepthMapper.clampElo(elo)
    val depth = EloDepthMapper.eloToStockfishDepth(clamped)
    if (_state.value.targetElo == clamped) return
    _state.update { it.copy(targetElo = clamped, engineDepth = depth) }
    triggerAnalysis(debounceMs = debounceMs)
  }

  fun setUseStockfishApi(enabled: Boolean) {
    if (_state.value.useStockfishApi == enabled) return
    _state.update { it.copy(useStockfishApi = enabled) }
    triggerAnalysis(debounceMs = 0L)
  }

  fun setAntiLagEnabled(enabled: Boolean) {
    if (_state.value.antiLagEnabled == enabled) return
    _state.update { it.copy(antiLagEnabled = enabled) }
    triggerAnalysis(debounceMs = 0L)
  }

  fun setShowFloatingStatsHud(show: Boolean) {
    _state.update { it.copy(showFloatingStatsHud = show) }
  }

  fun setArrowSettings(
    showSecondary: Boolean = _state.value.showSecondaryArrows,
    showThreat: Boolean = _state.value.showThreatArrow,
    opacity: Float = _state.value.arrowOpacity,
  ) {
    _state.update {
      it.copy(
        showSecondaryArrows = showSecondary,
        showThreatArrow = showThreat,
        arrowOpacity = opacity.coerceIn(0.35f, 1.0f),
      )
    }
  }

  fun setWatcherSettings(enabled: Boolean, packages: Set<String>) {
    _state.update { it.copy(watcherEnabled = enabled, watchedPackages = packages) }
  }

  fun setOverlayServiceState(
    running: Boolean,
    gridVisible: Boolean = _state.value.overlayGridVisible,
    passThrough: Boolean = _state.value.overlayPassThrough,
    tapSync: Boolean = _state.value.overlayTapSyncActive,
  ) {
    _state.update {
      it.copy(
        overlayServiceRunning = running,
        overlayGridVisible = gridVisible,
        overlayPassThrough = passThrough,
        overlayTapSyncActive = tapSync,
      )
    }
  }

  fun setDetectedForegroundApp(app: WatchedChessApp?) {
    _state.update { it.copy(detectedForegroundApp = app) }
  }

  fun updateOverlayBounds(x: Int, y: Int, sizePx: Int) {
    _state.update {
      it.copy(overlayBoardX = x, overlayBoardY = y, overlayBoardSizePx = sizePx)
    }
  }

  fun updateStatsHudPosition(x: Int, y: Int) {
    _state.update { it.copy(statsHudX = x, statsHudY = y) }
  }

  fun setAutoBoardScan(enabled: Boolean) {
    _state.update { it.copy(autoBoardScanEnabled = enabled) }
  }

  fun setVisionLearned(whiteBottom: Boolean, status: String) {
    _state.update {
      it.copy(
        visionTemplatesReady = true,
        visionWhiteBottom = whiteBottom,
        flippedBoard = !whiteBottom,
        boardCheckerStatus = status,
      )
    }
  }

  fun setBoardCheckerStatus(status: String) {
    _state.update { it.copy(boardCheckerStatus = status) }
  }

  fun setCaptureReadiness(
    shizukuRunning: Boolean = _state.value.shizukuRunning,
    shizukuReady: Boolean = _state.value.shizukuReady,
    mediaProjectionReady: Boolean = _state.value.mediaProjectionReady,
  ) {
    _state.update {
      it.copy(
        shizukuRunning = shizukuRunning,
        shizukuReady = shizukuReady,
        mediaProjectionReady = mediaProjectionReady,
      )
    }
  }

  /**
   * Applies a visually scanned board position. If a single legal move from the current position
   * produces the scanned piece placement, it plays that move so Move Grades (!!, !, ✓) update live.
   */
  fun applyScannedBoard(fullFen: String, whiteBottom: Boolean): Boolean {
    val scannedPlacement = fullFen.substringBefore(' ')
    val current = _state.value.board
    val currentPlacement = current.toFen().substringBefore(' ')
    if (currentPlacement == scannedPlacement) {
      _state.update {
        it.copy(
          flippedBoard = !whiteBottom,
          boardCheckerStatus = "Board verified (${if (current.sideToMove == PieceColor.WHITE) "White" else "Black"} to move)",
        )
      }
      return false
    }

    val legal = ChessRules.generateLegalMoves(current)
    val matchingMove =
      legal.firstOrNull { move ->
        (move.promotion == null || move.promotion == PieceType.QUEEN) &&
          ChessRules.applyMove(current, move).toFen().substringBefore(' ') == scannedPlacement
      }

    if (matchingMove != null) {
      _state.update {
        it.copy(
          flippedBoard = !whiteBottom,
          boardCheckerStatus = "Move detected & synced",
        )
      }
      return playMove(matchingMove)
    }

    val loaded = loadFen(fullFen, "Board Checker")
    if (loaded) {
      _state.update {
        it.copy(
          flippedBoard = !whiteBottom,
          boardCheckerStatus = "Position synced from screen",
        )
      }
    }
    return loaded
  }

  private fun maybeTriggerEngineReply() {
    val st = _state.value
    if (st.interactionMode != BoardInteractionMode.PLAY_VS_ENGINE) return
    if (st.board.sideToMove == st.humanPlaySide) return

    engineReplyJob?.cancel()
    engineReplyJob =
      scope.launch {
        delay(if (st.antiLagEnabled) 120L else 180L)
        val latest = _state.value
        if (
          latest.interactionMode == BoardInteractionMode.PLAY_VS_ENGINE &&
            latest.board.sideToMove != latest.humanPlaySide
        ) {
          val result =
            ChessEngine.analyzeHybrid(
              board = latest.board,
              targetElo = latest.targetElo,
              useStockfishApi = latest.useStockfishApi,
              antiLagEnabled = latest.antiLagEnabled,
            )
          val top = result.topMoves.firstOrNull() ?: return@launch
          playMove(top.move)
        }
      }
  }

  private fun applyMoveGradeIfMatching(analysis: EngineAnalysis) {
    val pending = pendingMoveGradeContext ?: return
    if (pending.fenAfter != analysis.fen) return

    val (quality, cpl) =
      MoveQualityEvaluator.classifyMove(
        snapshot = pending.snapshot,
        playedMove = pending.playedMove,
        postAnalysis = analysis,
      )

    _state.update { current ->
      val idx = pending.moveIndex
      if (idx !in current.moveHistory.indices) return@update current
      if (current.moveHistory[idx].fenAfter != analysis.fen) return@update current

      val updatedList = current.moveHistory.toMutableList()
      updatedList[idx] =
        updatedList[idx].copy(
          evalCpAfter = analysis.evalCp,
          quality = quality,
          centipawnLoss = cpl,
          gradedBy = analysis.engineSource,
        )
      current.copy(
        moveHistory = updatedList,
        moveStats = MoveQualityEvaluator.aggregateStats(updatedList, current.trackedPlayerSide),
      )
    }
  }

  fun triggerAnalysis(debounceMs: Long = 0L) {
    analysisJob?.cancel()
    val snapshotBoard = _state.value.board
    val targetElo = _state.value.targetElo
    val useStockfish = _state.value.useStockfishApi
    val antiLag = _state.value.antiLagEnabled
    _state.update { it.copy(isAnalyzing = true) }

    analysisJob =
      scope.launch {
        if (debounceMs > 0L) {
          delay(debounceMs)
        }
        val result =
          ChessEngine.analyzeHybrid(
            board = snapshotBoard,
            targetElo = targetElo,
            useStockfishApi = useStockfish,
            antiLagEnabled = antiLag,
            onQuickPreview = { preview ->
              _state.update { current ->
                if (current.board.toFen() == snapshotBoard.toFen()) {
                  current.copy(analysis = preview)
                } else {
                  current
                }
              }
              applyMoveGradeIfMatching(preview)
            },
          )
        _state.update { current ->
          if (current.board.toFen() == snapshotBoard.toFen()) {
            current.copy(analysis = result, isAnalyzing = false)
          } else {
            current
          }
        }
        applyMoveGradeIfMatching(result)
      }
  }
}
