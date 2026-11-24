package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.os.Build
import android.view.WindowManager
import com.example.chess.BoardState
import com.example.chessreviewer.BoardFinder
import com.example.chessreviewer.Chess
import com.example.chessreviewer.ProjectionCapture
import com.example.chessreviewer.SecureCapture
import com.example.chessreviewer.Templates
import com.example.chessreviewer.Tracker
import com.example.chessreviewer.Vision
import com.example.data.AssistantSharedState
import com.example.data.ChessAssistantHub
import com.example.data.PreferencesRepository
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Watches active screens via Shizuku's [com.example.chessreviewer.ISecureShot] or MediaProjection,
 * localizes any 8x8 chessboard via [BoardFinder], reads pieces with [Vision], and tracks turns
 * with [Tracker] so live arrows follow real games automatically.
 */
object BoardVisionScanner {

  private val scanMutex = Mutex()
  private val scannerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile
  private var secureCapture: SecureCapture? = null

  private val projectionCapture = ProjectionCapture()
  private val tracker = Tracker()

  @Volatile
  private var cachedTemplates: Templates? = null

  @Volatile
  private var cachedWhiteBottom: Boolean = true

  @Volatile
  private var lastPixelSignature: Long = 0L

  @Volatile
  private var lastAppliedPlacement: String? = null

  @Volatile
  private var unchangedStreak: Int = 0

  fun resetCaptureThrottle() {
    unchangedStreak = 0
  }

  /**
   * Adaptive delay between screen captures: fast right after a move or screen transition,
   * progressively backing off when the board state remains unchanged to save CPU and avoid flicker.
   */
  fun recommendedLoopDelayMs(
    antiLagEnabled: Boolean,
    streak: Int = unchangedStreak,
  ): Long =
    when {
      streak <= 0 -> if (antiLagEnabled) 750L else 550L
      streak <= 2 -> if (antiLagEnabled) 1300L else 950L
      streak <= 6 -> if (antiLagEnabled) 2200L else 1600L
      else -> if (antiLagEnabled) 3200L else 2500L
    }

  fun ensureInitialized(context: Context): SecureCapture {
    val existing = secureCapture
    if (existing != null) return existing
    return synchronized(this) {
      secureCapture ?: SecureCapture(context.applicationContext, context.cacheDir).also {
        secureCapture = it
      }
    }
  }

  fun restoreSavedTemplates(serialized: String?, whiteBottom: Boolean) {
    if (serialized.isNullOrBlank()) return
    val parsed = Templates.parse(serialized) ?: return
    cachedTemplates = parsed
    cachedWhiteBottom = whiteBottom
  }

  fun refreshReadiness(context: Context) {
    val sc = ensureInitialized(context)
    val shizukuReady = sc.shizuku.ready()
    if (shizukuReady && !sc.shizuku.isBound()) {
      scannerScope.launch {
        sc.shizuku.bindService(timeoutMs = 5_000L)
      }
    }
    ChessAssistantHub.setCaptureReadiness(
      shizukuInstalled = sc.shizuku.isAppInstalled(),
      shizukuRunning = sc.shizuku.isRunning(),
      shizukuReady = shizukuReady,
      mediaProjectionReady = projectionCapture.isActive,
    )
  }

  fun requestShizukuPermission(context: Context): Boolean {
    val sc = ensureInitialized(context)
    val ok = sc.shizuku.requestPermission()
    refreshReadiness(context)
    if (!ok && sc.shizuku.lastError.isNotBlank()) {
      ChessAssistantHub.setBoardCheckerStatus(sc.shizuku.lastError)
    }
    return ok
  }

  fun startMediaProjection(
    context: Context,
    projection: MediaProjection,
    screenWidth: Int,
    screenHeight: Int,
    densityDpi: Int,
  ) {
    projectionCapture.start(projection, screenWidth, screenHeight, densityDpi)
    resetCaptureThrottle()
    refreshReadiness(context)
    ChessAssistantHub.setBoardCheckerStatus(
      if (projectionCapture.isActive) {
        "Watching for Chess.com / Lichess board..."
      } else {
        projectionCapture.lastError.ifBlank { "Could not start screen capture" }
      },
    )
  }

