import SwiftUI

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
