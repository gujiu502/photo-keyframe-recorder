package com.eva.recorderapp

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import com.eva.recorderapp.navigation.AppNavHost
import com.eva.ui.R
import com.eva.ui.activity.animateOnExit
import com.eva.ui.theme.RecorderAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
	@javax.inject.Inject lateinit var sessions: com.eva.database.SessionStore
	@javax.inject.Inject lateinit var backup: com.eva.cloud.DriveBackup
	@javax.inject.Inject lateinit var db: com.eva.database.RecorderDataBase

	private var navController: NavHostController? = null
	private var homeRequest by mutableIntStateOf(0)

	override fun onCreate(savedInstanceState: Bundle?) {
		// splash needs to be initiated here
		val splash = installSplashScreen()

		super.onCreate(savedInstanceState)
		if (intent.getBooleanExtra("return_to_home", false)) homeRequest++
		if (intent.getBooleanExtra("request_recording_name", false)) {
			sessions.namingRequested.value = true
			intent.removeExtra("request_recording_name")
		}

		// set enable edge to edge normally
		enableEdgeToEdge()

		// on splash complete again enable edge to edge
		splash.animateOnExit(onAnimationEnd = { enableEdgeToEdge() })
		// set activity transitions
		setTransitions()

		setContent {
			RecorderAppTheme {
				Surface(color = MaterialTheme.colorScheme.background) {
					MainContent(this, sessions, backup, db, homeRequest) { readOnly -> AppNavHost(
						startInLibrary = readOnly,
						onSetController = { controller ->
							navController = controller
						},
					) }
				}
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		if (intent.getBooleanExtra("request_recording_name", false)) {
			sessions.namingRequested.value = true
			intent.removeExtra("request_recording_name")
		}
		if (intent.getBooleanExtra("return_to_home", false)) homeRequest++
		else navController?.handleDeepLink(intent)
	}

	override fun onStart() {
		super.onStart()
		backup.checkOnLaunch()
	}

	@Suppress("DEPRECATION")
	private fun setTransitions() {
		// allow activity transitions
		window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
		// set transitions
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			overrideActivityTransition(
				OVERRIDE_TRANSITION_OPEN,
				R.anim.activity_enter_transition,
				R.anim.activity_exit_transition
			)
		} else {
			overridePendingTransition(
				R.anim.activity_enter_transition,
				R.anim.activity_exit_transition
			)
		}
	}
}
