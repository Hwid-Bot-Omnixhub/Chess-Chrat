package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.InstalledChessAppStatus
import com.example.data.SystemAccessHelper
import com.example.ui.KnightSightUiState
import com.example.ui.MainViewModel
import com.example.ui.theme.AmberGold
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.TacticalEmerald
import com.example.ui.theme.ThreatCrimson

@Composable
fun WatcherScreen(
  uiState: KnightSightUiState,
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val shared = uiState.shared
  val perms = uiState.permissions

  val notificationLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      viewModel.refreshSystemStatus()
    }

  val settingsLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      viewModel.refreshSystemStatus()
    }

  val projectionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
      viewModel.onMediaProjectionResult(res.resultCode, res.data)
    }

  LaunchedEffect(shared.shizukuReady) {
    while (!shared.shizukuReady) {
      kotlinx.coroutines.delay(1500L)
      viewModel.refreshSystemStatus()
    }
  }

  Column(
    modifier =
      modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Hero Tactical Banner
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(168.dp)
          .clip(RoundedCornerShape(18.dp)),
    ) {
      Image(
        painter = painterResource(id = R.drawable.img_tactical_banner),
        contentDescription = "KnightSight tactical overlay preview banner",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      Box(
        modifier =
          Modifier.fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(Color(0x440D1117), Color(0xE60D1117)),
              ),
            ),
      )
      Column(
        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        val badgeText =
          when {
            shared.detectedForegroundApp != null ->
              "DETECTED: ${shared.detectedForegroundApp.displayName.uppercase()}"
            shared.overlayServiceRunning && shared.watcherEnabled -> "WATCHER ACTIVE • MONITORING"
            shared.overlayServiceRunning -> "OVERLAY ACTIVE"
            else -> "STANDBY • READY"
          }
        val badgeColor =
          when {
            shared.detectedForegroundApp != null -> TacticalEmerald
            shared.overlayServiceRunning -> ElectricCyan
            else -> AmberGold
          }

        Surface(
          color = badgeColor.copy(alpha = 0.22f),
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, badgeColor),
        ) {
          Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
        Text(
          text = "Live Chess App Watcher & Board Checker",
          style = MaterialTheme.typography.titleLarge,
          color = Color.White,
        )
        Text(
          text = "Touch-through arrows + non-root Shizuku & MediaProjection board scanner.",
          style = MaterialTheme.typography.bodyMedium,
          color = Color(0xFFD0D7DE),
        )
      }
    }

    // Primary Overlay Control Card
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Floating Arrow Overlay", style = MaterialTheme.typography.titleMedium)
            Text(
              text =
                if (shared.overlayServiceRunning) {
                  "Touch-through is ${if (shared.overlayPassThrough) "ON (real game is touchable)" else "OFF (Align mode active)"}. Tap Align / Done in the floating toolbar anytime."
                } else {
                  "Starts with touch-through ON so you can play your chess app uninterrupted."
                },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        ToggleSettingRow(
          title = "Touch-Through Mode (Real Game Stays Touchable)",
          checked = shared.overlayPassThrough,
          onCheckedChange = viewModel::setOverlayPassThrough,
          tag = "overlay_passthrough_switch",
        )

        Button(
          onClick = { viewModel.toggleOverlayService() },
          colors =
            ButtonDefaults.buttonColors(
              containerColor =
                if (shared.overlayServiceRunning) ThreatCrimson else TacticalEmerald,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth().height(50.dp).testTag("launch_overlay_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Layers,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text =
              if (shared.overlayServiceRunning) "Stop Floating Overlay & Watcher"
              else "Start Floating Arrow Overlay",
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    }

    // Non-Root Board Checker & Capture Card (Shizuku + MediaProjection + Vision)
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "BOARD CHECKER (NO ROOT)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Surface(
            color =
              if (shared.visionTemplatesReady) TacticalEmerald.copy(alpha = 0.16f)
              else AmberGold.copy(alpha = 0.16f),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = if (shared.visionTemplatesReady) "Theme Learned" else "Needs Learn",
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
              color = if (shared.visionTemplatesReady) TacticalEmerald else AmberGold,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        }

        Text(
          text = shared.boardCheckerStatus,
          style = MaterialTheme.typography.bodyMedium,
          color = ElectricCyan,
        )

        // Shizuku Non-Root Row
        PermissionRow(
          title = "Shizuku Capture (Non-Root ADB / Wireless)",
          subtitle =
            when {
              shared.shizukuReady -> "Connected (uid 2000 shell) — works on protected screens"
              shared.shizukuRunning -> "Shizuku is running — tap Grant to allow capture"
              shared.shizukuInstalled -> "Shizuku installed — start Shizuku or tap Open"
              else -> "Optional: reads FLAG_SECURE boards without root via Shizuku"
            },
          granted = shared.shizukuReady,
          actionLabel =
            when {
              shared.shizukuRunning -> "Grant"
              shared.shizukuInstalled -> "Open"
              else -> "Setup"
            },
          icon = Icons.Default.Security,
          buttonTag = "grant_shizuku_button",
          onGrant = { viewModel.requestShizukuPermission() },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        // Standard MediaProjection Non-Root Row
        PermissionRow(
          title = "Standard Screen Capture (No Root / No Shizuku)",
          subtitle =
            if (shared.mediaProjectionReady) "MediaProjection active for visual board scanning"
            else "Built-in Android screen capture for unrooted devices",
          granted = shared.mediaProjectionReady,
          actionLabel = "Enable",
          icon = Icons.Default.Screenshot,
          buttonTag = "enable_projection_button",
          onGrant = {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
          },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        ToggleSettingRow(
          title = "Auto Board Finder & Live Sync (Zero Planted Moves)",
          checked = shared.autoBoardScanEnabled,
          onCheckedChange = viewModel::setAutoBoardScan,
          tag = "auto_scan_switch",
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = { viewModel.learnBoardFromScreen() },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f).height(48.dp).testTag("learn_board_button"),
          ) {
            Icon(
              imageVector = Icons.Default.CenterFocusStrong,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Learn Start")
          }

          Button(
            onClick = { viewModel.scanBoardOnce() },
            colors =
              ButtonDefaults.buttonColors(
                containerColor = ElectricCyan,
                contentColor = Color(0xFF0D1117),
              ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f).height(48.dp).testTag("scan_board_button"),
          ) {
            Text("Find & Sync")
          }
        }
      }
    }

    // System Permissions Readiness Checklist
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("REQUIRED ANDROID ACCESS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        PermissionRow(
          title = "Draw Over Other Apps",
          subtitle = "Displays floating tactical arrows above chess boards",
          granted = perms.canDrawOverlays,
          icon = Icons.Default.Visibility,
          buttonTag = "grant_overlay_permission_button",
          onGrant = {
            settingsLauncher.launch(SystemAccessHelper.buildOverlaySettingsIntent(context))
          },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        PermissionRow(
          title = "App Launch Detection (Usage Access)",
          subtitle = "Watches when Chess.com or Lichess opens in foreground",
          granted = perms.hasUsageStatsAccess,
          icon = Icons.Default.Radar,
          buttonTag = "grant_usage_permission_button",
          onGrant = { settingsLauncher.launch(SystemAccessHelper.buildUsageAccessSettingsIntent()) },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
          PermissionRow(
            title = "Watcher Service Notification",
            subtitle = "Keeps the foreground watcher reliable on Android 13+",
            granted = perms.hasNotificationPermission,
            icon = Icons.Default.NotificationsActive,
            buttonTag = "grant_notification_permission_button",
            onGrant = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
          )
        }
      }
    }

    // Official Chess Apps Watchlist
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("OFFICIAL CHESS APPS WATCHLIST", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
              text = "Auto-reveal arrows when enabled apps open",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = shared.watcherEnabled,
            onCheckedChange = viewModel::setWatcherEnabled,
            modifier = Modifier.testTag("watcher_master_switch"),
          )
        }

        uiState.installedApps.forEach { status ->
          ChessAppWatchRow(
            status = status,
            onToggleWatch = { viewModel.toggleWatchedApp(status.app.packageName) },
            onLaunch = {
              viewModel.launchChessApp(status.app.packageName, status.app.displayName)
            },
          )
        }
      }
    }

    // Arrow & Engine Tuning
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("ARROW & ENGINE SETTINGS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ToggleSettingRow(
          title = "Anti-Lag Mode (Zero-GC Overlay & Budget Cap)",
          checked = shared.antiLagEnabled,
          onCheckedChange = viewModel::setAntiLagEnabled,
          tag = "watcher_antilag_switch",
        )

        ToggleSettingRow(
          title = "Floating Move Grades GUI (!! Brilliant / ! Excellent / ✓ Good)",
          checked = shared.showFloatingStatsHud,
          onCheckedChange = viewModel::setShowFloatingStatsHud,
          tag = "watcher_floating_stats_switch",
        )

        ToggleSettingRow(
          title =
            if (uiState.isNativeStockfishAvailable) "Use Stockfish (Native Binary + Online API)"
            else "Use Stockfish Online API (Up to Depth 15)",
          checked = shared.useStockfishApi,
          onCheckedChange = viewModel::setUseStockfishApi,
          tag = "watcher_stockfish_switch",
        )

        Column {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Target Elo (${com.example.chess.EloDepthMapper.tierLabel(shared.targetElo)})",
              style = MaterialTheme.typography.bodyLarge,
            )
            Text(
              text = "${shared.targetElo} • D${shared.engineDepth}",
              style = MaterialTheme.typography.labelMedium,
              color = TacticalEmerald,
            )
          }
          Slider(
            value = shared.targetElo.toFloat(),
            onValueChange = { raw ->
              val snapped = ((raw / 100f).let { kotlin.math.round(it).toInt() } * 100).coerceIn(800, 3200)
              viewModel.setTargetElo(snapped)
            },
            valueRange = 800f..3200f,
            steps = 23,
            colors =
              SliderDefaults.colors(
                thumbColor = TacticalEmerald,
                activeTrackColor = TacticalEmerald,
              ),
            modifier = Modifier.testTag("watcher_elo_slider"),
          )
        }

        ToggleSettingRow(
          title = "Show #2 Alternative Arrow (Cyan)",
          checked = shared.showSecondaryArrows,
          onCheckedChange = viewModel::setShowSecondaryArrows,
          tag = "toggle_secondary_arrow",
        )
        ToggleSettingRow(
          title = "Show Opponent Threat Arrow (Red Dashed)",
          checked = shared.showThreatArrow,
          onCheckedChange = viewModel::setShowThreatArrow,
          tag = "toggle_threat_arrow",
        )

        Column {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("Arrow Opacity", style = MaterialTheme.typography.bodyLarge)
            Text(
              text = "${(shared.arrowOpacity * 100).toInt()}%",
              style = MaterialTheme.typography.labelMedium,
              color = TacticalEmerald,
            )
          }
          Slider(
            value = shared.arrowOpacity,
            onValueChange = viewModel::setArrowOpacity,
            valueRange = 0.35f..1.0f,
            colors =
              SliderDefaults.colors(
                thumbColor = TacticalEmerald,
                activeTrackColor = TacticalEmerald,
              ),
          )
        }
      }
    }

    // Fair-Play Study Note
    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(14.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Icon(
          imageVector = Icons.Default.Security,
          contentDescription = "Fair play study mode notice",
          tint = AmberGold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Study & Bot-Practice Compliance", style = MaterialTheme.typography.titleMedium)
          Text(
            text =
              "Use live move arrows responsibly for unrated practice, bot matches, and post-game review in accordance with platform fair-play rules.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
  }
}

@Composable
private fun PermissionRow(
  title: String,
  subtitle: String,
  granted: Boolean,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  buttonTag: String,
  actionLabel: String = "Grant",
  onGrant: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = if (granted) TacticalEmerald else AmberGold,
      modifier = Modifier.size(24.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.titleMedium)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (granted) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = Icons.Default.CheckCircle,
          contentDescription = "Granted",
          tint = TacticalEmerald,
          modifier = Modifier.size(18.dp),
        )
        Text("Ready", style = MaterialTheme.typography.labelLarge, color = TacticalEmerald)
      }
    } else {
      OutlinedButton(
        onClick = onGrant,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.testTag(buttonTag),
      ) {
        Icon(
          imageVector = Icons.Default.Warning,
          contentDescription = null,
          tint = AmberGold,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(actionLabel)
      }
    }
  }
}

@Composable
private fun ChessAppWatchRow(
  status: InstalledChessAppStatus,
  onToggleWatch: () -> Unit,
  onLaunch: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(text = status.app.displayName, style = MaterialTheme.typography.titleMedium)
          Surface(
            color =
              if (status.isInstalled) TacticalEmerald.copy(alpha = 0.16f)
              else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(6.dp),
          ) {
            Text(
              text = if (status.isInstalled) "Installed" else "Not installed",
              style = MaterialTheme.typography.labelSmall,
              color =
                if (status.isInstalled) TacticalEmerald
                else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
        Text(
          text = status.app.packageName,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (status.isInstalled) {
        OutlinedButton(
          onClick = onLaunch,
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "Open ${status.app.displayName}",
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("Open")
        }
      }

      Switch(
        checked = status.isWatched,
        onCheckedChange = { onToggleWatch() },
        modifier = Modifier.testTag("watch_switch_${status.app.packageName}"),
      )
    }
  }
}

@Composable
private fun ToggleSettingRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  tag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.testTag(tag),
    )
  }
}
