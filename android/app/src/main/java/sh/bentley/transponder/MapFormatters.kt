package sh.bentley.transponder

import uniffi.transponder_core.City

internal fun City.displayName(): String =
    if (region.isNotEmpty()) "$name, $region" else "$name, $country"

internal fun formatAge(timestampMs: ULong): String {
    val now = System.currentTimeMillis().toULong()
    val diffMs = now - timestampMs
    val diffSec = diffMs / 1000u
    val diffMin = diffSec / 60u
    val diffHour = diffMin / 60u
    val diffDay = diffHour / 24u

    return when {
        diffMin < 1u -> "just now"
        diffMin < 60u -> "${diffMin}m ago"
        diffHour < 24u -> "${diffHour}h ago"
        else -> "${diffDay}d ago"
    }
}

internal fun formatDistance(meters: Float): String {
    return when {
        meters < 1000 -> "${meters.toInt()} m"
        meters < 10000 -> String.format("%.1f km", meters / 1000)
        else -> "${(meters / 1000).toInt()} km"
    }
}
