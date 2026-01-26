import SwiftUI
import CoreLocation

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
                            if let city = findNearestCityInRegion(
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
                            if let city = findNearestCityInRegion(
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
                altitude: location.altitude,
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
