import SwiftUI
import CoreLocation
import Combine

// MARK: - Onboarding Flow

struct OnboardingView: View {
    @ObservedObject var identityStore: IdentityStore
    var onComplete: () -> Void

    @State private var navigationPath = NavigationPath()
    @State private var hasAlwaysPermission = false

    var body: some View {
        NavigationStack(path: $navigationPath) {
            WelcomePage(onNext: {
                navigationPath.append(OnboardingDestination.location)
            })
            .navigationDestination(for: OnboardingDestination.self) { destination in
                switch destination {
                case .location:
                    LocationPermissionPage(
                        onPermissionResult: { _, hasAlways in
                            hasAlwaysPermission = hasAlways
                            navigationPath.append(OnboardingDestination.identity)
                        },
                        onSkip: {
                            hasAlwaysPermission = false
                            navigationPath.append(OnboardingDestination.identity)
                        }
                    )
                case .identity:
                    IdentityPage(
                        identityStore: identityStore,
                        hasAlwaysPermission: hasAlwaysPermission,
                        onComplete: onComplete
                    )
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.ultraThinMaterial)
    }
}

private enum OnboardingDestination: Hashable {
    case location
    case identity
}

// MARK: - Welcome Page

private struct WelcomePage: View {
    let onNext: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            // Header area
            VStack(spacing: 12) {
                AppIconView()
                    .frame(width: 80, height: 80)

                Text("Coords")
                    .font(.largeTitle)
                    .fontWeight(.bold)

                Text("Share your location with friends and family")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }

            Spacer()
                .frame(height: 48)

            // Feature highlights - Apple style
            VStack(spacing: 24) {
                FeatureItem(
                    icon: "lock.fill",
                    iconColor: .blue,
                    title: "Secure",
                    detail: "Only your friends can see your location"
                )

                FeatureItem(
                    icon: "iphone.and.arrow.forward",
                    iconColor: .green,
                    title: "Universal",
                    detail: "Works across Android and iOS"
                )

                FeatureItem(
                    icon: "chevron.left.forwardslash.chevron.right",
                    iconColor: .orange,
                    title: "Open",
                    detail: "Free forever & community-built"
                )
            }
            .padding(.horizontal, 24)

            Spacer()

            // Button pinned at bottom
            Button(action: onNext) {
                Text("Continue")
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
        }
    }
}

private struct FeatureItem: View {
    let icon: String
    let iconColor: Color
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            // Liquid Glass style - colored icon on tinted background
            Image(systemName: icon)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(iconColor)
                .frame(width: 44, height: 44)
                .background(iconColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.body)
                    .fontWeight(.semibold)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
    }
}

// MARK: - Location Permission Page

private struct LocationPermissionPage: View {
    let onPermissionResult: (Bool, Bool) -> Void
    let onSkip: () -> Void

    @StateObject private var permissionManager = LocationPermissionManager()
    @State private var hasRequestedAlways = false

