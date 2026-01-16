import SwiftUI
import MapKit
import CoreLocation
import Combine

// MARK: - Color Extension for Hex Parsing

extension Color {
    /// Initialize a Color from a hex string like "#4A90D9"
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let r, g, b: UInt64
        switch hex.count {
        case 6: // RGB
            (r, g, b) = ((int >> 16) & 0xFF, (int >> 8) & 0xFF, int & 0xFF)
        default:
            (r, g, b) = (128, 128, 128) // Default gray
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: 1
        )
    }
}

/// Which content is showing in the bottom sheet
enum SheetContent: Equatable {
    case friends
    case profile
    case friendDetail(String)  // pubkey
    case confirmAddFriend(ParsedFriendLink)  // deep link confirmation
}

/// Pending camera actions for deferred execution
enum CameraAction: Equatable {
    case centerOn(latitude: Double, longitude: Double)
    case fitAllFriends
}

struct MainView: View {
    @ObservedObject var identityStore: IdentityStore
    var delayLocationRequest: Bool = false
    var suppressSheet: Bool = false
    @Binding var pendingFriendLink: String?

    @ObservedObject private var locationManager = LocationManager.shared
    @Environment(\.scenePhase) private var scenePhase

    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var showingAddFriend = false
    @State private var isUploading = false
    @State private var isFetchingFriends = false

    // Foreground refresh state
    @State private var lastFetchTimestamp: Date = .distantPast
    private let fetchIntervalSeconds: TimeInterval = 5 * 60  // 5 minutes
    @State private var serverLocation: Location?
    @State private var showServerLocation = false
    @State private var uploadMessage: String?
    @State private var friends: [Friend] = []
    @State private var serverVersion: String?
    @State private var pendingCameraAction: CameraAction?
    @State private var hasSetInitialBounds = false

    // Sheet state
    @State private var sheetContent: SheetContent = .friends
    @State private var selectedDetent: PresentationDetent = .height(60)
    @State private var showSheet: Bool = true

    // Friend name editing
    @State private var showingEditFriendName = false
    @State private var editingFriendPubkey: String?
    @State private var editedFriendName: String = ""

    // Deep link handling - passed to AddFriendSheet to skip to "share your QR" step
    @State private var deepLinkFriendName: String?

    private let syncService = LocationSyncService()

    // Only allow .large for friends list if enough friends to justify scrolling
    private var friendsDetents: Set<PresentationDetent> {
        friends.count > 5 ? [.height(60), .fraction(0.4), .large] : [.height(60), .fraction(0.4)]
    }
    private let profileDetents: Set<PresentationDetent> = [.height(60), .fraction(0.4), .large]
    private let detailDetents: Set<PresentationDetent> = [.height(60), .fraction(0.4)]

    /// Computed location for "my location" display - either server or GPS based on toggle
    private var myDisplayLocation: (coordinate: CLLocationCoordinate2D, accuracy: Double)? {
        if showServerLocation, let serverLoc = serverLocation {
            return (
                coordinate: CLLocationCoordinate2D(latitude: serverLoc.latitude, longitude: serverLoc.longitude),
                accuracy: Double(serverLoc.accuracy)
            )
        } else if let location = locationManager.currentLocation {
            return (
                coordinate: location.coordinate,
                accuracy: location.horizontalAccuracy
            )
        }
        return nil
    }

