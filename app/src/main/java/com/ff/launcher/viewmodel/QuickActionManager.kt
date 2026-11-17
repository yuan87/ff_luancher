package com.ff.launcher.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import com.ff.launcher.util.Logger
import com.ff.launcher.R
import com.ff.launcher.data.AppInfo
import com.ff.launcher.data.QuickActionConfig
import com.ff.launcher.data.QuickActionPreferencesRepository
import com.ff.launcher.data.QuickActionType
import com.ff.launcher.data.TriggerEdgeSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages the quick action floating button: configuration, live preview, execution,
 * and flashlight toggle.
 *
 * Extracted from [LauncherViewModel]. The ViewModel delegates method calls and
 * exposes [quickActionDisplay], [isFlashlightOn], [isQuickActionPickerOpen] unchanged.
 */
class QuickActionManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val repository: QuickActionPreferencesRepository,
    private val appLauncher: (AppInfo) -> Unit,
    private val allInstalledApps: () -> List<AppInfo>,
    private val userMessages: MutableSharedFlow<String>,
) {
    private val cameraManager = application.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private var torchCameraId: String? = null

    private val _isFlashlightOn = MutableStateFlow(false)
    val isFlashlightOn: StateFlow<Boolean> = _isFlashlightOn.asStateFlow()

    private val _isQuickActionPickerOpen = MutableStateFlow(false)
    val isQuickActionPickerOpen: StateFlow<Boolean> = _isQuickActionPickerOpen.asStateFlow()

    val quickActionConfig: StateFlow<QuickActionConfig> = repository.config
        .stateIn(scope, SharingStarted.Eagerly, QuickActionConfig())

    private val _quickActionPreview = MutableStateFlow<QuickActionConfig?>(null)
    val quickActionDisplay: StateFlow<QuickActionConfig> =
        combine(quickActionConfig, _quickActionPreview) { persisted, preview -> preview ?: persisted }
            .stateIn(scope, SharingStarted.Eagerly, QuickActionConfig())

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            Logger.d(TAG) { "onTorchModeChanged: cameraId=$cameraId, enabled=$enabled" }
            if (cameraId == torchCameraId || torchCameraId == null) {
                torchCameraId = cameraId
                _isFlashlightOn.value = enabled
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            Logger.d(TAG) { "onTorchModeUnavailable: cameraId=$cameraId" }
            if (cameraId == torchCameraId) {
                _isFlashlightOn.value = false
            }
        }
    }

    init {
        runCatching {
            torchCameraId = findFlashCameraId()
            cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
            Logger.i(TAG) { "Registered torch callback for cameraId=$torchCameraId" }
        }.onFailure { Logger.w(TAG, "Failed to register torch callback", it) }
    }

    private fun findFlashCameraId(): String? {
        val cm = cameraManager ?: return null
        return runCatching {
            cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cm.cameraIdList.firstOrNull()
        }.getOrNull()
    }

    fun openQuickActionPicker() {
        Logger.i(TAG) { "openQuickActionPicker" }
        _isQuickActionPickerOpen.value = true
    }

    fun closeQuickActionPicker() {
        Logger.i(TAG) { "closeQuickActionPicker" }
        _isQuickActionPickerOpen.value = false
    }

    fun setQuickActionEnabled(enabled: Boolean) {
        Logger.i(TAG) { "setQuickActionEnabled: enabled=$enabled" }
        scope.launch { repository.setEnabled(enabled) }
    }

    fun setQuickAction(type: QuickActionType, componentKey: String = "", label: String = "") {
        Logger.i(TAG) { "setQuickAction: type=$type, componentKey=$componentKey, label=$label" }
        scope.launch { repository.setAction(type, componentKey, label) }
    }

    fun setQuickActionEdgeSide(side: TriggerEdgeSide) {
        Logger.i(TAG) { "setQuickActionEdgeSide: side=$side" }
        scope.launch { repository.setEdgeSide(side) }
    }

    fun previewQuickActionVerticalBias(bias: Float) {
        val base = _quickActionPreview.value ?: quickActionConfig.value
        _quickActionPreview.value = base.copy(
            verticalBias = bias.coerceIn(QuickActionPreferencesRepository.VERTICAL_BIAS_RANGE)
        )
    }

    fun previewQuickActionSizeDp(sizeDp: Int) {
        val base = _quickActionPreview.value ?: quickActionConfig.value
        _quickActionPreview.value = base.copy(
            sizeDp = sizeDp.coerceIn(QuickActionPreferencesRepository.SIZE_RANGE)
        )
    }

    fun previewQuickActionOpacity(opacity: Float) {
        val base = _quickActionPreview.value ?: quickActionConfig.value
        _quickActionPreview.value = base.copy(
            opacity = opacity.coerceIn(QuickActionPreferencesRepository.OPACITY_RANGE)
        )
    }

    fun setQuickActionVerticalBias(bias: Float) {
        val clamped = bias.coerceIn(QuickActionPreferencesRepository.VERTICAL_BIAS_RANGE)
        Logger.i(TAG) { "setQuickActionVerticalBias: bias=$clamped" }
        _quickActionPreview.value = null
        scope.launch { repository.setVerticalBias(clamped) }
    }

    fun setQuickActionSizeDp(size: Int) {
        val clamped = size.coerceIn(QuickActionPreferencesRepository.SIZE_RANGE)
        Logger.i(TAG) { "setQuickActionSizeDp: size=$clamped" }
        _quickActionPreview.value = null
        scope.launch { repository.setSizeDp(clamped) }
    }

    fun setQuickActionOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(QuickActionPreferencesRepository.OPACITY_RANGE)
        Logger.i(TAG) { "setQuickActionOpacity: opacity=$clamped" }
        _quickActionPreview.value = null
        scope.launch { repository.setOpacity(clamped) }
    }

    fun resetQuickActionToDefaults() {
        Logger.i(TAG) { "resetQuickActionToDefaults" }
        _quickActionPreview.value = null
        scope.launch { repository.resetToDefaults() }
    }

    fun toggleFlashlight() {
        val cm = cameraManager ?: return
        runCatching {
            val cameraId = torchCameraId ?: findFlashCameraId() ?: return
            torchCameraId = cameraId
            val target = !_isFlashlightOn.value
            Logger.i(TAG) { "toggleFlashlight: target=$target, cameraId=$cameraId" }
            cm.setTorchMode(cameraId, target)
            _isFlashlightOn.value = target
        }.onFailure { Logger.w(TAG, "Failed to toggle flashlight", it) }
    }

    fun executeQuickAction(onOpenSecondScreen: () -> Unit) {
        val config = quickActionConfig.value
        Logger.i(TAG) { "executeQuickAction: type=${config.actionType}, key=${config.componentKey}" }
        when (config.actionType) {
            QuickActionType.SECOND_SCREEN -> onOpenSecondScreen()
            QuickActionType.FLASHLIGHT -> toggleFlashlight()
            QuickActionType.CAMERA -> {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { application.startActivity(intent) }
                    .onFailure { userMessages.tryEmit(application.getString(R.string.error_launch_failed)) }
            }
            QuickActionType.ALARM -> {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { application.startActivity(intent) }
                    .recoverCatching {
                        application.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    .onFailure { userMessages.tryEmit(application.getString(R.string.error_launch_failed)) }
            }
            QuickActionType.ASSISTANT -> {
                val intent = Intent(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { application.startActivity(intent) }
                    .onFailure { userMessages.tryEmit(application.getString(R.string.error_launch_failed)) }
            }
            QuickActionType.APP -> {
                if (config.componentKey.isNotBlank()) {
                    val app = allInstalledApps().firstOrNull { it.componentKey == config.componentKey }
                    if (app != null) {
                        appLauncher(app)
                    } else {
                        val component = ComponentName.unflattenFromString(
                            config.componentKey.substringBeforeLast(AppInfo.PROFILE_KEY_SEPARATOR),
                        )
                        if (component != null) {
                            val intent = Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_LAUNCHER)
                                .setComponent(component)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            runCatching { application.startActivity(intent) }
                                .onFailure { userMessages.tryEmit(application.getString(R.string.error_launch_failed)) }
                        } else {
                            openQuickActionPicker()
                        }
                    }
                } else {
                    openQuickActionPicker()
                }
            }
        }
    }

    fun dispose() {
        runCatching {
            cameraManager?.unregisterTorchCallback(torchCallback)
            Logger.i(TAG) { "Unregistered torch callback" }
        }
        if (_isFlashlightOn.value) {
            val id = torchCameraId
            if (id != null) {
                runCatching { cameraManager?.setTorchMode(id, false) }
            }
            _isFlashlightOn.value = false
        }
    }

    private companion object {
        private const val TAG = "QuickActionManager"
    }
}
