package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.chess.BoardState
import com.example.chess.EloDepthMapper
import com.example.chess.PieceColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "knightsight_prefs")

data class UserPreferences(
  val targetElo: Int = 1800,
  val useStockfishApi: Boolean = true,
  val antiLagEnabled: Boolean = true,
  val showFloatingStatsHud: Boolean = true,
  val trackedPlayerSide: PieceColor? = PieceColor.WHITE,
  val showSecondaryArrows: Boolean = true,
  val showThreatArrow: Boolean = true,
  val arrowOpacity: Float = 0.90f,
  val watcherEnabled: Boolean = true,
  val flippedBoard: Boolean = false,
  val watchedPackages: Set<String> =
    setOf("com.chess", "org.lichess.mobileapp", "com.chess24.app", "uk.co.aifactory.chessfree"),
  val lastFen: String = BoardState.STARTING_FEN,
  val overlayBoardX: Int = 40,
  val overlayBoardY: Int = 260,
  val overlayBoardSizePx: Int = 780,
  val statsHudX: Int = 36,
  val statsHudY: Int = 1080,
  val visionTemplates: String? = null,
  val visionWhiteBottom: Boolean = true,
  val autoBoardScan: Boolean = true,
)

class PreferencesRepository(private val context: Context) {

  private object Keys {
    val TARGET_ELO = intPreferencesKey("target_elo")
    val USE_STOCKFISH_API = booleanPreferencesKey("use_stockfish_api")
    val ANTI_LAG_ENABLED = booleanPreferencesKey("anti_lag_enabled")
    val SHOW_FLOATING_STATS = booleanPreferencesKey("show_floating_stats")
    val TRACKED_PLAYER_SIDE = stringPreferencesKey("tracked_player_side")
    val SHOW_SECONDARY_ARROWS = booleanPreferencesKey("show_secondary_arrows")
    val SHOW_THREAT_ARROW = booleanPreferencesKey("show_threat_arrow")
    val ARROW_OPACITY = floatPreferencesKey("arrow_opacity")
    val WATCHER_ENABLED = booleanPreferencesKey("watcher_enabled")
    val FLIPPED_BOARD = booleanPreferencesKey("flipped_board")
    val WATCHED_PACKAGES = stringSetPreferencesKey("watched_packages")
    val LAST_FEN = stringPreferencesKey("last_fen")
    val OVERLAY_BOARD_X = intPreferencesKey("overlay_board_x")
    val OVERLAY_BOARD_Y = intPreferencesKey("overlay_board_y")
    val OVERLAY_BOARD_SIZE = intPreferencesKey("overlay_board_size")
    val STATS_HUD_X = intPreferencesKey("stats_hud_x")
    val STATS_HUD_Y = intPreferencesKey("stats_hud_y")
    val VISION_TEMPLATES = stringPreferencesKey("vision_templates")
    val VISION_WHITE_BOTTOM = booleanPreferencesKey("vision_white_bottom")
    val AUTO_BOARD_SCAN = booleanPreferencesKey("auto_board_scan")
  }

  val preferencesFlow: Flow<UserPreferences> =
    context.dataStore.data.map { prefs ->
      val trackedStr = prefs[Keys.TRACKED_PLAYER_SIDE] ?: "WHITE"
      val trackedSide =
        when (trackedStr) {
          "WHITE" -> PieceColor.WHITE
          "BLACK" -> PieceColor.BLACK
          else -> null
        }
      UserPreferences(
        targetElo = EloDepthMapper.clampElo(prefs[Keys.TARGET_ELO] ?: 1800),
        useStockfishApi = prefs[Keys.USE_STOCKFISH_API] ?: true,
        antiLagEnabled = prefs[Keys.ANTI_LAG_ENABLED] ?: true,
        showFloatingStatsHud = prefs[Keys.SHOW_FLOATING_STATS] ?: true,
        trackedPlayerSide = trackedSide,
        showSecondaryArrows = prefs[Keys.SHOW_SECONDARY_ARROWS] ?: true,
        showThreatArrow = prefs[Keys.SHOW_THREAT_ARROW] ?: true,
        arrowOpacity = prefs[Keys.ARROW_OPACITY] ?: 0.90f,
        watcherEnabled = prefs[Keys.WATCHER_ENABLED] ?: true,
        flippedBoard = prefs[Keys.FLIPPED_BOARD] ?: false,
        watchedPackages =
          prefs[Keys.WATCHED_PACKAGES]
            ?: setOf(
              "com.chess",
              "org.lichess.mobileapp",
              "com.chess24.app",
              "uk.co.aifactory.chessfree",
            ),
        lastFen = prefs[Keys.LAST_FEN] ?: BoardState.STARTING_FEN,
        overlayBoardX = prefs[Keys.OVERLAY_BOARD_X] ?: 40,
        overlayBoardY = prefs[Keys.OVERLAY_BOARD_Y] ?: 260,
        overlayBoardSizePx = prefs[Keys.OVERLAY_BOARD_SIZE] ?: 780,
        statsHudX = prefs[Keys.STATS_HUD_X] ?: 36,
        statsHudY = prefs[Keys.STATS_HUD_Y] ?: 1080,
        visionTemplates = prefs[Keys.VISION_TEMPLATES],
        visionWhiteBottom = prefs[Keys.VISION_WHITE_BOTTOM] ?: true,
        autoBoardScan = prefs[Keys.AUTO_BOARD_SCAN] ?: true,
      )
    }

