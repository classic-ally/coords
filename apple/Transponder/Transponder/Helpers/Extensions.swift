import SwiftUI

// MARK: - City Extension for Display Name

extension City {
    /// Returns a display string like "Toronto, ON" or "Paris, France"
    var displayName: String {
        if !region.isEmpty {
            return "\(name), \(region)"
        } else {
            return "\(name), \(country)"
        }
    }
}

// MARK: - Color Extension for Hex Parsing

extension Color {
    /// Initialize a Color from a hex string like "#4A90D9"
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let r, g, b: UInt64
        switch hex.count {
        case 6: // RGB
            (r, g, b) = ((int >> 16) & 0xFF, (int >> 8) & 0xFF, int & 0xFF)
        default:
            (r, g, b) = (128, 128, 128) // Default gray
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: 1
        )
    }
}
