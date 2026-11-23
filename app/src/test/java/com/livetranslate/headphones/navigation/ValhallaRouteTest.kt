package com.livetranslate.headphones.navigation

import com.livetranslate.headphones.resolveValhallaBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValhallaRouteTest {
    @Test
    fun fixtureYieldsTheNextInstruction() {
        val json = """
            {"trip":{"legs":[{"maneuvers":[
              {"instruction":"Walk east on King Street.","length":0.2,"begin_shape_index":0},
              {"instruction":"You have arrived.","length":0.0,"begin_shape_index":3}
            ]}]}}
        """.trimIndent()
        val route = ValhallaRoute.parse(json)
        assertEquals("Walk east on King Street.", route?.let { ValhallaRoute.nextInstruction(it, 0) })
        assertEquals("You have arrived.", route?.let { ValhallaRoute.nextInstruction(it, 1) })
    }

    @Test
    fun classicPolylineDecodes() {
        val points = ValhallaRoute.decodePolyline("_p~iF~ps|U_ulLnnqC", 1.0e5)
        assertEquals(2, points.size)
        assertEquals(38.5, points[0].lat, 0.01)
        assertEquals(-120.2, points[0].lon, 0.01)
        assertTrue(points[1].lat > 40.0)
    }

    @Test
    fun websiteHostMovesToTheApiHost() {
        assertEquals(
            "https://valhalla1.openstreetmap.de",
            resolveValhallaBaseUrl("https://valhalla.openstreetmap.de"),
        )
        assertEquals(
            "https://valhalla1.openstreetmap.de",
            resolveValhallaBaseUrl("https://valhalla.openstreetmap.de/"),
        )
        assertEquals(
            "https://example.test",
            resolveValhallaBaseUrl("https://example.test"),
        )
    }
}
