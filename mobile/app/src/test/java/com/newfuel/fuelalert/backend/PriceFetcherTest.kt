package com.newfuel.fuelalert.backend

import com.newfuel.fuelalert.route.LatLon
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Runs PriceFetcher's real HTTP + JSON parsing code (not a compile-check
 * substitute) against a real, in-JVM HTTP server serving fixed JSON
 * shaped exactly like tools/mock_backend.py / proxy_server.py's actual
 * responses - so this test exercises the genuine network round trip and
 * parsing logic, not a mocked-out version of PriceFetcher itself.
 *
 * Uses com.sun.net.httpserver.HttpServer (part of the standard JDK, not
 * Android's public API) - fine here because JVM unit tests under
 * src/test run on the full JDK, not restricted to Android's runtime API
 * surface the way src/main code is (that's why this file lives under
 * src/test, not src/main).
 */
class PriceFetcherTest {

    private lateinit var server: HttpServer
    private lateinit var fetcher: PriceFetcher

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        server.createContext("/proxy/tomtom-route") { exchange ->
            val body = """
                {"routes":[{"summary":{"lengthInMeters":12345.0,"travelTimeInSeconds":600.0},
                "legs":[{"points":[{"latitude":-33.86,"longitude":151.20},{"latitude":-33.87,"longitude":151.21}]}]}]}
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        server.createContext("/proxy/nsw-stations") { exchange ->
            val body = """
                {"stations":[
                  {"code":"A1","name":"Test Servo","brand":"BrandX","address":"1 Test St","location":{"latitude":-33.86,"longitude":151.20}},
                  {"code":"A2","name":"No Price Servo","brand":"BrandY","address":"2 Test St","location":{"latitude":-33.87,"longitude":151.21}},
                  {"code":"A3","name":"No Location Servo","brand":"BrandZ","address":"3 Test St","location":null}
                ],
                "prices":[
                  {"stationcode":"A1","price":172.9},
                  {"stationcode":"A3","price":180.0}
                ]}
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        server.start()
        val port = server.address.port
        fetcher = PriceFetcher("http://127.0.0.1:$port")
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `fetchRoute parses a real HTTP JSON response into RouteResult`() = runBlocking {
        val result = fetcher.fetchRoute(LatLon(-33.86, 151.20), LatLon(-33.87, 151.21), "fastest")
        val route = (result as BackendResult.Success).value
        assertEquals(12.345, route.distanceKm, 0.001)
        assertEquals(10.0, route.durationMinutes, 0.001)
        assertEquals(2, route.points.size)
        assertEquals(-33.86, route.points[0].lat, 0.0001)
    }

    @Test
    fun `fetchStations parses prices, drops stations with no price, drops stations with no location`() = runBlocking {
        val result = fetcher.fetchStations(LatLon(-33.86, 151.20), 10.0, "E10")
        val stations = (result as BackendResult.Success).value

        // A1 has both a price and a location -> included, price divided by 100.
        // A2 has a location but no price entry -> dropped.
        // A3 has a price but a null location -> dropped.
        assertEquals(1, stations.size)
        assertEquals("A1", stations[0].code)
        assertEquals(1.729, stations[0].pricePerLitre, 0.0001)
        assertTrue(stations.none { it.code == "A2" || it.code == "A3" })
    }

    @Test
    fun `fetchRoute reports failure for an unreachable backend, not a crash`() = runBlocking {
        val badFetcher = PriceFetcher("http://127.0.0.1:1") // nothing listens on port 1
        val result = badFetcher.fetchRoute(LatLon(0.0, 0.0), LatLon(1.0, 1.0), "fastest")
        assertTrue(result is BackendResult.Failure)
    }
}
