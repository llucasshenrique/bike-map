package com.ebike.router

import com.ebike.router.model.OsmWayTags
import com.ebike.router.service.WayTagsIndex
import org.junit.Assert.*
import org.junit.Test

class WayTagsIndexTest {

    @Test
    fun testFindTagsByNodeIdWhenPresent() {
        val tags = OsmWayTags(surface = "gravel", highway = "path")
        val index = WayTagsIndex(nodeTags = mapOf(42L to tags))
        assertEquals(tags, index.findTags(name = null, nodeId = 42L))
    }

    @Test
    fun testFindTagsFallsBackToNameWhenNodeIdMissing() {
        val tags = OsmWayTags(surface = "asphalt", highway = "residential")
        val index = WayTagsIndex(nameTags = mapOf("rua das flores" to tags))
        assertEquals(tags, index.findTags(name = "Rua das Flores", nodeId = 99L))
    }

    @Test
    fun testFindTagsNameLookupIsCaseAndWhitespaceInsensitive() {
        val tags = OsmWayTags(surface = "cobblestone")
        val index = WayTagsIndex(nameTags = mapOf("avenida central" to tags))
        assertEquals(tags, index.findTags(name = "  Avenida Central  "))
    }

    @Test
    fun testFindTagsReturnsNullWhenNothingMatches() {
        val index = WayTagsIndex()
        assertNull(index.findTags(name = "Unknown Street", nodeId = 1L))
    }

    @Test
    fun testFindTagsReturnsNullForBlankName() {
        val index = WayTagsIndex(nameTags = mapOf("" to OsmWayTags(surface = "asphalt")))
        assertNull(index.findTags(name = "   "))
    }

    @Test
    fun testFindTagsPrefersNodeIdOverName() {
        val nodeTags = OsmWayTags(surface = "gravel")
        val nameTags = OsmWayTags(surface = "asphalt")
        val index = WayTagsIndex(
            nodeTags = mapOf(7L to nodeTags),
            nameTags = mapOf("main st" to nameTags)
        )
        assertEquals(nodeTags, index.findTags(name = "Main St", nodeId = 7L))
    }
}
