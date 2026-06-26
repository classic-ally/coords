package sh.bentley.transponder

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import sh.bentley.transponder.components.QrCodeView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class QrCodeViewScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersQr() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    QrCodeView(
                        content = "coord://coord.is/add/ab12cd34#Alex",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/roborazzi/qr_code_view.png")
    }
}
