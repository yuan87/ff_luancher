package com.ff.launcher

import android.content.Intent
import android.os.Bundle
import com.ff.launcher.util.Logger
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.ff.launcher.ui.settings.SettingsScreen
import com.ff.launcher.ui.theme.FFLauncherTheme
import com.ff.launcher.viewmodel.SettingsViewModel

/**
 * Dedicated standalone activity for FF Launcher settings.
 *
 * Runs in its own task affinity ([TASK_AFFINITY]) so it surfaces independently in
 * Android's Recent Apps (Overview) carousel. Excluded from FF Launcher's own app drawer
 * by package filtering, but resolvable by external launchers via [Intent.CATEGORY_LAUNCHER].
 */
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    private val homeRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Logger.d(TAG) { "homeRoleLauncher returned, refreshing default launcher state" }
            viewModel.refreshDefaultLauncherState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i(TAG) { "onCreate called, taskId=$taskId" }
        enableEdgeToEdge()
        setContent {
            FFLauncherTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onRequestDefaultLauncher = {
                        homeRoleLauncher.launch(viewModel.buildSetDefaultIntent())
                    },
                    onReplaceHomeWidget = {
                        val homeIntent = Intent(this, MainActivity::class.java).apply {
                            action = MainActivity.ACTION_REPLACE_HOME_WIDGET
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(homeIntent)
                    },
                    onOpenSecondScreen = {
                        val homeIntent = Intent(this, MainActivity::class.java).apply {
                            action = MainActivity.ACTION_OPEN_SECOND_SCREEN
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(homeIntent)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Logger.i(TAG) { "onResume called" }
        viewModel.refreshDefaultLauncherState()
        viewModel.checkNotificationAccess()
    }

    companion object {
        private const val TAG = "SettingsActivity"
        const val TASK_AFFINITY = "com.ff.launcher.settings"
    }
}
