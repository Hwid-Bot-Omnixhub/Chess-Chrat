package com.example.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.example.data.ChessAppRegistry
import com.example.data.SystemAccessHelper
import com.example.data.WatchedChessApp

class AppWatcherMonitor(private val context: Context) {

  private var lastKnownForegroundPackage: String? = null

  fun pollForegroundChessApp(watchedPackages: Set<String>): ForegroundPollResult {
    if (!SystemAccessHelper.hasUsageStatsPermission(context)) {
      return ForegroundPollResult(hasPermission = false, activeChessApp = null)
    }

    val usageStatsManager =
      context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return ForegroundPollResult(hasPermission = false, activeChessApp = null)

    val now = System.currentTimeMillis()
    val events = usageStatsManager.queryEvents(now - 8_000L, now)
    val event = UsageEvents.Event()
    var latestPkg = lastKnownForegroundPackage

    while (events.hasNextEvent()) {
      events.getNextEvent(event)
      val isForegroundEvent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
        } else {
          @Suppress("DEPRECATION")
          event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
        }
      if (isForegroundEvent && !event.packageName.isNullOrBlank()) {
        latestPkg = event.packageName
      }
    }

    lastKnownForegroundPackage = latestPkg

    val matchedApp =
      if (latestPkg != null && latestPkg in watchedPackages) {
        ChessAppRegistry.findByPackage(latestPkg)
      } else {
        null
      }

    return ForegroundPollResult(
      hasPermission = true,
      activeChessApp = matchedApp,
      foregroundPackage = latestPkg,
    )
  }
}

data class ForegroundPollResult(
  val hasPermission: Boolean,
  val activeChessApp: WatchedChessApp?,
  val foregroundPackage: String? = null,
)
