package com.livetranslate.headphones.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The phone clock, shared so Nitro and Gemini see the same date line. */
object DeviceClock {
    fun block(): String {
        val now = Date()
        val zone = TimeZone.getDefault()
        val dateFmt = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).apply { timeZone = zone }
        val timeFmt = SimpleDateFormat("h:mm:ss a", Locale.US).apply { timeZone = zone }
        val zoneName = zone.getDisplayName(zone.inDaylightTime(now), TimeZone.LONG, Locale.US)
        return "Device clock (authoritative — use this for any time/date/timezone question; " +
            "never invent or guess the time):\n" +
            "- Local date: ${dateFmt.format(now)}\n" +
            "- Local time: ${timeFmt.format(now)}\n" +
            "- Time zone: $zoneName (${zone.id})\n" +
            "- UTC offset: ${formatUtcOffset(zone, now)}"
    }

    fun spokenTime(): String = "It is ${format("h:mm a")}."

    fun spokenDate(): String = "Today is ${format("EEEE, MMMM d, yyyy")}."

    fun spokenDay(): String = "Today is ${format("EEEE")}."

    private fun format(pattern: String): String {
        val fmt = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getDefault() }
        return fmt.format(Date())
    }

    private fun formatUtcOffset(zone: TimeZone, now: Date): String {
        val totalMinutes = zone.getOffset(now.time) / 60_000
        val sign = if (totalMinutes >= 0) "+" else "-"
        val abs = kotlin.math.abs(totalMinutes)
        val hours = abs / 60
        val minutes = abs % 60
        return String.format(Locale.US, "UTC%s%02d:%02d", sign, hours, minutes)
    }
}
