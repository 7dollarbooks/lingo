package com.livetranslate.headphones.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchWeatherTest {
    private val report = WatchWeatherReport(
        condition = WatchWeather.SUNNY,
        currentC = -5,
        todayMaxC = 2,
        todayMinC = -8,
        upcoming = listOf(WatchDay(WatchWeather.RAINY, 6, 1)),
        sunriseHour = 7,
        sunriseMinute = 32,
        sunsetHour = 18,
        sunsetMinute = 11,
        place = "Toronto",
    )

    @Test
    fun wmoCodesUseTheEightWatchIcons() {
        assertEquals(WatchWeather.SUNNY, WatchWeather.conditionFromWmo(0))
        assertEquals(WatchWeather.CLOUDY, WatchWeather.conditionFromWmo(2))
        assertEquals(WatchWeather.OVERCAST, WatchWeather.conditionFromWmo(3))
        assertEquals(WatchWeather.FOGGY, WatchWeather.conditionFromWmo(45))
        assertEquals(WatchWeather.RAINY, WatchWeather.conditionFromWmo(61))
        assertEquals(WatchWeather.SNOWY, WatchWeather.conditionFromWmo(71))
        assertEquals(WatchWeather.HAZE, WatchWeather.conditionFromWmo(999))
    }

    @Test
    fun todayPacketCarriesTempAndFourCharacterCity() {
        val today = WatchWeather.today(report)
        assertEquals(19, today.size)
        assertEquals(0, today[0].toInt())
        assertEquals(WatchWeather.SUNNY, today[1].toInt())
        assertEquals(-5, today[2].toInt())
        assertEquals('T'.code, ((today[11].toInt() and 0xFF) shl 8) or (today[12].toInt() and 0xFF))
        assertEquals('o'.code, ((today[17].toInt() and 0xFF) shl 8) or (today[18].toInt() and 0xFF))
    }

    @Test
    fun sunrisePacketRepeatsTheCurrentTemperature() {
        val sunrise = WatchWeather.sunrise(report)
        assertEquals(WatchWeather.SUNNY, sunrise[1].toInt())
        assertEquals(-5, sunrise[2].toInt())
        assertEquals(7, sunrise[5].toInt())
        assertEquals(32, sunrise[6].toInt())
        assertEquals(18, sunrise[7].toInt())
        assertEquals(11, sunrise[8].toInt())
    }

    @Test
    fun futurePacketIsEightDaysAndPadsTheRest() {
        val future = WatchWeather.future(report)
        assertEquals(24, future.size)
        assertEquals(WatchWeather.SUNNY, future[0].toInt())
        assertEquals(2, future[1].toInt())
        assertEquals(-8, future[2].toInt())
        assertEquals(WatchWeather.RAINY, future[3].toInt())
        assertEquals(WatchWeather.HAZE, future[6].toInt())
        assertEquals(-100, future[7].toInt())
        assertEquals(-100, future[8].toInt())
    }

    @Test
    fun openMeteoJsonBecomesAReport() {
        val json = """
            {"current":{"temperature_2m":12.6,"weather_code":61},
             "daily":{"weather_code":[61,1],"temperature_2m_max":[14.2,9.0],"temperature_2m_min":[4.4,2.0],
             "sunrise":["2026-10-27T07:32"],"sunset":["2026-10-27T18:11"]}}
        """.trimIndent()
        val parsed = WatchWeather.parseOpenMeteo(json, "Waupoos")
        assertEquals(13, parsed?.currentC)
        assertEquals(WatchWeather.RAINY, parsed?.condition)
        assertEquals(14, parsed?.todayMaxC)
        assertEquals(4, parsed?.todayMinC)
        assertEquals(1, parsed?.upcoming?.size)
        assertEquals(WatchWeather.SUNNY, parsed?.upcoming?.first()?.condition)
        assertEquals(7, parsed?.sunriseHour)
        assertEquals(32, parsed?.sunriseMinute)
        assertEquals("Waupoos", parsed?.place)
    }

    @Test
    fun nominatimPrefersTheTown() {
        val json = """{"address":{"village":"Waupoos","state":"Ontario"}}"""
        assertEquals("Waupoos", WatchWeather.parsePlace(json))
    }
}
