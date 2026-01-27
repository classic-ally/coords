import SwiftUI
import CoreLocation

struct FriendsSheetContent: View {
    @ObservedObject var identityStore: IdentityStore
    let friends: [Friend]
    let currentLocation: CLLocation?
    let isFetchingFriends: Bool
    let onRefresh: () -> Void
    let onStartAddFriend: () -> Void
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
                        .font(.system(size: 18, weight: .semibold))
                        .frame(width: 40, height: 40)
                        .background(.ultraThinMaterial, in: Circle())
                    }
                    .disabled(isFetchingFriends)

                    // Done editing
                    Button(action: { isEditMode = false }) {
                        Image(systemName: "checkmark")
                            .font(.system(size: 18, weight: .semibold))
                            .frame(width: 40, height: 40)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                } else {
                    // Add friend
                    Button(action: onStartAddFriend) {
                        Image(systemName: "plus")
                            .font(.system(size: 18, weight: .semibold))
                            .frame(width: 40, height: 40)
                            .background(.ultraThinMaterial, in: Circle())
                    }

                    // Edit mode toggle
                    Button(action: { isEditMode = true }) {
                        Image(systemName: "pencil")
                            .font(.system(size: 18, weight: .semibold))
                            .frame(width: 40, height: 40)
                            .background(.ultraThinMaterial, in: Circle())
                    }

                    // Profile
                    Button(action: onShowProfile) {
                        Image(systemName: "person.circle.fill")
                            .font(.system(size: 40))
                            .frame(width: 40, height: 40)
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
                        Button("Add Friend", action: onStartAddFriend)
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
    }
}

#Preview("Friends Sheet - Empty") {
    FriendsSheetContent(
        identityStore: IdentityStore(),
        friends: [],
        currentLocation: nil,
        isFetchingFriends: false,
        onRefresh: {},
        onStartAddFriend: {},
        onShowProfile: {},
        onSelectFriend: { _ in },
        onToggleShare: { _ in },
        onToggleFetch: { _ in },
        onDeleteFriend: { _ in }
    )
}

#Preview("Friends Sheet - With Friends") {
    FriendsSheetContent(
        identityStore: IdentityStore(),
        friends: [
            Friend(
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
            Friend(
                pubkey: "def456",
                server: "https://coord.is",
                name: "Bob",
                shareWith: true,
                fetchFrom: false,
                location: nil,
                fetchedAt: nil,
                color: "#50C878"
            )
        ],
        currentLocation: nil,
        isFetchingFriends: false,
        onRefresh: {},
        onStartAddFriend: {},
        onShowProfile: {},
        onSelectFriend: { _ in },
        onToggleShare: { _ in },
        onToggleFetch: { _ in },
        onDeleteFriend: { _ in }
    )
}