    var body: some View {
        ZStack {
            // Map
            Map(position: $cameraPosition) {
                // My location - either GPS or server location depending on toggle
                if let myLocation = myDisplayLocation {
                    // Accuracy circle
                    MapCircle(
                        center: myLocation.coordinate,
                        radius: CLLocationDistance(myLocation.accuracy)
                    )
                    .foregroundStyle(.blue.opacity(0.2))
                    .stroke(.blue, lineWidth: 2)

                    // Center dot
                    Annotation("", coordinate: myLocation.coordinate) {
                        ZStack {
                            Circle()
                                .fill(.blue)
                                .frame(width: 20, height: 20)
                            Circle()
                                .stroke(.white, lineWidth: 3)
                                .frame(width: 20, height: 20)
                        }
                    }
                }

                // Friend location accuracy circles (scale with map zoom)
                ForEach(friends.filter { $0.location != nil && $0.fetchFrom }, id: \.pubkey) { friend in
                    if let loc = friend.location {
                        let friendColor = Color(hex: friend.color)
                        MapCircle(
                            center: CLLocationCoordinate2D(latitude: loc.latitude, longitude: loc.longitude),
                            radius: CLLocationDistance(loc.accuracy)
                        )
                        .foregroundStyle(friendColor.opacity(0.2))
                        .stroke(friendColor, lineWidth: 2)
                    }
                }

                // Friend location center dots with initials
                ForEach(friends.filter { $0.location != nil && $0.fetchFrom }, id: \.pubkey) { friend in
                    if let loc = friend.location {
                        let isSelected = {
                            if case .friendDetail(let pubkey) = sheetContent {
                                return pubkey == friend.pubkey
                            }
                            return false
                        }()
                        let friendColor = Color(hex: friend.color)

                        Annotation("", coordinate: CLLocationCoordinate2D(
                            latitude: loc.latitude,
                            longitude: loc.longitude
                        )) {
                            ZStack {
                                // Circle with initial (centered at annotation point)
                                ZStack {
                                    Circle()
                                        .fill(friendColor)
                                        .frame(width: 24, height: 24)
                                    Circle()
                                        .stroke(.white, lineWidth: 2)
                                        .frame(width: 24, height: 24)
                                    Text(String(friend.name.prefix(1)))
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(.white)
                                }

                                // Show name label only when selected (offset above dot)
                                if isSelected {
                                    Text(friend.name)
                                        .font(.caption.bold())
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(.regularMaterial)
                                        .clipShape(Capsule())
                                        .offset(y: -28)
                                }
                            }
                            .onTapGesture {
                                selectAndCenterOnFriend(friend)
                            }
                        }
                    }
                }
            }
            .mapStyle(.standard)
            .mapControls {
                MapCompass()
                MapScaleView()
            }

        }
        .sheet(isPresented: $showSheet) {
            sheetContentView
                .presentationDetents(currentDetents, selection: $selectedDetent)
                .presentationDragIndicator(.visible)
                .presentationBackgroundInteraction(.enabled(upThrough: .fraction(0.4)))
                .interactiveDismissDisabled()
        }
        .onChange(of: showSheet) { _, newValue in
            // If sheet was dismissed (e.g., by system during alert presentation), re-show it
            if !newValue && !suppressSheet {
                showSheet = true
            }
        }
        .onChange(of: suppressSheet) { _, newValue in
            showSheet = !newValue
        }
        .onAppear {
            // Sync sheet visibility with suppressSheet on initial appear
            showSheet = !suppressSheet

            if !delayLocationRequest {
                locationManager.requestPermission()
            }
            refreshFriends()
            if identityStore.hasIdentity {
                fetchFriendsIfNeeded(force: true)  // Force fetch on appear
                fetchServerVersion()

                // Auto-upload on launch if auto-share is enabled and we have share recipients
                if identityStore.autoShareEnabled && !getShareRecipients().isEmpty {
                    uploadCurrentLocationBackground(.cachedOkay)
                }
            }
        }
        .onChange(of: identityStore.hasIdentity) { _, hasIdentity in
            // When identity is created (onboarding complete), start location
            if hasIdentity {
                locationManager.requestPermission()
                fetchFriendsIfNeeded(force: true)
                fetchServerVersion()
            }
        }
        .task {
            // Periodic refresh every 60s while in foreground
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                guard !Task.isCancelled else { break }

                print("Timer fired: autoShare=\(identityStore.autoShareEnabled), recipients=\(getShareRecipients().count)")
                fetchFriendsIfNeeded(force: true)

                // Periodic upload if auto-share enabled
                if identityStore.autoShareEnabled && !getShareRecipients().isEmpty {
                    uploadCurrentLocationBackground(.alwaysFresh)
                }
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                // App resumed, fetch if stale
                fetchFriendsIfNeeded(force: false)
            }
        }
        .onChange(of: friends) { _, _ in
            // Trigger initial fit when friends load
            if !hasSetInitialBounds {
                triggerInitialCameraFit()
            }
        }
        .onChange(of: locationManager.currentLocation) { _, _ in
            // Trigger initial fit when location becomes available
            if !hasSetInitialBounds {
                triggerInitialCameraFit()
            }
        }
        .onChange(of: pendingCameraAction) { _, action in
            guard let action = action else { return }
            executeCameraAction(action)
            pendingCameraAction = nil
        }
        .alert("Edit Name", isPresented: $showingEditFriendName) {
            TextField("Name", text: $editedFriendName)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                if let pubkey = editingFriendPubkey, !editedFriendName.isEmpty {
                    try? updateFriend(
                        pubkey: pubkey,
                        shareWith: nil,
                        fetchFrom: nil,
                        name: editedFriendName
                    )
                    refreshFriends()
                }
            }
        } message: {
            Text("Enter a new name for this friend")
        }
        .onChange(of: pendingFriendLink) { _, newLink in
            if let link = newLink {
                if let parsed = try? parseFriendLink(url: link) {
                    sheetContent = .confirmAddFriend(parsed)
                    selectedDetent = .medium
                }
                pendingFriendLink = nil
            }
        }
    }

    private var currentDetents: Set<PresentationDetent> {
        switch sheetContent {
        case .friends: return friendsDetents
        case .profile: return profileDetents
        case .friendDetail: return detailDetents
        case .confirmAddFriend: return [.medium]
        }
    }

    @ViewBuilder
    private var sheetContentView: some View {
        switch sheetContent {
        case .friends:
            FriendsSheetContent(
                identityStore: identityStore,
                friends: friends,
                currentLocation: locationManager.currentLocation,
                isFetchingFriends: isFetchingFriends,
                showingAddFriend: $showingAddFriend,
                deepLinkFriendName: $deepLinkFriendName,
                onRefresh: { fetchFriendsIfNeeded(force: true) },
                onAddFriend: addFriendFromLink,
                onShowProfile: {
                    sheetContent = .profile
                    // Center on my location
                    if let loc = locationManager.currentLocation {
                        pendingCameraAction = .centerOn(latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude)
                    }
                    // Only expand if fully collapsed
                    if selectedDetent == .height(60) {
                        selectedDetent = .fraction(0.4)
                    }
                },
                onSelectFriend: { friend in
                    selectAndCenterOnFriend(friend)
                },
                onToggleShare: toggleShare,
                onToggleFetch: toggleFetch,
                onDeleteFriend: deleteFriend
            )

        case .profile:
            ProfileSheetContent(
                identityStore: identityStore,
                locationManager: locationManager,
                syncService: syncService,
                isUploading: $isUploading,
                uploadMessage: $uploadMessage,
                showServerLocation: $showServerLocation,
                serverLocation: $serverLocation,
                serverVersion: serverVersion,
                onBack: {
                    sheetContent = .friends
                    pendingCameraAction = .fitAllFriends
                }
            )

        case .friendDetail(let pubkey):
            if let friend = friends.first(where: { $0.pubkey == pubkey }) {
                FriendDetailSheetContent(
                    friend: friend,
                    onBack: {
                        sheetContent = .friends
                        pendingCameraAction = .fitAllFriends
                    },
                    onToggleShare: {
                        toggleShare(friend)
                        refreshFriends()
                    },
                    onToggleFetch: {
                        toggleFetch(friend)
                        refreshFriends()
                    },
                    onRemove: {
                        try? removeFriend(pubkey: friend.pubkey)
                        refreshFriends()
                        sheetContent = .friends
                        pendingCameraAction = .fitAllFriends
                    },
                    onEditName: {
                        editingFriendPubkey = friend.pubkey
                        editedFriendName = friend.name
                        showingEditFriendName = true
                    }
                )
            } else {
                Text("Friend not found")
            }

        case .confirmAddFriend(let friend):
            ConfirmAddFriendContent(
                friend: friend,
                onConfirm: {
                    sheetContent = .friends
                    addFriendFromDeepLink(friend)
                },
                onCancel: {
                    sheetContent = .friends
                }
            )
        }
    }

    private func selectAndCenterOnFriend(_ friend: Friend) {
        guard let loc = friend.location else { return }
        pendingCameraAction = .centerOn(latitude: loc.latitude, longitude: loc.longitude)
        sheetContent = .friendDetail(friend.pubkey)
        selectedDetent = .fraction(0.4)
    }

    private func executeCameraAction(_ action: CameraAction) {
        withAnimation {
            switch action {
            case .centerOn(let latitude, let longitude):
                cameraPosition = .region(MKCoordinateRegion(
                    center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
                    latitudinalMeters: 750,
                    longitudinalMeters: 750
                ))

            case .fitAllFriends:
                // Collect all locations (friends + my location)
                var coordinates: [CLLocationCoordinate2D] = []

                // Add friend locations
                for friend in friends {
                    if let loc = friend.location {
                        coordinates.append(CLLocationCoordinate2D(latitude: loc.latitude, longitude: loc.longitude))
                    }
                }

                // Add my location
                if let myLoc = locationManager.currentLocation {
                    coordinates.append(myLoc.coordinate)
                }

                if coordinates.count >= 2 {
                    // Compute bounding box
                    let lats = coordinates.map { $0.latitude }
                    let lngs = coordinates.map { $0.longitude }
                    let minLat = lats.min()!
                    let maxLat = lats.max()!
                    let minLng = lngs.min()!
                    let maxLng = lngs.max()!

                    let center = CLLocationCoordinate2D(
                        latitude: (minLat + maxLat) / 2,
                        longitude: (minLng + maxLng) / 2
                    )

                    // Add padding (20% on each side)
                    let latDelta = (maxLat - minLat) * 1.4
                    let lngDelta = (maxLng - minLng) * 1.4

                    // Minimum ~1.5km span so fit-all is always more zoomed out than friend selection (750m)
                    let minDelta = 0.015
                    cameraPosition = .region(MKCoordinateRegion(
                        center: center,
                        span: MKCoordinateSpan(
                            latitudeDelta: max(latDelta, minDelta),
                            longitudeDelta: max(lngDelta, minDelta)
                        )
                    ))
                } else if coordinates.count == 1 {
                    // Single location: center on it with 1.5km span (same as minimum for multiple)
                    cameraPosition = .region(MKCoordinateRegion(
                        center: coordinates.first!,
                        latitudinalMeters: 1500,
                        longitudinalMeters: 1500
                    ))
                }
                // If no locations, don't change camera
            }
        }
    }

    private func toggleShare(_ friend: Friend) {
        try? updateFriend(
            pubkey: friend.pubkey,
            shareWith: !friend.shareWith,
            fetchFrom: nil,
            name: nil
        )
        refreshFriends()
    }

    private func toggleFetch(_ friend: Friend) {
        try? updateFriend(
            pubkey: friend.pubkey,
            shareWith: nil,
            fetchFrom: !friend.fetchFrom,
            name: nil
        )
        refreshFriends()
    }

    private func deleteFriend(_ friend: Friend) {
        try? removeFriend(pubkey: friend.pubkey)
        refreshFriends()
    }

    private func addFriendFromLink(_ link: String) {
        guard let parsed = try? parseFriendLink(url: link) else {
            uploadMessage = "Invalid friend link"
            return
        }

        Task {
            // Request fresh GPS location for explicit user action
            let location = await locationManager.requestLocation(.alwaysFresh)
            let result = await syncService.addFriendAndUploadLocation(
                pubkey: parsed.pubkey,
                server: parsed.server,
                name: parsed.name,
                location: location
            )
            await MainActor.run {
                switch result {
                case .addFriendFailed(let message):
                    uploadMessage = "Failed to add friend: \(message)"
                case .successUploadFailed(let message):
                    print("Friend added but location upload failed: \(message)")
                    refreshFriends()
                case .success, .successWithUpload:
                    refreshFriends()
                }
            }
        }
    }

    private func addFriendFromDeepLink(_ friend: ParsedFriendLink) {
        // Show share QR immediately - we have the name already
        deepLinkFriendName = friend.name
        showingAddFriend = true

        // Do the actual add + upload in background
        Task {
            let location = await locationManager.requestLocation(.alwaysFresh)
            let result = await syncService.addFriendAndUploadLocation(
                pubkey: friend.pubkey,
                server: friend.server,
                name: friend.name,
                location: location
            )
            await MainActor.run {
                switch result {
                case .addFriendFailed(let message):
                    uploadMessage = "Failed to add friend: \(message)"
                case .successUploadFailed(let message):
                    print("Friend added but location upload failed: \(message)")
                    refreshFriends()
                case .success, .successWithUpload:
                    refreshFriends()
                }
            }
        }
    }

    private func triggerInitialCameraFit() {
        // Check if we have any locations to show
        let hasFriendLocations = friends.contains { $0.location != nil }
        let hasMyLocation = locationManager.currentLocation != nil

        // Only set initial bounds if we have at least one location
        if hasFriendLocations || hasMyLocation {
            pendingCameraAction = .fitAllFriends
            hasSetInitialBounds = true
        }
    }

    private func refreshFriends() {
        let realFriends = listFriends()
        #if DEBUG
        if realFriends.isEmpty {
            friends = mockFriends()
        } else {
            friends = realFriends
        }
        #else
        friends = realFriends
        #endif
    }

    private func fetchFriendsIfNeeded(force: Bool) {
        let now = Date()
        let elapsed = now.timeIntervalSince(lastFetchTimestamp)

        // Skip if not stale and not forced
        if !force && elapsed < fetchIntervalSeconds {
            return
        }

        // Avoid concurrent fetches
        guard !isFetchingFriends else { return }

        lastFetchTimestamp = now
        fetchFriendLocations()
    }

    private func fetchFriendLocations() {
        isFetchingFriends = true

        Task {
            let result = await syncService.fetchTrackedFriends()

            await MainActor.run {
                isFetchingFriends = false
                refreshFriends()  // Reload from storage to get updated locations

                if case .error(let message) = result {
                    uploadMessage = message
                }
            }
        }
    }

    private func uploadCurrentLocationBackground(_ freshness: LocationFreshness) {
        Task {
            guard let location = await locationManager.requestLocation(freshness) else { return }

            _ = await syncService.uploadLocation(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                accuracy: Float(location.horizontalAccuracy),
                timestamp: UInt64(location.timestamp.timeIntervalSince1970 * 1000)
            )
        }
    }

    private func fetchServerVersion() {
        Task {
            let result = await syncService.validateServer(url: identityStore.serverUrl)
            await MainActor.run {
                if case .valid(_, let version) = result {
                    serverVersion = version
                } else {
                    serverVersion = nil
                }
            }
        }
    }
}

