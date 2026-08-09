package com.newfuel.fuelalert.backend

import com.newfuel.fuelalert.route.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Turns a free-text destination (PRD.md ss5.3: "User enters a
 * destination in-app") into coordinates, via Nominatim - the same
 * public geocoding service static/app.js's geocode() already uses, and
 * for the same reason PriceFetcher doesn't proxy this call: Nominatim
 * needs no API key, so there's no secret to keep out of the client -
 * only its usage policy (a real, identifying User-Agent header, no
 * secret involved) to follow, matching the web app's existing
 * 'FuelOptimizer/2.0' header exactly so both apps present as the same
 * client to Nominatim.
 *
 * [baseUrl] defaults to the real Nominatim endpoint but is overridable
 * specifically so GeocoderTest.kt can point it at a fake in-JVM server
 * instead - same reason PriceFetcher's backendBaseUrl is a constructor
 * parameter rather than a hardcoded constant.
 */
class Geocoder(private val baseUrl: String = "https://nominatim.openstreetmap.org/search") {

    suspend fun geocode(query: String): BackendResult<LatLon> = withContext(Dispatchers.IO) {
        val url = "$baseUrl?q=${URLEncoder.encode(query, "UTF-8")}&format=json&limit=1&countrycodes=au"
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "FuelOptimizer/2.0")

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (status !in 200..299) return@withContext BackendResult.Failure("Geocoding failed: HTTP $status")

            val results: JSONArray = org.json.JSONTokener(text).nextValue() as? JSONArray
                ?: return@withContext BackendResult.Failure("Geocoding response malformed")
            if (results.length() == 0) return@withContext BackendResult.Failure("Could not find: $query")

            val first = results.getJSONObject(0)
            BackendResult.Success(LatLon(first.getString("lat").toDouble(), first.getString("lon").toDouble()))
        } catch (e: IOException) {
            BackendResult.Failure("Geocoding failed: ${e.message}")
        } catch (e: org.json.JSONException) {
            BackendResult.Failure("Geocoding response malformed: ${e.message}")
        } catch (e: NumberFormatException) {
            BackendResult.Failure("Geocoding response malformed: ${e.message}")
        }
    }
}
