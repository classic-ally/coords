import Foundation

/// Role in the QR exchange process
enum ExchangeRole: String, CaseIterable, Equatable {
    case showFirst = "I show first"
    case scanFirst = "I scan first"
}

/// Steps within the add friend flow
enum AddFriendStep: Equatable {
    case showQR(role: ExchangeRole, isSecondStep: Bool, addedName: String?)
    case scanQR(role: ExchangeRole, isFirstStep: Bool)
    case linkEntry(role: ExchangeRole)
}

/// Which content is showing in the bottom sheet
enum SheetContent: Equatable {
    case friends
    case profile
    case friendDetail(String)  // pubkey
    case confirmAddFriend(ParsedFriendLink)  // deep link confirmation
    case addFriend(AddFriendStep)  // integrated add friend flow
}

/// Pending camera actions for deferred execution
enum CameraAction: Equatable {
    case centerOn(latitude: Double, longitude: Double)
    case fitAllFriends
}