  fun stopMediaProjection(context: Context? = null) {
    projectionCapture.stop()
    context?.let { refreshReadiness(it) }
  }

  fun releaseAll() {
    projectionCapture.stop()
    secureCapture?.release()
  }

  fun isCaptureAvailable(context: Context): Boolean {
    val sc = ensureInitialized(context)
    return sc.shizuku.ready() ||
      sc.shizuku.isBound() ||
      projectionCapture.isActive ||
      sc.mode == SecureCapture.Mode.ROOT ||
      sc.mode == SecureCapture.Mode.SHIZUKU
  }

  data class ScanOutcome(val success: Boolean, val message: String)

  private data class CaptureAndRect(
    val frame: Bitmap?,
    val rect: BoardFinder.BoardRect?,
    val error: String = "",
    val unchangedFrame: Boolean = false,
  )

  private data class LocatedBoard(
    val downscaled: IntArray,
    val boardSizePx: Int,
  )

  suspend fun learnStartingBoard(
    context: Context,
    prefsRepo: PreferencesRepository,
    pulseHideOverlay: (suspend (Boolean) -> Unit)? = null,
  ): ScanOutcome =
    scanMutex.withLock {
      resetCaptureThrottle()
      val appCtx = context.applicationContext
      val captured = captureAndFindBoard(appCtx, pulseHideOverlay)
      val frame = captured.frame
      if (frame == null) {
        val msg = captured.error.ifBlank { captureFailureMessage(appCtx) }
        ChessAssistantHub.setBoardCheckerStatus(msg)
        return@withLock ScanOutcome(false, msg)
      }

      val st = ChessAssistantHub.state.value
      val located =
        try {
          extractLocatedBoard(appCtx, frame, captured.rect, st, prefsRepo)
        } finally {
          frame.recycle()
        }

      if (located == null) {
        ChessAssistantHub.setBoardVisibleOnScreen(false)
        val msg = "No 8×8 chessboard visible on screen"
        ChessAssistantHub.setBoardCheckerStatus(msg)
        return@withLock ScanOutcome(false, msg)
      }

      val result = withContext(Dispatchers.Default) { Vision.learn(located.downscaled) }
      val tpl = result.templates
      if (tpl == null || result.error != null) {
        val err = result.error ?: "Could not learn starting position"
        ChessAssistantHub.setBoardCheckerStatus(err)
        return@withLock ScanOutcome(false, err)
      }

      val sideLabel = if (result.whiteBottom) "White at bottom" else "Black at bottom"
      val okMsg = "Board found & piece theme learned ($sideLabel)"
      applyLearnedPosition(
        tpl = tpl,
        whiteBottom = result.whiteBottom,
        downscaled = located.downscaled,
        sig = computeSignature(located.downscaled),
        prefsRepo = prefsRepo,
        statusMessage = okMsg,
      )
      ScanOutcome(true, okMsg)
    }

