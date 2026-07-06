package app.gamenative.ui.component.dialog

import android.view.InputDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import com.winlator.inputcontrols.ControllerManager
import com.winlator.winhandler.WinHandler

/**
 * Central place for every global controller option: which physical pad is
 * Player 1 / Player 2, plus general controller preferences. Per-game on-screen
 * layouts stay with each game's container config.
 */
@Composable
fun ControllersDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val controllerManager = remember {
        ControllerManager.getInstance().also { it.init(context) }
    }

    var refreshTick by remember { mutableIntStateOf(0) }
    var devices by remember { mutableStateOf<List<InputDevice>>(emptyList()) }
    var showGamepadHints by remember { mutableStateOf(PrefManager.showGamepadHints) }

    LaunchedEffect(refreshTick) {
        controllerManager.scanForDevices()
        devices = controllerManager.getDetectedDevices().toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.controllers_title))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (devices.isEmpty()) {
                        stringResource(R.string.controllers_none_connected)
                    } else {
                        stringResource(R.string.controllers_connected_count, devices.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                // One assignment card per player slot.
                for (slot in 0 until WinHandler.MAX_PLAYERS) {
                    PlayerSlotCard(
                        slot = slot,
                        devices = devices,
                        controllerManager = controllerManager,
                        onChanged = { refreshTick++ },
                    )
                }

                OutlinedButton(onClick = { refreshTick++ }) {
                    Text(stringResource(R.string.controllers_rescan))
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_interface_show_gamepad_hints_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_interface_show_gamepad_hints_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = showGamepadHints,
                        onCheckedChange = {
                            showGamepadHints = it
                            PrefManager.showGamepadHints = it
                        },
                    )
                }

                Text(
                    text = stringResource(R.string.controllers_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun PlayerSlotCard(
    slot: Int,
    devices: List<InputDevice>,
    controllerManager: ControllerManager,
    onChanged: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val assigned = controllerManager.getAssignedDeviceForSlot(slot)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = stringResource(R.string.controllers_player_n, slot + 1),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = assigned?.name ?: stringResource(R.string.controllers_auto_assign),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Column {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(stringResource(R.string.controllers_change))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.controllers_auto_assign)) },
                        onClick = {
                            controllerManager.unassignSlot(slot)
                            menuOpen = false
                            onChanged()
                        },
                    )
                    devices.forEach { device ->
                        DropdownMenuItem(
                            text = { Text(device.name) },
                            onClick = {
                                controllerManager.assignDeviceToSlot(slot, device)
                                controllerManager.setSlotEnabled(slot, true)
                                menuOpen = false
                                onChanged()
                            },
                        )
                    }
                }
            }
        }
    }
}
