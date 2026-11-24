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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates non-root screen capture (Shizuku UserService OR standard MediaProjection),
 * automatic [BoardFinder], [Vision], and [Tracker] to locate, check, and track live chess boards.
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
    val installed = sc.shizuku.isAppInstalled()
    val running = sc.shizuku.isRunning()
    val ready = sc.shizuku.ready()
    val projReady = projectionCapture.isActive
    ChessAssistantHub.setCaptureReadiness(
      shizukuInstalled = installed,
      shizukuRunning = running,
      shizukuReady = ready,
      mediaProjectionReady = projReady,
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
        "Screen capture ready (no root) — auto Board Finder active"
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
      sc.shizuku.isAppInstalled() -> "Shizuku Installed — Start Service or Enable Capture"
      else -> "Not Ready — Enable Screen Capture or Start Shizuku"
    }
  }

  data class ScanOutcome(val success: Boolean, val message: String)

  private data class LocatedBoard(
    val downscaled: IntArray,
    val autoFound: Boolean,
    val boardSizePx: Int,
  )

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
      val located =
        try {
          locateAndCropBoard(appCtx, frame, st, allowManualFallback = true, prefsRepo = prefsRepo)
        } finally {
          frame.recycle()
        }

      if (located == null) {
        val msg = "No 8×8 chessboard found on screen — open your chess game or tap Align"
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

      cachedTemplates = tpl
      cachedWhiteBottom = result.whiteBottom
      tracker.reset()
      val readCheck =
        withContext(Dispatchers.Default) {
          Vision.readPosition(located.downscaled, tpl, result.whiteBottom)
        }
      if (readCheck != null) {
        tracker.update(readCheck, result.whiteBottom)
        lastAppliedPlacement = Chess.placement(readCheck)
      } else {
        lastAppliedPlacement = Chess.START
      }
      lastPixelSignature = computeSignature(located.downscaled)

      withContext(Dispatchers.IO) {
        prefsRepo.saveVisionTemplates(tpl.serialize(), result.whiteBottom)
      }

      ChessAssistantHub.loadFen(BoardState.STARTING_FEN, "Board Checker")
      val sideLabel = if (result.whiteBottom) "White at bottom" else "Black at bottom"
      val foundPrefix = if (located.autoFound) "Board auto-found & theme learned" else "Learned piece theme"
      val okMsg = "$foundPrefix ($sideLabel)"
      ChessAssistantHub.setVisionLearned(result.whiteBottom, okMsg)
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
      val frame = captureCleanScreen(appCtx, pulseHideOverlay)
      if (frame == null) {
        val msg = captureFailureMessage(appCtx)
        if (!isAuto) ChessAssistantHub.setBoardCheckerStatus(msg)
        return@withLock ScanOutcome(false, msg)
      }

      val st = ChessAssistantHub.state.value
      val located =
        try {
          locateAndCropBoard(
            context = appCtx,
            frame = frame,
            fallbackState = st,
            allowManualFallback = !isAuto || cachedTemplates != null,
            prefsRepo = prefsRepo,
          )
        } finally {
          frame.recycle()
        }

      if (located == null) {
        val msg = "No 8×8 chessboard found on screen — open a chess board"
        if (!isAuto) ChessAssistantHub.setBoardCheckerStatus(msg)
        return@withLock ScanOutcome(false, msg)
      }

      val sig = computeSignature(located.downscaled)
      val existingTemplates = cachedTemplates

      // Anti-lag optimization: skip full Vision classification if board pixels did not change
      if (isAuto && existingTemplates != null && sig == lastPixelSignature && lastAppliedPlacement != null) {
        return@withLock ScanOutcome(true, "Board unchanged")
      }

      // If piece theme hasn't been learned yet, try automatic Vision.learn() if board is at starting position
      if (existingTemplates == null) {
        val autoLearn = withContext(Dispatchers.Default) { Vision.learn(located.downscaled) }
        val newTpl = autoLearn.templates
        if (newTpl != null && autoLearn.error == null) {
          cachedTemplates = newTpl
          cachedWhiteBottom = autoLearn.whiteBottom
          tracker.reset()
          val startPos =
            withContext(Dispatchers.Default) {
              Vision.readPosition(located.downscaled, newTpl, autoLearn.whiteBottom)
            }
          if (startPos != null) {
            tracker.update(startPos, autoLearn.whiteBottom)
            lastAppliedPlacement = Chess.placement(startPos)
          } else {
            lastAppliedPlacement = Chess.START
          }
          lastPixelSignature = sig
          if (prefsRepo != null) {
            withContext(Dispatchers.IO) {
              prefsRepo.saveVisionTemplates(newTpl.serialize(), autoLearn.whiteBottom)
            }
          }
          ChessAssistantHub.loadFen(BoardState.STARTING_FEN, "Board Checker")
          val sideLabel = if (autoLearn.whiteBottom) "White at bottom" else "Black at bottom"
          val msg = "Board found & theme auto-learned ($sideLabel)"
          ChessAssistantHub.setVisionLearned(autoLearn.whiteBottom, msg)
          return@withLock ScanOutcome(true, msg)
        } else {
          val msg =
            if (located.autoFound) {
              "Board found (${located.boardSizePx}px) — tap Learn on a starting board once"
            } else {
              "Tap Learn on a starting board first"
            }
          ChessAssistantHub.setBoardCheckerStatus(msg)
          return@withLock ScanOutcome(false, msg)
        }
      }

      var effectiveWhiteBottom = !st.flippedBoard
      var pos =
        withContext(Dispatchers.Default) {
          Vision.readPosition(located.downscaled, existingTemplates, effectiveWhiteBottom)
        }

      // If read failed, check if user flipped sides or started a new game with a fresh theme
      if (pos == null) {
        if (Vision.lastProblem.contains("pawn on rank 1 or 8")) {
          val flippedPos =
            withContext(Dispatchers.Default) {
              Vision.readPosition(located.downscaled, existingTemplates, !effectiveWhiteBottom)
            }
          if (flippedPos != null) {
            effectiveWhiteBottom = !effectiveWhiteBottom
            pos = flippedPos
          }
        }
      }

      if (pos == null) {
        val relearn = withContext(Dispatchers.Default) { Vision.learn(located.downscaled) }
        val newTpl = relearn.templates
        if (newTpl != null && relearn.error == null) {
          cachedTemplates = newTpl
          cachedWhiteBottom = relearn.whiteBottom
          tracker.reset()
          val startPos =
            withContext(Dispatchers.Default) {
              Vision.readPosition(located.downscaled, newTpl, relearn.whiteBottom)
            }
          if (startPos != null) {
            tracker.update(startPos, relearn.whiteBottom)
            lastAppliedPlacement = Chess.placement(startPos)
          } else {
            lastAppliedPlacement = Chess.START
          }
          lastPixelSignature = sig
          if (prefsRepo != null) {
            withContext(Dispatchers.IO) {
              prefsRepo.saveVisionTemplates(newTpl.serialize(), relearn.whiteBottom)
            }
          }
          ChessAssistantHub.loadFen(BoardState.STARTING_FEN, "Board Checker")
          val sideLabel = if (relearn.whiteBottom) "White at bottom" else "Black at bottom"
          val msg = "New game theme learned ($sideLabel)"
          ChessAssistantHub.setVisionLearned(relearn.whiteBottom, msg)
          return@withLock ScanOutcome(true, msg)
        }
      }

      if (pos == null) {
        val problem = Vision.lastProblem.ifBlank { "Board not recognized — check frame or Learn start" }
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
        return@withLock ScanOutcome(true, "Board synced")
      }

      lastAppliedPlacement = placement
      ChessAssistantHub.applyScannedBoard(fen, effectiveWhiteBottom)
      val turnLabel = if (tracker.blackToMove) "Black" else "White"
      val statusMsg =
        if (located.autoFound) "Board found & synced ($turnLabel to move)"
        else "Board checked ($turnLabel to move)"
      ChessAssistantHub.setBoardCheckerStatus(statusMsg)
      ScanOutcome(true, statusMsg)
    }

  private suspend fun locateAndCropBoard(
    context: Context,
    frame: Bitmap,
    fallbackState: AssistantSharedState,
    allowManualFallback: Boolean,
    prefsRepo: PreferencesRepository?,
  ): LocatedBoard? {
    val fw = frame.width
    val fh = frame.height
    if (fw < 64 || fh < 64) return null

    // 1. Always try automatic BoardFinder first
    val foundRect = withContext(Dispatchers.Default) { BoardFinder.find(frame) }
    if (foundRect != null) {
      val (dispW, dispH) = displayDimensions(context, fw, fh)
      val scaleX = dispW.toFloat() / fw.toFloat()
      val scaleY = dispH.toFloat() / fh.toFloat()
      val overlayX = (foundRect.x * scaleX).roundToInt().coerceAtLeast(0)
      val overlayY = (foundRect.y * scaleY).roundToInt().coerceAtLeast(0)
      val overlaySize = (foundRect.size * ((scaleX + scaleY) * 0.5f)).roundToInt()
        .coerceIn(120, minOf(dispW, dispH))

      val shifted =
        abs(fallbackState.overlayBoardX - overlayX) > 4 ||
          abs(fallbackState.overlayBoardY - overlayY) > 4 ||
          abs(fallbackState.overlayBoardSizePx - overlaySize) > 4

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
        }
      if (downscaled != null) {
        return LocatedBoard(downscaled, autoFound = true, boardSizePx = overlaySize)
      }
    }

    if (!allowManualFallback) return null

    val (dispW, dispH) = displayDimensions(context, fw, fh)
    val invScaleX = fw.toFloat() / dispW.toFloat()
    val invScaleY = fh.toFloat() / dispH.toFloat()
    val fx = (fallbackState.overlayBoardX * invScaleX).roundToInt()
    val fy = (fallbackState.overlayBoardY * invScaleY).roundToInt()
    val fSize = (fallbackState.overlayBoardSizePx * ((invScaleX + invScaleY) * 0.5f)).roundToInt()
    val downscaled =
      withContext(Dispatchers.Default) {
        cropAndDownscale(frame, fx, fy, fSize)
      } ?: return null

    return LocatedBoard(downscaled, autoFound = false, boardSizePx = fallbackState.overlayBoardSizePx)
  }

  private fun displayDimensions(context: Context, fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val bounds = wm.currentWindowMetrics.bounds
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

  private suspend fun captureCleanScreen(
    context: Context,
    pulseHideOverlay: (suspend (Boolean) -> Unit)?,
  ): Bitmap? {
    val sc = ensureInitialized(context)
    if (pulseHideOverlay != null && ChessAssistantHub.state.value.overlayGridVisible) {
      pulseHideOverlay.invoke(true)
      delay(65L)
      return try {
        withContext(Dispatchers.IO) {
          when {
            sc.shizuku.ready() -> sc.grab()
            projectionCapture.isActive -> projectionCapture.grab()
            else -> sc.grab()
          }
        }
      } finally {
        pulseHideOverlay.invoke(false)
        refreshReadiness(context)
      }
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
        val quantized = ((color shr 19) and 0x1F shl 10) or
          ((color shr 11) and 0x1F shl 5) or
          ((color shr 3) and 0x1F)
        hash = (hash xor quantized.toLong()) * 1099511628211L
      }
    }
    return hash
  }
}
