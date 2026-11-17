package com.ff.launcher

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import com.ff.launcher.util.Logger
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ff.launcher.ui.LauncherRoot
import com.ff.launcher.ui.theme.FFLauncherTheme
import com.ff.launcher.viewmodel.LauncherViewModel
import com.ff.launcher.viewmodel.WidgetRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    private val widgetBindLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Logger.d(TAG) { "widgetBindLauncher result: ${result.resultCode}, data: ${result.data}" }
            viewModel.onBindPermissionResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i(TAG) { "onCreate called, savedInstanceState=${savedInstanceState != null}" }
        enableEdgeToEdge()
        setContent {
            FFLauncherTheme {
                LauncherRoot(viewModel = viewModel)
            }
        }
        handleHomeIntent(intent)
        // Route widget picker intents to the registered launcher. Configure pages take a
        // different path: AppWidgetHost.startAppWidgetConfigureActivityForResult (see
        // onActivityResult), because the system must grant access to non-exported
        // configure activities a plain Intent launch would crash on.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.widgetRequests.collect { request ->
                    Logger.d(TAG) { "widgetRequests received: $request" }
                    when (request) {
                        is WidgetRequest.BindPermission ->
                            widgetBindLauncher.launch(request.intent)
                        is WidgetRequest.Configure ->
                            viewModel.startWidgetConfigure(
                                this@MainActivity,
                                request.appWidgetId,
                                REQUEST_CONFIGURE_WIDGET,
                            )
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Logger.d(TAG) { "onActivityResult: requestCode=$requestCode, resultCode=$resultCode" }
        if (requestCode == REQUEST_CONFIGURE_WIDGET) {
            viewModel.onWidgetConfigureResult(resultCode, data)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_REPLACE_HOME_WIDGET = "com.ff.launcher.action.REPLACE_HOME_WIDGET"
        const val ACTION_OPEN_SECOND_SCREEN = "com.ff.launcher.action.OPEN_SECOND_SCREEN"
        const val ACTION_OPEN_WIDGET_DECK = "com.ff.launcher.action.OPEN_WIDGET_DECK"
        const val EXTRA_OPEN_SECOND_SCREEN = "com.ff.launcher.extra.OPEN_SECOND_SCREEN"
        private const val REQUEST_CONFIGURE_WIDGET = 1001
    }

    override fun onResume() {
        super.onResume()
        Logger.i(TAG) { "onResume called" }
        // Ensure this home launcher task is never retained in the recent apps carousel,
        // and clean up any transient AppEntryActivity trampoline task, while preserving
        // the standalone SettingsActivity so the user can switch back to it via Recent Apps:
        lifecycleScope.launch(Dispatchers.Default) {
            val am = getSystemService(ActivityManager::class.java)
            am?.appTasks?.forEach { appTask ->
                val info = runCatching { appTask.taskInfo }.getOrNull()
                if (info != null && info.taskId != taskId) {
                    if (info.baseIntent.component?.className == AppEntryActivity::class.java.name) {
                        runCatching { appTask.finishAndRemoveTask() }
                    }
                } else {
                    runCatching { appTask.setExcludeFromRecents(true) }
                }
            }
        }
        viewModel.setHostResumed(true)
        // Re-check the default-home role: the user may have just confirmed the system
        // dialog (or changed it in system settings).
        viewModel.refreshDefaultLauncherState()
        viewModel.checkNotificationAccess()
        com.ff.launcher.notification.BadgeNotificationListener.requestSync()
    }

    override fun onPause() {
        super.onPause()
        Logger.i(TAG) { "onPause called" }
        viewModel.setHostResumed(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Logger.i(TAG) { "onNewIntent called with intent=$intent" }
        // Return mechanic #2: the bottom-up Home gesture re-delivers ACTION_MAIN +
        // CATEGORY_HOME to this singleTask activity — collapse the Secondary Screen.
        handleHomeIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        Logger.i(TAG) { "onStop called" }
        // Do not force-close the Secondary Screen onStop: when the user taps a widget
        // and later taps Back, they return to the Secondary Screen they launched it from.
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(TAG) { "onDestroy called" }
    }

    private fun handleHomeIntent(intent: Intent?) {
        Logger.d(TAG) { "handleHomeIntent: action=${intent?.action}, categories=${intent?.categories}" }
        if (intent?.action == ACTION_REPLACE_HOME_WIDGET) {
            viewModel.replaceHomeWidget()
            return
        }
        if (intent?.action == ACTION_OPEN_SECOND_SCREEN ||
            intent?.action == ACTION_OPEN_WIDGET_DECK ||
            intent?.getBooleanExtra(EXTRA_OPEN_SECOND_SCREEN, false) == true ||
            intent?.getBooleanExtra("open_second_screen", false) == true
        ) {
            viewModel.setSecondScreenOpen(true)
            return
        }
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            viewModel.onHomeIntent()
        }
    }
}
