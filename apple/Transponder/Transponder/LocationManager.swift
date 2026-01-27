import Foundation
import CoreLocation
import Combine

// MARK: - Location Freshness

enum LocationFreshness {
    case cachedOkay   // Use cached if <3min old
    case alwaysFresh  // Always request new GPS fix
}

// MARK: - Location Manager

class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationManager()

    private let manager = CLLocationManager()
    private var locationContinuation: CheckedContinuation<CLLocation?, Never>?

    @Published var currentLocation: CLLocation?
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined

    /// Called when location updates (including background). Set by MainView for uploads.
    var onBackgroundLocationUpdate: ((CLLocation) -> Void)?

    /// Maximum age for cached location to be considered fresh (3 minutes)
    private let maxLocationAge: TimeInterval = 3 * 60

    /// Distance filter for continuous updates (reduces background GPS wake)
    private let backgroundDistanceFilter: CLLocationDistance = 40

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = backgroundDistanceFilter
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false
    }

    func requestPermission() {
        manager.requestAlwaysAuthorization()
    }

    /// Request location with specified freshness requirement
    func requestLocation(_ freshness: LocationFreshness) async -> CLLocation? {
        // For cachedOkay, return cached if fresh enough
        if freshness == .cachedOkay, let location = currentLocation {
            let age = Date().timeIntervalSince(location.timestamp)
            if age < maxLocationAge {
                return location
            }
        }

        // Temporarily remove distance filter so requestLocation() works
        manager.distanceFilter = kCLDistanceFilterNone

        // Request fresh location from GPS
        let freshLocation = await withCheckedContinuation { continuation in
            locationContinuation = continuation
            manager.requestLocation()
        }

        // Restore distance filter for continuous updates
        manager.distanceFilter = backgroundDistanceFilter

        // Return fresh if we got it, otherwise fall back to any cached
        return freshLocation ?? currentLocation
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationStatus = manager.authorizationStatus

        #if os(iOS)
        if manager.authorizationStatus == .authorizedWhenInUse ||
           manager.authorizationStatus == .authorizedAlways {
            manager.startUpdatingLocation()
        }
        #else
        if manager.authorizationStatus == .authorizedAlways ||
           manager.authorizationStatus == .authorized {
            manager.startUpdatingLocation()
        }
        #endif
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        currentLocation = location

        #if DEBUG
        let age = Date().timeIntervalSince(location.timestamp)
        print("LocationManager: didUpdateLocations age=\(String(format: "%.1f", age))s")
        #endif

        onBackgroundLocationUpdate?(location)

        if let continuation = locationContinuation {
            locationContinuation = nil
            continuation.resume(returning: location)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        #if DEBUG
        print("Location error: \(error.localizedDescription)")
        #endif

        // Resume continuation with nil on error
        if let continuation = locationContinuation {
            locationContinuation = nil
            continuation.resume(returning: nil)
        }
    }
}
