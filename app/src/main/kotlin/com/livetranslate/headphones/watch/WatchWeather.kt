package com.livetranslate.headphones.watch

import com.livetranslate.headphones.navigation.Json
import kotlin.math.roundToInt

/**
 * Packets the ST9 weather screen expects, the same ones DaFit writes.
 * Temperatures are Celsius. The today city is four characters. Icons are the eight the watch draws.
 */
data class WatchDay(
    val condition: Int,
    val maxC: Int,
    val minC: Int,
)

data class WatchWeatherReport(
    val condition: Int,
    val currentC: Int,
    val todayMaxC: Int,
    val todayMinC: Int,
    val upcoming: List<WatchDay>,
    val sunriseHour: Int,
    val sunriseMinute: Int,
    val sunsetHour: Int,
    val sunsetMinute: Int,
    val place: String,
)

object WatchWeather {
    const val CMD_FUTURE = 66
    const val CMD_TODAY = 67
    const val CMD_LOCATION = 69
    const val CMD_SUNRISE = 181
    const val CMD_ASK = 100

    const val CLOUDY = 0
    const val FOGGY = 1
    const val OVERCAST = 2
    const val RAINY = 3
    const val SNOWY = 4
    const val SUNNY = 5
    const val WIND = 6
    const val HAZE = 7

    fun conditionFromWmo(code: Int): Int = when (code) {
        0, 1 -> SUNNY
        2 -> CLOUDY
        3 -> OVERCAST
        45, 48 -> FOGGY
        in 51..67, in 80..82, in 95..99 -> RAINY
        in 71..77, in 85..86 -> SNOWY
        else -> HAZE
    }

    fun today(report: WatchWeatherReport): ByteArray {
        val out = ByteArray(19)
        out[0] = 0
        out[1] = report.condition.toByte()
        out[2] = tempByte(report.currentC)
        utf16be4("").copyInto(out, destinationOffset = 3)
        utf16be4(report.place).copyInto(out, destinationOffset = 11)
        return out
    }

    /** Today, then up to seven following days. Missing days are the watch's empty marker. */
    fun future(report: WatchWeatherReport): ByteArray {
        val out = ByteArray(24)
        var index = 0
        index = putDay(out, index, report.condition, report.todayMaxC, report.todayMinC)
        val days = report.upcoming.take(7)
        for (day in days) {
            index = putDay(out, index, day.condition, day.maxC, day.minC)
        }
        repeat(7 - days.size) {
            index = putDay(out, index, HAZE, EMPTY_TEMP, EMPTY_TEMP)
        }
        return out
    }

    fun sunrise(report: WatchWeatherReport): ByteArray {
        val place = report.place.toByteArray(Charsets.UTF_8)
        val out = ByteArray(9 + place.size)
        out[0] = 0
        out[1] = report.condition.toByte()
        out[2] = tempByte(report.currentC)
        out[3] = 0
        out[4] = 0
        out[5] = report.sunriseHour.coerceIn(0, 23).toByte()
        out[6] = report.sunriseMinute.coerceIn(0, 59).toByte()
        out[7] = report.sunsetHour.coerceIn(0, 23).toByte()
        out[8] = report.sunsetMinute.coerceIn(0, 59).toByte()
        place.copyInto(out, destinationOffset = 9)
        return out
    }

    fun location(hour: Int, minute: Int, place: String): ByteArray {
        val text = "%02d:%02d %s".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59), place.trim())
        return text.toByteArray(Charsets.UTF_8)
    }

    fun parseOpenMeteo(json: String, place: String): WatchWeatherReport? {
        val root = Json.parse(json) as? Map<*, *> ?: return null
        val current = root["current"] as? Map<*, *> ?: return null
        val daily = root["daily"] as? Map<*, *> ?: return null
        val currentC = (current["temperature_2m"] as? Double)?.roundToInt() ?: return null
        val currentCode = (current["weather_code"] as? Double)?.toInt() ?: return null
        val codes = ints(daily["weather_code"])
        val maxes = ints(daily["temperature_2m_max"])
        val mins = ints(daily["temperature_2m_min"])
        val sunrises = strings(daily["sunrise"])
        val sunsets = strings(daily["sunset"])
        if (codes.isEmpty() || maxes.isEmpty() || mins.isEmpty()) return null
        val rise = hourMinute(sunrises.firstOrNull().orEmpty())
        val set = hourMinute(sunsets.firstOrNull().orEmpty())
        val upcoming = (1 until minOf(codes.size, maxes.size, mins.size, 8)).map { i ->
            WatchDay(conditionFromWmo(codes[i]), maxes[i], mins[i])
        }
        return WatchWeatherReport(
            condition = conditionFromWmo(currentCode),
            currentC = currentC,
            todayMaxC = maxes[0],
            todayMinC = mins[0],
            upcoming = upcoming,
            sunriseHour = rise?.first ?: 0,
            sunriseMinute = rise?.second ?: 0,
            sunsetHour = set?.first ?: 0,
            sunsetMinute = set?.second ?: 0,
            place = place.ifBlank { "Here" },
        )
    }

    fun parsePlace(nominatimJson: String): String? {
        val root = Json.parse(nominatimJson) as? Map<*, *> ?: return null
        val address = root["address"] as? Map<*, *> ?: return null
        val name = listOf("city", "town", "village", "hamlet", "municipality")
            .firstNotNullOfOrNull { key -> (address[key] as? String)?.trim()?.ifBlank { null } }
        return name
    }

    private fun putDay(out: ByteArray, index: Int, condition: Int, maxC: Int, minC: Int): Int {
        out[index] = condition.toByte()
        out[index + 1] = tempByte(maxC)
        out[index + 2] = tempByte(minC)
        return index + 3
    }

    private fun tempByte(celsius: Int): Byte = celsius.coerceIn(-100, 100).toByte()

    private fun utf16be4(text: String): ByteArray {
        val padded = text.trim().take(4).padEnd(4, ' ')
        val out = ByteArray(8)
        padded.forEachIndexed { i, c ->
            val value = c.code
            out[i * 2] = (value shr 8).toByte()
            out[i * 2 + 1] = (value and 0xFF).toByte()
        }
        return out
    }

    private fun ints(value: Any?): List<Int> =
        (value as? List<*>)?.mapNotNull { (it as? Double)?.roundToInt() }.orEmpty()

    private fun strings(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it as? String }.orEmpty()

    private fun hourMinute(iso: String): Pair<Int, Int>? {
        val clock = iso.substringAfter('T', "").take(5)
        val parts = clock.split(':')
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return hour to minute
    }

    private const val EMPTY_TEMP = -100
}
