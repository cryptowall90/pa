package com.pictureperfectx.app.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCatalogTest {

    @Test
    fun catalog_containsExpectedLooks() {
        val ids = FilterCatalog.filters.map { it.id }
        assertTrue(ids.containsAll(listOf("original", "fujifilm", "leica", "polaroid", "monochrome")))
    }

    @Test
    fun default_isOriginal() {
        assertEquals(Filter.ORIGINAL_ID, FilterCatalog.default.id)
    }

    @Test
    fun byId_unknownFallsBackToDefault() {
        assertEquals(FilterCatalog.default.id, FilterCatalog.byId("does-not-exist").id)
    }

    @Test
    fun byId_resolvesKnownFilter() {
        assertEquals("leica", FilterCatalog.byId("leica").id)
    }

    @Test
    fun filterIds_areUnique() {
        val ids = FilterCatalog.filters.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
