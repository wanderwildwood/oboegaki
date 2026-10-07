package com.wanderwildwood.oboegaki.remind

import android.content.Context
import android.text.format.DateFormat
import com.wanderwildwood.oboegaki.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Times and days, written the way the phone writes them: its 12- or 24-hour clock, its language. */
object Times {

    private fun locale(context: Context): Locale = context.resources.configuration.locales[0]

    private fun clock(context: Context): DateTimeFormatter {
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale(context), skeleton), locale(context))
    }

    fun time(context: Context, at: LocalDateTime): String = clock(context).format(at)

    /** "Thu 8 Oct", or with the year when it is not this year's. */
    fun day(context: Context, date: LocalDate): String {
        val skeleton = if (date.year == LocalDate.now().year) "EEEdMMM" else "EEEdMMMyyyy"
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale(context), skeleton), locale(context)).format(date)
    }

    /** "Today", "Yesterday", "Tomorrow", or the day itself. */
    fun relativeDay(context: Context, date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> context.getString(R.string.remind_today)
            today.minusDays(1) -> context.getString(R.string.remind_yesterday)
            today.plusDays(1) -> context.getString(R.string.remind_tomorrow)
            else -> day(context, date)
        }
    }

    /** A time, with its day in front when that is not today: "17:00", "Tomorrow 09:00". */
    fun whenShort(context: Context, at: LocalDateTime): String =
        if (at.toLocalDate() == LocalDate.now()) time(context, at) else relativeDay(context, at.toLocalDate()) + " " + time(context, at)

    fun whenShort(context: Context, at: Long): String =
        whenShort(context, LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()))
}
