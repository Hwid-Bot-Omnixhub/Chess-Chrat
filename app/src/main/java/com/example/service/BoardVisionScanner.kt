package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import com.example.chess.BoardState
import com.example.chessreviewer.ProjectionCapture
import com.example.chessreviewer.SecureCapture
import com.example.chessreviewer.Templates
import com.example.chessreviewer.Tracker
import com.example.chessreviewer.Vision
import com.example.data.ChessAssistantHub
import com.example.data.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates non-root screen capture (Shizuku UserService OR standard MediaProjection)
 * with [Vision] and [Tracker] to check and verify live chess boards without lag.
 */
object BoardVisionScanner {

  private val scanMutex = Mutex()

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
    ChessAssistantHub.setCaptureReadiness(
      shizukuRunning = sc.shizuku.isRunning(),
      shizukuReady = sc.shizuku.ready(),
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
    refreshReadiness(context)
    ChessAssistantHub.setBoardCheckerStatus(
      if (projectionCapture.isActive) {
        "Screen capture ready (no root) — align frame & tap Learn"
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
    return sc.shizuku.ready() || projectionCapture.isActive || sc.mode == SecureCapture.Mode.ROOT
  }

  fun describeCaptureMethod(context: Context): String {
    val sc = ensureInitialized(context)
    return when {
      sc.shizuku.ready() -> "Shizuku Active (Non-Root • Protected Boards OK)"
      projectionCapture.isActive -> "Standard Screen Capture Active (Non-Root)"
      sc.shizuku.isRunning() -> "Shizuku Running — Tap Grant Access"
      else -> "Not Ready — Enable Screen Capture or Start Shizuku"
    }
  }

  data class ScanOutcome(val success: Boolean, val message: String)

  suspend fun learnStartingBoard(
    context: Context,
    prefsRepo: PreferencesRepository,
    pulseHideOverlay: (suspend (Boolean) -> Unit)? = null,
  ): ScanOutcome =
    scanMutex.withLock {
      val appCtx = context.applicationContext
      val frame = captureCleanScreen(appCtx, pulseHideOverlay)
        ?: return@withLock ScanOutcome(false, captureFailureMessage(appCtx))

      val st = ChessAssistantHub.state.value
      val downscaled =
        withContext(Dispatchers.Default) {
          cropAndDownscale(frame, st.overlayBoardX, st.overlayBoardY, st.overlayBoardSizePx)
        }
      frame.recycle()

      if (downscaled == null) {
        val msg = "Board frame is outside the screen — align the 8×8 box over the board"
        ChessAssistantHub.setBoardCheckerStatus(msg)
        return@withLock ScanOutcome(false, msg)
      }

      val result = withContext(Dispatchers.Default) { Vision.learn(downscaled) }
      val tpl = result.templates
      if (tpl == null || result.error != null) {
        val err = result.error ?: "Could not learn starting position"
        ChessAssistantHub.setBoardCheckerStatus(err)
        return@withLock ScanOutcome(false, err)
      }

      cachedTemplates = tpl
      cachedWhiteBottom = result.whiteBottom
      lastPixelSignature = 0L
      lastAppliedPlacement = null
      tracker.reset()

      withContext(Dispatchers.IO) {
        prefsRepo.saveVisionTemplates(tpl.serialize(), result.whiteBottom)
      }

      ChessAssistantHub.loadFen(BoardState.STARTING_FEN, "Board Checker")
      val sideLabel = if (result.whiteBottom) "White at bottom" else "Black at bottom"
      val okMsg = "Learned piece theme ($sideLabel). Tap Auto or Scan anytime."
      ChessAssistantHub.setVisionLearned(result.whiteBottom, okMsg)
      ScanOutcome(true, okMsg)
    }

  suspend fun scanBoardOnce(
    context: Context,
    isAuto: Boolean = false,
    pulseHideOverlay: (suspend (Boolean) -> Unit)? = null,
  ): ScanOutcome =
    scanMutex.withLock {
      val appCtx = context.applicationContext
      val templates = cachedTemplates
      if (templates == null) {
        val msg = "Tap Learn on a starting board first"
        if (!isAuto) ChessAssistantHub.setBoardCheckerStatus(msg)
        return@withLock ScanOutcome(false, msg)
      }

      val frame = captureCleanScreen(appCtx, pulseHideOverlay)
      if (frame == null) {
        val msg = captureFailureMessage(appCtx)
        if (!isAuto) ChessAssistantHub.setBoardCheckerStatus(msg)
        return@withLock ScanOutcome(false, msg)
      }

      val st = ChessAssistantHub.state.value
      val downscaled =
        withContext(Dispatchers.Default) {
          cropAndDownscale(frame, st.overlayBoardX, st.overlayBoardY, st.overlayBoardSizePx)
        }
      frame.recycle()

      if (downscaled == null) {
        val msg = "Board frame outside screen bounds"
        if (!isAuto) ChessAssistantHub.setBoardCheckerStatus(msg)
        return@withLock ScanOutcome(false, msg)
      }

      // Anti-lag optimization: skip full Vision classification if cropped pixels didn't change
      val sig = computeSignature(downscaled)
      if (isAuto && sig == lastPixelSignature && lastAppliedPlacement != null) {
        return@withLock ScanOutcome(true, "Board unchanged")
      }

      val effectiveWhiteBottom = !st.flippedBoard

      val pos =
        withContext(Dispatchers.Default) {
          Vision.readPosition(downscaled, templates, effectiveWhiteBottom)
        }

      if (pos == null) {
        val problem = Vision.lastProblem.ifBlank { "Board not recognized — check frame alignment" }
        if (!isAuto) ChessAssistantHub.setBoardCheckerStatus(problem)
        return@withLock ScanOutcome(false, problem)
      }

      lastPixelSignature = sig
      tracker.update(pos, effectiveWhiteBottom)
      val fen = tracker.fen(pos)
      val placement = fen.substringBefore(' ')
      if (isAuto && placement == lastAppliedPlacement) {
        return@withLock ScanOutcome(true, "Board synced")
      }

      lastAppliedPlacement = placement
      ChessAssistantHub.applyScannedBoard(fen, effectiveWhiteBottom)
      val statusMsg =
        "Board checked (${if (tracker.blackToMove) "Black" else "White"} to move)"
      ChessAssistantHub.setBoardCheckerStatus(statusMsg)
      ScanOutcome(true, statusMsg)
    }

  private suspend fun captureCleanScreen(
    context: Context,
    pulseHideOverlay: (suspend (Boolean) -> Unit)?,
  ): Bitmap? {
    val sc = ensureInitialized(context)
    val shouldPulse = pulseHideOverlay != null && ChessAssistantHub.state.value.overlayGridVisible
    if (shouldPulse) {
      pulseHideOverlay?.invoke(true)
      delay(65L)
    }
    return try {
      withContext(Dispatchers.IO) {
        when {
          sc.shizuku.ready() -> sc.grab()
          projectionCapture.isActive -> projectionCapture.grab()
          else -> sc.grab()
        }
      }
    } finally {
      if (shouldPulse) {
        pulseHideOverlay?.invoke(false)
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
    // 64 center-of-square samples for ultra-fast O(1) frame change detection
    var hash = -3750763034362895579L
    for (r in 0 until 8) {
      val py = r * Vision.N + Vision.N / 2
      for (c in 0 until 8) {
        val px = c * Vision.N + Vision.N / 2
        val color = downscaled[py * Vision.B + px]
        // Quantize slightly (top 5 bits per channel) to ignore JPEG/compression flicker
        val quantized = ((color shr 19) and 0x1F shl 10) or
          ((color shr 11) and 0x1F shl 5) or
          ((color shr 3) and 0x1F)
        hash = (hash xor quantized.toLong()) * 1099511628211L
      }
    }
    return hash
  }
}
