package sh.bentley.transponder

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import sh.bentley.transponder.sheets.FriendDetailSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.transponder_core.Friend

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FriendDetailSheetScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun noLocationWithToggles() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    FriendDetailSheet(
                        friend = Friend(
                            pubkey = "ab12cd34",
                            server = "coord.is",
                            name = "Alex",
                            shareWith = true,
                            fetchFrom = true,
                            location = null,
                            fetchedAt = null,
                            color = "#4A90D9"
                        ),
                        onDismiss = {},
                        onNameEdit = {},
                        onToggleShare = {},
                        onToggleFetch = {},
                        onDelete = {}
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/roborazzi/friend_detail_sheet.png")
    }
}
