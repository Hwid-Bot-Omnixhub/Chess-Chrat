package com.example.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chess.ChessEngine
import com.example.chess.ChessRules
import com.example.chess.OpeningLine
import com.example.chess.PieceColor
import com.example.chess.PieceType
import com.example.chess.Square
import com.example.chess.SuggestedMove
import com.example.chess.TacticalPuzzle
import com.example.chessreviewer.ShizukuCapture
import com.example.data.AssistantSharedState
import com.example.data.BoardInteractionMode
import com.example.data.ChessAppRegistry
import com.example.data.ChessAssistantHub
import com.example.data.InstalledChessAppStatus
import com.example.data.PreferencesRepository
import com.example.data.SystemAccessHelper
import com.example.data.SystemPermissionsState
import com.example.service.BoardVisionScanner
import com.example.service.ChessOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class KnightSightUiState(
  val shared: AssistantSharedState = AssistantSharedState(),
  val permissions: SystemPermissionsState = SystemPermissionsState(),
  val installedApps: List<InstalledChessAppStatus> = emptyList(),
  val selectedSquare: Square? = null,
  val legalDestinations: Set<Square> = emptySet(),
  val pendingPromotion: Pair<Square, Square>? = null,
  val statusMessage: String? = null,
  val isNativeStockfishAvailable: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val prefsRepo = PreferencesRepository(application)
  private val _uiState = MutableStateFlow(KnightSightUiState())
  val uiState: StateFlow<KnightSightUiState> = _uiState.asStateFlow()

  private val shizukuPermissionListener =
    Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
      if (requestCode == ShizukuCapture.SHIZUKU_PERMISSION_REQUEST_CODE) {
        BoardVisionScanner.refreshReadiness(getApplication())
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        _uiState.update {
          it.copy(
            statusMessage =
              if (granted) "Shizuku access granted (non-root ready)"
              else "Shizuku permission denied — you can still use Standard Screen Capture",
          )
        }
      }
    }

  private val shizukuBinderListener =
    Shizuku.OnBinderReceivedListener {
      BoardVisionScanner.refreshReadiness(getApplication())
    }

  private val shizukuDeadListener =
    Shizuku.OnBinderDeadListener {
      BoardVisionScanner.refreshReadiness(getApplication())
    }

  init {
    ChessEngine.initNativeEngine(application)
    BoardVisionScanner.ensureInitialized(application)
    runCatching {
      Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
      Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
      Shizuku.addBinderDeadListener(shizukuDeadListener)
    }

    viewModelScope.launch {
      val initialPrefs = prefsRepo.preferencesFlow.first()
      BoardVisionScanner.restoreSavedTemplates(
        initialPrefs.visionTemplates,
        initialPrefs.visionWhiteBottom,
      )
      ChessAssistantHub.applyPreferences(initialPrefs)
      refreshSystemStatus()
    }

    viewModelScope.launch {
      ChessAssistantHub.state.collect { shared ->
        _uiState.update { prev ->
          val boardChanged = prev.shared.board != shared.board
          prev.copy(
            shared = shared,
            selectedSquare = if (boardChanged) null else prev.selectedSquare,
            legalDestinations = if (boardChanged) emptySet() else prev.legalDestinations,
            pendingPromotion = if (boardChanged) null else prev.pendingPromotion,
            isNativeStockfishAvailable = ChessEngine.isNativeStockfishAvailable(),
          )
        }
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    runCatching {
      Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
      Shizuku.removeBinderReceivedListener(shizukuBinderListener)
      Shizuku.removeBinderDeadListener(shizukuDeadListener)
    }
  }

  fun refreshSystemStatus() {
    val ctx = getApplication<Application>()
    val perms = SystemAccessHelper.checkPermissions(ctx)
    val apps = ChessAppRegistry.getStatuses(ctx, ChessAssistantHub.state.value.watchedPackages)
    BoardVisionScanner.refreshReadiness(ctx)
    _uiState.update {
      it.copy(
        permissions = perms,
        installedApps = apps,
        isNativeStockfishAvailable = ChessEngine.isNativeStockfishAvailable(),
      )
    }
  }

  fun onSquareTapped(square: Square) {
    val st = _uiState.value
    val board = st.shared.board
    if (
      st.shared.interactionMode == BoardInteractionMode.PLAY_VS_ENGINE &&
        board.sideToMove != st.shared.humanPlaySide
    ) {
      return
    }

    val currentSel = st.selectedSquare
    if (currentSel == null) {
      val movesFrom = ChessRules.generateLegalMoves(board).filter { it.from == square }
      if (movesFrom.isNotEmpty()) {
        _uiState.update {
          it.copy(
            selectedSquare = square,
            legalDestinations = movesFrom.map { m -> m.to }.toSet(),
          )
        }
      }
      return
    }

    if (currentSel == square) {
      _uiState.update { it.copy(selectedSquare = null, legalDestinations = emptySet()) }
      return
    }

    if (square in st.legalDestinations) {
      val matchingMoves =
        ChessRules.generateLegalMoves(board).filter { it.from == currentSel && it.to == square }
      val hasPromotion = matchingMoves.any { it.promotion != null }
      if (hasPromotion) {
        _uiState.update { it.copy(pendingPromotion = currentSel to square) }
      } else {
        matchingMoves.firstOrNull()?.let { ChessAssistantHub.playMove(it) }
      }
      return
    }

    val movesFrom = ChessRules.generateLegalMoves(board).filter { it.from == square }
    if (movesFrom.isNotEmpty()) {
      _uiState.update {
        it.copy(
          selectedSquare = square,
          legalDestinations = movesFrom.map { m -> m.to }.toSet(),
        )
      }
    } else {
      _uiState.update { it.copy(selectedSquare = null, legalDestinations = emptySet()) }
    }
  }

  fun onPromotionChosen(pieceType: PieceType) {
    val pair = _uiState.value.pendingPromotion ?: return
    val legal = ChessRules.generateLegalMoves(_uiState.value.shared.board)
    val move =
      legal.find { it.from == pair.first && it.to == pair.second && it.promotion == pieceType }
    if (move != null) {
      ChessAssistantHub.playMove(move)
    }
  }

  fun dismissPromotion() {
    _uiState.update { it.copy(pendingPromotion = null) }
  }

  fun playSuggestedMove(suggested: SuggestedMove) {
    ChessAssistantHub.playMove(suggested.move)
  }

  fun undoMove() = ChessAssistantHub.undoMove()

  fun redoMove() = ChessAssistantHub.redoMove()

  fun resetBoard() = ChessAssistantHub.resetBoard()

  fun toggleFlipped() {
    ChessAssistantHub.toggleFlipped()
    val flipped = ChessAssistantHub.state.value.flippedBoard
    viewModelScope.launch { prefsRepo.updateFlippedBoard(flipped) }
  }

  fun setInteractionMode(mode: BoardInteractionMode) {
    ChessAssistantHub.setInteractionMode(mode)
  }

  fun setHumanPlaySide(side: PieceColor) {
    ChessAssistantHub.setHumanPlaySide(side)
  }

  fun setTargetElo(elo: Int) {
    ChessAssistantHub.setTargetElo(elo)
    val clamped = ChessAssistantHub.state.value.targetElo
    viewModelScope.launch { prefsRepo.updateTargetElo(clamped) }
  }

  fun setUseStockfishApi(enabled: Boolean) {
    ChessAssistantHub.setUseStockfishApi(enabled)
    viewModelScope.launch { prefsRepo.updateUseStockfishApi(enabled) }
  }

  fun setAntiLagEnabled(enabled: Boolean) {
    ChessAssistantHub.setAntiLagEnabled(enabled)
    viewModelScope.launch { prefsRepo.updateAntiLagEnabled(enabled) }
  }

  fun setShowFloatingStatsHud(show: Boolean) {
    ChessAssistantHub.setShowFloatingStatsHud(show)
    viewModelScope.launch { prefsRepo.updateShowFloatingStatsHud(show) }
  }

  fun cycleTrackedPlayerSide() {
    val cur = ChessAssistantHub.state.value.trackedPlayerSide
    val next =
      when (cur) {
        PieceColor.WHITE -> PieceColor.BLACK
        PieceColor.BLACK -> null
        null -> PieceColor.WHITE
      }
    ChessAssistantHub.setTrackedPlayerSide(next)
    viewModelScope.launch { prefsRepo.updateTrackedPlayerSide(next) }
  }

  fun setShowSecondaryArrows(show: Boolean) {
    ChessAssistantHub.setArrowSettings(showSecondary = show)
    viewModelScope.launch { prefsRepo.updateShowSecondaryArrows(show) }
  }

  fun setShowThreatArrow(show: Boolean) {
    ChessAssistantHub.setArrowSettings(showThreat = show)
    viewModelScope.launch { prefsRepo.updateShowThreatArrow(show) }
  }

  fun setArrowOpacity(opacity: Float) {
    ChessAssistantHub.setArrowSettings(opacity = opacity)
    viewModelScope.launch { prefsRepo.updateArrowOpacity(opacity) }
  }

  fun setWatcherEnabled(enabled: Boolean) {
    val pkgs = ChessAssistantHub.state.value.watchedPackages
    ChessAssistantHub.setWatcherSettings(enabled, pkgs)
    viewModelScope.launch { prefsRepo.updateWatcherEnabled(enabled) }
  }

  fun toggleWatchedApp(packageName: String) {
    val current = ChessAssistantHub.state.value.watchedPackages
    val updated = if (packageName in current) current - packageName else current + packageName
    ChessAssistantHub.setWatcherSettings(ChessAssistantHub.state.value.watcherEnabled, updated)
    viewModelScope.launch { prefsRepo.updateWatchedPackages(updated) }
    refreshSystemStatus()
  }

  fun setOverlayPassThrough(passThrough: Boolean) {
    val cur = ChessAssistantHub.state.value
    ChessAssistantHub.setOverlayServiceState(
      running = cur.overlayServiceRunning,
      gridVisible = cur.overlayGridVisible,
      passThrough = passThrough,
      tapSync = if (passThrough) false else cur.overlayTapSyncActive,
    )
  }

  fun requestShizukuPermission() {
    val ctx = getApplication<Application>()
    BoardVisionScanner.refreshReadiness(ctx)
    val requested = BoardVisionScanner.requestShizukuPermission(ctx)
    if (requested) {
      val readyNow = BoardVisionScanner.ensureInitialized(ctx).shizuku.ready()
      _uiState.update {
        it.copy(
          statusMessage =
            if (readyNow) "Shizuku connected (uid 2000 shell non-root ready)"
            else "Approve the Shizuku permission popup to enable capture",
        )
      }
      return
    }
    val opened = SystemAccessHelper.launchApp(ctx, "moe.shizuku.privileged.api")
    _uiState.update {
      it.copy(
        statusMessage =
          if (opened) "Start Shizuku via Wireless Debugging, then switch back here"
          else "Shizuku not running — use Enable Screen Capture (No Root) below",
      )
    }
  }

  fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
    val ctx = getApplication<Application>()
    if (resultCode != Activity.RESULT_OK || data == null) {
      _uiState.update { it.copy(statusMessage = "Screen capture permission cancelled") }
      return
    }
    val perms = SystemAccessHelper.checkPermissions(ctx)
    if (!perms.isOverlayReady) {
      _uiState.update {
        it.copy(statusMessage = "Grant Overlay & Notification permissions first")
      }
      return
    }
    ChessAssistantHub.setAutoBoardScan(true)
    viewModelScope.launch { prefsRepo.updateAutoBoardScan(true) }
    ChessAssistantHub.setOverlayServiceState(
      running = true,
      gridVisible = true,
      passThrough = true,
      tapSync = false,
    )
    ChessOverlayService.startWithProjection(ctx, resultCode, data)
    _uiState.update {
      it.copy(statusMessage = "Non-root Screen Capture ready — Auto Board Finder active")
    }
  }

  fun learnBoardFromScreen() {
    val ctx = getApplication<Application>()
    if (_uiState.value.shared.overlayServiceRunning) {
      ChessOverlayService.triggerLearn(ctx)
      return
    }
    viewModelScope.launch {
      val outcome = BoardVisionScanner.learnStartingBoard(ctx, prefsRepo)
      _uiState.update { it.copy(statusMessage = outcome.message) }
    }
  }

  fun scanBoardOnce() {
    val ctx = getApplication<Application>()
    if (_uiState.value.shared.overlayServiceRunning) {
      ChessOverlayService.triggerScan(ctx)
      return
    }
    viewModelScope.launch {
      val outcome = BoardVisionScanner.scanBoardOnce(ctx, prefsRepo = prefsRepo, isAuto = false)
      _uiState.update { it.copy(statusMessage = outcome.message) }
    }
  }

  fun setAutoBoardScan(enabled: Boolean) {
    ChessAssistantHub.setAutoBoardScan(enabled)
    viewModelScope.launch { prefsRepo.updateAutoBoardScan(enabled) }
  }

  fun importFenOrPgn(rawInput: String): Boolean {
    val trimmed = rawInput.trim()
    val loaded =
      if (trimmed.contains('/') && trimmed.split(' ').firstOrNull()?.count { it == '/' } == 7) {
        ChessAssistantHub.loadFen(trimmed, "Imported FEN")
      } else {
        ChessAssistantHub.loadPgn(trimmed, "Imported PGN")
      }
    _uiState.update {
      it.copy(
        statusMessage =
          if (loaded) "Position loaded with live arrows" else "Invalid FEN or PGN sequence",
      )
    }
    return loaded
  }

  fun loadPuzzle(puzzle: TacticalPuzzle) {
    ChessAssistantHub.setInteractionMode(BoardInteractionMode.ASSIST_ARROWS)
    ChessAssistantHub.setFlipped(puzzle.sideToMove == PieceColor.BLACK)
    ChessAssistantHub.loadFen(puzzle.fen, puzzle.title)
    _uiState.update { it.copy(statusMessage = "Loaded drill: ${puzzle.title}") }
  }

  fun loadOpening(opening: OpeningLine) {
    ChessAssistantHub.setInteractionMode(BoardInteractionMode.ASSIST_ARROWS)
    ChessAssistantHub.setFlipped(false)
    ChessAssistantHub.loadPgn(opening.movesPgn, opening.name)
    _uiState.update { it.copy(statusMessage = "Loaded opening: ${opening.name}") }
  }

  fun toggleOverlayService(): Boolean {
    val ctx = getApplication<Application>()
    refreshSystemStatus()
    val perms = _uiState.value.permissions
    if (!perms.canDrawOverlays) {
      _uiState.update {
        it.copy(statusMessage = "Grant 'Draw over other apps' first to launch the arrow overlay.")
      }
      return false
    }
    if (!perms.hasNotificationPermission) {
      _uiState.update {
        it.copy(statusMessage = "Allow notifications so the Watcher service stays active.")
      }
      return false
    }
    val running = _uiState.value.shared.overlayServiceRunning
    if (running) {
      ChessOverlayService.stop(ctx)
      _uiState.update { it.copy(statusMessage = "Floating Arrow Overlay stopped") }
    } else {
      // Touch-through ON by default so the real chess game underneath stays 100% playable!
      ChessAssistantHub.setOverlayServiceState(
        running = true,
        gridVisible = true,
        passThrough = true,
        tapSync = false,
      )
      ChessOverlayService.start(ctx)
      _uiState.update {
        it.copy(
          statusMessage =
            "Overlay started (touch-through ON — tap Align in the floating bar to position frame)",
        )
      }
    }
    return true
  }

  fun launchChessApp(packageName: String, displayName: String) {
    val ctx = getApplication<Application>()
    val perms = SystemAccessHelper.checkPermissions(ctx)
    if (perms.isOverlayReady && !_uiState.value.shared.overlayServiceRunning) {
      ChessAssistantHub.setOverlayServiceState(
        running = true,
        gridVisible = true,
        passThrough = true,
        tapSync = false,
      )
      ChessOverlayService.start(ctx)
    }
    val launched = SystemAccessHelper.launchApp(ctx, packageName)
    if (!launched) {
      _uiState.update { it.copy(statusMessage = "Could not launch $displayName") }
    }
  }

  fun showMessage(msg: String) {
    _uiState.update { it.copy(statusMessage = msg) }
  }

  fun clearMessage() {
    _uiState.update { it.copy(statusMessage = null) }
  }
}
