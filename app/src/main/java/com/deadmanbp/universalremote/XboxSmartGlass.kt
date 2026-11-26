package com.deadmanbp.universalremote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val XboxBackground = Color(0xFF101114)
private val XboxSurface = Color(0xFF1B1D22)
private val XboxAccent = Color(0xFFE04444)
private val XboxMuted = Color(0xFF92959E)

private data class XboxDiscoveryInfo(
    val name: String,
    val uuid: UUID,
    val flags: Int,
    val lastError: Long,
    val certificate: X509Certificate
) {
    val anonymousAllowed: Boolean get() = flags and 0x04 != 0
}

private data class XboxHandshakeResult(
    val message: String,
    val success: Boolean,
    val paired: Boolean = false
)

/**
 * Minimal SmartGlass client for the initial encrypted ConnectRequest exchange.
 * It deliberately stops after validating ConnectResponse; service channels and
 * remote commands are added in a later, independently testable step.
 */
private class XboxSmartGlassClient(private val host: String) : Closeable {
    private var socket: DatagramSocket? = null
    private var connected = false

    @Synchronized
    fun connect(): XboxHandshakeResult {
        closeSocket()
        return try {
            val udp = DatagramSocket()
            socket = udp
            udp.soTimeout = 500
            udp.broadcast = true

            val discovery = receiveDiscovery(udp)
            val peerKey = discovery.certificate.publicKey as? ECPublicKey
                ?: error("Xbox certificate does not contain an elliptic-curve public key")

            val keyPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(peerKey.params, SecureRandom())
            }.generateKeyPair()
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(keyPair.private)
            agreement.doPhase(peerKey, true)
            val sharedSecret = agreement.generateSecret()
            val derived = deriveKeys(sharedSecret)

            val fieldBytes = (peerKey.params.curve.field.fieldSize + 7) / 8
            val publicPoint = keyPair.public as ECPublicKey
            val publicKeyBytes = publicPoint.w.affineX.toFixedBytes(fieldBytes) +
                publicPoint.w.affineY.toFixedBytes(fieldBytes)
            val keyType = when (fieldBytes) {
                32 -> 0
                48 -> 1
                66 -> 2
                else -> error("Unsupported Xbox elliptic curve size: $fieldBytes bytes")
            }

            val iv = ByteArray(16).also(SecureRandom()::nextBytes)
            val request = buildConnectRequest(
                clientId = UUID.randomUUID(),
                keyType = keyType,
                publicKey = publicKeyBytes,
                iv = iv,
                keys = derived
            )
            udp.soTimeout = 1000
            udp.send(DatagramPacket(request, request.size, InetAddress.getByName(host), 5050))

            val response = receiveConnectResponse(udp, derived)
            val resultCode = response.first
            val pairingState = response.second
            val participantId = response.third

            when (resultCode) {
                0 -> {
                    connected = true
                    val pairing = if (pairingState == 1) "paired" else "not paired"
                    XboxHandshakeResult(
                        "Encrypted SmartGlass handshake accepted. Console reports $pairing (participant $participantId). Service channels are the next step.",
                        true,
                        pairingState == 1
                    )
                }
                1 -> XboxHandshakeResult("Xbox accepted the request as pending. It has not confirmed a session yet.", false)
                2 -> XboxHandshakeResult("Xbox rejected the connection: unknown error.", false)
                3 -> XboxHandshakeResult("Xbox rejected the connection: anonymous connections are disabled. SmartGlass discovery works, but account authentication is required.", false)
                4 -> XboxHandshakeResult("Xbox rejected the connection: device limit reached.", false)
                5 -> XboxHandshakeResult("Xbox reports that SmartGlass connections are disabled.", false)
                6, 7, 8, 9 -> XboxHandshakeResult("Xbox requires Microsoft account authentication (connection result $resultCode).", false)
                else -> XboxHandshakeResult("Xbox returned an unrecognized connection result: $resultCode.", false)
            }
        } catch (e: Exception) {
            closeSocket()
            XboxHandshakeResult("SmartGlass connection failed: ${e.message ?: e.javaClass.simpleName}", false)
        }
    }

    private fun receiveDiscovery(udp: DatagramSocket): XboxDiscoveryInfo {
        val request = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putShort(0xDD00.toShort())
            .putShort(10)
            .putShort(0)
            .putInt(0)
            .putShort(0x08)
            .putShort(0)
            .putShort(2)
            .array()

        // A directed discovery request avoids relying on the router's broadcast handling.
        udp.send(DatagramPacket(request, request.size, InetAddress.getByName(host), 5050))
        val deadline = System.currentTimeMillis() + 5000
        val buffer = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                if (packet.address.hostAddress != host) continue
                val parsed = parseDiscoveryResponse(packet.data.copyOfRange(0, packet.length))
                if (parsed != null) return parsed
            } catch (_: java.net.SocketTimeoutException) {
                // Keep the directed discovery window open.
            }
        }
        error("No complete SmartGlass discovery response from $host. Try scanning again.")
    }

    private fun parseDiscoveryResponse(packet: ByteArray): XboxDiscoveryInfo? = runCatching {
        val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        require(input.remaining() >= 6)
        require((input.short.toInt() and 0xFFFF) == 0xDD01) { "Not a SmartGlass discovery response" }
        val payloadLength = input.short.toInt() and 0xFFFF
        val version = input.short.toInt() and 0xFFFF
        require(version == 0) { "Unexpected discovery packet version $version" }
        require(payloadLength <= input.remaining() && payloadLength >= 12) { "Incomplete discovery payload" }

        val flags = input.int
        input.short // client type
        val name = readSmartGlassString(input)
        val uuidText = readSmartGlassString(input)
        val lastError = input.int.toLong() and 0xFFFFFFFFL
        val certLength = input.short.toInt() and 0xFFFF
        require(certLength > 0 && certLength <= input.remaining()) { "Console certificate is missing or truncated" }
        val certBytes = ByteArray(certLength)
        input.get(certBytes)
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        XboxDiscoveryInfo(name, UUID.fromString(uuidText), flags, lastError, certificate)
    }.getOrNull()

    private fun readSmartGlassString(input: ByteBuffer): String {
        val length = input.short.toInt() and 0xFFFF
        require(length <= input.remaining() - 1) { "Invalid SmartGlass string length" }
        val bytes = ByteArray(length)
        input.get(bytes)
        require(input.get().toInt() == 0) { "SmartGlass string was not null terminated" }
        return String(bytes, Charsets.UTF_8)
    }

    private data class SessionKeys(val encryption: ByteArray, val iv: ByteArray, val hmac: ByteArray)

    private fun deriveKeys(sharedSecret: ByteArray): SessionKeys {
        val firstSalt = hex("D637F1AAE2F0418C")
        val secondSalt = hex("A8F81A574E228AB7")
        val expanded = MessageDigest.getInstance("SHA-512")
            .digest(firstSalt + sharedSecret + secondSalt)
        return SessionKeys(
            expanded.copyOfRange(0, 16),
            expanded.copyOfRange(16, 32),
            expanded.copyOfRange(32, 64)
        )
    }

    private fun buildConnectRequest(
        clientId: UUID,
        keyType: Int,
        publicKey: ByteArray,
        iv: ByteArray,
        keys: SessionKeys
    ): ByteArray {
        val unprotected = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeLong(clientId.mostSignificantBits)
                out.writeLong(clientId.leastSignificantBits)
                out.writeShort(keyType)
                out.write(publicKey)
                out.write(iv)
            }
        }.toByteArray()

        val protectedClear = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                writeSmartGlassString(out, "") // userhash: anonymous connection attempt
                writeSmartGlassString(out, "") // XSTS token: anonymous connection attempt
                out.writeInt(0) // request number
                out.writeInt(0) // request group start
                out.writeInt(1) // request group end (exclusive)
            }
        }.toByteArray()

        val padded = pkcs7Pad(protectedClear, 16)
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), IvParameterSpec(iv))
            doFinal(padded)
        }

        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putShort(0xCC00.toShort())
            .putShort(unprotected.size.toShort())
            .putShort(protectedClear.size.toShort())
            .putShort(2)
            .array()
        val authenticated = header + unprotected + encrypted
        return authenticated + hmac(keys.hmac, authenticated)
    }

    private fun receiveConnectResponse(
        udp: DatagramSocket,
        keys: SessionKeys
    ): Triple<Int, Int, Long> {
        val deadline = System.currentTimeMillis() + 7000
        val buffer = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                if (packet.address.hostAddress != host) continue
                val data = packet.data.copyOfRange(0, packet.length)
                if (data.size < 8 || readU16(data, 0) != 0xCC01) continue

                val unprotectedLength = readU16(data, 2)
                val protectedLength = readU16(data, 4)
                val version = readU16(data, 6)
                require(version == 2) { "Unexpected ConnectResponse version $version" }
                require(unprotectedLength == 16) { "Unexpected ConnectResponse IV length $unprotectedLength" }
                val encryptedLength = ((protectedLength + 15) / 16) * 16
                val expectedSize = 8 + unprotectedLength + encryptedLength + 32
                require(data.size == expectedSize) { "Malformed ConnectResponse size: expected $expectedSize, received ${data.size}" }

                val authenticatedLength = data.size - 32
                val signed = data.copyOfRange(0, authenticatedLength)
                val receivedHash = data.copyOfRange(authenticatedLength, data.size)
                require(MessageDigest.isEqual(hmac(keys.hmac, signed), receivedHash)) {
                    "Xbox ConnectResponse HMAC verification failed"
                }

                val responseIv = data.copyOfRange(8, 24)
                val ciphertext = data.copyOfRange(24, 24 + encryptedLength)
                val clearPadded = Cipher.getInstance("AES/CBC/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), IvParameterSpec(responseIv))
                    doFinal(ciphertext)
                }
                val clear = pkcs7Unpad(clearPadded)
                require(clear.size >= 8) { "ConnectResponse payload is too short" }
                val input = ByteBuffer.wrap(clear).order(ByteOrder.BIG_ENDIAN)
                val result = input.short.toInt() and 0xFFFF
                val pairing = input.short.toInt() and 0xFFFF
                val participant = input.int.toLong() and 0xFFFFFFFFL
                return Triple(result, pairing, participant)
            } catch (_: java.net.SocketTimeoutException) {
                // Wait for a response until the overall deadline expires.
            }
        }
        error("Timed out waiting for Xbox ConnectResponse on UDP 5050")
    }

    private fun writeSmartGlassString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF)
        out.writeShort(bytes.size)
        out.write(bytes)
        out.writeByte(0)
    }

    private fun pkcs7Pad(data: ByteArray, blockSize: Int): ByteArray {
        val amount = blockSize - (data.size % blockSize)
        return data + ByteArray(amount) { amount.toByte() }
    }

    private fun pkcs7Unpad(data: ByteArray): ByteArray {
        require(data.isNotEmpty())
        val amount = data.last().toInt() and 0xFF
        require(amount in 1..16 && amount <= data.size) { "Invalid SmartGlass PKCS#7 padding" }
        for (i in data.size - amount until data.size) {
            require((data[i].toInt() and 0xFF) == amount) { "Invalid SmartGlass PKCS#7 padding" }
        }
        return data.copyOfRange(0, data.size - amount)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun BigInteger.toFixedBytes(length: Int): ByteArray {
        val raw = toByteArray()
        val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(unsigned.size <= length) { "EC coordinate is larger than its curve size" }
        return ByteArray(length - unsigned.size) + unsigned
    }

    private fun closeSocket() {
        connected = false
        runCatching { socket?.close() }
        socket = null
    }

    override fun close() {
        closeSocket()
    }
}