// MARK: - Location Manager

enum LocationFreshness {
    case cachedOkay   // Use cached if <5min old
    case alwaysFresh  // Always request new GPS fix
}

class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationManager()

    private let manager = CLLocationManager()
    private var locationContinuation: CheckedContinuation<CLLocation?, Never>?

    @Published var currentLocation: CLLocation?
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined

    /// Maximum age for cached location to be considered fresh (5 minutes)
    private let maxLocationAge: TimeInterval = 5 * 60

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
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

        // Request fresh location from GPS
        let freshLocation = await withCheckedContinuation { continuation in
            locationContinuation = continuation
            manager.requestLocation()
        }

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
        currentLocation = locations.last

        // Resume continuation if waiting for fresh location
        if let continuation = locationContinuation {
            locationContinuation = nil
            continuation.resume(returning: locations.last)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("Location error: \(error.localizedDescription)")

        // Resume continuation with nil on error
        if let continuation = locationContinuation {
            locationContinuation = nil
            continuation.resume(returning: nil)
        }
    }
}

// MARK: - Format Helpers

private func formatAge(timestampMs: UInt64) -> String {
    let now = UInt64(Date().timeIntervalSince1970 * 1000)
    let diffMs = now - timestampMs
    let diffSec = diffMs / 1000
    let diffMin = diffSec / 60
    let diffHour = diffMin / 60
    let diffDay = diffHour / 24

    if diffMin < 1 {
        return "just now"
    } else if diffMin < 60 {
        return "\(diffMin)m ago"
    } else if diffHour < 24 {
        return "\(diffHour)h ago"
    } else {
        return "\(diffDay)d ago"
    }
}

