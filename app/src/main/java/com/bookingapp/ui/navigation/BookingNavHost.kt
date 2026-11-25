package com.bookingapp.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bookingapp.ui.dashboard.DashboardRoute
import com.bookingapp.ui.edit.EditRoute
import com.bookingapp.ui.importing.ImportRoute
import com.bookingapp.ui.transactions.TransactionsRoute
import kotlinx.coroutines.launch

private enum class TopLevel(val label: String, val icon: ImageVector, val route: Any) {
    DASHBOARD("Dashboard", Icons.Filled.Home, Dashboard()),
    TRANSACTIONS("Transactions", Icons.AutoMirrored.Filled.List, Transactions()),
    ADD("Add", Icons.Filled.Add, Edit()),
    IMPORT("Import", Icons.AutoMirrored.Filled.ExitToApp, Import),
}

/** Navigates to a top-level screen, keeping only the Dashboard beneath it. */
private fun NavHostController.navigateTopLevel(route: Any) = navigate(route) {
    popUpTo<Dashboard>()
    launchSingleTop = true
}

/** Opens the Dashboard with nothing from before left on the back stack. */
private fun NavHostController.restartAtDashboard() = navigate(Dashboard()) {
    popUpTo(graph.id) { inclusive = true }
}

@Composable
fun BookingNavHost(navController: NavHostController = rememberNavController()) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TopLevel.entries.forEach { item ->
                    val selected = when (item) {
                        TopLevel.DASHBOARD -> destination?.hasRoute<Dashboard>() == true
                        TopLevel.TRANSACTIONS -> destination?.hasRoute<Transactions>() == true ||
                            (destination?.hasRoute<Edit>() == true && entry?.toRoute<Edit>()?.id != null)
                        TopLevel.ADD -> destination?.hasRoute<Edit>() == true && entry?.toRoute<Edit>()?.id == null
                        TopLevel.IMPORT -> destination?.hasRoute<Import>() == true
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateTopLevel(item.route) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Dashboard(), modifier = Modifier.padding(padding)) {
            composable<Dashboard> { DashboardRoute() }
            composable<Transactions> {
                TransactionsRoute(
                    onEdit = { id -> navController.navigate(Edit(id)) },
                    onAdd = { navController.navigateTopLevel(Edit()) },
                )
            }
            composable<Edit> {
                EditRoute(
                    onViewInList = { route -> navController.navigateTopLevel(route) },
                    onUpdated = { route -> navController.navigateTopLevel(route) },
                )
            }
            composable<Import> {
                ImportRoute(
                    onViewMonth = { route -> navController.navigateTopLevel(route) },
                    onLedgerReplaced = { message ->
                        navController.restartAtDashboard()
                        scope.launch { snackbar.showSnackbar(message) }
                    },
                )
            }
        }
    }
}
