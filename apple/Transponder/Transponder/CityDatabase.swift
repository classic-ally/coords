import Foundation

struct City {
    let name: String
    let lat: Double
    let lng: Double
    let region: String
    let country: String
    let population: Int

    /// Returns a display string like "Toronto, ON" or "Paris, France"
    var displayName: String {
        if !region.isEmpty {
            return "\(name), \(region)"
        } else {
            return "\(name), \(country)"
        }
    }
}

/// Local city database for privacy-preserving reverse geocoding.
/// No network requests - all lookups are done locally.
class CityDatabase {
    static let shared = CityDatabase()

    private var cities: [City]?

    /// True once cities have been loaded
    var isLoaded: Bool { cities != nil }

    private init() {
        load()
    }

    /// Load cities from bundle. Called automatically on init.
    private func load() {
        guard cities == nil else { return }

        guard let url = Bundle.main.url(forResource: "cities", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            print("CityDatabase: Failed to load cities.json")
            return
        }

        cities = array.compactMap { obj -> City? in
            guard let name = obj["n"] as? String,
                  let lat = obj["la"] as? Double,
                  let lng = obj["lo"] as? Double else {
                return nil
            }
            return City(
                name: name,
                lat: lat,
                lng: lng,
                region: obj["r"] as? String ?? "",
                country: obj["c"] as? String ?? "",
                population: obj["p"] as? Int ?? 0
            )
        }
    }

    /// Find the nearest city to the given coordinates.
    /// Uses a weighted score that prefers larger cities when distances are similar.
    func findNearest(lat: Double, lng: Double) -> City? {
        guard let cityList = cities else { return nil }

        // Score = distance - population bonus
        // A city with 1M population gets ~5km bonus, 100k gets ~3km, etc.
        return cityList.min { a, b in
            score(for: a, from: lat, lng: lng) < score(for: b, from: lat, lng: lng)
        }
    }

    private func score(for city: City, from lat: Double, lng: Double) -> Double {
        let distance = haversineDistance(lat1: lat, lng1: lng, lat2: city.lat, lng2: city.lng)
        let populationBonus = log(Double(max(city.population, 1))) * 0.5
        return distance - populationBonus
    }

    /// Haversine distance in kilometers between two points.
    private func haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double) -> Double {
        let r = 6371.0 // Earth radius in km
        let dLat = (lat2 - lat1) * .pi / 180
        let dLng = (lng2 - lng1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) *
                sin(dLng / 2) * sin(dLng / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
