package com.deadmanbp.universalremote

import android.content.Context
import android.text.Html
import android.util.Base64
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

private val Background = Color(0xFF101114)
private val SurfaceDark = Color(0xFF1B1D22)
private val Accent = Color(0xFFE04444)
private val Muted = Color(0xFF92959E)

data class FoundDevice(val name: String, val type: String, val host: String, val port: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UniversalRemoteApp(this) }
    }
}

@Composable
fun UniversalRemoteApp(context: Context) {
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FoundDevice?>(null) }
    var devices by remember { mutableStateOf(listOf<FoundDevice>()) }
    var status by remember { mutableStateOf("Ready to discover devices on your Wi-Fi network") }
    var showRemote by remember { mutableStateOf(false) }
    var remoteStatus by remember { mutableStateOf("Not connected") }
    var connected by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("tv_tokens", Context.MODE_PRIVATE) }
    val remoteClient = remember {
        SamsungRemoteClient(
            onStatus = { remoteStatus = it },
            onConnected = { connected = it },
            onToken = { host, token -> prefs.edit().putString(host, token).apply() }
        )
    }

    BackHandler(enabled = showRemote) {
        remoteClient.disconnect()
        showRemote = false
        connected = false
        remoteStatus = "Disconnected"
    }

    val discoverer = remember {
        TvDiscovery(context) { device ->
            val cleanName = Html.fromHtml(device.name, Html.FROM_HTML_MODE_LEGACY).toString()
            val clean = device.copy(name = cleanName)
            devices = (devices.filterNot { it.host == clean.host } + clean)
            status = "Found ${devices.size} possible device(s)"
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, background = Background, surface = SurfaceDark,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            if (showRemote && selected != null) {
                val device = selected!!
                if (device.type.contains("Xbox", ignoreCase = true)) {
                    XboxDiscoveryScreen(device) {
                        showRemote = false
                        selected = null
                    }
                } else if (!device.type.contains("Samsung", ignoreCase = true)) {
                    GoogleTvRemoteScreen(context, device) {
                        showRemote = false
                        selected = null
                    }
                } else Column(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            remoteClient.disconnect()
                            showRemote = false
                            connected = false
                        }) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("SAMSUNG TIZEN REMOTE", color = Muted, fontSize = 10.sp, letterSpacing = 1.4.sp)
                        }
                        Box(Modifier.size(10.dp).background(if (connected) Color(0xFF4CAF50) else Accent, CircleShape))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(remoteStatus, color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(24.dp))
                    RemoteKeyButton("▲", Modifier.size(76.dp)) { remoteClient.sendKey("KEY_UP") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemoteKeyButton("◀", Modifier.size(76.dp)) { remoteClient.sendKey("KEY_LEFT") }
                        Spacer(Modifier.width(16.dp))
                        RemoteKeyButton("OK", Modifier.size(86.dp), true) { remoteClient.sendKey("KEY_ENTER") }
                        Spacer(Modifier.width(16.dp))
                        RemoteKeyButton("▶", Modifier.size(76.dp)) { remoteClient.sendKey("KEY_RIGHT") }
                    }
                    RemoteKeyButton("▼", Modifier.size(76.dp)) { remoteClient.sendKey("KEY_DOWN") }
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RemoteKeyButton("BACK", Modifier.weight(1f).height(54.dp)) { remoteClient.sendKey("KEY_RETURN") }
                        Spacer(Modifier.width(12.dp))
                        RemoteKeyButton("HOME", Modifier.weight(1f).height(54.dp)) { remoteClient.sendKey("KEY_HOME") }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RemoteKeyButton("VOL −", Modifier.weight(1f).height(58.dp)) { remoteClient.sendKey("KEY_VOLDOWN") }
                        RemoteKeyButton("MUTE", Modifier.weight(1f).height(58.dp)) { remoteClient.sendKey("KEY_MUTE") }
                        RemoteKeyButton("VOL +", Modifier.weight(1f).height(58.dp)) { remoteClient.sendKey("KEY_VOLUP") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RemoteKeyButton("CH −", Modifier.weight(1f).height(54.dp)) { remoteClient.sendKey("KEY_CHDOWN") }
                        RemoteKeyButton("PLAY / PAUSE", Modifier.weight(1.5f).height(54.dp)) { remoteClient.sendKey("KEY_PLAY") }
                        RemoteKeyButton("CH +", Modifier.weight(1f).height(54.dp)) { remoteClient.sendKey("KEY_CHUP") }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            val savedToken = prefs.getString(device.host, null)
                            remoteClient.connect(device.host, savedToken)
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text(if (connected) "RECONNECT" else "CONNECT TO TV", fontWeight = FontWeight.Bold) }
                    Text("Approve the connection prompt on your Samsung TV if it appears.", color = Muted, fontSize = 11.sp)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tv, null, tint = Accent, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("UNIVERSAL", fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("REMOTE CONTROL", fontSize = 11.sp, color = Muted, letterSpacing = 2.sp)
                        }
                        Box(Modifier.size(10.dp).background(Accent, CircleShape))
                    }
                    Spacer(Modifier.height(30.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text("CURRENT DEVICE", color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(selected?.name ?: "No TV selected", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(status, color = Muted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            devices = emptyList()
                            scanning = true
                            status = "Searching for Samsung, Google and Xbox devices…"
                            discoverer.start {
                                scanning = false
                                status = if (devices.isEmpty()) "No compatible devices found yet. Make sure they are on and connected to Wi-Fi." else "Discovery finished • ${devices.size} device(s) found"
                            }
                        },
                        enabled = !scanning,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (scanning) CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Default.WifiFind, null)
                        Spacer(Modifier.width(9.dp))
                        Text(if (scanning) "SCANNING NETWORK…" else "FIND MY TVS", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("DISCOVERED DEVICES", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${devices.size}", color = Accent, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(9.dp))
                    if (devices.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f).border(1.dp, Color(0xFF303239), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.TvOff, null, tint = Muted, modifier = Modifier.size(38.dp))
                                Spacer(Modifier.height(10.dp))
                                Text("No televisions found", color = Muted)
                                Text("Tap Find My TVs to scan your network", color = Muted, fontSize = 11.sp)
                            }
                        }
                    } else {
                        Column(Modifier.weight(1f)) {
                            devices.forEach { device ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable {
                                        selected = device
                                        showRemote = true
                                        if (device.type.contains("Samsung")) {
                                            remoteStatus = "Ready to connect to ${device.host}"
                                        }
                                    },
                                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                                ) {
                                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Tv, null, tint = Accent)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(device.name, fontWeight = FontWeight.Bold)
                                            Text("${device.type} • ${device.host}:${device.port}", color = Muted, fontSize = 11.sp)
                                        }
                                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                                    }
                                }
                            }
                        }
                    }
                    Text("BUILD 0.4.0 • LOCAL NETWORK ONLY", color = Color(0xFF62656E), fontSize = 9.sp, letterSpacing = 1.2.sp)
                }
            }
        }
    }
}

