package sh.bentley.transponder

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The three possible states for the bottom sheet.
 */
enum class SheetAnchor {
    /** Only the drag handle is visible */
    Handle,
    /** Sheet is partially expanded (default state) */
    Partial,
    /** Sheet is fully expanded */
    Full
}

/**
 * A bottom sheet with three snap points: handle-only, partial, and full.
 *
 * @param sheetContent The content to display in the sheet.
 * @param modifier Modifier for the entire component.
 * @param handleHeight Height of the drag handle area when collapsed.
 * @param partialHeight Height of the sheet when partially expanded.
 * @param fullHeight Height of the sheet when fully expanded.
 * @param screenHeight Total screen height for calculating offsets.
 * @param sheetState The state controlling the sheet position.
 * @param content The main content behind the sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreeStateBottomSheet(
    sheetContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    handleHeight: Dp = 48.dp,
    partialHeight: Dp = 300.dp,
    fullHeight: Dp = 800.dp,
    screenHeight: Dp = 800.dp,
    sheetState: AnchoredDraggableState<SheetAnchor> = rememberThreeStateSheetState(),
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize()) {
        // Main content
        Box(
            modifier = Modifier.fillMaxSize(),
            content = content
        )

        // Sheet - needs fixed height for offset calculations to work
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenHeight)
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, sheetState.offset.roundToInt()) }
                .anchoredDraggable(sheetState, Orientation.Vertical),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                }

                // Sheet content
                sheetContent()
            }
        }
    }
}

/**
 * Creates and remembers an [AnchoredDraggableState] for the three-state bottom sheet.
 *
 * @param initialAnchor The initial state of the sheet.
 * @param handleHeight Height when only handle is visible.
 * @param partialHeight Height when partially expanded.
 * @param fullHeight Height when fully expanded.
 * @param screenHeight Total screen height available.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberThreeStateSheetState(
    initialAnchor: SheetAnchor = SheetAnchor.Partial,
    handleHeight: Dp = 48.dp,
    partialHeight: Dp = 300.dp,
    fullHeight: Dp = 800.dp,
    screenHeight: Dp = 800.dp
): AnchoredDraggableState<SheetAnchor> {
    val density = LocalDensity.current

    val handleHeightPx = with(density) { handleHeight.toPx() }
    val partialHeightPx = with(density) { partialHeight.toPx() }
    val fullHeightPx = with(density) { fullHeight.toPx() }
    val screenHeightPx = with(density) { screenHeight.toPx() }

    val decayAnimationSpec = splineBasedDecay<Float>(density)

    return remember {
        AnchoredDraggableState(
            initialValue = initialAnchor,
            anchors = DraggableAnchors {
                // Offset from top of screen to position sheet
                // Handle: sheet mostly hidden, only handle visible
                SheetAnchor.Handle at (screenHeightPx - handleHeightPx)
                // Partial: sheet at partial height
                SheetAnchor.Partial at (screenHeightPx - partialHeightPx)
                // Full: sheet at full height (leaving some map visible at top)
                SheetAnchor.Full at (screenHeightPx - fullHeightPx)
            },
            positionalThreshold = { distance: Float -> distance * 0.5f },
            velocityThreshold = { 100f },
            snapAnimationSpec = tween(durationMillis = 300),
            decayAnimationSpec = decayAnimationSpec
        )
    }
}

/**
 * Animates the sheet to the specified anchor.
 */
@OptIn(ExperimentalFoundationApi::class)
suspend fun AnchoredDraggableState<SheetAnchor>.animateToAnchor(anchor: SheetAnchor) {
    val targetOffset = anchors.positionOf(anchor)
    if (targetOffset.isNaN()) return

    val startOffset = offset
    animate(
        initialValue = startOffset,
        targetValue = targetOffset,
        animationSpec = tween(durationMillis = 300)
    ) { value, _ ->
        dispatchRawDelta(value - offset)
    }
    settle(0f)
}
