package com.example.gemini.assistant.share

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredPeer(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int = 43884,
    val platform: String = "unknown",
    val hasGpu: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Discovers nearby devices (Windows, Android, Linux, Mac) running Smart-To-Do or AntiBot
 * on the local Wi-Fi network using UDP broadcast on port 43884.
 */
class LanDiscoveryService private constructor(private val appContext: Context) {

    companion object {
        const val DISCOVERY_PORT = 43884
        const val PROTOCOL_TAG = "synctask_discovery_v1"

        @Volatile
        private var instance: LanDiscoveryService? = null

        fun getInstance(context: Context): LanDiscoveryService {
            return instance ?: synchronized(this) {
                instance ?: LanDiscoveryService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var isRunning = false

    private val localDeviceId: String by lazy {
        UUID.randomUUID().toString()
    }
    private val localDeviceName: String by lazy {
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (AntiBot)"
    }

    private val peersMap = ConcurrentHashMap<String, DiscoveredPeer>()
    private val _peersFlow = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peersFlow: StateFlow<List<DiscoveredPeer>> = _peersFlow.asStateFlow()

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            socket = DatagramSocket(DISCOVERY_PORT).apply {
                broadcast = true
                reuseAddress = true
            }
        } catch (_: Exception) {
            // Port might be temporarily occupied, try unbound
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                }
            } catch (_: Exception) {
                socket = null
            }
        }

        startListening()
        startBeaconLoop()
        startPruningLoop()
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        peersMap.clear()
        _peersFlow.value = emptyList()
    }

    private fun startListening() {
        scope.launch {
            val buffer = ByteArray(4096)
            while (isRunning && socket != null) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleIncomingPacket(raw, packet.address.hostAddress ?: "")
                } catch (_: Exception) {
                    delay(500)
                }
            }
        }
    }

    private fun handleIncomingPacket(rawJson: String, remoteIp: String) {
        try {
            val json = JSONObject(rawJson)
            if (json.optString("proto") != PROTOCOL_TAG) return

            val peerId = json.optString("id")
            if (peerId.isBlank() || peerId == localDeviceId) return

            val peerName = json.optString("name", "Nearby Device")
            val peerPort = json.optInt("tcp_port", DISCOVERY_PORT)
            val peerPlatform = json.optString("platform", "android")
            val peerHasGpu = json.optBoolean("has_gpu", false)

            val peer = DiscoveredPeer(
                id = peerId,
                name = peerName,
                ipAddress = remoteIp,
                port = peerPort,
                platform = peerPlatform,
                hasGpu = peerHasGpu,
                lastSeen = System.currentTimeMillis()
            )

            peersMap[peerId] = peer
            _peersFlow.value = peersMap.values.toList()
        } catch (_: Exception) {
            // Discard invalid packet
        }
    }

    private fun startBeaconLoop() {
        scope.launch {
            while (isRunning) {
                broadcastBeacon()
                delay(5000)
            }
        }
    }

    fun broadcastBeacon() {
        val s = socket ?: return
        try {
            val json = JSONObject().apply {
                put("proto", PROTOCOL_TAG)
                put("id", localDeviceId)
                put("name", localDeviceName)
                put("tcp_port", DISCOVERY_PORT)
                put("ts", System.currentTimeMillis())
                put("platform", "android")
                put("has_gpu", false)
            }

            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(bytes, bytes.size, broadcastAddr, DISCOVERY_PORT)
            s.send(packet)
        } catch (_: Exception) {
            // Network might be offline
        }
    }

    private fun startPruningLoop() {
        scope.launch {
            while (isRunning) {
                delay(6000)
                val cutoff = System.currentTimeMillis() - 20_000
                var modified = false
                peersMap.entries.removeIf { (_, peer) ->
                    val expired = peer.lastSeen < cutoff
                    if (expired) modified = true
                    expired
                }
                if (modified) {
                    _peersFlow.value = peersMap.values.toList()
                }
            }
        }
    }
}
