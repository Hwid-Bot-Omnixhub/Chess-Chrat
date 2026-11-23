package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.ChessAssistantHub
import com.example.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChessOverlayService : Service() {

  companion object {
    const val ACTION_START = "com.example.action.START_OVERLAY"
    const val ACTION_TOGGLE_ARROWS = "com.example.action.TOGGLE_ARROWS"
    const val ACTION_STOP = "com.example.action.STOP_OVERLAY"
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
  private var lastNotifiedAppPkg: String? = null

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    prefsRepo = PreferencesRepository(applicationContext)
    watcherMonitor = AppWatcherMonitor(applicationContext)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_TOGGLE_ARROWS -> {
        val current = ChessAssistantHub.state.value
        ChessAssistantHub.setOverlayServiceState(
          running = true,
          gridVisible = !current.overlayGridVisible,
        )
      }
    }

    promoteToForeground()

    if (!Settings.canDrawOverlays(this)) {
      ChessAssistantHub.setOverlayServiceState(running = false)
      stopSelf()
      return START_NOT_STICKY
    }

    attachOverlayWindowsIfNeeded()
    ChessAssistantHub.setOverlayServiceState(running = true)
    observeState()
    startWatcherLoop()

    return START_STICKY
  }

  private fun promoteToForeground() {
    val notification = buildNotification(detectedAppName = null)
    val fgsType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      } else {
        0
      }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
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
      val bParams =
        WindowManager.LayoutParams(
          state.overlayBoardSizePx,
          state.overlayBoardSizePx,
          overlayType,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
              passThrough = if (nextSync) false else cur.overlayPassThrough,
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
              if (st.overlayGridVisible) android.view.View.VISIBLE else android.view.View.GONE
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
              if (lp.flags != targetFlags) {
                lp.flags = targetFlags
                runCatching { windowManager.updateViewLayout(bView, lp) }
              }
            }
          }
          controlPillView?.updateState(st)
          statsHudView?.let { sView ->
            sView.visibility =
              if (st.showFloatingStatsHud) android.view.View.VISIBLE else android.view.View.GONE
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
          val st = ChessAssistantHub.state.value
          if (st.watcherEnabled) {
            val poll = watcherMonitor.pollForegroundChessApp(st.watchedPackages)
            val detected = poll.activeChessApp
            if (detected?.packageName != lastNotifiedAppPkg) {
              lastNotifiedAppPkg = detected?.packageName
              ChessAssistantHub.setDetectedForegroundApp(detected)
              if (detected != null) {
                // Auto-reveal tactical overlay when an official chess app comes to foreground
                ChessAssistantHub.setOverlayServiceState(running = true, gridVisible = true)
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
    stateJob?.cancel()
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
