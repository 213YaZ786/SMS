package com.yaz.sms.core.security

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties.IsSensitiveData
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The keys and the invite code carry Compose's IsSensitiveData: createNodeInfo reads
 * `semanticsNode.config.getOrNull(IsSensitiveData) == true` and gives such a node only to an accessibility tool.
 * https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeViewAccessibilityDelegateCompat.android.kt#createNodeInfo
 * not ported: everything of createNodeInfo but that read (Compose runs the rest itself)
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [36])
class SensitiveDataTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theMarkedNodeCarriesTheProperty() {
        compose.activity.setContent {
            Box(Modifier.isSensitiveData().testTag("keys")) { Text("AAAA BBBB") }
            Box(Modifier.testTag("plain")) { Text("hello") }
        }
        compose.waitForIdle()
        val semanticsNode = compose.onNodeWithTag("keys").fetchSemanticsNode()
        assertEquals(true, semanticsNode.config.getOrNull(IsSensitiveData))
        assertNull(compose.onNodeWithTag("plain").fetchSemanticsNode().config.getOrNull(IsSensitiveData))
    }
}
