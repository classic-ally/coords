import SwiftUI
import CoreImage.CIFilterBuiltins

struct FriendsView: View {
    @ObservedObject var identityStore: IdentityStore
    @Environment(\.dismiss) private var dismiss

    @State private var friends: [Friend] = []
    @State private var showingAddFriend = false
    @State private var showingMyLink = false
    @State private var pendingLink: String = ""
    @State private var errorMessage: String?

    @StateObject private var locationManager = LocationManager()
    private let syncService = LocationSyncService()

    var body: some View {
        NavigationStack {
            List {
                if friends.isEmpty {
                    Section {
                        Text("No friends yet. Tap + to add a friend.")
                            .foregroundStyle(.secondary)
                    }
                } else {
                    ForEach(friends, id: \.pubkey) { friend in
                        FriendRow(friend: friend, onUpdate: refreshFriends)
                    }
                    .onDelete(perform: deleteFriends)
                }
            }
            .navigationTitle("Friends")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            showingMyLink = true
                        } label: {
                            Label("Share My Link", systemImage: "qrcode")
                        }
                        Button {
                            showingAddFriend = true
                        } label: {
                            Label("Add Friend", systemImage: "person.badge.plus")
                        }
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showingMyLink) {
                MyLinkSheet(identityStore: identityStore)
            }
            .sheet(isPresented: $showingAddFriend) {
                AddFriendSheet(
                    identityStore: identityStore,
                    onAdd: { link in
                        addFriendFromLink(link)
                    },
                    onComplete: {
                        refreshFriends()
                        // Fetch friend locations in background - they may have uploaded when adding us
                        Task {
                            _ = await syncService.fetchTrackedFriends()
                            await MainActor.run {
                                refreshFriends()
                            }
                        }
                    }
                )
            }
            .alert("Error", isPresented: .constant(errorMessage != nil)) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
            .onAppear {
                refreshFriends()
            }
        }
    }

    private func refreshFriends() {
        friends = listFriends()
    }

    private func deleteFriends(at offsets: IndexSet) {
        for index in offsets {
            let friend = friends[index]
            do {
                try removeFriend(pubkey: friend.pubkey)
            } catch {
                errorMessage = "Failed to remove friend: \(error.localizedDescription)"
            }
        }
        refreshFriends()
    }

    private func addFriendFromLink(_ link: String) {
        guard let parsed = try? parseFriendLink(url: link) else {
            errorMessage = "Invalid friend link"
            return
        }

        Task {
            let result = await syncService.addFriendAndUploadLocation(
                pubkey: parsed.pubkey,
                server: parsed.server,
                name: parsed.name,
                location: locationManager.currentLocation
            )
            await MainActor.run {
                switch result {
                case .addFriendFailed(let message):
                    errorMessage = "Failed to add friend: \(message)"
                case .successUploadFailed(let message):
                    print("Friend added but location upload failed: \(message)")
                    refreshFriends()
                case .success, .successWithUpload:
                    refreshFriends()
                }
            }
        }
    }
}

// MARK: - Friend Row

struct FriendRow: View {
    let friend: Friend
    let onUpdate: () -> Void

    @State private var shareWith: Bool
    @State private var fetchFrom: Bool

    init(friend: Friend, onUpdate: @escaping () -> Void) {
        self.friend = friend
        self.onUpdate = onUpdate
        _shareWith = State(initialValue: friend.shareWith)
        _fetchFrom = State(initialValue: friend.fetchFrom)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(friend.name)
                    .font(.headline)
                Spacer()
                if let location = friend.location {
                    Text(formatCoordinates(location))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            HStack(spacing: 16) {
                Toggle(isOn: $shareWith) {
                    Label("Share", systemImage: "arrow.up.circle")
                        .font(.caption)
                }
                .toggleStyle(.button)
                .buttonStyle(.bordered)
                .tint(shareWith ? .blue : .gray)
                .onChange(of: shareWith) { _, newValue in
                    updateFriendSettings(shareWith: newValue, fetchFrom: nil)
                }

                Toggle(isOn: $fetchFrom) {
                    Label("See", systemImage: "arrow.down.circle")
                        .font(.caption)
                }
                .toggleStyle(.button)
                .buttonStyle(.bordered)
                .tint(fetchFrom ? .green : .gray)
                .onChange(of: fetchFrom) { _, newValue in
                    updateFriendSettings(shareWith: nil, fetchFrom: newValue)
                }

                Spacer()

                Text(serverHost)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 4)
    }

    private var serverHost: String {
        friend.server
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
    }

    private func formatCoordinates(_ location: Location) -> String {
        String(format: "%.2f, %.2f", location.latitude, location.longitude)
    }

    private func updateFriendSettings(shareWith: Bool?, fetchFrom: Bool?) {
        do {
            try updateFriend(
                pubkey: friend.pubkey,
                shareWith: shareWith,
                fetchFrom: fetchFrom,
                name: nil
            )
            onUpdate()
        } catch {
            print("Failed to update friend: \(error)")
        }
    }
}

// MARK: - My Link Sheet

struct MyLinkSheet: View {
    @ObservedObject var identityStore: IdentityStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                QRCodeView(identityStore: identityStore)

