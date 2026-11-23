package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.chess.EloDepthMapper
import com.example.chess.GameStatus
import com.example.chess.MoveQuality
import com.example.chess.PieceColor
import com.example.chess.PieceType
import com.example.chess.SuggestedMove
import com.example.data.BoardInteractionMode
import com.example.ui.KnightSightUiState
import com.example.ui.MainViewModel
import com.example.ui.components.ChessBoardView
import com.example.ui.components.EvalBar
import com.example.ui.theme.AmberGold
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.TacticalEmerald
import com.example.ui.theme.ThreatCrimson
import kotlin.math.roundToInt

@Composable
fun BoardScreen(
  uiState: KnightSightUiState,
  viewModel: MainViewModel,
  onNavigateToWatcher: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val shared = uiState.shared
  val analysis = shared.analysis
  var showImportDialog by remember { mutableStateOf(false) }

  BoxWithConstraints(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
    val isWide = maxWidth >= 720.dp

    if (isWide) {
      Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Column(
          modifier = Modifier.weight(1.1f).fillMaxHeight(),
          verticalArrangement = Arrangement.Center,
        ) {
          BoardHeaderStrip(
            uiState = uiState,
            onToggleOverlay = {
              val started = viewModel.toggleOverlayService()
              if (!started) onNavigateToWatcher()
            },
            onEloStepCycle = {
              val nextElo =
                when {
                  shared.targetElo < 1500 -> 1800
                  shared.targetElo < 2400 -> 2600
                  shared.targetElo < 3000 -> 3200
                  else -> 1000
                }
              viewModel.setTargetElo(nextElo)
            },
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            EvalBar(
              evalCp = analysis.evalCp,
              mateIn = analysis.mateIn,
              scoreDisplay = analysis.scoreDisplay,
              flipped = shared.flippedBoard,
              modifier = Modifier.fillMaxHeight(0.92f),
            )
            ChessBoardView(
              board = shared.board,
              analysis = analysis,
              lastMove = shared.moveHistory.lastOrNull()?.move,
              selectedSquare = uiState.selectedSquare,
              legalDestinations = uiState.legalDestinations,
              flipped = shared.flippedBoard,
              showSecondaryArrows = shared.showSecondaryArrows,
              showThreatArrow = shared.showThreatArrow,
              arrowOpacity = shared.arrowOpacity,
              onSquareTap = viewModel::onSquareTapped,
              modifier = Modifier.fillMaxHeight(0.92f),
            )
          }
        }

        Column(
          modifier = Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          BoardTacticalPanel(
            uiState = uiState,
            viewModel = viewModel,
            onOpenImport = { showImportDialog = true },
            onCopyFen = { copyFen(context, shared.board.toFen(), viewModel) },
          )
        }
      }
    } else {
      Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        BoardHeaderStrip(
          uiState = uiState,
          onToggleOverlay = {
            val started = viewModel.toggleOverlayService()
            if (!started) onNavigateToWatcher()
          },
          onEloStepCycle = {
            val nextElo =
              when {
                shared.targetElo < 1500 -> 1800
                shared.targetElo < 2400 -> 2600
                shared.targetElo < 3000 -> 3200
                else -> 1000
              }
            viewModel.setTargetElo(nextElo)
          },
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          EvalBar(
            evalCp = analysis.evalCp,
            mateIn = analysis.mateIn,
            scoreDisplay = analysis.scoreDisplay,
            flipped = shared.flippedBoard,
            modifier = Modifier.height(310.dp),
          )
          ChessBoardView(
            board = shared.board,
            analysis = analysis,
            lastMove = shared.moveHistory.lastOrNull()?.move,
            selectedSquare = uiState.selectedSquare,
            legalDestinations = uiState.legalDestinations,
            flipped = shared.flippedBoard,
            showSecondaryArrows = shared.showSecondaryArrows,
            showThreatArrow = shared.showThreatArrow,
            arrowOpacity = shared.arrowOpacity,
            onSquareTap = viewModel::onSquareTapped,
            modifier = Modifier.weight(1f),
          )
        }

        BoardTacticalPanel(
          uiState = uiState,
          viewModel = viewModel,
          onOpenImport = { showImportDialog = true },
          onCopyFen = { copyFen(context, shared.board.toFen(), viewModel) },
        )
        Spacer(modifier = Modifier.height(8.dp))
      }
    }
  }

  uiState.pendingPromotion?.let {
    PromotionPickerDialog(
      color = shared.board.sideToMove,
      onSelect = viewModel::onPromotionChosen,
      onDismiss = viewModel::dismissPromotion,
    )
  }

  if (showImportDialog) {
    FenPgnImportDialog(
      currentFen = shared.board.toFen(),
      onImport = { input ->
        if (viewModel.importFenOrPgn(input)) {
          showImportDialog = false
        }
      },
      onDismiss = { showImportDialog = false },
    )
  }
}