@Composable
private fun RemoteKeyButton(label: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (accent) Accent else SurfaceDark,
        tonalElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (label.length > 5) 11.sp else 18.sp)
        }
    }
}

class SamsungRemoteClient(
    private val onStatus: (String) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val onToken: (String, String) -> Unit
) {
    private var socket: WebSocket? = null
    private var currentHost: String = ""
    private val client: OkHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun connect(host: String, token: String?) {
        currentHost = host
        disconnect()
        onConnected(false)
        onStatus("Connecting… approve the request on your TV")
        val name = Base64.encodeToString("Universal Remote".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val tokenPart = token?.let { "&token=$it" } ?: ""
        val request = Request.Builder()
            .url("wss://$host:8002/api/v2/channels/samsung.remote.control?name=$name$tokenPart")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("Connection opened. Waiting for Samsung authorization…")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    when (root.optString("event")) {
                        "ms.channel.connect" -> {
                            val data = root.optJSONObject("data")
                            val newToken = data?.optString("token")?.takeIf { it.isNotBlank() && it != "null" }
                            if (newToken != null) onToken(currentHost, newToken)
                            onConnected(true)
                            onStatus("Connected to Samsung TV")
                        }
                        "ms.channel.unauthorized" -> onStatus("Waiting for you to allow Universal Remote on the TV…")
                        else -> onStatus("Samsung TV responded")
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onConnected(false)
                onStatus("Connection failed: ${t.message ?: "check TV network remote settings"}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onConnected(false)
                onStatus("Disconnected")
            }
        })
    }

    fun sendKey(key: String) {
        val payload = JSONObject().put("method", "ms.remote.control")
            .put("params", JSONObject()
                .put("Cmd", "Click")
                .put("DataOfCmd", key)
                .put("Option", "false")
                .put("TypeOfRemote", "SendRemoteKey"))
        val sent = socket?.send(payload.toString()) ?: false
        if (!sent) onStatus("Not connected. Connect to the TV first.")
    }

    fun disconnect() {
        socket?.close(1000, "User disconnected")
        socket = null
        onConnected(false)
    }
}

