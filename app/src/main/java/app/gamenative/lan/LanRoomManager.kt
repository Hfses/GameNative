package app.gamenative.lan

import android.content.Context
import android.net.wifi.WifiManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Room system for playing over the local network: one phone hosts a room
 * (name + optional password), friends join by IP (auto-discovered on the same
 * Wi-Fi), everyone chats, then each player opens the same game and connects
 * through the game's own LAN menu.
 *
 * The room does NOT tunnel game traffic — games use their own netcode. It
 * solves the human part: finding the host's IP, agreeing on the game, and
 * knowing when everyone is ready. Works across networks too when both sides
 * share a VPN like ZeroTier/Tailscale (join by the VPN IP).
 */
object LanRoomManager {

    const val ROOM_PORT = 36890
    private const val DISCOVERY_PORT = 36891
    private const val DISCOVERY_PROBE = "GN_ROOM?"
    private const val DISCOVERY_REPLY_PREFIX = "GN_ROOM!"

    enum class Status { IDLE, HOSTING, JOINING, JOINED, DENIED, ERROR }

    data class ChatMessage(val from: String, val text: String, val system: Boolean = false)
    data class DiscoveredRoom(val ip: String, val roomName: String, val gameName: String, val needsPassword: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _players = MutableStateFlow<List<String>>(emptyList())
    val players: StateFlow<List<String>> = _players.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _roomInfo = MutableStateFlow<String>("")
    val roomInfo: StateFlow<String> = _roomInfo.asStateFlow()

    // --- host state ---
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private val hostClients = ConcurrentHashMap<Socket, String>()
    private var hostJob: Job? = null
    private var roomName = ""
    private var roomPassword = ""
    private var roomGameName = ""
    private var selfName = ""

    // --- client state ---
    private var clientSocket: Socket? = null
    private var clientWriter: PrintWriter? = null
    private var clientJob: Job? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    /** Best-effort local IPv4 (site-local preferred) for showing to friends. */
    fun localIpAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidates = interfaces
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
            candidates.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") }
                ?: candidates.firstOrNull()
                ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    @Synchronized
    fun createRoom(context: Context, name: String, password: String, gameName: String, playerName: String) {
        stop()
        roomName = name.ifBlank { "Sala de $playerName" }
        roomPassword = password
        roomGameName = gameName
        selfName = playerName
        _chat.value = emptyList()
        _players.value = listOf(playerName)
        _status.value = Status.HOSTING
        _roomInfo.value = localIpAddress()
        acquireMulticastLock(context)

        hostJob = scope.launch {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(ROOM_PORT))
                serverSocket = server
                launch { runDiscoveryResponder() }
                appendSystem("Sala \"$roomName\" criada. Passe o IP ${_roomInfo.value} para os amigos.")
                while (!server.isClosed) {
                    val socket = server.accept()
                    launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                if (_status.value == Status.HOSTING) {
                    Timber.tag("LanRoom").e(e, "Host loop ended")
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var playerName = "?"
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = PrintWriter(socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8), true)
            val joinLine = reader.readLine() ?: return
            val join = JSONObject(joinLine)
            if (join.optString("type") != "join") { socket.close(); return }
            playerName = join.optString("name", "?").take(32)
            val pw = join.optString("password", "")
            if (roomPassword.isNotEmpty() && pw != roomPassword) {
                writer.println(JSONObject().put("type", "denied").put("reason", "password"))
                socket.close()
                return
            }
            writer.println(
                JSONObject()
                    .put("type", "welcome")
                    .put("room", roomName)
                    .put("game", roomGameName)
                    .put("hostIp", _roomInfo.value),
            )
            hostClients[socket] = playerName
            refreshPlayers()
            broadcast(JSONObject().put("type", "chat").put("from", "").put("system", true).put("text", "$playerName entrou na sala"))
            appendSystem("$playerName entrou na sala")

            while (!socket.isClosed) {
                val line = reader.readLine() ?: break
                val msg = JSONObject(line)
                when (msg.optString("type")) {
                    "chat" -> {
                        val entry = JSONObject()
                            .put("type", "chat")
                            .put("from", playerName)
                            .put("text", msg.optString("text").take(500))
                        appendChat(playerName, msg.optString("text").take(500))
                        broadcast(entry)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("LanRoom").d(e, "Client handler ended")
        } finally {
            hostClients.remove(socket)
            runCatching { socket.close() }
            refreshPlayers()
            if (_status.value == Status.HOSTING) {
                appendSystem("$playerName saiu da sala")
                broadcast(JSONObject().put("type", "chat").put("from", "").put("system", true).put("text", "$playerName saiu da sala"))
            }
        }
    }

    private fun refreshPlayers() {
        _players.value = listOf(selfName) + hostClients.values.toList()
        broadcast(
            JSONObject()
                .put("type", "peers")
                .put("names", JSONArray(_players.value)),
        )
    }

    private fun broadcast(message: JSONObject) {
        val line = message.toString()
        for (socket in hostClients.keys) {
            runCatching {
                PrintWriter(socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8), true).println(line)
            }
        }
    }

    private fun runDiscoveryResponder() {
        try {
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(DISCOVERY_PORT))
            discoverySocket = socket
            val buffer = ByteArray(256)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                if (text.startsWith(DISCOVERY_PROBE)) {
                    val reply = DISCOVERY_REPLY_PREFIX + JSONObject()
                        .put("room", roomName)
                        .put("game", roomGameName)
                        .put("needsPassword", roomPassword.isNotEmpty())
                        .toString()
                    val bytes = reply.toByteArray(StandardCharsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                }
            }
        } catch (e: Exception) {
            Timber.tag("LanRoom").d(e, "Discovery responder ended")
        }
    }

    /** Broadcasts a probe and returns rooms that answered within [timeoutMs]. */
    suspend fun discoverRooms(context: Context, timeoutMs: Long = 1500): List<DiscoveredRoom> {
        acquireMulticastLock(context)
        val found = LinkedHashMap<String, DiscoveredRoom>()
        withTimeoutOrNull(timeoutMs + 500) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = timeoutMs.toInt()
                    val probe = DISCOVERY_PROBE.toByteArray(StandardCharsets.UTF_8)
                    socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                    val buffer = ByteArray(1024)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (e: Exception) {
                            break
                        }
                        val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                        if (text.startsWith(DISCOVERY_REPLY_PREFIX)) {
                            try {
                                val json = JSONObject(text.removePrefix(DISCOVERY_REPLY_PREFIX))
                                val ip = packet.address.hostAddress ?: continue
                                found[ip] = DiscoveredRoom(
                                    ip = ip,
                                    roomName = json.optString("room"),
                                    gameName = json.optString("game"),
                                    needsPassword = json.optBoolean("needsPassword"),
                                )
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("LanRoom").d(e, "Discovery probe failed")
            }
        }
        return found.values.toList()
    }

    @Synchronized
    fun joinRoom(context: Context, ip: String, password: String, playerName: String) {
        stop()
        selfName = playerName
        _chat.value = emptyList()
        _players.value = emptyList()
        _status.value = Status.JOINING
        acquireMulticastLock(context)

        clientJob = scope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip.trim(), ROOM_PORT), 5000)
                clientSocket = socket
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8), true)
                clientWriter = writer
                writer.println(
                    JSONObject()
                        .put("type", "join")
                        .put("name", playerName)
                        .put("password", password),
                )
                val replyLine = reader.readLine() ?: throw IllegalStateException("connection closed")
                val reply = JSONObject(replyLine)
                when (reply.optString("type")) {
                    "welcome" -> {
                        roomName = reply.optString("room")
                        roomGameName = reply.optString("game")
                        _roomInfo.value = ip.trim()
                        _status.value = Status.JOINED
                        appendSystem("Você entrou na sala \"$roomName\". Jogo: $roomGameName")
                        while (!socket.isClosed) {
                            val line = reader.readLine() ?: break
                            val msg = JSONObject(line)
                            when (msg.optString("type")) {
                                "chat" -> {
                                    if (msg.optBoolean("system", false)) {
                                        appendSystem(msg.optString("text"))
                                    } else {
                                        appendChat(msg.optString("from"), msg.optString("text"))
                                    }
                                }
                                "peers" -> {
                                    val names = msg.optJSONArray("names") ?: JSONArray()
                                    _players.value = (0 until names.length()).map { names.optString(it) }
                                }
                            }
                        }
                        if (_status.value == Status.JOINED) {
                            _status.value = Status.IDLE
                            appendSystem("A sala foi encerrada pelo anfitrião.")
                        }
                    }
                    "denied" -> {
                        _status.value = Status.DENIED
                        appendSystem("Entrada negada: senha incorreta.")
                        socket.close()
                    }
                    else -> throw IllegalStateException("unexpected reply")
                }
            } catch (e: Exception) {
                Timber.tag("LanRoom").w(e, "Join failed")
                if (_status.value == Status.JOINING || _status.value == Status.JOINED) {
                    _status.value = Status.ERROR
                    appendSystem("Não foi possível conectar em $ip. Confirme se os dois estão na mesma rede (ou na mesma VPN) e se a sala está aberta.")
                }
            }
        }
    }

    /** Sends a chat line (works both as host and as guest). */
    fun sendChat(text: String) {
        val trimmed = text.trim().take(500)
        if (trimmed.isEmpty()) return
        when (_status.value) {
            Status.HOSTING -> {
                appendChat(selfName, trimmed)
                broadcast(JSONObject().put("type", "chat").put("from", selfName).put("text", trimmed))
            }
            Status.JOINED -> {
                appendChat(selfName, trimmed)
                scope.launch {
                    runCatching { clientWriter?.println(JSONObject().put("type", "chat").put("text", trimmed)) }
                }
            }
            else -> {}
        }
    }

    val currentGameName: String get() = roomGameName

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        runCatching { discoverySocket?.close() }
        for (socket in hostClients.keys) runCatching { socket.close() }
        hostClients.clear()
        runCatching { clientSocket?.close() }
        clientSocket = null
        clientWriter = null
        hostJob?.cancel()
        clientJob?.cancel()
        hostJob = null
        clientJob = null
        serverSocket = null
        discoverySocket = null
        releaseMulticastLock()
        _status.value = Status.IDLE
        _players.value = emptyList()
        _roomInfo.value = ""
    }

    private fun appendChat(from: String, text: String) {
        _chat.value = (_chat.value + ChatMessage(from, text)).takeLast(200)
    }

    private fun appendSystem(text: String) {
        _chat.value = (_chat.value + ChatMessage("", text, system = true)).takeLast(200)
    }

    private fun acquireMulticastLock(context: Context) {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("gamenative-lan-room").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
}
