package sh.bentley.transponder

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import sh.bentley.transponder.components.FriendDisplayData
import sh.bentley.transponder.components.FriendList
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
class FriendListScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun friend(name: String, share: Boolean, fetch: Boolean) = FriendDisplayData(
        friend = Friend(
            pubkey = "pk-$name",
            server = "coord.is",
            name = name,
            shareWith = share,
            fetchFrom = fetch,
            location = null,
            fetchedAt = null,
            color = "#4A90D9"
        ),
        city = null
    )

    @Test
    fun normalMode() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    FriendList(
                        items = listOf(
                            friend("Alex", share = true, fetch = true),
                            friend("Sam", share = true, fetch = false),
                            friend("Jo", share = false, fetch = true)
                        ),
                        currentLocation = null,
                        isEditMode = false,
                        onClick = {},
                        onToggleShare = {},
                        onToggleFetch = {},
                        onDelete = {}
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/roborazzi/friend_list.png")
    }
}
