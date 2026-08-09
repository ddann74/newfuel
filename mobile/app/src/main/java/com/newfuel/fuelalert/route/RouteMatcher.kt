package com.newfuel.fuelalert.route

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** How far off the route a station is, and how far along the route
  * (from the start) its closest point on the route sits - the second
  * number is what lets a caller tell "ahead of me" from "behind me". */
data class RouteProjection(val offsetKm: Double, val progressKm: Double)

data class CorridorMatch(val station: LatLon, val offsetKm: Double, val progressKm: Double)

/**
 * Pure route-corridor geometry - no Android dependencies, so it's fully
 * unit-testable on the plain JVM and fully compile-verifiable in a
 * sandbox that can't reach dl.google.com (see mobile/PROGRESS.md).
 *
 * The distance/segment-projection math (haversineRaw, point-to-segment
 * projection, polyline sampling) is a direct port of the proven,
 * production logic already in ../../../static/app.js (the web app) -
 * same formulas, same "raw haversine for corridor width, no road-factor
 * fudge" choice, since corridor width is a short local distance where
 * road-vs-straight-line doesn't matter the way it does for a whole trip.
 *
 * `progressAlongRoute`/`stationsAhead` are new for this app - the web
 * app never needed "ahead of me" vs "behind me" since it shows a
 * one-shot search result, not a live in-trip alert (PRD.md ss5.3).
 */
object RouteMatcher {

    private const val EARTH_RADIUS_KM = 6371.0

    fun haversineKm(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            sin(dLon / 2).let { it * it }
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Thins a polyline down to points roughly [stepKm] apart, always
      * keeping the first and last point - mirrors static/app.js's
      * samplePolyline, used when sampling corridor search points along a
      * long route rather than querying every raw route vertex. */
    fun samplePolyline(points: List<LatLon>, stepKm: Double): List<LatLon> {
        if (points.isEmpty()) return emptyList()
        val sampled = mutableListOf(points[0])
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += haversineKm(points[i - 1], points[i])
            if (acc >= stepKm) {
                sampled.add(points[i])
                acc = 0.0
            }
        }
        val last = points.last()
        if (sampled.last() != last) sampled.add(last)
        return sampled
    }

    /** Cumulative distance from points[0] to points[i], for each i. */
    private fun cumulativeDistances(points: List<LatLon>): DoubleArray {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + haversineKm(points[i - 1], points[i])
        }
        return cum
    }

    /** Projects [p] onto the closest point of segment a→b, returning how
      * far [p] is from that projected point. t is clamped to [0,1] so the
      * projection never falls outside the segment itself - same approach
      * as static/app.js's ptToSegDist. Segment length is treated in
      * degree-space for the projection parameter t (matching the web
      * app's original approach) since t is only used to interpolate a
      * point on the segment, not as a distance itself - the actual
      * distance is always computed afterward via haversineKm. */
    private fun projectOntoSegment(p: LatLon, a: LatLon, b: LatLon): Pair<LatLon, Double> {
        val dx = b.lon - a.lon
        val dy = b.lat - a.lat
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return a to haversineKm(p, a)
        val t = (((p.lon - a.lon) * dx + (p.lat - a.lat) * dy) / lenSq).coerceIn(0.0, 1.0)
        val projected = LatLon(a.lat + t * dy, a.lon + t * dx)
        return projected to t
    }

    /** Shortest distance from [p] to the polyline as a whole - the
      * corridor-width check. Mirrors static/app.js's distToPolyline
      * exactly (raw haversine, no road-factor fudge - see class doc). */
    fun distanceToPolyline(p: LatLon, points: List<LatLon>): Double {
        if (points.size < 2) return Double.POSITIVE_INFINITY
        var best = Double.POSITIVE_INFINITY
        for (i in 1 until points.size) {
            val (projected, _) = projectOntoSegment(p, points[i - 1], points[i])
            best = minOf(best, haversineKm(p, projected))
        }
        return best
    }

    /** Where [p]'s closest point on the polyline sits, both as an offset
      * distance (corridor width) and as a progress distance from the
      * route's start (ahead/behind) - the new logic this app needed that
      * the web app never did (see class doc). */
    fun projectOntoRoute(p: LatLon, points: List<LatLon>): RouteProjection {
        if (points.size < 2) return RouteProjection(Double.POSITIVE_INFINITY, 0.0)
        val cumulative = cumulativeDistances(points)
        var bestOffset = Double.POSITIVE_INFINITY
        var bestProgress = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val (projected, t) = projectOntoSegment(p, a, b)
            val offset = haversineKm(p, projected)
            if (offset < bestOffset) {
                bestOffset = offset
                val segmentLen = cumulative[i] - cumulative[i - 1]
                bestProgress = cumulative[i - 1] + segmentLen * t
            }
        }
        return RouteProjection(bestOffset, bestProgress)
    }

    /** Stations within [corridorWidthKm] of the route AND between the
      * driver's current position and [leadDistanceKm] further ahead -
      * never behind (PRD.md ss5.3: "ahead of the current position...
      * not behind"), and never further ahead than the lead distance, so
      * an alert always leaves time to react. */
    fun stationsAhead(
        currentPosition: LatLon,
        routePoints: List<LatLon>,
        stations: List<LatLon>,
        corridorWidthKm: Double,
        leadDistanceKm: Double,
    ): List<CorridorMatch> {
        if (routePoints.size < 2) return emptyList()
        val currentProgress = projectOntoRoute(currentPosition, routePoints).progressKm
        return stations.mapNotNull { station ->
            val projection = projectOntoRoute(station, routePoints)
            if (projection.offsetKm > corridorWidthKm) return@mapNotNull null
            val distanceAhead = projection.progressKm - currentProgress
            if (distanceAhead < 0.0 || distanceAhead > leadDistanceKm) return@mapNotNull null
            CorridorMatch(station, projection.offsetKm, projection.progressKm)
        }
    }
}
