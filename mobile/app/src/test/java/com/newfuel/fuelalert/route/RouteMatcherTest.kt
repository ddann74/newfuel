package com.newfuel.fuelalert.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture coordinates are real places (Sydney), and every expected
 * distance/progress value below was computed independently in Python
 * (same haversine formula, separate implementation) before writing
 * these assertions - not derived by running RouteMatcher itself and
 * copying its output. See mobile/PROGRESS.md milestone 2's note for the
 * exact values. Tolerances (delta) allow for the Kotlin/Python
 * floating-point paths not being bit-identical, not for genuine
 * correctness slop.
 */
class RouteMatcherTest {

    // Real Sydney coordinates.
    private val operaHouse = LatLon(-33.8568, 151.2153)
    private val bondiBeach = LatLon(-33.8908, 151.2743)
    private val parramatta = LatLon(-33.8151, 151.0011)

    @Test
    fun `haversineKm matches independently computed real-world distances`() {
        assertEquals(6.6304, RouteMatcher.haversineKm(operaHouse, bondiBeach), 0.01)
        assertEquals(20.3201, RouteMatcher.haversineKm(operaHouse, parramatta), 0.01)
    }

    // A due east-west route along the Opera House's latitude, from
    // 151.2153 to 151.0011 - total length ~19.7792 km (independently
    // computed). Kept as a single 2-point segment so every projection
    // below has an exact, hand-verifiable answer.
    private val routeStart = LatLon(-33.8568, 151.2153)
    private val routeEnd = LatLon(-33.8568, 151.0011)
    private val route = listOf(routeStart, routeEnd)

    @Test
    fun `distanceToPolyline is ~0 for a point exactly on the route`() {
        val midLon = (routeStart.lon + routeEnd.lon) / 2
        val onRoute = LatLon(routeStart.lat, midLon)
        assertEquals(0.0, RouteMatcher.distanceToPolyline(onRoute, route), 0.001)
    }

    @Test
    fun `distanceToPolyline matches an independently computed perpendicular offset`() {
        val midLon = (routeStart.lon + routeEnd.lon) / 2
        // 0.009 degrees of latitude south of the route line at the same
        // longitude - independently computed haversine distance for this
        // exact offset is 1.0007543 km.
        val offRoute = LatLon(routeStart.lat - 0.009, midLon)
        assertEquals(1.0008, RouteMatcher.distanceToPolyline(offRoute, route), 0.01)
    }

    @Test
    fun `projectOntoRoute progress matches independently computed along-route distance`() {
        // 40% of the way from routeStart to routeEnd by longitude
        // interpolation - independently computed progress from
        // routeStart is 7.9117 km.
        val fortyPercentLon = routeStart.lon + 0.4 * (routeEnd.lon - routeStart.lon)
        val point = LatLon(routeStart.lat, fortyPercentLon)
        val projection = RouteMatcher.projectOntoRoute(point, route)
        assertEquals(7.9117, projection.progressKm, 0.01)
        assertEquals(0.0, projection.offsetKm, 0.001)
    }

    @Test
    fun `stationsAhead includes on-route stations within corridor and lead distance`() {
        // Current position: 20% along the route - independently computed
        // progress 3.9558 km.
        val currentPos = LatLon(routeStart.lat, routeStart.lon + 0.2 * (routeEnd.lon - routeStart.lon))

        // stationA: 40% along, on the route - 3.9558 km ahead (independently computed).
        val stationA = LatLon(routeStart.lat, routeStart.lon + 0.4 * (routeEnd.lon - routeStart.lon))

        val matches = RouteMatcher.stationsAhead(
            currentPosition = currentPos,
            routePoints = route,
            stations = listOf(stationA),
            corridorWidthKm = 5.0,
            leadDistanceKm = 10.0,
        )

        assertEquals(1, matches.size)
        assertEquals(3.9558, matches[0].progressKm - RouteMatcher.projectOntoRoute(currentPos, route).progressKm, 0.01)
    }

    @Test
    fun `stationsAhead excludes a station beyond the lead distance`() {
        val currentPos = LatLon(routeStart.lat, routeStart.lon + 0.2 * (routeEnd.lon - routeStart.lon))
        // stationB: 90% along - independently computed 13.8454 km ahead of
        // current, which is beyond a 10 km lead distance.
        val stationB = LatLon(routeStart.lat, routeStart.lon + 0.9 * (routeEnd.lon - routeStart.lon))

        val matches = RouteMatcher.stationsAhead(
            currentPosition = currentPos,
            routePoints = route,
            stations = listOf(stationB),
            corridorWidthKm = 5.0,
            leadDistanceKm = 10.0,
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `stationsAhead excludes a station behind the current position`() {
        val currentPos = LatLon(routeStart.lat, routeStart.lon + 0.2 * (routeEnd.lon - routeStart.lon))
        // stationC: 5% along - behind the 20%-along current position
        // (independently computed -2.9669 km "ahead", i.e. actually behind).
        val stationC = LatLon(routeStart.lat, routeStart.lon + 0.05 * (routeEnd.lon - routeStart.lon))

        val matches = RouteMatcher.stationsAhead(
            currentPosition = currentPos,
            routePoints = route,
            stations = listOf(stationC),
            corridorWidthKm = 5.0,
            leadDistanceKm = 10.0,
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `stationsAhead excludes a station outside the corridor width even if ahead`() {
        val currentPos = LatLon(routeStart.lat, routeStart.lon + 0.2 * (routeEnd.lon - routeStart.lon))
        // stationD: same longitude as the 40%-along point, but 0.09
        // degrees of latitude off the route - independently computed
        // offset is 10.0075 km, outside a 5 km corridor width.
        val stationD = LatLon(
            routeStart.lat - 0.09,
            routeStart.lon + 0.4 * (routeEnd.lon - routeStart.lon),
        )

        val matches = RouteMatcher.stationsAhead(
            currentPosition = currentPos,
            routePoints = route,
            stations = listOf(stationD),
            corridorWidthKm = 5.0,
            leadDistanceKm = 10.0,
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `samplePolyline keeps first and last points and thins the rest`() {
        // 21 evenly spaced points from routeStart to routeEnd - total
        // length ~19.7792 km, so ~10km steps should produce roughly 3
        // sampled points (start, ~midpoint, end), not all 21.
        val points = (0..20).map { i ->
            LatLon(routeStart.lat, routeStart.lon + (i / 20.0) * (routeEnd.lon - routeStart.lon))
        }
        val sampled = RouteMatcher.samplePolyline(points, stepKm = 10.0)

        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
        assertTrue("expected thinning, got ${sampled.size} of ${points.size} points", sampled.size < points.size)
    }
}
