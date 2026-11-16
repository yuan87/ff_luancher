package com.ff.launcher.widget.providers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ff.launcher.util.Logger
import android.view.View
import android.widget.RemoteViews
import com.ff.launcher.R
import com.ff.launcher.data.glance.HeaderPreferencesRepository
import com.ff.launcher.data.glance.WeatherData
import com.ff.launcher.data.glance.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Standard Android AppWidgetProvider for FF Launcher Weather Widget.
 * Shows temperature, condition emoji, and condition text using Open-Meteo data.
 * Tapping opens the web weather search.
 */
class WeatherWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Logger.i("WeatherWidgetProvider") { "onUpdate called for ${appWidgetIds.size} widgets" }
        val pendingResult = goAsync()
        scope.launch {
            try {
                val weatherRepo = WeatherRepository(context)
                val headerPrefs = HeaderPreferencesRepository(context)
                val useFahrenheit = headerPrefs.headerConfig.first().useFahrenheit
                val data = weatherRepo.getOrFetchWeather(forceRefresh = false)

                for (appWidgetId in appWidgetIds) {
                    updateWeatherWidget(context, appWidgetManager, appWidgetId, data, useFahrenheit)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Logger.i("WeatherWidgetProvider") { "onReceive action=${intent.action}" }
        if (intent.action == ACTION_REFRESH_WEATHER) {
            val pendingResult = goAsync()
            scope.launch {
                try {
                    val weatherRepo = WeatherRepository(context)
                    val headerPrefs = HeaderPreferencesRepository(context)
                    val useFahrenheit = headerPrefs.headerConfig.first().useFahrenheit
                    val data = weatherRepo.getOrFetchWeather(forceRefresh = true)
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val ids = appWidgetManager.getAppWidgetIds(
                        ComponentName(context, WeatherWidgetProvider::class.java)
                    )
                    for (id in ids) {
                        updateWeatherWidget(context, appWidgetManager, id, data, useFahrenheit)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_WEATHER = "com.ff.launcher.action.REFRESH_WEATHER"

        fun updateWeatherWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            data: WeatherData,
            useFahrenheit: Boolean,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)

            views.setTextViewText(R.id.widget_weather_icon, data.conditionEmoji())
            views.setTextViewText(R.id.widget_weather_temp, data.formatTemp(useFahrenheit))
            views.setTextViewText(R.id.widget_weather_condition, data.conditionText)

            val high = if (useFahrenheit) (data.highC * 9f / 5f + 32f).roundToInt() else data.highC.roundToInt()
            val low = if (useFahrenheit) (data.lowC * 9f / 5f + 32f).roundToInt() else data.lowC.roundToInt()
            val unit = if (useFahrenheit) "°F" else "°C"
            views.setTextViewText(R.id.widget_weather_range, "H: $high$unit   L: $low$unit")
            views.setViewVisibility(R.id.widget_weather_range, View.VISIBLE)

            // Tap action: open weather web search
            val weatherIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                weatherIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_weather_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, WeatherWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                val weatherRepo = WeatherRepository(context)
                val headerPrefs = HeaderPreferencesRepository(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val useFahrenheit = headerPrefs.headerConfig.first().useFahrenheit
                    val data = weatherRepo.getOrFetchWeather(forceRefresh = false)
                    for (id in ids) {
                        updateWeatherWidget(context, appWidgetManager, id, data, useFahrenheit)
                    }
                }
            }
        }
    }
}