  suspend fun updateTargetElo(elo: Int) {
    context.dataStore.edit { it[Keys.TARGET_ELO] = EloDepthMapper.clampElo(elo) }
  }

  suspend fun updateUseStockfishApi(enabled: Boolean) {
    context.dataStore.edit { it[Keys.USE_STOCKFISH_API] = enabled }
  }

  suspend fun updateAntiLagEnabled(enabled: Boolean) {
    context.dataStore.edit { it[Keys.ANTI_LAG_ENABLED] = enabled }
  }

  suspend fun updateShowFloatingStatsHud(show: Boolean) {
    context.dataStore.edit { it[Keys.SHOW_FLOATING_STATS] = show }
  }

  suspend fun updateTrackedPlayerSide(side: PieceColor?) {
    context.dataStore.edit {
      it[Keys.TRACKED_PLAYER_SIDE] = side?.name ?: "BOTH"
    }
  }

  suspend fun updateShowSecondaryArrows(show: Boolean) {
    context.dataStore.edit { it[Keys.SHOW_SECONDARY_ARROWS] = show }
  }

  suspend fun updateShowThreatArrow(show: Boolean) {
    context.dataStore.edit { it[Keys.SHOW_THREAT_ARROW] = show }
  }

  suspend fun updateArrowOpacity(opacity: Float) {
    context.dataStore.edit { it[Keys.ARROW_OPACITY] = opacity.coerceIn(0.35f, 1.0f) }
  }

  suspend fun updateWatcherEnabled(enabled: Boolean) {
    context.dataStore.edit { it[Keys.WATCHER_ENABLED] = enabled }
  }

  suspend fun updateFlippedBoard(flipped: Boolean) {
    context.dataStore.edit { it[Keys.FLIPPED_BOARD] = flipped }
  }

  suspend fun updateWatchedPackages(packages: Set<String>) {
    context.dataStore.edit { it[Keys.WATCHED_PACKAGES] = packages }
  }

  suspend fun saveLastFen(fen: String) {
    context.dataStore.edit { it[Keys.LAST_FEN] = fen }
  }

  suspend fun saveOverlayBounds(x: Int, y: Int, sizePx: Int) {
    context.dataStore.edit {
      it[Keys.OVERLAY_BOARD_X] = x
      it[Keys.OVERLAY_BOARD_Y] = y
      it[Keys.OVERLAY_BOARD_SIZE] = sizePx
    }
  }

  suspend fun saveStatsHudPosition(x: Int, y: Int) {
    context.dataStore.edit {
      it[Keys.STATS_HUD_X] = x
      it[Keys.STATS_HUD_Y] = y
    }
  }

  suspend fun saveVisionTemplates(serialized: String, whiteBottom: Boolean) {
    context.dataStore.edit {
      it[Keys.VISION_TEMPLATES] = serialized
      it[Keys.VISION_WHITE_BOTTOM] = whiteBottom
    }
  }

  suspend fun updateAutoBoardScan(enabled: Boolean) {
    context.dataStore.edit { it[Keys.AUTO_BOARD_SCAN] = enabled }
  }
}
