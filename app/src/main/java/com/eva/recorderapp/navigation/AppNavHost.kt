package com.eva.recorderapp.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import com.eva.ui.navigation.LocalNavigateHome
import com.eva.ui.navigation.PlayerSubGraph
import com.eva.ui.navigation.navigateHome
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.eva.feature_categories.routes.categoryPickerRoute
import com.eva.feature_categories.routes.createOrEditCategoryRoute
import com.eva.feature_categories.routes.manageRecordingCategories
import com.eva.feature_recorder.recorderRoute
import com.eva.feature_recordings.bin.trashRecordingsRoute
import com.eva.feature_recordings.recordings.recordingsRoute
import com.eva.feature_recordings.rename.renameRecordingDialog
import com.eva.feature_recordings.search.recordingsSearchRoute
import com.eva.feature_settings.settingsRoute
import com.eva.recorderapp.navigation.navgraph.playerNavGraph
import com.eva.recorderapp.navigation.routes.appInfoDialog
import com.eva.ui.navigation.NavRoutes
import com.eva.ui.utils.LocalSharedTransitionScopeProvider
import com.eva.ui.utils.LocalSnackBarProvider


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavHost(
	modifier: Modifier = Modifier,
	onSetController: suspend (NavHostController) -> Unit = {},
	startInLibrary: Boolean = false,
) {
	val navController = rememberNavController()
	val entry by navController.currentBackStackEntryAsState()
	val activity = LocalActivity.current
	val currentOnSetController by rememberUpdatedState(onSetController)

	LaunchedEffect(navController) {
		currentOnSetController(navController)
	}

	val snackBarProvider = remember { SnackbarHostState() }

	SharedTransitionLayout {
		CompositionLocalProvider(
			LocalNavigateHome provides { navController.navigateHome() },
			LocalSnackBarProvider provides snackBarProvider,
			LocalSharedTransitionScopeProvider provides this,
		) {
			NavHost(
				navController = navController,
				startDestination = if (startInLibrary) NavRoutes.VoiceRecordings else NavRoutes.VoiceRecorder,
				modifier = modifier
			) {
				// screens
				recorderRoute(navController = navController)
				recordingsRoute(controller = navController)
				trashRecordingsRoute(controller = navController)
				recordingsSearchRoute(controller = navController)
				manageRecordingCategories(controller = navController)
				settingsRoute(controller = navController)
				categoryPickerRoute(controller = navController)
				createOrEditCategoryRoute(controller = navController)
				//dialogs
				appInfoDialog(navController)
				renameRecordingDialog(controller = navController)
				// subgraph
				playerNavGraph(controller = navController)
			}
		}
	}
	// Register after NavHost so nested navigation cannot send the task to the launcher.
	// The editor handles Back itself to protect unsaved edits.
	BackHandler(enabled = entry?.destination?.hasRoute<PlayerSubGraph.AudioEditorRoute>() != true) {
		if (entry?.destination?.hasRoute<NavRoutes.VoiceRecorder>() == true) activity?.moveTaskToBack(true)
		else navController.navigateHome()
	}
}

