package com.eva.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController

/** Clear nested pages so the next Back from the recorder returns to the launcher. */
fun NavController.navigateHome() {
    navigate(NavRoutes.VoiceRecorder) {
        popUpTo(graph.id)
        launchSingleTop = true
    }
}

val LocalNavigateHome = staticCompositionLocalOf<() -> Unit> { {} }
