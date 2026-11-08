package fr.ayoub.cliolauncher.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** Vitesse lue sur le GPS de l'autoradio (fonctionne sans boitier CAN). */
object SpeedProvider {
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (loc.hasSpeed()) {
                VehicleState.speedKmh = (loc.speed * 3.6f).roundToInt()
                VehicleState.speedTime = System.currentTimeMillis()
            }
            WeatherRepository.onLocation(loc)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (started || !Permissions.hasLocation(ctx)) return
        val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
            started = true
        } catch (e: Exception) {
            // pas de GPS : la vitesse restera "--"
        }
    }
}

data class Weather(val tempC: Int, val code: Int, val isDay: Boolean)

/** Meteo via Open-Meteo (gratuit, sans compte). Necessite internet sur l'autoradio. */
object WeatherRepository {
    var weather by mutableStateOf<Weather?>(null)
    var status by mutableStateOf("Météo en attente du GPS")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastFetch = 0L
    private var lastLoc: Pair<Double, Double>? = null
    private var loading = false

    private fun stale() = System.currentTimeMillis() - lastFetch > 20 * 60_000L

    fun onLocation(loc: Location) {
        lastLoc = loc.latitude to loc.longitude
        if (stale()) fetch()
    }

    @SuppressLint("MissingPermission")
    fun refreshIfStale(ctx: Context) {
        if (lastLoc == null && Permissions.hasLocation(ctx)) {
            val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (p in lm.getProviders(true)) {
                val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null }
                if (l != null) { lastLoc = l.latitude to l.longitude; break }
            }
        }
        if (stale()) fetch()
    }

    private fun fetch() {
        val loc = lastLoc
        if (loc == null) { status = "Météo en attente du GPS"; return }
        if (loading) return
        loading = true
        scope.launch {
            try {
                weather = withContext(Dispatchers.IO) { download(loc.first, loc.second) }
                lastFetch = System.currentTimeMillis()
            } catch (e: Exception) {
                // nouvel essai dans ~2 minutes
                lastFetch = System.currentTimeMillis() - 18 * 60_000L
                if (weather == null) status = "Météo indisponible (internet ?)"
            } finally {
                loading = false
            }
        }
    }

    private fun download(lat: Double, lon: Double): Weather {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day&timezone=auto",
                lat, lon,
            )
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val cur = JSONObject(body).getJSONObject("current")
            return Weather(
                tempC = cur.getDouble("temperature_2m").roundToInt(),
                code = cur.getInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
            )
        } finally {
            conn.disconnect()
        }
    }
}

/** Codes meteo WMO -> texte francais. */
fun weatherLabel(code: Int): String = when (code) {
    0 -> "Ciel dégagé"
    1 -> "Peu nuageux"
    2 -> "Éclaircies"
    3 -> "Couvert"
    45, 48 -> "Brouillard"
    in 51..57 -> "Bruine"
    in 61..67 -> "Pluie"
    in 71..77 -> "Neige"
    in 80..82 -> "Averses"
    85, 86 -> "Averses de neige"
    in 95..99 -> "Orage"
    else -> "--"
}

fun weatherIcon(code: Int, isDay: Boolean): ImageVector = when (code) {
    0, 1 -> if (isDay) Icons.Filled.WbSunny else Icons.Filled.DarkMode
    2 -> Icons.Filled.WbCloudy
    3, 45, 48 -> Icons.Filled.Cloud
    in 71..77, 85, 86 -> Icons.Filled.AcUnit
    in 95..99 -> Icons.Filled.FlashOn
    in 51..67, in 80..82 -> Icons.Filled.Umbrella
    else -> Icons.Filled.Cloud
}