  suspend fun scanBoardOnce(
    context: Context,
    prefsRepo: PreferencesRepository? = null,
    isAuto: Boolean = false,
    pulseHideOverlay: (suspend (Boolean) -> Unit)? = null,
  ): ScanOutcome =
    scanMutex.withLock {
      val appCtx = context.applicationContext
      val captured = captureAndFindBoard(appCtx, pulseHideOverlay)
      if (captured.unchangedFrame) {
        val visible = ChessAssistantHub.state.value.isBoardVisibleOnScreen
        unchangedStreak = if (visible) (unchangedStreak + 1).coerceAtMost(100) else 0
        return@withLock ScanOutcome(true, "Board unchanged")
      }

      val frame = captured.frame
      if (frame == null) {
        unchangedStreak = (unchangedStreak + 1).coerceAtMost(100)
        val msg = captured.error.ifBlank { captureFailureMessage(appCtx) }
        if (!isAuto) ChessAssistantHub.setBoardCheckerStatus(msg)
        return@withLock ScanOutcome(false, msg)
      }

      val st = ChessAssistantHub.state.value
      val located =
        try {
          extractLocatedBoard(appCtx, frame, captured.rect, st, prefsRepo)
        } finally {
          frame.recycle()
        }

      if (located == null) {
        val wasVisible = st.isBoardVisibleOnScreen
        lastPixelSignature = 0L
        ChessAssistantHub.setBoardVisibleOnScreen(false)
        unchangedStreak = if (wasVisible) 0 else (unchangedStreak + 1).coerceAtMost(100)
        val msg = "Waiting for chessboard..."
        if (!isAuto) ChessAssistantHub.setBoardCheckerStatus(msg)
        return@withLock ScanOutcome(false, msg)
      }

      val sig = computeSignature(located.downscaled)
      val existingTemplates = cachedTemplates

      // Anti-lag optimization: skip Vision classification and back off when pixels are identical
      if (isAuto && existingTemplates != null && sig == lastPixelSignature && lastAppliedPlacement != null) {
        ChessAssistantHub.setBoardVisibleOnScreen(true)
        unchangedStreak = (unchangedStreak + 1).coerceAtMost(100)
        return@withLock ScanOutcome(true, "Board synced")
      }

      // Auto-learn automatically whenever a starting position appears
      if (existingTemplates == null) {
        val autoLearn = withContext(Dispatchers.Default) { Vision.learn(located.downscaled) }
        val newTpl = autoLearn.templates
        if (newTpl != null && autoLearn.error == null) {
          val sideLabel = if (autoLearn.whiteBottom) "White" else "Black"
          val msg = "Game detected ($sideLabel at bottom)"
          applyLearnedPosition(
            tpl = newTpl,
            whiteBottom = autoLearn.whiteBottom,
            downscaled = located.downscaled,
            sig = sig,
            prefsRepo = prefsRepo,
            statusMessage = msg,
          )
          resetCaptureThrottle()
          return@withLock ScanOutcome(true, msg)
        } else {
          ChessAssistantHub.setBoardVisibleOnScreen(true)
          unchangedStreak = (unchangedStreak + 1).coerceAtMost(100)
          val msg = "Board found — start a new game or tap Learn Start"
          ChessAssistantHub.setBoardCheckerStatus(msg)
          return@withLock ScanOutcome(false, msg)
        }
      }

      var effectiveWhiteBottom = !st.flippedBoard
      var pos =
        withContext(Dispatchers.Default) {
          Vision.readPosition(located.downscaled, existingTemplates, effectiveWhiteBottom)
        }

      if (pos == null && Vision.lastProblem.contains("pawn on rank 1 or 8")) {
        val flippedPos =
          withContext(Dispatchers.Default) {
            Vision.readPosition(located.downscaled, existingTemplates, !effectiveWhiteBottom)
          }
        if (flippedPos != null) {
          effectiveWhiteBottom = !effectiveWhiteBottom
          pos = flippedPos
        }
      }

      // Check if a brand-new starting game or new piece theme started
      if (pos == null) {
        val relearn = withContext(Dispatchers.Default) { Vision.learn(located.downscaled) }
        val newTpl = relearn.templates
        if (newTpl != null && relearn.error == null) {
          val sideLabel = if (relearn.whiteBottom) "White" else "Black"
          val msg = "New game started ($sideLabel at bottom)"
          applyLearnedPosition(
            tpl = newTpl,
            whiteBottom = relearn.whiteBottom,
            downscaled = located.downscaled,
            sig = sig,
            prefsRepo = prefsRepo,
            statusMessage = msg,
          )
          resetCaptureThrottle()
          return@withLock ScanOutcome(true, msg)
        }
      }

      if (pos == null) {
        unchangedStreak = (unchangedStreak + 1).coerceAtMost(100)
        val problem = Vision.lastProblem.ifBlank { "Reading board..." }
        if (!isAuto) ChessAssistantHub.setBoardCheckerStatus(problem)
        return@withLock ScanOutcome(false, problem)
      }

      lastPixelSignature = sig
      if (Chess.placement(pos) == Chess.START) {
        tracker.reset()
      }
      tracker.update(pos, effectiveWhiteBottom)
      val fen = tracker.fen(pos)
      val placement = fen.substringBefore(' ')
      if (isAuto && placement == lastAppliedPlacement && effectiveWhiteBottom == !st.flippedBoard) {
        ChessAssistantHub.setBoardVisibleOnScreen(true)
        unchangedStreak = (unchangedStreak + 1).coerceAtMost(100)
        return@withLock ScanOutcome(true, "Board synced")
      }

      resetCaptureThrottle()
      lastAppliedPlacement = placement
      ChessAssistantHub.applyScannedBoard(fen, effectiveWhiteBottom)
      ScanOutcome(true, "Watching (${if (tracker.blackToMove) "Black" else "White"} to move)")
    }

