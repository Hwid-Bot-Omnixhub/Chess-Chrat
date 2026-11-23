package com.example.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class WatchedChessApp(
  val packageName: String,
  val displayName: String,
  val publisher: String,
  val isOfficial: Boolean = true,
)

data class InstalledChessAppStatus(
  val app: WatchedChessApp,
  val isInstalled: Boolean,
  val isWatched: Boolean,
)

object ChessAppRegistry {
  val officialApps: List<WatchedChessApp> =
    listOf(
      WatchedChessApp("com.chess", "Chess.com", "Chess.com Official"),
      WatchedChessApp("org.lichess.mobileapp", "Lichess", "lichess.org Official"),
      WatchedChessApp("com.chess24.app", "Chess24", "Chess24.com"),
      WatchedChessApp("com.chess.learn", "Learn Chess with Dr. Wolf", "Chess.com"),
      WatchedChessApp("uk.co.aifactory.chessfree", "Chess Free", "AI Factory"),
      WatchedChessApp("com.google.android.apps.chess", "Google Chess", "Google"),
    )

  fun findByPackage(packageName: String): WatchedChessApp? =
    officialApps.find { it.packageName == packageName }

  fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
          packageName,
          PackageManager.PackageInfoFlags.of(0),
        )
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
      }
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }
  }

  fun getStatuses(context: Context, watchedPackages: Set<String>): List<InstalledChessAppStatus> {
    return officialApps.map { app ->
      InstalledChessAppStatus(
        app = app,
        isInstalled = isPackageInstalled(context, app.packageName),
        isWatched = app.packageName in watchedPackages,
      )
    }
  }
}
