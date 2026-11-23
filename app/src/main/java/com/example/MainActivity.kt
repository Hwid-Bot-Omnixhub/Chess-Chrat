package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.MainViewModel
import com.example.ui.screens.BoardScreen
import com.example.ui.screens.DrillsScreen
import com.example.ui.screens.WatcherScreen
import com.example.ui.theme.KnightSightTheme

const val ROUTE_BOARD = "board"
const val ROUTE_WATCHER = "watcher"
const val ROUTE_DRILLS = "drills"

private data class NavDestinationItem(
  val route: String,
  val labelRes: Int,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

private val NAV_ITEMS =
  listOf(
    NavDestinationItem(
      route = ROUTE_BOARD,
      labelRes = R.string.tab_board,
      selectedIcon = Icons.Filled.GridOn,
      unselectedIcon = Icons.Outlined.GridOn,
      testTag = "nav_tab_board",
    ),
    NavDestinationItem(
      route = ROUTE_WATCHER,
      labelRes = R.string.tab_watcher,
      selectedIcon = Icons.Filled.Radar,
      unselectedIcon = Icons.Outlined.Radar,
      testTag = "nav_tab_watcher",
    ),
    NavDestinationItem(
      route = ROUTE_DRILLS,
      labelRes = R.string.tab_drills,
      selectedIcon = Icons.Filled.AutoAwesome,
      unselectedIcon = Icons.Outlined.AutoAwesome,
      testTag = "nav_tab_drills",
    ),
  )

class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      KnightSightTheme {
        KnightSightApp(viewModel = viewModel)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshSystemStatus()
  }
}

@Composable
fun KnightSightApp(viewModel: MainViewModel) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route ?: ROUTE_BOARD
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(uiState.statusMessage) {
    val msg = uiState.statusMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearMessage()
    }
  }

  fun navigateTo(route: String) {
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      bottomBar = {
        if (!useRail) {
          NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NAV_ITEMS.forEach { item ->
              val selected = currentRoute == item.route
              NavigationBarItem(
                selected = selected,
                onClick = { navigateTo(item.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                    contentDescription = stringResource(item.labelRes),
                  )
                },
                label = { Text(stringResource(item.labelRes)) },
                modifier = Modifier.testTag(item.testTag),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (useRail) {
          NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxHeight(),
          ) {
            NAV_ITEMS.forEach { item ->
              val selected = currentRoute == item.route
              NavigationRailItem(
                selected = selected,
                onClick = { navigateTo(item.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                    contentDescription = stringResource(item.labelRes),
                  )
                },
                label = { Text(stringResource(item.labelRes)) },
                modifier = Modifier.testTag(item.testTag),
              )
            }
          }
        }

        NavHost(
          navController = navController,
          startDestination = ROUTE_BOARD,
          modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
          composable(ROUTE_BOARD) {
            BoardScreen(
              uiState = uiState,
              viewModel = viewModel,
              onNavigateToWatcher = { navigateTo(ROUTE_WATCHER) },
            )
          }
          composable(ROUTE_WATCHER) {
            WatcherScreen(
              uiState = uiState,
              viewModel = viewModel,
            )
          }
          composable(ROUTE_DRILLS) {
            DrillsScreen(
              viewModel = viewModel,
              onNavigateToBoard = { navigateTo(ROUTE_BOARD) },
            )
          }
        }
      }
    }
  }
}