  private suspend fun applyLearnedPosition(
    tpl: Templates,
    whiteBottom: Boolean,
    downscaled: IntArray,
    sig: Long,
    prefsRepo: PreferencesRepository?,
    statusMessage: String,
  ) {
    cachedTemplates = tpl
    cachedWhiteBottom = whiteBottom
    tracker.reset()
    val readCheck =
      withContext(Dispatchers.Default) {
        Vision.readPosition(downscaled, tpl, whiteBottom)
      }
    val fenToLoad =
      if (readCheck != null) {
        tracker.update(readCheck, whiteBottom)
        lastAppliedPlacement = Chess.placement(readCheck)
        tracker.fen(readCheck)
      } else {
        lastAppliedPlacement = Chess.START
        BoardState.STARTING_FEN
      }
    lastPixelSignature = sig
    if (prefsRepo != null) {
      withContext(Dispatchers.IO) {
        prefsRepo.saveVisionTemplates(tpl.serialize(), whiteBottom)
      }
    }
    ChessAssistantHub.loadFen(fenToLoad, "Board Checker")
    ChessAssistantHub.setVisionLearned(whiteBottom, statusMessage)
  }

  private suspend fun extractLocatedBoard(
    context: Context,
    frame: Bitmap,
    rect: BoardFinder.BoardRect?,
    fallbackState: AssistantSharedState,
    prefsRepo: PreferencesRepository?,
  ): LocatedBoard? {
    val foundRect = rect ?: return null
    val fw = frame.width
    val fh = frame.height
    if (fw < 64 || fh < 64) return null

    val (dispW, dispH) = displayDimensions(context, fw, fh)
    val scaleX = dispW.toFloat() / fw.toFloat()
    val scaleY = dispH.toFloat() / fh.toFloat()
    val overlayX = (foundRect.x * scaleX).roundToInt().coerceAtLeast(0)
    val overlayY = (foundRect.y * scaleY).roundToInt().coerceAtLeast(0)
    val overlaySize = (foundRect.size * ((scaleX + scaleY) * 0.5f)).roundToInt()
      .coerceIn(120, minOf(dispW, dispH))

    val shifted =
      abs(fallbackState.overlayBoardX - overlayX) > 2 ||
        abs(fallbackState.overlayBoardY - overlayY) > 2 ||
        abs(fallbackState.overlayBoardSizePx - overlaySize) > 2

    if (shifted) {
      ChessAssistantHub.updateOverlayBounds(overlayX, overlayY, overlaySize)
      if (prefsRepo != null) {
        withContext(Dispatchers.IO) {
          prefsRepo.saveOverlayBounds(overlayX, overlayY, overlaySize)
        }
      }
    }

    val downscaled =
      withContext(Dispatchers.Default) {
        cropAndDownscale(frame, foundRect.x, foundRect.y, foundRect.size)
      } ?: return null

    return LocatedBoard(downscaled = downscaled, boardSizePx = overlaySize)
  }

