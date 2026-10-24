package no.oktodrive.app

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * Minimal Mumble control-protocol client.
 *
 * Uses the documented Mumble control frame: 2-byte message type,
 * 4-byte payload size, then protobuf payload, all over pinned TLS.
 * Audio, UDP and PTT are still intentionally not implemented.
 */
internal class MumbleClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val fingerprint: String,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String, connected: Boolean)
        fun onChannels(channels: List<Channel>)
        fun onActiveChannel(channelId: Int)
        fun onError(message: String)
    }

    data class Channel(val id: Int, val name: String, val parent: Int?)

    private val running = AtomicBoolean(false)
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var controlOutput: DataOutputStream? = null
    @Volatile private var ownSession: Int? = null
    @Volatile private var lastRequestedChannelId: Int? = null
    private val channels = sortedMapOf<Int, Channel>()
    private val userChannels = mutableMapOf<Int, Int>()

    fun connect() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            var wasConnected = false
            try {
                Log.i(TAG, "Connecting to $host:$port as '$username'")
                listener.onStatus("Kobler til Mumble …", false)
                val tls = ServerCertificate.openPinned(host, port, fingerprint)
                socket = tls
                tls.soTimeout = 1500
                val input = DataInputStream(tls.inputStream)
                val output = DataOutputStream(tls.outputStream)
                controlOutput = output
                send(output, 0, proto {
                    number(1, 0x010500)
                    string(2, "OktoDrive 0.1")
                    string(3, "Android")
                })
                send(output, 2, proto {
                    string(1, username)
                    if (password.isNotEmpty()) string(2, password)
                    number(5, 1) // Opus capability; audio remains disabled.
                })
                var lastPing = System.currentTimeMillis()
                while (running.get()) {
                    if (System.currentTimeMillis() - lastPing > 15000) {
                        send(output, 3, proto { number(1, System.currentTimeMillis()) })
                        lastPing = System.currentTimeMillis()
                    }
                    try {
                        val type = input.readUnsignedShort()
                        val length = input.readInt()
                        if (length < 0 || length > 2_000_000) throw IOException("Invalid Mumble frame size")
                        val payload = ByteArray(length)
                        input.readFully(payload)
                        when (type) {
                            4 -> {
                                val reason = fields(payload)[2]?.lastOrNull()?.asText()
                                    ?: "Serveren avviste innloggingen"
                                Log.w(TAG, "Server rejected login: $reason")
                                throw IOException(reason)
                            }
                            5 -> {
                                val sync = fields(payload)
                                ownSession = sync[1]?.firstOrNull()?.asNumber()?.toInt()
                                wasConnected = true
                                Log.i(TAG, "ServerSync received; ownSession=$ownSession")
                                try {
                                    OktoDriveApp.startConnectionProtection()
                                    Log.i(TAG, "Foreground connection protection started")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not start foreground protection", e)
                                }
                                listener.onStatus("Tilkoblet Mumble som $username", true)
                                listener.onChannels(channels.values.toList())
                                ownSession?.let { session ->
                                    userChannels[session]?.let(listener::onActiveChannel)
                                }
                            }
                            6 -> {
                                fields(payload)[1]?.firstOrNull()?.asNumber()?.toInt()?.let { channels.remove(it) }
                                listener.onChannels(channels.values.toList())
                            }
                            7 -> {
                                val f = fields(payload)
                                val id = f[1]?.firstOrNull()?.asNumber()?.toInt()
                                if (id != null) {
                                    val old = channels[id]
                                    val name = f[3]?.lastOrNull()?.asText() ?: old?.name ?: "Kanal $id"
                                    val parent = f[2]?.lastOrNull()?.asNumber()?.toInt() ?: old?.parent
                                    channels[id] = Channel(id, name, parent)
                                    Log.d(TAG, "ChannelState id=$id name='$name' parent=$parent")
                                    listener.onChannels(channels.values.toList())
                                }
                            }
                            8 -> {
                                fields(payload)[1]?.firstOrNull()?.asNumber()?.toInt()?.let { userChannels.remove(it) }
                            }
                            9 -> {
                                val f = fields(payload)
                                val session = f[1]?.firstOrNull()?.asNumber()?.toInt()
                                val channelId = f[5]?.lastOrNull()?.asNumber()?.toInt()
                                if (session != null && channelId != null) {
                                    userChannels[session] = channelId
                                    if (session == ownSession) {
                                        Log.i(
                                            TAG,
                                            "Own UserState confirmed channel=$channelId requested=$lastRequestedChannelId"
                                        )
                                        if (channelId == lastRequestedChannelId) {
                                            lastRequestedChannelId = null
                                        }
                                        listener.onActiveChannel(channelId)
                                    }
                                }
                            }
                            12 -> {
                                val f = fields(payload)
                                val permission = f[1]?.lastOrNull()?.asNumber()
                                val channelId = f[2]?.lastOrNull()?.asNumber()?.toInt()
                                val deniedSession = f[3]?.lastOrNull()?.asNumber()?.toInt()
                                val reason = f[4]?.lastOrNull()?.asText()?.takeIf { it.isNotBlank() }
                                val denyType = f[5]?.lastOrNull()?.asNumber()?.toInt()
                                val invalidName = f[6]?.lastOrNull()?.asText()?.takeIf { it.isNotBlank() }
                                val channelName = channelId?.let { channels[it]?.name }
                                val detail = buildString {
                                    append("type=${denyType ?: "?"}")
                                    append(", channel=${channelId ?: "?"}")
                                    append(", session=${deniedSession ?: "?"}")
                                    append(", permission=${permission ?: "?"}")
                                    if (invalidName != null) append(", name='$invalidName'")
                                }
                                Log.w(TAG, "PermissionDenied $detail reason=${reason ?: "<none>"}")
                                listener.onError(
                                    when {
                                        reason != null && channelName != null ->
                                            "Kan ikke gå inn i $channelName: $reason\n\nMumble: $detail"
                                        reason != null ->
                                            "Mumble-serveren avviste handlingen: $reason\n\nMumble: $detail"
                                        channelName != null ->
                                            "Du har ikke tilgang til kanalen $channelName.\n\nMumble: $detail"
                                        else ->
                                            "Mumble-serveren avviste kanalbyttet.\n\nMumble: $detail"
                                    }
                                )
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Timeouts allow periodic ping and clean shutdown.
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mumble connection failed: ${e.javaClass.simpleName}: ${e.localizedMessage}", e)
                if (running.get()) {
                    listener.onError(
                        "${e.javaClass.simpleName}: ${e.localizedMessage ?: "Ukjent tilkoblingsfeil"}"
                    )
                }
            } finally {
                running.set(false)
                controlOutput = null
                ownSession = null
                lastRequestedChannelId = null
                userChannels.clear()
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                try {
                    OktoDriveApp.stopConnectionProtection()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not stop foreground protection", e)
                }
                Log.i(TAG, "Mumble connection closed")
                listener.onStatus(if (wasConnected) "Frakoblet" else "Ikke tilkoblet", false)
            }
        }, "OktoDrive-Mumble").start()
    }

    fun switchChannel(channelId: Int): Boolean {
        val output = controlOutput ?: return false
        val session = ownSession ?: return false
        if (!running.get()) return false
        return try {
            lastRequestedChannelId = channelId
            Log.i(TAG, "Sending UserState channel switch: session=$session channel=$channelId")
            // UserState: field 1 = our session, field 5 = requested channel_id.
            // The server confirms the move by broadcasting UserState back to us.
            send(output, 9, proto {
                number(1, session)
                number(5, channelId)
            })
            true
        } catch (e: Exception) {
            lastRequestedChannelId = null
            Log.e(TAG, "Channel switch write failed", e)
            listener.onError(
                "Kanalbytte feilet ved sending: ${e.javaClass.simpleName}: " +
                    (e.localizedMessage ?: "ukjent feil")
            )
            false
        }
    }

    fun disconnect() {
        Log.i(TAG, "Disconnect requested")
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun send(out: DataOutputStream, type: Int, payload: ByteArray) {
        synchronized(out) {
            out.writeShort(type)
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    private fun proto(build: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().apply(build).bytes()

    private class ProtoWriter {
        private val out = ByteArrayOutputStream()
        fun number(field: Int, value: Long) {
            varint((field * 8).toLong())
            varint(value)
        }
        fun number(field: Int, value: Int) = number(field, value.toLong())
        fun string(field: Int, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            varint((field * 8 + 2).toLong())
            varint(bytes.size.toLong())
            out.write(bytes)
        }
        private fun varint(value: Long) {
            var v = value
            while (v and -128L != 0L) {
                out.write(((v and 127L) or 128L).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private data class Value(val data: ByteArray?, val number: Long?) {
        fun asText(): String = String(data ?: ByteArray(0), StandardCharsets.UTF_8)
        fun asNumber(): Long = number ?: 0
    }

    private fun fields(data: ByteArray): Map<Int, List<Value>> {
        val result = mutableMapOf<Int, MutableList<Value>>()
        var i = 0
        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (i < data.size && shift < 70) {
                val b = data[i++].toInt() and 0xff
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            throw IOException("Invalid protobuf varint")
        }
        while (i < data.size) {
            val tag = readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 0) throw IOException("Invalid protobuf field")
            val v = when (wire) {
                0 -> Value(null, readVarint())
                1 -> { if (i + 8 > data.size) throw EOFException(); i += 8; continue }
                2 -> {
                    val len = readVarint()
                    if (len < 0 || len > data.size - i) throw IOException("Invalid protobuf length")
                    val b = data.copyOfRange(i, i + len.toInt())
                    i += len.toInt()
                    Value(b, null)
                }
                5 -> { if (i + 4 > data.size) throw EOFException(); i += 4; continue }
                else -> throw IOException("Unsupported protobuf wire type $wire")
            }
            result.getOrPut(field) { mutableListOf() }.add(v)
        }
        return result
    }

    companion object {
        private const val TAG = "OktoDriveMumble"
    }
}
