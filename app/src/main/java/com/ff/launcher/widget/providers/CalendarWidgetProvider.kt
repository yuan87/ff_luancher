package com.ff.launcher.widget.providers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.ff.launcher.util.Logger
import android.view.View
import android.widget.RemoteViews
import com.ff.launcher.R
import com.ff.launcher.data.glance.CalendarProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standard Android AppWidgetProvider for FF Launcher Calendar Widget.
 * Shows the current day, month, weekday, and the next upcoming event.
 * Tapping opens the system calendar.
 */
class CalendarWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Logger.i("CalendarWidgetProvider") { "onUpdate called for ${appWidgetIds.size} widgets" }
        val pendingResult = goAsync()
        scope.launch {
            try {
                val calendarProvider = CalendarProvider(context)
                val state = calendarProvider.loadUpcomingEvents(24)
                for (appWidgetId in appWidgetIds) {
                    updateCalendarWidget(context, appWidgetManager, appWidgetId, state)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        fun updateCalendarWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: com.ff.launcher.data.glance.CalendarState,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_calendar)
            val now = Date()

            val dayFormat = SimpleDateFormat("d", Locale.getDefault())
            val monthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
            val weekdayFormat = SimpleDateFormat("EEEE", Locale.getDefault())

            views.setTextViewText(R.id.widget_calendar_day, dayFormat.format(now))
            views.setTextViewText(R.id.widget_calendar_month, monthFormat.format(now).uppercase(Locale.getDefault()))
            views.setTextViewText(R.id.widget_calendar_weekday, weekdayFormat.format(now))

            val next = state.nextEvent
            if (next != null) {
                views.setTextViewText(R.id.widget_calendar_event_title, next.title)
                views.setTextViewText(R.id.widget_calendar_event_time, next.formatEventTime())
                views.setViewVisibility(R.id.widget_calendar_event_time, View.VISIBLE)
            } else {
                views.setTextViewText(
                    R.id.widget_calendar_event_title,
                    context.getString(R.string.widget_no_events),
                )
                views.setViewVisibility(R.id.widget_calendar_event_time, View.GONE)
            }

            // Launch calendar on click
            val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            ContentUris.appendId(builder, System.currentTimeMillis())
            val calendarIntent = Intent(Intent.ACTION_VIEW).apply {
                data = builder.build()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                calendarIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_calendar_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, CalendarWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                val calendarProvider = CalendarProvider(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val state = calendarProvider.loadUpcomingEvents(24)
                    for (id in ids) {
                        updateCalendarWidget(context, appWidgetManager, id, state)
                    }
                }
            }
        }
    }
}
