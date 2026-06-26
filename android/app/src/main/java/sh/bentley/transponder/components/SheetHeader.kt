package sh.bentley.transponder.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Sheet title row: colored avatar with the name's initial, the name, and a
 * trailing slot for sheet-specific action buttons.
 */
@Composable
fun SheetHeader(
    name: String,
    avatarColor: Color,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(avatarColor, shape = CircleShape)
                    .border(1.5.dp, Color.White, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.toString() ?: "?",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = name, style = MaterialTheme.typography.titleLarge)
        }
        Row(content = actions)
    }
}
