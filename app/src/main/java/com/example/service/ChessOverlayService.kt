package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.example.MainActivity
import com.example.R
import com.example.chess.ChessEngine
import com.example.data.ChessAssistantHub
import com.example.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChessOverlayService : Service() {

  companion object {
    const val ACTION_START = "com.example.action.START_OVERLAY"
    const val ACTION_TOGGLE_ARROWS = "com.example.action.TOGGLE_ARROWS"
    const val ACTION_STOP = "com.example.action.STOP_OVERLAY"
    const val ACTION_INIT_PROJECTION = "com.example.action.INIT_PROJECTION"
    const val ACTION_LEARN_BOARD = "com.example.action.LEARN_BOARD"
    const val ACTION_SCAN_BOARD = "com.example.action.SCAN_BOARD"

    const val EXTRA_RESULT_CODE = "extra_result_code"
    const val EXTRA_DATA = "extra_data"

    private const val CHANNEL_ID = "knightsight_overlay_channel"
    private const val NOTIFICATION_ID = 4102

    fun start(context: Context) {
      val intent = Intent(context, ChessOverlayService::class.java).apply { action = ACTION_START }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun startWithProjection(context: Context, resultCode: Int, data: Intent) {
      val intent =
        Intent(context, ChessOverlayService::class.java).apply {
          action = ACTION_INIT_PROJECTION
          putExtra(EXTRA_RESULT_CODE, resultCode)
          putExtra(EXTRA_DATA, data)
        }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun triggerLearn(context: Context) {
      val intent = Intent(context, ChessOverlayService::class.java).apply { action = ACTION_LEARN_BOARD }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun triggerScan(context: Context) {
      val intent = Intent(context, ChessOverlayService::class.java).apply { action = ACTION_SCAN_BOARD }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, ChessOverlayService::class.java))
    }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var windowManager: WindowManager
  private lateinit var prefsRepo: PreferencesRepository
  private lateinit var watcherMonitor: AppWatcherMonitor

  private var boardOverlayView: OverlayBoardCanvasView? = null
  private var boardParams: WindowManager.LayoutParams? = null
  private var controlPillView: OverlayControlPillView? = null
  private var pillParams: WindowManager.LayoutParams? = null
  private var statsHudView: OverlayStatsFloatingView? = null
  private var statsHudParams: WindowManager.LayoutParams? = null

  private var watcherJob: Job? = null
  private var stateJob: Job? = null
  private var boardScannerJob: Job? = null
  private var lastNotifiedAppPkg: String? = null
  @Volatile private var hasProjectionGrant: Boolean = false

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    prefsRepo = PreferencesRepository(applicationContext)
    watcherMonitor = AppWatcherMonitor(applicationContext)
    ChessEngine.initNativeEngine(applicationContext)
    BoardVisionScanner.ensureInitialized(applicationContext)
    createNotificationChannel()

    serviceScope.launch {
      val prefs = prefsRepo.preferencesFlow.first()
      BoardVisionScanner.restoreSavedTemplates(prefs.visionTemplates, prefs.visionWhiteBottom)
      BoardVisionScanner.refreshReadiness(applicationContext)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_INIT_PROJECTION -> {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (resultCode == Activity.RESULT_OK && data != null) {
          hasProjectionGrant = true
          promoteToForeground(withProjection = true)
          initMediaProjectionCapture(resultCode, data)
        } else {
          promoteToForeground(withProjection = false)
        }
      }
      else -> {
        promoteToForeground(withProjection = hasProjectionGrant)
      }
    }

    if (intent?.action == ACTION_TOGGLE_ARROWS) {
      val current = ChessAssistantHub.state.value
      ChessAssistantHub.setOverlayServiceState(
        running = true,
        gridVisible = !current.overlayGridVisible,
      )
    }

    if (!Settings.canDrawOverlays(this)) {
      ChessAssistantHub.setOverlayServiceState(running = false)
      stopSelf()
      return START_NOT_STICKY
    }

    attachOverlayWindowsIfNeeded()
    ChessAssistantHub.setOverlayServiceState(running = true)
    observeState()
    startWatcherLoop()
    startBoardCheckerLoop()

    when (intent?.action) {
      ACTION_LEARN_BOARD -> runLearnFromService()
      ACTION_SCAN_BOARD -> runScanOnceFromService()
    }

    return START_STICKY
  }

  private fun promoteToForeground(withProjection: Boolean) {
    val notification = buildNotification(detectedAppName = null)
    val fgsType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (withProjection) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
      } else {
        0
      }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
  }

  private fun initMediaProjectionCapture(resultCode: Int, data: Intent) {
    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return
    val projection =
      runCatching { mgr.getMediaProjection(resultCode, data) }.getOrNull() ?: run {
        ChessAssistantHub.setBoardCheckerStatus("MediaProjection permission denied or expired")
        return
      }
    val (w, h, dpi) = currentScreenGeometry()
    BoardVisionScanner.startMediaProjection(applicationContext, projection, w, h, dpi)
  }

  private fun currentScreenGeometry(): Triple<Int, Int, Int> {
    val dm = resources.displayMetrics
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val bounds = windowManager.currentWindowMetrics.bounds
      return Triple(bounds.width(), bounds.height(), dm.densityDpi)
    }
    return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
  }

  private fun runLearnFromService() {
    serviceScope.launch {
      val outcome =
        BoardVisionScanner.learnStartingBoard(
          context = applicationContext,
          prefsRepo = prefsRepo,
          pulseHideOverlay = ::setOverlayHiddenForCapture,
        )
      withContext(Dispatchers.Main) {
        Toast.makeText(applicationContext, outcome.message, Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun runScanOnceFromService(isAuto: Boolean = false) {
    serviceScope.launch {
      val outcome =
        BoardVisionScanner.scanBoardOnce(
          context = applicationContext,
          prefsRepo = prefsRepo,
          isAuto = isAuto,
          pulseHideOverlay = ::setOverlayHiddenForCapture,
        )
      if (!isAuto && !outcome.success) {
        withContext(Dispatchers.Main) {
          Toast.makeText(applicationContext, outcome.message, Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  private suspend fun setOverlayHiddenForCapture(hidden: Boolean) {
    withContext(Dispatchers.Main.immediate) {
      boardOverlayView?.visibility =
        if (hidden || !ChessAssistantHub.state.value.overlayGridVisible) View.INVISIBLE
        else View.VISIBLE
    }
  }

  private fun attachOverlayWindowsIfNeeded() {
    val overlayType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
      }

    val state = ChessAssistantHub.state.value

    if (boardOverlayView == null) {
      val baseBoardFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
      val initialBoardFlags =
        if (state.overlayPassThrough) {
          baseBoardFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
          baseBoardFlags
        }

      val bParams =
        WindowManager.LayoutParams(
          state.overlayBoardSizePx,
          state.overlayBoardSizePx,
          overlayType,
          initialBoardFlags,
          PixelFormat.TRANSLUCENT,
        ).apply {
          gravity = Gravity.TOP or Gravity.START
          x = state.overlayBoardX
          y = state.overlayBoardY
        }

      val bView =
        OverlayBoardCanvasView(
          context = this,
          onDragBy = { dx, dy ->
            val lp = boardParams ?: return@OverlayBoardCanvasView
            lp.x += dx
            lp.y += dy
            runCatching { windowManager.updateViewLayout(boardOverlayView, lp) }
            ChessAssistantHub.updateOverlayBounds(lp.x, lp.y, lp.width)
          },
          onResizeBy = { delta ->
            val lp = boardParams ?: return@OverlayBoardCanvasView
            val nextSize = (lp.width + delta).coerceIn(360, 1280)
            lp.width = nextSize
            lp.height = nextSize
            runCatching { windowManager.updateViewLayout(boardOverlayView, lp) }
            ChessAssistantHub.updateOverlayBounds(lp.x, lp.y, nextSize)
          },
          onInteractionFinished = {
            val lp = boardParams ?: return@OverlayBoardCanvasView
            serviceScope.launch(Dispatchers.IO) {
              prefsRepo.saveOverlayBounds(lp.x, lp.y, lp.width)
            }
          },
          onSquaresMoveSynced = { from, to ->
            ChessAssistantHub.playSquares(from, to)
          },
        )
      boardParams = bParams
      boardOverlayView = bView
      windowManager.addView(bView, bParams)
    }

    if (controlPillView == null) {
      val pParams =
        WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          overlayType,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
          PixelFormat.TRANSLUCENT,
        ).apply {
          gravity = Gravity.TOP or Gravity.START
          x = 24
          y = 110
        }

      val pView =
        OverlayControlPillView(
          context = this,
          onDragBy = { dx, dy ->
            val lp = pillParams ?: return@OverlayControlPillView
            lp.x += dx
            lp.y += dy
            runCatching { windowManager.updateViewLayout(controlPillView, lp) }
          },
          onApplyBestMove = {
            val top = ChessAssistantHub.state.value.analysis.topMoves.firstOrNull()
            if (top != null) {
              ChessAssistantHub.playMove(top.move)
            }
          },
          onLearnBoard = { runLearnFromService() },
          onScanBoard = { runScanOnceFromService() },
          onToggleAutoScan = {
            val cur = ChessAssistantHub.state.value
            val next = !cur.autoBoardScanEnabled
            ChessAssistantHub.setAutoBoardScan(next)
            serviceScope.launch(Dispatchers.IO) { prefsRepo.updateAutoBoardScan(next) }
            if (next && BoardVisionScanner.isCaptureAvailable(applicationContext)) {
              runScanOnceFromService(isAuto = true)
            }
          },
          onToggleGrid = {
            val cur = ChessAssistantHub.state.value
            ChessAssistantHub.setOverlayServiceState(
              running = true,
              gridVisible = !cur.overlayGridVisible,
            )
          },
          onToggleLock = {
            val cur = ChessAssistantHub.state.value
            val nextPassThrough = !cur.overlayPassThrough
            ChessAssistantHub.setOverlayServiceState(
              running = true,
              passThrough = nextPassThrough,
              tapSync = if (nextPassThrough) false else cur.overlayTapSyncActive,
            )
          },
          onToggleTapSync = {
            val cur = ChessAssistantHub.state.value
            val nextSync = !cur.overlayTapSyncActive
            ChessAssistantHub.setOverlayServiceState(
              running = true,
              gridVisible = true,
              // When turning tapSync off, immediately restore touch-through so the real game works!
              passThrough = !nextSync,
              tapSync = nextSync,
            )
          },
          onToggleStats = {
            val nextShow = !ChessAssistantHub.state.value.showFloatingStatsHud
            ChessAssistantHub.setShowFloatingStatsHud(nextShow)
            serviceScope.launch(Dispatchers.IO) { prefsRepo.updateShowFloatingStatsHud(nextShow) }
          },
          onFlipBoard = { ChessAssistantHub.toggleFlipped() },
          onUndo = { ChessAssistantHub.undoMove() },
          onClose = { stopSelf() },
        )
      pillParams = pParams
      controlPillView = pView
      windowManager.addView(pView, pParams)
    }

    if (statsHudView == null) {
      val sParams =
        WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          overlayType,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
          PixelFormat.TRANSLUCENT,
        ).apply {
          gravity = Gravity.TOP or Gravity.START
          x = state.statsHudX
          y = state.statsHudY
        }

      val sView =
        OverlayStatsFloatingView(
          context = this,
          onDragBy = { dx, dy ->
            val lp = statsHudParams ?: return@OverlayStatsFloatingView
            lp.x += dx
            lp.y += dy
            runCatching { windowManager.updateViewLayout(statsHudView, lp) }
            ChessAssistantHub.updateStatsHudPosition(lp.x, lp.y)
          },
          onDragFinished = {
            val lp = statsHudParams ?: return@OverlayStatsFloatingView
            serviceScope.launch(Dispatchers.IO) {
              prefsRepo.saveStatsHudPosition(lp.x, lp.y)
            }
          },
          onCycleTrackedSide = {
            val cur = ChessAssistantHub.state.value.trackedPlayerSide
            val nextSide =
              when (cur) {
                com.example.chess.PieceColor.WHITE -> com.example.chess.PieceColor.BLACK
                com.example.chess.PieceColor.BLACK -> null
                null -> com.example.chess.PieceColor.WHITE
              }
            ChessAssistantHub.setTrackedPlayerSide(nextSide)
            serviceScope.launch(Dispatchers.IO) { prefsRepo.updateTrackedPlayerSide(nextSide) }
          },
          onHideStats = {
            ChessAssistantHub.setShowFloatingStatsHud(false)
            serviceScope.launch(Dispatchers.IO) { prefsRepo.updateShowFloatingStatsHud(false) }
          },
        )
      statsHudParams = sParams
      statsHudView = sView
      windowManager.addView(sView, sParams)
    }
  }

  private fun observeState() {
    if (stateJob != null) return
    stateJob =
      serviceScope.launch {
        ChessAssistantHub.state.collectLatest { st ->
          boardOverlayView?.let { bView ->
            bView.visibility =
              if (st.overlayGridVisible) View.VISIBLE else View.GONE
            bView.updateState(st)
            boardParams?.let { lp ->
              val baseFlags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                  WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
              val targetFlags =
                if (st.overlayPassThrough) {
                  baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                  baseFlags
                }
              if (lp.flags != targetFlags ||
                lp.x != st.overlayBoardX ||
                lp.y != st.overlayBoardY ||
                lp.width != st.overlayBoardSizePx
              ) {
                lp.flags = targetFlags
                lp.x = st.overlayBoardX
                lp.y = st.overlayBoardY
                lp.width = st.overlayBoardSizePx
                lp.height = st.overlayBoardSizePx
                runCatching { windowManager.updateViewLayout(bView, lp) }
              }
            }
          }
          controlPillView?.updateState(st)
          statsHudView?.let { sView ->
            sView.visibility =
              if (st.showFloatingStatsHud) View.VISIBLE else View.GONE
            sView.updateState(st)
          }
        }
      }
  }

  private fun startWatcherLoop() {
    if (watcherJob != null) return
    watcherJob =
      serviceScope.launch(Dispatchers.Default) {
        while (isActive) {
          BoardVisionScanner.refreshReadiness(applicationContext)
          val st = ChessAssistantHub.state.value
          if (st.watcherEnabled) {
            val poll = watcherMonitor.pollForegroundChessApp(st.watchedPackages)
            val detected = poll.activeChessApp
            if (detected?.packageName != lastNotifiedAppPkg) {
              lastNotifiedAppPkg = detected?.packageName
              ChessAssistantHub.setDetectedForegroundApp(detected)
              if (detected != null) {
                // Keep pass-through active so user can immediately play the chess app
                ChessAssistantHub.setOverlayServiceState(
                  running = true,
                  gridVisible = true,
                  passThrough = true,
                  tapSync = false,
                )
                if (BoardVisionScanner.isCaptureAvailable(applicationContext)) {
                  delay(450L)
                  runScanOnceFromService(isAuto = true)
                }
              }
              updateNotification(detected?.displayName)
            }
          } else if (lastNotifiedAppPkg != null) {
            lastNotifiedAppPkg = null
            ChessAssistantHub.setDetectedForegroundApp(null)
            updateNotification(null)
          }
          delay(if (st.antiLagEnabled) 2000L else 1200L)
        }
      }
  }

  private fun startBoardCheckerLoop() {
    if (boardScannerJob != null) return
    boardScannerJob =
      serviceScope.launch(Dispatchers.Default) {
        while (isActive) {
          val st = ChessAssistantHub.state.value
          if (st.autoBoardScanEnabled &&
            st.overlayGridVisible &&
            st.overlayPassThrough &&
            BoardVisionScanner.isCaptureAvailable(applicationContext)
          ) {
            BoardVisionScanner.scanBoardOnce(
              context = applicationContext,
              prefsRepo = prefsRepo,
              isAuto = true,
              pulseHideOverlay = ::setOverlayHiddenForCapture,
            )
          }
          delay(if (st.antiLagEnabled) 1500L else 950L)
        }
      }
  }

  private fun updateNotification(detectedAppName: String?) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, buildNotification(detectedAppName))
  }

  private fun buildNotification(detectedAppName: String?): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val toggleIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, ChessOverlayService::class.java).apply { action = ACTION_TOGGLE_ARROWS },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val stopIntent =
      PendingIntent.getService(
        this,
        2,
        Intent(this, ChessOverlayService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val contentText =
      if (detectedAppName != null) {
        getString(R.string.overlay_notification_detected, detectedAppName)
      } else {
        getString(R.string.overlay_notification_standby)
      }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(getString(R.string.overlay_notification_title))
      .setContentText(contentText)
      .setContentIntent(openIntent)
      .setOngoing(true)
      .addAction(0, getString(R.string.action_toggle_arrows), toggleIntent)
      .addAction(0, getString(R.string.action_stop_watcher), stopIntent)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.overlay_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = getString(R.string.overlay_channel_desc)
        }
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(channel)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    watcherJob?.cancel()
    boardScannerJob?.cancel()
    stateJob?.cancel()
    hasProjectionGrant = false
    BoardVisionScanner.stopMediaProjection(applicationContext)
    boardOverlayView?.let { runCatching { windowManager.removeView(it) } }
    controlPillView?.let { runCatching { windowManager.removeView(it) } }
    statsHudView?.let { runCatching { windowManager.removeView(it) } }
    boardOverlayView = null
    controlPillView = null
    statsHudView = null
    ChessAssistantHub.setOverlayServiceState(running = false)
    ChessAssistantHub.setDetectedForegroundApp(null)
    serviceScope.cancel()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