@Composable
fun XboxRemoteScreen(device: FoundDevice, onBack: () -> Unit) {
    var status by remember(device.host) {
        mutableStateOf("Ready to request an encrypted SmartGlass connection.")
    }
    var connecting by remember(device.host) { mutableStateOf(false) }
    var connected by remember(device.host) { mutableStateOf(false) }
    val client = remember(device.host) { XboxSmartGlassClient(device.host) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    DisposableEffect(client) {
        onDispose { client.close() }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(XboxBackground).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("XBOX SERIES • SMARTGLASS", color = XboxMuted, fontSize = 10.sp, letterSpacing = 1.2.sp)
            }
            Box(Modifier.size(10.dp).background(if (connected) Color(0xFF4CAF50) else XboxAccent, CircleShape))
        }
        Spacer(Modifier.height(30.dp))
        Icon(Icons.Default.SportsEsports, null, tint = XboxAccent, modifier = Modifier.size(58.dp))
        Spacer(Modifier.height(18.dp))
        Text(if (connected) "CONSOLE CONNECTED" else "XBOX CONNECTION", fontWeight = FontWeight.Black, fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        Text(device.host + ":5050", color = XboxMuted, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = XboxSurface
        ) {
            Text(status, modifier = Modifier.padding(18.dp), color = if (connected) Color(0xFF4CAF50) else XboxMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                connecting = true
                status = "Reading the console certificate and negotiating encrypted SmartGlass keys…"
                Thread({
                    val result = client.connect()
                    mainHandler.post {
                        status = result.message
                        connected = result.success
                        connecting = false
                    }
                }, "Xbox-SmartGlass-Connect").start()
            },
            enabled = !connecting && !connected,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = XboxAccent),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (connecting) CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(if (connected) "CONNECTED" else "CONNECT TO XBOX", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                client.close()
                onBack()
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("BACK TO DEVICES") }
    }
}
