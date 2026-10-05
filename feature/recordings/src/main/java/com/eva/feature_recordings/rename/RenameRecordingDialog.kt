package com.eva.feature_recordings.rename

import com.eva.ui.navigation.navigateHome
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.dialog
import com.eva.feature_recordings.handlers.RenameItemRequestHandler
import com.eva.ui.navigation.NavDialogs
import com.eva.ui.utils.UiEventsHandler

fun NavGraphBuilder.renameRecordingDialog(controller: NavController) =
	dialog<NavDialogs.RenameRecordingDialog>(
		dialogProperties = DialogProperties(
			dismissOnClickOutside = false,
			dismissOnBackPress = false
		),
	) {

		val viewModel = hiltViewModel<RenameDialogViewModel>()
		val renameState by viewModel.renameState.collectAsStateWithLifecycle()

		RenameItemRequestHandler(
			onWriteAccessChange = viewModel::onEvent,
			permissionEventDeferred = viewModel::permissionEvent
		)

		UiEventsHandler(
			eventsFlow = viewModel::uiEvent,
			onNavigateBack = dropUnlessResumed(block = controller::navigateHome)
		)

		BackHandler { controller.navigateHome() }

		RenameRecordingsDialogContent(
			state = renameState,
			onEvent = viewModel::onEvent,
			onDismissRequest = dropUnlessResumed(block = controller::navigateHome),
		)
	}