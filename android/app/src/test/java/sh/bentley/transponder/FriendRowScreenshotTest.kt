package sh.bentley.transponder

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import sh.bentley.transponder.components.FriendRow
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
class FriendRowScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun friend(name: String, share: Boolean, fetch: Boolean) = Friend(
        pubkey = "ab12cd34",
        server = "coord.is",
        name = name,
        shareWith = share,
        fetchFrom = fetch,
        location = null,
        fetchedAt = null,
        color = "#4A90D9"
    )

    @Test
    fun normalAndEditModes() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Column {
                        FriendRow(
                            friend = friend("Alex", share = true, fetch = true),
                            city = null,
                            currentLocation = null,
                            isEditMode = false,
                            onClick = {},
                            onToggleShare = {},
                            onToggleFetch = {},
                            onDelete = {}
                        )
                        FriendRow(
                            friend = friend("Sam", share = true, fetch = false),
                            city = null,
                            currentLocation = null,
                            isEditMode = true,
                            onClick = {},
                            onToggleShare = {},
                            onToggleFetch = {},
                            onDelete = {}
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/roborazzi/friend_row.png")
    }
}
