package com.eva.recorderapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.eva.cloud.DriveBackup
import com.eva.database.RecorderDataBase
import com.eva.ui.theme.RecorderAppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AccountActivity : ComponentActivity() {
    @Inject lateinit var backup: DriveBackup
    @Inject lateinit var db: RecorderDataBase
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { RecorderAppTheme { AccountGate(this, backup, db) { finish() } } }
    }
}
