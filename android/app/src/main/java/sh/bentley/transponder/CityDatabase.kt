package sh.bentley.transponder

import android.content.Context
import org.json.JSONArray
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class City(
    val name: String,
    val lat: Double,
    val lng: Double,
    val region: String,
    val country: String,
    val population: Int
) {
    /**
     * Returns a display string like "Toronto, ON" or "Paris, France"
     */
    fun displayName(): String {
        return if (region.isNotBlank()) {
            "$name, $region"
        } else {
            "$name, $country"
        }
    }
}

object CityDatabase {
    private var cities: List<City>? = null

    /** True once cities have been loaded from assets */
    val isLoaded: Boolean get() = cities != null

    /**
     * Load cities from assets. Call this once on app startup.
     */
    fun load(context: Context) {
        if (cities != null) return

        val json = context.assets.open("cities.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        val list = mutableListOf<City>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                City(
                    name = obj.getString("n"),
                    lat = obj.getDouble("la"),
                    lng = obj.getDouble("lo"),
                    region = obj.optString("r", ""),
                    country = obj.optString("c", ""),
                    population = obj.optInt("p", 0)
                )
            )
        }
        cities = list
    }

    /**
     * Find the nearest city to the given coordinates.
     * Uses a weighted score that prefers larger cities when distances are similar.
     * Returns null if database not loaded.
     */
    fun findNearest(lat: Double, lng: Double): City? {
        val cityList = cities ?: return null

        // Score = distance - population bonus
        // A city with 1M population gets ~5km bonus, 100k gets ~3km, etc.
        return cityList.minByOrNull { city ->
            val distance = haversineDistance(lat, lng, city.lat, city.lng)
            val populationBonus = kotlin.math.ln(city.population.toDouble().coerceAtLeast(1.0)) * 0.5
            distance - populationBonus
        }
    }

    /**
     * Haversine distance in kilometers between two points.
     */
    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
