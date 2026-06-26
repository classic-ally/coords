package sh.bentley.transponder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

internal const val SELF_COLOR = "#4285F4" // Google blue

// Source and layer IDs for native layers
internal const val SOURCE_LOCATIONS = "locations-source"
internal const val SOURCE_SELECTED = "selected-source"
internal const val LAYER_ACCURACY = "accuracy-layer"
internal const val LAYER_DOTS = "dots-layer"
internal const val LAYER_SELECTED_LABEL = "selected-label-layer"

// Camera animation
internal const val CAMERA_ANIMATION_DURATION_MS = 350

// Marker icon size in pixels (for generated bitmaps)
internal const val MARKER_ICON_SIZE = 56

/** Cache for generated friend marker icons, keyed by pubkey */
internal val markerIconCache = mutableMapOf<String, Bitmap>()

/**
 * Generate a circular marker icon with an initial letter.
 */
internal fun generateMarkerIcon(
    initial: String,
    fillColor: Int,
    strokeColor: Int = Color.WHITE,
    textColor: Int = Color.WHITE
): Bitmap {
    val size = MARKER_ICON_SIZE
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val centerX = size / 2f
    val centerY = size / 2f
    val radius = size / 2f - 4f // Leave room for stroke

    // Fill circle
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(centerX, centerY, radius, fillPaint)

    // Stroke circle
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(centerX, centerY, radius, strokePaint)

    // Draw initial letter
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = size * 0.45f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    // Center text vertically
    val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2
    canvas.drawText(initial, centerX, textY, textPaint)

    return bitmap
}

/**
 * Get or create a marker icon for a friend using their name initial and color.
 */
internal fun getFriendMarkerIcon(pubkey: String, name: String, colorHex: String): Bitmap {
    return markerIconCache.getOrPut(pubkey) {
        val initial = name.firstOrNull()?.toString() ?: "?"
        val color = Color.parseColor(colorHex)
        generateMarkerIcon(initial, color)
    }
}

/**
 * Generate self location marker (blue circle, no initial).
 */
internal fun generateSelfMarkerIcon(): Bitmap {
    val size = MARKER_ICON_SIZE
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val centerX = size / 2f
    val centerY = size / 2f
    val radius = size / 2f - 4f

    // Fill circle (blue)
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(SELF_COLOR)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(centerX, centerY, radius, fillPaint)

    // Stroke circle
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(centerX, centerY, radius, strokePaint)

    return bitmap
}
