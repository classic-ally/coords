import Foundation
import CoreLocation
import BackgroundTasks
import UIKit
import Combine

/// Manages background location sync via Significant Location Change and BGTaskScheduler
class BackgroundSyncManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = BackgroundSyncManager()

    private let locationManager = CLLocationManager()
    private let syncService = LocationSyncService()
    private let identityStore = IdentityStore.shared

    static let backgroundTaskIdentifier = "sh.bentley.transponder.refresh"

    @Published var isMonitoringSignificantChanges = false

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
    }

    // MARK: - BGTaskScheduler Registration

    /// Call this from AppDelegate/App init to register background tasks
    func registerBackgroundTasks() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.backgroundTaskIdentifier,
            using: nil
        ) { [weak self] task in
            self?.handleBackgroundRefresh(task: task as! BGAppRefreshTask)
        }
    }

    /// Schedule the next background refresh
    func scheduleBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.backgroundTaskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60) // 15 minutes

        do {
            try BGTaskScheduler.shared.submit(request)
            print("BackgroundSync: Scheduled background refresh")
        } catch {
            print("BackgroundSync: Failed to schedule background refresh: \(error)")
        }
    }

    /// Handle the background refresh task
    private func handleBackgroundRefresh(task: BGAppRefreshTask) {
        print("BackgroundSync: Background refresh task started")

        // Schedule the next refresh
        scheduleBackgroundRefresh()

        // Create a task to do the work
        let workTask = Task {
            await performBackgroundSync()
        }

        // Handle expiration
        task.expirationHandler = {
            workTask.cancel()
        }

        // Complete when done
        Task {
            _ = await workTask.value
            task.setTaskCompleted(success: true)
            print("BackgroundSync: Background refresh task completed")
        }
    }

    /// Perform the background sync (upload + fetch)
    private func performBackgroundSync() async {
        guard identityStore.hasIdentity else {
            print("BackgroundSync: No identity, skipping sync")
            return
        }

        // Upload if auto-share is enabled
        if identityStore.autoShareEnabled {
            await uploadCurrentLocation()
        }

        // Always fetch friend locations
        let result = await syncService.fetchTrackedFriends()
        switch result {
        case .success(let locations):
            print("BackgroundSync: Fetched \(locations.count) friend locations")
        case .error(let message):
            print("BackgroundSync: Fetch failed: \(message)")
        }
    }

    // MARK: - Significant Location Change

    /// Start monitoring for significant location changes
    func startMonitoringSignificantLocationChanges() {
        guard CLLocationManager.significantLocationChangeMonitoringAvailable() else {
            print("BackgroundSync: Significant location change monitoring not available")
            return
        }

        locationManager.startMonitoringSignificantLocationChanges()
        isMonitoringSignificantChanges = true
        print("BackgroundSync: Started monitoring significant location changes")
    }

    /// Stop monitoring for significant location changes
    func stopMonitoringSignificantLocationChanges() {
        locationManager.stopMonitoringSignificantLocationChanges()
        isMonitoringSignificantChanges = false
        print("BackgroundSync: Stopped monitoring significant location changes")
    }

    /// Request "Always" authorization for background location
    func requestAlwaysAuthorization() {
        locationManager.requestAlwaysAuthorization()
    }

    /// Check if we have "Always" authorization
    var hasAlwaysAuthorization: Bool {
        locationManager.authorizationStatus == .authorizedAlways
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        print("BackgroundSync: Significant location change detected: \(location.coordinate)")

        // Only upload if auto-share is enabled
        guard identityStore.autoShareEnabled else {
            print("BackgroundSync: Auto-share disabled, skipping upload")
            return
        }

        // Upload in background
        Task {
            await uploadLocation(location)
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        print("BackgroundSync: Authorization changed to \(manager.authorizationStatus.rawValue)")

        if manager.authorizationStatus == .authorizedAlways {
            // User granted "Always" permission - enable auto-share since that's the only
            // way to trigger this permission request in the app
            if !identityStore.autoShareEnabled {
                identityStore.setAutoShareEnabled(true)
            }
            // Start significant location monitoring
            if !isMonitoringSignificantChanges {
                startMonitoringSignificantLocationChanges()
                scheduleBackgroundRefresh()
            }
        } else {
            // Lost "Always" permission, stop monitoring and disable auto-share
            if isMonitoringSignificantChanges {
                stopMonitoringSignificantLocationChanges()
            }
            if identityStore.autoShareEnabled {
                identityStore.setAutoShareEnabled(false)
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("BackgroundSync: Location error: \(error.localizedDescription)")
    }

    // MARK: - Location Upload

    private func uploadCurrentLocation() async {
        // Use shared LocationManager with cached-okay freshness to preserve BGTask execution time
        guard let location = await LocationManager.shared.requestLocation(.cachedOkay) else {
            print("BackgroundSync: Could not get current location")
            return
        }

        await uploadLocation(location)
    }

    private func uploadLocation(_ location: CLLocation) async {
        let recipients = getShareRecipients()
        guard !recipients.isEmpty else {
            print("BackgroundSync: No share recipients, skipping upload")
            return
        }

        let result = await syncService.uploadLocation(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            altitude: location.altitude,
            accuracy: Float(location.horizontalAccuracy),
            timestamp: UInt64(location.timestamp.timeIntervalSince1970 * 1000)
        )

        switch result {
        case .success:
            print("BackgroundSync: Location uploaded successfully")
        case .error(let message):
            print("BackgroundSync: Upload failed: \(message)")
        }
    }
}