                Text("Scan this QR code to add me")
                    .font(.headline)

                Button {
                    copyLinkToClipboard()
                } label: {
                    Label("Copy Link", systemImage: "doc.on.doc")
                }
                .buttonStyle(.bordered)

                #if os(iOS)
                ShareLink(item: identityStore.friendLink) {
                    Label("Share Link", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.borderedProminent)
                #endif
            }
            .padding()
            .navigationTitle("My Friend Link")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func copyLinkToClipboard() {
        let link = identityStore.friendLink
        #if os(iOS)
        UIPasteboard.general.string = link
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(link, forType: .string)
        #endif
    }
}

// MARK: - Add Friend Sheet

enum ExchangeRole: String, CaseIterable {
    case showFirst = "I show first"
    case scanFirst = "I scan first"
}

struct AddFriendSheet: View {
    @ObservedObject var identityStore: IdentityStore
    let onAdd: (String) -> Void
    let onComplete: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var role: ExchangeRole
    @State private var currentStep: Int
    @State private var addedFriendName: String?
    @State private var showingLinkEntry = false
    @State private var linkText: String = ""

    init(identityStore: IdentityStore, onAdd: @escaping (String) -> Void, onComplete: @escaping () -> Void, initialFriendName: String? = nil) {
        self.identityStore = identityStore
        self.onAdd = onAdd
        self.onComplete = onComplete

        // If opened via deep link, start at "show your QR" step (step 1 of scanFirst)
        if let name = initialFriendName {
            _role = State(initialValue: .scanFirst)
            _currentStep = State(initialValue: 1)
            _addedFriendName = State(initialValue: name)
        } else {
            _role = State(initialValue: .showFirst)
            _currentStep = State(initialValue: 0)
            _addedFriendName = State(initialValue: nil)
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                #if os(iOS)
                // Role selector
                Picker("Who goes first?", selection: $role) {
                    ForEach(ExchangeRole.allCases, id: \.self) { r in
                        Text(r.rawValue).tag(r)
                    }
                }
                .pickerStyle(.segmented)
                .padding()
                .disabled(currentStep > 0) // Lock once started

                // Step indicator
                HStack(spacing: 8) {
                    ForEach(0..<2) { step in
                        Circle()
                            .fill(step <= currentStep ? Color.accentColor : Color.secondary.opacity(0.3))
                            .frame(width: 8, height: 8)
                    }
                }
                .padding(.bottom, 8)

                // Content based on role and step
                TabView(selection: $currentStep) {
                    firstStepView
                        .tag(0)
                    secondStepView
                        .tag(1)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .animation(.easeInOut, value: currentStep)
                #else
                // macOS fallback - link entry only
                linkEntryView
                #endif
            }
            .navigationTitle(navigationTitle)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            #if os(iOS)
            .sheet(isPresented: $showingLinkEntry) {
                linkEntrySheet
            }
            .interactiveDismissDisabled(currentStep > 0)
            #endif
        }
    }

    private var navigationTitle: String {
        switch (role, currentStep) {
        case (.showFirst, 0): return "Show Your QR"
        case (.showFirst, 1): return "Scan Their QR"
        case (.scanFirst, 0): return "Scan Their QR"
        case (.scanFirst, 1): return "Show Your QR"
        default: return "Add Friend"
        }
    }

    #if os(iOS)
    @ViewBuilder
    private var firstStepView: some View {
        if role == .showFirst {
            showQRView(isFirstStep: true)
        } else {
            scanQRView(isFirstStep: true)
        }
    }

    @ViewBuilder
    private var secondStepView: some View {
        if role == .showFirst {
            scanQRView(isFirstStep: false)
        } else {
            showQRView(isFirstStep: false)
        }
    }

