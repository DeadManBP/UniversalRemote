package com.deadmanbp.universalremote

import android.content.Context
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android TV Remote v2 transport. The identity is stored in app-private storage:
 * the TV trusts this exact certificate, so it must survive app restarts/upgrades.
 */
class AndroidTvIdentity private constructor(
    val privateKey: java.security.PrivateKey,
    val certificate: X509Certificate
) {
    val keyStore: KeyStore by lazy {
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", privateKey, PASSWORD, arrayOf<java.security.cert.Certificate>(certificate))
        }
    }

    companion object {
        private const val FILE = "android_tv_remote_identity.p12"
        private val PASSWORD = "remote-identity".toCharArray()

        @Synchronized
        fun load(context: Context): AndroidTvIdentity {
            val file = File(context.filesDir, FILE)
            if (file.exists()) {
                runCatching {
                    val store = KeyStore.getInstance("PKCS12")
                    file.inputStream().use { store.load(it, PASSWORD) }
                    return AndroidTvIdentity(
                        store.getKey("client", PASSWORD) as java.security.PrivateKey,
                        store.getCertificate("client") as X509Certificate
                    )
                }
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "atvremote").build()
            val now = System.currentTimeMillis()
            val certBuilder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L),
                Date(now + 20L * 365 * 86_400_000L), name, pair.public
            ).addExtension(Extension.basicConstraints, false, BasicConstraints(0))
            val cert = JcaX509CertificateConverter().getCertificate(
                certBuilder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            )
            val identity = AndroidTvIdentity(pair.private, cert)
            file.outputStream().use { identity.keyStore.store(it, PASSWORD) }
            return identity
        }
    }

    internal fun keyManagers() = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore, PASSWORD) }.keyManagers
}

private class TvTrustManager : X509TrustManager {
    @Volatile var peer: X509Certificate? = null
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { peer = chain.firstOrNull() }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class TvTlsConnection(
    val socket: SSLSocket,
    private val trust: TvTrustManager
) : Closeable {
    val serverCertificate: X509Certificate?
        get() = trust.peer ?: runCatching { socket.session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
    override fun close() { runCatching { socket.close() } }
}

private fun openTvTls(host: String, port: Int, identity: AndroidTvIdentity, timeout: Int = 5000): TvTlsConnection {
    val trust = TvTrustManager()
    val ssl = SSLContext.getInstance("TLS").apply {
        init(identity.keyManagers(), arrayOf(trust), SecureRandom())
    }
    val socket = ssl.socketFactory.createSocket() as SSLSocket
    socket.connect(InetSocketAddress(host, port), timeout)
    socket.soTimeout = 8000
    socket.startHandshake()
    return TvTlsConnection(socket, trust)
}

private class PbWriter {
    private val out = ByteArrayOutputStream()
    fun number(field: Int, value: Long) = apply {
        rawVarint(out, ((field shl 3)).toLong()); rawVarint(out, value)
    }
    fun number(field: Int, value: Int) = number(field, value.toLong())
    fun bytes(field: Int, value: ByteArray) = apply {
        rawVarint(out, ((field shl 3) or 2).toLong()); rawVarint(out, value.size.toLong()); out.write(value)
    }
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun message(field: Int, block: PbWriter.() -> Unit) = bytes(field, PbWriter().apply(block).result())
    fun result() = out.toByteArray()
}

private sealed class PbValue {
    data class Number(val value: Long) : PbValue()
    data class Data(val value: ByteArray) : PbValue()
}

private fun rawVarint(out: OutputStream, value: Long) {
    var n = value
    while (true) {
        val b = (n and 0x7f).toInt()
        n = n ushr 7
        if (n == 0L) { out.write(b); return }
        out.write(b or 0x80)
    }
}

private fun InputStream.readPbFrame(): ByteArray {
    var length = 0L
    var shift = 0
    while (true) {
        val b = read()
        if (b < 0) throw EOFException("TV closed the connection")
        length = length or ((b and 0x7f).toLong() shl shift)
        if (b and 0x80 == 0) break
        shift += 7
        if (shift > 28) throw IllegalStateException("Invalid message length")
    }
    require(length in 0..1_000_000) { "Invalid TV message size" }
    val result = ByteArray(length.toInt())
    var offset = 0
    while (offset < result.size) {
        val count = read(result, offset, result.size - offset)
        if (count < 0) throw EOFException("TV closed mid-message")
        offset += count
    }
    return result
}

private fun OutputStream.writePbFrame(payload: ByteArray) {
    rawVarint(this, payload.size.toLong())
    write(payload)
    flush()
}

private fun parsePb(data: ByteArray): Map<Int, List<PbValue>> {
    var pos = 0
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= data.size || shift > 63) throw EOFException("Malformed protobuf")
            val b = data[pos++].toInt() and 255
            result = result or ((b and 127).toLong() shl shift)
            if (b and 128 == 0) return result
            shift += 7
        }
    }
    val fields = mutableMapOf<Int, MutableList<PbValue>>()
    while (pos < data.size) {
        val tag = varint().toInt()
        val field = tag ushr 3
        when (tag and 7) {
            0 -> fields.getOrPut(field) { mutableListOf() }.add(PbValue.Number(varint()))
            1 -> pos += 8
            2 -> {
                val len = varint().toInt()
                if (len < 0 || pos + len > data.size) throw EOFException("Malformed protobuf field")
                fields.getOrPut(field) { mutableListOf() }.add(PbValue.Data(data.copyOfRange(pos, pos + len)))
                pos += len
            }
            5 -> pos += 4
            else -> throw IllegalStateException("Unsupported protobuf wire type")
        }
        if (pos > data.size) throw EOFException("Malformed protobuf")
    }
    return fields
}

