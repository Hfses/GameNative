package app.gamenative.lan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.launch

/**
 * "Jogar LAN" dialog: create a room (name + optional password, host IP shown)
 * or join one (IP pre-filled by discovery on the same network), with chat.
 * After everyone is in the room, each player opens the same game and connects
 * through the game's own LAN menu.
 */
@Composable
fun LanRoomDialog(
    visible: Boolean,
    gameName: String,
    onDismiss: () -> Unit,
    onOpenGame: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val status by LanRoomManager.status.collectAsState()
    val players by LanRoomManager.players.collectAsState()
    val chat by LanRoomManager.chat.collectAsState()
    val roomInfo by LanRoomManager.roomInfo.collectAsState()

    val defaultPlayerName = remember {
        SteamService.instance?.localPersona?.value?.name?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    }

    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = create, 1 = join
    var playerName by rememberSaveable { mutableStateOf(defaultPlayerName) }
    var roomName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var joinIp by rememberSaveable { mutableStateOf("") }
    var chatInput by rememberSaveable { mutableStateOf("") }
    var discovering by remember { mutableStateOf(false) }

    val inRoom = status == LanRoomManager.Status.HOSTING || status == LanRoomManager.Status.JOINED
    val chatListState = rememberLazyListState()
    // Key on the last message, not size: the chat is capped at 200, so size stops changing and a
    // size-keyed effect would stop auto-scrolling once the cap is hit.
    LaunchedEffect(chat.lastOrNull()) {
        if (chat.isNotEmpty()) chatListState.animateScrollToItem(chat.size - 1)
    }

    // Clear a leftover error/denied/joining status when leaving the dialog without being in a room,
    // so reopening doesn't greet the user with a stale "wrong password"/"could not connect".
    val handleDismiss: () -> Unit = {
        if (!inRoom) LanRoomManager.resetTransient()
        onDismiss()
    }

    // Pre-fill the IP field with the first room found on this network.
    LaunchedEffect(tab) {
        if (tab == 1 && joinIp.isBlank()) {
            discovering = true
            val rooms = LanRoomManager.discoverRooms(context)
            rooms.firstOrNull()?.let { joinIp = it.ip }
            discovering = false
        }
    }

    AlertDialog(
        onDismissRequest = handleDismiss,
        confirmButton = {
            TextButton(onClick = handleDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            if (inRoom) {
                TextButton(
                    onClick = { LanRoomManager.stop() },
                ) { Text(stringResource(R.string.lan_leave_room)) }
            }
        },
        title = { Text(stringResource(R.string.lan_play_title, gameName)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!inRoom) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(stringResource(R.string.lan_create)) }
                        SegmentedButton(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text(stringResource(R.string.lan_join)) }
                    }

                    OutlinedTextField(
                        value = playerName,
                        onValueChange = { playerName = it },
                        label = { Text(stringResource(R.string.lan_player_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (tab == 0) {
                        OutlinedTextField(
                            value = roomName,
                            onValueChange = { roomName = it },
                            label = { Text(stringResource(R.string.lan_room_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.lan_password_optional)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                LanRoomManager.createRoom(context, roomName, password, gameName, playerName)
                            },
                            enabled = status != LanRoomManager.Status.HOSTING &&
                                status != LanRoomManager.Status.JOINING &&
                                playerName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.lan_create_room)) }
                    } else {
                        OutlinedTextField(
                            value = joinIp,
                            onValueChange = { joinIp = it },
                            label = {
                                Text(
                                    if (discovering) {
                                        stringResource(R.string.lan_searching_rooms)
                                    } else {
                                        stringResource(R.string.lan_host_ip_or_link)
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.lan_password_optional)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    // Accept either a bare IP or a pasted gamenative://lan link
                                    // (the link may also carry the room password).
                                    val parsed = LanRoomManager.parseJoinLink(joinIp)
                                    if (parsed != null) {
                                        LanRoomManager.joinRoom(
                                            context,
                                            parsed.ip,
                                            parsed.password.ifEmpty { password },
                                            playerName,
                                        )
                                    } else {
                                        LanRoomManager.joinRoom(context, joinIp, password, playerName)
                                    }
                                },
                                enabled = joinIp.isNotBlank() &&
                                    playerName.isNotBlank() &&
                                    status != LanRoomManager.Status.JOINING,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.lan_join_room)) }
                            TextButton(onClick = {
                                scope.launch {
                                    discovering = true
                                    val rooms = LanRoomManager.discoverRooms(context)
                                    rooms.firstOrNull()?.let { joinIp = it.ip }
                                    discovering = false
                                }
                            }) { Text(stringResource(R.string.controllers_rescan)) }
                        }
                    }

                    if (status == LanRoomManager.Status.DENIED) {
                        Text(
                            text = stringResource(R.string.lan_denied),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (status == LanRoomManager.Status.ERROR) {
                        Text(
                            text = stringResource(R.string.lan_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (status == LanRoomManager.Status.JOINING) {
                        Text(
                            text = stringResource(R.string.lan_joining),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Text(
                        text = stringResource(R.string.lan_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // In-room view: room info, players, chat, open game.
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (status == LanRoomManager.Status.HOSTING) {
                                Text(
                                    text = stringResource(R.string.lan_room_ip, roomInfo),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                val linkCopiedMsg = stringResource(R.string.lan_link_copied)
                                Button(
                                    onClick = {
                                        val link = LanRoomManager.buildJoinLink(roomInfo, password)
                                        clipboard.setText(AnnotatedString(link))
                                        // This project bans android.widget.Toast at compile time
                                        // (deprecation rule); use the app's SnackbarManager.
                                        SnackbarManager.show(linkCopiedMsg)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.lan_copy_link)) }
                            }
                            Text(
                                text = stringResource(R.string.lan_players, players.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    LazyColumn(
                        state = chatListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(chat) { msg ->
                            if (msg.system) {
                                Text(
                                    text = msg.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = "${msg.from}: ${msg.text}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val sendChat: () -> Unit = {
                            if (chatInput.isNotBlank()) {
                                LanRoomManager.sendChat(chatInput)
                                chatInput = ""
                            }
                        }
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            label = { Text(stringResource(R.string.lan_chat_message)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendChat() }),
                        )
                        IconButton(onClick = sendChat, enabled = chatInput.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        }
                    }

                    HorizontalDivider()

                    Button(onClick = onOpenGame, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.lan_open_game))
                    }
                    Text(
                        text = stringResource(R.string.lan_in_room_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
