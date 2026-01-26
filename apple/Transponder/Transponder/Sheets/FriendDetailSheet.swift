import SwiftUI
#if os(iOS)
import UIKit
#endif

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
                            .background(Color.secondary.opacity(0.1))
                            .cornerRadius(10)
                        }
                    }
                    #endif

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
