package com.deadmanbp.universalremote

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun GoogleTvRemoteScreen(context: Context, device: FoundDevice, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("android_tv_remote", Context.MODE_PRIVATE) }
    val hosts = remember { prefs.getStringSet("paired_hosts", emptySet())?.toSet() ?: emptySet() }
    var paired by remember { mutableStateOf(device.host in hosts) }
    var started by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (paired) "Paired identity saved" else "Not paired") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    val identity = remember { AndroidTvIdentity.load(context) }
    var pairing by remember { mutableStateOf<AndroidTvPairing?>(null) }
    var remote by remember { mutableStateOf<AndroidTvRemote?>(null) }

    DisposableEffect(device.host) {
        onDispose { pairing?.close(); remote?.close() }
    }

    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("PHILIPS GOOGLE TV REMOTE", color = Color(0xFF92959E), fontSize = 10.sp)
            }
            Box(Modifier.size(10.dp).background(if (connected) Color(0xFF4CAF50) else Color(0xFFE04444), CircleShape))
        }
        Spacer(Modifier.height(12.dp))
        Text(status, color = Color(0xFF92959E), fontSize = 12.sp)

        if (!paired) {
            Spacer(Modifier.height(26.dp))
            Text("PAIR WITH YOUR TV", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                if (started) "A six-character code should now be showing on your Philips TV."
                else "Pair once to authorize this phone. The certificate is saved in app storage.",
                color = Color(0xFF92959E), fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))
            if (started) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { value -> code = value.uppercase().filter { it in "0123456789ABCDEF" }.take(6) },
                    label = { Text("TV pairing code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val session = pairing ?: return@Button
                        busy = true
                        scope.launch {
                            val result = session.finish(code)
                            busy = false
                            result.onSuccess {
                                paired = true
                                prefs.edit().putStringSet("paired_hosts", (hosts + device.host).toSet()).apply()
                                status = "Pairing successful. Connect to the TV."
                            }.onFailure { status = it.message ?: "Pairing failed" }
                        }
                    },
                    enabled = !busy && code.length == 6,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE04444))
                ) { Text(if (busy) "VERIFYING…" else "VERIFY PAIRING CODE") }
            } else {
                Button(
                    onClick = {
                        busy = true
                        val session = AndroidTvPairing(device.host, identity)
                        pairing = session
                        scope.launch {
                            val result = session.begin()
                            busy = false
                            result.onSuccess {
                                started = true
                                status = "Enter the code displayed on the TV"
                            }.onFailure { status = "Pairing could not start: " + (it.message ?: "unknown error") }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE04444))
                ) { Text(if (busy) "STARTING PAIRING…" else "START PAIRING") }
            }
        } else {
            Spacer(Modifier.height(18.dp))
            Text("NAVIGATION", color = Color(0xFF92959E), fontSize = 10.sp)
            Spacer(Modifier.height(12.dp))
            TvButton("▲", Modifier.size(72.dp)) { remote?.sendKey(AndroidTvKey.UP) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvButton("◀", Modifier.size(72.dp)) { remote?.sendKey(AndroidTvKey.LEFT) }
                Spacer(Modifier.width(14.dp))
                TvButton("OK", Modifier.size(82.dp), true) { remote?.sendKey(AndroidTvKey.OK) }
                Spacer(Modifier.width(14.dp))
                TvButton("▶", Modifier.size(72.dp)) { remote?.sendKey(AndroidTvKey.RIGHT) }
            }
            TvButton("▼", Modifier.size(72.dp)) { remote?.sendKey(AndroidTvKey.DOWN) }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("BACK", Modifier.weight(1f).height(52.dp)) { remote?.sendKey(AndroidTvKey.BACK) }
                TvButton("HOME", Modifier.weight(1f).height(52.dp)) { remote?.sendKey(AndroidTvKey.HOME) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("VOL −", Modifier.weight(1f).height(54.dp)) { remote?.sendKey(AndroidTvKey.VOLUME_DOWN) }
                TvButton("MUTE", Modifier.weight(1f).height(54.dp)) { remote?.sendKey(AndroidTvKey.MUTE) }
                TvButton("VOL +", Modifier.weight(1f).height(54.dp)) { remote?.sendKey(AndroidTvKey.VOLUME_UP) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("PLAY", Modifier.weight(1f).height(50.dp)) { remote?.sendKey(AndroidTvKey.PLAY) }
                TvButton("PAUSE", Modifier.weight(1f).height(50.dp)) { remote?.sendKey(AndroidTvKey.PAUSE) }
                TvButton("MENU", Modifier.weight(1f).height(50.dp)) { remote?.sendKey(AndroidTvKey.MENU) }
            }
            Spacer(Modifier.height(14.dp))
            AppShortcutsRow(isSamsung = false) { app ->
                remote?.launchApp(app.googlePackage, app.googleAppLink)
                status = "Sent " + app.name + " launch request"
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    busy = true
                    val client = AndroidTvRemote(device.host, identity) { message ->
                        connected = false
                        status = message
                    }
                    remote?.close()
                    remote = client
                    scope.launch {
                        val result = client.connect()
                        busy = false
                        result.onSuccess {
                            connected = true
                            status = "Connected to Philips Google TV"
                        }.onFailure { status = "Connection failed: " + (it.message ?: "unknown error") }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE04444))
            ) { Text(if (busy) "CONNECTING…" else if (connected) "RECONNECT" else "CONNECT TO TV") }
            Text("Your pairing certificate stays on this phone.", color = Color(0xFF92959E), fontSize = 10.sp)
        }
    }
}

@Composable
private fun TvButton(label: String, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (accent) Color(0xFFE04444) else Color(0xFF1B1D22)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