@Composable
private fun BoardHeaderStrip(
  uiState: KnightSightUiState,
  onToggleOverlay: () -> Unit,
  onEloStepCycle: () -> Unit,
) {
  val shared = uiState.shared
  val analysis = shared.analysis

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = shared.activeLabel ?: analysis.openingName ?: "Tactical Analysis",
          style = MaterialTheme.typography.titleLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val statusLine =
          when (analysis.status) {
            GameStatus.CHECK ->
              "${if (shared.board.sideToMove == PieceColor.WHITE) "White" else "Black"} is in CHECK"
            GameStatus.CHECKMATE_WHITE_WINS -> "Checkmate • White Wins"
            GameStatus.CHECKMATE_BLACK_WINS -> "Checkmate • Black Wins"
            GameStatus.STALEMATE -> "Draw • Stalemate"
            GameStatus.DRAW_FIFTY_MOVE -> "Draw • 50-Move Rule"
            GameStatus.DRAW_INSUFFICIENT_MATERIAL -> "Draw • Insufficient Material"
            GameStatus.ONGOING ->
              if (shared.board.sideToMove == PieceColor.WHITE) "White to move • Move ${shared.board.fullmoveNumber}"
              else "Black to move • Move ${shared.board.fullmoveNumber}"
          }
        Text(
          text = statusLine,
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (analysis.status == GameStatus.CHECK || analysis.status == GameStatus.CHECKMATE_WHITE_WINS || analysis.status == GameStatus.CHECKMATE_BLACK_WINS)
              ThreatCrimson
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
          onClick = onEloStepCycle,
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier.testTag("depth_cycle_button"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Bolt,
              contentDescription = "Cycle engine Elo preset",
              tint = AmberGold,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text = "${shared.targetElo} • D${shared.engineDepth}",
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }

        Button(
          onClick = onToggleOverlay,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor =
                if (shared.overlayServiceRunning) ThreatCrimson else TacticalEmerald,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.testTag("quick_overlay_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Layers,
            contentDescription = "Toggle floating overlay",
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = if (shared.overlayServiceRunning) "Overlay On" else "Overlay",
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    }

    AnimatedVisibility(visible = shared.isAnalyzing) {
      LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp)),
        color = TacticalEmerald,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
      )
    }
  }
}

