package com.newfuel.fuelalert.backend

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/** Same real-in-JVM-HTTP-server pattern as PriceFetcherTest.kt - runs
  * Geocoder's actual HTTP + JSON-array parsing code, not a mocked-out
  * version of it. */
class GeocoderTest {

    private lateinit var server: HttpServer
    private lateinit var geocoder: Geocoder

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/search") { exchange ->
            val query = exchange.requestURI.query.orEmpty()
            val body = when {
                query.contains("q=nowhere") -> "[]".toByteArray(StandardCharsets.UTF_8)
                query.contains("q=broken") -> "{not valid json array".toByteArray(StandardCharsets.UTF_8)
                else -> """[{"lat":"-33.8568","lon":"151.2153","display_name":"Sydney Opera House"}]"""
                    .toByteArray(StandardCharsets.UTF_8)
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        geocoder = Geocoder(baseUrl = "http://127.0.0.1:${server.address.port}/search")
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `geocode parses the first result's lat lon from a real JSON array response`() = runBlocking {
        val result = geocoder.geocode("Sydney Opera House")
        val location = (result as BackendResult.Success).value
        assertEquals(-33.8568, location.lat, 0.0001)
        assertEquals(151.2153, location.lon, 0.0001)
    }

    @Test
    fun `geocode reports failure for an empty result array, not a crash`() = runBlocking {
        val result = geocoder.geocode("nowhere")
        assertTrue(result is BackendResult.Failure)
    }

    @Test
    fun `geocode reports failure for a malformed response, not a crash`() = runBlocking {
        val result = geocoder.geocode("broken")
        assertTrue(result is BackendResult.Failure)
    }

    @Test
    fun `geocode reports failure for an unreachable server, not a crash`() = runBlocking {
        val badGeocoder = Geocoder(baseUrl = "http://127.0.0.1:1/search")
        val result = badGeocoder.geocode("anything")
        assertTrue(result is BackendResult.Failure)
    }
}
