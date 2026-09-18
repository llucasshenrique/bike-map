package com.ebike.router.data.local.converter

import com.ebike.router.data.local.entity.DestinationCategory
import com.ebike.router.model.GeoPoint
import com.ebike.router.model.RouteWaypoint
import com.ebike.router.model.RoutingProfile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RoomConvertersTest {
    private lateinit var converters: RoomConverters

    @Before
    fun setUp() {
        converters = RoomConverters()
    }

    @Test
    fun `routing profile round trips through name`() {
        val json = converters.fromRoutingProfile(RoutingProfile.SCENIC)
        assertEquals("SCENIC", json)
        assertEquals(RoutingProfile.SCENIC, converters.toRoutingProfile(json))
    }

    @Test
    fun `unknown routing profile string defaults to EFFICIENT`() {
        assertEquals(RoutingProfile.EFFICIENT, converters.toRoutingProfile("NOT_A_REAL_PROFILE"))
    }

    @Test
    fun `null routing profile stays null`() {
        assertNull(converters.fromRoutingProfile(null))
        assertNull(converters.toRoutingProfile(null))
    }

    @Test
    fun `destination category round trips through name`() {
        val json = converters.fromDestinationCategory(DestinationCategory.TRAIL)
        assertEquals("TRAIL", json)
        assertEquals(DestinationCategory.TRAIL, converters.toDestinationCategory(json))
    }

    @Test
    fun `unknown destination category string defaults to FAVORITE`() {
        assertEquals(DestinationCategory.FAVORITE, converters.toDestinationCategory("GARBAGE"))
    }

    @Test
    fun `geo point list round trips through json`() {
        val points = listOf(GeoPoint(1.0, 2.0, 3.0), GeoPoint(4.0, 5.0, 6.0))
        val json = converters.fromGeoPointList(points)
        val decoded = converters.toGeoPointList(json)
        assertEquals(points, decoded)
    }

    @Test
    fun `blank geo point json decodes to empty list instead of null`() {
        assertEquals(emptyList<GeoPoint>(), converters.toGeoPointList(""))
        assertEquals(emptyList<GeoPoint>(), converters.toGeoPointList(null))
    }

    @Test
    fun `malformed geo point json decodes to empty list instead of throwing`() {
        assertEquals(emptyList<GeoPoint>(), converters.toGeoPointList("{not valid json"))
    }

    @Test
    fun `waypoint list round trips through json`() {
        val waypoints = listOf(
            RouteWaypoint(id = "a", letter = "A", label = "Start", point = GeoPoint(1.0, 2.0)),
            RouteWaypoint(id = "b", letter = "B", label = "End", point = null, isGps = true)
        )
        val json = converters.fromWaypointList(waypoints)
        val decoded = converters.toWaypointList(json)
        assertEquals(waypoints, decoded)
    }

    @Test
    fun `malformed waypoint json decodes to empty list instead of throwing`() {
        assertEquals(emptyList<RouteWaypoint>(), converters.toWaypointList("]not[json"))
    }
}