    var body: some View {
        VStack(spacing: 32) {
            Spacer()

            // Header
            VStack(spacing: 16) {
                Image(systemName: "location.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.blue)

                Text("Location Access")
                    .font(.title)
                    .fontWeight(.bold)

                Text("Coords needs location access to share where you are with friends")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 32)

            Spacer()

            // Permission explanations
            VStack(alignment: .leading, spacing: 16) {
                PermissionExplainer(
                    title: "While using the app",
                    detail: "Share your location manually when you choose"
                )
                PermissionExplainer(
                    title: "Always allow",
                    detail: "Share automatically in the background, even when the app is closed"
                )
                PermissionExplainer(
                    title: "Don't allow",
                    detail: "You can still see friends' locations, but can't share yours"
                )
            }
            .padding(.horizontal, 32)

            Spacer()

            // Permission buttons
            VStack(spacing: 12) {
                switch permissionManager.authorizationStatus {
                case .notDetermined:
                    Button(action: {
                        permissionManager.requestWhenInUsePermission()
                    }) {
                        Text("Enable Location")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)

                case .authorizedWhenInUse:
                    // Try to upgrade to Always, then proceed after a short delay
                    Text("Location access granted!")
                        .font(.subheadline)
                        .foregroundStyle(.green)

                    ProgressView()
                        .onAppear {
                            guard !hasRequestedAlways else { return }
                            hasRequestedAlways = true
                            // Request Always - will show prompt if "While Using" was selected,
                            // or do nothing if "Allow Once" was selected
                            permissionManager.requestAlwaysPermission()
                            // Proceed after a short delay to allow the dialog to appear
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                                // If still at WhenInUse, proceed without Always
                                if permissionManager.authorizationStatus == .authorizedWhenInUse {
                                    onPermissionResult(true, false)
                                }
                            }
                        }

                case .authorizedAlways:
                    Text("Background location enabled!")
                        .font(.subheadline)
                        .foregroundStyle(.green)

                    Button(action: {
                        onPermissionResult(true, true)
                    }) {
                        Text("Continue")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)

                case .denied, .restricted:
                    Text("Location access denied")
                        .font(.subheadline)
                        .foregroundStyle(.red)

                    Button(action: {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }) {
                        Text("Open Settings")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)

                    Button(action: {
                        onPermissionResult(false, false)
                    }) {
                        Text("Continue without location")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)

                @unknown default:
                    Button(action: {
                        onPermissionResult(false, false)
                    }) {
                        Text("Continue")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                }

                if permissionManager.authorizationStatus == .notDetermined {
                    Button(action: onSkip) {
                        Text("Skip — I'll just see friends' locations")
                    }
                    .font(.subheadline)
                }
            }
            .padding(.horizontal, 32)

            Spacer()
        }
        .padding()
        .onChange(of: permissionManager.authorizationStatus) { _, newStatus in
            // If upgraded to Always, proceed immediately
            if newStatus == .authorizedAlways {
                onPermissionResult(true, true)
            }
        }
    }
}

private struct PermissionExplainer: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.subheadline)
                .fontWeight(.medium)
            Text(detail)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Location Permission Manager

private class LocationPermissionManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()

    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined

    override init() {
        super.init()
        manager.delegate = self
        authorizationStatus = manager.authorizationStatus
    }

    func requestWhenInUsePermission() {
        manager.requestWhenInUseAuthorization()
    }

    func requestAlwaysPermission() {
        manager.requestAlwaysAuthorization()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        DispatchQueue.main.async {
            self.authorizationStatus = manager.authorizationStatus
        }
    }
}

// MARK: - Identity Page

private struct IdentityPage: View {
    @ObservedObject var identityStore: IdentityStore
    let hasAlwaysPermission: Bool
    let onComplete: () -> Void

    @State private var displayName = ""
    @State private var serverUrl = "https://coord.is"
    @State private var showServerSheet = false
    @State private var isCreatingIdentity = false

