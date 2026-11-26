package com.androidwatch.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ForecastTest {
    init { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    private val hour = 3_600_000L
    // 2026-11-26 12:00 UTC
    private val noon = 1_795_694_400_000L

    private fun hours(vararg chancesAndMm: Pair<Int, Double>, snowFrom: Int = 99) =
        chancesAndMm.mapIndexed { i, (chance, mm) ->
            HourForecast(noon + i * hour, 10, chance, mm, if (i >= snowFrom && mm > 0) 1.0 else 0.0, if (mm > 0) 61 else 3)
        }

    private fun forecast(h: List<HourForecast>, q: List<QuarterForecast> = emptyList()) =
        Forecast(h, q, 12, 4, "°C", noon)

    // Newer Java formats "3 PM" with a narrow no-break space; compare with a plain one.
    private fun summary(f: Forecast) =
        PrecipSummary.summarize(f, now = noon + 60_000, locale = Locale.US).replace("\u202F", " ")

    @Test fun dry() = assertEquals("☀ Dry next 12 h", summary(forecast(hours(*Array(13) { 0 to 0.0 }))))

    @Test fun rainLater() {
        val h = hours(0 to 0.0, 10 to 0.0, 20 to 0.0, 70 to 1.2, 80 to 2.0)
        assertEquals("🌧 Rain from 3 PM (70%)", summary(forecast(h)))
    }

    @Test fun snowLater() {
        val h = hours(0 to 0.0, 0 to 0.0, 60 to 0.5, snowFrom = 2)
        assertEquals("🌨 Snow from 2 PM (60%)", summary(forecast(h)))
    }

    @Test fun rainSoonFromQuarters() {
        val q = listOf(
            QuarterForecast(noon, 0.0, 0.0),
            QuarterForecast(noon + 15 * 60_000, 0.0, 0.0),
            QuarterForecast(noon + 30 * 60_000, 0.4, 0.0),
        )
        assertEquals("🌧 Rain in ~29 min", summary(forecast(hours(0 to 0.0, 50 to 0.6), q)))
    }

    @Test fun rainingNowAndStops() {
        val q = listOf(QuarterForecast(noon, 0.8, 0.0))
        val h = hours(90 to 2.0, 80 to 1.0, 10 to 0.0)
        assertEquals("🌧 Raining, stops ~2 PM", summary(forecast(h, q)))
    }

    @Test fun parsesOpenMeteoUnixtimeResponse() {
        val t0 = noon / 1000
        val body = """
            {"hourly":{"time":[$t0,${t0 + 3600}],"temperature_2m":[10.4,9.6],
              "precipitation_probability":[5,65],"precipitation":[0.0,1.3],"snowfall":[0.0,0.0],"weather_code":[3,61]},
             "minutely_15":{"time":[$t0,${t0 + 900}],"precipitation":[0.0,0.2],"snowfall":[0.0,0.0]},
             "daily":{"time":[$t0],"temperature_2m_max":[12.2],"temperature_2m_min":[3.7]}}
        """.trimIndent()
        val f = ForecastClient.parse(body, "°C", now = noon)
        assertEquals(2, f.hours.size)
        assertEquals(65, f.hours[1].precipChance)
        assertEquals(noon + hour, f.hours[1].time)
        assertEquals(12, f.high)
        assertEquals(4, f.low)
        assertTrue(f.hours[1].wet)
        assertEquals(0.2, f.quarters[1].precipMm, 0.001)
    }

    @Test fun parsesNullsWithoutCrashing() {
        val t0 = noon / 1000
        val body = """{"hourly":{"time":[$t0],"temperature_2m":[null],"precipitation_probability":[null],"precipitation":[null],"snowfall":[null],"weather_code":[null]}}"""
        val f = ForecastClient.parse(body, "°F", now = noon)
        assertEquals(1, f.hours.size)
        assertEquals(0, f.hours[0].precipChance)
    }
}
