package com.ff.launcher.widget.providers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.ff.launcher.util.Logger
import android.widget.RemoteViews
import com.ff.launcher.R

/**
 * Standard Android AppWidgetProvider for FF Launcher Clock Widget.
 * Shows time and date using [android.widget.TextClock] for zero-drain real-time updates.
 * Tapping opens the system clock/alarm app.
 */
class ClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Logger.i("ClockWidgetProvider") { "onUpdate called for ${appWidgetIds.size} widgets" }
        for (appWidgetId in appWidgetIds) {
            updateClockWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateClockWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_clock)

            val alarmIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val launchIntent = if (alarmIntent.resolveActivity(context.packageManager) != null) {
                alarmIntent
            } else {
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_clock_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, ClockWidgetProvider::class.java)
            )
            for (id in ids) {
                updateClockWidget(context, appWidgetManager, id)
            }
        }
    }
}
