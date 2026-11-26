package com.androidwatch.launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** One hour of forecast. [time] is the start of the hour (epoch millis). */
data class HourForecast(
    val time: Long,
    val temperature: Int,
    /** 0..100 */
    val precipChance: Int,
    /** mm of water in this hour (rain + melted snow). */
    val precipMm: Double,
    /** cm of snow in this hour. */
    val snowCm: Double,
    val code: Int,
) {
    val isSnow get() = snowCm > 0.0 || code in SNOW_CODES
    /** Counts as "wet" for the summary and the ring. */
    val wet get() = precipChance >= 40 || precipMm >= 0.2
}

/** 15 minutes of near-term forecast, for "rain in 20 min". */
data class QuarterForecast(val time: Long, val precipMm: Double, val snowCm: Double)

data class Forecast(
    val hours: List<HourForecast>,
    val quarters: List<QuarterForecast>,
    val high: Int,
    val low: Int,
    val unit: String,
    val fetchedAt: Long,
)

private val SNOW_CODES = setOf(71, 73, 75, 77, 85, 86)

/** Short plain-language answer to "when will it rain or snow?" */
object PrecipSummary {

    fun summarize(f: Forecast, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        fun at(t: Long) = timeFmt.format(Date(t)).replace(":00", "")

        // Next 2 hours, 15-minute steps: most precise for "starting soon".
        val soon = f.quarters.filter { it.time + 15 * 60_000 > now }
        val wetNow = soon.firstOrNull()?.let { it.precipMm >= 0.1 } ?: false
        val hours = f.hours.filter { it.time + 3_600_000 > now }

        if (wetNow || hours.firstOrNull()?.let { it.precipMm >= 0.2 } == true) {
            val snow = soon.firstOrNull()?.snowCm?.let { it > 0 } ?: hours.firstOrNull()?.isSnow ?: false
            val what = if (snow) "🌨 Snowing" else "🌧 Raining"
            val stops = hours.drop(1).firstOrNull { !it.wet }
            return if (stops != null) "$what, stops ~${at(stops.time)}" else "$what for a while"
        }

        soon.firstOrNull { it.precipMm >= 0.1 }?.let { q ->
            val mins = ((q.time - now) / 60_000).coerceAtLeast(5)
            val what = if (q.snowCm > 0) "🌨 Snow" else "🌧 Rain"
            return "$what in ~$mins min"
        }

        hours.firstOrNull { it.wet }?.let { h ->
            val what = if (h.isSnow) "🌨 Snow" else "🌧 Rain"
            return "$what from ${at(h.time)} (${h.precipChance}%)"
        }
        val span = hours.size.coerceAtMost(12)
        return if (span > 0) "☀ Dry next $span h" else ""
    }
}

object ForecastClient {

    /**
     * Next 12 hours (hourly), next 2 hours (15-minute), and today's high/low,
     * from Open-Meteo. Separate from the current-weather call, so if this one
     * fails the face still shows the temperature. Blocking: run off the main thread.
     */
    fun fetch(lat: Double, lon: Double, fahrenheit: Boolean): Forecast {
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                    "&hourly=temperature_2m,precipitation_probability,precipitation,snowfall,weather_code" +
                    "&minutely_15=precipitation,snowfall" +
                    "&daily=temperature_2m_max,temperature_2m_min" +
                    "&forecast_hours=13&forecast_minutely_15=9&forecast_days=1" +
                    "&timezone=auto&timeformat=unixtime&temperature_unit=%s",
                lat, lon, unit,
            ),
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            return parse(conn.inputStream.bufferedReader().readText(), if (fahrenheit) "°F" else "°C")
        } finally {
            conn.disconnect()
        }
    }

    /** Parses Open-Meteo's response (timeformat=unixtime). Missing parts become empty, not errors. */
    fun parse(body: String, unit: String, now: Long = System.currentTimeMillis()): Forecast {
        val json = JSONObject(body)
        val hours = json.optJSONObject("hourly")?.let { h ->
            val t = h.getJSONArray("time")
            (0 until t.length()).map { i ->
                HourForecast(
                    time = t.getLong(i) * 1000,
                    temperature = Math.round(h.optJSONArray("temperature_2m")?.optDouble(i, 0.0) ?: 0.0).toInt(),
                    precipChance = h.optJSONArray("precipitation_probability")?.optInt(i, 0) ?: 0,
                    precipMm = h.optJSONArray("precipitation")?.optDouble(i, 0.0)?.takeUnless { it.isNaN() } ?: 0.0,
                    snowCm = h.optJSONArray("snowfall")?.optDouble(i, 0.0)?.takeUnless { it.isNaN() } ?: 0.0,
                    code = h.optJSONArray("weather_code")?.optInt(i, 0) ?: 0,
                )
            }
        }.orEmpty()
        val quarters = json.optJSONObject("minutely_15")?.let { m ->
            val t = m.getJSONArray("time")
            (0 until t.length()).map { i ->
                QuarterForecast(
                    time = t.getLong(i) * 1000,
                    precipMm = m.optJSONArray("precipitation")?.optDouble(i, 0.0)?.takeUnless { it.isNaN() } ?: 0.0,
                    snowCm = m.optJSONArray("snowfall")?.optDouble(i, 0.0)?.takeUnless { it.isNaN() } ?: 0.0,
                )
            }
        }.orEmpty()
        val daily = json.optJSONObject("daily")
        val high = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeUnless { it.isNaN() }
        val low = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeUnless { it.isNaN() }
        return Forecast(
            hours = hours,
            quarters = quarters,
            high = Math.round(high ?: hours.maxOfOrNull { it.temperature.toDouble() } ?: 0.0).toInt(),
            low = Math.round(low ?: hours.minOfOrNull { it.temperature.toDouble() } ?: 0.0).toInt(),
            unit = unit,
            fetchedAt = now,
        )
    }

    fun icon(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }
}
