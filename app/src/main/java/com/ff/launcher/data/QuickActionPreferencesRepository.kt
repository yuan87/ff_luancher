package com.ff.launcher.data

import android.content.Context
import com.ff.launcher.util.Logger
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class QuickActionType {
    APP,
    CAMERA,
    ALARM,
    FLASHLIGHT,
    ASSISTANT,
    SECOND_SCREEN,
}

/**
 * Live configuration of the quick action floating button.
 */
data class QuickActionConfig(
    val enabled: Boolean = true,
    val actionType: QuickActionType = QuickActionType.CAMERA,
    val componentKey: String = "",
    val customLabel: String = "",
    val edgeSide: TriggerEdgeSide = TriggerEdgeSide.LEFT,
    val verticalBias: Float = 0.82f,
    val sizeDp: Int = 48,
    val opacity: Float = 0.35f,
)

private val Context.quickActionDataStore by preferencesDataStore(name = "quick_action_prefs")

class QuickActionPreferencesRepository(context: Context) {
    private val dataStore = context.applicationContext.quickActionDataStore

    val config: Flow<QuickActionConfig> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            QuickActionConfig(
                enabled = prefs[KEY_ENABLED] ?: DEFAULT.enabled,
                actionType = prefs[KEY_ACTION_TYPE].toEnum(DEFAULT.actionType),
                componentKey = prefs[KEY_COMPONENT_KEY] ?: DEFAULT.componentKey,
                customLabel = prefs[KEY_CUSTOM_LABEL] ?: DEFAULT.customLabel,
                edgeSide = prefs[KEY_EDGE_SIDE].toEnum(DEFAULT.edgeSide),
                verticalBias = (prefs[KEY_VERTICAL_BIAS] ?: DEFAULT.verticalBias).coerceIn(VERTICAL_BIAS_RANGE),
                sizeDp = (prefs[KEY_SIZE_DP] ?: DEFAULT.sizeDp).coerceIn(SIZE_RANGE),
                opacity = (prefs[KEY_OPACITY] ?: DEFAULT.opacity).coerceIn(OPACITY_RANGE),
            )
        }

    suspend fun setEnabled(enabled: Boolean) {
        Logger.i(TAG) { "setEnabled: $enabled" }
        dataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setAction(type: QuickActionType, componentKey: String = "", label: String = "") {
        Logger.i(TAG) { "setAction: type=$type, componentKey=$componentKey, label=$label" }
        dataStore.edit {
            it[KEY_ACTION_TYPE] = type.name
            it[KEY_COMPONENT_KEY] = componentKey
            it[KEY_CUSTOM_LABEL] = label
        }
    }

    suspend fun setEdgeSide(side: TriggerEdgeSide) {
        Logger.i(TAG) { "setEdgeSide: $side" }
        dataStore.edit { it[KEY_EDGE_SIDE] = side.name }
    }

    suspend fun setVerticalBias(bias: Float) {
        val clamped = bias.coerceIn(VERTICAL_BIAS_RANGE)
        Logger.i(TAG) { "setVerticalBias: $clamped" }
        dataStore.edit {
            it[KEY_VERTICAL_BIAS] = clamped
        }
    }

    suspend fun setSizeDp(sizeDp: Int) {
        val clamped = sizeDp.coerceIn(SIZE_RANGE)
        Logger.i(TAG) { "setSizeDp: $clamped" }
        dataStore.edit {
            it[KEY_SIZE_DP] = clamped
        }
    }

    suspend fun setOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(OPACITY_RANGE)
        Logger.i(TAG) { "setOpacity: $clamped" }
        dataStore.edit {
            it[KEY_OPACITY] = clamped
        }
    }

    suspend fun resetToDefaults() {
        Logger.i(TAG) { "resetToDefaults called" }
        dataStore.edit { prefs ->
            prefs.remove(KEY_ENABLED)
            prefs.remove(KEY_ACTION_TYPE)
            prefs.remove(KEY_COMPONENT_KEY)
            prefs.remove(KEY_CUSTOM_LABEL)
            prefs.remove(KEY_EDGE_SIDE)
            prefs.remove(KEY_VERTICAL_BIAS)
            prefs.remove(KEY_SIZE_DP)
            prefs.remove(KEY_OPACITY)
        }
    }

    companion object {
        private const val TAG = "FF/QuickActionPrefs"
        val VERTICAL_BIAS_RANGE = 0.08f..0.92f
        val SIZE_RANGE = 38..60
        val SIZE_RANGE_F = SIZE_RANGE.first.toFloat()..SIZE_RANGE.last.toFloat()
        val OPACITY_RANGE = 0.15f..1.0f

        private val DEFAULT = QuickActionConfig()
        private val KEY_ENABLED = booleanPreferencesKey("quick_action_enabled")
        private val KEY_ACTION_TYPE = stringPreferencesKey("quick_action_type")
        private val KEY_COMPONENT_KEY = stringPreferencesKey("quick_action_component_key")
        private val KEY_CUSTOM_LABEL = stringPreferencesKey("quick_action_custom_label")
        private val KEY_EDGE_SIDE = stringPreferencesKey("quick_action_edge_side")
        private val KEY_VERTICAL_BIAS = floatPreferencesKey("quick_action_vertical_bias")
        private val KEY_SIZE_DP = intPreferencesKey("quick_action_size_dp")
        private val KEY_OPACITY = floatPreferencesKey("quick_action_opacity")

        private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
            this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
    }
}
