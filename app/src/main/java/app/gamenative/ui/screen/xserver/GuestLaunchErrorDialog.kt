package app.gamenative.ui.screen.xserver

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import com.winlator.container.Container
import timber.log.Timber

/**
 * Shows the guest (box64/wine) exit code ON SCREEN when the game fails to launch, instead of the
 * app silently closing with a clean log — which is exactly what made the launch crash impossible
 * to diagnose. The user can screenshot this dialog (no adb needed).
 *
 * Extracted from [XServerScreen] on purpose: keeping this state + event listener + dialog in its
 * own composable keeps the XServerScreen function small enough for the on-device bytecode verifier
 * (a too-large composable triggers a VerifyError at class load and the whole app closes).
 *
 * @param onDismissAndExit invoked when the user closes the dialog; the caller runs the normal exit
 *        path (winHandler cleanup + navigate back).
 */
@Composable
fun GuestLaunchErrorDialog(container: Container, onDismissAndExit: () -> Unit) {
    // Non-null when the guest exited with a real error — drives the on-screen dialog.
    var guestLaunchErrorStatus by rememberSaveable { mutableStateOf<Int?>(null) }

    DisposableEffect(Unit) {
        val onGuestProgramLaunchError: (AndroidEvent.GuestProgramLaunchError) -> Unit = { event ->
            Timber.i("onGuestProgramLaunchError status=${event.status}")
            guestLaunchErrorStatus = event.status
        }
        PluviaApp.events.on<AndroidEvent.GuestProgramLaunchError, Unit>(onGuestProgramLaunchError)
        onDispose {
            PluviaApp.events.off<AndroidEvent.GuestProgramLaunchError, Unit>(onGuestProgramLaunchError)
        }
    }

    guestLaunchErrorStatus?.let { st ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = stringResource(R.string.guest_launch_error_title)) },
            text = {
                Text(
                    text = stringResource(R.string.guest_launch_error_message, st) + "\n\n" +
                        "Box64: ${container.box64Version}\n" +
                        "Wine: ${container.wineVersion}\n" +
                        "Box64 preset: ${container.box64Preset}\n" +
                        "DX: ${container.dxWrapper}",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    guestLaunchErrorStatus = null
                    onDismissAndExit()
                }) {
                    Text(text = stringResource(R.string.close))
                }
            },
        )
    }
}
