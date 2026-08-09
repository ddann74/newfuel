package com.newfuel.fuelalert.backend

import com.newfuel.fuelalert.route.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class StationPrice(
    val code: String,
    val name: String,
    val brand: String,
    val address: String,
    val location: LatLon,
    /** Dollars per litre - already divided down from the raw API's
      * cents-like price field (see parseStationsResponse), matching
      * static/app.js's parseStations() convention. */
    val pricePerLitre: Double,
)

data class RouteResult(
    val distanceKm: Double,
    val durationMinutes: Double,
    val points: List<LatLon>,
)

sealed class BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>()
    data class Failure(val message: String) : BackendResult<Nothing>()
}

/**
 * Talks to the backend proxy contract (proxy_server.py at the repo
 * root / tools/mock_backend.py for dev - see PRD.md ss6) rather than
 * the NSW FuelCheck / TomTom APIs directly, so this app never embeds
 * those API keys - same reasoning as the fix already applied to the
 * web app's static/app.js.
 *
 * Deliberately built on java.net.HttpURLConnection + org.json instead
 * of a networking library (OkHttp/Retrofit): both are already part of
 * the Android platform (present in the real android-all stub jar this
 * sandbox can compile against), so this file - unlike anything
 * depending on androidx.* - is fully compile-verifiable here without
 * adding a dependency whose own reachability would need separate
 * confirmation.
 */
class PriceFetcher(private val backendBaseUrl: String) {

    suspend fun fetchRoute(
        origin: LatLon,
        destination: LatLon,
        routeType: String,
    ): BackendResult<RouteResult> = withContext(Dispatchers.IO) {
        val url = "$backendBaseUrl/proxy/tomtom-route" +
            "?olat=${origin.lat}&olon=${origin.lon}" +
            "&dlat=${destination.lat}&dlon=${destination.lon}" +
            "&routeType=${URLEncoder.encode(routeType, "UTF-8")}"
        try {
            val json = httpGet(url)
            BackendResult.Success(parseRouteResponse(json))
        } catch (e: IOException) {
            BackendResult.Failure("Route fetch failed: ${e.message}")
        } catch (e: org.json.JSONException) {
            BackendResult.Failure("Route response malformed: ${e.message}")
        }
    }

    suspend fun fetchStations(
        position: LatLon,
        radiusKm: Double,
        fuelType: String,
    ): BackendResult<List<StationPrice>> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("fuelType", fuelType)
            .put("lat", position.lat)
            .put("lon", position.lon)
            .put("radius", radiusKm)
            .toString()
        try {
            val json = httpPost("$backendBaseUrl/proxy/nsw-stations", body)
            BackendResult.Success(parseStationsResponse(json))
        } catch (e: IOException) {
            BackendResult.Failure("Station fetch failed: ${e.message}")
        } catch (e: org.json.JSONException) {
            BackendResult.Failure("Station response malformed: ${e.message}")
        }
    }

    private fun httpGet(url: String): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        return readJsonResponse(connection)
    }

    private fun httpPost(url: String, body: String): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        return readJsonResponse(connection)
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        if (status !in 200..299) {
            throw IOException("HTTP $status: $text")
        }
        return JSONObject(text)
    }

    /** Matches the shape both proxy_server.py (real NSW/TomTom passthrough)
      * and tools/mock_backend.py return - see either file's docstring. */
    private fun parseRouteResponse(json: JSONObject): RouteResult {
        val route = json.getJSONArray("routes").getJSONObject(0)
        val summary = route.getJSONObject("summary")
        val distanceKm = summary.getDouble("lengthInMeters") / 1000.0
        val durationMinutes = summary.getDouble("travelTimeInSeconds") / 60.0

        val points = mutableListOf<LatLon>()
        val legs = route.getJSONArray("legs")
        for (i in 0 until legs.length()) {
            val legPoints: JSONArray = legs.getJSONObject(i).getJSONArray("points")
            for (j in 0 until legPoints.length()) {
                val p = legPoints.getJSONObject(j)
                points.add(LatLon(p.getDouble("latitude"), p.getDouble("longitude")))
            }
        }
        return RouteResult(distanceKm, durationMinutes, points)
    }

    /** Mirrors static/app.js's parseStations(): prices arrive as a
      * cents-like value (divided by 100 to get dollars/litre), and a
      * station missing a price or coordinates is dropped rather than
      * guessed at - a station that can't be ranked by distance or price
      * shouldn't silently pass through as if it could be. */
    private fun parseStationsResponse(json: JSONObject): List<StationPrice> {
        val priceByCode = mutableMapOf<String, Double>()
        val prices = json.optJSONArray("prices") ?: JSONArray()
        for (i in 0 until prices.length()) {
            val p = prices.getJSONObject(i)
            if (!p.isNull("price")) {
                priceByCode[p.getString("stationcode")] = p.getDouble("price") / 100.0
            }
        }

        val result = mutableListOf<StationPrice>()
        val stations = json.optJSONArray("stations") ?: JSONArray()
        for (i in 0 until stations.length()) {
            val s = stations.getJSONObject(i)
            val code = s.getString("code")
            val price = priceByCode[code] ?: continue
            val location = s.optJSONObject("location") ?: continue
            if (location.isNull("latitude") || location.isNull("longitude")) continue
            result.add(
                StationPrice(
                    code = code,
                    name = s.optString("name", "Unknown"),
                    brand = s.optString("brand", ""),
                    address = s.optString("address", ""),
                    location = LatLon(location.getDouble("latitude"), location.getDouble("longitude")),
                    pricePerLitre = price,
                )
            )
        }
        return result
    }
}