    private var isCustomServer: Bool {
        serverUrl != "https://coord.is"
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            // Main content
            VStack(spacing: 32) {
                Spacer()

                // Header
                VStack(spacing: 8) {
                    Text("Set Up Your Profile")
                        .font(.title)
                        .fontWeight(.bold)

                    Text("What should your friends call you?")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                // Name field - corner radius matches large button style (~14pt)
                TextField("Your name", text: $displayName)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .background(.thinMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    #if os(iOS)
                    .textContentType(.name)
                    .autocapitalization(.words)
                    #endif
                    .padding(.horizontal, 32)

                Spacer()

                // Finish button
                Button(action: createIdentityAndContinue) {
                    if isCreatingIdentity {
                        ProgressView()
                            .tint(.white)
                    } else {
                        Text("Finish")
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(displayName.trimmingCharacters(in: .whitespaces).isEmpty || isCreatingIdentity)
                .padding(.horizontal, 32)

                Spacer()
            }
            .padding()

            // Top right server button
            Button(action: { showServerSheet = true }) {
                HStack(spacing: 4) {
                    Image(systemName: "server.rack")
                        .font(.system(size: 14))
                    Text(isCustomServer ? "Custom server" : "Using your own server?")
                        .font(.caption)
                }
                .foregroundStyle(.secondary)
            }
            .padding()
        }
        .sheet(isPresented: $showServerSheet) {
            ServerConfigSheet(
                serverUrl: $serverUrl,
                onDismiss: { showServerSheet = false }
            )
        }
    }

    private func createIdentityAndContinue() {
        isCreatingIdentity = true

        Task {
            // Generate identity using Rust core (can be off main thread)
            let identity = generateIdentity()

            // Save to keychain and update state on MainActor to ensure proper ordering
            await MainActor.run {
                identityStore.saveIdentity(identity)
                identityStore.setDisplayName(displayName.trimmingCharacters(in: .whitespaces))
                identityStore.setServerUrl(serverUrl.trimmingCharacters(in: .whitespaces))

                // Auto-enable background sharing if we have Always permission
                if hasAlwaysPermission {
                    identityStore.setAutoShareEnabled(true)
                    BackgroundSyncManager.shared.startMonitoringSignificantLocationChanges()
                    BackgroundSyncManager.shared.scheduleBackgroundRefresh()
                }

                isCreatingIdentity = false
                onComplete()
            }
        }
    }
}

// MARK: - Server Config Sheet

private struct ServerConfigSheet: View {
    @Binding var serverUrl: String
    let onDismiss: () -> Void

    @State private var urlInput: String = ""
    @State private var validationState: ServerValidationState = .idle
    @State private var validationTask: Task<Void, Never>?

    private let syncService = LocationSyncService()

    enum ServerValidationState {
        case idle
        case validating
        case valid(version: String)
        case invalid(String)
    }

    private var isValid: Bool {
        if case .valid = validationState { return true }
        return false
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                TextField("Server URL", text: $urlInput)
                    .textFieldStyle(.roundedBorder)
                    #if os(iOS)
                    .autocapitalization(.none)
                    .keyboardType(.URL)
                    #endif
                    .onChange(of: urlInput) { _, newValue in
                        validateServerDebounced(url: newValue)
                    }

                // Validation status
                HStack(spacing: 4) {
                    switch validationState {
                    case .idle:
                        EmptyView()
                    case .validating:
                        ProgressView()
                            .scaleEffect(0.7)
                        Text("Checking server...")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    case .valid(let version):
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        Text("coords server v\(version)")
                            .font(.caption)
                            .foregroundStyle(.green)
                    case .invalid(let message):
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.red)
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
                .frame(height: 20)

                Spacer()
            }
            .padding()
            .navigationTitle("Custom Server")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        onDismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        serverUrl = urlInput.trimmingCharacters(in: .whitespaces)
                        onDismiss()
                    }
                    .disabled(!isValid)
                }
            }
            .onAppear {
                urlInput = serverUrl
                validateServerDebounced(url: serverUrl)
            }
        }
        .presentationDetents([.medium])
    }

    private func validateServerDebounced(url: String) {
        validationTask?.cancel()

        let trimmed = url.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            validationState = .idle
            return
        }

        validationState = .validating

        validationTask = Task {
            try? await Task.sleep(nanoseconds: 500_000_000) // 500ms debounce

            guard !Task.isCancelled else { return }

            let result = await syncService.validateServer(url: trimmed)

            guard !Task.isCancelled else { return }

            await MainActor.run {
                switch result {
                case .valid(_, let version):
                    validationState = .valid(version: version)
                case .invalidServer:
                    validationState = .invalid("Not a Coords server")
                case .networkError(let message):
                    validationState = .invalid(message)
                }
            }
        }
    }
}

// MARK: - App Icon View

/// Loads the app icon from the bundle - automatically uses the correct icon
private struct AppIconView: View {
    var body: some View {
        #if os(iOS)
        if let iconName = Bundle.main.object(forInfoDictionaryKey: "CFBundleIcons") as? [String: Any],
           let primaryIcon = iconName["CFBundlePrimaryIcon"] as? [String: Any],
           let iconFiles = primaryIcon["CFBundleIconFiles"] as? [String],
           let lastIcon = iconFiles.last,
           let uiImage = UIImage(named: lastIcon) {
            Image(uiImage: uiImage)
                .resizable()
                .scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        } else {
            // Fallback to asset catalog
            Image("CoordsLogo")
                .resizable()
                .scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        #else
        // macOS fallback
        Image("CoordsLogo")
            .resizable()
            .scaledToFit()
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        #endif
    }
}

#Preview {
    OnboardingView(identityStore: IdentityStore()) {
        print("Onboarding complete")
    }
}