    private func showQRView(isFirstStep: Bool) -> some View {
        VStack(spacing: 24) {
            Spacer()

            QRCodeView(identityStore: identityStore)

            if isFirstStep {
                Text("Have your friend scan this QR code")
                    .font(.headline)
                    .multilineTextAlignment(.center)

                Text("Once they've scanned it, tap Next to scan theirs")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Button {
                    withAnimation {
                        currentStep = 1
                    }
                } label: {
                    Text("Next")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .padding(.horizontal, 32)

                ShareLink(item: identityStore.friendLink) {
                    Label("Share Link Instead", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .padding(.horizontal, 32)
            } else {
                if let name = addedFriendName {
                    Text("Added \(name)!")
                        .font(.headline)
                        .foregroundStyle(.green)
                }

                Text("Now have your friend scan this to complete the exchange")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Button {
                    onComplete()
                    dismiss()
                } label: {
                    Text("Done")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .padding(.horizontal, 32)

                ShareLink(item: identityStore.friendLink) {
                    Label("Share Link Instead", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .padding(.horizontal, 32)
            }

            Spacer()
        }
        .padding()
    }

    private func scanQRView(isFirstStep: Bool) -> some View {
        ZStack {
            QRScannerView { code in
                onAdd(code)
                // Extract friend name from link for display
                if let parsed = try? parseFriendLink(url: code) {
                    addedFriendName = parsed.name
                }
                if isFirstStep {
                    // "I scan first" mode: advance to show your QR
                    withAnimation {
                        currentStep = 1
                    }
                } else {
                    // "I show first" mode: scanning is the last step, complete the flow
                    onComplete()
                    dismiss()
                }
            }
            .ignoresSafeArea()

            VStack {
                Spacer()

                if isFirstStep {
                    Text("Point camera at your friend's QR code")
                        .font(.subheadline)
                        .padding(.vertical, 8)
                        .padding(.horizontal, 16)
                        .background(.regularMaterial)
                        .cornerRadius(8)
                }

                Button {
                    showingLinkEntry = true
                } label: {
                    Label("Add with Link", systemImage: "link")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(.regularMaterial)
                        .cornerRadius(10)
                }
                .padding(.horizontal)
                .padding(.bottom, 8)
            }
        }
    }

    private var linkEntrySheet: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Paste a friend's Coords link:")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                TextField("coord://...", text: $linkText)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
                    .keyboardType(.URL)

                Button("Add Friend") {
                    onAdd(linkText)
                    if let parsed = try? parseFriendLink(url: linkText) {
                        addedFriendName = parsed.name
                    }
                    showingLinkEntry = false
                    withAnimation {
                        currentStep = 1
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(linkText.isEmpty)

                Spacer()
            }
            .padding()
            .navigationTitle("Add with Link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showingLinkEntry = false }
                }
            }
        }
        .presentationDetents([.medium])
    }
    #endif

    private var linkEntryView: some View {
        VStack(spacing: 20) {
            Text("Paste a friend's Coords link:")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            TextField("coord://...", text: $linkText)
                .textFieldStyle(.roundedBorder)

            Button("Add Friend") {
                onAdd(linkText)
                dismiss()
            }
            .buttonStyle(.borderedProminent)
            .disabled(linkText.isEmpty)

            Spacer()
        }
        .padding()
    }
}

// MARK: - QR Code View (reusable)

struct QRCodeView: View {
    @ObservedObject var identityStore: IdentityStore
    @State private var qrImage: Image?

    var body: some View {
        Group {
            if let qrImage = qrImage {
                qrImage
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 250, height: 250)
                    .padding()
                    .background(.white)
                    .cornerRadius(12)
            } else {
                ProgressView()
                    .frame(width: 250, height: 250)
            }
        }
        .onAppear {
            generateQRCode()
        }
    }

    private func generateQRCode() {
        guard let identity = identityStore.getIdentity() else { return }
        let name = identityStore.displayName ?? "Friend"
        let server = identityStore.serverUrl
        let link = generateFriendLink(identity: identity, server: server, name: name)

        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(link.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage,
              let cgImage = context.createCGImage(outputImage, from: outputImage.extent) else {
            return
        }

        #if os(iOS)
        qrImage = Image(uiImage: UIImage(cgImage: cgImage))
        #else
        qrImage = Image(nsImage: NSImage(cgImage: cgImage, size: NSSize(width: 250, height: 250)))
        #endif
    }
}

// MARK: - QR Scanner (iOS only)

#if os(iOS)
import AVFoundation

struct QRScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let vc = QRScannerViewController()
        vc.onScan = onScan
        return vc
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {}
}

class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String) -> Void)?
    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var deniedLabel: UILabel?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        checkCameraPermission()
    }

    private func checkCameraPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setupCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.setupCamera()
                    } else {
                        self?.showDeniedMessage()
                    }
                }
            }
        case .denied, .restricted:
            showDeniedMessage()
        @unknown default:
            showDeniedMessage()
        }
    }

    private func showDeniedMessage() {
        let label = UILabel()
        label.text = "Camera access is required to scan QR codes.\n\nGo to Settings → Coords to enable camera access."
        label.textColor = .white
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)

        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32)
        ])

        deniedLabel = label
    }

    private func setupCamera() {
        let session = AVCaptureSession()

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else {
            return
        }

        if session.canAddInput(input) {
            session.addInput(input)
        }

        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
        }

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = view.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)

        previewLayer = preview
        captureSession = session

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        captureSession?.stopRunning()
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let code = object.stringValue,
              code.hasPrefix("coord://") else {
            return
        }

        captureSession?.stopRunning()
        onScan?(code)
    }
}
#endif

#Preview {
    FriendsView(identityStore: IdentityStore())
}
