package sh.bentley.transponder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import sh.bentley.transponder.components.SheetHeader
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SheetHeaderScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun friendAndProfileActions() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SheetHeader(name = "Alex", avatarColor = Color(0xFF4A90D9)) {
                            IconButton(onClick = {}) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = {}) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }
                        SheetHeader(name = "Me", avatarColor = Color(0xFFE57373)) {
                            IconButton(onClick = {}) {
                                Icon(Icons.Default.CloudUpload, contentDescription = "Upload")
                            }
                            IconButton(onClick = {}) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/roborazzi/sheet_header.png")
    }
}
