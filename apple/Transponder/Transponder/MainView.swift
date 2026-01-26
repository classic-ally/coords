import SwiftUI
import MapKit
import CoreLocation
import Combine

struct MainView: View {
    @ObservedObject var identityStore: IdentityStore
    var delayLocationRequest: Bool = false
    var suppressSheet: Bool = false
    @Binding var pendingFriendLink: String?

    @ObservedObject private var locationManager = LocationManager.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var currentScenePhase: ScenePhase = .active

    @State private var cameraPosition: MapCameraPosition = .automatic
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

                #if DEBUG
                print("Timer fired: autoShare=\(identityStore.autoShareEnabled), recipients=\(getShareRecipients().count), scenePhase=\(currentScenePhase)")
                #endif

                // Skip fetch in background - will fetch when app becomes active
                if currentScenePhase == .active {
                    fetchFriendsIfNeeded(force: true)
                }

                // Periodic upload if auto-share enabled
                // Use cached location in background to avoid GPS wake
                if identityStore.autoShareEnabled && !getShareRecipients().isEmpty {
                    let freshness: LocationFreshness = currentScenePhase == .active ? .alwaysFresh : .cachedOkay
                    uploadCurrentLocationBackground(freshness)
                }
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            currentScenePhase = newPhase
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
        case .addFriend(let step):
            switch step {
            case .showQR: return [.large]
            case .scanQR: return [.large]
            case .linkEntry: return [.large]
            }
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
                onRefresh: { fetchFriendsIfNeeded(force: true) },
                onStartAddFriend: {
                    sheetContent = .addFriend(.showQR(role: .showFirst, isSecondStep: false, addedName: nil))
                    selectedDetent = .large
                },
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
                    handleDeepLink(friend)
                },
                onCancel: {
                    sheetContent = .friends
                }
            )

        case .addFriend(let step):
            AddFriendContent(
                identityStore: identityStore,
                step: step,
                onNavigate: { newStep in
                    sheetContent = .addFriend(newStep)
                },
                onAddFriend: { link, completion in
                    addFriendFromLink(link) { result in
                        let alreadyFriends = if case .alreadyFriends = result { true } else { false }
                        completion(alreadyFriends)
                    }
                },
                onComplete: { pubkey in
                    refreshFriends()
                    if let pubkey = pubkey,
                       let friend = friends.first(where: { $0.pubkey == pubkey }) {
                        selectAndCenterOnFriend(friend)
                    } else {
                        sheetContent = .friends
                        selectedDetent = .fraction(0.4)
                    }
                },
                onCancel: {
                    sheetContent = .friends
                    selectedDetent = .fraction(0.4)
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

    /// Result of adding a friend and checking if they've added us back
    enum AddFriendCheckResult {
        case alreadyFriends(Friend)  // They added us - we can decrypt their location
        case needsReciprocal(String)  // They haven't added us yet - name for display
        case failed(String)  // Error message
    }

    /// Add a friend from a link, upload our location, and check if they've already added us
    /// Returns whether the friend has already added us (we can decrypt their location)
    private func addFriendAndCheckReciprocal(_ parsed: ParsedFriendLink) async -> AddFriendCheckResult {
        // 1. Add friend first (required before we can fetch)
        do {
            try addFriend(
                pubkey: parsed.pubkey,
                server: parsed.server,
                name: parsed.name,
                shareWith: true,
                fetchFrom: true
            )
        } catch {
            return .failed("Failed to add friend: \(error.localizedDescription)")
        }

        await MainActor.run { refreshFriends() }

        // 2. Fire off location upload in background (don't wait for it)
        Task {
            if let location = await locationManager.requestLocation(.alwaysFresh) {
                _ = await syncService.uploadLocation(
                    latitude: location.coordinate.latitude,
                    longitude: location.coordinate.longitude,
                    altitude: location.altitude,
                    accuracy: Float(location.horizontalAccuracy),
                    timestamp: UInt64(location.timestamp.timeIntervalSince1970 * 1000)
                )
            }
        }

        // 3. Fetch their location immediately to check if they've added us
        guard let friend = await MainActor.run(body: { friends.first(where: { $0.pubkey == parsed.pubkey }) }) else {
            return .failed("Friend not found after adding")
        }

        let fetchResult = await syncService.fetchFriendLocations(friends: [friend])

        // 4. Check if we could decrypt their location
        if case .success(let locations) = fetchResult,
           let fetched = locations.first,
           let decryptedLocation = fetched.location {
            // Can decrypt - they've added us!
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            try? updateFriendLocation(
                pubkey: parsed.pubkey,
                location: decryptedLocation,
                fetchedAt: now
            )
            await MainActor.run { refreshFriends() }

            if let updatedFriend = await MainActor.run(body: { friends.first(where: { $0.pubkey == parsed.pubkey }) }) {
                return .alreadyFriends(updatedFriend)
            }
        }

        // Can't decrypt - they haven't added us yet
        return .needsReciprocal(parsed.name)
    }

    private func addFriendFromLink(_ link: String, completion: ((AddFriendCheckResult) -> Void)? = nil) {
        guard let parsed = try? parseFriendLink(url: link) else {
            uploadMessage = "Invalid friend link"
            completion?(.failed("Invalid friend link"))
            return
        }

        Task {
            let result = await addFriendAndCheckReciprocal(parsed)
            await MainActor.run {
                if case .failed(let message) = result {
                    uploadMessage = message
                }
                completion?(result)
            }
        }
    }

    private func handleDeepLink(_ parsed: ParsedFriendLink) {
        Task {
            let result = await addFriendAndCheckReciprocal(parsed)

            await MainActor.run {
                switch result {
                case .alreadyFriends(let friend):
                    selectAndCenterOnFriend(friend)
                case .needsReciprocal(let name):
                    sheetContent = .addFriend(.showQR(role: .scanFirst, isSecondStep: true, addedName: name))
                    selectedDetent = .large
                case .failed(let message):
                    uploadMessage = message
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

            #if DEBUG
            let age = Date().timeIntervalSince(location.timestamp)
            let wasCached = freshness == .cachedOkay && age < 5 * 60
            print("Timer upload: freshness=\(freshness), age=\(String(format: "%.1f", age))s, wasCached=\(wasCached)")
            #endif

            _ = await syncService.uploadLocation(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                altitude: location.altitude,
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

#Preview {
    MainView(identityStore: IdentityStore(), pendingFriendLink: .constant(nil))
}
