package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chess.ChessLibraryData
import com.example.chess.OpeningLine
import com.example.chess.PieceColor
import com.example.chess.TacticalPuzzle
import com.example.ui.MainViewModel
import com.example.ui.theme.AmberGold
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.TacticalEmerald

@Composable
fun DrillsScreen(
  viewModel: MainViewModel,
  onNavigateToBoard: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedSection by remember { mutableIntStateOf(0) }

  Column(
    modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(text = "Tactical Drills & Repertoire", style = MaterialTheme.typography.headlineMedium)
      Text(
        text = "Load classic tactics or grandmaster openings onto the board to study live arrow evaluations.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      FilterChip(
        selected = selectedSection == 0,
        onClick = { selectedSection = 0 },
        leadingIcon = {
          Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        label = { Text("Tactical Puzzles (${ChessLibraryData.puzzles.size})") },
        colors =
          FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = TacticalEmerald,
          ),
        modifier = Modifier.testTag("tab_puzzles_chip"),
      )
      FilterChip(
        selected = selectedSection == 1,
        onClick = { selectedSection = 1 },
        leadingIcon = {
          Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        label = { Text("Openings (${ChessLibraryData.openings.size})") },
        colors =
          FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = ElectricCyan,
          ),
        modifier = Modifier.testTag("tab_openings_chip"),
      )
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = PaddingValues(bottom = 16.dp),
    ) {
      if (selectedSection == 0) {
        items(ChessLibraryData.puzzles, key = { it.id }) { puzzle ->
          PuzzleCard(
            puzzle = puzzle,
            onLoad = {
              viewModel.loadPuzzle(puzzle)
              onNavigateToBoard()
            },
          )
        }
      } else {
        items(ChessLibraryData.openings, key = { it.id }) { opening ->
          OpeningCard(
            opening = opening,
            onLoad = {
              viewModel.loadOpening(opening)
              onNavigateToBoard()
            },
          )
        }
      }
    }
  }
}

@Composable
private fun PuzzleCard(
  puzzle: TacticalPuzzle,
  onLoad: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(text = puzzle.title, style = MaterialTheme.typography.titleMedium)
        Surface(
          color = AmberGold.copy(alpha = 0.18f),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text(
            text = puzzle.difficulty,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = AmberGold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(6.dp),
        ) {
          Text(
            text = puzzle.theme,
            style = MaterialTheme.typography.labelSmall,
            color = TacticalEmerald,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(6.dp),
        ) {
          Text(
            text = if (puzzle.sideToMove == PieceColor.WHITE) "White to play" else "Black to play",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }

      Text(
        text = puzzle.hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Button(
        onClick = onLoad,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = TacticalEmerald,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().testTag("load_puzzle_${puzzle.id}"),
      ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Analyze with Arrows")
      }
    }
  }
}

@Composable
private fun OpeningCard(
  opening: OpeningLine,
  onLoad: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(text = opening.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Surface(
          color = ElectricCyan.copy(alpha = 0.18f),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text(
            text = "${opening.eco} • ${opening.style}",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ElectricCyan,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }

      Text(
        text = opening.movesPgn,
        style = MaterialTheme.typography.labelMedium,
        color = TacticalEmerald,
      )
      Text(
        text = opening.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Button(
        onClick = onLoad,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = ElectricCyan,
            contentColor = MaterialTheme.colorScheme.onSecondary,
          ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().testTag("load_opening_${opening.id}"),
      ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Load Opening & Show Arrows")
      }
    }
  }
}
