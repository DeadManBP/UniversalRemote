package com.deadmanbp.universalremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    var selected by remember { mutableStateOf("No TV selected") }
    var devices by remember { mutableStateOf(listOf<FoundDevice>()) }
    var status by remember { mutableStateOf("Ready to discover TVs on your Wi-Fi network") }

    val discoverer = remember {
        TvDiscovery(context) { device ->
            devices = (devices + device).distinctBy { "${it.host}:${it.port}" }
            status = "Found ${devices.size} possible device(s)"
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, background = Background, surface = SurfaceDark,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
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
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("CURRENT DEVICE", color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(selected, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(status, color = Muted, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        devices = emptyList()
                        scanning = true
                        status = "Searching for Samsung and Google TVs…"
                        discoverer.start {
                            scanning = false
                            status = if (devices.isEmpty()) "No compatible services found yet. Make sure both TVs are on and connected to Wi-Fi." else "Discovery finished • ${devices.size} device(s) found"
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
                    Box(
                        Modifier.fillMaxWidth().weight(1f).border(1.dp, Color(0xFF303239), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
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
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                    .clickable { selected = device.name },
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

                Text("BUILD 0.1.0 • LOCAL NETWORK ONLY", color = Color(0xFF62656E), fontSize = 9.sp, letterSpacing = 1.2.sp)
            }
        }
    }
}

class TvDiscovery(context: Context, private val onDevice: (FoundDevice) -> Unit) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val serviceTypes = listOf("_androidtvremote2._tcp.", "_samsungmsf._tcp.", "_smartview._tcp.")

    fun start(onFinished: () -> Unit) {
        stop()
        var remaining = serviceTypes.size
        serviceTypes.forEach { serviceType ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val label = when {
                                serviceType.contains("androidtv") -> "Google TV"
                                else -> "Samsung TV"
                            }
                            onDevice(FoundDevice(info.serviceName, label, info.host?.hostAddress ?: "unknown", info.port))
                        }
                    })
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) { finishOne() }
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
                private fun finishOne() {
                    runCatching { manager.stopServiceDiscovery(this) }
                    remaining--
                    if (remaining <= 0) onFinished()
                }
            }
            listeners.add(listener)
            runCatching { manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure {
                    remaining--
                    if (remaining <= 0) onFinished()
                }
        }
        // Discovery window is intentionally short for this first prototype.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stop()
            onFinished()
        }, 9000)
    }

    fun stop() {
        listeners.toList().forEach { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        listeners.clear()
    }
}
