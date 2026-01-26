import SwiftUI

struct ConfirmAddFriendContent: View {
    let friend: ParsedFriendLink
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text("Add Friend?")
                .font(.title2)
                .fontWeight(.semibold)

            VStack(spacing: 8) {
                Text(friend.name)
                    .font(.headline)
                Text("from \(friend.server)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 16) {
                Button("Cancel") {
                    onCancel()
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)

                Button("Add") {
                    onConfirm()
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal)
        }
        .padding()
    }
}
