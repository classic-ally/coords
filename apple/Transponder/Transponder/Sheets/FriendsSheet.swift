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
                    Button(action: onStartAddFriend) {
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
