package com.ebike.router.model

enum class PlaceCategory {
    ADDRESS,
    LANDMARK,
    POI,
    CITY
}

data class SearchResultItem(
    val id: String,
    val name: String,
    val subText: String,
    val category: PlaceCategory,
    val point: GeoPoint
)