private fun Map<Int, List<PbValue>>.has(field: Int) = containsKey(field)
private fun Map<Int, List<PbValue>>.num(field: Int) = (this[field]?.firstOrNull() as? PbValue.Number)?.value
private fun Map<Int, List<PbValue>>.nested(field: Int) =
    (this[field]?.firstOrNull() as? PbValue.Data)?.value?.let(::parsePb)
private fun BigInteger.unsigned() = toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

class AndroidTvPairing(private val host: String, private val identity: AndroidTvIdentity) : Closeable {
    private var connection: TvTlsConnection? = null

    suspend fun begin(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = openTvTls(host, 6467, identity)
            connection = c
            c.socket.outputStream.writePbFrame(PbWriter().apply {
                number(1, 2); number(2, 200)
                message(10) { string(1, "atvremote"); string(2, "Universal Remote") }
            }.result())

            while (true) {
                val reply = parsePb(c.socket.inputStream.readPbFrame())
                val status = reply.num(2) ?: 200L
                check(status == 200L) { "TV pairing error (status $status)" }
                when {
                    reply.has(11) -> c.socket.outputStream.writePbFrame(PbWriter().apply {
                        number(1, 2); number(2, 200)
                        message(20) {
                            message(1) { number(1, 3); number(2, 6) }
                            number(3, 1)
                        }
                    }.result())
                    reply.has(20) -> c.socket.outputStream.writePbFrame(PbWriter().apply {
                        number(1, 2); number(2, 200)
                        message(30) {
                            message(1) { number(1, 3); number(2, 6) }
                            number(2, 1)
                        }
                    }.result())
                    reply.has(31) -> return@runCatching
                    else -> error("Unexpected response from Google TV")
                }
            }
        }.onFailure { close() }
    }

    suspend fun finish(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = code.trim().uppercase()
            require(normalized.length == 6 && normalized.all { it in "0123456789ABCDEF" }) {
                "Enter the six-character code shown on the TV"
            }
            val c = connection ?: error("Start pairing first")
            val server = c.serverCertificate ?: error("Could not read the TV certificate")
            val clientKey = identity.certificate.publicKey as RSAPublicKey
            val serverKey = server.publicKey as RSAPublicKey
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(clientKey.modulus.unsigned())
                update(clientKey.publicExponent.unsigned())
                update(serverKey.modulus.unsigned())
                update(serverKey.publicExponent.unsigned())
                update(normalized.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }.digest()
            require((digest[0].toInt() and 255) == normalized.substring(0, 2).toInt(16)) {
                "That code doesn't match. Check the TV screen and try again."
            }
            c.socket.outputStream.writePbFrame(PbWriter().apply {
                number(1, 2); number(2, 200)
                message(40) { bytes(1, digest) }
            }.result())
            val ack = parsePb(c.socket.inputStream.readPbFrame())
            check((ack.num(2) ?: 200L) == 200L && ack.has(41)) { "Google TV rejected the pairing code" }
        }.also { close() }
    }

    override fun close() { connection?.close(); connection = null }
}

