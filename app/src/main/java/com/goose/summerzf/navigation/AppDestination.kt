package com.goose.summerzf.navigation

sealed class AppDestination(val route: String) {
    data object Hud: AppDestination("hud")
    data object Debug: AppDestination("debug")
    data object ThemeEditor: AppDestination("theme_editor")
    data object QrScan: AppDestination("qr_scan")
    data object Login: AppDestination("login")
}