class TvDiscovery(context: Context, private val onDevice: (FoundDevice) -> Unit) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val serviceTypes = listOf("_androidtvremote2._tcp.", "_samsungmsf._tcp.", "_smartview._tcp.")
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(onFinished: () -> Unit) {
        stop()
        val finished = AtomicBoolean(false)
        fun finishScan() {
            if (finished.compareAndSet(false, true)) {
                stop()
                mainHandler.post { onFinished() }
            }
        }

        serviceTypes.forEach { serviceType ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (finished.get()) return
                    runCatching {
                        manager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                if (finished.get()) return
                                val label = if (serviceType.contains("androidtv")) "Google TV" else "Samsung TV"
                                val found = FoundDevice(info.serviceName, label, info.host?.hostAddress ?: "unknown", info.port)
                                mainHandler.post { if (!finished.get()) onDevice(found) }
                            }
                        })
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    runCatching { manager.stopServiceDiscovery(this) }
                }
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
            }
            listeners.add(listener)
            runCatching { manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
        }

        // Samsung sets do not always advertise an mDNS service. Also probe the
        // local Wi-Fi subnet for Samsung's documented Smart TV HTTP API on port 8001.
        Thread({ scanSamsungSubnet() }, "Samsung-TV-Subnet-Scan").start()

        // Xbox SmartGlass discovery uses UDP 5050 rather than mDNS.
        Thread({ scanXboxSmartGlass() }, "Xbox-SmartGlass-Discovery").start()

        // Keep DNS-SD discovery alive long enough to receive delayed announcements.
        mainHandler.postDelayed({ finishScan() }, 12000)
    }

    private fun scanXboxSmartGlass() {
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 450
                val request = java.nio.ByteBuffer.allocate(16)
                    .order(java.nio.ByteOrder.BIG_ENDIAN)
                    .putShort(0xDD00.toShort())
                    .putShort(10)
                    .putShort(0)
                    .putInt(0)
                    .putShort(0x08)
                    .putShort(0)
                    .putShort(2)
                    .array()

                listOf("255.255.255.255", "239.255.255.250").forEach { target ->
                    runCatching {
                        socket.send(DatagramPacket(
                            request, request.size, InetAddress.getByName(target), 5050
                        ))
                    }
                }

                val deadline = System.currentTimeMillis() + 4500
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val response = DatagramPacket(buffer, buffer.size)
                        socket.receive(response)
                        val data = response.data.copyOfRange(0, response.length)
                        val consoleName = parseXboxDiscoveryName(data) ?: continue
                        val host = response.address.hostAddress ?: continue
                        mainHandler.post {
                            onDevice(FoundDevice(consoleName, "Xbox Series • SmartGlass", host, 5050))
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Continue listening until the discovery window closes.
                    }
                }
            }
        }
    }

    private fun parseXboxDiscoveryName(packet: ByteArray): String? {
        if (packet.size < 16) return null
        val buffer = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.BIG_ENDIAN)
        if ((buffer.short.toInt() and 0xFFFF) != 0xDD01) return null
        val payloadLength = buffer.short.toInt() and 0xFFFF
        if (payloadLength <= 0 || payloadLength > packet.size - 6) return null

        // Response fields: flags, client type, then a length-prefixed console name.
        for (offset in listOf(14, 8)) {
            if (offset + 2 > packet.size) continue
            val length = ((packet[offset].toInt() and 0xFF) shl 8) or
                (packet[offset + 1].toInt() and 0xFF)
            if (length !in 1..128 || offset + 2 + length > packet.size) continue
            val name = String(packet, offset + 2, length, Charsets.UTF_8)
                .trim()
                .takeIf { it.isNotBlank() && it.none { ch -> ch.isISOControl() } }
            if (name != null) return name
        }
        return "Xbox Console"
    }

    private fun scanSamsungSubnet() {
        val subnet = findWifiSubnet() ?: return
        val (network, prefix, ownAddress) = subnet
        val hostCount = 1 shl (32 - prefix)
        val executor = Executors.newFixedThreadPool(32)
        for (host in 1 until hostCount - 1) {
            val addressInt = network or host
            if (addressInt == ownAddress) continue
            val address = intToAddress(addressInt)
            executor.submit {
                probeSamsungApi(address)
            }
        }
        executor.shutdown()
        runCatching { executor.awaitTermination(10, TimeUnit.SECONDS) }
        executor.shutdownNow()
    }

    private fun findWifiSubnet(): Triple<Int, Int, Int>? {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrNull()
            ?: return null
        val active = interfaces.filter { iface ->
            runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
        }.sortedBy { iface ->
            if (iface.name.startsWith("wlan", ignoreCase = true) ||
                iface.name.startsWith("wifi", ignoreCase = true)) 0 else 1
        }

        for (iface in active) {
            for (entry in iface.interfaceAddresses) {
                val ipv4 = entry.address as? Inet4Address ?: continue
                val prefix = entry.networkPrefixLength.toInt().coerceAtLeast(24)
                if (prefix !in 24..30) continue
                val bytes = ipv4.address
                val own = ((bytes[0].toInt() and 255) shl 24) or
                    ((bytes[1].toInt() and 255) shl 16) or
                    ((bytes[2].toInt() and 255) shl 8) or
                    (bytes[3].toInt() and 255)
                val mask = -1 shl (32 - prefix)
                return Triple(own and mask, prefix, own)
            }
        }
        return null
    }

    private fun intToAddress(value: Int): String =
        "${(value ushr 24) and 255}.${(value ushr 16) and 255}.${(value ushr 8) and 255}.${value and 255}"

    private fun probeSamsungApi(host: String) {
        val open = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, 8001), 350)
            }
            true
        }.getOrDefault(false)
        if (!open) return

        val connection = runCatching {
            (URL("http://$host:8001/api/v2/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 700
                readTimeout = 700
                useCaches = false
            }
        }.getOrNull() ?: return

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val device = root.optJSONObject("device") ?: return
            val name = device.optString("name").takeIf { it.isNotBlank() }
                ?: device.optString("model").takeIf { it.isNotBlank() }
                ?: "Samsung Smart TV"
            mainHandler.post {
                onDevice(FoundDevice(name, "Samsung Tizen", host, 8001))
            }
        } catch (_: Exception) {
            // An open port alone is not enough to label a device as a Samsung TV.
        } finally {
            connection.disconnect()
        }
    }

    fun stop() {
        listeners.toList().forEach { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        listeners.clear()
    }
}


@Composable
private fun XboxDiscoveryScreen(device: FoundDevice, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("XBOX SERIES • SMARTGLASS DISCOVERY", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(36.dp))
        Icon(Icons.Default.SportsEsports, null, tint = Accent, modifier = Modifier.size(58.dp))
        Spacer(Modifier.height(18.dp))
        Text("XBOX FOUND", fontWeight = FontWeight.Black, fontSize = 22.sp)
        Spacer(Modifier.height(10.dp))
        Text("Console responded to local SmartGlass discovery.", color = Color(0xFF4CAF50))
        Spacer(Modifier.height(8.dp))
        Text("${device.host}:5050", color = Muted)
        Spacer(Modifier.height(24.dp))
        Text(
            "This is the first connection milestone. The encrypted SmartGlass session and media controls are not enabled in this build yet.",
            color = Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) { Text("BACK TO DEVICES", fontWeight = FontWeight.Bold) }
    }
}
