import SwiftUI
import CoreImage.CIFilterBuiltins

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
