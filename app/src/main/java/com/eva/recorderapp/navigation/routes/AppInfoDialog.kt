package com.eva.recorderapp.navigation.routes

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.dialog
import com.eva.recorderapp.BuildConfig
import com.eva.ui.R
import com.eva.ui.navigation.NavDialogs
import com.eva.ui.navigation.navigateHome
import com.eva.utils.ApplicationInfo

fun NavGraphBuilder.appInfoDialog(controller: NavController) = dialog<NavDialogs.ApplicationInfo>(
    dialogProperties = DialogProperties(dismissOnBackPress = false),
) {
    BackHandler { controller.navigateHome() }
    AppDialogInfoContent { controller.popBackStack() }
}

@Composable
private fun AppDialogInfoContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val links = listOf(
        stringResource(R.string.app_info_source_code) to ApplicationInfo.GITHUB_PROJECT_LINK,
        "使用條款" to "https://gujiu502.github.io/photo-keyframe-recorder/terms.html",
        "隱私權政策" to "https://gujiu502.github.io/photo-keyframe-recorder/privacy.html",
        stringResource(R.string.app_info_author) to ApplicationInfo.PROJECT_AUTHOR_LINK,
    )
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("關於應用程式", style = MaterialTheme.typography.titleLarge)
            Icon(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider()
            for ((title, url) in links) OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.cannot_launch_activity, Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text(title) }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("關閉") }
        }
    }
}