  private fun displayDimensions(context: Context, fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val bounds = wm.maximumWindowMetrics.bounds
      if (bounds.width() > 0 && bounds.height() > 0) {
        return bounds.width() to bounds.height()
      }
    }
    val dm = context.resources.displayMetrics
    return if (dm.widthPixels > 0 && dm.heightPixels > 0) {
      dm.widthPixels to dm.heightPixels
    } else {
      fallbackW to fallbackH
    }
  }

  /**
   * Captures a frame cleanly (hiding the overlay briefly so our arrows never obscure squares)
   * and localizes any chessboard using [BoardFinder.findFromSecureShot] when Shizuku
   * [com.example.chessreviewer.ISecureShot] is available, or [BoardFinder.find] over MediaProjection frames.
   */
  private suspend fun captureAndFindBoard(
    context: Context,
    pulseHideOverlay: (suspend (Boolean) -> Unit)?,
  ): CaptureAndRect {
    val sc = ensureInitialized(context)
    val shouldHideOverlay =
      pulseHideOverlay != null &&
        ChessAssistantHub.state.value.overlayGridVisible &&
        ChessAssistantHub.state.value.isBoardVisibleOnScreen

    val seqBeforeHide = if (projectionCapture.isActive) projectionCapture.currentFrameSequence() else -1L
    if (shouldHideOverlay) {
      pulseHideOverlay!!.invoke(true)
      // Allow SurfaceFlinger to composite a clean frame without our red/dark arrows
      delay(95L)
    }

    return try {
      withContext(Dispatchers.IO) {
        val shot =
          if (sc.shizuku.ready()) {
            sc.shizuku.bindService(if (sc.shizuku.isBound()) 2_500L else 5_000L)
          } else {
            null
          }
        if (shot != null) {
          val res = BoardFinder.findFromSecureShot(shot)
          if (res.frame != null) {
            return@withContext CaptureAndRect(res.frame, res.boardRect, res.error)
          }
        }

        if (projectionCapture.isActive) {
          val projBmp = projectionCapture.grab(
            waitAfterSeq = if (shouldHideOverlay) seqBeforeHide else -1L,
            waitForFreshMs = if (shouldHideOverlay) 140L else 0L,
          )
          return@withContext if (projBmp != null) {
            val rect = BoardFinder.find(projBmp)
            CaptureAndRect(projBmp, rect)
          } else if (projectionCapture.wasNoNewFrame) {
            CaptureAndRect(null, null, error = "", unchangedFrame = true)
          } else {
            CaptureAndRect(null, null, captureFailureMessage(context))
          }
        }

        // Only try non-Shizuku SecureCapture fallback (root screencap) so Shizuku is never double-bound
        if (sc.mode == SecureCapture.Mode.ROOT) {
          val rootBmp = sc.grab()
          if (rootBmp != null) {
            val rect = BoardFinder.find(rootBmp)
            return@withContext CaptureAndRect(rootBmp, rect)
          }
        }

        CaptureAndRect(null, null, captureFailureMessage(context))
      }
    } finally {
      if (shouldHideOverlay) {
        pulseHideOverlay!!.invoke(false)
      }
      refreshReadiness(context)
    }
  }

  private fun captureFailureMessage(context: Context): String {
    val sc = ensureInitialized(context)
    return when {
      sc.shizuku.isRunning() && !sc.shizuku.ready() ->
        "Grant Shizuku permission or enable Screen Capture in Watcher tab"
      projectionCapture.lastError.isNotBlank() -> projectionCapture.lastError
      sc.shizuku.lastError.isNotBlank() -> sc.shizuku.lastError
      sc.lastError.isNotBlank() -> sc.lastError
      else -> "Enable Screen Capture (No Root) or start Shizuku first"
    }
  }

  private fun cropAndDownscale(
    frame: Bitmap,
    boardX: Int,
    boardY: Int,
    boardSizePx: Int,
  ): IntArray? {
    val fw = frame.width
    val fh = frame.height
    if (fw < 64 || fh < 64) return null

    val size = boardSizePx.coerceIn(64, minOf(fw, fh))
    val left = boardX.coerceIn(0, (fw - size).coerceAtLeast(0))
    val top = boardY.coerceIn(0, (fh - size).coerceAtLeast(0))
    val actualSize = minOf(size, fw - left, fh - top)
    if (actualSize < 64) return null

    val pixels = IntArray(actualSize * actualSize)
    frame.getPixels(pixels, 0, actualSize, left, top, actualSize, actualSize)
    return Vision.downscale(pixels, actualSize)
  }

  private fun computeSignature(downscaled: IntArray): Long {
    var hash = -3750763034362895579L
    val offsets = intArrayOf(Vision.N / 4, Vision.N / 2, (Vision.N * 3) / 4)
    for (r in 0 until 8) {
      val ry = r * Vision.N
      for (c in 0 until 8) {
        val cx = c * Vision.N
        for (oy in offsets) {
          val py = ry + oy
          for (ox in offsets) {
            val px = cx + ox
            val color = downscaled[py * Vision.B + px]
            val quantized = ((color shr 19) and 0x1F shl 10) or
              ((color shr 11) and 0x1F shl 5) or
              ((color shr 3) and 0x1F)
            hash = (hash xor quantized.toLong()) * 1099511628211L
          }
        }
      }
    }
    return hash
  }
}