@Composable
private fun BoardTacticalPanel(
  uiState: KnightSightUiState,
  viewModel: MainViewModel,
  onOpenImport: () -> Unit,
  onCopyFen: () -> Unit,
) {
  val shared = uiState.shared
  val analysis = shared.analysis

  // Mode selector chips
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FilterChip(
      selected = shared.interactionMode == BoardInteractionMode.ASSIST_ARROWS,
      onClick = { viewModel.setInteractionMode(BoardInteractionMode.ASSIST_ARROWS) },
      label = { Text("Assist Arrows") },
      colors =
        FilterChipDefaults.filterChipColors(
          selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
          selectedLabelColor = TacticalEmerald,
        ),
      modifier = Modifier.testTag("mode_assist_chip"),
    )
    FilterChip(
      selected = shared.interactionMode == BoardInteractionMode.PLAY_VS_ENGINE,
      onClick = { viewModel.setInteractionMode(BoardInteractionMode.PLAY_VS_ENGINE) },
      label = {
        Text(
          if (shared.interactionMode == BoardInteractionMode.PLAY_VS_ENGINE) {
            "vs Engine (${if (shared.humanPlaySide == PieceColor.WHITE) "W" else "B"})"
          } else "Play vs Engine",
        )
      },
      colors =
        FilterChipDefaults.filterChipColors(
          selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
          selectedLabelColor = ElectricCyan,
        ),
      modifier = Modifier.testTag("mode_play_chip"),
    )
    if (shared.interactionMode == BoardInteractionMode.PLAY_VS_ENGINE) {
      TextButton(
        onClick = {
          val nextSide =
            if (shared.humanPlaySide == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE
          viewModel.setHumanPlaySide(nextSide)
        },
        modifier = Modifier.testTag("switch_play_side_button"),
      ) {
        Text("Play ${if (shared.humanPlaySide == PieceColor.WHITE) "Black" else "White"}")
      }
    }
  }

  // Top Engine Recommendations
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
            text = "BEST MOVE ARROWS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "${analysis.engineSource} • ${analysis.analysisTimeMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = TacticalEmerald,
          )
        }
        analysis.opponentThreat?.let { threat ->
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = "Threat: ${threat.san} (${threat.scoreDisplay})",
              style = MaterialTheme.typography.labelSmall,
              color = ThreatCrimson,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        }
      }

      if (analysis.topMoves.isEmpty()) {
        Text(
          text = "Game finished — reset board or load a new position.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          analysis.topMoves.forEach { suggested ->
            SuggestedMoveRow(
              suggested = suggested,
              onPlay = { viewModel.playSuggestedMove(suggested) },
            )
          }
        }
      }
    }
  }

  // Elo & Depth Strength Slider + Stockfish API toggle
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "ENGINE ELO & DEPTH",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text =
              "${shared.targetElo} Elo (${EloDepthMapper.tierLabel(shared.targetElo)}) • Depth ${shared.engineDepth} • ${EloDepthMapper.speedLabel(shared.targetElo)}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = TacticalEmerald,
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "Stockfish",
            style = MaterialTheme.typography.labelMedium,
            color = if (shared.useStockfishApi) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Switch(
            checked = shared.useStockfishApi,
            onCheckedChange = viewModel::setUseStockfishApi,
            modifier = Modifier.testTag("stockfish_api_switch"),
          )
        }
      }

      Slider(
        value = shared.targetElo.toFloat(),
        onValueChange = { raw ->
          val snapped = ((raw / 100f).roundToInt() * 100).coerceIn(800, 3200)
          viewModel.setTargetElo(snapped)
        },
        valueRange = EloDepthMapper.MIN_ELO.toFloat()..EloDepthMapper.MAX_ELO.toFloat(),
        steps = 23,
        colors =
          SliderDefaults.colors(
            thumbColor = TacticalEmerald,
            activeTrackColor = TacticalEmerald,
          ),
        modifier = Modifier.testTag("elo_depth_slider"),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("800 • Instant (D1)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("2000 • Balanced (D8)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("3200 • Deep/Slow (D15)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(
          selected = shared.antiLagEnabled,
          onClick = { viewModel.setAntiLagEnabled(!shared.antiLagEnabled) },
          label = { Text(if (shared.antiLagEnabled) "Anti-Lag ON" else "Anti-Lag OFF") },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = TacticalEmerald,
            ),
          modifier = Modifier.weight(1f).testTag("board_antilag_chip"),
        )
        FilterChip(
          selected = shared.showFloatingStatsHud,
          onClick = { viewModel.setShowFloatingStatsHud(!shared.showFloatingStatsHud) },
          label = { Text(if (shared.showFloatingStatsHud) "Floating Stats ON" else "Floating Stats OFF") },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
              selectedLabelColor = ElectricCyan,
            ),
          modifier = Modifier.weight(1f).testTag("board_floating_stats_chip"),
        )
      }
    }
  }

  // Stockfish Move Quality Counter Card (Brilliant, Excellent, Good)
  MoveQualitySummaryCard(
    uiState = uiState,
    onCycleSide = viewModel::cycleTrackedPlayerSide,
  )

  // Controls Row
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    shape = RoundedCornerShape(14.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = viewModel::undoMove,
        enabled = shared.moveHistory.isNotEmpty(),
        modifier = Modifier.testTag("undo_button"),
      ) {
        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo move")
      }
      IconButton(
        onClick = viewModel::redoMove,
        enabled = shared.redoStack.isNotEmpty(),
        modifier = Modifier.testTag("redo_button"),
      ) {
        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo move")
      }
      IconButton(onClick = viewModel::toggleFlipped, modifier = Modifier.testTag("flip_board_button")) {
        Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip board")
      }
      IconButton(onClick = onOpenImport, modifier = Modifier.testTag("import_fen_button")) {
        Icon(Icons.Default.UploadFile, contentDescription = "Import FEN or PGN")
      }
      IconButton(onClick = onCopyFen, modifier = Modifier.testTag("copy_fen_button")) {
        Icon(Icons.Default.ContentCopy, contentDescription = "Copy FEN")
      }
      IconButton(onClick = viewModel::resetBoard, modifier = Modifier.testTag("reset_board_button")) {
        Icon(Icons.Default.Refresh, contentDescription = "Reset board")
      }
    }
  }

  // Move history tape with Stockfish quality badges
  if (shared.moveHistory.isNotEmpty()) {
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      contentPadding = PaddingValues(vertical = 2.dp),
    ) {
      itemsIndexed(shared.moveHistory) { idx, rec ->
        val movePrefix = if (idx % 2 == 0) "${idx / 2 + 1}." else ""
        val qColor =
          when (rec.quality) {
            MoveQuality.BRILLIANT -> ElectricCyan
            MoveQuality.EXCELLENT -> TacticalEmerald
            MoveQuality.GOOD -> Color(0xFF4ADE80)
            MoveQuality.INACCURACY -> AmberGold
            MoveQuality.MISTAKE,
            MoveQuality.BLUNDER -> ThreatCrimson
            null -> MaterialTheme.colorScheme.onSurface
          }
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(8.dp),
          border =
            if (rec.quality == MoveQuality.BRILLIANT || rec.quality == MoveQuality.EXCELLENT) {
              BorderStroke(1.dp, qColor.copy(alpha = 0.65f))
            } else null,
        ) {
          val badge = rec.quality?.symbol?.let { " $it" } ?: ""
          Text(
            text = "$movePrefix${rec.san}$badge",
            style = MaterialTheme.typography.labelMedium,
            color = qColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun MoveQualitySummaryCard(
  uiState: KnightSightUiState,
  onCycleSide: () -> Unit,
) {
  val shared = uiState.shared
  val stats = shared.moveStats

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.testTag("move_quality_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
            text = "STOCKFISH MOVE GRADES",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text =
              if (stats.lastMoveSan != null && stats.lastMoveQuality != null) {
                "Last: ${stats.lastMoveQuality.symbol} ${stats.lastMoveSan} (${stats.lastMoveQuality.label})"
              } else {
                "Play or sync moves to track quality"
              },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          Surface(
            onClick = onCycleSide,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.testTag("tracked_side_button"),
          ) {
            Text(
              text =
                when (shared.trackedPlayerSide) {
                  PieceColor.WHITE -> "You: White"
                  PieceColor.BLACK -> "You: Black"
                  null -> "Both Sides"
                },
              style = MaterialTheme.typography.labelSmall,
              color = ElectricCyan,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
          }

          Surface(
            color = TacticalEmerald.copy(alpha = 0.16f),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = "${stats.accuracyPercent}%",
              style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
              color = TacticalEmerald,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        QualityCounterChip(
          label = "!! Brilliant",
          count = stats.brilliantCount,
          accent = ElectricCyan,
          modifier = Modifier.weight(1f).testTag("count_brilliant"),
        )
        QualityCounterChip(
          label = "! Excellent",
          count = stats.excellentCount,
          accent = TacticalEmerald,
          modifier = Modifier.weight(1f).testTag("count_excellent"),
        )
        QualityCounterChip(
          label = "✓ Good",
          count = stats.goodCount,
          accent = Color(0xFF4ADE80),
          modifier = Modifier.weight(1f).testTag("count_good"),
        )
      }

      if (stats.inaccuracyCount > 0 || stats.mistakeCount > 0 || stats.blunderCount > 0) {
        Text(
          text = "Inaccuracies: ${stats.inaccuracyCount} • Mistakes: ${stats.mistakeCount} • Blunders: ${stats.blunderCount}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun QualityCounterChip(
  label: String,
  count: Int,
  accent: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = accent.copy(alpha = 0.12f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
    shape = RoundedCornerShape(12.dp),
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = count.toString(),
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = accent,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun SuggestedMoveRow(
  suggested: SuggestedMove,
  onPlay: () -> Unit,
) {
  val accentColor =
    when (suggested.rank) {
      1 -> TacticalEmerald
      2 -> ElectricCyan
      else -> AmberGold
    }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onPlay)
        .testTag("suggested_move_${suggested.rank}"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
        modifier = Modifier.size(26.dp).clip(CircleShape).background(accentColor),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "#${suggested.rank}",
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
          color = Color(0xFF0D1117),
        )
      }

      Text(
        text = suggested.san,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = accentColor,
        modifier = Modifier.width(64.dp),
      )

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = suggested.tacticalTag,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (suggested.pvLine.isNotEmpty()) {
          Text(
            text = suggested.pvLine.joinToString(" "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Surface(
        color = accentColor.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
      ) {
        Text(
          text = suggested.scoreDisplay,
          style = MaterialTheme.typography.labelMedium,
          color = accentColor,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }

      Icon(
        imageVector = Icons.Default.PlayArrow,
        contentDescription = "Play ${suggested.san}",
        tint = accentColor,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
private fun PromotionPickerDialog(
  color: PieceColor,
  onSelect: (PieceType) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Promote Pawn") },
    text = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        listOf(
            PieceType.QUEEN to if (color == PieceColor.WHITE) "♕" else "♛",
            PieceType.ROOK to if (color == PieceColor.WHITE) "♖" else "♜",
            PieceType.BISHOP to if (color == PieceColor.WHITE) "♗" else "♝",
            PieceType.KNIGHT to if (color == PieceColor.WHITE) "♘" else "♞",
          )
          .forEach { (type, glyph) ->
            OutlinedButton(
              onClick = { onSelect(type) },
              modifier = Modifier.size(58.dp),
              contentPadding = PaddingValues(0.dp),
            ) {
              Text(text = glyph, style = MaterialTheme.typography.headlineMedium)
            }
          }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun FenPgnImportDialog(
  currentFen: String,
  onImport: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var text by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Import FEN or PGN") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Paste a FEN string or algebraic move list (e.g. 1. e4 c5 2. Nf3 d6) to visualize live arrows.",
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          label = { Text("FEN or PGN moves") },
          placeholder = { Text(currentFen) },
          minLines = 3,
          maxLines = 5,
          modifier = Modifier.fillMaxWidth().testTag("fen_pgn_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onImport(text) },
        enabled = text.isNotBlank(),
        modifier = Modifier.testTag("confirm_import_button"),
      ) {
        Text("Load Position")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

private fun copyFen(context: Context, fen: String, viewModel: MainViewModel) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
  clipboard?.setPrimaryClip(ClipData.newPlainText("Chess FEN", fen))
  viewModel.showMessage("Copied FEN to clipboard")
}
