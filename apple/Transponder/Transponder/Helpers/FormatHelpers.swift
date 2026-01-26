import Foundation

func formatAge(timestampMs: UInt64) -> String {
    let now = UInt64(Date().timeIntervalSince1970 * 1000)
    let diffMs = now - timestampMs
    let diffSec = diffMs / 1000
    let diffMin = diffSec / 60
    let diffHour = diffMin / 60
    let diffDay = diffHour / 24

    if diffMin < 1 {
        return "just now"
    } else if diffMin < 60 {
        return "\(diffMin)m ago"
    } else if diffHour < 24 {
        return "\(diffHour)h ago"
    } else {
        return "\(diffDay)d ago"
    }
}

func formatDistance(meters: Double) -> String {
    if meters < 1000 {
        return "\(Int(meters)) m"
    } else if meters < 10000 {
        return String(format: "%.1f km", meters / 1000)
    } else {
        return "\(Int(meters / 1000)) km"
    }
}