private func formatDistance(meters: Double) -> String {
    if meters < 1000 {
        return "\(Int(meters)) m"
    } else if meters < 10000 {
        return String(format: "%.1f km", meters / 1000)
    } else {
        return "\(Int(meters / 1000)) km"
    }
}

// MARK: - Friend List Row

struct FriendListRow: View {
    let friend: Friend
    let currentLocation: CLLocation?
    let isEditMode: Bool
    let onTap: () -> Void
    let onToggleShare: () -> Void
    let onToggleFetch: () -> Void

    private var distance: Double? {
        guard let loc = friend.location,
              let currentLoc = currentLocation else { return nil }
        let friendLoc = CLLocation(latitude: loc.latitude, longitude: loc.longitude)
        return currentLoc.distance(from: friendLoc)
    }

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(friend.name)
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .foregroundStyle(.primary)

                if isEditMode {
                    // Edit mode: show location age
                    if let loc = friend.location {
                        Text(formatAge(timestampMs: loc.timestamp))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("No location")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                } else {
                    // Normal mode: show city
                    if let loc = friend.location {
                        if let city = CityDatabase.shared.findNearest(lat: loc.latitude, lng: loc.longitude) {
                            Text(city.displayName)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        } else {
                            Text("Unknown location")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Text("No location")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
            }

            Spacer()

            if isEditMode {
                // Edit mode: show share/fetch toggles and delete
                Button(action: onToggleShare) {
                    Image(systemName: friend.shareWith ? "arrow.up" : "arrow.up")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(friend.shareWith ? .white : .gray)
                        .frame(width: 32, height: 32)
                        .background(friend.shareWith ? Color.blue : Color(.systemGray5))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)

                Button(action: onToggleFetch) {
                    Image(systemName: friend.fetchFrom ? "arrow.down" : "arrow.down")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(friend.fetchFrom ? .white : .gray)
                        .frame(width: 32, height: 32)
                        .background(friend.fetchFrom ? Color.green : Color(.systemGray5))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
            } else {
                // Normal mode: show distance and time ago
                if let loc = friend.location {
                    VStack(alignment: .trailing, spacing: 2) {
                        if let dist = distance {
                            Text(formatDistance(meters: dist))
                                .font(.subheadline)
                                .foregroundStyle(.primary)
                        }
                        Text(formatAge(timestampMs: loc.timestamp))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(.horizontal)
        .contentShape(Rectangle())
        .padding(.vertical, 10)
        .onTapGesture {
            if !isEditMode {
                onTap()
            }
        }
    }
}

// MARK: - Sheet Header

struct SheetHeader<LeadingContent: View, TrailingContent: View>: View {
    let leadingContent: LeadingContent
    let trailingContent: TrailingContent

    init(
        @ViewBuilder leading: () -> LeadingContent,
        @ViewBuilder trailing: () -> TrailingContent
    ) {
        self.leadingContent = leading()
        self.trailingContent = trailing()
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center) {
                leadingContent
                Spacer()
                HStack(alignment: .center, spacing: 12) {
                    trailingContent
                }
            }
            .padding(.horizontal)
            .padding(.top, 12)
            .padding(.bottom, 8)

            Divider()
        }
    }
}

// MARK: - Confirm Add Friend Content

struct ConfirmAddFriendContent: View {
    let friend: ParsedFriendLink
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text("Add Friend?")
                .font(.title2)
                .fontWeight(.semibold)

            VStack(spacing: 8) {
                Text(friend.name)
                    .font(.headline)
                Text("from \(friend.server)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 16) {
                Button("Cancel") {
                    onCancel()
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)

                Button("Add") {
                    onConfirm()
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal)
        }
        .padding()
    }
}

// MARK: - Friends Sheet Content

struct FriendsSheetContent: View {
    @ObservedObject var identityStore: IdentityStore
    let friends: [Friend]
    let currentLocation: CLLocation?
    let isFetchingFriends: Bool
    @Binding var showingAddFriend: Bool
    @Binding var deepLinkFriendName: String?
    let onRefresh: () -> Void
    let onAddFriend: (String) -> Void
    let onShowProfile: () -> Void
    let onSelectFriend: (Friend) -> Void
    let onToggleShare: (Friend) -> Void
    let onToggleFetch: (Friend) -> Void
    let onDeleteFriend: (Friend) -> Void

    @State private var isEditMode = false

    var body: some View {
        VStack(spacing: 0) {
            SheetHeader {
                Text("Friends")
                    .font(.title2.bold())
                    .frame(height: 32)
            } trailing: {
                if isEditMode {
                    // Refresh button in edit mode
                    Button(action: onRefresh) {
                        Group {
                            if isFetchingFriends {
                                ProgressView()
                            } else {
                                Image(systemName: "arrow.clockwise")
                            }
                        }
                        .font(.system(size: 16, weight: .semibold))
                        .frame(width: 32, height: 32)
                        .background(Color(.systemGray5))
                        .clipShape(Circle())
                    }
                    .disabled(isFetchingFriends)

                    // Done editing
                    Button(action: { isEditMode = false }) {
                        Image(systemName: "checkmark")
                            .font(.system(size: 16, weight: .semibold))
                            .frame(width: 32, height: 32)
                            .background(Color(.systemGray5))
                            .clipShape(Circle())
                    }
                } else {
                    // Add friend
                    Button(action: { showingAddFriend = true }) {
                        Image(systemName: "plus")
                            .font(.system(size: 16, weight: .semibold))
                            .frame(width: 32, height: 32)
                            .background(Color(.systemGray5))
                            .clipShape(Circle())
                    }

                    // Edit mode toggle
                    Button(action: { isEditMode = true }) {
                        Image(systemName: "pencil")
                            .font(.system(size: 16, weight: .semibold))
                            .frame(width: 32, height: 32)
                            .background(Color(.systemGray5))
                            .clipShape(Circle())
                    }

                    // Profile
                    Button(action: onShowProfile) {
                        Image(systemName: "person.circle.fill")
                            .font(.system(size: 32))
                    }
                }
            }

            // Friends list
            if friends.isEmpty {
                ScrollView {
                    VStack(spacing: 8) {
                        Spacer()
                            .frame(height: 40)
                        Image(systemName: "person.2.slash")
                            .font(.system(size: 32))
                            .foregroundStyle(.secondary)
                        Text("No friends yet")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Button("Add Friend") { showingAddFriend = true }
                            .font(.subheadline)
                    }
                    .frame(maxWidth: .infinity)
                }
            } else {
                List {
                    ForEach(friends, id: \.pubkey) { friend in
                        FriendListRow(
                            friend: friend,
                            currentLocation: currentLocation,
                            isEditMode: isEditMode,
                            onTap: { onSelectFriend(friend) },
                            onToggleShare: { onToggleShare(friend) },
                            onToggleFetch: { onToggleFetch(friend) }
                        )
                        .listRowInsets(EdgeInsets())
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            if isEditMode {
                                Button(role: .destructive) {
                                    onDeleteFriend(friend)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .sheet(isPresented: $showingAddFriend, onDismiss: {
            deepLinkFriendName = nil  // Clear after use
        }) {
            AddFriendSheet(
                identityStore: identityStore,
                onAdd: { link in
                    onAddFriend(link)
                },
                onComplete: {
                    onRefresh()
                },
                initialFriendName: deepLinkFriendName
            )
        }
    }
}

// MARK: - Profile Sheet Content

struct ProfileSheetContent: View {
    @ObservedObject var identityStore: IdentityStore
    @ObservedObject var locationManager: LocationManager
    let syncService: LocationSyncService
    @Binding var isUploading: Bool
    @Binding var uploadMessage: String?
    @Binding var showServerLocation: Bool
    @Binding var serverLocation: Location?
    let serverVersion: String?
    let onBack: () -> Void

    @State private var showingMyLink = false
    @State private var showingEditName = false
    @State private var editedName: String = ""
    @State private var showingEditServer = false
    @State private var showingLicenses = false
    @State private var editedServerUrl: String = ""
    @State private var isValidatingServer = false
    @State private var serverValidationResult: String?

    var body: some View {
        let myColor = identityStore.myColor.map { Color(hex: $0) } ?? .gray

        VStack(spacing: 0) {
            SheetHeader {
                HStack(spacing: 10) {
                    // Avatar showing how others see you
                    Circle()
                        .fill(myColor)
                        .frame(width: 40, height: 40)
                        .overlay {
                            Text(String((identityStore.displayName ?? "M").prefix(1)))
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(.white)
                        }
                        .overlay {
                            Circle()
                                .stroke(.white, lineWidth: 2)
                        }

                    VStack(alignment: .leading, spacing: 2) {
                        Text(identityStore.displayName ?? "Me")
                            .font(.title3.bold())

                        if showServerLocation, let serverLoc = serverLocation {
                            // Show server location with time
                            if let city = CityDatabase.shared.findNearest(
                                lat: serverLoc.latitude,
                                lng: serverLoc.longitude
                            ) {
                                Text("\(city.displayName) · \(formatAge(timestampMs: serverLoc.timestamp))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            } else {
                                Text(formatAge(timestampMs: serverLoc.timestamp))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } else if let location = locationManager.currentLocation {
                            // Show GPS location (no time since it's always current)
                            if let city = CityDatabase.shared.findNearest(
                                lat: location.coordinate.latitude,
                                lng: location.coordinate.longitude
                            ) {
                                Text(city.displayName)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } else {
                            Text("Waiting for location...")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } trailing: {
                Button(action: uploadCurrentLocation) {
                    Group {
                        if isUploading {
                            ProgressView()
                        } else {
                            Image(systemName: "icloud.and.arrow.up")
                        }
                    }
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 32, height: 32)
                    .background(Color(.systemGray5))
                    .clipShape(Circle())
                }
                .disabled(isUploading)

                Button(action: { showingEditName = true }) {
                    Image(systemName: "pencil")
                        .font(.system(size: 16, weight: .semibold))
                        .frame(width: 32, height: 32)
                        .background(Color(.systemGray5))
                        .clipShape(Circle())
                }

                Button(action: onBack) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(.secondary)
                }
            }

            ScrollView {
                VStack(spacing: 20) {

                    if let message = uploadMessage {
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    // Server location toggle
                    Toggle(isOn: $showServerLocation) {
                        HStack {
                            Image(systemName: "cloud.fill")
                                .foregroundStyle(.orange)
                            Text("Show server location")
                        }
                    }
                    .padding(.horizontal)
                    .onChange(of: showServerLocation) { _, newValue in
                        if newValue { fetchServerLocation() }
                    }

                    // Auto-share toggle
                    Toggle(isOn: Binding(
                        get: { identityStore.autoShareEnabled },
                        set: { newValue in
                            if newValue {
                                // Check if we have Always permission
                                if BackgroundSyncManager.shared.hasAlwaysAuthorization {
                                    identityStore.setAutoShareEnabled(true)
                                    BackgroundSyncManager.shared.startMonitoringSignificantLocationChanges()
                                    BackgroundSyncManager.shared.scheduleBackgroundRefresh()
                                } else {
                                    // Request permission
                                    BackgroundSyncManager.shared.requestAlwaysAuthorization()
                                }
                            } else {
                                identityStore.setAutoShareEnabled(false)
                                BackgroundSyncManager.shared.stopMonitoringSignificantLocationChanges()
                            }
                        }
                    )) {
                        HStack {
                            Image(systemName: "location.fill")
                                .foregroundStyle(.blue)
                            VStack(alignment: .leading) {
                                Text("Share automatically")
                                Text(identityStore.autoShareEnabled ? "Sharing in background" : "Manual sharing only")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .padding(.horizontal)

                    // Share my link button
                    Button { showingMyLink = true } label: {
                        HStack {
                            Image(systemName: "qrcode")
                            Text("Share My Link")
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color.secondary.opacity(0.1))
                        .cornerRadius(10)
                    }
                    .padding(.horizontal)

                    Divider()
                        .padding(.vertical, 8)

                    // Server URL
                    Button { showingEditServer = true } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(serverVersion != nil ? "Server v\(serverVersion!)" : "Server")
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                Text(identityStore.serverUrl)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                        .padding(.horizontal)
                    }

                    // App and core version
                    Button { showingLicenses = true } label: {
                        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
                        Text("Coords iOS v\(appVersion) · Core \(getVersion())")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal)
                    .padding(.top, 8)
                }
                .padding(.bottom, 20)
            }
        }
        .sheet(isPresented: $showingLicenses) {
            LicensesSheet()
        }
        .sheet(isPresented: $showingMyLink) {
            MyLinkSheet(identityStore: identityStore)
        }
        .alert("Edit Name", isPresented: $showingEditName) {
            TextField("Display Name", text: $editedName)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                if !editedName.isEmpty {
                    identityStore.setDisplayName(editedName)
                }
            }
        } message: {
            Text("Enter your display name")
        }
        .sheet(isPresented: $showingEditServer) {
            serverEditSheet
        }
        .onAppear {
            editedName = identityStore.displayName ?? ""
            editedServerUrl = identityStore.serverUrl
        }
    }

    private var serverEditSheet: some View {
        NavigationStack {
            VStack(spacing: 20) {
                TextField("Server URL", text: $editedServerUrl)
                    .textFieldStyle(.roundedBorder)
                    #if os(iOS)
                    .autocapitalization(.none)
                    .keyboardType(.URL)
                    #endif

                if let result = serverValidationResult {
                    Text(result)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Button {
                    validateAndSaveServer()
                } label: {
                    HStack {
                        if isValidatingServer {
                            ProgressView()
                                .tint(.white)
                        }
                        Text("Validate & Save")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(.blue)
                    .foregroundStyle(.white)
                    .cornerRadius(10)
                }
                .disabled(isValidatingServer || editedServerUrl.isEmpty)

                Spacer()
            }
            .padding()
            .navigationTitle("Server")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showingEditServer = false }
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func validateAndSaveServer() {
        isValidatingServer = true
        serverValidationResult = nil

        Task {
            let result = await syncService.validateServer(url: editedServerUrl)

            await MainActor.run {
                isValidatingServer = false
                switch result {
                case .valid(_, let version):
                    serverValidationResult = "Valid (v\(version))"
                    identityStore.setServerUrl(editedServerUrl)
                    // Auto-close after short delay on success
                    Task {
                        try? await Task.sleep(nanoseconds: 1_000_000_000)
                        await MainActor.run { showingEditServer = false }
                    }
                case .invalidServer:
                    serverValidationResult = "Not a Coords server"
                case .networkError(let message):
                    serverValidationResult = "Error: \(message)"
                }
            }
        }
    }

    private func uploadCurrentLocation() {
        isUploading = true
        uploadMessage = nil

        Task {
            // Request fresh GPS location for explicit user action
            guard let location = await locationManager.requestLocation(.alwaysFresh) else {
                await MainActor.run {
                    isUploading = false
                    uploadMessage = "Unable to get location"
                }
                return
            }

            let result = await syncService.uploadLocation(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                accuracy: Float(location.horizontalAccuracy),
                timestamp: UInt64(location.timestamp.timeIntervalSince1970 * 1000)
            )

            await MainActor.run {
                isUploading = false
                switch result {
                case .success: uploadMessage = "Location uploaded"
                case .error(let message): uploadMessage = message
                }

                Task {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    await MainActor.run { uploadMessage = nil }
                }
            }
        }
    }

    private func fetchServerLocation() {
        Task {
            let result = await syncService.fetchSelfLocation()
            await MainActor.run {
                switch result {
                case .success(let locations):
                    if let loc = locations.first?.location {
                        serverLocation = loc
                    } else {
                        showServerLocation = false
                        uploadMessage = "No location on server"
                    }
                case .error(let message):
                    showServerLocation = false
                    uploadMessage = message
                }
            }
        }
    }
}

// MARK: - Friend Detail Sheet Content

struct FriendDetailSheetContent: View {
    let friend: Friend
    let onBack: () -> Void
    let onToggleShare: () -> Void
    let onToggleFetch: () -> Void
    let onRemove: () -> Void
    let onEditName: () -> Void

    @State private var showRemoveConfirmation = false

    private var friendColor: Color {
        Color(hex: friend.color)
    }

    var body: some View {
        VStack(spacing: 0) {
            SheetHeader {
                HStack(spacing: 10) {
                    // Avatar with friend's color
                    Circle()
                        .fill(friendColor)
                        .frame(width: 40, height: 40)
                        .overlay {
                            Text(String(friend.name.prefix(1)))
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(.white)
                        }
                        .overlay {
                            Circle()
                                .stroke(.white, lineWidth: 2)
                        }

                    VStack(alignment: .leading, spacing: 2) {
                        Text(friend.name)
                            .font(.title3.bold())

                        if let loc = friend.location {
                            if let city = CityDatabase.shared.findNearest(lat: loc.latitude, lng: loc.longitude) {
                                Text("\(city.displayName) · \(formatAge(timestampMs: loc.timestamp))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            } else {
                                Text(formatAge(timestampMs: loc.timestamp))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } else {
                            Text("No location available")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } trailing: {
                Button(action: onEditName) {
                    Image(systemName: "pencil")
                        .font(.system(size: 16, weight: .semibold))
                        .frame(width: 32, height: 32)
                        .background(Color(.systemGray5))
                        .clipShape(Circle())
                }

                Button(action: onBack) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(.secondary)
                }
            }

            ScrollView {
                VStack(spacing: 16) {
                    // Open in Maps button
                    if let loc = friend.location {
                        Button {
                            let coordinate = "\(loc.latitude),\(loc.longitude)"
                            if let url = URL(string: "http://maps.apple.com/?q=\(friend.name)&ll=\(coordinate)") {
                                UIApplication.shared.open(url)
                            }
                        } label: {
                            HStack {
                                Image(systemName: "map")
                                Text("Open in Maps")
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color.secondary.opacity(0.1))
                            .cornerRadius(10)
                        }
                    }

                    Divider()

                    // Share toggle
                    Toggle(isOn: Binding(
                        get: { friend.shareWith },
                        set: { _ in onToggleShare() }
                    )) {
                        HStack {
                            Image(systemName: "arrow.up")
                                .foregroundStyle(.blue)
                            Text("Share with \(friend.name)")
                        }
                    }

                    // Fetch toggle
                    Toggle(isOn: Binding(
                        get: { friend.fetchFrom },
                        set: { _ in onToggleFetch() }
                    )) {
                        HStack {
                            Image(systemName: "arrow.down")
                                .foregroundStyle(.green)
                            Text("See \(friend.name)")
                        }
                    }

                    // Remove button - only when both toggles are off
                    if !friend.shareWith && !friend.fetchFrom {
                        Divider()

                        Button(role: .destructive) {
                            showRemoveConfirmation = true
                        } label: {
                            HStack {
                                Image(systemName: "person.badge.minus")
                                Text("Remove Friend")
                            }
                        }
                    }
                }
                .padding()
            }
        }
        .confirmationDialog("Remove \(friend.name)?", isPresented: $showRemoveConfirmation, titleVisibility: .visible) {
            Button("Remove", role: .destructive, action: onRemove)
            Button("Cancel", role: .cancel) {}
        }
    }
}

struct LicensesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var showingDependencies = false

    private var totalPackages: Int {
        getLicenses().reduce(0) { $0 + $1.packages.count }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 16) {
                        // AGPL Logo
                        Image("agpl-logo")
                            .resizable()
                            .scaledToFit()
                            .frame(height: 80)

                        VStack(spacing: 4) {
                            Text("Coords for iOS")
                                .font(.headline)
                            Text("v\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0") · Core \(getVersion())")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        VStack(spacing: 4) {
                            Text("© 2026 Allison Bentley")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Text("Made with ❤️ for Helen")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)

                    // Dependencies row
                    NavigationLink {
                        DependenciesListView()
                    } label: {
                        HStack {
                            Text("Dependencies")
                            Spacer()
                            Text("\(totalPackages)")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

struct DependenciesListView: View {
    @State private var expandedLicenses: Set<String> = []

    var body: some View {
        List {
            let licenses = getLicenses()
            ForEach(licenses, id: \.id) { group in
                DisclosureGroup(
                    isExpanded: Binding(
                        get: { expandedLicenses.contains(group.id) },
                        set: { isExpanded in
                            if isExpanded {
                                expandedLicenses.insert(group.id)
                            } else {
                                expandedLicenses.remove(group.id)
                            }
                        }
                    )
                ) {
                    VStack(alignment: .leading, spacing: 12) {
                        // Package list
                        Text(group.packages.joined(separator: ", "))
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Divider()

                        // License text
                        Text(group.text)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(group.name)
                            .font(.body)
                        Text("\(group.packages.count) packages")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Dependencies")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    MainView(identityStore: IdentityStore(), pendingFriendLink: .constant(nil))
}