object AndroidTvKey {
    const val HOME = 3; const val BACK = 4
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22; const val OK = 23
    const val VOLUME_UP = 24; const val VOLUME_DOWN = 25; const val POWER = 26
    const val MENU = 82; const val PLAY = 126; const val PAUSE = 127; const val MUTE = 164
}

class AndroidTvRemote(
    private val host: String,
    private val identity: AndroidTvIdentity,
    private val onDisconnected: (String) -> Unit
) : Closeable {
    @Volatile private var ready = false
    private var connection: TvTlsConnection? = null
    private val writer = Executors.newSingleThreadExecutor()

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            closeSocket()
            val c = openTvTls(host, 6466, identity)
            connection = c
            val input = c.socket.inputStream
            while (!ready) {
                val msg = parsePb(input.readPbFrame())
                handle(msg)
            }
            c.socket.soTimeout = 0
            Thread({
                try {
                    while (ready) handle(parsePb(input.readPbFrame()))
                } catch (e: Exception) {
                    ready = false
                    onDisconnected(e.message ?: "Google TV disconnected")
                }
            }, "GoogleTV-reader").apply { isDaemon = true; start() }
            Unit
        }.onFailure { closeSocket() }
    }

    private fun handle(msg: Map<Int, List<PbValue>>) {
        when {
            msg.has(1) -> send(PbWriter().apply {
                message(1) {
                    number(1, 1 or 2 or 32 or 64 or 512)
                    message(2) { number(3, 1); string(4, "1"); string(5, "atvremote"); string(6, "1.0.0") }
                }
            }.result())
            msg.has(2) -> send(PbWriter().apply { message(2) { number(1, 1 or 2 or 32 or 64 or 512) } }.result())
            msg.has(8) -> {
                val ping = msg.nested(8)?.num(1) ?: 0L
                send(PbWriter().apply { message(9) { number(1, ping) } }.result())
            }
            msg.has(40) -> ready = true
        }
    }

    fun sendKey(keyCode: Int) {
        if (!ready) return
        send(PbWriter().apply { message(10) { number(1, keyCode); number(2, 3) } }.result())
    }

    /**
     * Launch an Android TV app through the Remote v2 app-link request.
     * The URL is resolved by the TV; packageName identifies the target app.
     */
    fun launchApp(packageName: String, appLink: String) {
        if (!ready) return
        send(PbWriter().apply {
            message(90) {
                string(1, appLink)
                string(2, packageName)
                number(3, 1)
            }
        }.result())
    }

    private fun send(payload: ByteArray) {
        val socket = connection?.socket ?: return
        runCatching { writer.execute { runCatching { socket.outputStream.writePbFrame(payload) }
            .onFailure { ready = false; onDisconnected(it.message ?: "Send failed") } } }
    }

    private fun closeSocket() {
        ready = false
        runCatching { connection?.close() }
        connection = null
    }

    override fun close() {
        closeSocket()
        writer.shutdownNow()
    }
}
