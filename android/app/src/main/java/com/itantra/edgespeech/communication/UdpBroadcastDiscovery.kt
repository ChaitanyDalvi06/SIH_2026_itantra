package com.itantra.edgespeech.communication

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class UdpBroadcastDiscovery(
    private val context: Context,
    val nodeId: String,
    private val tcpPort: Int = 8988,
    private val broadcastPort: Int = 8989
) {
    private var isRunning = false
    private var discoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var multicastLock: WifiManager.MulticastLock? = null

    var onPeerDiscovered: ((peerId: String, peerIp: String, peerPort: Int) -> Unit)? = null

    fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var fallbackIp: String? = null
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                val isWifiOrAp = name.contains("wlan") || name.contains("ap") || name.contains("p2p") || name.contains("eth")
                val isCellular = name.contains("rmnet") || name.contains("ccmni") || name.contains("dummy") || name.contains("tun")

                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.isNotEmpty() && ip != "127.0.0.1") {
                            // Prioritize local hotspot / Wi-Fi interface
                            if (isWifiOrAp) return ip
                            if (fallbackIp == null && !isCellular) {
                                fallbackIp = ip
                            }
                        }
                    }
                }
            }
            if (fallbackIp != null) return fallbackIp

            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) Formatter.formatIpAddress(ip) else "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    fun getGatewayIp(): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val gateway = wm?.dhcpInfo?.gateway ?: 0
            if (gateway != 0) Formatter.formatIpAddress(gateway) else null
        } catch (_: Exception) {
            null
        }
    }

    fun startDiscovery() {
        if (isRunning) return
        isRunning = true

        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("ITantraEdgeSpeechLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}

        discoveryJob = scope.launch {
            launch { listenForBeacons() }
            launch { broadcastBeacons() }
            launch { probeGatewayAndKnownSubnets() }
        }
    }

    private suspend fun listenForBeacons() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(broadcastPort))
                broadcast = true
            }

            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isRunning && isActive) {
                try {
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val senderIp = packet.address?.hostAddress ?: ""
                    val localIp = getLocalIpAddress()

                    val parts = message.split("|")
                    if (parts.size >= 4 && parts[0] == "ITANTRA_BEACON") {
                        val peerNodeId = parts[1]
                        val peerTcpPort = parts[3].toIntOrNull() ?: tcpPort

                        if (peerNodeId != nodeId && senderIp.isNotEmpty() && senderIp != localIp) {
                            onPeerDiscovered?.invoke(peerNodeId, senderIp, peerTcpPort)
                        }
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun broadcastBeacons() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { broadcast = true }

            while (isRunning && isActive) {
                val localIp = getLocalIpAddress()
                val beaconText = "ITANTRA_BEACON|$nodeId|$localIp|$tcpPort"
                val beaconBytes = beaconText.toByteArray(Charsets.UTF_8)

                // 1. Global broadcast 255.255.255.255
                try {
                    val globalBroadcast = InetAddress.getByName("255.255.255.255")
                    socket.send(DatagramPacket(beaconBytes, beaconBytes.size, globalBroadcast, broadcastPort))
                } catch (_: Exception) {}

                // 2. Subnet directed broadcasts (all active network interfaces)
                try {
                    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val intf = interfaces.nextElement()
                        if (intf.isLoopback || !intf.isUp) continue
                        for (addr in intf.interfaceAddresses) {
                            val bcast = addr.broadcast
                            if (bcast != null) {
                                socket.send(DatagramPacket(beaconBytes, beaconBytes.size, bcast, broadcastPort))
                            }
                        }
                    }
                } catch (_: Exception) {}

                // 3. Directed beacon to gateway if connected as a Wi-Fi / Hotspot client
                val gw = getGatewayIp()
                if (!gw.isNullOrEmpty() && gw != "0.0.0.0" && gw != localIp) {
                    try {
                        val hostAddr = InetAddress.getByName(gw)
                        socket.send(DatagramPacket(beaconBytes, beaconBytes.size, hostAddr, broadcastPort))
                    } catch (_: Exception) {}
                }

                // 4. Also directed beacon to standard hotspot fallback 192.168.43.1
                if (localIp.startsWith("192.168.43.") && localIp != "192.168.43.1") {
                    try {
                        val hostAddr = InetAddress.getByName("192.168.43.1")
                        socket.send(DatagramPacket(beaconBytes, beaconBytes.size, hostAddr, broadcastPort))
                    } catch (_: Exception) {}
                }

                delay(1000)
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int = 150): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun probeGatewayAndKnownSubnets() = withContext(Dispatchers.IO) {
        while (isRunning && isActive) {
            val localIp = getLocalIpAddress()
            val gw = getGatewayIp()

            // If we have an active gateway (hotspot host), verify port is open before reporting
            if (!gw.isNullOrEmpty() && gw != "0.0.0.0" && gw != localIp) {
                if (isPortOpen(gw, tcpPort, 150)) {
                    onPeerDiscovered?.invoke("Gateway_Host", gw, tcpPort)
                }
            }

            if (localIp.startsWith("192.168.43.") && localIp != "192.168.43.1") {
                if (isPortOpen("192.168.43.1", tcpPort, 150)) {
                    onPeerDiscovered?.invoke("Hotspot_Host", "192.168.43.1", tcpPort)
                }
            }

            delay(2000)
        }
    }

    fun stopDiscovery() {
        isRunning = false
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
        discoveryJob?.cancel()
        discoveryJob = null
    }
}
