package com.ff.launcher.data.glance

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import com.ff.launcher.util.Logger
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

data class CalendarEvent(
    val id: Long,
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val isAllDay: Boolean,
    val location: String?,
) {
    fun formatEventTime(): String {
        if (isAllDay) return "All day"
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        return timeFormat.format(startEpochMs)
    }
}

data class CalendarState(
    val hasPermission: Boolean = false,
    val upcomingEvents: List<CalendarEvent> = emptyList(),
    val nextEvent: CalendarEvent? = null,
)

/**
 * Loads upcoming calendar events from the Android [CalendarContract].
 */
class CalendarProvider(private val context: Context) {

    fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun loadUpcomingEvents(hoursAhead: Int = 24): CalendarState = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) {
            return@withContext CalendarState(hasPermission = false)
        }

        val events = mutableListOf<CalendarEvent>()
        val now = System.currentTimeMillis()
        val endRange = now + (hoursAhead * 60 * 60 * 1000L)

        val builder: Uri.Builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now)
        ContentUris.appendId(builder, endRange)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )

        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

        runCatching {
            val cursor: Cursor? = context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                sortOrder,
            )
            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val locIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)

                while (it.moveToNext() && events.size < 5) {
                    val id = it.getLong(idIdx)
                    val title = it.getString(titleIdx) ?: "Untitled event"
                    val begin = it.getLong(beginIdx)
                    val end = it.getLong(endIdx)
                    val allDay = it.getInt(allDayIdx) == 1
                    val location = it.getString(locIdx)

                    events.add(
                        CalendarEvent(
                            id = id,
                            title = title,
                            startEpochMs = begin,
                            endEpochMs = end,
                            isAllDay = allDay,
                            location = location,
                        ),
                    )
                }
            }
        }.onFailure { Logger.w("CalendarProvider", "Failed to query calendar events", it) }

        CalendarState(
            hasPermission = true,
            upcomingEvents = events,
            nextEvent = events.firstOrNull(),
        )
    }
}
