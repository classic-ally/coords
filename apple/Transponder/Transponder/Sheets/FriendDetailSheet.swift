import SwiftUI
#if os(iOS)
import UIKit
#endif

/// Button style for settings rows with press highlight
private struct SettingsRowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.primary.opacity(0.1) : Color.clear)
            .contentShape(Rectangle())
    }
}

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
                            if let city = findNearestCityInRegion(lat: loc.latitude, lng: loc.longitude) {
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
                        .font(.system(size: 18, weight: .semibold))
                        .frame(width: 40, height: 40)
                        .background(.ultraThinMaterial, in: Circle())
                }

                Button(action: onBack) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 40))
                        .frame(width: 40, height: 40)
                        .foregroundStyle(.secondary)
                }
            }

            ScrollView {
                VStack(spacing: 16) {
                    // Open in Maps button
                    #if os(iOS)
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
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .shadow(color: .black.opacity(0.08), radius: 4, y: 2)
                        }
                    }
                    #endif

                    // Sharing settings group
                    VStack(spacing: 0) {
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
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)

                        Divider()
                            .padding(.leading, 48)

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
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .shadow(color: .black.opacity(0.08), radius: 4, y: 2)

                    // Remove button - only when both toggles are off
                    if !friend.shareWith && !friend.fetchFrom {
                        Button(role: .destructive) {
                            showRemoveConfirmation = true
                        } label: {
                            HStack {
                                Image(systemName: "person.badge.minus")
                                    .foregroundStyle(.red)
                                Text("Remove Friend")
                                    .foregroundStyle(.red)
                                Spacer()
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                        }
                        .buttonStyle(SettingsRowButtonStyle())
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .shadow(color: .black.opacity(0.08), radius: 4, y: 2)
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

#Preview("Friend Detail") {
    FriendDetailSheetContent(
        friend: Friend(
            pubkey: "abc123",
            server: "https://coord.is",
            name: "Alice",
            shareWith: true,
            fetchFrom: true,
            location: Location(
                latitude: 37.7749,
                longitude: -122.4194,
                altitude: 0,
                accuracy: 10,
                timestamp: UInt64(Date().timeIntervalSince1970 * 1000)
            ),
            fetchedAt: nil,
            color: "#4A90D9"
        ),
        onBack: {},
        onToggleShare: {},
        onToggleFetch: {},
        onRemove: {},
        onEditName: {}
    )
}

#Preview("Friend Detail - No Location") {
    FriendDetailSheetContent(
        friend: Friend(
            pubkey: "def456",
            server: "https://coord.is",
            name: "Bob",
            shareWith: false,
            fetchFrom: false,
            location: nil,
            fetchedAt: nil,
            color: "#50C878"
        ),
        onBack: {},
        onToggleShare: {},
        onToggleFetch: {},
        onRemove: {},
        onEditName: {}
    )
}
