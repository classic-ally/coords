package sh.bentley.transponder.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import sh.bentley.transponder.generateQrCode

@Composable
fun QrCodeView(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { generateQrCode(content, 512) }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "QR Code",
            modifier = modifier
                .size(200.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        )
    }
}
