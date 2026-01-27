import SwiftUI

// MARK: - Add Friend Content (Unified State Machine Version)

struct AddFriendContent: View {
    @ObservedObject var identityStore: IdentityStore
    let step: AddFriendStep
    let onNavigate: (AddFriendStep) -> Void
    /// Callback when scanning/adding a friend. The completion returns true if they've already added us.
    let onAddFriend: (String, @escaping (Bool) -> Void) -> Void
    /// Called when the flow completes, with the added friend's pubkey (if available)
    let onComplete: (String?) -> Void
    let onCancel: () -> Void

    @State private var linkText: String = ""
    @State private var addedFriendName: String?
    @State private var addedFriendPubkey: String?
    @State private var showingLinkEntry = false
    @State private var isProcessing = false

    // Derived state from step
    private var role: ExchangeRole {
        switch step {
        case .showQR(let r, _, _): return r
        case .scanQR(let r, _): return r
        case .linkEntry(let r): return r
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                #if os(iOS)
                // Role selector (segmented picker)
                Picker("Who goes first?", selection: Binding(
                    get: { role },
                    set: { newRole in
                        // Switch to first step of new role
                        if newRole == .showFirst {
                            onNavigate(.showQR(role: .showFirst, isSecondStep: false, addedName: nil))
                        } else {
                            onNavigate(.scanQR(role: .scanFirst, isFirstStep: true))
                        }
                    }
                )) {
                    ForEach(ExchangeRole.allCases, id: \.self) { r in
                        Text(r.rawValue).tag(r)
                    }
                }
                .pickerStyle(.segmented)
                .padding()
                .disabled(hasStarted) // Lock once a friend is added

                // Step indicator
                HStack(spacing: 8) {
                    ForEach(0..<2) { stepIdx in
                        Circle()
                            .fill(stepIdx <= displayStepIndex ? Color.accentColor : Color.secondary.opacity(0.3))
                            .frame(width: 8, height: 8)
                    }
                }
                .padding(.bottom, 8)

                // Content based on step
                stepContentView
                #else
                // macOS fallback - link entry only
                linkEntryView(role: .showFirst)
                #endif
            }
            .navigationTitle(navigationTitle)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onCancel() }
                }
            }
            #if os(iOS)
            .sheet(isPresented: $showingLinkEntry) {
                linkEntrySheet
            }
            .interactiveDismissDisabled(hasStarted)
            #endif
        }
    }

    private var hasStarted: Bool {
        addedFriendName != nil
    }

    private var displayStepIndex: Int {
        switch step {
        case .showQR(let role, let isSecondStep, _):
            if role == .showFirst {
                return isSecondStep ? 1 : 0
            } else {
                return isSecondStep ? 1 : 0
            }
        case .scanQR(let role, let isFirstStep):
            if role == .scanFirst {
                return isFirstStep ? 0 : 1
            } else {
                return isFirstStep ? 0 : 1
            }
        case .linkEntry: return 0
        }
    }

    private var navigationTitle: String {
        switch step {
        case .showQR:
            return "Show Your QR"
        case .scanQR:
            return "Scan Their QR"
        case .linkEntry:
            return "Add with Link"
        }
    }

    #if os(iOS)
    @ViewBuilder
    private var stepContentView: some View {
        switch step {
        case .showQR(let role, let isSecondStep, let addedName):
            showQRView(role: role, isSecondStep: isSecondStep, addedName: addedName)

        case .scanQR(let role, let isFirstStep):
            scanQRView(role: role, isFirstStep: isFirstStep)

        case .linkEntry(let role):
            linkEntryView(role: role)
        }
    }

    private func showQRView(role: ExchangeRole, isSecondStep: Bool, addedName: String?) -> some View {
        VStack(spacing: 24) {
            Spacer()

            QRCodeView(identityStore: identityStore)

            if isSecondStep {
                if let name = addedName ?? self.addedFriendName {
                    Text("Added \(name)!")
                        .font(.headline)
                        .foregroundStyle(.green)
                }

                Text("Now have your friend scan this to complete the exchange")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Button {
                    onComplete(addedFriendPubkey)
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
            } else {
                Text("Have your friend scan this QR code")
                    .font(.headline)
                    .multilineTextAlignment(.center)

                Text("Once they've scanned it, tap Next to scan theirs")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Button {
                    // Advance to scan step
                    onNavigate(.scanQR(role: role, isFirstStep: false))
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
            }

            Spacer()
        }
        .padding()
    }

    private func scanQRView(role: ExchangeRole, isFirstStep: Bool) -> some View {
        ZStack {
            QRScannerView { code in
                handleScannedCode(code, role: role, isFirstStep: isFirstStep)
            }
            .ignoresSafeArea()

            VStack {
                Spacer()

                if isProcessing {
                    ProgressView("Checking...")
                        .padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                } else if isFirstStep {
                    Text("Point camera at your friend's QR code")
                        .font(.subheadline)
                        .padding(.vertical, 8)
                        .padding(.horizontal, 16)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                Button {
                    showingLinkEntry = true
                } label: {
                    Label("Add with Link", systemImage: "link")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .padding(.horizontal)
                .padding(.bottom, 8)
                .disabled(isProcessing)
            }
        }
    }

    private func handleScannedCode(_ code: String, role: ExchangeRole, isFirstStep: Bool) {
        guard !isProcessing else { return }
        isProcessing = true

        // Extract friend info for display and completion
        if let parsed = try? parseFriendLink(url: code) {
            addedFriendName = parsed.name
            addedFriendPubkey = parsed.pubkey
        }

        onAddFriend(code) { alreadyFriends in
            isProcessing = false

            if alreadyFriends {
                // They already added us - skip showing QR, we're done!
                onComplete(addedFriendPubkey)
            } else if isFirstStep {
                // "I scan first" mode: advance to show your QR
                onNavigate(.showQR(role: role, isSecondStep: true, addedName: addedFriendName))
            } else {
                // "I show first" mode: scanning is the last step, complete the flow
                onComplete(addedFriendPubkey)
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
                    handleLinkEntry(role: role)
                }
                .buttonStyle(.borderedProminent)
                .disabled(linkText.isEmpty || isProcessing)

                if isProcessing {
                    ProgressView("Checking...")
                }

                Spacer()
            }
            .padding()
            .navigationTitle("Add with Link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showingLinkEntry = false }
                        .disabled(isProcessing)
                }
            }
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled(isProcessing)
    }

    private func handleLinkEntry(role: ExchangeRole) {
        guard !isProcessing else { return }
        isProcessing = true

        if let parsed = try? parseFriendLink(url: linkText) {
            addedFriendName = parsed.name
            addedFriendPubkey = parsed.pubkey
        }

        onAddFriend(linkText) { alreadyFriends in
            isProcessing = false
            showingLinkEntry = false

            if alreadyFriends {
                // They already added us - we're done!
                onComplete(addedFriendPubkey)
            } else if role == .scanFirst {
                onNavigate(.showQR(role: role, isSecondStep: true, addedName: addedFriendName))
            } else {
                onComplete(addedFriendPubkey)
            }
        }
    }
    #endif

    private func linkEntryView(role: ExchangeRole) -> some View {
        VStack(spacing: 20) {
            Spacer()

            Text("Paste a friend's Coords link:")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            TextField("coord://...", text: $linkText)
                .textFieldStyle(.roundedBorder)
                #if os(iOS)
                .autocapitalization(.none)
                .keyboardType(.URL)
                #endif
                .padding(.horizontal, 32)

            Button("Add Friend") {
                if let parsed = try? parseFriendLink(url: linkText) {
                    addedFriendName = parsed.name
                    addedFriendPubkey = parsed.pubkey
                }
                onAddFriend(linkText) { alreadyFriends in
                    if alreadyFriends {
                        onComplete(addedFriendPubkey)
                    } else if role == .scanFirst {
                        onNavigate(.showQR(role: role, isSecondStep: true, addedName: addedFriendName))
                    } else {
                        onComplete(addedFriendPubkey)
                    }
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(linkText.isEmpty)

            Spacer()
        }
        .padding()
    }
}

#Preview("Show QR - Step 1") {
    AddFriendContent(
        identityStore: IdentityStore(),
        step: .showQR(role: .showFirst, isSecondStep: false, addedName: nil),
        onNavigate: { _ in },
        onAddFriend: { _, _ in },
        onComplete: { _ in },
        onCancel: {}
    )
}

#Preview("Show QR - Step 2 (Friend Added)") {
    AddFriendContent(
        identityStore: IdentityStore(),
        step: .showQR(role: .showFirst, isSecondStep: true, addedName: "Alice"),
        onNavigate: { _ in },
        onAddFriend: { _, _ in },
        onComplete: { _ in },
        onCancel: {}
    )
}

#Preview("Scan QR - First Step") {
    AddFriendContent(
        identityStore: IdentityStore(),
        step: .scanQR(role: .scanFirst, isFirstStep: true),
        onNavigate: { _ in },
        onAddFriend: { _, _ in },
        onComplete: { _ in },
        onCancel: {}
    )
}

#Preview("Scan QR - Second Step") {
    AddFriendContent(
        identityStore: IdentityStore(),
        step: .scanQR(role: .showFirst, isFirstStep: false),
        onNavigate: { _ in },
        onAddFriend: { _, _ in },
        onComplete: { _ in },
        onCancel: {}
    )
}

#Preview("Link Entry") {
    AddFriendContent(
        identityStore: IdentityStore(),
        step: .linkEntry(role: .showFirst),
        onNavigate: { _ in },
        onAddFriend: { _, _ in },
        onComplete: { _ in },
        onCancel: {}
    )
}
