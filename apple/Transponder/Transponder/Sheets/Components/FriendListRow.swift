import SwiftUI
import CoreLocation

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
                        if let city = findNearestCityInRegion(lat: loc.latitude, lng: loc.longitude) {
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
