package com.goose.summerzf.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.goose.summerzf.core.qr.QrContent
import com.goose.summerzf.feature.ap.QrScanScreen
import com.goose.summerzf.feature.hud.HudDebugScreen
import com.goose.summerzf.feature.hud.HudScreen
import com.goose.summerzf.feature.login.LoginScreen
import com.goose.summerzf.feature.theme.ThemeEditorScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String = AppDestination.Hud.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(AppDestination.Hud.route) { backStackEntry ->
            val qrResultState = backStackEntry
                .savedStateHandle
                .getStateFlow<QrContent?>("qrResult", QrContent.Unknown)
                .collectAsState()

            HudScreen(
                hudQrValue = qrResultState.value,
                onShowQrScanner = {
                    navController.navigate(AppDestination.QrScan.route)
                },
                onShowDebug = {
                    navController.navigate(AppDestination.Debug.route)
                },
                onShowThemeEditor = {
                    navController.navigate(AppDestination.ThemeEditor.route)
                }
            )
        }

        composable(AppDestination.Debug.route) {
            HudDebugScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(AppDestination.ThemeEditor.route) {
            ThemeEditorScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(AppDestination.QrScan.route) {
            QrScanScreen(
                onQrScanned = { qrValue ->
                    navController.previousBackStackEntry?.savedStateHandle?.set("qrResult", qrValue)
                    navController.popBackStack()
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(AppDestination.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(AppDestination.Hud.route) {
                        popUpTo(AppDestination.Login.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
