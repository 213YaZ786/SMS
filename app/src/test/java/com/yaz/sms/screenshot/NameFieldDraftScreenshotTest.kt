package com.yaz.sms.screenshot

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.yaz.sms.ui.component.BoldButton
import com.yaz.sms.ui.component.DialogNameField
import com.yaz.sms.ui.component.DialogPane
import com.yaz.sms.ui.component.QuietButton
import com.yaz.sms.ui.component.rememberDialogNameState
import com.yaz.sms.ui.glass.LocalGlass
import com.yaz.sms.ui.glass.glassGround
import com.yaz.sms.ui.glass.rememberGlassLook
import com.yaz.sms.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DRAFT for the user's yes (requests.md, the name fields): nothing of app/src/main changes. ThreadScreen.kt:2455's
 * GroupNameDialog (a ZoneAlertDialog with a Material OutlinedTextField and a placeholder) and SettingsScreen.kt:530's
 * OwnNumberDialog become ZoneDialog's pane with the base kit's DialogNameField: the label floats onto the outline, the
 * cursor and the text start at the start, and Rename selects the whole name (rememberDialogNameState with selectAll).
 * Robolectric cannot draw a dialog window, so each dialog's pane (DialogPane, the app's own) is drawn inline with the
 * title, the field and the two buttons of ZoneAlertDialog's layout. Three panes: the group's name empty, filled with a
 * long name, and Rename with the name selected. Robolectric starts no keyboard, so the first field of each picture
 * shows what the focus gives.
 *
 * Ports:
 * https://raw.githubusercontent.com/LineageOS/android_packages_apps_DocumentsUI/lineage-23.0/src/com/android/documentsui/dirlist/RenameDocumentFragment.java#RenameDocumentFragment
 * (onCreateDialog's builder with its title, view and OK and Cancel buttons becomes NameDialog; selectFileName's
 * setSelection(0, length) becomes rememberDialogNameState(selectAll = true); mRenameInputWrapper.setHint becomes the label)
 * not ported: show, onShowDialog, onClickDialog, onActivityCreated, onSaveInstanceState, clearFileNameSelection,
 * renameDocuments and its conflict checks (nothing is renamed in a picture; the callers keep their own rules), the
 * extension split of selectFileName (a group has no extension)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class NameFieldDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)
    )

    private fun capture(name: String, body: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w411dp-h914dp-420dpi")
        listOf(false, true).forEach { night ->
            compose.activity.setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AppTheme(darkTheme = night, dynamicColor = false) {
                        val look = rememberGlassLook(MaterialTheme.colorScheme, true, quiet = true)
                        CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                            Box(Modifier.fillMaxSize().glassGround(look, MaterialTheme.colorScheme.background)) { body() }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(
                "src/test/screenshots/${name}_phone_${if (night) "dark" else "light"}.png",
                roborazziOptions = options
            )
        }
    }

    /** onCreateDialog: the title, the field with its label, Cancel and the positive button. */
    @Composable
    private fun NameDialog(title: String, label: String, initial: String, selectAll: Boolean, positive: String) {
        val mEditText = rememberDialogNameState(initial, selectAll)
        DialogPane {
            Column(Modifier.widthIn(max = 312.dp).fillMaxWidth().padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Column(Modifier.padding(top = 16.dp, bottom = 24.dp)) {
                    DialogNameField(mEditText, label, onDone = {})
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    QuietButton(onClick = {}) { Text("Cancel") }
                    BoldButton(filled = true, onClick = {}) { Text(positive) }
                }
            }
        }
    }

    @Test
    fun names() = capture("name_field_draft") {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
        ) {
            // The group's name, nothing typed: the label sits in the field.
            NameDialog("Name the group", "Group name", initial = "", selectAll = false, positive = "Save")
            // A long name: the label floats onto the outline, the text starts at the start and is cut by the field.
            NameDialog(
                "Name the group", "Group name",
                initial = "Marie-Christine Delacroix-Beaumont and the whole family of Tanaka and Okafor",
                selectAll = false, positive = "Save"
            )
            // Rename: the whole name selected, so typing replaces it.
            NameDialog("Rename", "Name", initial = "Joëlle Martin", selectAll = true, positive = "Rename")
        }
    }
}
