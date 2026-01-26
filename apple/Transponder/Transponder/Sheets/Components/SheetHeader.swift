import SwiftUI

struct SheetHeader<LeadingContent: View, TrailingContent: View>: View {
    let leadingContent: LeadingContent
    let trailingContent: TrailingContent

    init(
        @ViewBuilder leading: () -> LeadingContent,
        @ViewBuilder trailing: () -> TrailingContent
    ) {
        self.leadingContent = leading()
        self.trailingContent = trailing()
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center) {
                leadingContent
                Spacer()
                HStack(alignment: .center, spacing: 12) {
                    trailingContent
                }
            }
            .padding(.horizontal)
            .padding(.top, 12)
            .padding(.bottom, 8)

            Divider()
        }
    }
}
